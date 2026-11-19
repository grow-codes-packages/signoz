package codes.grow.packages.signoz;

import codes.grow.packages.signoz.logging.AttrValues;
import codes.grow.packages.signoz.logging.LogRecord;
import codes.grow.packages.signoz.logging.Severity;
import codes.grow.packages.signoz.tracing.query.Span;
import codes.grow.packages.signoz.tracing.query.TraceIds;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;

/** Mapping of SigNoz JSON rows (raw logs, raw spans, waterfall spans) to the normalized models. */
final class Rows {
  private Rows() {}

  /** Text of a field: strings as-is, numbers and booleans as text, anything else empty. */
  static String str(JsonNode node, String key) {
    JsonNode v = node == null ? null : node.get(key);
    if (v == null || v.isNull() || v.isContainerNode()) {
      return "";
    }
    return v.asText();
  }

  /** Integral value of a field (numbers or numeric strings). */
  static OptionalLong num(JsonNode node, String key) {
    JsonNode v = node == null ? null : node.get(key);
    if (v == null) {
      return OptionalLong.empty();
    }
    if (v.isIntegralNumber()) {
      return OptionalLong.of(v.asLong());
    }
    if (v.isNumber()) {
      return OptionalLong.of((long) v.asDouble());
    }
    if (v.isTextual()) {
      try {
        return OptionalLong.of(Long.parseLong(v.asText().trim()));
      } catch (NumberFormatException e) {
        return OptionalLong.empty();
      }
    }
    return OptionalLong.empty();
  }

  /** Converts a JSON value to a plain Java value (String, Long, Double, Boolean, List, Map). */
  static Object plain(JsonNode v) {
    if (v == null || v.isNull() || v.isMissingNode()) {
      return null;
    }
    if (v.isTextual()) {
      return v.asText();
    }
    if (v.isBoolean()) {
      return v.asBoolean();
    }
    if (v.isIntegralNumber()) {
      return v.canConvertToLong() ? (Object) v.asLong() : (Object) v.asDouble();
    }
    if (v.isNumber()) {
      return v.asDouble();
    }
    if (v.isArray()) {
      List<Object> out = new ArrayList<>();
      v.forEach(e -> out.add(plain(e)));
      return out;
    }
    Map<String, Object> out = new LinkedHashMap<>();
    v.properties().forEach(e -> out.put(e.getKey(), plain(e.getValue())));
    return out;
  }

