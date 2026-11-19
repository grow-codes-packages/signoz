import { createServer, type IncomingHttpHeaders, type Server } from "node:http";
import type { AddressInfo } from "node:net";
import { afterEach, describe, expect, it } from "vitest";
import {
  ConfigError,
  SignozClient,
  SignozConfig,
  createSignozSpanExporter,
  initTracing,
  resolveTracingConfig,
  signozTracingDefaults,
} from "../src/index.js";

const cleanups: (() => Promise<void> | void)[] = [];
afterEach(async () => {
  for (const fn of cleanups.splice(0).reverse()) await fn();
});

async function collector(): Promise<{
  url: string;
  requests: { url: string; headers: IncomingHttpHeaders; size: number }[];
}> {
  const requests: { url: string; headers: IncomingHttpHeaders; size: number }[] = [];
  const server: Server = createServer((req, res) => {
    let size = 0;
    req.on("data", (c: Buffer) => (size += c.length));
    req.on("end", () => {
      requests.push({ url: req.url ?? "", headers: req.headers, size });
      res.writeHead(200, { "content-type": "application/x-protobuf" });
      res.end();
    });
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  cleanups.push(
    () =>
      new Promise<void>((resolve) => {
        server.closeAllConnections();
        server.close(() => resolve());
      }),
  );
  return { url: `http://127.0.0.1:${(server.address() as AddressInfo).port}`, requests };
}

describe("initTracing", () => {
  it("exports OTLP/HTTP protobuf to {endpoint}/v1/traces with the ingestion key", async () => {
    const c = await collector();
    const handle = initTracing({
      serviceName: "checkout",
      setGlobal: false,
      compression: "none",
      signoz: { otlpEndpoint: c.url, ingestionKey: "ingest-key-abc" },
    });
    expect(handle.config.protocol).toBe("http/protobuf");
    expect(String(handle.config)).not.toContain("ingest-key-abc");
    handle.tracer("t").startSpan("op").end();
    await handle.forceFlush(5000);
    await handle.shutdown();
    expect(c.requests[0]).toMatchObject({ url: "/v1/traces" });
    expect(c.requests[0]!.headers["content-type"]).toBe("application/x-protobuf");
    expect(c.requests[0]!.headers["signoz-ingestion-key"]).toBe("ingest-key-abc");
    expect(c.requests[0]!.size).toBeGreaterThan(0);
  });

  it("uses SignozConfig.fromEnv() by default", () => {
    process.env.GROW_SIGNOZ_OTLP_ENDPOINT = "http://env-collector:4318";
    try {
      const handle = initTracing({ serviceName: "env", setGlobal: false });
      expect(handle.config.endpoint).toBe("http://env-collector:4318");
      void handle.shutdown();
    } finally {
      delete process.env.GROW_SIGNOZ_OTLP_ENDPOINT;
    }
  });

  it("SignozClient.initTracing handles are shut down by close()", async () => {
    const c = await collector();
    const client = new SignozClient({ otlpEndpoint: c.url });
    const handle = client.initTracing({
      serviceName: "svc",
      setGlobal: false,
      batch: { scheduledDelayMs: 60_000 },
    });
    handle.tracer("t").startSpan("closing").end();
    expect(c.requests).toHaveLength(0);
    await client.close();
    expect(c.requests).toHaveLength(1);
  });

  it("grpc protocol uses the gRPC exporter and the swapped port", async () => {
    const cfg = new SignozConfig({ otlpEndpoint: "http://collector:4318", ingestionKey: "k-123" });
    expect(signozTracingDefaults(cfg, "grpc")).toMatchObject({
      protocol: "grpc",
      endpoint: "http://collector:4317",
    });
    expect(signozTracingDefaults(cfg, "native")).toMatchObject({
      protocol: "http/protobuf",
      endpoint: "http://collector:4318",
    });
    expect(signozTracingDefaults(cfg).headers).toEqual({ "signoz-ingestion-key": "k-123" });
    const handle = initTracing({
      serviceName: "g",
      protocol: "grpc",
      setGlobal: false,
      signoz: cfg,
    });
    expect(handle.config.endpoint).toBe("http://collector:4317");
    await handle.shutdown();
  });

  it("builds exporters for every TLS / protocol combination", async () => {
    const base = { serviceName: "x" };
    const grpcTls = createSignozSpanExporter(
      resolveTracingConfig({
        ...base,
        protocol: "grpc",
        endpoint: "https://ingest.eu.signoz.cloud:443",
        tls: { caCertPem: "-----BEGIN CERTIFICATE-----\n-----END CERTIFICATE-----" },
        headers: { a: "b" },
      }),
    );
    await grpcTls.shutdown();
    const grpcDefault = createSignozSpanExporter(
      resolveTracingConfig({ ...base, protocol: "grpc" }),
    );
    await grpcDefault.shutdown();
    const http = createSignozSpanExporter(
      resolveTracingConfig({ ...base, tls: { insecureSkipVerify: true, caCertPem: "x" } }),
    );
    await http.shutdown();
    expect(() =>
      createSignozSpanExporter(resolveTracingConfig({ ...base, protocol: "native" })),
    ).toThrow(ConfigError);
  });
});
