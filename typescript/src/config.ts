// Service-owned: generated once by sdk-kit, then maintained in this repository.

import {
  type CommonConfig,
  type CommonConfigOptions,
  type CommonVendorEnv,
  ConfigError,
  type EnvMap,
  EnvReader,
  type Hooks,
  type ClientLogger,
  REDACTED,
  type RetryPolicy,
  Secret,
  type TlsOptions,
  describeCommonConfig,
  mergeOptions,
  readCommonEnv,
  redactUrl,
  resolveCommonConfig,
  secretValues,
  validateCommonConfig,
} from "./core/index.js";

const INSPECT = Symbol.for("nodejs.util.inspect.custom");

/** Port text from the stack catalog; the default URL uses its first number. */
const CATALOG_PORT = "8080";

/** OTLP export protocol used for logs and traces sent to the SigNoz collector. */
export type OtlpProtocol = "http/protobuf" | "grpc";

/** Header carrying the query API key (`SIGNOZ-API-KEY`). */
export const API_KEY_HEADER = "SIGNOZ-API-KEY";
/** Header / gRPC metadata carrying the SigNoz Cloud ingestion key. */
export const INGESTION_KEY_HEADER = "signoz-ingestion-key";

/** Options accepted by {@link SignozConfig} (all optional). */
export interface SignozConfigOptions extends CommonConfigOptions {
  /**
   * Query API key, sent as `SIGNOZ-API-KEY` (env `GROW_SIGNOZ_API_KEY`, then `SIGNOZ_API_KEY`).
   * Required only for query calls and `ping()`; an ingest-only client does not need it.
   */
  apiKey?: string | Secret;
  /**
   * OTLP endpoint of the SigNoz collector (env `GROW_SIGNOZ_OTLP_ENDPOINT`, then
   * `SIGNOZ_OTLP_ENDPOINT`, then `OTEL_EXPORTER_OTLP_ENDPOINT`). Default
   * `http://127.0.0.1:4318` for `http/protobuf`, `http://127.0.0.1:4317` for `grpc`.
   */
  otlpEndpoint?: string;
  /**
   * OTLP protocol (env `GROW_SIGNOZ_OTLP_PROTOCOL`, then `OTEL_EXPORTER_OTLP_PROTOCOL`).
   * Default `http/protobuf`.
   */
  otlpProtocol?: OtlpProtocol;
  /**
   * SigNoz Cloud ingestion key, sent as the `signoz-ingestion-key` header / gRPC metadata on every
   * OTLP export (env `GROW_SIGNOZ_INGESTION_KEY`, then `SIGNOZ_INGESTION_KEY`). Self-hosted
   * collectors ignore it.
   */
  ingestionKey?: string | Secret;
}

/**
 * Vendor environment variables read after `GROW_SIGNOZ_*`, in order.
 */
export const VENDOR_ENV: CommonVendorEnv & {
  apiKey: readonly string[];
  otlpEndpoint: readonly string[];
  otlpProtocol: readonly string[];
  ingestionKey: readonly string[];
} = {
  url: ["SIGNOZ_URL"],
  apiKey: ["SIGNOZ_API_KEY"],
  otlpEndpoint: ["SIGNOZ_OTLP_ENDPOINT", "OTEL_EXPORTER_OTLP_ENDPOINT"],
  otlpProtocol: ["OTEL_EXPORTER_OTLP_PROTOCOL"],
  ingestionKey: ["SIGNOZ_INGESTION_KEY"],
};

const PROTOCOLS: readonly OtlpProtocol[] = ["http/protobuf", "grpc"];

/** Immutable configuration of {@link SignozClient}. */
export class SignozConfig implements CommonConfig {
  /** Default query API endpoint. */
  static readonly DEFAULT_URL = `http://127.0.0.1:${/\d+/.exec(CATALOG_PORT)?.[0] ?? "8080"}`;
  /** Default OTLP/HTTP endpoint of the collector. */
  static readonly DEFAULT_OTLP_HTTP_ENDPOINT = "http://127.0.0.1:4318";
  /** Default OTLP/gRPC endpoint of the collector. */
  static readonly DEFAULT_OTLP_GRPC_ENDPOINT = "http://127.0.0.1:4317";
  /** Prefix of every environment variable. */
  static readonly ENV_PREFIX = "GROW_SIGNOZ_";

  /** Query API base URL (UI + API, port 8080). */
  readonly url: string;
  readonly connectTimeoutMs: number;
  readonly requestTimeoutMs: number;
  readonly totalTimeoutMs: number;
  readonly retry: RetryPolicy;
  readonly tls: Readonly<TlsOptions>;
  readonly proxy?: string;
  readonly headers: Readonly<Record<string, string>>;
  readonly userAgentSuffix?: string;
  readonly hooks?: Hooks;
  readonly logger?: ClientLogger;
  /** Query API key (`SIGNOZ-API-KEY`). */
  readonly apiKey?: Secret;
  /** OTLP collector endpoint (default depends on {@link otlpProtocol}). */
  readonly otlpEndpoint: string;
  /** OTLP protocol. */
  readonly otlpProtocol: OtlpProtocol;
  /** SigNoz Cloud ingestion key. */
  readonly ingestionKey?: Secret;

