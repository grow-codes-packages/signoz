package codes.grow.packages.signoz;

import static org.assertj.core.api.Assertions.assertThat;

import codes.grow.packages.signoz.core.Json;
import codes.grow.packages.signoz.logging.LogRecord;
import codes.grow.packages.signoz.logging.Severity;
import codes.grow.packages.signoz.tracing.query.Span;
import codes.grow.packages.signoz.tracing.query.TraceSummary;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RowsTest {
  private static JsonNode json(String s) {
    return Json.tree(s.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void scalarHelpers() {
    JsonNode n =
        json(
            "{\"s\":\"x\",\"i\":12345678901234567,\"f\":2.9,\"t\":\" 42 \",\"bad\":\"x\",\"b\":true,"
                + "\"o\":{},\"z\":null,\"big\":123456789012345678901234567890}");
    assertThat(Rows.str(n, "s")).isEqualTo("x");
    assertThat(Rows.str(n, "i")).isEqualTo("12345678901234567");
    assertThat(Rows.str(n, "b")).isEqualTo("true");
    assertThat(Rows.str(n, "o")).isEmpty();
    assertThat(Rows.str(n, "z")).isEmpty();
    assertThat(Rows.str(n, "missing")).isEmpty();
    assertThat(Rows.str(null, "x")).isEmpty();
    assertThat(Rows.num(n, "i")).hasValue(12345678901234567L);
    assertThat(Rows.num(n, "f")).hasValue(2);
    assertThat(Rows.num(n, "t")).hasValue(42);
    assertThat(Rows.num(n, "bad")).isEmpty();
    assertThat(Rows.num(n, "b")).isEmpty();
    assertThat(Rows.num(n, "missing")).isEmpty();
    assertThat(Rows.num(null, "x")).isEmpty();
    assertThat(Rows.plain(n.get("big"))).isInstanceOf(Double.class);
    assertThat(Rows.plain(json("[1,\"a\",[true],{\"k\":null}]")))
        .isEqualTo(List.of(1L, "a", List.of(true), java.util.Collections.singletonMap("k", null)));
    assertThat(Rows.plain(null)).isNull();
    assertThat(Rows.attrs(json("{\"a\":1,\"n\":null}"), null, json("[1]")))
        .isEqualTo(Map.of("a", 1L));
  }

  @Test
  void timestamps() {
    assertThat(
            Rows.rowNanos(json("{\"timestamp\":\"2026-11-18T20:21:00.000000001Z\",\"data\":{}}")))
        .isEqualTo(Instant.parse("2026-11-18T20:21:00Z").getEpochSecond() * 1_000_000_000L + 1);
    assertThat(Rows.rowNanos(json("{\"data\":{\"timestamp\":\"2026-11-18T20:21:00Z\"}}")))
        .isEqualTo(Instant.parse("2026-11-18T20:21:00Z").getEpochSecond() * 1_000_000_000L);
    assertThat(Rows.rowNanos(json("{\"data\":{\"timestamp\":\"garbage\"}}"))).isZero();
    assertThat(Rows.rowMillis(json("{\"data\":{\"timestamp\":1795033284000000000}}")))
        .isEqualTo(1795033284000L);
    assertThat(Rows.instantOfNanos(-1)).isEqualTo(Instant.EPOCH.minusNanos(1));
  }

  @Test
  void logRecordFallbacks() {
    LogRecord textOnly =
        Rows.logRecord(
            json(
                "{\"data\":{\"body\":\"b\",\"severity_text\":\"warning\",\"trace_id\":\"XYZ\","
                    + "\"span_id\":\"00000000000000000\",\"timestamp\":1}}"));
    assertThat(textOnly.severity()).isEqualTo(Severity.WARN);
    assertThat(textOnly.severityText()).isEqualTo("warning");
    assertThat(textOnly.traceId()).isEmpty();
    assertThat(textOnly.spanId()).isEmpty();
    LogRecord numberOnly =
        Rows.logRecord(
            json("{\"data\":{\"severity_number\":21,\"span_id\":\"0000000000000000\"}}"));
    assertThat(numberOnly.severity()).isEqualTo(Severity.FATAL);
    assertThat(numberOnly.severityText()).isEqualTo("FATAL");
    assertThat(numberOnly.spanId()).isEmpty();
    LogRecord none = Rows.logRecord(json("{\"data\":{\"severity_text\":\"\"}}"));
    assertThat(none.severity()).isEqualTo(Severity.INFO);
    LogRecord badHex =
        Rows.logRecord(json("{\"data\":{\"trace_id\":\"4bf92f3577b34da6a3ce929d0e0e47zz\"}}"));
    assertThat(badHex.traceId()).isEmpty();
  }

  @Test
  void spanFallbacks() {
    JsonNode d =
        json(
            "{\"trace_id\":\"4bf92f3577b34da6a3ce929d0e0e4736\",\"span_id\":\"00f067aa0ba902b7\","
                + "\"name\":\"n\",\"status_code_string\":\"Error\",\"kind_string\":\"Weird\","
                + "\"parent_span_id\":\"\",\"duration_nano\":5000,\"time_unix\":1,"
                + "\"events\":[1,{\"name\":\"e\"}],"
                + "\"references\":[{\"refType\":\"FOLLOWS_FROM\",\"traceId\":\"xyz\",\"spanId\":\"1\"},"
                + "{\"refType\":\"FOLLOWS_FROM\",\"traceId\":\"4bf92f3577b34da6a3ce929d0e0e4736\"}]}");
    Span s = Rows.spanFromWaterfall(d);
    assertThat(s.status().code()).isEqualTo(Span.StatusCode.ERROR);
    assertThat(s.kind()).isEqualTo(Span.Kind.UNSPECIFIED);
    assertThat(s.parentSpanId()).isEmpty();
    assertThat(s.durationMicros()).isEqualTo(5);
    assertThat(s.startMicros()).isEqualTo(1000);
    assertThat(s.events()).hasSize(1);
    assertThat(s.links()).isEmpty();
    assertThat(Rows.statusCode(json("{\"status_code_string\":\"Ok\"}")))
        .isEqualTo(Span.StatusCode.OK);
    assertThat(Rows.statusCode(json("{}"))).isEqualTo(Span.StatusCode.UNSET);
    JsonNode raw =
        json(
            "{\"timestamp\":\"2026-11-18T20:21:00Z\",\"data\":{\"trace_id\":\"4bf92f3577b34da6a3ce929d0e0e4736\","
                + "\"span_id\":\"00f067aa0ba902b7\",\"name\":\"n\",\"status_code\":0,"
                + "\"links\":[{\"traceId\":\"4bf92f3577b34da6a3ce929d0e0e4736\",\"spanId\":\"\"},"
                + "{\"traceId\":\"nothex\",\"spanId\":\"1111111111111111\"}]}}");
    Span r = Rows.spanFromRaw(raw);
    assertThat(r.links()).isEmpty();
    assertThat(r.status().code()).isEqualTo(Span.StatusCode.UNSET);
  }

  @Test
  void summaryWithoutWaterfallKeepsTheTraceRow() {
    JsonNode d =
        json("{\"service.name\":\"\",\"name\":\"\",\"duration_nano\":1000,\"span_count\":4}");
    TraceSummary s = SignozQueryClient.summary("4bf92f3577b34da6a3ce929d0e0e4736", d, null);
    assertThat(s.rootServiceName()).isEmpty();
    assertThat(s.rootName()).isEmpty();
    assertThat(s.spanCount()).isEqualTo(4);
    assertThat(s.durationMicros()).isEqualTo(1);
    TraceSummary w =
        SignozQueryClient.summary(
            "4bf92f3577b34da6a3ce929d0e0e4736", d, json("{\"totalSpansCount\":0}"));
    assertThat(w.spanCount()).isEqualTo(4);
    assertThat(SignozQueryClient.notInIds(List.of())).isEmpty();
    assertThat(SignozQueryClient.checkLimit("x", 0, 7)).isEqualTo(7);
    assertThat(SignozQueryClient.groupField("other")).isEqualTo(Map.of("name", "other"));
  }

  @Test
  void modelDefaults() {
    assertThat(new LogQueryResult(null, null, null).records()).isEmpty();
    assertThat(new SpanQueryResult(null, null).spans()).isEmpty();
    assertThat(new LogField(null, null, null, null).description()).isEmpty();
    assertThat(FieldValuesOptions.defaults().signal()).isEqualTo(Signal.LOGS);
    assertThat(new FieldValuesOptions(null, null).signal()).isEqualTo(Signal.LOGS);
    LogQuery q =
        LogQuery.builder()
            .filter((String) null)
            .filter((FilterBuilder) null)
            .cursor("")
            .start(Instant.EPOCH)
            .end(Instant.EPOCH)
            .build();
    assertThat(q.filter()).isEmpty();
    assertThat(q.cursor()).isEmpty();
    LogQuery copy = q.toBuilder().limit(5).build();
    assertThat(copy.limit()).isEqualTo(5);
    assertThat(copy.start()).contains(Instant.EPOCH);
    assertThat(copy.end()).contains(Instant.EPOCH);
    assertThat(copy.order()).isEmpty();
    TraceQuery t =
        TraceQuery.builder()
            .filter((String) null)
            .filter((FilterBuilder) null)
            .service("")
            .operation("")
            .build();
    assertThat(t.expression()).isEmpty();
    assertThat(t.service()).contains("");
    assertThat(t.operation()).contains("");
    assertThat(t.start()).isEmpty();
    assertThat(t.end()).isEmpty();
    assertThat(t.filter()).isEmpty();
    assertThat(TraceQuery.builder().filter(FilterBuilder.create().eq("a", 1)).build().filter())
        .isEqualTo("a = 1");
  }
}
