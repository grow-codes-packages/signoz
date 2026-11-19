// Service-owned: SigNoz HTTP conventions shared by the query modules.

import {
  CancelledError,
  ConfigError,
  type HttpTransport,
  type QueryValue,
  UnavailableError,
  UnsupportedError,
} from "./core/index.js";

/** Options of {@link SignozApi.call}. */
export interface ApiCallOptions {
  method?: "GET" | "POST";
  query?: Readonly<Record<string, QueryValue>>;
  json?: unknown;
  /** Operation name used in error messages. */
  operation: string;
  signal?: AbortSignal | undefined;
  /** Unwrap `{"status":"success","data":…}` (default true). Legacy v1 endpoints return bare JSON. */
  envelope?: boolean;
  /** Return `null` instead of throwing `NotFoundError` on HTTP 404. */
  nullOn404?: boolean;
  /** Send the `SIGNOZ-API-KEY` header (default true). */
  auth?: boolean;
}

/** Response of {@link SignozApi.call} with the envelope's `warning` messages. */
export interface ApiResult<T> {
  data: T;
  warnings: string[];
}

function isJsonContentType(value: string | null): boolean {
  return value !== null && /\bjson\b/i.test(value);
}

function warningsOf(body: unknown): string[] {
  const warning = (body as { data?: { warning?: unknown } } | null)?.data?.warning;
  if (warning === null || typeof warning !== "object") return [];
  const list = (warning as { warnings?: unknown }).warnings;
  const out: string[] = [];
  if (Array.isArray(list)) {
    for (const w of list) {
      const message = (w as { message?: unknown } | null)?.message;
      if (typeof message === "string" && message !== "") out.push(message);
    }
  }
  const message = (warning as { message?: unknown }).message;
  if (out.length === 0 && typeof message === "string" && message !== "") out.push(message);
  return out;
}

/**
 * Thin layer over the shared transport: SigNoz auth header, JSON-only responses (the server answers
 * unknown routes with `200 text/html`, mapped to `UnsupportedError`), the `{status,data}` envelope
 * and the `data.warning` list. Status codes are mapped by the transport (SPEC §2.1). @internal
 */
export class SignozApi {
  readonly #transport: HttpTransport;
  readonly #apiKey: () => string | undefined;

  constructor(transport: HttpTransport, apiKey: () => string | undefined) {
    this.#transport = transport;
    this.#apiKey = apiKey;
  }

  /** The underlying transport. */
  get transport(): HttpTransport {
    return this.#transport;
  }

  /** Headers for an authenticated call; throws `ConfigError` when no API key is configured. */
  authHeaders(operation: string): Record<string, string> {
    const key = this.#apiKey();
    if (key === undefined) {
      throw new ConfigError(
        `${operation}: apiKey is required for SigNoz query calls (GROW_SIGNOZ_API_KEY / SIGNOZ_API_KEY)`,
      );
    }
    return { "signoz-api-key": key };
  }

  /** Calls the API and returns the decoded payload (or `null` on 404 with `nullOn404`). */
  async call<T>(path: string, options: ApiCallOptions): Promise<ApiResult<T> | null> {
    // Same in every language: any call on a closed client raises CANCELLED.
    if (this.#transport.closed) throw new CancelledError(`${options.operation}: client is closed`);
    const headers = options.auth === false ? {} : this.authHeaders(options.operation);
    const method = options.method ?? "GET";
    const res = await this.#transport.request<string>({
      method,
      path,
      headers,
      operation: options.operation,
      responseType: "text",
      // Every SigNoz query endpoint is read-only, so POST queries are safe to retry.
      idempotent: true,
      ...(options.query === undefined ? {} : { query: options.query }),
      ...(options.json === undefined ? {} : { json: options.json }),
      ...(options.signal === undefined ? {} : { signal: options.signal }),
      ...(options.nullOn404 === true ? { allowStatus: [404] } : {}),
    });
    if (res.status === 404) return null;
    if (!isJsonContentType(res.headers.get("content-type"))) {
      throw new UnsupportedError(
        `${options.operation}: endpoint not available on this server version (non-JSON response, HTTP ${res.status})`,
        { httpStatus: res.status },
      );
    }
    let body: unknown;
    try {
      body = res.data.trim() === "" ? undefined : (JSON.parse(res.data) as unknown);
    } catch {
      throw new UnavailableError(`${options.operation}: invalid JSON in response`, {
        retryable: false,
        httpStatus: res.status,
      });
    }
    if (options.envelope === false) return { data: body as T, warnings: [] };
    const envelope = body as { status?: unknown; data?: unknown } | null | undefined;
    if (envelope === null || typeof envelope !== "object" || envelope.status !== "success") {
      throw new UnavailableError(`${options.operation}: unexpected response shape`, {
        retryable: false,
        httpStatus: res.status,
      });
    }
    return { data: envelope.data as T, warnings: warningsOf(body) };
  }

  /** Like {@link call} but never `null` (no `nullOn404`). */
  async get<T>(path: string, options: ApiCallOptions): Promise<ApiResult<T>> {
    return (await this.call<T>(path, { ...options, nullOn404: false })) as ApiResult<T>;
  }
}
