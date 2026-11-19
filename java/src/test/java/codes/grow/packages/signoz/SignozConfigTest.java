package codes.grow.packages.signoz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import codes.grow.packages.signoz.core.ConfigException;
import codes.grow.packages.signoz.core.EnvReader;
import codes.grow.packages.signoz.core.MockServer;
import codes.grow.packages.signoz.core.Secret;
import codes.grow.packages.signoz.core.TlsOptions;
import codes.grow.packages.signoz.tracing.TracingConfig;
import codes.grow.packages.signoz.tracing.TracingHandle;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SignozConfigTest {
  private static final String P = EnvReader.DEFAULT_PREFIX;

  @Test
  void vendorVariablesAndPrecedence() {
    Map<String, String> env = new HashMap<>();
    env.put("SIGNOZ_URL", "http://vendor:8080");
    env.put("SIGNOZ_API_KEY", "vendor-key-1234");
    env.put("SIGNOZ_OTLP_ENDPOINT", "http://collector:4318");
    env.put("OTEL_EXPORTER_OTLP_ENDPOINT", "http://otel:4318");
    env.put("SIGNOZ_INGESTION_KEY", "vendor-ingest-1234");
    env.put("OTEL_EXPORTER_OTLP_PROTOCOL", "GRPC");
    SignozConfig c = SignozConfig.fromEnv(env);
    assertThat(c.url()).isEqualTo("http://vendor:8080");
    assertThat(c.apiKey().map(Secret::reveal)).contains("vendor-key-1234");
    assertThat(c.otlpEndpoint()).isEqualTo("http://collector:4318");
    assertThat(c.ingestionKey().map(Secret::reveal)).contains("vendor-ingest-1234");
    assertThat(c.otlpProtocol()).isEqualTo(OtlpProtocol.GRPC);
    env.put(P + "URL", "http://grow:1");
    env.put(P + "API_KEY", "grow-key-1234");
    env.put(P + "OTLP_ENDPOINT", "http://grow-collector:4317");
    env.put(P + "INGESTION_KEY", "grow-ingest-1234");
    env.put(P + "OTLP_PROTOCOL", "http/protobuf");
    SignozConfig g = SignozConfig.fromEnv(env);
    assertThat(g.url()).isEqualTo("http://grow:1");
    assertThat(g.apiKey().map(Secret::reveal)).contains("grow-key-1234");
    assertThat(g.otlpEndpoint()).isEqualTo("http://grow-collector:4317");
    assertThat(g.ingestionKey().map(Secret::reveal)).contains("grow-ingest-1234");
    assertThat(g.otlpProtocol()).isEqualTo(OtlpProtocol.HTTP_PROTOBUF);
    SignozConfig explicit =
        SignozConfig.builder()
            .apiKey(Secret.of("explicit-key-1"))
            .ingestionKey(Secret.of("explicit-ingest-1"))
            .otlpEndpoint("http://e:1")
            .otlpProtocol(OtlpProtocol.GRPC)
            .fromEnv(EnvReader.of(env))
            .build();
    assertThat(explicit.apiKey().map(Secret::reveal)).contains("explicit-key-1");
    assertThat(explicit.ingestionKey().map(Secret::reveal)).contains("explicit-ingest-1");
    assertThat(explicit.otlpEndpoint()).isEqualTo("http://e:1");
    assertThat(explicit.otlpProtocol()).isEqualTo(OtlpProtocol.GRPC);
    // OTEL_EXPORTER_OTLP_ENDPOINT is the last fallback
    SignozConfig otel =
        SignozConfig.fromEnv(Map.of("OTEL_EXPORTER_OTLP_ENDPOINT", "http://otel:4318"));
    assertThat(otel.otlpEndpoint()).isEqualTo("http://otel:4318");
  }

  @Test
  void defaultsAndValidation() {
    SignozConfig d = SignozConfig.fromEnv(Map.of());
    assertThat(d.url()).isEqualTo(SignozConfig.DEFAULT_URL);
    assertThat(d.otlpEndpoint()).isEqualTo(SignozConfig.DEFAULT_OTLP_HTTP_ENDPOINT);
    assertThat(d.otlpProtocol()).isEqualTo(OtlpProtocol.HTTP_PROTOBUF);
    assertThat(d.apiKey()).isEmpty();
    assertThat(d.ingestionKey()).isEmpty();
    assertThat(SignozConfig.builder().otlpProtocol(OtlpProtocol.GRPC).build().otlpEndpoint())
        .isEqualTo(SignozConfig.DEFAULT_OTLP_GRPC_ENDPOINT);
    assertThatThrownBy(() -> SignozConfig.builder().otlpEndpoint("ftp://x").build())
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("otlpEndpoint");
    assertThatThrownBy(() -> SignozConfig.builder().otlpEndpoint("http://[").build())
        .isInstanceOf(ConfigException.class);
    assertThatThrownBy(() -> SignozConfig.fromEnv(Map.of(P + "OTLP_PROTOCOL", "native-secret")))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining(P + "OTLP_PROTOCOL")
        .message()
        .doesNotContain("native-secret");
    assertThatThrownBy(() -> SignozConfig.fromEnv(Map.of("OTEL_EXPORTER_OTLP_PROTOCOL", "x")))
        .hasMessageContaining("OTEL_EXPORTER_OTLP_PROTOCOL");
    assertThatThrownBy(() -> OtlpProtocol.parse(null)).isInstanceOf(ConfigException.class);
    assertThat(OtlpProtocol.parse(" grpc ")).isEqualTo(OtlpProtocol.GRPC);
    assertThat(OtlpProtocol.GRPC.wireValue()).isEqualTo("grpc");
    assertThat(Signal.TRACES.wireValue()).isEqualTo("traces");
    assertThat(FilterOperator.LTE.symbol()).isEqualTo("<=");
    assertThat(
            SignozConfig.builder()
                .apiKey((String) null)
                .ingestionKey((String) null)
                .build()
                .apiKey())
        .isEmpty();
  }

  @Test
  void redactionAndKnownSecrets() {
    SignozConfig c =
        SignozConfig.builder()
            .apiKey("api-key-secret-1")
            .ingestionKey("ingest-key-secret-1")
            .header("Authorization", "Bearer header-secret-1")
            .header("X-Plain", "visible")
            .header("X-Token", "")
            .otlpEndpoint("http://u:pw@collector:4318")
            .build();
    assertThat(c.toString())
        .doesNotContain("api-key-secret-1")
        .doesNotContain("ingest-key-secret-1")
        .doesNotContain("header-secret-1")
        .doesNotContain(":pw@")
        .contains("apiKey=[REDACTED]")
        .contains("ingestionKey=[REDACTED]")
        .contains("otlpProtocol=http/protobuf");
    assertThat(c.knownSecrets())
        .containsExactly("api-key-secret-1", "ingest-key-secret-1", "Bearer header-secret-1");
    assertThat(SignozConfig.builder().build().toString()).contains("ingestionKey=null");
    SignozConfig copy = c.toBuilder().url("http://other:1").build();
    assertThat(copy.apiKey()).isEqualTo(c.apiKey());
    assertThat(copy.headers()).isEqualTo(c.headers());
    assertThat(copy.url()).isEqualTo("http://other:1");
    assertThat(SignozConfig.builder().apiKey("").build().knownSecrets()).isEmpty();
  }

  @Test
  void tracingConfigForFillsExportSettings() {
    SignozConfig http =
        SignozConfig.builder()
            .otlpEndpoint("http://collector:4318")
            .ingestionKey("ingest-key-secret-1")
            .header("X-A", "1")
            .build();
    TracingConfig t = SignozTracing.tracingConfigFor(http, TracingConfig.builder("svc").build());
    assertThat(t.protocol()).isEqualTo(TracingConfig.Protocol.HTTP_PROTOBUF);
    assertThat(t.endpoint()).contains("http://collector:4318/v1/traces");
    assertThat(t.headers())
        .containsEntry("signoz-ingestion-key", "ingest-key-secret-1")
        .containsEntry("X-A", "1");
    TracingConfig grpcOnHttp =
        SignozTracing.tracingConfigFor(
            http,
            TracingConfig.builder("svc")
                .protocol(TracingConfig.Protocol.GRPC)
                .header("X-A", "2")
                .build());
    assertThat(grpcOnHttp.endpoint()).contains(SignozConfig.DEFAULT_OTLP_GRPC_ENDPOINT);
    assertThat(grpcOnHttp.headers()).containsEntry("X-A", "2");
    SignozConfig grpc =
        SignozConfig.builder()
            .otlpProtocol(OtlpProtocol.GRPC)
            .otlpEndpoint("http://collector:4317")
            .tls(TlsOptions.builder().insecureSkipVerify(true).build())
            .build();
    TracingConfig g = SignozTracing.tracingConfigFor(grpc, TracingConfig.builder("svc").build());
    assertThat(g.protocol()).isEqualTo(TracingConfig.Protocol.GRPC);
    assertThat(g.endpoint()).contains("http://collector:4317");
    assertThat(g.tls()).isPresent();
    TracingConfig explicit =
        SignozTracing.tracingConfigFor(
            grpc, TracingConfig.builder("svc").endpoint("http://x:4318/custom").build());
    assertThat(explicit.endpoint()).contains("http://x:4318/custom");
    assertThat(explicit.protocol()).isEqualTo(TracingConfig.Protocol.HTTP_PROTOBUF);
    assertThatThrownBy(
            () ->
                SignozTracing.tracingConfigFor(
                    http,
                    TracingConfig.builder("svc").protocol(TracingConfig.Protocol.NATIVE).build()))
        .isInstanceOf(ConfigException.class);
    assertThat(SignozTracing.exporterFactory()).isNotNull();
  }

  @Test
  void initTracingIsShutDownWithTheClient() throws Exception {
    try (MockServer server = MockServer.start()) {
      SignozClient client =
          SignozClient.builder()
              .url(server.url())
              .otlpEndpoint(server.url())
              .ingestionKey(Secret.of("ingest-key-1"))
              .apiKey(Secret.of("api-key-1"))
              .build();
      TracingHandle h = client.initTracing(TracingConfig.builder("svc").setGlobal(false).build());
      h.tracer("t").spanBuilder("s").startSpan().end();
      h.forceFlush();
      assertThat(server.requests()).anyMatch(r -> r.uri().equals("/v1/traces"));
      assertThat(server.requests().get(0).header("signoz-ingestion-key")).isEqualTo("ingest-key-1");
      client.close();
      assertThat(h.isShutdown()).isTrue();
      assertThatThrownBy(
              () -> client.initTracing(TracingConfig.builder("svc").setGlobal(false).build()))
          .isInstanceOf(codes.grow.packages.signoz.core.CancelledException.class);
      assertThatThrownBy(client::logClient)
          .isInstanceOf(codes.grow.packages.signoz.core.CancelledException.class);
      TracingHandle env =
          SignozTracing.initTracing(TracingConfig.builder("svc").setGlobal(false).build());
      env.shutdown();
      assertThat(client.logs()).isSameAs(client.query());
      assertThat(client.traces()).isSameAs(client.query());
      assertThat(Path.of(".")).isNotNull();
    }
  }
}
