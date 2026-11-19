// Service-owned: LogQueryClient for SigNoz (search, live tail over SSE, streams, labels).

import { fetch as undiciFetch, type Dispatcher } from "undici";
import {
  AuthenticationError,
  ConfigError,
  PermissionDeniedError,
  SignozError,
  UnavailableError,
  UnsupportedError,
  errorForStatus,
  sleep,
} from "./core/index.js";
import type { SignozApi } from "./http.js";
import {
  BaseLogQueryClient,
  type LogSearchOptions,
  type LogSearchResult,
  type TailHandle,
  type TailOptions,
} from "./logging/query.js";
import type { LogRecord } from "./logging/types.js";
import { logRowToRecord, nanosToMs, type SignozLogRecord } from "./mapping.js";
import type { SignozQueryClient } from "./query.js";

/** Extra options of {@link SignozLogQueryClient.tail}. */
export interface SignozTailOptions extends TailOptions {
  /** Delay before reconnecting after the stream ends or fails. Default `intervalMs` (1000 ms). */
  reconnectDelayMs?: number;
}

/** Live-tail plumbing supplied by the client. @internal */
export interface TailTransport {
  api: SignozApi;
  headers: () => Record<string, string>;
  dispatcher: () => Dispatcher;
}

const FATAL_TAIL_ERRORS = [
  AuthenticationError,
  PermissionDeniedError,
  UnsupportedError,
  ConfigError,
];

/**
 * {@link LogQueryClient} for SigNoz. `query` strings are SigNoz filter expressions (search syntax);
 * bare words are full-text searches of the log body (`checkout failed`), and
 * `service.name = 'x' AND severity_text IN ('ERROR')` style expressions are passed through.
 * Obtain it from `SignozClient.logs`.
 */
export class SignozLogQueryClient extends BaseLogQueryClient {
  readonly #query: SignozQueryClient;
  readonly #tail: TailTransport;

  /** @internal Use `SignozClient.logs`. */
  constructor(query: SignozQueryClient, tail: TailTransport) {
    super();
    this.#query = query;
    this.#tail = tail;
  }

