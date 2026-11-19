// Service-owned: aggregate(...) builder over the v5 query_range API (scalar and time series).

import { ValidationError } from "./core/index.js";
import type { FilterBuilder } from "./filter.js";

/** Telemetry signal of a SigNoz query. */
export type SignozSignal = "logs" | "traces";

/** One aggregation: a SigNoz expression such as `count()` or `p99(duration_nano)`. */
export interface Aggregation {
  expression: string;
  /** Name of the value in results (default: the expression). */
  alias?: string;
}

/** A group-by field with an optional context (`resource`, `attribute`, `span`, `log`). */
export interface GroupByField {
  name: string;
  fieldContext?: string;
  fieldDataType?: string;
}

/** A group value as returned by SigNoz. */
export type GroupValue = string | number | boolean | null;

/** One scalar result row. */
export interface AggregateRow {
  /** Group-by values by field name. */
  groups: Record<string, GroupValue>;
  /** Aggregation values by alias. */
  values: Record<string, number | null>;
}

/** Result of {@link AggregateBuilder.run}. */
export interface AggregateResult {
  rows: AggregateRow[];
  /** Server warnings (for example unknown fields in the filter). */
  warnings: string[];
}

/** One point of a time series. */
export interface AggregatePoint {
  timestamp: Date;
  value: number;
}

/** One series of {@link AggregateBuilder.timeSeries}. */
export interface AggregateSeries {
  /** Alias of the aggregation the series belongs to. */
  alias: string;
  /** Group-by labels of the series. */
  labels: Record<string, GroupValue>;
  points: AggregatePoint[];
}

/** Executes a built aggregate query (provided by {@link SignozQueryClient}). @internal */
export type AggregateExecutor = (
  body: Record<string, unknown>,
  operation: string,
  signal: AbortSignal | undefined,
) => Promise<{ data: unknown; warnings: string[] }>;

const DEFAULT_WINDOW_MS = 3_600_000;
const FIELD = /^[A-Za-z0-9_.:-]+$/;

type Obj = Record<string, unknown>;
function obj(value: unknown): Obj {
  return value !== null && typeof value === "object" && !Array.isArray(value) ? (value as Obj) : {};
}
function arr(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}
function toNumber(value: unknown): number | null {
  if (typeof value === "number") return Number.isFinite(value) ? value : null;
  if (typeof value === "string" && value.trim() !== "" && Number.isFinite(Number(value)))
    return Number(value);
  return null;
}
function toGroup(value: unknown): GroupValue {
  return typeof value === "string" || typeof value === "number" || typeof value === "boolean"
    ? value
    : null;
}

/**
 * Fluent builder for SigNoz aggregations (`requestType: scalar` or `time_series`), e.g.
 * `query.aggregate("traces").count("calls").p99("duration_nano", "p99").groupBy("name").run()`.
 * Aliases are mapped back from SigNoz's `__result_<i>` columns by aggregation index.
 * Default window: the last hour.
 */
export class AggregateBuilder {
  readonly signal: SignozSignal;
  readonly #execute: AggregateExecutor;
  readonly #aggregations: Aggregation[] = [];
  readonly #groupBy: GroupByField[] = [];
  readonly #order: { key: string; direction: "asc" | "desc" }[] = [];
  #filter = "";
  #limit: number | undefined;
  #start: Date | undefined;
  #end: Date | undefined;

  constructor(signal: SignozSignal, execute: AggregateExecutor) {
    if (!(["logs", "traces"] as string[]).includes(signal)) {
      throw new ValidationError('aggregate: signal must be "logs" or "traces"');
    }
    this.signal = signal;
    this.#execute = execute;
  }

  /** Sets the filter expression (replaces an earlier one). */
  filter(expression: string | FilterBuilder): this {
    this.#filter = typeof expression === "string" ? expression.trim() : expression.build();
    return this;
  }

  /** Adds any aggregation expression. */
  expression(expression: string, alias?: string): this {
    const text = expression.trim();
    if (text === "") throw new ValidationError("aggregate: expression must not be empty");
    const aggregation: Aggregation = { expression: text };
    if (alias !== undefined) aggregation.alias = alias;
    this.#aggregations.push(aggregation);
    return this;
  }

  /** `count()`. */
  count(alias?: string): this {
    return this.expression("count()", alias);
  }

  /** `count_distinct(field)`. */
  countDistinct(field: string, alias?: string): this {
    return this.expression(`count_distinct(${checkField(field)})`, alias);
  }

  /** `sum(field)`. */
  sum(field: string, alias?: string): this {
    return this.expression(`sum(${checkField(field)})`, alias);
  }

  /** `avg(field)`. */
  avg(field: string, alias?: string): this {
    return this.expression(`avg(${checkField(field)})`, alias);
  }

  /** `min(field)`. */
  min(field: string, alias?: string): this {
    return this.expression(`min(${checkField(field)})`, alias);
  }

  /** `max(field)`. */
  max(field: string, alias?: string): this {
    return this.expression(`max(${checkField(field)})`, alias);
  }

  /** `p<percentile>(field)`; percentile is one of 5, 10, 20, 25, 50, 75, 90, 95, 99. */
  percentile(percentile: number, field = "duration_nano", alias?: string): this {
    if (![5, 10, 20, 25, 50, 75, 90, 95, 99].includes(percentile)) {
      throw new ValidationError(
        "aggregate: percentile must be 5, 10, 20, 25, 50, 75, 90, 95 or 99",
      );
    }
    return this.expression(`p${percentile}(${checkField(field)})`, alias);
  }

  /** `p50(field)` (default field `duration_nano`). */
  p50(field = "duration_nano", alias?: string): this {
    return this.percentile(50, field, alias);
  }