  /** Merges JSON objects into one normalized attribute map (null values dropped). */
  static Map<String, Object> attrs(JsonNode... objects) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (JsonNode o : objects) {
      if (o == null || !o.isObject()) {
        continue;
      }
      o.properties()
          .forEach(
              e -> {
                if (!e.getValue().isNull()) {
                  out.put(e.getKey(), AttrValues.normalize(plain(e.getValue())));
                }
              });
    }
    return out;
  }

  /**
   * Row timestamp in Unix nanoseconds: the int64 {@code data.timestamp} when present, else the RFC
   * 3339 envelope timestamp; 0 when neither parses.
   */
  static long rowNanos(JsonNode row) {
    JsonNode data = row.path("data");
    OptionalLong n = num(data, "timestamp");
    if (n.isPresent() && n.getAsLong() > 0) {
      return n.getAsLong();
    }
    String ts = str(row, "timestamp");
    if (ts.isEmpty()) {
      ts = str(data, "timestamp");
    }
    try {
      Instant t = Instant.parse(ts);
      return t.getEpochSecond() * 1_000_000_000L + t.getNano();
    } catch (DateTimeParseException e) {
      return 0;
    }
  }

  /** Row timestamp in Unix milliseconds. */
  static long rowMillis(JsonNode row) {
    return Math.floorDiv(rowNanos(row), 1_000_000L);
  }

  static Instant instantOfNanos(long nanos) {
    return Instant.ofEpochSecond(
        Math.floorDiv(nanos, 1_000_000_000L), Math.floorMod(nanos, 1_000_000_000L));
  }

  private static boolean isHex(String s, int len) {
    if (s.length() != len) {
      return false;
    }
    boolean nonZero = false;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
      if (!hex) {
        return false;
      }
      nonZero |= c != '0';
    }
    return nonZero;
  }

  /** Maps a raw logs row (query or live tail event) to a {@link LogRecord}. */
  static LogRecord logRecord(JsonNode row) {
    JsonNode d = row.path("data");
    LogRecord.Builder b =
        LogRecord.builder()
            .timestamp(instantOfNanos(rowNanos(row)))
            .body(str(d, "body"))
            .attributes(
                attrs(
                    d.get("attributes_string"),
                    d.get("attributes_number"),
                    d.get("attributes_bool")))
            .resource(attrs(d.get("resources_string")));
    String text = str(d, "severity_text");
    OptionalLong number = num(d, "severity_number");
    Severity severity = null;
    if (number.isPresent() && number.getAsLong() > 0) {
      severity = Severity.fromNumber((int) Math.min(number.getAsLong(), 255));
    } else {
      severity = Severity.parse(text).orElse(null);
    }
    if (severity != null) {
      b.severity(severity);
    }
    b.severityText(text.isEmpty() && severity != null ? severity.text() : text);
    String traceId = str(d, "trace_id").toLowerCase(Locale.ROOT);
    String spanId = str(d, "span_id").toLowerCase(Locale.ROOT);
    if (isHex(traceId, 32)) {
      b.traceId(traceId);
      b.traceFlags((int) (num(d, "trace_flags").orElse(0) & 0xff));
    }
    if (isHex(spanId, 16)) {
      b.spanId(spanId);
    }
    return b.build();
  }

  /** Maps SigNoz {@code kind_string} ("Server", "Client", ...) to a span kind. */
  static Span.Kind kind(String s) {
    return Span.Kind.parse(s);
  }

  /** Status from {@code status_code} (0/1/2, omitted for Unset) or {@code status_code_string}. */
  static Span.StatusCode statusCode(JsonNode d) {
    OptionalLong code = num(d, "status_code");
    long c;
    if (code.isPresent()) {
      c = code.getAsLong();
    } else {
      String s = str(d, "status_code_string").toLowerCase(Locale.ROOT);
      c = s.equals("ok") ? 1 : s.equals("error") ? 2 : 0;
    }
    return c == 1 ? Span.StatusCode.OK : c == 2 ? Span.StatusCode.ERROR : Span.StatusCode.UNSET;
  }

  private static Span.Builder spanBase(JsonNode d) {
    String parent = str(d, "parent_span_id").toLowerCase(Locale.ROOT);
    String message = str(d, "status_message");
    Span.Builder b =
        Span.builder(
                str(d, "trace_id").toLowerCase(Locale.ROOT),
                str(d, "span_id").toLowerCase(Locale.ROOT),
                str(d, "name"))
            .parentSpanId(isHex(parent, 16) ? parent : null)
            .kind(kind(str(d, "kind_string")))
            .serviceName(str(d.path("resource"), "service.name"))
            .status(statusCode(d), message.isEmpty() ? null : message)
            .attributes(attrs(d.get("attributes")));
    return b;
  }

  private static void events(Span.Builder b, JsonNode d, String attrKey) {
    for (JsonNode e : d.path("events")) {
      if (!e.isObject()) {
        continue;
      }
      long ns = num(e, "timeUnixNano").orElse(0);
      b.event(new Span.Event(str(e, "name"), ns / 1000, attrs(e.get(attrKey))));
    }
  }

  /** Maps a raw spans row (start = RFC 3339 ns row timestamp). */
  static Span spanFromRaw(JsonNode row) {
    JsonNode d = row.path("data");
    Span.Builder b = spanBase(d);
    long duration = num(d, "duration_nano").orElse(0) / 1000;
    long start = rowNanos(row) / 1000;
    b.times(start, start + duration).durationMicros(duration);
    events(b, d, "attributes");
    String parent = str(d, "parent_span_id").toLowerCase(Locale.ROOT);
    for (JsonNode l : d.path("links")) {
      String sid = str(l, "spanId").toLowerCase(Locale.ROOT);
      String tid = str(l, "traceId").toLowerCase(Locale.ROOT);
      if (sid.isEmpty() || sid.equals(parent) || !TraceIds.isValidTraceId(tid)) {
        continue; // the parent reference, not a span link
      }
      b.link(new Span.Link(tid, sid, Map.of()));
    }
    return b.build();
  }

  /**
   * Maps a waterfall span: {@code time_unix} is milliseconds; links are the {@code FOLLOWS_FROM}
   * references (SigNoz drops link attributes).
   */
  static Span spanFromWaterfall(JsonNode d) {
    Span.Builder b = spanBase(d);
    long duration = num(d, "duration_nano").orElse(0) / 1000;
    long start = num(d, "time_unix").orElse(0) * 1000;
    b.times(start, start + duration).durationMicros(duration);
    events(b, d, "attributeMap");
    for (JsonNode r : d.path("references")) {
      String sid = str(r, "spanId").toLowerCase(Locale.ROOT);
      String tid = str(r, "traceId").toLowerCase(Locale.ROOT);
      if (!str(r, "refType").equalsIgnoreCase("FOLLOWS_FROM")
          || sid.isEmpty()
          || !TraceIds.isValidTraceId(tid)) {
        continue;
      }
      b.link(new Span.Link(tid, sid, Map.of()));
    }
    return b.build();
  }
}
