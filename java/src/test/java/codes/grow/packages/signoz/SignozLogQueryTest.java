package codes.grow.packages.signoz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import codes.grow.packages.signoz.core.AuthenticationException;
import codes.grow.packages.signoz.core.Logger;
import codes.grow.packages.signoz.core.MockServer;
import codes.grow.packages.signoz.core.UnavailableException;
import codes.grow.packages.signoz.core.UnsupportedException;
import codes.grow.packages.signoz.core.ValidationException;
import codes.grow.packages.signoz.logging.LogRecord;
import codes.grow.packages.signoz.logging.LogSearchResult;
import codes.grow.packages.signoz.logging.SearchOptions;
import codes.grow.packages.signoz.logging.Severity;
import codes.grow.packages.signoz.logging.SortOrder;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SignozLogQueryTest {
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
  void queryLogsMapsRowsAndBuildsTheRequest() {
    server.enqueue(200, Fixtures.read("queryLogs.raw.ok.json"));
    LogQueryResult page =
        client
            .query()
            .queryLogs(
                LogQuery.builder()
                    .filter(FilterBuilder.create().eq("service.name", "sdk-fe67ae4b"))
                    .selectFields("body")
                    .build());
    assertThat(page.records()).hasSize(6);
    assertThat(page.nextCursor()).isEmpty();
    assertThat(page.warnings()).isEmpty();
    LogRecord r = page.records().get(0);
    assertThat(r.body()).isEqualTo("marker-fe67ae4b seq 5 ünïcode it's");
    assertThat(r.timestamp()).isEqualTo(Instant.parse("2026-11-18T20:21:00.005Z"));
    assertThat(r.severity()).isEqualTo(Severity.INFO);
    assertThat(r.severityText()).isEqualTo("INFO");
    assertThat(r.traceId()).contains("fe67ae4bfe67ae4bfe67ae4bfe67ae4b");
    assertThat(r.spanId()).contains("00f067aa0ba902b7");
    assertThat(r.traceFlags()).hasValue(1);
    assertThat(r.attributes())
        .containsEntry("ok", true)
        .containsEntry("seq", 5L)
        .containsEntry("run.id", "fe67ae4b");
    assertThat(r.resource()).containsEntry("service.name", "sdk-fe67ae4b");
    assertThat(page.records().get(5).severity()).isEqualTo(Severity.ERROR);

    MockServer.Recorded req = server.requests().get(0);
    assertThat(req.method()).isEqualTo("POST");
    assertThat(req.uri()).isEqualTo("/api/v5/query_range");
    assertThat(req.header("SIGNOZ-API-KEY")).isEqualTo(Fixtures.KEY);
    JsonNode body = Fixtures.body(req);
    assertThat(body.path("end").asLong()).isEqualTo(Fixtures.NOW.toEpochMilli());
    assertThat(body.path("start").asLong()).isEqualTo(Fixtures.NOW.toEpochMilli() - 3_600_000L);
    assertThat(body.path("requestType").asText()).isEqualTo("raw");
    JsonNode spec = Fixtures.spec(req);
    assertThat(spec.path("signal").asText()).isEqualTo("logs");
    assertThat(spec.path("filter").path("expression").asText())
        .isEqualTo("service.name = 'sdk-fe67ae4b'");
    assertThat(spec.path("limit").asInt()).isEqualTo(100);
    assertThat(spec.path("order").get(0).path("direction").asText()).isEqualTo("desc");
    assertThat(spec.path("order").get(1).path("key").path("name").asText()).isEqualTo("id");
    assertThat(spec.path("selectFields").get(0).path("name").asText()).isEqualTo("body");
  }

  @Test
  void keysetCursorRepeatsTheBoundaryMillisecond() {
    // two rows in the same millisecond, then the next page
    String page1 =
        "{\"status\":\"success\",\"data\":{\"data\":{\"results\":[{\"rows\":["
            + row("b", "2026-11-18T20:21:00.005Z", "five")
            + ","
            + row("a", "2026-11-18T20:21:00.005Z", "five-bis")
            + "]}]}}}";
    String page2 =
        "{\"status\":\"success\",\"data\":{\"data\":{\"results\":[{\"rows\":["
            + row("z", "2026-11-18T20:21:00.005Z", "five-ter")
            + ","
            + row("y", "2026-11-18T20:21:00.004Z", "four")
            + "]}]}}}";
    String page3 =
        "{\"status\":\"success\",\"data\":{\"data\":{\"results\":[{\"rows\":["
            + row("x", "2026-11-18T20:21:00.004Z", "four-bis")
            + "]}]}}}";
    server.enqueue(200, page1).enqueue(200, page2).enqueue(200, page3);
    SignozQueryClient q = client.query();
    LogQueryResult p1 = q.queryLogs(LogQuery.builder().filter("a = 1").limit(2).build());
    assertThat(p1.nextCursor()).isPresent();
    LogQueryResult p2 =
        q.queryLogs(
            LogQuery.builder().filter("a = 1").limit(2).cursor(p1.nextCursor().get()).build());
    LogQueryResult p3 =
        q.queryLogs(
            LogQuery.builder().filter("a = 1").limit(2).cursor(p2.nextCursor().get()).build());
    assertThat(p3.nextCursor()).isEmpty();
    long boundary = Instant.parse("2026-11-18T20:21:00.005Z").toEpochMilli();
    JsonNode b2 = Fixtures.body(server.requests().get(1));
    assertThat(b2.path("end").asLong()).isEqualTo(boundary + 1);
    assertThat(b2.path("start").asLong()).isEqualTo(Fixtures.NOW.toEpochMilli() - 3_600_000L);
    assertThat(Fixtures.spec(server.requests().get(1)).path("filter").path("expression").asText())
        .isEqualTo("(a = 1) AND (id NOT IN ('b', 'a'))");
    // the boundary moved: only ids of the new boundary millisecond are excluded
    JsonNode b3 = Fixtures.body(server.requests().get(2));
    assertThat(b3.path("end").asLong()).isEqualTo(boundary);
    assertThat(Fixtures.spec(server.requests().get(2)).path("filter").path("expression").asText())
        .isEqualTo("(a = 1) AND (id NOT IN ('y'))");
  }

  @Test
  void ascendingCursorMovesTheStartAndKeepsIdsOfTheSameBoundary() {
    String page =
        "{\"status\":\"success\",\"data\":{\"data\":{\"results\":[{\"rows\":["
            + row("a", "2026-11-18T20:21:00.001Z", "one")
            + "]}]}}}";
    server.enqueue(200, page).enqueue(200, page);
    SignozQueryClient q = client.query();
    Instant start = Instant.parse("2026-11-18T20:00:00Z");
    LogQueryResult p1 =
        q.queryLogs(LogQuery.builder().start(start).limit(1).order(SortOrder.ASC).build());
    LogQueryResult p2 =
        q.queryLogs(LogQuery.builder().limit(1).cursor(p1.nextCursor().get()).build());
    long ms = Instant.parse("2026-11-18T20:21:00.001Z").toEpochMilli();
    JsonNode b2 = Fixtures.body(server.requests().get(1));
    assertThat(b2.path("start").asLong()).isEqualTo(ms);
    assertThat(b2.path("end").asLong()).isEqualTo(Fixtures.NOW.toEpochMilli());
    assertThat(
            Fixtures.spec(server.requests().get(1)).path("order").get(0).path("direction").asText())
        .isEqualTo("asc");
    assertThat(Fixtures.spec(server.requests().get(1)).path("filter").path("expression").asText())
        .isEqualTo("id NOT IN ('a')");
    // same boundary again: the earlier ids stay excluded (deduplicated)
    SignozQueryClient.LogCursor c = SignozQueryClient.LogCursor.decode("t", p2.nextCursor().get());
    assertThat(c.ids()).containsExactly("a");
    assertThat(c.order()).isEqualTo(SortOrder.ASC);
  }

  @Test
  void queryRangePostsARawBody() {
    server.enqueue(
        200,
        "{\"status\":\"success\",\"data\":{\"type\":\"scalar\",\"warning\":{\"warnings\":"
            + "[{\"message\":\"w1\"}]},\"data\":{\"results\":[]}}}");
    server.enqueue(200, "{\"status\":\"success\"}");
    QueryRangeResult res =
        client.query().queryRange(Map.of("start", 1, "end", 2, "requestType", "scalar"));
    assertThat(res.warnings()).containsExactly("w1");
    assertThat(res.data().path("type").asText()).isEqualTo("scalar");
    JsonNode body = Fixtures.body(server.requests().get(0));
    assertThat(body.path("requestType").asText()).isEqualTo("scalar");
    assertThat(server.requests().get(0).header("SIGNOZ-API-KEY")).isEqualTo(Fixtures.KEY);
    QueryRangeResult empty = client.query().queryRangeAsync(Map.of()).join();
    assertThat(empty.data().isNull()).isTrue();
    assertThat(empty.warnings()).isEmpty();
    assertThat(new QueryRangeResult(null, null).warnings()).isEmpty();
  }

  @Test
  void goCursorWithNullOrMissingIdsIsAccepted() {
    // Go encodes an empty id list as "x": null; a missing "x" is accepted too.
    SignozQueryClient.LogCursor nullIds =
        SignozQueryClient.LogCursor.decode(
            "t",
            Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                    "{\"v\":1,\"s\":1000,\"e\":5000,\"o\":\"asc\",\"m\":2000,\"x\":null}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    assertThat(nullIds.ids()).isEmpty();
    assertThat(nullIds.ms()).isEqualTo(2000);
    SignozQueryClient.LogCursor missing =
        SignozQueryClient.LogCursor.decode(
            "t",
            Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                    "{\"v\":1,\"s\":1000,\"e\":5000,\"o\":\"desc\",\"m\":2000}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    assertThat(missing.ids()).isEmpty();
    assertThat(missing.order()).isEqualTo(SortOrder.DESC);
  }

  private static String row(String id, String ts, String body) {
    return "{\"timestamp\":\""
        + ts
        + "\",\"data\":{\"id\":\""
        + id
        + "\",\"body\":\""
        + body
        + "\",\"severity_text\":\"WARN\",\"trace_id\":\"\",\"span_id\":\"\"}}";
  }

  @Test
  void invalidInputIsRejectedBeforeAnyRequest() {
    SignozQueryClient q = client.query();
    assertThatThrownBy(() -> q.queryLogs(LogQuery.builder().limit(-1).build()))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("limit");
    assertThatThrownBy(() -> q.queryLogs(LogQuery.builder().limit(10_001).build()))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> q.queryLogs(LogQuery.builder().cursor("%%%").build()))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("invalid cursor");
    String wrong =
        Base64.getUrlEncoder()
            .encodeToString("{\"v\":2}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertThatThrownBy(() -> q.queryLogs(LogQuery.builder().cursor(wrong).build()))
        .isInstanceOf(ValidationException.class);
    String numericIds =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                "{\"v\":1,\"s\":1,\"e\":5,\"o\":\"asc\",\"m\":2,\"x\":[1]}"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertThatThrownBy(() -> q.queryLogs(LogQuery.builder().cursor(numericIds).build()))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("invalid cursor");
    Instant t = Instant.parse("2026-11-18T20:00:00Z");
    assertThatThrownBy(() -> q.queryLogs(LogQuery.builder().start(t).end(t).build()))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("start must be before end");
    assertThatThrownBy(
            () -> q.queryLogs(LogQuery.builder().filter(FilterBuilder.create().in("x")).build()))
        .isInstanceOf(ValidationException.class);
    assertThat(server.requests()).isEmpty();
    assertThatThrownBy(() -> q.queryLogsAsync(LogQuery.builder().limit(-5).build()).join())
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(ValidationException.class);
  }

  @Test
  void emptyRowsWarningsAndErrors() {
    List<String> warned = new ArrayList<>();
    Logger logger =
        new Logger() {
          @Override
          public void warn(String message, Map<String, Object> fields) {
            warned.add(String.valueOf(fields.get("warning")));
          }
        };
    try (SignozClient c =
        SignozClient.create(
            Fixtures.config(server).logger(logger).build(),
            Clock.fixed(Fixtures.NOW, ZoneOffset.UTC))) {
      server
          .enqueue(200, Fixtures.read("queryLogs.empty.json"))
          .enqueue(200, Fixtures.read("queryLogs.unknownFieldWarning.json"))
          .enqueue(200, "{\"status\":\"success\",\"data\":{\"warning\":{\"message\":\"only\"}}}")
          .enqueue(400, Fixtures.read("queryLogs.invalidFilter.json"))
          .enqueue(401, Fixtures.read("queryLogs.unauthenticated.json"))
          .enqueue(200, "<!DOCTYPE html><html></html>", "Content-Type", "text/html")
          .enqueue(200, "{\"status\":\"error\"}")
          .enqueue(200, "garbage", "Content-Type", "application/json");
      SignozQueryClient q = c.query();
      assertThat(q.queryLogs(LogQuery.defaults()).records()).isEmpty();
      LogQueryResult w = q.queryLogs(LogQuery.defaults());
      assertThat(w.records()).isEmpty();
      assertThat(w.warnings()).singleElement().asString().contains("nosuchfield_fe67ae4b");
      assertThat(warned).hasSize(1);
      assertThat(q.queryLogs(LogQuery.defaults()).warnings()).containsExactly("only");
      assertThatThrownBy(() -> q.queryLogs(LogQuery.defaults()))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("syntax");
      assertThatThrownBy(() -> q.queryLogs(LogQuery.defaults()))
          .isInstanceOf(AuthenticationException.class)
          .message()
          .doesNotContain(Fixtures.KEY);
      assertThatThrownBy(() -> q.queryLogs(LogQuery.defaults()))
          .isInstanceOf(UnsupportedException.class);
      assertThatThrownBy(() -> q.queryLogs(LogQuery.defaults()))
          .isInstanceOf(UnavailableException.class)
          .hasMessageContaining("unexpected response status");
      assertThatThrownBy(() -> q.queryLogs(LogQuery.defaults()))
          .isInstanceOf(UnavailableException.class)
          .hasMessageContaining("invalid JSON");
    }
  }

  @Test
  void searchAndWaitForLogUseTheLogCategoryInterface() {
    server.fallback(ex -> MockServer.send(ex, 200, Fixtures.read("queryLogs.page1.json")));
    LogSearchResult page =
        client
            .logs()
            .search(
                "marker-fe67ae4b",
                SearchOptions.builder()
                    .limit(2)
                    .order(SortOrder.DESC)
                    .start(Fixtures.NOW.minusSeconds(600))
                    .end(Fixtures.NOW)
                    .build());
    assertThat(page.records()).hasSize(2);
    assertThat(page.nextCursor()).isPresent();
    assertThat(page.total()).isEmpty();
    assertThat(client.logs().searchAsync("x", null).join().records()).hasSize(2);
    assertThat(client.logs().waitForLog("marker").body()).contains("seq 5");
    JsonNode spec = Fixtures.spec(server.requests().get(0));
    assertThat(spec.path("filter").path("expression").asText()).isEqualTo("marker-fe67ae4b");
    assertThat(spec.path("limit").asInt()).isEqualTo(2);
  }

  @Test
  void fieldsLabelsStreamsAndValues() {
    server
        .enqueue(200, Fixtures.read("listLogFields.ok.json"))
        .enqueue(200, Fixtures.read("listLogFields.search.json"))
        .enqueue(200, Fixtures.read("listLogFields.ok.json"))
        .enqueue(200, Fixtures.read("listStreams.fieldValues.json"))
        .enqueue(
            200,
            "{\"status\":\"success\",\"data\":{\"values\":{\"stringValues\":[\"b\",\"a\",\"a\"],"
                + "\"numberValues\":[2,1.5,null],\"boolValues\":[true]}}}")
        .enqueue(200, "{\"status\":\"success\",\"data\":{}}");
    SignozQueryClient q = client.query();
    List<LogField> fields = q.listLogFields();
    assertThat(fields).isNotEmpty();
    assertThat(fields).extracting(LogField::name).isSorted();
    assertThat(fields)
        .anyMatch(f -> f.name().equals("service.name") && f.context().equals("resource"));
    List<LogField> searched = q.listLogFields(new ListFieldsOptions("run", 5));
    assertThat(searched).isNotEmpty();
    assertThat(server.requests().get(1).uri())
        .contains("searchText=run")
        .contains("limit=5")
        .contains("signal=logs");
    List<String> labels = client.logs().listLabels();
    assertThat(labels).isSorted().doesNotHaveDuplicates().contains("service.name");
    assertThat(client.logs().listStreams())
        .containsExactly("lat-fe67ae4b", "sdk-fe67ae4b", "tg-grpc-fe67ae4b", "tg-httppb-fe67ae4b");
    assertThat(server.requests().get(3).uri()).contains("name=service.name");
    List<Object> values = q.listFieldValues("seq", new FieldValuesOptions(Signal.TRACES, "x"));
    assertThat(values).containsExactly(1.5, 2L, "a", "b", true);
    assertThat(server.requests().get(4).uri()).contains("signal=traces").contains("searchText=x");
    assertThat(q.listFieldValuesAsync("seq", null).join()).isEmpty();
    assertThatThrownBy(() -> q.listFieldValues("bad name", FieldValuesOptions.defaults()))
        .isInstanceOf(ValidationException.class);
    assertThat(q.listLogFieldsAsync(null)).isNotNull();
  }

  @Test
  void asyncVariantsOfTheCategoryMethods() {
    server.fallback(ex -> MockServer.send(ex, 200, Fixtures.read("listStreams.fieldValues.json")));
    assertThat(client.logs().listStreamsAsync().join()).hasSize(4);
    server.fallback(ex -> MockServer.send(ex, 200, Fixtures.read("listLogFields.ok.json")));
    assertThat(client.logs().listLabelsAsync().join()).isNotEmpty();
    assertThat(client.query().toString()).contains(server.url());
  }
}
