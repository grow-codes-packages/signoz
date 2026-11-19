// Service-owned: initTracing — OpenTelemetry tracing exporting OTLP to the SigNoz collector.

import { readFileSync } from "node:fs";
import { Metadata, credentials as grpcCredentials } from "@grpc/grpc-js";
import { OTLPTraceExporter as OtlpGrpcExporter } from "@opentelemetry/exporter-trace-otlp-grpc";
import { OTLPTraceExporter as OtlpProtoExporter } from "@opentelemetry/exporter-trace-otlp-proto";
import { CompressionAlgorithm } from "@opentelemetry/otlp-exporter-base";
import type { SpanExporter } from "@opentelemetry/sdk-trace-base";
import { ConfigError, LANGUAGE, PACKAGE_KEY, SDK_VERSION, type TlsOptions } from "./core/index.js";
import { SignozConfig, type SignozConfigOptions } from "./config.js";
import { endpointFor, otlpHeaders, otlpHttpUrl } from "./otlp.js";
import type { ResolvedTracingConfig, TracingConfig } from "./tracing/config.js";
import { initTracingWith, type InitTracingOptions, type TracingHandle } from "./tracing/init.js";

/** {@link TracingConfig} plus the SigNoz connection settings. */
export interface SignozTracingConfig extends TracingConfig {
  /** SigNoz settings for endpoint, protocol and ingestion key (default: `SignozConfig.fromEnv()`). */
  signoz?: SignozConfig | SignozConfigOptions;
}

/**
 * Service defaults merged under the caller's {@link TracingConfig}: protocol and endpoint from the
 * config (`otlpProtocol`, `otlpEndpoint`) and the `signoz-ingestion-key` header when set.
 */
export function signozTracingDefaults(
  config: SignozConfig,
  protocol?: string,
): Partial<TracingConfig> {
  const p = protocol === "grpc" || protocol === "http/protobuf" ? protocol : config.otlpProtocol;
  return {
    protocol: p,
    endpoint: endpointFor(config, p),
    headers: otlpHeaders(config),
    tls: { ...config.tls },
  };
}

function readPem(path: string | undefined): string | undefined {
  return path === undefined ? undefined : readFileSync(path, "utf8");
}

function agentOptions(tls: Readonly<TlsOptions>): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  const ca = [tls.caCertPem, readPem(tls.caCertPath)].filter((v) => v !== undefined);
  if (ca.length > 0) out.ca = ca;
  const cert = readPem(tls.clientCertPath);
  if (cert !== undefined) out.cert = cert;
  const key = readPem(tls.clientKeyPath);
  if (key !== undefined) out.key = key;
  if (tls.insecureSkipVerify === true) out.rejectUnauthorized = false;
  return out;
}

/**
 * Builds the span exporter for a resolved config: `http/protobuf` → OTLP/HTTP protobuf to
 * `{endpoint}/v1/traces`, `grpc` → OTLP/gRPC. `native` is not supported by SigNoz (`ConfigError`).
 */
export function createSignozSpanExporter(resolved: ResolvedTracingConfig): SpanExporter {
  const compression =
    resolved.compression === "gzip" ? CompressionAlgorithm.GZIP : CompressionAlgorithm.NONE;
  const userAgent = `grow-codes-${PACKAGE_KEY}/${SDK_VERSION} (${LANGUAGE})`;
  const endpoint =
    resolved.endpoint ??
    (resolved.protocol === "grpc"
      ? SignozConfig.DEFAULT_OTLP_GRPC_ENDPOINT
      : SignozConfig.DEFAULT_OTLP_HTTP_ENDPOINT);
  if (resolved.protocol === "grpc") {
    const metadata = new Metadata();
    for (const [k, v] of Object.entries(resolved.headers)) metadata.set(k.toLowerCase(), v);
    const url = new URL(endpoint);
    const tls = resolved.tls;
    const options: ConstructorParameters<typeof OtlpGrpcExporter>[0] = {
      url: endpoint,
      metadata,
      compression,
      userAgent,
      timeoutMillis: resolved.batch.exportTimeoutMs,
    };
    if (
      url.protocol === "https:" &&
      (tls.caCertPath !== undefined ||
        tls.caCertPem !== undefined ||
        tls.clientCertPath !== undefined)
    ) {
      const ca = [tls.caCertPem, readPem(tls.caCertPath)]
        .filter((v): v is string => v !== undefined)
        .join("\n");
      const key = readPem(tls.clientKeyPath);
      const cert = readPem(tls.clientCertPath);
      options.credentials = grpcCredentials.createSsl(
        ca === "" ? null : Buffer.from(ca),
        key === undefined ? null : Buffer.from(key),
        cert === undefined ? null : Buffer.from(cert),
      );
    }
    return new OtlpGrpcExporter(options);
  }
  if (resolved.protocol === "native") {
    throw new ConfigError(
      "tracing: SigNoz has no native trace protocol; use http/protobuf or grpc",
    );
  }
  return new OtlpProtoExporter({
    url: otlpHttpUrl(endpoint, "traces"),
    headers: { ...resolved.headers },
    compression,
    userAgent,
    timeoutMillis: resolved.batch.exportTimeoutMs,
    httpAgentOptions: agentOptions(resolved.tls),
  });
}

/**
 * Sets up OpenTelemetry tracing that exports to the SigNoz collector (SPEC §5). Endpoint,
 * protocol and ingestion key come from `config.signoz` (default `SignozConfig.fromEnv()`); every
 * {@link TracingConfig} field overrides them. Throws `ConfigError` for invalid settings.
 */
export function initTracing(
  config: SignozTracingConfig,
  options: InitTracingOptions = {},
): TracingHandle {
  const { signoz, ...tracing } = config;
  const signozConfig = signoz === undefined ? SignozConfig.fromEnv() : SignozConfig.from(signoz);
  const defaults = {
    ...signozTracingDefaults(signozConfig, tracing.protocol),
    ...options.defaults,
  };
  return initTracingWith(tracing, createSignozSpanExporter, { ...options, defaults });
}
