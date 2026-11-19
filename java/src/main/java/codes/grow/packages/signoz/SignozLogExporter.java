package codes.grow.packages.signoz;

import codes.grow.packages.signoz.core.CancelledException;
import codes.grow.packages.signoz.core.HttpTransport;
import codes.grow.packages.signoz.core.Json;
import codes.grow.packages.signoz.core.Redaction;
import codes.grow.packages.signoz.core.TransportRequest;
import codes.grow.packages.signoz.core.TransportResponse;
import codes.grow.packages.signoz.core.ValidationException;
import codes.grow.packages.signoz.logging.ExportOptions;
import codes.grow.packages.signoz.logging.ExportResult;
import codes.grow.packages.signoz.logging.LogExporter;
import codes.grow.packages.signoz.logging.LogRecord;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPOutputStream;

/**
 * {@link LogExporter} that sends records to the SigNoz collector over OTLP/HTTP protobuf ({@code
 * POST <endpoint>/v1/logs}) or OTLP/gRPC ({@code LogsService/Export}), with the {@code
 * signoz-ingestion-key} header when an ingestion key is configured. Retries are left to the {@code
 * BatchProcessor}; a collector partial success is reported as rejected records with a {@code
 * ValidationException}. No API key is needed.
 */
public final class SignozLogExporter implements LogExporter, AutoCloseable {
  private static final String OP = "logs.export";

  private final OtlpProtocol protocol;
  private final String endpoint;
  private final HttpTransport http;
  private final String path;
  private final GrpcLogSender grpc;
  private final SignozConfig config;
  private final AtomicBoolean closed = new AtomicBoolean();

  private SignozLogExporter(SignozConfig config) {
    this.config = config;
    this.protocol = config.otlpProtocol();
    Map<String, String> headers = new LinkedHashMap<>(config.headers());
    config
        .ingestionKey()
        .ifPresent(k -> headers.put(SignozConfig.INGESTION_KEY_HEADER, k.reveal()));
    if (protocol == OtlpProtocol.GRPC) {
      this.endpoint = config.otlpEndpoint();
      Map<String, String> md = new LinkedHashMap<>();
      headers.forEach((k, v) -> md.put(k.toLowerCase(Locale.ROOT), v));
      this.grpc =
          new GrpcLogSender(
              endpoint,
              config.tls(),
              config.proxy().orElse(null),
              config.connectTimeout(),
              md,
              config.transportConfig(config.url(), Map.of()).userAgent(),
              config.knownSecrets());
      this.http = null;
      this.path = null;
    } else {
      this.endpoint = otlpHttpUrl(config.otlpEndpoint(), "logs");
      this.http = new HttpTransport(config.transportConfig(endpoint, headers));
      this.path = "";
      this.grpc = null;
    }
  }

  /**
   * Builds an exporter from the configuration ({@code otlpEndpoint}, {@code otlpProtocol}, {@code
   * ingestionKey}, TLS, proxy, headers, timeouts, hooks and logger).
   *
   * @param config configuration
   * @return the exporter
   */
  public static SignozLogExporter create(SignozConfig config) {
    config.validate();
    return new SignozLogExporter(config);
  }

  /**
   * Appends {@code /v1/<signal>} to the endpoint path unless it already ends with it.
   *
   * @param endpoint collector endpoint
   * @param signal {@code logs} or {@code traces}
   * @return the OTLP/HTTP URL
   */
  public static String otlpHttpUrl(String endpoint, String signal) {
    String suffix = "/v1/" + signal;
    URI u = URI.create(endpoint);
    String p = u.getRawPath() == null ? "" : u.getRawPath();
    if (p.endsWith(suffix)) {
      return endpoint;
    }
    String base = endpoint;
    int q = base.indexOf('?');
    if (q >= 0) {
      base = base.substring(0, q);
    }
    return base.replaceAll("/+$", "") + suffix;
  }

  /**
   * Returns the protocol.
   *
   * @return the protocol
   */
  public OtlpProtocol protocol() {
    return protocol;
  }

