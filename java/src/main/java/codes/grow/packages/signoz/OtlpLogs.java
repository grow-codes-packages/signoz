package codes.grow.packages.signoz;

import codes.grow.packages.signoz.core.SdkInfo;
import codes.grow.packages.signoz.logging.AttrValues;
import codes.grow.packages.signoz.logging.LogRecord;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Minimal protobuf encoding of an OTLP {@code ExportLogsServiceRequest} and decoding of the partial
 * success of an {@code ExportLogsServiceResponse} (opentelemetry-proto logs v1).
 */
final class OtlpLogs {
  /** Instrumentation scope of exported records. */
  static final String SCOPE_NAME = "grow-codes-signoz";

  private OtlpLogs() {}

  /** Growable protobuf writer. */
  static final class Writer {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    void varint(long v) {
      while ((v & ~0x7FL) != 0) {
        out.write((int) ((v & 0x7F) | 0x80));
        v >>>= 7;
      }
      out.write((int) v);
    }

    void tag(int field, int wire) {
      varint(((long) field << 3) | wire);
    }

    void fixed64(int field, long v) {
      tag(field, 1);
      for (int i = 0; i < 8; i++) {
        out.write((int) (v >>> (8 * i)) & 0xFF);
      }
    }

    void fixed32(int field, int v) {
      tag(field, 5);
      for (int i = 0; i < 4; i++) {
        out.write((v >>> (8 * i)) & 0xFF);
      }
    }

    void uint64(int field, long v) {
      tag(field, 0);
      varint(v);
    }

    void bytes(int field, byte[] b) {
      tag(field, 2);
      varint(b.length);
      out.write(b, 0, b.length);
    }

    void string(int field, String s) {
      bytes(field, s.getBytes(StandardCharsets.UTF_8));
    }

    void message(int field, Writer w) {
      bytes(field, w.toByteArray());
    }

    byte[] toByteArray() {
      return out.toByteArray();
    }
  }

  /**
   * Encodes records, one {@code ResourceLogs} per distinct resource. Invalid trace or span ids are
   * left out (the collector would reject the whole batch).
   *
   * @param records records
   * @return protobuf bytes
   */
  static byte[] encode(List<LogRecord> records) {
    Map<String, Map<String, Object>> resources = new LinkedHashMap<>();
    Map<String, List<LogRecord>> groups = new LinkedHashMap<>();
    for (LogRecord r : records) {
      String key = new TreeMap<>(r.resource()).toString();
      resources.putIfAbsent(key, r.resource());
      groups.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
    }
    Writer req = new Writer();
    for (Map.Entry<String, List<LogRecord>> g : groups.entrySet()) {
      Writer resource = new Writer();
      keyValues(resource, 1, resources.get(g.getKey()));
      Writer scope = new Writer();
      scope.string(1, SCOPE_NAME);
      scope.string(2, SdkInfo.VERSION);
      Writer scopeLogs = new Writer();
      scopeLogs.message(1, scope);
      for (LogRecord r : g.getValue()) {
        scopeLogs.message(2, logRecord(r));
      }
      Writer rl = new Writer();
      rl.message(1, resource);
      rl.message(2, scopeLogs);
      req.message(1, rl);
    }
    return req.toByteArray();
  }

  static long nanos(Instant t) {
    return t.getEpochSecond() * 1_000_000_000L + t.getNano();
  }

  static Writer logRecord(LogRecord r) {
    Writer w = new Writer();
    w.fixed64(1, nanos(r.timestamp()));
    w.uint64(2, r.severity().number());
    if (!r.severityText().isEmpty()) {
      w.string(3, r.severityText());
    }
    Writer body = new Writer();
    body.string(1, r.body());
    w.message(5, body);
    keyValues(w, 6, r.attributes());
    r.traceFlags().ifPresent(f -> w.fixed32(8, f));
    r.traceId().map(id -> hex(id, 16)).ifPresent(b -> w.bytes(9, b));
    r.spanId().map(id -> hex(id, 8)).ifPresent(b -> w.bytes(10, b));
    r.observedTimestamp().ifPresent(t -> w.fixed64(11, nanos(t)));
    return w;
  }

  /** Decodes a hex id of {@code len} bytes, or {@code null} when it is not valid hex. */
  static byte[] hex(String id, int len) {
    if (id == null || id.length() != len * 2) {
      return null;
    }
    byte[] out = new byte[len];
    for (int i = 0; i < len; i++) {
      int hi = Character.digit(id.charAt(2 * i), 16);
      int lo = Character.digit(id.charAt(2 * i + 1), 16);
      if (hi < 0 || lo < 0) {
        return null;
      }
      out[i] = (byte) ((hi << 4) | lo);
    }
    return out;
  }

