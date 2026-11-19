// Service-owned: SignozClient — health, ping, version, query clients, log client and tracing.

import {
  type HealthStatus,
  HttpTransport,
  type TransportOptions,
  UnsupportedError,
  createDispatcher,
  healthStatus,
  probeHealth,
} from "./core/index.js";
import { SignozConfig, type OtlpProtocol, type SignozConfigOptions } from "./config.js";
import { SignozApi } from "./http.js";
import { BatchingLogClient, type BatchingLogClientOptions } from "./logging/client.js";
import type { Attributes } from "./logging/types.js";
import { SignozLogQueryClient } from "./logs.js";
import { SignozLogExporter } from "./otlp.js";
import { SignozQueryClient } from "./query.js";
import { initTracing, type SignozTracingConfig } from "./traces.js";
import type { InitTracingOptions, TracingHandle } from "./tracing/init.js";
import type { Dispatcher } from "undici";

/** Per-call options. */
export interface CallOptions {
  /** Cancels the call (`CancelledError`). */
  signal?: AbortSignal;
}

/** Test seams (undici `MockAgent`, fake sleep). Not part of the stable API. */
export type ClientInternals = Pick<TransportOptions, "dispatcher" | "sleep" | "now">;

/** Options of {@link SignozClient.logClient} / {@link createLogClient}. */
export interface SignozLogClientOptions extends BatchingLogClientOptions {
  /** `service.name` resource attribute (default `unknown_service`). */
  serviceName?: string;
  /** `service.version` resource attribute. */
  serviceVersion?: string;
  /** `deployment.environment` resource attribute. */
  environment?: string;
  /** OTLP protocol (default: the config's `otlpProtocol`). */
  protocol?: OtlpProtocol;
  /** Collector endpoint (default: the config's `otlpEndpoint`). */
  endpoint?: string;
  /** Extra export headers. */
  headers?: Record<string, string>;
}

/**
 * Builds a {@link BatchingLogClient} that sends OTLP logs to the SigNoz collector. Does not need
 * an API key (only `ingestionKey` for SigNoz Cloud).
 */
export function createLogClient(
  config: SignozConfig | SignozConfigOptions = SignozConfig.fromEnv(),
  options: SignozLogClientOptions = {},
): BatchingLogClient {
  const cfg = SignozConfig.from(config);
  const { serviceName, serviceVersion, environment, protocol, endpoint, headers, ...batching } =
    options;
  const resource: Attributes = { "service.name": serviceName ?? "unknown_service" };
  if (serviceVersion !== undefined) resource["service.version"] = serviceVersion;
  if (environment !== undefined) resource["deployment.environment"] = environment;
  const exporter = new SignozLogExporter(cfg, {
    ...(protocol === undefined ? {} : { protocol }),
    ...(endpoint === undefined ? {} : { endpoint }),
    ...(headers === undefined ? {} : { headers }),
  });
  return new BatchingLogClient(exporter, {
    ...batching,
    resource: { ...resource, ...batching.resource },
  });
}

/**
 * Client for SigNoz: `health()`, `ping()`, `serverVersion()`, the query clients
 * ({@link SignozClient.query}, {@link SignozClient.logs}, {@link SignozClient.traces}), OTLP log
 * clients ({@link SignozClient.logClient}) and tracing ({@link SignozClient.initTracing}).
 * `close()` flushes and closes every log client and tracing handle created through it.
 */
export class SignozClient {
  /** The immutable configuration. */
  readonly config: SignozConfig;
  /** Query API (logs, spans, traces, services, aggregations, log fields). */
  readonly query: SignozQueryClient;
  /** Logging-category query client (search, tail, streams, labels, waitForLog). */
  readonly logs: SignozLogQueryClient;
  readonly #transport: HttpTransport;
  readonly #api: SignozApi;
  readonly #logClients = new Set<BatchingLogClient>();
  readonly #tracing = new Set<TracingHandle>();
  #dispatcher: Dispatcher | undefined;
  readonly #internalDispatcher: Dispatcher | undefined;
  #closed = false;

