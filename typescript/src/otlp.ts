// Service-owned: OTLP log exporter (HTTP/protobuf and gRPC) to the SigNoz collector.

import { readFileSync } from "node:fs";
import { gzipSync } from "node:zlib";
import {
  Client as GrpcClient,
  Metadata,
  credentials as grpcCredentials,
  status as GrpcStatus,
  type ChannelCredentials,
  type ServiceError,
} from "@grpc/grpc-js";
import { ProtobufLogsSerializer } from "@opentelemetry/otlp-transformer";
import {
  AuthenticationError,
  CancelledError,
  ConflictError,
  HttpTransport,
  LANGUAGE,
  NotFoundError,
  PACKAGE_KEY,
  PermissionDeniedError,
  RateLimitError,
  SDK_VERSION,
  SignozError,
  TimeoutError,
  type TransportOptions,
  UnavailableError,
  UnsupportedError,
  ValidationError,
} from "./core/index.js";
import { INGESTION_KEY_HEADER, SignozConfig, type OtlpProtocol } from "./config.js";
import type { ExportOptions, ExportResult, LogExporter, LogRecord } from "./logging/types.js";

type SerializerInput = Parameters<typeof ProtobufLogsSerializer.serializeRequest>[0];

/** gRPC method of the OTLP logs service. */
export const OTLP_LOGS_GRPC_METHOD = "/opentelemetry.proto.collector.logs.v1.LogsService/Export";

/** Options of {@link SignozLogExporter}. */
export interface SignozLogExporterOptions {
  /** Protocol (default: `config.otlpProtocol`). */
  protocol?: OtlpProtocol;
  /** Collector endpoint (default: `config.otlpEndpoint`); `/v1/logs` is appended for HTTP. */
  endpoint?: string;
  /** Extra headers / gRPC metadata for every export. */
  headers?: Record<string, string>;
  /** Instrumentation scope name of exported records. Default `@grow-codes/signoz`. */
  scopeName?: string;
  /** Test seams for the HTTP transport. @internal */
  internals?: Pick<TransportOptions, "dispatcher" | "sleep" | "now">;
}

function hrTime(date: Date): [number, number] {
  const ms = date.getTime();
  const seconds = Math.floor(ms / 1000);
  return [seconds, Math.round((ms - seconds * 1000) * 1_000_000)];
}

/** Converts records into the serializer's input, grouping identical resources. @internal */
export function toReadableLogs(records: readonly LogRecord[], scopeName: string): SerializerInput {
  const resources = new Map<string, { attributes: Record<string, unknown> }>();
  const scope = { name: scopeName, version: SDK_VERSION };
  return records.map((record) => {
    const key = JSON.stringify(record.resource);
    let resource = resources.get(key);
    if (resource === undefined) {
      resource = { attributes: { ...record.resource } };
      resources.set(key, resource);
    }
    const out: Record<string, unknown> = {
      hrTime: hrTime(record.timestamp),
      hrTimeObserved: hrTime(record.observedTimestamp ?? record.timestamp),
      severityNumber: record.severity,
      severityText: record.severityText,
      body: record.body,
      resource,
      instrumentationScope: scope,
      attributes: record.attributes,
      droppedAttributesCount: 0,
    };
    if (record.traceId !== undefined) {
      out.spanContext = {
        traceId: record.traceId,
        spanId: record.spanId ?? "0000000000000000",
        traceFlags: record.traceFlags ?? 0,
      };
    }
    return out;
  }) as unknown as SerializerInput;
}

/** Maps a gRPC status to the SPEC §2.1 error classes. @internal */
export function grpcError(error: ServiceError | Error, operation: string): SignozError {
  const code = (error as Partial<ServiceError>).code;
  const detail = (error as Partial<ServiceError>).details ?? error.message;
  const message = `${operation}: ${detail.slice(0, 300)} (gRPC ${code ?? "?"})`;
  switch (code) {
    case GrpcStatus.UNAUTHENTICATED:
      return new AuthenticationError(message);
    case GrpcStatus.PERMISSION_DENIED:
      return new PermissionDeniedError(message);
    case GrpcStatus.INVALID_ARGUMENT:
    case GrpcStatus.FAILED_PRECONDITION:
    case GrpcStatus.OUT_OF_RANGE:
      return new ValidationError(message);
    case GrpcStatus.NOT_FOUND:
      return new NotFoundError(message);
    case GrpcStatus.ALREADY_EXISTS:
    case GrpcStatus.ABORTED:
      return new ConflictError(message);
    case GrpcStatus.RESOURCE_EXHAUSTED:
      return new RateLimitError(message);
    case GrpcStatus.UNIMPLEMENTED:
      return new UnsupportedError(message);
    case GrpcStatus.DEADLINE_EXCEEDED:
      return new TimeoutError(message);
    case GrpcStatus.CANCELLED:
      return new CancelledError(message);
    default:
      return new UnavailableError(message);
  }
}

