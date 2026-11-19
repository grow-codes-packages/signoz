// Service-owned: converts SigNoz rows (logs, spans, waterfall) into the normalized SDK models.

import { ValidationError } from "./core/index.js";
import { normalizeHexId, toAttributes } from "./logging/json.js";
import {
  Severity,
  canonicalSeverity,
  parseSeverity,
  severityText,
  type Attributes,
  type LogRecord,
} from "./logging/types.js";
import {
  isoToMicros,
  sortSpans,
  type Span,
  type SpanEvent,
  type SpanKindName,
  type SpanLink,
  type SpanStatusName,
  type Trace,
} from "./tracing/model.js";

/** A {@link LogRecord} read from SigNoz, with the row id and exact timestamp. */
export interface SignozLogRecord extends LogRecord {
  /** SigNoz row id (KSUID-like, assigned at ingest). */
  id: string;
  /** Exact timestamp in epoch nanoseconds (decimal string; JavaScript numbers lose precision). */
  timeUnixNano: string;
  /** Instrumentation scope name, when set. */
  scopeName?: string;
}

/** A {@link Trace} read from the SigNoz waterfall API. */
export interface SignozTrace extends Trace {
  /** `false` when SigNoz returned a window of a very large trace (more than ~10,000 spans). */
  complete: boolean;
  /** Total spans SigNoz reports for the trace (may exceed `spans.length` when incomplete). */
  totalSpans: number;
  /** Service of the root span. */
  rootServiceName?: string;
  /** Name of the root span. */
  rootName?: string;
}

type Obj = Record<string, unknown>;

function obj(value: unknown): Obj {
  return value !== null && typeof value === "object" && !Array.isArray(value) ? (value as Obj) : {};
}

function str(value: unknown): string | undefined {
  return typeof value === "string" && value !== "" ? value : undefined;
}

function num(value: unknown): number | undefined {
  if (typeof value === "number" && Number.isFinite(value)) return value;
  if (typeof value === "string" && /^-?\d+(\.\d+)?$/.test(value.trim())) return Number(value);
  return undefined;
}

const RFC3339 = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}:\d{2})$/;

/**
 * Parses an RFC 3339 timestamp with up to nanosecond precision into epoch nanoseconds
 * (`bigint`). Throws `ValidationError` when invalid.
 */
export function rfc3339ToNanos(text: string): bigint {
  const m = RFC3339.exec(text);
  const seconds = m === null ? NaN : Date.parse(`${m[1] ?? ""}${m[3] ?? ""}`);
  if (m === null || Number.isNaN(seconds)) throw new ValidationError("signoz: invalid timestamp");
  return BigInt(seconds) * 1_000_000n + BigInt((m[2] ?? "").padEnd(9, "0"));
}

/** Epoch milliseconds (floor) of epoch nanoseconds. */
export function nanosToMs(nanos: bigint): number {
  return Number(nanos / 1_000_000n);
}

/** Merges every `<prefix>*` map of a row (e.g. `attributes_string`, `attributes_number`). */
function mergeTyped(data: Obj, prefix: string): Attributes {
  const out: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(data)) {
    if (key.startsWith(prefix)) Object.assign(out, obj(value));
  }
  return toAttributes(out);
}

function timestampNanos(row: Obj, data: Obj): bigint {
  const envelope = str(row.timestamp);
  if (envelope !== undefined) return rfc3339ToNanos(envelope);
  const raw = data.timestamp;
  if (typeof raw === "string" && /^\d+$/.test(raw)) return BigInt(raw);
  if (typeof raw === "number" && Number.isFinite(raw)) return BigInt(Math.trunc(raw));
  throw new ValidationError("signoz: log row without timestamp");
}

/**
 * Converts a raw log row (`{timestamp, data}` from `query_range` or live tail) into a
 * {@link SignozLogRecord}. The RFC 3339 envelope timestamp is used because `data.timestamp`
 * exceeds 2^53.
 */
export function logRowToRecord(row: unknown): SignozLogRecord {
  const r = obj(row);
  const data = obj(r.data);
  const nanos = timestampNanos(r, data);
  const number = num(data.severity_number);
  const text = str(data.severity_text);
  let severity: Severity;
  if (number !== undefined && number >= 1 && number <= 24) severity = canonicalSeverity(number);
  else if (text !== undefined) severity = canonicalSeverity(parseSeverity(text) ?? Severity.Info);
  else severity = Severity.Info;
  const record: SignozLogRecord = {
    id: str(data.id) ?? "",
    timeUnixNano: nanos.toString(),
    timestamp: new Date(nanosToMs(nanos)),
    severity,
    severityText: text ?? severityText(severity),
    body: typeof data.body === "string" ? data.body : JSON.stringify(data.body ?? ""),
    attributes: mergeTyped(data, "attributes_"),
    resource: mergeTyped(data, "resources_"),
  };
  const traceId = normalizeHexId(str(data.trace_id), 32);
  if (traceId !== undefined && !/^0+$/.test(traceId)) {
    record.traceId = traceId;
    const spanId = normalizeHexId(str(data.span_id), 16);
    if (spanId !== undefined && !/^0+$/.test(spanId)) record.spanId = spanId;
    const flags = num(data.trace_flags);
    if (flags !== undefined) record.traceFlags = flags;
  }
  const scope = str(data.scope_name);
  if (scope !== undefined) record.scopeName = scope;
  return record;
}