  /** Accepts a {@link SignozConfig} or plain options. */
  constructor(config: SignozConfigOptions = {}, internals: ClientInternals = {}) {
    const cfg = SignozConfig.from(config);
    this.config = cfg;
    this.#internalDispatcher = internals.dispatcher;
    this.#transport = new HttpTransport({
      ...internals,
      config: cfg,
      knownSecrets: () => cfg.knownSecrets(),
    });
    this.#api = new SignozApi(this.#transport, () => cfg.apiKey?.reveal());
    this.query = new SignozQueryClient(this.#api, cfg.logger, internals.now);
    this.logs = new SignozLogQueryClient(this.query, {
      api: this.#api,
      headers: () => ({ ...cfg.headers }),
      dispatcher: () => {
        this.#dispatcher ??= this.#internalDispatcher ?? createDispatcher(cfg);
        return this.#dispatcher;
      },
    });
  }

  /** Builds a client from `GROW_SIGNOZ_*` (and vendor) environment variables. */
  static fromEnv(overrides: SignozConfigOptions = {}): SignozClient {
    return new SignozClient(SignozConfig.fromEnv(overrides));
  }

  /** Tracing-category query client (same object as {@link query}). */
  get traces(): SignozQueryClient {
    return this.query;
  }

  /** The shared HTTP transport (for service modules). @internal */
  get transport(): HttpTransport {
    return this.#transport;
  }

  /**
   * Unauthenticated liveness: `GET /api/v1/health?live=1` (also checks ClickHouse). Never throws
   * for an unhealthy or unreachable service (only `CANCELLED` and `CONFIG`).
   */
  health(options: CallOptions = {}): Promise<HealthStatus> {
    return probeHealth(async () => {
      const res = await this.#api.get<Record<string, unknown> | undefined>("/api/v1/health", {
        query: { live: 1 },
        operation: "health",
        envelope: false,
        auth: false,
        ...(options.signal === undefined ? {} : { signal: options.signal }),
      });
      const { status, ...rest } = res.data ?? {};
      const details = Object.fromEntries(
        Object.entries(rest).map(([k, v]) => [k, typeof v === "string" ? v : JSON.stringify(v)]),
      );
      return healthStatus(typeof status === "string" ? status : "ok", details);
    });
  }

  /**
   * Authenticated round trip (`GET /api/v1/fields/keys?signal=logs&limit=1` with
   * `SIGNOZ-API-KEY`). Returns the latency in milliseconds; `AuthenticationError` on a bad key.
   */
  async ping(options: CallOptions = {}): Promise<number> {
    const start = performance.now();
    await this.#api.get("/api/v1/fields/keys", {
      query: { signal: "logs", limit: 1 },
      operation: "ping",
      ...(options.signal === undefined ? {} : { signal: options.signal }),
    });
    return performance.now() - start;
  }

  /**
   * Server version from `GET /api/v1/version` (leading `v` stripped). The official community image
   * reports an empty version → `UnsupportedError`.
   */
  async serverVersion(options: CallOptions = {}): Promise<string> {
    const res = await this.#api.call<{ version?: unknown } | undefined>("/api/v1/version", {
      operation: "serverVersion",
      envelope: false,
      auth: false,
      nullOn404: true,
      ...(options.signal === undefined ? {} : { signal: options.signal }),
    });
    const version = res?.data?.version;
    if (typeof version !== "string" || version.trim() === "") {
      throw new UnsupportedError("serverVersion: the server does not report its version");
    }
    return version.trim().replace(/^v/, "");
  }

  /**
   * Creates an OTLP log client for the collector (see {@link createLogClient}); it is flushed and
   * closed by {@link close}.
   */
  logClient(options: SignozLogClientOptions = {}): BatchingLogClient {
    const client = createLogClient(this.config, options);
    this.#logClients.add(client);
    return client;
  }

  /**
   * Sets up tracing to the collector with this client's config (see {@link initTracing}); the
   * handle is shut down by {@link close}.
   */
  initTracing(config: TracingConfigInput, options: InitTracingOptions = {}): TracingHandle {
    const handle = initTracing({ signoz: this.config, ...config }, options);
    this.#tracing.add(handle);
    return handle;
  }

  /** Flushes and closes log clients and tracing handles, then releases connections. Idempotent. */
  async close(): Promise<void> {
    if (this.#closed) return;
    this.#closed = true;
    const pending = [...this.#logClients].map((c) => c.close());
    pending.push(...[...this.#tracing].map((h) => h.shutdown()));
    await Promise.allSettled(pending);
    await this.#transport.close();
    const dispatcher = this.#dispatcher;
    this.#dispatcher = undefined;
    if (dispatcher !== undefined && this.#internalDispatcher === undefined) {
      await dispatcher.close().catch(() => undefined);
    }
  }
}

/** {@link SignozTracingConfig} without the `signoz` field (the client supplies it). */
export type TracingConfigInput = Omit<SignozTracingConfig, "signoz">;