function readPem(path: string | undefined): Buffer | undefined {
  return path === undefined ? undefined : readFileSync(path);
}

/** Appends `/v1/<signal>` to an OTLP/HTTP base endpoint unless it already ends with it. */
export function otlpHttpUrl(endpoint: string, signal: "logs" | "traces"): string {
  // Strip trailing slashes with a loop, not a regex (no backtracking on long runs of "/").
  let end = endpoint.length;
  while (end > 0 && endpoint.charCodeAt(end - 1) === 47) end -= 1;
  const base = endpoint.slice(0, end);
  return base.endsWith(`/v1/${signal}`) ? base : `${base}/v1/${signal}`;
}

/**
 * Default endpoint for `protocol`: the configured endpoint when the protocols match, otherwise the
 * configured endpoint with the standard port swapped (4318 ↔ 4317).
 */
export function endpointFor(config: SignozConfig, protocol: OtlpProtocol): string {
  if (protocol === config.otlpProtocol) return config.otlpEndpoint;
  const url = new URL(config.otlpEndpoint);
  if (protocol === "grpc" && url.port === "4318") url.port = "4317";
  else if (protocol === "http/protobuf" && url.port === "4317") url.port = "4318";
  return stripTrailingSlashes(url.toString());
}

/** Headers for OTLP exports: config headers, extra headers and the Cloud ingestion key. */
export function otlpHeaders(
  config: SignozConfig,
  extra: Record<string, string> = {},
): Record<string, string> {
  const headers: Record<string, string> = { ...config.headers, ...extra };
  if (config.ingestionKey !== undefined)
    headers[INGESTION_KEY_HEADER] = config.ingestionKey.reveal();
  return headers;
}

/**
 * {@link LogExporter} sending OTLP logs to the SigNoz collector: HTTP/protobuf
 * (`POST {endpoint}/v1/logs`, optional gzip) or gRPC (`LogsService/Export`). Adds the
 * `signoz-ingestion-key` header for SigNoz Cloud when configured. Retries of failed batches are
 * done by the {@link BatchProcessor}.
 */
export class SignozLogExporter implements LogExporter {
  readonly protocol: OtlpProtocol;
  readonly endpoint: string;
  readonly #config: SignozConfig;
  readonly #headers: Record<string, string>;
  readonly #scopeName: string;
  readonly #transport: HttpTransport | undefined;
  #grpc: GrpcClient | undefined;
  #closed = false;

