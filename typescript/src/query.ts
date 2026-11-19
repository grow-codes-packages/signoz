// Service-owned: SignozQueryClient — query-range API (v5), waterfall, services, operations,
// dependencies and log fields. Implements the tracing category's TraceQueryClient.

import { type ClientLogger, ValidationError } from "./core/index.js";
import { AggregateBuilder, type SignozSignal } from "./aggregate.js";
import { FilterBuilder, andFilters, filterLiteral, quoteFilterString } from "./filter.js";
import type { SignozApi } from "./http.js";
import {
  logRowToRecord,
  nanosToMs,
  spanRowToSpan,
  waterfallToTrace,
  type SignozLogRecord,
  type SignozTrace,
} from "./mapping.js";
import { BaseTraceQueryClient, type DependencyOptions } from "./tracing/query.js";
import {
  normalizeTraceId,
  sortSpans,
  type DependencyLink,
  type Span,
  type TraceSearchQuery,
  type TraceSummary,
} from "./tracing/model.js";

/** Default query window when `start` is omitted: one hour before `end`. */
export const DEFAULT_QUERY_WINDOW_MS = 3_600_000;
/** Default page size of {@link SignozQueryClient.queryLogs}. */
export const DEFAULT_LOG_LIMIT = 100;
/** Largest page SigNoz accepts for raw queries. */
export const MAX_RAW_LIMIT = 10_000;

/** A time window plus cancellation. */
export interface TimeRangeOptions {
  /** Window start (default: one hour before `end`). */
  start?: Date;
  /** Window end, exclusive (default: now). */
  end?: Date;
  signal?: AbortSignal;
}

/** Options of {@link SignozQueryClient.queryLogs}. */
export interface QueryLogsOptions extends TimeRangeOptions {
  /** SigNoz filter expression (search syntax) or a {@link FilterBuilder}. Empty: every log. */
  filter?: string | FilterBuilder;
  /** Page size, 1..10000. Default 100. */
  limit?: number;
  /** Order by timestamp (ties by id). Default `"desc"` (newest first). */
  orderBy?: "asc" | "desc";
  /** Opaque cursor from {@link LogQueryResult.nextCursor} (same filter and order). */
  cursor?: string;
  /** Columns to return besides `id` and `timestamp` (default: all). */
  selectFields?: string[];
}

/** One page of {@link SignozQueryClient.queryLogs}. */
export interface LogQueryResult {
  records: SignozLogRecord[];
  /** Present when the page is full; pass it back as `cursor` for the next page. */
  nextCursor?: string;
  /** Server warnings, e.g. unknown fields in the filter (SigNoz answers 200 with no rows). */
  warnings: string[];
}

/** Options of {@link SignozQueryClient.queryTraces}. */
export interface QueryTracesOptions extends TimeRangeOptions {
  /** `service.name = '<service>'`. */
  service?: string;
  /** Span name: `name = '<operation>'`. */
  operation?: string;
  /** Extra filter expression. */
  filter?: string | FilterBuilder;
  /** Page size, 1..10000. Default 100. */
  limit?: number;
  /** Rows to skip (offset paging). Default 0. */
  offset?: number;
  /** Order by timestamp. Default `"desc"`. */
  orderBy?: "asc" | "desc";
}

/** Result of {@link SignozQueryClient.queryTraces}: one entry per matching span. */
export interface SpanQueryResult {
  spans: Span[];
  warnings: string[];
}

/** Search options of {@link SignozQueryClient.searchTraces}. */
export interface SignozTraceSearchQuery extends TraceSearchQuery {
  /** Extra filter expression. */
  filter?: string | FilterBuilder;
  signal?: AbortSignal;
}

/** Options of {@link SignozQueryClient.listLogFields}. */
export interface ListLogFieldsOptions {
  /** Only names containing this text. */
  search?: string;
  /** Maximum number of names. */
  limit?: number;
  signal?: AbortSignal;
}

/** A log field known to SigNoz. */
export interface LogField {
  name: string;
  /** `log`, `resource`, `attribute` or `scope`. */
  context: string;
  /** `string`, `number` or `bool`. */
  dataType: string;
  description?: string;
}

/**
 * The SDK's own keyset cursor. Same wire form in every language (Go is the reference):
 * base64url without padding of `{"v":1,"s":<startMs>,"e":<endMs>,"o":"asc|desc","m":<boundaryMs>,"x":[ids]}`.
 * `x` may be `null` or missing (no ids excluded).
 */