  /** `p90(field)`. */
  p90(field = "duration_nano", alias?: string): this {
    return this.percentile(90, field, alias);
  }

  /** `p95(field)`. */
  p95(field = "duration_nano", alias?: string): this {
    return this.percentile(95, field, alias);
  }

  /** `p99(field)`. */
  p99(field = "duration_nano", alias?: string): this {
    return this.percentile(99, field, alias);
  }

  /** Adds group-by fields. */
  groupBy(...fields: (string | GroupByField)[]): this {
    for (const f of fields) {
      const g = typeof f === "string" ? { name: f } : { ...f };
      checkField(g.name);
      this.#groupBy.push(g);
    }
    return this;
  }

  /** Orders by a group field or an aggregation alias/expression. */
  orderBy(key: string, direction: "asc" | "desc" = "desc"): this {
    if (!(["asc", "desc"] as string[]).includes(direction)) {
      throw new ValidationError('aggregate: direction must be "asc" or "desc"');
    }
    this.#order.push({ key, direction });
    return this;
  }

  /** Maximum number of groups. */
  limit(limit: number): this {
    if (!Number.isInteger(limit) || limit <= 0)
      throw new ValidationError("aggregate: limit must be a positive integer");
    this.#limit = limit;
    return this;
  }

  /** Time window (default: the last hour). */
  between(start: Date, end: Date): this {
    this.#start = start;
    this.#end = end;
    return this;
  }

  /** Aliases in aggregation order. */
  aliases(): string[] {
    return this.#aggregations.map((a) => a.alias ?? a.expression);
  }

  /** The request body that {@link run} / {@link timeSeries} send (for inspection). */
  toRequest(
    requestType: "scalar" | "time_series" = "scalar",
    stepSeconds?: number,
  ): Record<string, unknown> {
    if (this.#aggregations.length === 0) {
      throw new ValidationError("aggregate: add at least one aggregation (count(), p99(), …)");
    }
    const end = this.#end ?? new Date();
    const start = this.#start ?? new Date(end.getTime() - DEFAULT_WINDOW_MS);
    if (start.getTime() >= end.getTime())
      throw new ValidationError("aggregate: start must be before end");
    const spec: Record<string, unknown> = {
      name: "A",
      signal: this.signal,
      aggregations: this.#aggregations.map((a) =>
        a.alias === undefined
          ? { expression: a.expression }
          : { expression: a.expression, alias: a.alias },
      ),
    };
    if (this.#filter !== "") spec.filter = { expression: this.#filter };
    if (this.#groupBy.length > 0) spec.groupBy = this.#groupBy;
    if (this.#order.length > 0) {
      spec.order = this.#order.map((o) => ({ key: { name: o.key }, direction: o.direction }));
    }
    if (this.#limit !== undefined) spec.limit = this.#limit;
    if (stepSeconds !== undefined) spec.stepInterval = stepSeconds;
    return {
      start: start.getTime(),
      end: end.getTime(),
      requestType,
      compositeQuery: { queries: [{ type: "builder_query", spec }] },
    };
  }

  /** Runs a scalar query: one row per group. */
  async run(options: { signal?: AbortSignal } = {}): Promise<AggregateResult> {
    const body = this.toRequest("scalar");
    const aliases = this.aliases();
    const res = await this.#execute(body, `aggregate.${this.signal}`, options.signal);
    const result = obj(arr(obj(obj(res.data).data).results)[0]);
    const columns = arr(result.columns).map(obj);
    const rows = arr(result.data).map((raw) => {
      const values = arr(raw);
      const row: AggregateRow = { groups: {}, values: {} };
      columns.forEach((col, i) => {
        if (col.columnType === "group") {
          row.groups[String(col.name)] = toGroup(values[i]);
        } else {
          const index = typeof col.aggregationIndex === "number" ? col.aggregationIndex : 0;
          row.values[aliases[index] ?? String(col.name)] = toNumber(values[i]);
        }
      });
      return row;
    });
    return { rows, warnings: res.warnings };
  }

  /** Runs a time-series query with buckets of `stepSeconds` (default: chosen by SigNoz). */
  async timeSeries(
    stepSeconds?: number,
    options: { signal?: AbortSignal } = {},
  ): Promise<AggregateSeries[]> {
    if (stepSeconds !== undefined && (!Number.isInteger(stepSeconds) || stepSeconds <= 0)) {
      throw new ValidationError("aggregate: stepSeconds must be a positive integer");
    }
    const body = this.toRequest("time_series", stepSeconds);
    const aliases = this.aliases();
    const res = await this.#execute(body, `aggregate.${this.signal}`, options.signal);
    const result = obj(arr(obj(obj(res.data).data).results)[0]);
    const out: AggregateSeries[] = [];
    for (const agg of arr(result.aggregations).map(obj)) {
      const index = typeof agg.index === "number" ? agg.index : 0;
      const alias = aliases[index] ?? String(agg.alias);
      for (const series of arr(agg.series).map(obj)) {
        const labels: Record<string, GroupValue> = {};
        for (const label of arr(series.labels).map(obj)) {
          labels[String(obj(label.key).name)] = toGroup(label.value);
        }
        const points = arr(series.values)
          .map(obj)
          .map((p) => ({
            timestamp: new Date(Number(p.timestamp)),
            value: toNumber(p.value) ?? 0,
          }));
        out.push({ alias, labels, points });
      }
    }
    return out;
  }
}

function checkField(name: string): string {
  if (!FIELD.test(name))
    throw new ValidationError(`aggregate: invalid field name ${JSON.stringify(name)}`);
  return name;
}
