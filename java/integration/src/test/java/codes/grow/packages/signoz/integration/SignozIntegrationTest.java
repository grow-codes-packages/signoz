package codes.grow.packages.signoz.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import codes.grow.packages.signoz.AggregateQuery;
import codes.grow.packages.signoz.AggregateResult;
import codes.grow.packages.signoz.AggregateSeries;
import codes.grow.packages.signoz.FieldValuesOptions;
import codes.grow.packages.signoz.FilterBuilder;
import codes.grow.packages.signoz.ListFieldsOptions;
import codes.grow.packages.signoz.LogClientOptions;
import codes.grow.packages.signoz.LogField;
import codes.grow.packages.signoz.LogQuery;
import codes.grow.packages.signoz.LogQueryResult;
import codes.grow.packages.signoz.OtlpProtocol;
import codes.grow.packages.signoz.QueryRangeResult;
import codes.grow.packages.signoz.Signal;
import codes.grow.packages.signoz.SignozClient;
import codes.grow.packages.signoz.SignozConfig;
import codes.grow.packages.signoz.SignozLogExporter;
import codes.grow.packages.signoz.SignozQueryClient;
import codes.grow.packages.signoz.SignozTracing;
import codes.grow.packages.signoz.SpanQueryResult;
import codes.grow.packages.signoz.TimeRange;
import codes.grow.packages.signoz.TraceQuery;
import codes.grow.packages.signoz.core.AuthenticationException;
import codes.grow.packages.signoz.core.EnvReader;
import codes.grow.packages.signoz.core.HealthStatus;
import codes.grow.packages.signoz.core.Redaction;
import codes.grow.packages.signoz.core.UnsupportedException;
import codes.grow.packages.signoz.core.ValidationException;
import codes.grow.packages.signoz.logging.BatchStats;
import codes.grow.packages.signoz.logging.BatchingLogClient;
import codes.grow.packages.signoz.logging.ExportOptions;
import codes.grow.packages.signoz.logging.ExportResult;
import codes.grow.packages.signoz.logging.LogInput;
import codes.grow.packages.signoz.logging.LogRecord;
import codes.grow.packages.signoz.logging.LogSearchResult;
import codes.grow.packages.signoz.logging.SearchOptions;
import codes.grow.packages.signoz.logging.Severity;
import codes.grow.packages.signoz.logging.SortOrder;
import codes.grow.packages.signoz.logging.TailHandle;
import codes.grow.packages.signoz.logging.TailOptions;
import codes.grow.packages.signoz.logging.WaitForLogOptions;
import codes.grow.packages.signoz.tracing.TracingConfig;
import codes.grow.packages.signoz.tracing.TracingHandle;
import codes.grow.packages.signoz.tracing.query.DependencyLink;
import codes.grow.packages.signoz.tracing.query.Span;
import codes.grow.packages.signoz.tracing.query.Trace;
import codes.grow.packages.signoz.tracing.query.TraceSearchQuery;
import codes.grow.packages.signoz.tracing.query.TraceSummary;
import codes.grow.packages.signoz.tracing.query.WaitForTraceOptions;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Calls every public function of the package against the real SigNoz stack from {@code
 * docker/compose.yml} with round-trip assertions (write, then read back).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SignozIntegrationTest {
  private static final Duration WAIT = Duration.ofSeconds(60);

  private Map<String, String> env;
  private SignozConfig cfg;
  private SignozClient client;
  private SignozQueryClient q;
  private String run;
  private String service;
  private final List<LogRecord> tailed = new CopyOnWriteArrayList<>();
  private TailHandle tail;
  private String traceService;
  private String dbService;

  @BeforeAll
  void setUp() throws IOException {
    env = ConformanceTest.environment();
    assumeTrue(
        env.containsKey(EnvReader.DEFAULT_PREFIX + "URL"),
        "GROW_SIGNOZ_URL is not set; use docker/compose.yml");
    byte[] b = new byte[4];
    new SecureRandom().nextBytes(b);
    StringBuilder sb = new StringBuilder();
    for (byte x : b) {
      sb.append(String.format("%02x", x));
    }
    run = sb.toString();
    service = "java-it-" + run;
    traceService = "java-it-trace-" + run;
    dbService = "java-it-db-" + run;
    cfg = SignozConfig.builder().fromEnv(EnvReader.of(env)).build();
    client = SignozClient.create(cfg);
    q = client.query();
  }

  @AfterAll
  void tearDown() {
    if (tail != null) {
      tail.stop();
    }
    if (client != null) {
      client.close();
    }
  }

  private String grpcEndpoint() {
    String e = env.get("SIGNOZ_OTLP_GRPC_ENDPOINT");
    return e != null && !e.isEmpty() ? e : cfg.otlpEndpoint().replace("4318", "4317");
  }

  private static void poll(String what, Supplier<Boolean> fn) {
    long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
    RuntimeException last = null;
    while (System.nanoTime() < deadline) {
      try {
        if (fn.get()) {
          return;
        }
      } catch (RuntimeException e) {
        last = e;
      }
      try {
        Thread.sleep(1000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    fail(what + ": condition not met (last error " + last + ")");
  }

  private List<LogRecord> waitForLogs(String filter, int n) {
    List<LogRecord> out = new ArrayList<>();
    poll(
        "waitForLogs " + filter,
        () -> {
          LogQueryResult r =
              q.queryLogs(
                  LogQuery.builder()
                      .filter(filter)
                      .start(Instant.now().minus(Duration.ofMinutes(30)))
                      .limit(1000)
                      .build());
          out.clear();
          out.addAll(r.records());
          return out.size() >= n;
        });
    return out;
  }

  @Test
  @Order(1)
  void core() {
    String key = cfg.apiKey().orElseThrow().reveal();
    assertFalse(cfg.toString().contains(key), "api key must be redacted");
    HealthStatus h = client.health();
    assertTrue(h.healthy(), h.toString());
    assertEquals("ok", h.status());
    assertTrue(client.healthAsync().join().healthy());
    assertFalse(client.ping().isNegative());
    assertNotNull(client.pingAsync().join());
    try {
      String v = client.serverVersion();
      assertFalse(v.isEmpty());
    } catch (UnsupportedException expected) {
      // the community image reports an empty version
    }
    Object async =
        client.serverVersionAsync().handle((v, e) -> v != null ? v : e.getCause()).join();
    assertTrue(
        async instanceof String || async instanceof UnsupportedException, String.valueOf(async));
    try (SignozClient bad =
        SignozClient.create(cfg.toBuilder().apiKey("wrong-key-0000").build())) {
      assertThrows(AuthenticationException.class, bad::ping);
      assertThrows(AuthenticationException.class, () -> bad.query().queryLogs(LogQuery.defaults()));
    }
    try (SignozClient fromEnv = SignozClient.builder().fromEnv(EnvReader.of(env)).build()) {
      assertEquals(cfg.url(), fromEnv.config().url());
    }
    SignozConfig grpc =
        SignozConfig.builder()
            .otlpProtocol(OtlpProtocol.GRPC)
            .otlpEndpoint(grpcEndpoint())
            .fromEnv(EnvReader.of(env))
            .build();
    assertEquals(OtlpProtocol.GRPC, grpc.otlpProtocol());
    // live tail first, so it sees the records sent below
    tail =
        q.tail(
            FilterBuilder.create().contains("body", "tail-" + run).build(),
            tailed::add,
            TailOptions.defaults());
  }

  @Test
  @Order(2)
  void logsSendAndQueryBack() {
    String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
    String spanId = "00f067aa0ba902b7";
    BatchingLogClient lc =
        client.logClient(
            LogClientOptions.builder()
                .serviceName(service)
                .serviceVersion("1.0.0")
                .environment("it")
                .build());
    String special = "special " + run + " it's 50% a_b back\\slash ünïcode";
    lc.send(
        LogInput.builder("hello " + run)
            .severity(Severity.INFO)
            .attributes(Map.of("java.it.run", run, "seq", 7, "ok", true, "password", "hunter2-" + run))
            .build());
    lc.send(
        LogInput.builder("error " + run)
            .severity(Severity.ERROR)
            .traceId(traceId)
            .spanId(spanId)
            .attributes(Map.of("java.it.run", run, "seq", 8))
            .build());
    lc.sendBatch(
        List.of(
            LogInput.of(special),
            LogInput.of("tail-" + run + " match"),
            LogInput.of("tail-x" + run + " nomatch")));
    lc.logger("it", Map.of("component", "logger")).warn("logger " + run);
    lc.flush(Duration.ofSeconds(30));
    BatchStats st = lc.stats();
    assertEquals(6, st.sent(), st.toString());
    assertEquals(0, st.dropped());
    assertEquals(0, st.failed());
    lc.close();

    String byService = FilterBuilder.create().eq("service.name", service).build();
    assertEquals(6, waitForLogs(byService, 6).size());

    String expr =
        FilterBuilder.create()
            .eq("service.name", service)
            .eq("java.it.run", run)
            .in("severity_text", "ERROR", "FATAL")
            .gte("seq", 8)
            .exists("java.it.run")
            .build();
    LogQueryResult res =
        q.queryLogs(
            LogQuery.builder().filter(expr).start(Instant.now().minus(Duration.ofMinutes(10))).build());
    assertEquals(1, res.records().size());
    LogRecord r = res.records().get(0);
    assertEquals("error " + run, r.body());
    assertEquals(Severity.ERROR, r.severity());
    assertEquals("ERROR", r.severityText());
    assertEquals(Optional.of(traceId), r.traceId());
    assertEquals(Optional.of(spanId), r.spanId());
    assertEquals(8L, r.attributes().get("seq"));
    assertEquals(service, r.resource().get("service.name"));
    assertEquals("1.0.0", r.resource().get("service.version"));
    assertEquals("it", r.resource().get("deployment.environment"));
    assertTrue(Duration.between(r.timestamp(), Instant.now()).toMinutes() < 10);

    LogRecord hello =
        q.waitForLog("'hello " + run + "'", WaitForLogOptions.of(WAIT, Duration.ofSeconds(1)));
    assertEquals(Redaction.REDACTED, hello.attributes().get("password"));
    assertEquals(true, hello.attributes().get("ok"));
    assertNotNull(q.waitForLogAsync("'hello " + run + "'", WaitForLogOptions.defaults()).join());

    Map<String, Integer> checks = new LinkedHashMap<>();
    FilterBuilder base = FilterBuilder.create().eq("service.name", service);
    checks.put(base.contains("body", "it's 50% a_b back\\slash").build(), 1);
    checks.put(base.contains("body", "50%_a").build(), 0);
    checks.put(base.notContains("body", run).build(), 0);
    checks.put(base.like("body", "tail-%" + run + "%").build(), 2);
    checks.put(base.regexp("body", "^tail-x?" + run).build(), 2);
    checks.put(base.text("logger " + run).build(), 1);
    checks.put(
        base.or(FilterBuilder.create().eq("seq", 7), FilterBuilder.create().eq("seq", 8)).build(),
        2);
    checks.put(base.not(FilterBuilder.create().exists("seq")).notExists("java.it.run").build(), 4);
    checks.put(
        base.notIn("severity_text", "ERROR")
            .ne("seq", 7)
            .lt("seq", 100)
            .lte("seq", 8)
            .gt("seq", 1)
            .build(),
        0);
    checks.put(FilterBuilder.andFilters(base.build(), "seq = 7 OR seq = 8"), 2);
    checks.put(base.raw("seq = 7 OR seq = 8").build(), 2);
    checks.put(base.where("seq", codes.grow.packages.signoz.FilterOperator.EQ, 7).build(), 1);
    for (Map.Entry<String, Integer> c : checks.entrySet()) {
      LogQueryResult got =
          q.queryLogs(LogQuery.builder().filter(c.getKey()).limit(100).selectFields("body").build());
      assertEquals(c.getValue(), got.records().size(), c.getKey());
    }
    assertThrows(
        ValidationException.class,
        () -> q.queryLogs(LogQuery.builder().filter("body CONTAINS (((").build()));
    LogQueryResult warn =
        q.queryLogs(LogQuery.builder().filter("nosuchfield_" + run + " = 'x'").build());
    assertTrue(warn.records().isEmpty());
    assertFalse(warn.warnings().isEmpty());
    assertNotNull(q.queryLogsAsync(LogQuery.defaults()).join());
  }

  @Test
  @Order(3)
  void exportersHttpGrpcAndRaw() {
    SignozConfig grpcCfg =
        cfg.toBuilder().otlpProtocol(OtlpProtocol.GRPC).otlpEndpoint(grpcEndpoint()).build();
    BatchingLogClient lc =
        SignozClient.createLogClient(
            grpcCfg, LogClientOptions.builder().serviceName(service + "-grpc").build());
    lc.send(LogInput.of("grpc " + run));
    lc.close();
    BatchingLogClient lc2 =
        client.logClient(
            LogClientOptions.builder()
                .serviceName(service + "-grpc2")
                .protocol(OtlpProtocol.GRPC)
                .endpoint(grpcEndpoint())
                .build());
    lc2.send(LogInput.of("grpc2 " + run));
    lc2.close();

    try (SignozLogExporter exp = client.logExporter();
        SignozLogExporter direct = SignozLogExporter.create(grpcCfg)) {
      LogRecord rec =
          LogRecord.builder()
              .timestamp(Instant.now())
              .severity(Severity.DEBUG)
              .severityText("DEBUG")
              .body("raw " + run)
              .resource(Map.of("service.name", service + "-raw"))
              .attributes(Map.of("f", 1.5))
              .build();
      ExportResult res = exp.export(List.of(rec), new ExportOptions(true, WAIT)).join();
      assertEquals(1, res.accepted());
      ExportResult res2 =
          direct
              .export(
                  List.of(rec.toBuilder().body("rawgrpc " + run).build()),
                  new ExportOptions(true, WAIT))
              .join();
      assertEquals(1, res2.accepted());
      assertEquals(OtlpProtocol.GRPC, direct.protocol());
      exp.shutdown().join();
    }
    for (String body : List.of("grpc " + run, "grpc2 " + run, "raw " + run, "rawgrpc " + run)) {
      LogRecord r =
          q.waitForLog("'" + body + "'", WaitForLogOptions.of(WAIT, Duration.ofSeconds(1)));
      assertTrue(String.valueOf(r.resource().get("service.name")).startsWith(service), r.toString());
      if (body.equals("raw " + run)) {
        assertEquals(Severity.DEBUG, r.severity());
        assertEquals(1.5, r.attributes().get("f"));
      }
    }
  }

  @Test
  @Order(4)
  void liveTail() {
    poll("tail", () -> !tailed.isEmpty());
    assertEquals(1, tailed.size(), tailed.toString());
    assertEquals("tail-" + run + " match", tailed.get(0).body());
    assertEquals(service, tailed.get(0).resource().get("service.name"));
  }

  @Test
  @Order(5)
  void pagingWithASharedMillisecond() {
    BatchingLogClient lc =
        client.logClient(LogClientOptions.builder().serviceName(service + "-page").build());
    Instant base = Instant.now().minus(Duration.ofMinutes(1)).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    long[] offsetsNanos = {0, 1_000_000, 1_000_010, 1_000_020, 2_000_000, 2_000_000, 3_000_000};
    for (int i = 0; i < offsetsNanos.length; i++) {
      lc.send(
          LogInput.builder("page " + run + " " + i)
              .timestamp(base.plusNanos(offsetsNanos[i]))
              .build());
    }
    lc.close();
    String query = FilterBuilder.create().eq("service.name", service + "-page").build();
    waitForLogs(query, offsetsNanos.length);
    for (SortOrder order : SortOrder.values()) {
      List<String> bodies = new ArrayList<>();
      String cursor = null;
      for (int page = 0; page < 20; page++) {
        LogQueryResult res =
            q.queryLogs(LogQuery.builder().filter(query).limit(2).order(order).cursor(cursor).build());
        res.records().forEach(r -> bodies.add(r.body()));
        if (res.nextCursor().isEmpty()) {
          break;
        }
        cursor = res.nextCursor().get();
      }
      assertEquals(offsetsNanos.length, bodies.size(), order + ": " + bodies);
      assertEquals(offsetsNanos.length, new HashSet<>(bodies).size(), order + ": " + bodies);
      String first = "page " + run + " " + (order == SortOrder.ASC ? 0 : 6);
      assertEquals(first, bodies.get(0), order + ": " + bodies);
    }
    LogSearchResult page = client.logs().search(query, SearchOptions.builder().limit(3).build());
    assertEquals(3, page.records().size());
    assertTrue(page.nextCursor().isPresent());
    assertEquals(3, client.logs().searchAsync(query, SearchOptions.builder().limit(3).build()).join().records().size());
    LogRecord any = client.logs().waitForLog(query);
    assertTrue(any.body().startsWith("page " + run));
  }

  @Test
  @Order(6)
  void fieldsStreamsLabelsValues() {
    poll("listLabels", () -> client.logs().listLabels().contains("java.it.run"));
    assertTrue(client.logs().listLabelsAsync().join().contains("java.it.run"));
    List<LogField> fields = q.listLogFields(new ListFieldsOptions("java.it", 50));
    assertTrue(
        fields.stream()
            .anyMatch(
                f ->
                    f.name().equals("java.it.run")
                        && f.context().equals("attribute")
                        && f.dataType().equals("string")),
        fields.toString());
    assertFalse(q.listLogFields().isEmpty());
    assertFalse(q.listLogFieldsAsync(ListFieldsOptions.defaults()).join().isEmpty());
    poll("listStreams", () -> client.logs().listStreams().contains(service));
    assertTrue(client.logs().listStreamsAsync().join().contains(service));
    poll(
        "listFieldValues",
        () ->
            q.listFieldValues("service.name", new FieldValuesOptions(Signal.LOGS, run))
                .contains(service));
    assertTrue(
        q.listFieldValuesAsync("service.name", FieldValuesOptions.defaults()).join().contains(service));
  }

  @Test
  @Order(7)
  void aggregateLogs() {
    String byService = FilterBuilder.create().eq("service.name", service).build();
    AggregateResult res =
        q.aggregate(Signal.LOGS)
            .filter(byService)
            .count("n")
            .groupBy("service.name")
            .orderBy("n desc")
            .limit(5)
            .between(Instant.now().minus(Duration.ofMinutes(30)), Instant.now().plusSeconds(60))
            .run();
    assertEquals(1, res.rows().size(), res.toString());
    assertEquals(service, res.rows().get(0).groups().get("service.name"));
    assertEquals(6.0, res.rows().get(0).values().get("n"));
    AggregateQuery ts =
        AggregateQuery.builder(Signal.LOGS)
            .filter(byService)
            .count("n")
            .step(Duration.ofMinutes(1))
            .between(Instant.now().minus(Duration.ofMinutes(30)), Instant.now().plusSeconds(60))
            .build();
    AggregateResult series = q.aggregateAsync(ts).join();
    double total = 0;
    for (AggregateSeries s : series.series()) {
      assertEquals("n", s.alias());
      total += s.points().stream().mapToDouble(p -> p.value()).sum();
    }
    assertEquals(6.0, total, series.toString());
    assertEquals(6.0, q.aggregate(Signal.LOGS).filter(byService).count("n").runAsync().join().rows().get(0).values().get("n"));
    long nowMs = System.currentTimeMillis();
    QueryRangeResult raw =
        q.queryRange(
            java.util.Map.of(
                "start", nowMs - 3_600_000L,
                "end", nowMs + 1_000L,
                "requestType", "scalar",
                "compositeQuery",
                    java.util.Map.of(
                        "queries",
                        java.util.List.of(
                            java.util.Map.of(
                                "type", "builder_query",
                                "spec",
                                    java.util.Map.of(
                                        "name", "A",
                                        "signal", "logs",
                                        "filter", java.util.Map.of("expression", byService),
                                        "aggregations",
                                            java.util.List.of(
                                                java.util.Map.of("expression", "count()"))))))));
    assertTrue(raw.data().toString().contains("__result_0"), raw.toString());
  }

  @Test
  @Order(8)
  void tracingRoundTrip() {
    TracingHandle h =
        client.initTracing(
            TracingConfig.builder(traceService).serviceVersion("1.0.0").setGlobal(false).build());
    TracingHandle hdb =
        SignozTracing.initTracing(
            cfg,
            TracingConfig.builder(dbService)
                .protocol(TracingConfig.Protocol.GRPC)
                .endpoint(grpcEndpoint())
                .setGlobal(false)
                .build());
    Tracer tracer = h.tracer("it");
    Tracer dbTracer = hdb.tracer("it");
    io.opentelemetry.api.trace.Span linked = tracer.spanBuilder("linked-" + run).setNoParent().startSpan();
    linked.end();
    io.opentelemetry.api.trace.Span root =
        tracer.spanBuilder("GET /it-" + run).setSpanKind(SpanKind.SERVER).setNoParent().startSpan();
    io.opentelemetry.api.trace.Span call =
        tracer
            .spanBuilder("call-db")
            .setSpanKind(SpanKind.CLIENT)
            .setParent(Context.root().with(root))
            .addLink(linked.getSpanContext())
            .startSpan();
    call.addEvent("checkpoint");
    io.opentelemetry.api.trace.Span server =
        dbTracer
            .spanBuilder("SELECT users")
            .setSpanKind(SpanKind.SERVER)
            .setParent(
                Context.root()
                    .with(io.opentelemetry.api.trace.Span.wrap(call.getSpanContext())))
            .startSpan();
    server.recordException(new RuntimeException("boom"));
    server.setStatus(StatusCode.ERROR, "boom");
    server.end();
    call.end();
    root.setStatus(StatusCode.OK);
    root.end();
    h.forceFlush();
    hdb.forceFlush();
    String traceId = root.getSpanContext().getTraceId();

    Trace tr =
        client.traces().waitForTrace(traceId, new WaitForTraceOptions(WAIT, Duration.ofSeconds(1), 3));
    Map<String, Span> byName = new HashMap<>();
    tr.spans().forEach(s -> byName.put(s.name(), s));
    Span r = byName.get("GET /it-" + run);
    Span c = byName.get("call-db");
    Span s = byName.get("SELECT users");
    assertEquals(Span.Kind.SERVER, r.kind());
    assertEquals(Span.StatusCode.OK, r.status().code());
    assertTrue(r.parentSpanId().isEmpty());
    assertEquals(traceService, r.serviceName());
    assertEquals(Span.Kind.CLIENT, c.kind());
    assertEquals(Optional.of(r.spanId()), c.parentSpanId());
    assertEquals(1, c.events().size());
    assertEquals("checkpoint", c.events().get(0).name());
    assertEquals(1, c.links().size(), c.toString());
    assertEquals(linked.getSpanContext().getSpanId(), c.links().get(0).spanId());
    assertEquals(dbService, s.serviceName());
    assertEquals(Optional.of(c.spanId()), s.parentSpanId());
    assertEquals(Span.StatusCode.ERROR, s.status().code());
    assertEquals(Optional.of("boom"), s.status().message());
    assertEquals("GET /it-" + run, tr.root().orElseThrow().name());
    assertTrue(client.traces().waitForTraceAsync(traceId, WaitForTraceOptions.defaults()).join().spans().size() >= 3);
    assertTrue(client.traces().getTraceAsync(traceId).join().isPresent());

    SpanQueryResult spans =
        q.queryTraces(
            TraceQuery.builder()
                .service(traceService)
                .operation("call-db")
                .start(Instant.now().minus(Duration.ofMinutes(10)))
                .build());
    assertEquals(1, spans.spans().size(), spans.toString());
    assertEquals(traceId, spans.spans().get(0).traceId());
    assertTrue(spans.spans().get(0).startMicros() > 0);
    assertEquals(1, spans.spans().get(0).links().size());
    assertEquals(1, q.queryTracesAsync(TraceQuery.builder().service(dbService).build()).join().spans().size());

    List<TraceSummary> sum =
        q.searchTraces(
            TraceSearchQuery.builder()
                .service(traceService)
                .operation("GET /it-" + run)
                .minDurationMicros(0L)
                .limit(5)
                .build());
    assertEquals(1, sum.size(), sum.toString());
    assertEquals(traceId, sum.get(0).traceId());
    assertEquals(3, sum.get(0).spanCount());
    assertTrue(sum.get(0).startMicros() > 0);
    assertEquals(Optional.of(traceService), sum.get(0).rootServiceName());
    assertEquals(1, q.searchTracesAsync(TraceSearchQuery.forService(dbService)).join().size());

    List<String> services = q.listServices();
    assertTrue(services.contains(traceService) && services.contains(dbService), services.toString());
    assertTrue(q.listServicesAsync().join().contains(traceService));
    assertTrue(q.listServices(TimeRange.since(Instant.now().minus(Duration.ofMinutes(10)))).contains(traceService));
    assertTrue(q.listServicesAsync(TimeRange.defaults()).join().contains(dbService));
    assertEquals(List.of("GET /it-" + run, "call-db", "linked-" + run), q.listOperations(traceService));
    assertEquals(List.of("SELECT users"), q.listOperations(dbService, TimeRange.since(Instant.now().minus(Duration.ofMinutes(10)))));
    assertEquals(List.of("SELECT users"), q.listOperationsAsync(dbService).join());
    assertEquals(List.of("SELECT users"), q.listOperationsAsync(dbService, TimeRange.defaults()).join());
    AggregateResult agg =
        q.aggregate(
            AggregateQuery.builder(Signal.TRACES)
                .filter(FilterBuilder.create().eq("service.name", traceService))
                .count("calls")
                .percentile(99, "duration_nano", "p99")
                .groupBy("name")
                .build());
    assertEquals(3, agg.rows().size(), agg.toString());
    assertEquals(1.0, agg.rows().get(0).values().get("calls"));
    assertTrue(agg.rows().get(0).values().get("p99") > 0);
    assertTrue(q.getTrace("0af7651916cd43dd8448eb211c80319c").isEmpty());
    h.shutdown();
    hdb.shutdown();
  }

  @Test
  @Order(9)
  void dependencies() {
    Instant start = Instant.now().minus(Duration.ofMinutes(30));
    poll(
        "getDependencies",
        () -> {
          for (DependencyLink d : client.traces().getDependencies(start, null)) {
            if (d.parent().equals(traceService) && d.child().equals(dbService) && d.callCount() >= 1) {
              return true;
            }
          }
          return false;
        });
    assertFalse(client.traces().getDependenciesAsync(start, null).join().isEmpty());
  }

  @Test
  @Order(10)
  void closeIsIdempotentAndStopsTails() {
    SignozClient c = SignozClient.create(cfg);
    TailHandle t = c.query().tail("", r -> {}, TailOptions.defaults());
    assertNotNull(t);
    c.close();
    c.close();
    assertTrue(c.isClosed());
    assertInstanceOf(String.class, client.toString());
  }
}
