package codes.grow.packages.signoz;

import codes.grow.packages.signoz.core.CancelledException;
import codes.grow.packages.signoz.core.ErrorCode;
import codes.grow.packages.signoz.core.Redaction;
import codes.grow.packages.signoz.core.SignozException;
import codes.grow.packages.signoz.core.TimeoutException;
import codes.grow.packages.signoz.core.TlsOptions;
import codes.grow.packages.signoz.core.UnavailableException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Unary gRPC call of {@code opentelemetry.proto.collector.logs.v1.LogsService/Export} over OkHttp
 * HTTP/2 (prior knowledge for {@code http://}, ALPN for {@code https://}), the transport the
 * OpenTelemetry Java OTLP exporters use.
 */
final class GrpcLogSender {
  /** gRPC method path of the OTLP logs service. */
  static final String METHOD = "/opentelemetry.proto.collector.logs.v1.LogsService/Export";

  private static final MediaType GRPC = MediaType.get("application/grpc");

  private final OkHttpClient http;
  private final String url;
  private final Map<String, String> metadata;
  private final String userAgent;
  private final Collection<String> secrets;

  GrpcLogSender(
      String endpoint,
      TlsOptions tls,
      String proxy,
      Duration connectTimeout,
      Map<String, String> metadata,
      String userAgent,
      Collection<String> secrets) {
    URI u = URI.create(endpoint);
    boolean https = "https".equalsIgnoreCase(u.getScheme());
    int port = u.getPort() > 0 ? u.getPort() : https ? 443 : 80;
    this.url = (https ? "https" : "http") + "://" + u.getHost() + ":" + port + METHOD;
    OkHttpClient.Builder b =
        new OkHttpClient.Builder()
            .protocols(
                https
                    ? List.of(Protocol.HTTP_2, Protocol.HTTP_1_1)
                    : List.of(Protocol.H2_PRIOR_KNOWLEDGE))
            .connectTimeout(connectTimeout)
            .retryOnConnectionFailure(false);
    if (https && tls.isCustom()) {
      b.sslSocketFactory(
          tls.sslContext().getSocketFactory(), trustManager(tls.insecureSkipVerify()));
      if (tls.insecureSkipVerify()) {
        b.hostnameVerifier((host, session) -> true);
      }
    }
    if (proxy != null) {
      URI p = URI.create(proxy);
      int pp = p.getPort() > 0 ? p.getPort() : 80;
      b.proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(p.getHost(), pp)));
    }
    this.http = b.build();
    this.metadata = Map.copyOf(metadata);
    this.userAgent = userAgent;
    this.secrets = secrets;
  }

  /**
   * Trust manager handed to OkHttp next to the SSL socket factory (the handshake itself uses the
   * socket factory of {@link TlsOptions#sslContext()}).
   */
  static X509TrustManager trustManager(boolean insecure) {
    if (insecure) {
      return new X509TrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {}

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {}

        @Override
        public X509Certificate[] getAcceptedIssuers() {
          return new X509Certificate[0];
        }
      };
    }
    try {
      TrustManagerFactory f =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      f.init((java.security.KeyStore) null);
      return (X509TrustManager) f.getTrustManagers()[0];
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException("tls: no default trust manager", e);
    }
  }

  String url() {
    return url;
  }

  /** Length-prefixed gRPC message (optionally gzip-compressed). */
  static byte[] frame(byte[] message, boolean gzip) {
    byte[] payload = gzip ? SignozLogExporter.gzip(message) : message;
    byte[] out = new byte[5 + payload.length];
    out[0] = (byte) (gzip ? 1 : 0);
    int n = payload.length;
    out[1] = (byte) (n >>> 24);
    out[2] = (byte) (n >>> 16);
    out[3] = (byte) (n >>> 8);
    out[4] = (byte) n;
    System.arraycopy(payload, 0, out, 5, n);
    return out;
  }

  /**
   * Sends one export request.
   *
   * @param message encoded {@code ExportLogsServiceRequest}
   * @param gzip compress the message
   * @param timeout call timeout
   * @return a future with the response partial success
   */
  CompletableFuture<OtlpLogs.PartialSuccess> export(
      byte[] message, boolean gzip, Duration timeout) {
    Request.Builder rb =
        new Request.Builder()
            .url(url)
            .header("te", "trailers")
            .header("user-agent", userAgent)
            .post(RequestBody.create(frame(message, gzip), GRPC));
    if (gzip) {
      rb.header("grpc-encoding", "gzip");
    }
    if (!timeout.isZero() && !timeout.isNegative()) {
      rb.header("grpc-timeout", Math.max(1, timeout.toMillis()) + "m");
    }
    metadata.forEach(rb::header);
    Call call =
        http.newBuilder()
            .callTimeout(timeout.isNegative() ? Duration.ZERO : timeout)
            .build()
            .newCall(rb.build());
    CompletableFuture<OtlpLogs.PartialSuccess> result = new CompletableFuture<>();
    result.whenComplete(
        (r, t) -> {
          if (result.isCancelled()) {
            call.cancel();
          }
        });
    call.enqueue(
        new Callback() {
          @Override
          public void onFailure(Call c, IOException e) {
            result.completeExceptionally(networkError(e, c.isCanceled()));
          }

          @Override
          public void onResponse(Call c, Response response) {
            try (Response r = response) {
              byte[] body = r.body().bytes();
              Headers trailers = r.trailers();
              String status = first(trailers.get("grpc-status"), r.header("grpc-status"));
              String msg = first(trailers.get("grpc-message"), r.header("grpc-message"));
              result.complete(interpret(r.code(), status, msg, body, secrets));
            } catch (IOException e) {
              result.completeExceptionally(networkError(e, c.isCanceled()));
            } catch (RuntimeException e) {
              result.completeExceptionally(e);
            }
          }
        });
    return result;
  }

  private static String first(String a, String b) {
    return a != null ? a : b;
  }

  SignozException networkError(IOException e, boolean cancelled) {
    String msg = Redaction.redactText(String.valueOf(e.getMessage()), secrets);
    if (e instanceof InterruptedIOException) {
      return new TimeoutException("logs.export: gRPC call timed out", null, null, null, null);
    }
    if (cancelled) {
      return new CancelledException("logs.export: cancelled", e);
    }
    return new UnavailableException(
        "logs.export: gRPC call failed: " + msg, null, null, null, null);
  }

  /**
   * Interprets a gRPC response: HTTP status, {@code grpc-status} / {@code grpc-message} (from the
   * trailers or a trailers-only response) and the framed response message.
   *
   * @param httpStatus HTTP status
   * @param grpcStatus grpc-status value or {@code null}
   * @param grpcMessage percent-encoded grpc-message or {@code null}
   * @param body response body
   * @param secrets secret values to redact
   * @return the partial success
   * @throws SignozException mapped from the gRPC status
   */
  static OtlpLogs.PartialSuccess interpret(
      int httpStatus,
      String grpcStatus,
      String grpcMessage,
      byte[] body,
      Collection<String> secrets) {
    String op = "logs.export";
    if (grpcStatus == null) {
      if (httpStatus != 200) {
        throw SignozException.of(
            ErrorCode.forStatus(httpStatus),
            op + ": gRPC endpoint answered HTTP " + httpStatus,
            httpStatus,
            null,
            null,
            null);
      }
      throw new UnavailableException(op + ": gRPC response without grpc-status");
    }
    int code;
    try {
      code = Integer.parseInt(grpcStatus.trim());
    } catch (NumberFormatException e) {
      code = 2;
    }
    if (code != 0) {
      String text = decodeMessage(grpcMessage);
      text = Redaction.truncate(Redaction.redactText(text, secrets), Redaction.MAX_VENDOR_TEXT);
      throw SignozException.of(
          grpcCode(code),
          op + ": " + (text.isEmpty() ? "gRPC error" : text) + " (gRPC " + code + ")",
          null,
          null,
          null,
          null);
    }
    if (body.length < 5 || body[0] != 0) {
      return new OtlpLogs.PartialSuccess(0, "");
    }
    int n =
        ((body[1] & 0xFF) << 24)
            | ((body[2] & 0xFF) << 16)
            | ((body[3] & 0xFF) << 8)
            | (body[4] & 0xFF);
    if (n < 0 || 5 + n > body.length) {
      return new OtlpLogs.PartialSuccess(0, "");
    }
    byte[] msg = new byte[n];
    System.arraycopy(body, 5, msg, 0, n);
    return OtlpLogs.partialSuccess(msg);
  }

  static String decodeMessage(String m) {
    if (m == null) {
      return "";
    }
    try {
      return URLDecoder.decode(m.replace("+", "%2B"), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      return m;
    }
  }

  /** Maps a gRPC status code to the SDK error code. */
  static ErrorCode grpcCode(int code) {
    switch (code) {
      case 1:
        return ErrorCode.CANCELLED;
      case 3:
      case 9:
      case 11:
        return ErrorCode.VALIDATION;
      case 4:
        return ErrorCode.TIMEOUT;
      case 5:
        return ErrorCode.NOT_FOUND;
      case 7:
        return ErrorCode.PERMISSION_DENIED;
      case 8:
        return ErrorCode.RATE_LIMITED;
      case 12:
        return ErrorCode.UNSUPPORTED;
      case 16:
        return ErrorCode.AUTHENTICATION;
      default:
        return ErrorCode.UNAVAILABLE;
    }
  }

  /** Releases connections and threads. */
  void close() {
    http.dispatcher().executorService().shutdown();
    http.connectionPool().evictAll();
  }
}
