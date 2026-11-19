// Service-owned: builder for SigNoz v5 filter expressions (search syntax).

import { ValidationError } from "./core/index.js";

/** A literal accepted by {@link FilterBuilder} comparisons. */
export type FilterValue = string | number | boolean;

/** Comparison operators of the SigNoz search syntax. */
export type FilterOperator = "=" | "!=" | "<" | "<=" | ">" | ">=";

const FIELD = /^[A-Za-z0-9_.:-]+$/;
const OPERATORS: ReadonlySet<string> = new Set(["=", "!=", "<", "<=", ">", ">="]);

/** Quotes a string literal: `'…'` with `\` → `\\` and `'` → `\'`. */
export function quoteFilterString(value: string): string {
  return `'${value.replace(/\\/g, "\\\\").replace(/'/g, "\\'")}'`;
}

/**
 * Escapes a value for `CONTAINS`/`LIKE` so it matches literally: `\` → `\\`, `%` → `\%`,
 * `_` → `\_` (string quoting is applied afterwards by {@link quoteFilterString}).
 */
export function escapeLikePattern(value: string): string {
  return value.replace(/\\/g, "\\\\").replace(/%/g, "\\%").replace(/_/g, "\\_");
}

/** Renders a literal: numbers bare, booleans `true`/`false`, strings quoted. */
export function filterLiteral(value: FilterValue): string {
  if (typeof value === "number") {
    if (!Number.isFinite(value)) throw new ValidationError("filter: numbers must be finite");
    return String(value);
  }
  if (typeof value === "boolean") return value ? "true" : "false";
  return quoteFilterString(value);
}

function field(name: string): string {
  if (!FIELD.test(name)) {
    throw new ValidationError(`filter: invalid field name ${JSON.stringify(name)}`);
  }
  return name;
}

function list(values: readonly FilterValue[]): string {
  if (values.length === 0) throw new ValidationError("filter: IN lists must not be empty");
  return `(${values.map(filterLiteral).join(", ")})`;
}

/**
 * Immutable builder for SigNoz filter expressions, e.g.
 * `new FilterBuilder().eq("service.name", "x").in("severity_text", ["WARN", "ERROR"]).build()`
 * → `service.name = 'x' AND severity_text IN ('WARN', 'ERROR')`. Clauses are joined with `AND`;
 * use {@link FilterBuilder.or} / {@link FilterBuilder.not} for groups (rendered in parentheses).
 * Every method returns a new builder.
 */
export class FilterBuilder {
  readonly #clauses: readonly string[];

  constructor(clauses: readonly string[] = []) {
    this.#clauses = Object.freeze([...clauses]);
  }

  /** A new empty builder. */
  static create(): FilterBuilder {
    return new FilterBuilder();
  }

  #add(clause: string): FilterBuilder {
    return new FilterBuilder([...this.#clauses, clause]);
  }

  /** `field <op> value`. */
  where(name: string, op: FilterOperator, value: FilterValue): FilterBuilder {
    if (!OPERATORS.has(op)) throw new ValidationError(`filter: unknown operator ${op}`);
    return this.#add(`${field(name)} ${op} ${filterLiteral(value)}`);
  }

  /** `field = value`. */
  eq(name: string, value: FilterValue): FilterBuilder {
    return this.where(name, "=", value);
  }

  /** `field != value`. */
  ne(name: string, value: FilterValue): FilterBuilder {
    return this.where(name, "!=", value);
  }

  /** `field < value`. */
  lt(name: string, value: number | string): FilterBuilder {
    return this.where(name, "<", value);
  }

  /** `field <= value`. */
  lte(name: string, value: number | string): FilterBuilder {
    return this.where(name, "<=", value);
  }

  /** `field > value`. */
  gt(name: string, value: number | string): FilterBuilder {
    return this.where(name, ">", value);
  }

  /** `field >= value`. */
  gte(name: string, value: number | string): FilterBuilder {
    return this.where(name, ">=", value);
  }

  /** `field IN (v1, v2, …)` (non-empty list). */
  in(name: string, values: readonly FilterValue[]): FilterBuilder {
    return this.#add(`${field(name)} IN ${list(values)}`);
  }

  /** `field NOT IN (v1, v2, …)` (non-empty list). */
  notIn(name: string, values: readonly FilterValue[]): FilterBuilder {
    return this.#add(`${field(name)} NOT IN ${list(values)}`);
  }

  /** `field CONTAINS 'text'` — matches `text` literally (LIKE wildcards are escaped). */
  contains(name: string, text: string): FilterBuilder {
    return this.#add(`${field(name)} CONTAINS ${quoteFilterString(escapeLikePattern(text))}`);
  }

  /** `NOT field CONTAINS 'text'` (literal match). */
  notContains(name: string, text: string): FilterBuilder {
    return this.#add(`NOT ${field(name)} CONTAINS ${quoteFilterString(escapeLikePattern(text))}`);
  }

  /** `field LIKE 'pattern'` — `%` and `_` are wildcards; the pattern is passed as given. */
  like(name: string, pattern: string): FilterBuilder {
    return this.#add(`${field(name)} LIKE ${quoteFilterString(pattern)}`);
  }

  /** `field REGEXP 're'`. */
  regexp(name: string, pattern: string): FilterBuilder {
    return this.#add(`${field(name)} REGEXP ${quoteFilterString(pattern)}`);
  }

  /** `field EXISTS`. */
  exists(name: string): FilterBuilder {
    return this.#add(`${field(name)} EXISTS`);
  }

  /** `field NOT EXISTS`. */
  notExists(name: string): FilterBuilder {
    return this.#add(`${field(name)} NOT EXISTS`);
  }

  /** Full-text search of the log body: `'text'`. */
  text(value: string): FilterBuilder {
    return this.#add(quoteFilterString(value));
  }

  /** A raw expression, passed through untouched (parenthesised when combined). */
  raw(expression: string): FilterBuilder {
    const trimmed = expression.trim();
    if (trimmed === "") return this;
    return this.#add(/\bOR\b/i.test(trimmed) ? `(${trimmed})` : trimmed);
  }

  /** `(a OR b …)` from other builders/expressions; empty alternatives are ignored. */
  or(...alternatives: (FilterBuilder | string)[]): FilterBuilder {
    const parts = alternatives
      .map((a) => (typeof a === "string" ? a.trim() : a.build()))
      .filter((p) => p !== "");
    if (parts.length === 0) return this;
    return this.#add(
      parts.length === 1
        ? `(${parts[0] as string})`
        : `(${parts.map((p) => `(${p})`).join(" OR ")})`,
    );
  }

  /** `NOT (expression)`. */
  not(expression: FilterBuilder | string): FilterBuilder {
    const text = typeof expression === "string" ? expression.trim() : expression.build();
    if (text === "") return this;
    return this.#add(`NOT (${text})`);
  }

  /** `true` when no clause was added. */
  get isEmpty(): boolean {
    return this.#clauses.length === 0;
  }

  /** The expression (`""` when empty). */
  build(): string {
    return this.#clauses.join(" AND ");
  }

  toString(): string {
    return this.build();
  }
}

/** Joins expressions with `AND`, parenthesising each non-empty part when there is more than one. */
export function andFilters(...parts: (string | FilterBuilder | undefined)[]): string {
  const texts = parts
    .map((p) => (p === undefined ? "" : typeof p === "string" ? p.trim() : p.build()))
    .filter((p) => p !== "");
  if (texts.length <= 1) return texts[0] ?? "";
  return texts.map((t) => `(${t})`).join(" AND ");
}