  /**
   * Returns the export URL (OTLP/HTTP) or the collector endpoint (gRPC).
   *
   * @return the endpoint
   */
  public String endpoint() {
    return endpoint;
  }

  /**
   * Sends one batch.
   *
   * @param records records
   * @param options gzip flag and time budget
   * @return a future result (rejected records from a partial success)
   */
  @Override
  public CompletableFuture<ExportResult> export(List<LogRecord> records, ExportOptions options) {
    if (closed.get()) {
      return CompletableFuture.failedFuture(new CancelledException(OP + ": exporter is shut down"));
    }
    if (records == null || records.isEmpty()) {
      return CompletableFuture.completedFuture(ExportResult.success(0));
    }
    int n = records.size();
    return SignozApi.defer(
        () -> {
          byte[] body = OtlpLogs.encode(records);
          CompletableFuture<OtlpLogs.PartialSuccess> sent =
              protocol == OtlpProtocol.GRPC
                  ? grpc.export(body, options.gzip(), options.timeout())
                  : exportHttp(body, options);
          return sent.thenApply(ps -> result(n, ps));
        });
  }

  private ExportResult result(int n, OtlpLogs.PartialSuccess ps) {
    if (ps.rejected() <= 0) {
      return ExportResult.success(n);
    }
    int rej = (int) Math.min(ps.rejected(), n);
    String msg = Redaction.redactText(ps.message(), config.knownSecrets());
    return ExportResult.failure(
        n - rej,
        rej,
        new ValidationException(
            OP
                + ": collector rejected "
                + rej
                + " log records"
                + (msg.isEmpty() ? "" : ": " + msg)));
  }

  static byte[] gzip(byte[] body) {
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    try (GZIPOutputStream z = new GZIPOutputStream(buf)) {
      z.write(body);
    } catch (IOException e) {
      throw new IllegalStateException("gzip failed", e);
    }
    return buf.toByteArray();
  }

  private CompletableFuture<OtlpLogs.PartialSuccess> exportHttp(byte[] body, ExportOptions o) {
    TransportRequest.Builder b =
        TransportRequest.post(path)
            .header("Accept", "application/x-protobuf")
            .maxRetries(0)
            .operation(OP);
    if (o.gzip()) {
      b.body(gzip(body), "application/x-protobuf").header("Content-Encoding", "gzip");
    } else {
      b.body(body, "application/x-protobuf");
    }
    if (!o.timeout().isZero() && !o.timeout().isNegative()) {
      b.timeout(o.timeout());
    }
    return http.sendAsync(b.build()).thenApply(SignozLogExporter::partialFromHttp);
  }

  /** Reads {@code partial_success} from a protobuf or JSON export response. */
  static OtlpLogs.PartialSuccess partialFromHttp(TransportResponse r) {
    byte[] body = r.body();
    if (body.length == 0) {
      return new OtlpLogs.PartialSuccess(0, "");
    }
    if (r.header("Content-Type").orElse("").contains("json")) {
      try {
        JsonNode ps = Json.tree(body).path("partialSuccess");
        return new OtlpLogs.PartialSuccess(
            Rows.num(ps, "rejectedLogRecords").orElse(0), Rows.str(ps, "errorMessage"));
      } catch (ValidationException e) {
        return new OtlpLogs.PartialSuccess(0, "");
      }
    }
    return OtlpLogs.partialSuccess(body);
  }

  /**
   * Closes connections. Idempotent.
   *
   * @return a completed future
   */
  @Override
  public CompletableFuture<Void> shutdown() {
    if (closed.compareAndSet(false, true)) {
      if (http != null) {
        http.close();
      }
      if (grpc != null) {
        grpc.close();
      }
    }
    return CompletableFuture.completedFuture(null);
  }

  /** Same as {@link #shutdown()}. */
  @Override
  public void close() {
    shutdown();
  }

  @Override
  public String toString() {
    return "SignozLogExporter{" + protocol.wireValue() + " " + Redaction.redactUrl(endpoint) + "}";
  }
}
