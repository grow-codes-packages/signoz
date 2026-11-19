package codes.grow.packages.signoz;

import codes.grow.packages.signoz.core.ConfigException;
import codes.grow.packages.signoz.tracing.OtlpExporters;
import codes.grow.packages.signoz.tracing.SpanExporterFactory;
import codes.grow.packages.signoz.tracing.Tracing;
import codes.grow.packages.signoz.tracing.TracingConfig;
import codes.grow.packages.signoz.tracing.TracingHandle;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OpenTelemetry tracing that exports spans to the SigNoz collector over OTLP (gRPC or HTTP
 * protobuf). An API key is not needed; SigNoz Cloud gets the {@code signoz-ingestion-key} header.
 */
public final class SignozTracing {
  private SignozTracing() {}

  /**
   * Starts tracing with the collector settings from the environment ({@link
   * SignozConfig#fromEnv()}: {@code GROW_SIGNOZ_OTLP_ENDPOINT}, {@code SIGNOZ_OTLP_ENDPOINT}, ...)
   * unless {@code tracing} sets them.
   *
   * @param tracing tracing configuration
   * @return the handle
   */
  public static TracingHandle initTracing(TracingConfig tracing) {
    return initTracing(SignozConfig.fromEnv(), tracing);
  }

  /**
   * Starts tracing with the collector settings of {@code config} (see {@link #tracingConfigFor}).
   *
   * @param config SigNoz configuration
   * @param tracing tracing configuration
   * @return the handle
   */
  public static TracingHandle initTracing(SignozConfig config, TracingConfig tracing) {
    return Tracing.initWith(tracingConfigFor(config, tracing), exporterFactory());
  }

  /**
   * The span exporter factory: the OTLP exporters of OpenTelemetry Java.
   *
   * @return factory
   */
  public static SpanExporterFactory exporterFactory() {
    return OtlpExporters.factory();
  }

  /**
   * Fills the export settings {@code tracing} leaves empty from {@code config}.
   *
   * <ul>
   *   <li>Endpoint and protocol: without an explicit endpoint, the config's protocol and collector
   *       endpoint are used when {@code tracing} keeps the default protocol (HTTP protobuf) or
   *       picks the config's protocol ({@code /v1/traces} is appended for HTTP). A tracing protocol
   *       of gRPC with an HTTP config uses {@link SignozConfig#DEFAULT_OTLP_GRPC_ENDPOINT}.
   *   <li>TLS: the config's TLS options when {@code tracing} has none.
   *   <li>Headers: config headers, then the ingestion key, then the tracing headers (which win).
   * </ul>
   *
   * @param config SigNoz configuration
   * @param tracing tracing configuration
   * @return the completed tracing configuration
   * @throws ConfigException for the {@code native} protocol (SigNoz ingests OTLP only)
   */
  public static TracingConfig tracingConfigFor(SignozConfig config, TracingConfig tracing) {
    if (tracing.protocol() == TracingConfig.Protocol.NATIVE) {
      throw new ConfigException(
          "tracing: SigNoz ingests OTLP only (protocol grpc or http/protobuf)");
    }
    TracingConfig.Builder b = tracing.toBuilder();
    if (tracing.endpoint().isEmpty()) {
      TracingConfig.Protocol cfgProtocol =
          config.otlpProtocol() == OtlpProtocol.GRPC
              ? TracingConfig.Protocol.GRPC
              : TracingConfig.Protocol.HTTP_PROTOBUF;
      TracingConfig.Protocol protocol = tracing.protocol();
      String endpoint;
      if (protocol == cfgProtocol || protocol == TracingConfig.Protocol.HTTP_PROTOBUF) {
        protocol = cfgProtocol;
        endpoint = config.otlpEndpoint();
      } else {
        endpoint = SignozConfig.DEFAULT_OTLP_GRPC_ENDPOINT;
      }
      if (protocol == TracingConfig.Protocol.HTTP_PROTOBUF) {
        endpoint = SignozLogExporter.otlpHttpUrl(endpoint, "traces");
      }
      b.protocol(protocol).endpoint(endpoint);
    }
    if (tracing.tls().isEmpty() && config.tls().isCustom()) {
      b.tls(config.tls());
    }
    Map<String, String> headers = new LinkedHashMap<>(config.headers());
    config
        .ingestionKey()
        .ifPresent(k -> headers.put(SignozConfig.INGESTION_KEY_HEADER, k.reveal()));
    headers.putAll(tracing.headers());
    b.headers(headers);
    return b.build();
  }
}