  static void keyValues(Writer w, int field, Map<String, ?> attrs) {
    for (Map.Entry<String, ?> e : new TreeMap<>(attrs).entrySet()) {
      Writer any = anyValue(AttrValues.normalize(e.getValue()));
      if (any == null) {
        continue;
      }
      Writer kv = new Writer();
      kv.string(1, e.getKey());
      kv.message(2, any);
      w.message(field, kv);
    }
  }

  /** OTLP {@code AnyValue} of a normalized attribute value ({@code null} for null). */
  static Writer anyValue(Object v) {
    if (v == null) {
      return null;
    }
    Writer w = new Writer();
    if (v instanceof String) {
      w.string(1, (String) v);
    } else if (v instanceof Boolean) {
      w.uint64(2, ((Boolean) v) ? 1 : 0);
    } else if (v instanceof Long || v instanceof Integer) {
      w.uint64(3, ((Number) v).longValue());
    } else if (v instanceof Double || v instanceof Float) {
      w.fixed64(4, Double.doubleToLongBits(((Number) v).doubleValue()));
    } else if (v instanceof List) {
      Writer arr = new Writer();
      for (Object e : (List<?>) v) {
        Writer a = anyValue(e);
        if (a != null) {
          arr.message(1, a);
        }
      }
      w.message(5, arr);
    } else if (v instanceof Map) {
      Map<String, Object> m = new LinkedHashMap<>();
      ((Map<?, ?>) v).forEach((k, e) -> m.put(String.valueOf(k), e));
      Writer kvl = new Writer();
      keyValues(kvl, 1, m);
      w.message(6, kvl);
    } else {
      w.string(1, String.valueOf(v));
    }
    return w;
  }

  /** Partial success of an export response. */
  record PartialSuccess(long rejected, String message) {}

  /**
   * Reads {@code partial_success} (field 1: {@code rejected_log_records} = 1, {@code error_message}
   * = 2) from an {@code ExportLogsServiceResponse}; unknown or malformed input reads as no
   * rejection.
   *
   * @param b response bytes
   * @return the partial success
   */
  static PartialSuccess partialSuccess(byte[] b) {
    try {
      Reader r = new Reader(b, 0, b.length);
      while (r.more()) {
        long tag = r.varint();
        if (tag == ((1 << 3) | 2)) {
          int len = (int) r.varint();
          Reader ps = new Reader(b, r.pos, r.pos + len);
          r.pos += len;
          long rejected = 0;
          String msg = "";
          while (ps.more()) {
            long t = ps.varint();
            if (t == ((1 << 3) | 0)) {
              rejected = ps.varint();
            } else if (t == ((2 << 3) | 2)) {
              int l = (int) ps.varint();
              msg = new String(b, ps.pos, l, StandardCharsets.UTF_8);
              ps.pos += l;
            } else {
              ps.skip((int) (t & 7));
            }
          }
          return new PartialSuccess(rejected, msg);
        }
        r.skip((int) (tag & 7));
      }
    } catch (RuntimeException e) {
      return new PartialSuccess(0, "");
    }
    return new PartialSuccess(0, "");
  }

  /** Minimal protobuf reader. */
  static final class Reader {
    private final byte[] b;
    private final int end;
    int pos;

    Reader(byte[] b, int pos, int end) {
      if (end > b.length || pos < 0 || pos > end) {
        throw new IllegalArgumentException("protobuf: truncated");
      }
      this.b = b;
      this.pos = pos;
      this.end = end;
    }

    boolean more() {
      return pos < end;
    }

    long varint() {
      long v = 0;
      for (int shift = 0; shift < 64; shift += 7) {
        if (pos >= end) {
          throw new IllegalArgumentException("protobuf: truncated varint");
        }
        int c = b[pos++] & 0xFF;
        v |= (long) (c & 0x7F) << shift;
        if ((c & 0x80) == 0) {
          return v;
        }
      }
      throw new IllegalArgumentException("protobuf: varint too long");
    }

    void skip(int wire) {
      switch (wire) {
        case 0:
          varint();
          break;
        case 1:
          pos += 8;
          break;
        case 2:
          pos += (int) varint();
          break;
        case 5:
          pos += 4;
          break;
        default:
          throw new IllegalArgumentException("protobuf: unsupported wire type " + wire);
      }
      if (pos > end) {
        throw new IllegalArgumentException("protobuf: truncated");
      }
    }
  }
}
