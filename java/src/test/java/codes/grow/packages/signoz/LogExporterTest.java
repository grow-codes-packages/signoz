package codes.grow.packages.signoz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import codes.grow.packages.signoz.core.AuthenticationException;
import codes.grow.packages.signoz.core.CancelledException;
import codes.grow.packages.signoz.core.ErrorCode;
import codes.grow.packages.signoz.core.MockServer;
import codes.grow.packages.signoz.core.NotFoundException;
import codes.grow.packages.signoz.core.SignozException;
import codes.grow.packages.signoz.core.TimeoutException;
import codes.grow.packages.signoz.core.TlsOptions;
import codes.grow.packages.signoz.core.UnavailableException;
import codes.grow.packages.signoz.core.ValidationException;
import codes.grow.packages.signoz.logging.BatchOptions;
import codes.grow.packages.signoz.logging.BatchingLogClient;
import codes.grow.packages.signoz.logging.ExportOptions;
import codes.grow.packages.signoz.logging.ExportResult;
import codes.grow.packages.signoz.logging.LogInput;
import codes.grow.packages.signoz.logging.LogRecord;
import codes.grow.packages.signoz.logging.RedactionProcessor;
import codes.grow.packages.signoz.logging.Severity;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LogExporterTest {
  private MockServer server;

  @BeforeEach
  void start() throws Exception {
    server = MockServer.start();
  }

  @AfterEach
  void stop() {
    server.close();
  }

  /** Minimal protobuf decoder: field number to values (byte[] for length-delimited, Long else). */
  static Map<Integer, List<Object>> decode(byte[] b) {
    Map<Integer, List<Object>> out = new LinkedHashMap<>();
    OtlpLogs.Reader r = new OtlpLogs.Reader(b, 0, b.length);
    while (r.more()) {
      long tag = r.varint();
      int field = (int) (tag >>> 3);
      int wire = (int) (tag & 7);
      Object v;
      if (wire == 2) {
        int len = (int) r.varint();
        byte[] sub = new byte[len];
        System.arraycopy(b, r.pos, sub, 0, len);
        r.pos += len;
        v = sub;
      } else if (wire == 0) {
        v = r.varint();
      } else {
        int n = wire == 1 ? 8 : 4;
        long x = 0;
        for (int i = 0; i < n; i++) {
          x |= (long) (b[r.pos + i] & 0xFF) << (8 * i);
        }
        r.pos += n;
        v = x;
      }
      out.computeIfAbsent(field, k -> new ArrayList<>()).add(v);
    }
    return out;
  }

  static byte[] sub(Map<Integer, List<Object>> m, int field, int index) {
    return (byte[]) m.get(field).get(index);
  }

  static String str(Map<Integer, List<Object>> m, int field) {
    return new String(sub(m, field, 0), StandardCharsets.UTF_8);
  }

  private static LogRecord record(String body, Map<String, Object> resource) {
    return LogRecord.builder()
        .timestamp(Instant.parse("2026-11-18T20:21:00.000000123Z"))
        .observedTimestamp(Instant.parse("2026-11-18T20:21:01Z"))
        .severity(Severity.ERROR)
        .severityText("ERROR")
        .body(body)
        .attributes(
            Map.of(
                "s",
                "v",
                "n",
                7L,
                "d",
                1.5,
                "b",
                true,
                "list",
                List.of("x", 2L),
                "map",
                Map.of("k", "v")))
        .resource(resource)
        .traceId("4bf92f3577b34da6a3ce929d0e0e4736")
        .spanId("00f067aa0ba902b7")
        .traceFlags(1)
        .build();
  }

  @Test
  void encodesOneResourceLogsPerResource() {
    byte[] bytes =
        OtlpLogs.encode(
            List.of(
                record("one", Map.of("service.name", "a")),
                record("two", Map.of("service.name", "b")),
                record("three", Map.of("service.name", "a"))));
    Map<Integer, List<Object>> req = decode(bytes);
    assertThat(req.get(1)).hasSize(2);
    Map<Integer, List<Object>> rl = decode(sub(req, 1, 0));
    Map<Integer, List<Object>> resource = decode(sub(rl, 1, 0));
    Map<Integer, List<Object>> kv = decode(sub(resource, 1, 0));
    assertThat(str(kv, 1)).isEqualTo("service.name");
    assertThat(str(decode(sub(kv, 2, 0)), 1)).isEqualTo("a");
    Map<Integer, List<Object>> scopeLogs = decode(sub(rl, 2, 0));
    assertThat(str(decode(sub(scopeLogs, 1, 0)), 1)).isEqualTo(OtlpLogs.SCOPE_NAME);
    assertThat(scopeLogs.get(2)).hasSize(2);
    Map<Integer, List<Object>> lr = decode(sub(scopeLogs, 2, 0));
    assertThat(lr.get(1).get(0))
        .isEqualTo(Instant.parse("2026-11-18T20:21:00Z").getEpochSecond() * 1_000_000_000L + 123);
    assertThat(lr.get(2).get(0)).isEqualTo(17L);
    assertThat(str(lr, 3)).isEqualTo("ERROR");
    assertThat(str(decode(sub(lr, 5, 0)), 1)).isEqualTo("one");
    assertThat(lr.get(6)).hasSize(6);
    assertThat(lr.get(8).get(0)).isEqualTo(1L);
    assertThat(sub(lr, 9, 0)).hasSize(16);
    assertThat(sub(lr, 10, 0)).hasSize(8);
    assertThat(lr).containsKey(11);
    // attributes sorted: b, d, list, map, n, s
    Map<Integer, List<Object>> b = decode(sub(decode(sub(lr, 6, 0)), 2, 0));
    assertThat(b.get(2).get(0)).isEqualTo(1L);
    Map<Integer, List<Object>> d = decode(sub(decode(sub(lr, 6, 1)), 2, 0));
    assertThat(Double.longBitsToDouble((Long) d.get(4).get(0))).isEqualTo(1.5);
    Map<Integer, List<Object>> list = decode(sub(decode(sub(lr, 6, 2)), 2, 0));
    assertThat(decode(sub(list, 5, 0)).get(1)).hasSize(2);
    Map<Integer, List<Object>> map = decode(sub(decode(sub(lr, 6, 3)), 2, 0));
    assertThat(map).containsKey(6);
    Map<Integer, List<Object>> n = decode(sub(decode(sub(lr, 6, 4)), 2, 0));
    assertThat(n.get(3).get(0)).isEqualTo(7L);
  }

  @Test
  void anyValueAndIdHelpers() {
    assertThat(OtlpLogs.anyValue(null)).isNull();
    assertThat(decode(OtlpLogs.anyValue(3).toByteArray()).get(3).get(0)).isEqualTo(3L);
    assertThat(decode(OtlpLogs.anyValue(2.5f).toByteArray())).containsKey(4);
    assertThat(str(decode(OtlpLogs.anyValue(Duration.ZERO).toByteArray()), 1)).isEqualTo("PT0S");
    List<Object> withNull = new ArrayList<>();
    withNull.add(null);
    withNull.add("x");
    assertThat(decode(sub(decode(OtlpLogs.anyValue(withNull).toByteArray()), 5, 0)).get(1))
        .hasSize(1);
    assertThat(OtlpLogs.hex("zz", 1)).isNull();
    assertThat(OtlpLogs.hex("0z", 1)).isNull();
    assertThat(OtlpLogs.hex(null, 1)).isNull();
    assertThat(OtlpLogs.hex("abc", 2)).isNull();
    assertThat(OtlpLogs.hex("ff", 1)).containsExactly((byte) 0xff);
    LogRecord minimal = LogRecord.builder().timestamp(Instant.EPOCH).body("x").build();
    Map<Integer, List<Object>> lr = decode(OtlpLogs.logRecord(minimal).toByteArray());
    assertThat(lr).doesNotContainKeys(8, 9, 10, 11);
    Map<String, Object> attrs = new LinkedHashMap<>();
    attrs.put("nil", null);
    OtlpLogs.Writer w = new OtlpLogs.Writer();
    OtlpLogs.keyValues(w, 1, attrs);
    assertThat(w.toByteArray()).isNotEmpty();
  }

  @Test
  void partialSuccessDecoding() {
    OtlpLogs.Writer ps = new OtlpLogs.Writer();
    ps.uint64(3, 9);
    ps.uint64(1, 4);
    ps.string(2, "bad records");
    ps.fixed32(7, 1);
    ps.fixed64(8, 1);
    OtlpLogs.Writer resp = new OtlpLogs.Writer();
    resp.uint64(5, 1);
    resp.message(1, ps);
    OtlpLogs.PartialSuccess p = OtlpLogs.partialSuccess(resp.toByteArray());
    assertThat(p.rejected()).isEqualTo(4);
    assertThat(p.message()).isEqualTo("bad records");
    assertThat(OtlpLogs.partialSuccess(new byte[0]).rejected()).isZero();
    assertThat(OtlpLogs.partialSuccess(new byte[] {(byte) 0x80}).rejected()).isZero();
    assertThat(OtlpLogs.partialSuccess(new byte[] {0x0b}).rejected()).isZero();
    assertThat(OtlpLogs.partialSuccess(new byte[] {0x0a, 0x7f}).rejected()).isZero();
    byte[] tooLong = new byte[12];
    java.util.Arrays.fill(tooLong, (byte) 0xff);
    assertThat(OtlpLogs.partialSuccess(tooLong).rejected()).isZero();
  }

  private SignozConfig.Builder httpConfig() {
    return SignozConfig.builder()
        .otlpEndpoint(server.url())
        .ingestionKey("ingest-key-secret-1")
        .header("X-Extra", "1");
  }

  @Test
  void httpExportSendsProtobufWithIngestionKey() {
    OtlpLogs.Writer ps = new OtlpLogs.Writer();
    ps.uint64(1, 1);
    ps.string(2, "one bad: ingest-key-secret-1");
    OtlpLogs.Writer resp = new OtlpLogs.Writer();
    resp.message(1, ps);
    server
        .enqueue(200, "", "Content-Type", "application/x-protobuf")
        .enqueue(
            200,
            new String(resp.toByteArray(), StandardCharsets.ISO_8859_1),
            "Content-Type",
            "application/x-protobuf")
        .enqueue(200, "{\"partialSuccess\":{\"rejectedLogRecords\":\"5\",\"errorMessage\":\"x\"}}")
        .enqueue(200, "{\"partialSuccess\":{}}")
        .enqueue(200, "not json at all", "Content-Type", "application/json")
        .enqueue(400, Fixtures.read("otlp.logs.http.badRequest.json"));
    try (SignozLogExporter exp = SignozLogExporter.create(httpConfig().build())) {
      assertThat(exp.protocol()).isEqualTo(OtlpProtocol.HTTP_PROTOBUF);
      assertThat(exp.endpoint()).isEqualTo(server.url() + "/v1/logs");
      List<LogRecord> recs = List.of(record("hello", Map.of("service.name", "a")));
      ExportResult ok = exp.export(recs, new ExportOptions(false, Duration.ofSeconds(5))).join();
      assertThat(ok.accepted()).isEqualTo(1);
      MockServer.Recorded req = server.requests().get(0);
      assertThat(req.uri()).isEqualTo("/v1/logs");
      assertThat(req.header("Content-Type")).isEqualTo("application/x-protobuf");
      assertThat(req.header("signoz-ingestion-key")).isEqualTo("ingest-key-secret-1");
      assertThat(req.header("X-Extra")).isEqualTo("1");
      assertThat(req.header("SIGNOZ-API-KEY")).isNull();
      assertThat(req.body()).contains("hello");
      ExportResult partial =
          exp.export(recs, new ExportOptions(true, Duration.ofSeconds(5))).join();
      assertThat(partial.rejected()).isEqualTo(1);
      assertThat(partial.error()).get().asString().doesNotContain("ingest-key-secret-1");
      assertThat(server.requests().get(1).header("Content-Encoding")).isEqualTo("gzip");
      ExportResult json = exp.export(recs, new ExportOptions(false, Duration.ZERO)).join();
      assertThat(json.rejected()).isEqualTo(1);
      assertThat(json.accepted()).isZero();
      assertThat(
              exp.export(recs, new ExportOptions(false, Duration.ofSeconds(5))).join().accepted())
          .isEqualTo(1);
      assertThat(
              exp.export(recs, new ExportOptions(false, Duration.ofSeconds(5))).join().accepted())
          .isEqualTo(1);
      assertThatThrownBy(
              () -> exp.export(recs, new ExportOptions(false, Duration.ofSeconds(5))).join())
          .hasCauseInstanceOf(ValidationException.class);
      assertThat(
              exp.export(List.of(), new ExportOptions(false, Duration.ofSeconds(1)))
                  .join()
                  .accepted())
          .isZero();
      assertThat(
              exp.export(null, new ExportOptions(false, Duration.ofSeconds(1))).join().accepted())
          .isZero();
      assertThat(exp.toString()).contains("http/protobuf");
      exp.shutdown().join();
      assertThatThrownBy(
              () -> exp.export(recs, new ExportOptions(false, Duration.ofSeconds(5))).join())
          .hasCauseInstanceOf(CancelledException.class);
    }
  }

  @Test
  void otlpHttpUrlAppendsTheSignalPath() {
    assertThat(SignozLogExporter.otlpHttpUrl("http://c:4318", "logs"))
        .isEqualTo("http://c:4318/v1/logs");
    assertThat(SignozLogExporter.otlpHttpUrl("http://c:4318/", "traces"))
        .isEqualTo("http://c:4318/v1/traces");
    assertThat(SignozLogExporter.otlpHttpUrl("http://c:4318/v1/logs", "logs"))
        .isEqualTo("http://c:4318/v1/logs");
    assertThat(SignozLogExporter.otlpHttpUrl("http://c/p?x=1", "logs"))
        .isEqualTo("http://c/p/v1/logs");
  }

  @Test
  void logClientSendsThroughTheExporter() {
    server.fallback(ex -> MockServer.send(ex, 200, ""));
    try (SignozClient client =
        SignozClient.builder().url(server.url()).otlpEndpoint(server.url()).build()) {
      BatchingLogClient lc =
          client.logClient(
              LogClientOptions.builder()
                  .serviceName("svc")
                  .serviceVersion("1.0.0")
                  .environment("test")
                  .resource(Map.of("host.name", "h"))
                  .header("X-Log", "y")
                  .batchOptions(
                      BatchOptions.builder()
                          .gzip(false)
                          .flushInterval(Duration.ofMillis(50))
                          .build())
                  .redaction(RedactionProcessor.defaults())
                  .build());
      lc.send(
          LogInput.builder("hello world")
              .severity(Severity.WARN)
              .attribute("password", "hunter2")
              .build());
      lc.flush(Duration.ofSeconds(5));
      assertThat(lc.stats().sent()).isEqualTo(1);
      assertThat(lc.resource())
          .containsEntry("service.name", "svc")
          .containsEntry("service.version", "1.0.0")
          .containsEntry("deployment.environment", "test")
          .containsEntry("host.name", "h");
      MockServer.Recorded req = server.requests().get(0);
      assertThat(req.header("X-Log")).isEqualTo("y");
      assertThat(req.body()).contains("hello world").doesNotContain("hunter2");
      BatchingLogClient other = client.logClient();
      assertThat(other.resource()).containsEntry("service.name", "unknown_service");
      SignozLogExporter exporter = client.logExporter();
      exporter.close();
      exporter.close();
    }
  }

  @Test
  void logClientOptionsExportConfig() {
    SignozConfig base = SignozConfig.builder().otlpEndpoint("http://collector:4318").build();
    SignozConfig grpc =
        LogClientOptions.builder().protocol(OtlpProtocol.GRPC).build().exportConfig(base);
    assertThat(grpc.otlpProtocol()).isEqualTo(OtlpProtocol.GRPC);
    assertThat(grpc.otlpEndpoint()).isEqualTo(SignozConfig.DEFAULT_OTLP_GRPC_ENDPOINT);
    SignozConfig same =
        LogClientOptions.builder().protocol(OtlpProtocol.HTTP_PROTOBUF).build().exportConfig(base);
    assertThat(same.otlpEndpoint()).isEqualTo("http://collector:4318");
    SignozConfig explicit =
        LogClientOptions.builder()
            .protocol(OtlpProtocol.GRPC)
            .endpoint("http://collector:4317")
            .build()
            .exportConfig(base);
    assertThat(explicit.otlpEndpoint()).isEqualTo("http://collector:4317");
    LogClientOptions d = LogClientOptions.defaults();
    assertThat(d.serviceName()).isEmpty();
    assertThat(d.batchOptions()).isEmpty();
    assertThat(d.redaction()).isEmpty();
    assertThat(d.exportConfig(base).otlpEndpoint()).isEqualTo("http://collector:4318");
    assertThat(SignozClient.createLogClient(base, null)).isNotNull();
  }

  @Test
  void grpcInterpretMapsStatusAndPartialSuccess() {
    List<String> secrets = List.of("ingest-key-secret-1");
    OtlpLogs.Writer ps = new OtlpLogs.Writer();
    ps.uint64(1, 2);
    OtlpLogs.Writer resp = new OtlpLogs.Writer();
    resp.message(1, ps);
    byte[] framed = GrpcLogSender.frame(resp.toByteArray(), false);
    assertThat(framed[0]).isZero();
    assertThat(GrpcLogSender.interpret(200, "0", null, framed, secrets).rejected()).isEqualTo(2);
    assertThat(GrpcLogSender.interpret(200, "0", null, new byte[0], secrets).rejected()).isZero();
    byte[] compressed = GrpcLogSender.frame(new byte[] {1, 2, 3}, true);
    assertThat(compressed[0]).isEqualTo((byte) 1);
    assertThat(GrpcLogSender.interpret(200, "0", null, compressed, secrets).rejected()).isZero();
    byte[] truncated = {0, 0, 0, 0, 9, 1};
    assertThat(GrpcLogSender.interpret(200, "0", null, truncated, secrets).rejected()).isZero();
    assertThatThrownBy(
            () ->
                GrpcLogSender.interpret(
                    200, "3", "bad%20payload%3A ingest-key-secret-1", new byte[0], secrets))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("bad payload")
        .hasMessageContaining("(gRPC 3)")
        .message()
        .doesNotContain("ingest-key-secret-1");
    assertThatThrownBy(() -> GrpcLogSender.interpret(200, "16", null, new byte[0], secrets))
        .isInstanceOf(AuthenticationException.class)
        .hasMessageContaining("gRPC error");
    assertThatThrownBy(() -> GrpcLogSender.interpret(200, "x", null, new byte[0], secrets))
        .isInstanceOf(UnavailableException.class);
    assertThatThrownBy(() -> GrpcLogSender.interpret(200, null, null, new byte[0], secrets))
        .isInstanceOf(UnavailableException.class)
        .hasMessageContaining("without grpc-status");
    assertThatThrownBy(() -> GrpcLogSender.interpret(404, null, null, new byte[0], secrets))
        .isInstanceOf(NotFoundException.class);
    assertThat(GrpcLogSender.decodeMessage(null)).isEmpty();
    assertThat(GrpcLogSender.decodeMessage("a+b")).isEqualTo("a+b");
    assertThat(GrpcLogSender.decodeMessage("%zz")).isEqualTo("%zz");
    int[][] codes = {
      {1, 0}, {3, 1}, {9, 1}, {11, 1}, {4, 2}, {5, 3}, {7, 4}, {8, 5}, {12, 6}, {16, 7}, {14, 8}
    };
    ErrorCode[] expected = {
      ErrorCode.CANCELLED,
      ErrorCode.VALIDATION,
      ErrorCode.TIMEOUT,
      ErrorCode.NOT_FOUND,
      ErrorCode.PERMISSION_DENIED,
      ErrorCode.RATE_LIMITED,
      ErrorCode.UNSUPPORTED,
      ErrorCode.AUTHENTICATION,
      ErrorCode.UNAVAILABLE
    };
    for (int[] c : codes) {
      assertThat(GrpcLogSender.grpcCode(c[0])).isEqualTo(expected[c[1]]);
    }
  }

  @Test
  void grpcExportToADeadCollectorIsUnavailable() throws Exception {
    int port;
    try (ServerSocket s = new ServerSocket(0)) {
      port = s.getLocalPort();
    }
    SignozConfig cfg =
        SignozConfig.builder()
            .otlpProtocol(OtlpProtocol.GRPC)
            .otlpEndpoint("http://127.0.0.1:" + port)
            .ingestionKey("ingest-key-secret-1")
            .header("X-Grpc", "1")
            .proxy(null)
            .build();
    try (SignozLogExporter exp = SignozLogExporter.create(cfg)) {
      assertThat(exp.protocol()).isEqualTo(OtlpProtocol.GRPC);
      assertThat(exp.endpoint()).isEqualTo("http://127.0.0.1:" + port);
      List<LogRecord> recs = List.of(record("x", Map.of()));
      assertThatThrownBy(
              () -> exp.export(recs, new ExportOptions(true, Duration.ofSeconds(5))).join())
          .isInstanceOf(CompletionException.class)
          .hasCauseInstanceOf(UnavailableException.class);
      assertThatThrownBy(
              () -> exp.export(recs, new ExportOptions(false, Duration.ofSeconds(5))).join())
          .hasCauseInstanceOf(UnavailableException.class);
    }
  }

  @Test
  void grpcSenderOptionsAndNetworkErrors() throws Exception {
    TlsOptions insecure = TlsOptions.builder().insecureSkipVerify(true).build();
    GrpcLogSender s =
        new GrpcLogSender(
            "https://collector.invalid",
            insecure,
            "http://proxy.invalid:3128",
            Duration.ofSeconds(1),
            Map.of("a", "b"),
            "ua",
            List.of("secret-1234"));
    assertThat(s.url()).isEqualTo("https://collector.invalid:443" + GrpcLogSender.METHOD);
    GrpcLogSender plain =
        new GrpcLogSender(
            "http://collector.invalid",
            TlsOptions.defaults(),
            "http://proxy.invalid",
            Duration.ofSeconds(1),
            Map.of(),
            "ua",
            List.of());
    assertThat(plain.url()).isEqualTo("http://collector.invalid:80" + GrpcLogSender.METHOD);
    SignozException cancelled = s.networkError(new IOException("x"), true);
    assertThat(cancelled).isInstanceOf(CancelledException.class);
    assertThat(s.networkError(new InterruptedIOException("timeout"), false))
        .isInstanceOf(TimeoutException.class);
    assertThat(s.networkError(new IOException("boom secret-1234"), false))
        .isInstanceOf(UnavailableException.class)
        .message()
        .doesNotContain("secret-1234");
    assertThat(GrpcLogSender.trustManager(true).getAcceptedIssuers()).isEmpty();
    GrpcLogSender.trustManager(true).checkClientTrusted(null, "x");
    GrpcLogSender.trustManager(true).checkServerTrusted(null, "x");
    assertThat(GrpcLogSender.trustManager(false)).isNotNull();
    s.close();
    plain.close();
  }

  @Test
  void grpcCallCancelledByTheCaller() throws Exception {
    try (ServerSocket listener = new ServerSocket(0)) {
      GrpcLogSender s =
          new GrpcLogSender(
              "http://127.0.0.1:" + listener.getLocalPort(),
              TlsOptions.defaults(),
              null,
              Duration.ofSeconds(5),
              Map.of(),
              "ua",
              List.of());
      var f = s.export(new byte[] {1}, false, Duration.ofSeconds(30));
      f.cancel(true);
      assertThat(f.isCancelled()).isTrue();
      var timeout = s.export(new byte[] {1}, false, Duration.ofMillis(200));
      assertThatThrownBy(timeout::join).hasCauseInstanceOf(TimeoutException.class);
      s.close();
    }
  }
}