  constructor(config: SignozConfig, options: SignozLogExporterOptions = {}) {
    this.#config = config;
    this.protocol = options.protocol ?? config.otlpProtocol;
    this.endpoint = stripTrailingSlashes(options.endpoint ?? endpointFor(config, this.protocol));
    this.#headers = otlpHeaders(config, options.headers);
    this.#scopeName = options.scopeName ?? `@grow-codes/${PACKAGE_KEY}`;
    if (this.protocol === "http/protobuf") {
      const transportConfig = new SignozConfig({
        url: this.endpoint,
        connectTimeoutMs: config.connectTimeoutMs,
        requestTimeoutMs: config.requestTimeoutMs,
        totalTimeoutMs: config.totalTimeoutMs,
        retry: config.retry,
        tls: config.tls,
        ...(config.proxy === undefined ? {} : { proxy: config.proxy }),
        ...(config.userAgentSuffix === undefined
          ? {}
          : { userAgentSuffix: config.userAgentSuffix }),
        ...(config.hooks === undefined ? {} : { hooks: config.hooks }),
        ...(config.logger === undefined ? {} : { logger: config.logger }),
      });
      this.#transport = new HttpTransport({
        ...options.internals,
        config: transportConfig,
        knownSecrets: () => config.knownSecrets(),
      });
    }
  }

  async export(records: LogRecord[], opts: ExportOptions): Promise<ExportResult> {
    if (records.length === 0) return { accepted: 0, rejected: 0 };
    const bytes = ProtobufLogsSerializer.serializeRequest(toReadableLogs(records, this.#scopeName));
    if (bytes === undefined) throw new ValidationError("logging.export: could not encode records");
    const response =
      this.#transport === undefined
        ? await this.#exportGrpc(bytes, opts)
        : await this.#exportHttp(this.#transport, bytes, opts);
    let rejected = 0;
    let message: string | undefined;
    if (response.length > 0) {
      try {
        const decoded = ProtobufLogsSerializer.deserializeResponse(response);
        const count: unknown = decoded.partialSuccess?.rejectedLogRecords ?? 0;
        rejected = Number(count);
        message = decoded.partialSuccess?.errorMessage;
      } catch {
        rejected = 0;
      }
    }
    rejected = Math.min(Math.max(rejected, 0), records.length);
    const result: ExportResult = { accepted: records.length - rejected, rejected };
    if (rejected > 0) {
      result.error = new ValidationError(
        `logging.export: collector rejected ${rejected} records${message === undefined || message === "" ? "" : `: ${message.slice(0, 300)}`}`,
      );
    }
    return result;
  }

  async #exportHttp(
    transport: HttpTransport,
    bytes: Uint8Array,
    opts: ExportOptions,
  ): Promise<Uint8Array> {
    const headers: Record<string, string> = {
      ...this.#headers,
      "content-type": "application/x-protobuf",
      accept: "application/x-protobuf",
    };
    let body: Uint8Array = bytes;
    if (opts.gzip) {
      body = gzipSync(bytes);
      headers["content-encoding"] = "gzip";
    }
    const res = await transport.request<Uint8Array | undefined>({
      method: "POST",
      path: otlpHttpUrl(this.endpoint, "logs"),
      headers,
      body,
      operation: "logging.export",
      responseType: "bytes",
      ...(opts.signal === undefined ? {} : { signal: opts.signal }),
    });
    const ct = res.headers.get("content-type") ?? "";
    return res.data !== undefined && /protobuf/i.test(ct) ? res.data : new Uint8Array();
  }

  #grpcClient(): GrpcClient {
    if (this.#grpc !== undefined) return this.#grpc;
    const url = new URL(this.endpoint);
    const port = url.port !== "" ? url.port : url.protocol === "https:" ? "443" : "80";
    let creds: ChannelCredentials;
    if (url.protocol === "https:") {
      const tls = this.#config.tls;
      const ca = [
        tls.caCertPem === undefined ? undefined : Buffer.from(tls.caCertPem),
        readPem(tls.caCertPath),
      ].filter((v): v is Buffer => v !== undefined);
      creds = grpcCredentials.createSsl(
        ca.length > 0 ? Buffer.concat(ca) : null,
        readPem(tls.clientKeyPath) ?? null,
        readPem(tls.clientCertPath) ?? null,
        tls.insecureSkipVerify === true ? { checkServerIdentity: () => undefined } : {},
      );
    } else {
      creds = grpcCredentials.createInsecure();
    }
    const suffix = this.#config.userAgentSuffix;
    this.#grpc = new GrpcClient(`${url.hostname}:${port}`, creds, {
      "grpc.primary_user_agent":
        `grow-codes-${PACKAGE_KEY}/${SDK_VERSION} (${LANGUAGE})` +
        (suffix === undefined || suffix === "" ? "" : ` ${suffix}`),
    });
    return this.#grpc;
  }

  #exportGrpc(bytes: Uint8Array, opts: ExportOptions): Promise<Uint8Array> {
    const operation = "logging.export";
    if (this.#closed)
      return Promise.reject(
        new UnavailableError(`${operation}: exporter is shut down`, { retryable: false }),
      );
    if (opts.signal?.aborted === true)
      return Promise.reject(new CancelledError(`${operation}: cancelled`));
    const metadata = new Metadata();
    for (const [k, v] of Object.entries(this.#headers)) metadata.set(k.toLowerCase(), v);
    if (opts.gzip) metadata.set("grpc-internal-encoding-request", "gzip");
    const client = this.#grpcClient();
    return new Promise<Uint8Array>((resolve, reject) => {
      const call = client.makeUnaryRequest<Uint8Array, Buffer>(
        OTLP_LOGS_GRPC_METHOD,
        (value) => Buffer.from(value),
        (value) => value,
        bytes,
        metadata,
        { deadline: Date.now() + this.#config.requestTimeoutMs },
        (error, value) => {
          opts.signal?.removeEventListener("abort", onAbort);
          if (error !== null) {
            const mapped = grpcError(error, operation);
            mapped.message = mapped.message.replace(/\s+/g, " ");
            for (const secret of this.#config.knownSecrets()) {
              if (secret.length >= 4)
                mapped.message = mapped.message.split(secret).join("[REDACTED]");
            }
            reject(mapped);
          } else {
            resolve(value === undefined ? new Uint8Array() : new Uint8Array(value));
          }
        },
      );
      const onAbort = (): void => {
        call.cancel();
      };
      opts.signal?.addEventListener("abort", onAbort, { once: true });
    });
  }

  async shutdown(): Promise<void> {
    if (this.#closed) return;
    this.#closed = true;
    this.#grpc?.close();
    await this.#transport?.close();
  }
}

/** Removes trailing "/" characters with a loop (no regex backtracking on long runs of "/"). */
function stripTrailingSlashes(value: string): string {
  let end = value.length;
  while (end > 0 && value.charCodeAt(end - 1) === 47) end -= 1;
  return value.slice(0, end);
}