  /** Applies defaults, validates (throws {@link ConfigError}) and freezes. */
  constructor(options: SignozConfigOptions = {}) {
    const common = resolveCommonConfig(options, SignozConfig.DEFAULT_URL);
    this.url = common.url;
    this.connectTimeoutMs = common.connectTimeoutMs;
    this.requestTimeoutMs = common.requestTimeoutMs;
    this.totalTimeoutMs = common.totalTimeoutMs;
    this.retry = common.retry;
    this.tls = common.tls;
    this.headers = common.headers;
    if (common.proxy !== undefined) this.proxy = common.proxy;
    if (common.userAgentSuffix !== undefined) this.userAgentSuffix = common.userAgentSuffix;
    if (common.hooks !== undefined) this.hooks = common.hooks;
    if (common.logger !== undefined) this.logger = common.logger;
    const apiKey = Secret.from(options.apiKey);
    if (apiKey !== undefined) this.apiKey = apiKey;
    const ingestionKey = Secret.from(options.ingestionKey);
    if (ingestionKey !== undefined) this.ingestionKey = ingestionKey;
    const protocol = (options.otlpProtocol ?? "http/protobuf").trim().toLowerCase();
    if (!PROTOCOLS.includes(protocol as OtlpProtocol)) {
      throw new ConfigError("invalid configuration: otlpProtocol must be http/protobuf or grpc");
    }
    this.otlpProtocol = protocol as OtlpProtocol;
    this.otlpEndpoint = (
      options.otlpEndpoint?.trim() ||
      (this.otlpProtocol === "grpc"
        ? SignozConfig.DEFAULT_OTLP_GRPC_ENDPOINT
        : SignozConfig.DEFAULT_OTLP_HTTP_ENDPOINT)
    ).replace(/\/+$/, "");
    this.validate();
    Object.freeze(this);
  }

  /** Returns `options` when it already is a config, otherwise builds one. */
  static from(options: SignozConfigOptions = {}): SignozConfig {
    if (options instanceof SignozConfig) return options;
    return new SignozConfig(options);
  }

  /**
   * Builds a config from the environment. Precedence: explicit `overrides` >
   * `GROW_SIGNOZ_<NAME>` > vendor variables ({@link VENDOR_ENV}) > defaults.
   */
  static fromEnv(overrides: SignozConfigOptions = {}, env: EnvMap = process.env): SignozConfig {
    const reader = new EnvReader(SignozConfig.ENV_PREFIX, env);
    const fromEnv: SignozConfigOptions = readCommonEnv(reader, VENDOR_ENV);
    const apiKey = reader.secret("API_KEY", VENDOR_ENV.apiKey);
    if (apiKey !== undefined) fromEnv.apiKey = apiKey;
    const ingestionKey = reader.secret("INGESTION_KEY", VENDOR_ENV.ingestionKey);
    if (ingestionKey !== undefined) fromEnv.ingestionKey = ingestionKey;
    const endpoint = reader.string("OTLP_ENDPOINT", VENDOR_ENV.otlpEndpoint);
    if (endpoint !== undefined) fromEnv.otlpEndpoint = endpoint;
    const protocol = reader.lookup("OTLP_PROTOCOL", VENDOR_ENV.otlpProtocol);
    if (protocol !== undefined) {
      const value = protocol.value.trim().toLowerCase();
      if (!PROTOCOLS.includes(value as OtlpProtocol)) {
        throw new ConfigError(
          `invalid value in environment variable ${protocol.name}: expected http/protobuf or grpc`,
        );
      }
      fromEnv.otlpProtocol = value as OtlpProtocol;
    }
    const merged = mergeOptions<SignozConfigOptions>(fromEnv, overrides);
    return new SignozConfig(merged);
  }

  /** Throws {@link ConfigError} when a setting is invalid. */
  validate(): void {
    validateCommonConfig(this);
    if (this.apiKey?.reveal().trim() === "")
      throw new ConfigError("invalid configuration: apiKey must not be blank");
    if (this.ingestionKey?.reveal().trim() === "")
      throw new ConfigError("invalid configuration: ingestionKey must not be blank");
    let endpoint: URL | undefined;
    try {
      endpoint = new URL(this.otlpEndpoint);
    } catch {
      endpoint = undefined;
    }
    if (
      endpoint === undefined ||
      (endpoint.protocol !== "http:" && endpoint.protocol !== "https:")
    ) {
      throw new ConfigError("invalid configuration: otlpEndpoint must be an http(s) URL");
    }
  }

  /** Credential values to redact from error messages and logs. */
  knownSecrets(): string[] {
    return secretValues(this.apiKey, this.ingestionKey);
  }

  /** Debug form with every credential redacted. */
  toJSON(): Record<string, unknown> {
    return {
      ...describeCommonConfig(this),
      apiKey: this.apiKey === undefined ? null : REDACTED,
      otlpEndpoint: redactUrl(this.otlpEndpoint),
      otlpProtocol: this.otlpProtocol,
      ingestionKey: this.ingestionKey === undefined ? null : REDACTED,
    };
  }

  toString(): string {
    return `SignozConfig(${JSON.stringify(this.toJSON())})`;
  }

  [INSPECT](): string {
    return this.toString();
  }
}