interface Cursor {
  v: 1;
  s: number;
  e: number;
  o: "asc" | "desc";
  /** Boundary millisecond of the last row. */
  m: number;
  /** Ids already returned in the boundary millisecond. */
  x: string[];
}

function encodeCursor(cursor: Cursor): string {
  return Buffer.from(JSON.stringify(cursor), "utf8").toString("base64url");
}

function decodeCursor(text: string): Cursor {
  try {
    const c = JSON.parse(Buffer.from(text, "base64url").toString("utf8")) as Partial<
      Omit<Cursor, "x">
    > & { x?: unknown };
    const ids = c.x ?? [];
    if (
      c.v === 1 &&
      Number.isInteger(c.s) &&
      Number.isInteger(c.e) &&
      Number.isInteger(c.m) &&
      (c.o === "asc" || c.o === "desc") &&
      Array.isArray(ids) &&
      ids.every((id) => typeof id === "string")
    ) {
      return { v: 1, s: c.s as number, e: c.e as number, o: c.o, m: c.m as number, x: ids };
    }
  } catch {
    // fall through
  }
  throw new ValidationError("queryLogs: invalid cursor");
}

/** Resolves a window to epoch milliseconds (default: the last hour). */
export function resolveWindow(
  opts: { start?: Date | undefined; end?: Date | undefined },
  now = Date.now(),
): {
  start: number;
  end: number;
} {
  const end = opts.end?.getTime() ?? now;
  const start = opts.start?.getTime() ?? end - DEFAULT_QUERY_WINDOW_MS;
  if (!Number.isFinite(start) || !Number.isFinite(end))
    throw new ValidationError("query: invalid time window");
  if (start >= end) throw new ValidationError("query: start must be before end");
  return { start, end };
}

function checkLimit(limit: number | undefined, fallback: number, operation: string): number {
  // undefined or 0 = the default (same as the other languages, where 0 is the unset value)
  const value = limit === undefined || limit === 0 ? fallback : limit;
  if (!Number.isInteger(value) || value <= 0 || value > MAX_RAW_LIMIT) {
    throw new ValidationError(
      `${operation}: limit must be an integer between 1 and ${MAX_RAW_LIMIT}`,
    );
  }
  return value;
}

function checkOrder(order: string | undefined, operation: string): "asc" | "desc" {
  const value = order ?? "desc";
  if (value !== "asc" && value !== "desc")
    throw new ValidationError(`${operation}: orderBy must be "asc" or "desc"`);
  return value;
}

function filterText(filter: string | FilterBuilder | undefined): string {
  return filter === undefined ? "" : typeof filter === "string" ? filter.trim() : filter.build();
}

function nsString(ms: number): string {
  return (BigInt(Math.trunc(ms)) * 1_000_000n).toString();
}

type Obj = Record<string, unknown>;
function obj(value: unknown): Obj {
  return value !== null && typeof value === "object" && !Array.isArray(value) ? (value as Obj) : {};
}
function arr(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

async function mapLimit<T, R>(
  items: readonly T[],
  limit: number,
  fn: (item: T) => Promise<R>,
): Promise<R[]> {
  const out = new Array<R>(items.length);
  let next = 0;
  const worker = async (): Promise<void> => {
    while (next < items.length) {
      const i = next++;
      out[i] = await fn(items[i] as T);
    }
  };
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, worker));
  return out;
}

/**
 * Query client for SigNoz (`SIGNOZ-API-KEY` auth): logs and spans via `/api/v5/query_range`,
 * traces via the waterfall API, services/operations via v5 aggregations, dependencies via
 * `/api/v1/dependency_graph`, log fields via `/api/v1/fields/keys`. Implements
 * {@link TraceQueryClient}. Obtain it from `SignozClient.query`.
 */
export class SignozQueryClient extends BaseTraceQueryClient {
  readonly #api: SignozApi;
  readonly #logger: ClientLogger | undefined;
  readonly #now: () => number;

  /** @internal Use `SignozClient.query`. */
  constructor(api: SignozApi, logger?: ClientLogger, now: () => number = Date.now) {
    super();
    this.#api = api;
    this.#logger = logger;
    this.#now = now;
  }

