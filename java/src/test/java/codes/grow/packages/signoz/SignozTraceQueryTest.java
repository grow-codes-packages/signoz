package codes.grow.packages.signoz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import codes.grow.packages.signoz.core.MockServer;
import codes.grow.packages.signoz.core.PermissionDeniedException;
import codes.grow.packages.signoz.core.ValidationException;
import codes.grow.packages.signoz.logging.SortOrder;
import codes.grow.packages.signoz.tracing.query.DependencyLink;
import codes.grow.packages.signoz.tracing.query.Span;
import codes.grow.packages.signoz.tracing.query.Trace;
import codes.grow.packages.signoz.tracing.query.TraceSearchQuery;
import codes.grow.packages.signoz.tracing.query.TraceSummary;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SignozTraceQueryTest {
  private static final String TRACE = "fe67ae4bfe67ae4bfe67ae4bfe67ae4b";

  private MockServer server;
  private SignozClient client;

  @BeforeEach
  void start() throws Exception {
    server = MockServer.start();
    client = Fixtures.client(server);
  }

  @AfterEach
  void stop() {
    client.close();
    server.close();
  }

  @Test
  void queryTracesMapsRawSpans() {
    server.enqueue(200, Fixtures.read("queryTraces.raw.ok.json"));
    SpanQueryResult res =
        client
            .query()
            .queryTraces(
                TraceQuery.builder()
                    .service("sdk-fe67ae4b")
                    .operation("db.query")
                    .filter("duration_nano >= 1")
                    .offset(5)
                    .limit(10)
                    .order(SortOrder.ASC)
                    .start(Fixtures.NOW.minusSeconds(60))
                    .end(Fixtures.NOW)
                    .build());
    assertThat(res.spans()).isNotEmpty();
    Span db = res.spans().stream().filter(s -> s.name().equals("db.query")).findFirst().get();
    assertThat(db.traceId()).isEqualTo(TRACE);
    assertThat(db.kind()).isEqualTo(Span.Kind.CLIENT);
    assertThat(db.status().code()).isEqualTo(Span.StatusCode.ERROR);
    assertThat(db.startMicros())
        .isEqualTo(Instant.parse("2026-11-18T20:21:00.02Z").toEpochMilli() * 1000);
    assertThat(db.links())
        .singleElement()
        .extracting(Span.Link::spanId)
        .isEqualTo("b7ad6b7169203331");
    assertThat(db.events()).singleElement().extracting(Span.Event::name).isEqualTo("exception");
    assertThat(db.events().get(0).attributes()).containsEntry("exception.message", "boom");
    JsonNode spec = Fixtures.spec(server.requests().get(0));
    assertThat(spec.path("signal").asText()).isEqualTo("traces");
    assertThat(spec.path("filter").path("expression").asText())
        .isEqualTo(
            "(service.name = 'sdk-fe67ae4b' AND name = 'db.query') AND (duration_nano >= 1)");
    assertThat(spec.path("offset").asInt()).isEqualTo(5);
    assertThat(spec.path("limit").asInt()).isEqualTo(10);
    assertThat(spec.path("order").get(0).path("direction").asText()).isEqualTo("asc");
    assertThatThrownBy(() -> client.query().queryTraces(TraceQuery.builder().offset(-1).build()))
        .isInstanceOf(ValidationException.class);
    server.enqueue(200, Fixtures.read("queryLogs.empty.json"));
    SpanQueryResult empty = client.query().queryTraces(TraceQuery.builder().build());
    assertThat(empty.spans()).isEmpty();
    assertThat(Fixtures.spec(server.requests().get(1)).has("offset")).isFalse();
    assertThat(Fixtures.spec(server.requests().get(1)).has("filter")).isFalse();
  }

  @Test
  void getTraceFromTheWaterfall() {
    server
        .enqueue(200, Fixtures.read("getTrace.waterfall.ok.json"))
        .enqueue(404, Fixtures.read("getTrace.notFound.json"))
        .enqueue(200, "{\"status\":\"success\",\"data\":{\"spans\":[]}}")
        .enqueue(403, Fixtures.read("error.forbidden.json"));
    Trace t = client.traces().getTrace(TRACE.toUpperCase()).orElseThrow();
    assertThat(t.traceId()).isEqualTo(TRACE);
    assertThat(t.spans()).hasSize(3);
    Span root = t.root().orElseThrow();
    assertThat(root.name()).isEqualTo("GET /verify");
    assertThat(root.kind()).isEqualTo(Span.Kind.SERVER);
    assertThat(root.status().code()).isEqualTo(Span.StatusCode.OK);
    assertThat(root.startMicros()).isEqualTo(1795033260000L * 1000);
    assertThat(root.links()).isEmpty();
    Span db = t.spansNamed("db.query").get(0);
    assertThat(db.parentSpanId()).contains(root.spanId());
    assertThat(db.links())
        .singleElement()
        .extracting(Span.Link::traceId)
        .isEqualTo("0af7651916cd43dd8448eb211c80319c");
    assertThat(db.events().get(0).attributes()).containsEntry("exception.message", "boom");
    Span select = t.spansNamed("SELECT users").get(0);
    assertThat(select.status().code()).isEqualTo(Span.StatusCode.UNSET);
    assertThat(server.requests().get(0).uri()).isEqualTo("/api/v4/traces/" + TRACE + "/waterfall");
    assertThat(server.requests().get(0).method()).isEqualTo("POST");
    assertThat(client.traces().getTrace(TRACE)).isEmpty();
    assertThat(client.traces().getTraceAsync(TRACE).join()).isEmpty();
    assertThatThrownBy(() -> client.traces().getTrace(TRACE))
        .isInstanceOf(PermissionDeniedException.class);
    assertThatThrownBy(() -> client.traces().getTrace("not-hex"))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void windowedWaterfallIsCompletedWithRawSpans() {
    Map<String, Function<String, String>> routes = new LinkedHashMap<>();
    routes.put(
        "/api/v4/traces/",
        u ->
            "{\"status\":\"success\",\"data\":{\"startTimestampMillis\":1795033260000,"
                + "\"endTimestampMillis\":1795033260150,\"totalSpansCount\":5,\"hasMore\":true,"
                + "\"spans\":[{\"trace_id\":\""
                + TRACE
                + "\",\"span_id\":\"00f067aa0ba902b7\",\"name\":\"x\",\"time_unix\":1795033260000}]}}");
    routes.put("/api/v5/query_range", u -> Fixtures.read("queryTraces.raw.ok.json"));
    Fixtures.route(server, routes);
    Trace t = client.traces().getTrace(TRACE).orElseThrow();
    assertThat(t.spans()).hasSize(1);
    JsonNode body = Fixtures.body(server.requests().get(1));
    assertThat(body.path("start").asLong()).isEqualTo(1795033260000L);
    assertThat(body.path("end").asLong()).isEqualTo(1795033260151L);
    JsonNode spec = Fixtures.spec(server.requests().get(1));
    assertThat(spec.path("filter").path("expression").asText())
        .isEqualTo("trace_id = '" + TRACE + "'");
    assertThat(spec.path("limit").asInt()).isEqualTo(10_000);
  }

  @Test
  void searchTracesFillsFromTheWaterfall() {
    String two =
        "{\"status\":\"success\",\"data\":{\"data\":{\"results\":[{\"rows\":["
            + "{\"data\":{\"trace_id\":\""
            + TRACE
            + "\",\"service.name\":\"svc\",\"name\":\"root\",\"duration_nano\":150000000,\"span_count\":1}},"
            + "{\"data\":{\"trace_id\":\"0af7651916cd43dd8448eb211c80319c\",\"service.name\":\"svc2\","
            + "\"name\":\"r2\",\"duration_nano\":2000,\"span_count\":2}},"
            + "{\"data\":{\"trace_id\":\"zz\"}}]}]}}}";
    Map<String, Function<String, String>> routes = new LinkedHashMap<>();
    routes.put("/api/v5/query_range", u -> two);
    routes.put(
        "/api/v4/traces/",
        u -> u.contains(TRACE) ? Fixtures.read("getTrace.waterfall.ok.json") : null);
    Fixtures.route(server, routes);
    List<TraceSummary> res =
        client
            .traces()
            .searchTraces(
                TraceSearchQuery.builder()
                    .service("svc")
                    .operation("root")
                    .tag("http.method", "GET")
                    .tag("a.b", "1")
                    .minDurationMicros(10L)
                    .maxDurationMicros(1_000_000L)
                    .limit(5)
                    .build());
    assertThat(res).hasSize(2);
    TraceSummary first = res.get(0);
    assertThat(first.traceId()).isEqualTo(TRACE);
    assertThat(first.startMicros()).isEqualTo(1795033260000L * 1000);
    assertThat(first.spanCount()).isEqualTo(3);
    assertThat(first.rootServiceName()).contains("sdk-fe67ae4b");
    assertThat(first.rootName()).contains("GET /verify");
    assertThat(first.durationMicros()).isEqualTo(150_000);
    TraceSummary second = res.get(1);
    assertThat(second.startMicros()).isZero();
    assertThat(second.spanCount()).isEqualTo(2);
    assertThat(second.rootServiceName()).contains("svc2");
    MockServer.Recorded search =
        server.requests().stream().filter(r -> r.uri().contains("query_range")).findFirst().get();
    assertThat(Fixtures.body(search).path("requestType").asText()).isEqualTo("trace");
    assertThat(Fixtures.spec(search).path("filter").path("expression").asText())
        .isEqualTo(
            "service.name = 'svc' AND name = 'root' AND a.b = '1' AND http.method = 'GET'"
                + " AND duration_nano >= 10000 AND duration_nano <= 1000000000");
    assertThat(Fixtures.spec(search).path("limit").asInt()).isEqualTo(5);
    assertThat(client.traces().searchTracesAsync(TraceSearchQuery.forService("svc")).join())
        .hasSize(2);
    assertThat(client.query().searchTraces(null)).hasSize(2);
  }

  @Test
  void searchTracesInBatchesOfFour() {
    StringBuilder rows = new StringBuilder();
    for (int i = 1; i <= 6; i++) {
      if (i > 1) {
        rows.append(',');
      }
      rows.append("{\"data\":{\"trace_id\":\"").append(String.format("%032x", i)).append("\"}}");
    }
    String body =
        "{\"status\":\"success\",\"data\":{\"data\":{\"results\":[{\"rows\":[" + rows + "]}]}}}";
    Map<String, Function<String, String>> routes = new LinkedHashMap<>();
    routes.put("/api/v5/query_range", u -> body);
    routes.put("/api/v4/traces/", u -> null);
    Fixtures.route(server, routes);
    List<TraceSummary> res = client.traces().searchTraces(TraceSearchQuery.builder().build());
    assertThat(res).hasSize(6);
    assertThat(res.get(5).traceId()).isEqualTo(String.format("%032x", 6));
    assertThat(res.get(0).rootServiceName()).isEmpty();
  }

  @Test
  void servicesOperationsAndDependencies() {
    server
        .enqueue(200, Fixtures.read("listServices.scalar.json"))
        .enqueue(200, Fixtures.read("listServices.scalar.json"))
        .enqueue(200, Fixtures.read("listOperations.scalar.json"))
        .enqueue(200, Fixtures.read("listOperations.scalar.json"))
        .enqueue(200, Fixtures.read("getDependencies.ok.json"));
    List<String> services = client.traces().listServices();
    assertThat(services).isNotEmpty().isSorted().contains("sdk-fe67ae4b");
    JsonNode spec = Fixtures.spec(server.requests().get(0));
    assertThat(Fixtures.body(server.requests().get(0)).path("requestType").asText())
        .isEqualTo("scalar");
    assertThat(spec.path("groupBy").get(0).path("name").asText()).isEqualTo("service.name");
    assertThat(spec.path("groupBy").get(0).path("fieldContext").asText()).isEqualTo("resource");
    assertThat(spec.path("limit").asInt()).isEqualTo(10_000);
    assertThat(client.traces().listServicesAsync().join()).isEqualTo(services);
    List<String> ops = client.traces().listOperations("sdk-fe67ae4b");
    assertThat(ops).isNotEmpty().isSorted();
    JsonNode opSpec = Fixtures.spec(server.requests().get(2));
    assertThat(opSpec.path("filter").path("expression").asText())
        .isEqualTo("service.name = 'sdk-fe67ae4b'");
    assertThat(opSpec.path("groupBy").get(0).path("fieldContext").asText()).isEqualTo("span");
    assertThat(client.traces().listOperationsAsync("sdk-fe67ae4b").join()).isEqualTo(ops);
    assertThatThrownBy(() -> client.traces().listOperations(" "))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> client.query().listOperations(null, TimeRange.defaults()))
        .isInstanceOf(ValidationException.class);
    Instant from = Fixtures.NOW.minus(Duration.ofMinutes(30));
    List<DependencyLink> deps = client.traces().getDependencies(from, null);
    assertThat(deps)
        .containsExactly(
            new DependencyLink("sdk-fe67ae4b", "postgresql", 1),
            new DependencyLink("sdk-fe67ae4b", "sdk-db-fe67ae4b", 1));
    JsonNode body = Fixtures.body(server.requests().get(4));
    assertThat(server.requests().get(4).uri()).isEqualTo("/api/v1/dependency_graph");
    assertThat(body.path("start").asText())
        .isEqualTo(Long.toString(from.toEpochMilli() * 1_000_000L));
    assertThat(body.path("end").asText())
        .isEqualTo(Long.toString(Fixtures.NOW.toEpochMilli() * 1_000_000L));
    assertThat(body.path("tags").isArray()).isTrue();
  }

  @Test
  void servicesInARange() {
    server.fallback(ex -> MockServer.send(ex, 200, Fixtures.read("listServices.scalar.json")));
    Instant from = Fixtures.NOW.minusSeconds(600);
    assertThat(client.query().listServices(TimeRange.since(from))).isNotEmpty();
    assertThat(Fixtures.body(server.requests().get(0)).path("start").asLong())
        .isEqualTo(from.toEpochMilli());
    assertThat(client.query().listServices(null)).isNotEmpty();
    assertThat(client.query().listOperations("x", new TimeRange(from, Fixtures.NOW))).isEmpty();
    assertThat(TimeRange.since(from).startOptional()).contains(from);
    assertThat(TimeRange.defaults().endOptional()).isEmpty();
    assertThat(client.traces().getDependenciesAsync(null, null)).isNotNull();
  }

  @Test
  void aggregateScalarTimeSeriesAndValidation() {
    String grouped =
        "{\"status\":\"success\",\"data\":{\"data\":{\"results\":[{\"columns\":["
            + "{\"name\":\"name\",\"columnType\":\"group\"},"
            + "{\"name\":\"__result_0\",\"columnType\":\"aggregation\",\"aggregationIndex\":0},"
            + "{\"name\":\"__result_1\",\"columnType\":\"aggregation\",\"aggregationIndex\":1},"
            + "{\"name\":\"__result_7\",\"columnType\":\"aggregation\",\"aggregationIndex\":7}],"
            + "\"data\":[[\"db.query\",1,100000000,3],[\"GET /verify\",1,null]]}]}}}";
    server
        .enqueue(200, grouped)
        .enqueue(200, Fixtures.read("aggregate.count.json"))
        .enqueue(200, Fixtures.read("aggregate.timeSeries.json"))
        .enqueue(200, "{\"status\":\"success\",\"data\":{\"data\":{\"results\":[]}}}");
    SignozQueryClient q = client.query();
    AggregateResult r =
        q.aggregate(Signal.TRACES)
            .filter(FilterBuilder.create().eq("service.name", "x"))
            .count("calls")
            .p99("duration_nano", "p99")
            .groupBy("name")
            .orderBy("calls desc")
            .limit(5)
            .run();
    assertThat(r.rows()).hasSize(2);
    assertThat(r.rows().get(0).groups()).containsEntry("name", "db.query");
    assertThat(r.rows().get(0).values())
        .containsEntry("calls", 1.0)
        .containsEntry("p99", 1.0e8)
        .containsEntry("__result_7", 3.0);
    assertThat(r.rows().get(1).values()).doesNotContainKey("p99");
    JsonNode spec = Fixtures.spec(server.requests().get(0));
    assertThat(spec.path("aggregations").get(0).path("expression").asText()).isEqualTo("count()");
    assertThat(spec.path("aggregations").get(0).path("alias").asText()).isEqualTo("calls");
    assertThat(spec.path("aggregations").get(1).path("expression").asText())
        .isEqualTo("p99(duration_nano)");
    assertThat(spec.path("order").get(0).path("key").path("name").asText()).isEqualTo("calls");
    assertThat(spec.path("order").get(0).path("direction").asText()).isEqualTo("desc");
    assertThat(spec.path("limit").asInt()).isEqualTo(5);

    AggregateQuery count =
        AggregateQuery.builder(Signal.LOGS)
            .expression("count()", null)
            .between(Fixtures.NOW.minusSeconds(60), Fixtures.NOW)
            .build();
    assertThat(q.aggregate(count).rows().get(0).values()).containsEntry("count()", 12.0);
    assertThat(count.startOptional()).isPresent();
    assertThat(count.isTimeSeries()).isFalse();

    AggregateResult ts =
        q.aggregate(Signal.LOGS)
            .count("n")
            .step(Duration.ofMillis(10))
            .groupBy("service.name")
            .runAsync()
            .join();
    assertThat(ts.series())
        .singleElement()
        .satisfies(
            s -> {
              assertThat(s.alias()).isEqualTo("n");
              assertThat(s.points())
                  .singleElement()
                  .extracting(AggregatePoint::value)
                  .isEqualTo(12.0);
              assertThat(s.points().get(0).time()).isEqualTo(Instant.ofEpochMilli(1795033260000L));
            });
    JsonNode tsBody = Fixtures.body(server.requests().get(2));
    assertThat(tsBody.path("requestType").asText()).isEqualTo("time_series");
    assertThat(Fixtures.spec(server.requests().get(2)).path("stepInterval").asLong()).isEqualTo(1);
    assertThat(q.aggregate(AggregateQuery.builder(Signal.TRACES).count(null).build()).rows())
        .isEmpty();

    assertThatThrownBy(() -> q.aggregate(AggregateQuery.builder(null).count("x").build()))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> q.aggregate(AggregateQuery.builder(Signal.LOGS).build()))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(
            () -> q.aggregate(AggregateQuery.builder(Signal.LOGS).expression(" ", "x").build()))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(
            () ->
                q.aggregate(
                    AggregateQuery.builder(Signal.LOGS)
                        .count("x")
                        .step(Duration.ofSeconds(-1))
                        .build()))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(
            () ->
                q.aggregate(AggregateQuery.builder(Signal.LOGS).count("x").groupBy("a b").build()))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> AggregateQuery.builder(Signal.LOGS).orderBy("x sideways"))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> AggregateQuery.builder(Signal.LOGS).count("x").run())
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> AggregateQuery.builder(Signal.LOGS).count("x").runAsync())
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void aggregateBuilderHelpersRenderExpressions() {
    AggregateQuery q =
        AggregateQuery.builder(Signal.TRACES)
            .filter((String) null)
            .countDistinct("trace_id", "traces")
            .sum("n", null)
            .avg("d", "avg")
            .min("d", "min")
            .max("d", "max")
            .percentile(75, "d", null)
            .p50("d", null)
            .p90("d", null)
            .p95("d", null)
            .orderBy("name asc")
            .orderBy("name")
            .orderBy("name", null)
            .filter((FilterBuilder) null)
            .build();
    assertThat(q.aggregations())
        .extracting(Aggregation::name)
        .containsExactly(
            "traces", "sum(n)", "avg", "min", "max", "p75(d)", "p50(d)", "p90(d)", "p95(d)");
    assertThat(q.orderKey()).isEqualTo("name");
    assertThat(q.orderDirection()).isNull();
    assertThat(new Aggregation("count()", "").name()).isEqualTo("count()");
    assertThat(AggregateQuery.builder(Signal.TRACES).orderBy("x asc").build().orderDirection())
        .isEqualTo(SortOrder.ASC);
    assertThat(AggregateQuery.builder(Signal.TRACES).orderBy(null).build().orderKey()).isEmpty();
    AggregateQuery nulls =
        new AggregateQuery(Signal.LOGS, null, null, null, null, null, null, 0, null, null);
    assertThat(nulls.filter()).isEmpty();
    assertThat(nulls.aggregations()).isEmpty();
    assertThat(new AggregateResult(null, null, null).rows()).isEmpty();
    assertThat(new AggregateRow(null, null).groups()).isEmpty();
    assertThat(new AggregateSeries("a", null, null).points()).isEmpty();
    assertThat(Optional.of(q).map(AggregateQuery::isTimeSeries)).contains(false);
  }
}