const KINDS: Record<string, SpanKindName> = {
  server: "server",
  client: "client",
  producer: "producer",
  consumer: "consumer",
  internal: "internal",
  unspecified: "unspecified",
};
const KIND_BY_NUMBER: readonly SpanKindName[] = [
  "unspecified",
  "internal",
  "server",
  "client",
  "producer",
  "consumer",
];

function spanKind(data: Obj): SpanKindName {
  const text = str(data.kind_string)?.toLowerCase();
  if (text !== undefined && KINDS[text] !== undefined) return KINDS[text];
  const n = num(data.kind);
  return (n !== undefined ? KIND_BY_NUMBER[n] : undefined) ?? "unspecified";
}

function spanStatus(data: Obj): { code: SpanStatusName; message?: string } {
  const code = num(data.status_code);
  const text = str(data.status_code_string)?.toLowerCase();
  let name: SpanStatusName = "unset";
  if (code === 2 || text === "error") name = "error";
  else if (code === 1 || text === "ok") name = "ok";
  const status: { code: SpanStatusName; message?: string } = { code: name };
  const message = str(data.status_message);
  if (message !== undefined) status.message = message;
  return status;
}

function nanosValueToMicros(value: unknown): number {
  if (typeof value === "string" && /^\d+$/.test(value)) return Number(BigInt(value) / 1000n);
  const n = num(value);
  return n === undefined ? 0 : Math.floor(n / 1000);
}

function spanEvents(data: Obj): SpanEvent[] {
  const events = Array.isArray(data.events) ? data.events : [];
  return events.map((e) => {
    const event = obj(e);
    return {
      name: str(event.name) ?? "",
      timeMicros: nanosValueToMicros(event.timeUnixNano),
      attributes: toAttributes(obj(event.attributeMap ?? event.attributes)),
    };
  });
}

function link(traceId: unknown, spanId: unknown): SpanLink | undefined {
  const t = normalizeHexId(str(traceId), 32);
  const s = normalizeHexId(str(spanId), 16);
  return t === undefined || s === undefined ? undefined : { traceId: t, spanId: s, attributes: {} };
}

function baseSpan(data: Obj, startMicros: number): Span {
  const durationMicros = Math.floor((num(data.duration_nano) ?? 0) / 1000);
  const resource = obj(data.resource);
  const span: Span = {
    traceId: normalizeHexId(str(data.trace_id), 32) ?? "",
    spanId: normalizeHexId(str(data.span_id), 16) ?? "",
    name: str(data.name) ?? "",
    kind: spanKind(data),
    serviceName: str(resource["service.name"]) ?? str(data["service.name"]) ?? "",
    startMicros,
    endMicros: startMicros + durationMicros,
    durationMicros,
    status: spanStatus(data),
    attributes: toAttributes(obj(data.attributes)),
    events: spanEvents(data),
    links: [],
  };
  const parent = normalizeHexId(str(data.parent_span_id), 16);
  if (parent !== undefined && !/^0+$/.test(parent)) span.parentSpanId = parent;
  return span;
}

/**
 * Converts a raw span row (`requestType: "raw"`, `signal: "traces"`) into a {@link Span}. The
 * parent reference SigNoz mirrors into `links` is dropped; link attributes are not stored by
 * SigNoz, so they are always `{}`.
 */
export function spanRowToSpan(row: unknown): Span {
  const r = obj(row);
  const data = obj(r.data);
  const ts = str(r.timestamp) ?? str(data.timestamp);
  const start = ts === undefined ? 0 : isoToMicros(ts);
  const span = baseSpan(data, start);
  const links = Array.isArray(data.links) ? data.links : [];
  for (const l of links) {
    const candidate = link(obj(l).traceId, obj(l).spanId);
    if (candidate === undefined) continue;
    if (candidate.spanId === span.parentSpanId && candidate.traceId === span.traceId) continue;
    span.links.push(candidate);
  }
  return span;
}

/**
 * Converts one waterfall span into a {@link Span}. `time_unix` is in milliseconds, so
 * `startMicros` has millisecond precision. Links come from `references` with
 * `refType: FOLLOWS_FROM`.
 */
export function waterfallSpanToSpan(value: unknown): Span {
  const data = obj(value);
  const span = baseSpan(data, Math.round((num(data.time_unix) ?? 0) * 1000));
  const refs = Array.isArray(data.references) ? data.references : [];
  for (const ref of refs) {
    const r = obj(ref);
    if (str(r.refType)?.toUpperCase() !== "FOLLOWS_FROM") continue;
    const candidate = link(r.traceId, r.spanId);
    if (candidate !== undefined) span.links.push(candidate);
  }
  return span;
}

/** Converts the waterfall response (`data` of `/api/v4/traces/{id}/waterfall`). */
export function waterfallToTrace(traceId: string, value: unknown): SignozTrace {
  const data = obj(value);
  const spans = sortSpans((Array.isArray(data.spans) ? data.spans : []).map(waterfallSpanToSpan));
  const total = num(data.totalSpansCount) ?? spans.length;
  const trace: SignozTrace = {
    traceId,
    spans,
    complete: data.hasMore !== true && total <= spans.length,
    totalSpans: total,
  };
  const rootService = str(data.rootServiceName);
  if (rootService !== undefined) trace.rootServiceName = rootService;
  const rootName = str(data.rootServiceEntryPoint);
  if (rootName !== undefined) trace.rootName = rootName;
  return trace;
}