  /** Runs a raw `/api/v5/query_range` request body and returns `data` plus warnings. */
  async queryRange(
    body: Record<string, unknown>,
    options: { operation?: string; signal?: AbortSignal | undefined } = {},
  ): Promise<{ data: unknown; warnings: string[] }> {
    const operation = options.operation ?? "queryRange";
    const res = await this.#api.get<unknown>("/api/v5/query_range", {
      method: "POST",
      json: body,
      operation,
      signal: options.signal,
    });
    for (const warning of res.warnings) {
      try {
        this.#logger?.warn(`${operation}: ${warning}`);
      } catch {
        // the logger must never break a call
      }
    }
    return res;
  }

  #window(opts: TimeRangeOptions): { start: number; end: number } {
    return resolveWindow(opts, this.#now());
  }

  async #raw(
    signal: SignozSignal,
    spec: Record<string, unknown>,
    window: { start: number; end: number },
    requestType: "raw" | "trace",
    operation: string,
    abort: AbortSignal | undefined,
  ): Promise<{ rows: unknown[]; warnings: string[] }> {
    const body = {
      start: window.start,
      end: window.end,
      requestType,
      compositeQuery: {
        queries: [{ type: "builder_query", spec: { name: "A", signal, ...spec } }],
      },
    };
    const res = await this.queryRange(body, { operation, signal: abort });
    const result = obj(arr(obj(obj(res.data).data).results)[0]);
    return { rows: arr(result.rows), warnings: res.warnings };
  }

  /**
   * Raw log query. Paging uses the SDK's own cursor (the server's `nextCursor` skips rows in the
   * millisecond before it): the next page narrows the window to the boundary millisecond and
   * excludes the ids already returned there.
   */
  async queryLogs(options: QueryLogsOptions = {}): Promise<LogQueryResult> {
    const operation = "queryLogs";
    const limit = checkLimit(options.limit, DEFAULT_LOG_LIMIT, operation);
    let order = checkOrder(options.orderBy, operation);
    let window: { start: number; end: number };
    let exclude: string[] = [];
    let carried: Cursor | undefined;
    if (options.cursor !== undefined) {
      carried = decodeCursor(options.cursor);
      order = carried.o;
      exclude = carried.x;
      window =
        order === "desc"
          ? { start: carried.s, end: carried.m + 1 }
          : { start: carried.m, end: carried.e };
    } else {
      window = this.#window(options);
    }
    const userFilter = filterText(options.filter);
    const excludeFilter =
      exclude.length === 0
        ? ""
        : `id NOT IN (${exclude.map((id) => quoteFilterString(id)).join(", ")})`;
    const expression = andFilters(userFilter, excludeFilter);
    const spec: Record<string, unknown> = {
      order: [
        { key: { name: "timestamp" }, direction: order },
        { key: { name: "id" }, direction: order },
      ],
      limit,
    };
    if (expression !== "") spec.filter = { expression };
    if (options.selectFields !== undefined && options.selectFields.length > 0) {
      spec.selectFields = options.selectFields.map((name) => ({ name }));
    }
    const { rows, warnings } = await this.#raw(
      "logs",
      spec,
      window,
      "raw",
      operation,
      options.signal,
    );
    const records = rows.map(logRowToRecord);
    const result: LogQueryResult = { records, warnings };
    const last = records[records.length - 1];
    if (records.length >= limit && last !== undefined) {
      const boundary = nanosToMs(BigInt(last.timeUnixNano));
      const ids = records
        .filter((r) => nanosToMs(BigInt(r.timeUnixNano)) === boundary)
        .map((r) => r.id);
      const sameBoundary = carried !== undefined && carried.m === boundary ? carried.x : [];
      result.nextCursor = encodeCursor({
        v: 1,
        s: carried?.s ?? window.start,
        e: carried?.e ?? window.end,
        o: order,
        m: boundary,
        x: [...new Set([...sameBoundary, ...ids])],
      });
    }
    return result;
  }

  /** Raw span query: one {@link Span} per matching span (offset paging). */
  async queryTraces(options: QueryTracesOptions = {}): Promise<SpanQueryResult> {
    const operation = "queryTraces";
    const limit = checkLimit(options.limit, DEFAULT_LOG_LIMIT, operation);
    const order = checkOrder(options.orderBy, operation);
    const offset = options.offset ?? 0;
    if (!Number.isInteger(offset) || offset < 0)
      throw new ValidationError(`${operation}: offset must be >= 0`);
    let builder = new FilterBuilder();
    if (options.service !== undefined) builder = builder.eq("service.name", options.service);
    if (options.operation !== undefined) builder = builder.eq("name", options.operation);
    const expression = andFilters(builder, filterText(options.filter));
    const spec: Record<string, unknown> = {
      order: [{ key: { name: "timestamp" }, direction: order }],
      limit,
    };
    if (offset > 0) spec.offset = offset;
    if (expression !== "") spec.filter = { expression };
    const { rows, warnings } = await this.#raw(
      "traces",
      spec,
      this.#window(options),
      "raw",
      operation,
      options.signal,
    );
    return { spans: rows.map(spanRowToSpan), warnings };
  }

  /**
   * Full trace via `POST /api/v4/traces/{traceId}/waterfall`; `null` when SigNoz does not know it
   * or reports no spans for it. Span start times have millisecond precision (the waterfall reports
   * `time_unix` in ms). A trace larger than the server's waterfall window (`hasMore`) is completed
   * with raw span queries, as in every language.
   */
  async getTrace(
    traceId: string,
    options: { signal?: AbortSignal } = {},
  ): Promise<SignozTrace | null> {
    const id = normalizeTraceId(traceId);
    const res = await this.#api.call<unknown>(`/api/v4/traces/${id}/waterfall`, {
      method: "POST",
      json: {},
      operation: `getTrace ${id}`,
      signal: options.signal,
      nullOn404: true,
    });
    if (res === null) return null;
    const trace = waterfallToTrace(id, res.data);
    if (trace.spans.length === 0) return null;
    if (!trace.complete) {
      const data = obj(res.data);
      const startMs = Number(data.startTimestampMillis ?? 0);
      const endMs = Number(data.endTimestampMillis ?? 0);
      trace.spans = sortSpans(
        await this.#allSpans(id, new Date(startMs), new Date(endMs + 1), options.signal),
      );
      trace.complete = true;
      trace.totalSpans = Math.max(trace.totalSpans, trace.spans.length);
    }
    return trace;
  }

  /** Pages through every span of a trace with a frozen window (offset paging). */
  async #allSpans(id: string, start: Date, end: Date, signal?: AbortSignal): Promise<Span[]> {
    const out: Span[] = [];
    for (;;) {
      const page = await this.queryTraces({
        filter: `trace_id = ${quoteFilterString(id)}`,
        start,
        end,
        limit: MAX_RAW_LIMIT,
        orderBy: "asc",
        offset: out.length,
        ...(signal === undefined ? {} : { signal }),
      });
      out.push(...page.spans);
      if (page.spans.length < MAX_RAW_LIMIT) return out;
    }
  }

  /**
   * Trace search (`requestType: trace`, one row per trace). `startMicros` and `spanCount` come
   * from the waterfall of each hit (millisecond precision).
   */
  async searchTraces(query: SignozTraceSearchQuery = {}): Promise<TraceSummary[]> {
    const operation = "searchTraces";
    const limit = checkLimit(query.limit, 20, operation);
    let builder = new FilterBuilder();
    if (query.service !== undefined) builder = builder.eq("service.name", query.service);
    if (query.operation !== undefined) builder = builder.eq("name", query.operation);
    for (const [key, value] of Object.entries(query.tags ?? {})) builder = builder.eq(key, value);
    if (query.minDurationMicros !== undefined)
      builder = builder.gte("duration_nano", Math.round(query.minDurationMicros * 1000));
    if (query.maxDurationMicros !== undefined)
      builder = builder.lte("duration_nano", Math.round(query.maxDurationMicros * 1000));
    const expression = andFilters(builder, filterText(query.filter));
    const spec: Record<string, unknown> = {
      order: [{ key: { name: "timestamp" }, direction: "desc" }],
      limit,
    };
    if (expression !== "") spec.filter = { expression };
    const { rows } = await this.#raw(
      "traces",
      spec,
      this.#window(query),
      "trace",
      operation,
      query.signal,
    );
    const hits = rows
      .map((r) => obj(obj(r).data))
      .filter((d) => typeof d.trace_id === "string" && d.trace_id !== "");
    return mapLimit(hits, 4, async (data) => {
      const traceId = normalizeTraceId(String(data.trace_id));
      const summary: TraceSummary = {
        traceId,
        startMicros: 0,
        durationMicros: Math.floor(Number(data.duration_nano ?? 0) / 1000),
        spanCount: Number(data.span_count ?? 0),
      };
      if (typeof data["service.name"] === "string" && data["service.name"] !== "")
        summary.rootServiceName = data["service.name"];
      if (typeof data.name === "string" && data.name !== "") summary.rootName = data.name;
      const trace = await this.getTrace(
        traceId,
        query.signal === undefined ? {} : { signal: query.signal },
      );
      if (trace !== null) {
        const first = trace.spans[0];
        if (first !== undefined) summary.startMicros = first.startMicros;
        summary.spanCount = trace.totalSpans;
        if (trace.rootServiceName !== undefined) summary.rootServiceName = trace.rootServiceName;
        if (trace.rootName !== undefined) summary.rootName = trace.rootName;
      }
      return summary;
    });
  }

  /** Every service with spans in the window (default: the last hour), sorted. */
  async listServices(options: TimeRangeOptions = {}): Promise<string[]> {
    const window = this.#window(options);
    const result = await this.aggregate("traces")
      .count()
      .groupBy("service.name")
      .limit(MAX_RAW_LIMIT)
      .between(new Date(window.start), new Date(window.end))
      .run(options.signal === undefined ? {} : { signal: options.signal });
    return uniqueSorted(result.rows.map((r) => r.groups["service.name"]));
  }

  /** Span names of `service` in the window (default: the last hour), sorted. */
  async listOperations(service: string, options: TimeRangeOptions = {}): Promise<string[]> {
    if (service.trim() === "")
      throw new ValidationError("listOperations: service must not be empty");
    const window = this.#window(options);
    const result = await this.aggregate("traces")
      .filter(`service.name = ${filterLiteral(service)}`)
      .count()
      .groupBy("name")
      .limit(MAX_RAW_LIMIT)
      .between(new Date(window.start), new Date(window.end))
      .run(options.signal === undefined ? {} : { signal: options.signal });
    return uniqueSorted(result.rows.map((r) => r.groups.name));
  }

  /** Service dependency edges in the window (default: the last hour). */
  override async getDependencies(
    options: DependencyOptions & { signal?: AbortSignal } = {},
  ): Promise<DependencyLink[]> {
    const window = this.#window(options);
    const res = await this.#api.get<unknown>("/api/v1/dependency_graph", {
      method: "POST",
      json: { start: nsString(window.start), end: nsString(window.end), tags: [] },
      operation: "getDependencies",
      envelope: false,
      signal: options.signal,
    });
    return arr(res.data)
      .map(obj)
      .filter((e) => typeof e.parent === "string" && typeof e.child === "string")
      .map((e) => ({
        parent: String(e.parent),
        child: String(e.child),
        callCount: Number(e.callCount ?? 0),
      }));
  }

  /** Log fields (`GET /api/v1/fields/keys?signal=logs`), one entry per name and context. */
  async listLogFields(options: ListLogFieldsOptions = {}): Promise<LogField[]> {
    const res = await this.#api.get<unknown>("/api/v1/fields/keys", {
      query: { signal: "logs", searchText: options.search, limit: options.limit },
      operation: "listLogFields",
      signal: options.signal,
    });
    const out: LogField[] = [];
    for (const [name, entries] of Object.entries(obj(obj(res.data).keys))) {
      for (const entry of arr(entries).map(obj)) {
        const field: LogField = {
          name: typeof entry.name === "string" ? entry.name : name,
          context: typeof entry.fieldContext === "string" ? entry.fieldContext : "",
          dataType: typeof entry.fieldDataType === "string" ? entry.fieldDataType : "",
        };
        if (typeof entry.description === "string" && entry.description !== "")
          field.description = entry.description;
        out.push(field);
      }
    }
    return out.sort((a, b) =>
      a.name === b.name ? a.context.localeCompare(b.context) : a.name < b.name ? -1 : 1,
    );
  }

  /** Distinct values of a field (`GET /api/v1/fields/values`), sorted. */
  async listFieldValues(
    name: string,
    options: { signal?: AbortSignal; source?: SignozSignal; search?: string } = {},
  ): Promise<(string | number | boolean)[]> {
    const res = await this.#api.get<unknown>("/api/v1/fields/values", {
      query: { signal: options.source ?? "logs", name, searchText: options.search },
      operation: "listFieldValues",
      signal: options.signal,
    });
    const values = obj(obj(res.data).values);
    const all = [
      ...arr(values.stringValues),
      ...arr(values.numberValues),
      ...arr(values.boolValues),
    ].filter(
      (v): v is string | number | boolean =>
        typeof v === "string" || typeof v === "number" || typeof v === "boolean",
    );
    return [...new Set(all)].sort((a, b) => String(a).localeCompare(String(b)));
  }

  /** Starts an aggregation (count / percentiles / …) over `logs` or `traces`. */
  aggregate(signal: SignozSignal): AggregateBuilder {
    return new AggregateBuilder(signal, (body, operation, abort) =>
      this.queryRange(body, { operation, signal: abort }),
    );
  }
}

function uniqueSorted(values: unknown[]): string[] {
  return [...new Set(values.filter((v): v is string => typeof v === "string" && v !== ""))].sort();
}