  /** Searches logs (default window: the last hour, newest first, 100 per page). */
  async search(query: string, opts: LogSearchOptions = {}): Promise<LogSearchResult> {
    const page = await this.#query.queryLogs({
      filter: query,
      ...(opts.start === undefined ? {} : { start: opts.start }),
      ...(opts.end === undefined ? {} : { end: opts.end }),
      ...(opts.limit === undefined ? {} : { limit: opts.limit }),
      ...(opts.order === undefined ? {} : { orderBy: opts.order }),
      ...(opts.cursor === undefined ? {} : { cursor: opts.cursor }),
      ...(opts.signal === undefined ? {} : { signal: opts.signal }),
    });
    const result: LogSearchResult = { records: page.records };
    if (page.nextCursor !== undefined) result.nextCursor = page.nextCursor;
    return result;
  }

  /** Service names that sent logs (SigNoz has no streams; `service.name` is the equivalent). */
  override async listStreams(): Promise<string[]> {
    const values = await this.#query.listFieldValues("service.name", { source: "logs" });
    return values.filter((v): v is string => typeof v === "string").sort();
  }

  /** Distinct log field names (attributes, resource attributes and intrinsic fields), sorted. */
  override async listLabels(): Promise<string[]> {
    const fields = await this.#query.listLogFields();
    return [...new Set(fields.map((f) => f.name))].sort();
  }

  /**
   * Live tail over `GET /api/v3/logs/livetail` (server-sent events). Calls `onRecord` once per
   * matching record; reconnects after the stream ends (resuming at the last seen timestamp and
   * de-duplicating by row id). Authentication, permission and unsupported errors stop the tail
   * after `onError`.
   */
  override tail(
    query: string,
    onRecord: (record: LogRecord) => void,
    opts: SignozTailOptions = {},
  ): TailHandle {
    const controller = new AbortController();
    const stopped = (): boolean => controller.signal.aborted;
    const retryDelay = opts.reconnectDelayMs ?? opts.intervalMs ?? 1000;
    let sinceMs = (opts.start ?? new Date()).getTime();
    let seen = new Set<string>();

    const deliver = (record: SignozLogRecord): void => {
      const ms = nanosToMs(BigInt(record.timeUnixNano));
      if (ms < sinceMs) return;
      if (record.id !== "" && seen.has(record.id)) return;
      if (ms > sinceMs) {
        sinceMs = ms;
        seen = new Set();
      }
      if (record.id !== "") seen.add(record.id);
      try {
        onRecord(record);
      } catch (error) {
        opts.onError?.(error);
      }
    };

    const connect = async (): Promise<void> => {
      const operation = "logging.tail";
      const url = this.#tail.api.transport.buildUrl("/api/v3/logs/livetail", {
        start: sinceMs,
        filter: query.trim() === "" ? undefined : query,
      });
      const res = await undiciFetch(url, {
        headers: {
          ...this.#tail.headers(),
          ...this.#tail.api.authHeaders(operation),
          accept: "text/event-stream",
          "user-agent": this.#tail.api.transport.userAgent,
        },
        signal: controller.signal,
        dispatcher: this.#tail.dispatcher(),
      });
      if (res.status >= 400) {
        const text = await res.text().catch(() => "");
        let reason: string | undefined;
        try {
          const body = JSON.parse(text) as { error?: { message?: unknown } };
          reason = typeof body.error?.message === "string" ? body.error.message : undefined;
        } catch {
          reason = undefined;
        }
        throw errorForStatus(
          operation,
          res.status,
          reason === undefined ? {} : { reason: this.#tail.api.transport.redact(reason) },
        );
      }
      if (!/event-stream/i.test(res.headers.get("content-type") ?? "")) {
        await res.body?.cancel().catch(() => undefined);
        throw new UnsupportedError(`${operation}: live tail not available on this server version`);
      }
      if (res.body === null) return;
      const decoder = new TextDecoder();
      let buffer = "";
      for await (const chunk of res.body as AsyncIterable<Uint8Array>) {
        if (stopped()) break;
        buffer += decoder.decode(chunk, { stream: true });
        let index: number;
        while ((index = buffer.search(/\r?\n\r?\n/)) >= 0) {
          const raw = buffer.slice(0, index);
          buffer = buffer.slice(index).replace(/^\r?\n\r?\n/, "");
          handleEvent(raw);
        }
      }
    };

    const handleEvent = (raw: string): void => {
      let event = "message";
      const data: string[] = [];
      for (const line of raw.split(/\r?\n/)) {
        if (line.startsWith(":")) continue;
        const colon = line.indexOf(":");
        const fieldName = colon < 0 ? line : line.slice(0, colon);
        const value = colon < 0 ? "" : line.slice(colon + 1).replace(/^ /, "");
        if (fieldName === "event") event = value;
        else if (fieldName === "data") data.push(value);
      }
      if (data.length === 0) return;
      const payload = data.join("\n");
      if (event === "error") {
        opts.onError?.(
          new UnavailableError(
            `logging.tail: ${this.#tail.api.transport.redact(payload).slice(0, 300)}`,
          ),
        );
        return;
      }
      try {
        deliver(logRowToRecord(JSON.parse(payload)));
      } catch (error) {
        opts.onError?.(error);
      }
    };

    const loop = async (): Promise<void> => {
      while (!stopped()) {
        try {
          await connect();
        } catch (error) {
          if (stopped()) break;
          opts.onError?.(
            error instanceof SignozError || !(error instanceof Error)
              ? error
              : new UnavailableError(`logging.tail: ${error.message}`, { cause: error }),
          );
          if (FATAL_TAIL_ERRORS.some((cls) => error instanceof cls)) {
            controller.abort();
            break;
          }
        }
        if (stopped()) break;
        await sleep(retryDelay, controller.signal).catch(() => undefined);
      }
    };
    void loop();
    return {
      stop(): void {
        controller.abort();
      },
    };
  }
}
