import { createServer, type IncomingHttpHeaders, type Server } from "node:http";
import type { AddressInfo } from "node:net";
import { gunzipSync } from "node:zlib";
import {
  Server as GrpcServer,
  ServerCredentials,
  Metadata,
  status as GrpcStatus,
  type ServiceError,
} from "@grpc/grpc-js";
import { ProtobufLogsSerializer } from "@opentelemetry/otlp-transformer";
import { afterEach, describe, expect, it } from "vitest";
import {
  OTLP_LOGS_GRPC_METHOD,
  Severity,
  SignozClient,
  SignozConfig,
  SignozLogExporter,
  createLogClient,
  endpointFor,
  otlpHttpUrl,
  type LogRecord,
} from "../src/index.js";
import { grpcError, toReadableLogs } from "../src/otlp.js";

const cleanups: (() => Promise<void> | void)[] = [];
afterEach(async () => {
  for (const fn of cleanups.splice(0).reverse()) await fn();
});

interface Captured {
  url: string;
  headers: IncomingHttpHeaders;
  body: Buffer;
}

interface Reply {
  status?: number;
  contentType?: string;
  body?: Uint8Array | string;
}

async function otlpServer(
  reply: (index: number) => Reply = () => ({}),
): Promise<{ url: string; requests: Captured[] }> {
  const requests: Captured[] = [];
  const server: Server = createServer((req, res) => {
    const chunks: Buffer[] = [];
    req.on("data", (c: Buffer) => chunks.push(c));
    req.on("end", () => {
      requests.push({ url: req.url ?? "", headers: req.headers, body: Buffer.concat(chunks) });
      const r = reply(requests.length - 1);
      res.writeHead(r.status ?? 200, { "content-type": r.contentType ?? "application/x-protobuf" });
      res.end(r.body ?? new Uint8Array());
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

interface GrpcCall {
  body: Buffer;
  metadata: Metadata;
}

async function grpcServer(
  fail?: (index: number) => ServiceError | null,
): Promise<{ url: string; calls: GrpcCall[] }> {
  const calls: GrpcCall[] = [];
  const server = new GrpcServer();
  server.addService(
    {
      Export: {
        path: OTLP_LOGS_GRPC_METHOD,
        requestStream: false,
        responseStream: false,
        requestSerialize: (v: Buffer) => v,
        requestDeserialize: (v: Buffer) => v,
        responseSerialize: (v: Buffer) => v,
        responseDeserialize: (v: Buffer) => v,
      },
    },
    {
      Export: (
        call: { request: Buffer; metadata: Metadata },
        callback: (e: ServiceError | null, v?: Buffer) => void,
      ) => {
        calls.push({ body: call.request, metadata: call.metadata });
        const error = fail?.(calls.length - 1) ?? null;
        if (error !== null) callback(error);
        else callback(null, Buffer.alloc(0));
      },
    } as never,
  );
  const port = await new Promise<number>((resolve, reject) =>
    server.bindAsync("127.0.0.1:0", ServerCredentials.createInsecure(), (err, p) =>
      err ? reject(err) : resolve(p),
    ),
  );
  cleanups.push(() => new Promise<void>((resolve) => server.tryShutdown(() => resolve())));
  return { url: `http://127.0.0.1:${port}`, calls };
}

function record(body: string, extra: Partial<LogRecord> = {}): LogRecord {
  return {
    timestamp: new Date(1795033260005),
    severity: Severity.Error,
    severityText: "ERROR",
    body,
    attributes: { run: "r1", n: 2, ok: true, nested: { a: [1, "b"] } },
    resource: { "service.name": "svc-a" },
    ...extra,
  };
}

const fastBatch = { flushIntervalMs: 50, maxExportRetries: 1, retryBackoffMs: 1 };

describe("SignozLogExporter (HTTP/protobuf)", () => {
  it("posts protobuf to /v1/logs with gzip and the ingestion key", async () => {
    const otlp = await otlpServer();
    const cfg = new SignozConfig({
      otlpEndpoint: otlp.url,
      ingestionKey: "ingest-key-123",
      headers: { "x-tenant": "t1" },
    });
    const exporter = new SignozLogExporter(cfg);
    expect(exporter.protocol).toBe("http/protobuf");
    const res = await exporter.export(
      [
        record("hello ünïcode", {
          traceId: "4bf92f3577b34da6a3ce929d0e0e4736",
          spanId: "00f067aa0ba902b7",
          traceFlags: 1,
        }),
        record("second", { resource: { "service.name": "svc-b" }, observedTimestamp: new Date() }),
      ],
      { gzip: true },
    );
    expect(res).toEqual({ accepted: 2, rejected: 0 });
    const req = otlp.requests[0]!;
    expect(req.url).toBe("/v1/logs");
    expect(req.headers["content-type"]).toBe("application/x-protobuf");
    expect(req.headers["content-encoding"]).toBe("gzip");
    expect(req.headers["signoz-ingestion-key"]).toBe("ingest-key-123");
    expect(req.headers["x-tenant"]).toBe("t1");
    expect(req.headers["user-agent"]).toMatch(/^grow-codes-signoz\/.+ \(typescript\)$/);
    const raw = gunzipSync(req.body).toString("latin1");
    expect(raw).toContain(Buffer.from("hello ünïcode").toString("latin1"));
    expect(raw).toContain("svc-a");
    expect(raw).toContain("svc-b");
    expect(raw).toContain("@grow-codes/signoz");
    expect(await exporter.export([], { gzip: false })).toEqual({ accepted: 0, rejected: 0 });
    await exporter.shutdown();
    await exporter.shutdown();
  });

  it("reports partial success and maps HTTP errors", async () => {
    const partial = ProtobufLogsSerializer.serializeRequest(toReadableLogs([record("x")], "s"));
    expect(partial).toBeDefined();
    // ExportLogsServiceResponse{partial_success{rejected_log_records=1, error_message="bad"}}
    const response = Uint8Array.from([0x0a, 0x07, 0x08, 0x01, 0x12, 0x03, 0x62, 0x61, 0x64]);
    const otlp = await otlpServer((i) =>
      i === 0
        ? { body: response }
        : i === 1
          ? {
              status: 400,
              contentType: "application/json",
              body: '{"code":3,"message":"ID.UnmarshalJSONIter: length mismatch"}',
            }
          : i === 2
            ? { body: Uint8Array.from([0xff, 0xff]) }
            : { contentType: "application/json", body: "{}" },
    );
    const exporter = new SignozLogExporter(
      new SignozConfig({ otlpEndpoint: `${otlp.url}/v1/logs` }),
    );
    const res = await exporter.export([record("a"), record("b")], { gzip: false });
    expect(res).toMatchObject({ accepted: 1, rejected: 1 });
    expect(res.error?.message).toContain("rejected 1 records: bad");
    expect(otlp.requests[0]!.url).toBe("/v1/logs");
    expect(otlp.requests[0]!.headers["content-encoding"]).toBeUndefined();
    const err = await exporter.export([record("a")], { gzip: false }).catch((e: unknown) => e);
    expect(err).toMatchObject({ code: "VALIDATION", httpStatus: 400, retryable: false });
    expect(await exporter.export([record("a")], { gzip: false })).toEqual({
      accepted: 1,
      rejected: 0,
    });
    expect(await exporter.export([record("a")], { gzip: false })).toEqual({
      accepted: 1,
      rejected: 0,
    });
    await exporter.shutdown();
  });

  it("createLogClient batches records with resource defaults", async () => {
    const otlp = await otlpServer();
    const client = createLogClient(
      { otlpEndpoint: otlp.url },
      {
        serviceName: "checkout",
        serviceVersion: "1.2.3",
        environment: "test",
        headers: { "x-h": "v" },
        batch: { ...fastBatch, gzip: false },
      },
    );
    client.send({ message: "hi", attributes: { password: "hunter2" } });
    await client.flush();
    expect(client.stats()).toMatchObject({ sent: 1, failed: 0 });
    const raw = otlp.requests[0]!.body.toString("latin1");
    expect(raw).toContain("checkout");
    expect(raw).toContain("1.2.3");
    expect(raw).toContain("deployment.environment");
    expect(raw).not.toContain("hunter2");
    expect(otlp.requests[0]!.headers["x-h"]).toBe("v");
    await client.close();
    const anonymous = createLogClient(new SignozConfig({ otlpEndpoint: otlp.url }), {
      batch: { gzip: false },
    });
    expect(anonymous.resource).toEqual({ "service.name": "unknown_service" });
    await anonymous.close();
  });

  it("createLogClient defaults to SignozConfig.fromEnv()", async () => {
    const otlp = await otlpServer();
    process.env.GROW_SIGNOZ_OTLP_ENDPOINT = otlp.url;
    try {
      const client = createLogClient();
      client.send({ message: "from env" });
      await client.close();
      expect(otlp.requests).toHaveLength(1);
    } finally {
      delete process.env.GROW_SIGNOZ_OTLP_ENDPOINT;
    }
  });

  it("SignozClient.logClient clients are flushed by close()", async () => {
    const otlp = await otlpServer();
    const client = new SignozClient({ otlpEndpoint: otlp.url });
    const logs = client.logClient({ serviceName: "svc", batch: { flushIntervalMs: 60_000 } });
    logs.logger("app").error("closing flushes", { k: 1 });
    expect(otlp.requests).toHaveLength(0);
    await client.close();
    expect(otlp.requests).toHaveLength(1);
    expect(gunzipSync(otlp.requests[0]!.body).toString("latin1")).toContain("closing flushes");
  });
});

describe("SignozLogExporter (gRPC)", () => {
  it("exports through LogsService/Export with metadata", async () => {
    const grpc = await grpcServer();
    const cfg = new SignozConfig({
      otlpProtocol: "grpc",
      otlpEndpoint: grpc.url,
      ingestionKey: "ingest-key-456",
    });
    const client = createLogClient(cfg, { serviceName: "grpc-svc", batch: fastBatch });
    client.sendBatch([
      { message: "via grpc 1" },
      { message: "via grpc 2", severity: Severity.Warn },
    ]);
    await client.flush();
    expect(client.stats()).toMatchObject({ sent: 2, failed: 0 });
    expect(grpc.calls).toHaveLength(1);
    expect(grpc.calls[0]!.body.toString("latin1")).toContain("via grpc 2");
    expect(grpc.calls[0]!.metadata.get("signoz-ingestion-key")).toEqual(["ingest-key-456"]);
    await client.close();
  });

  it("maps gRPC errors and redacts secrets", async () => {
    const fail = (code: GrpcStatus, details: string): ServiceError =>
      Object.assign(new Error(details), {
        code,
        details,
        metadata: new Metadata(),
      }) as ServiceError;
    const grpc = await grpcServer((i) =>
      i === 0 ? fail(GrpcStatus.UNAUTHENTICATED, "bad key ingest-key-789") : null,
    );
    const exporter = new SignozLogExporter(
      new SignozConfig({ otlpEndpoint: grpc.url, ingestionKey: "ingest-key-789" }),
      {
        protocol: "grpc",
      },
    );
    const err = await exporter.export([record("a")], { gzip: false }).catch((e: unknown) => e);
    expect(err).toMatchObject({ code: "AUTHENTICATION" });
    expect((err as Error).message).not.toContain("ingest-key-789");
    expect(await exporter.export([record("b")], { gzip: true })).toEqual({
      accepted: 1,
      rejected: 0,
    });
    await expect(
      exporter.export([record("c")], { gzip: false, signal: AbortSignal.abort() }),
    ).rejects.toMatchObject({
      code: "CANCELLED",
    });
    await exporter.shutdown();
    await expect(exporter.export([record("d")], { gzip: false })).rejects.toMatchObject({
      code: "UNAVAILABLE",
    });
    await exporter.shutdown();
  });

  it("cancels an in-flight call", async () => {
    const exporter = new SignozLogExporter(
      new SignozConfig({ otlpProtocol: "grpc", otlpEndpoint: "http://10.255.255.1:4317" }),
    );
    const controller = new AbortController();
    const pending = exporter.export([record("a")], { gzip: false, signal: controller.signal });
    controller.abort();
    await expect(pending).rejects.toMatchObject({ code: "CANCELLED" });
    await exporter.shutdown();
  });

  it("builds TLS credentials for https endpoints", async () => {
    const exporter = new SignozLogExporter(
      new SignozConfig({
        otlpProtocol: "grpc",
        otlpEndpoint: "https://127.0.0.1:1",
        tls: { insecureSkipVerify: true, caCertPem: "x" },
      }),
    );
    const err = await exporter.export([record("a")], { gzip: false }).catch((e: unknown) => e);
    expect(err).toMatchObject({ code: "UNAVAILABLE" });
    await exporter.shutdown();
    const noPort = new SignozLogExporter(
      new SignozConfig({ otlpProtocol: "grpc", otlpEndpoint: "https://127.0.0.1" }),
    );
    expect(noPort.endpoint).toBe("https://127.0.0.1");
    await noPort.shutdown();
    const plain = new SignozLogExporter(
      new SignozConfig({ otlpProtocol: "grpc", otlpEndpoint: "http://127.0.0.1" }),
      {
        endpoint: "http://127.0.0.1",
      },
    );
    await expect(plain.export([record("a")], { gzip: false })).rejects.toHaveProperty("code");
    await plain.shutdown();
  }, 20_000);

  it("grpcError covers every status", () => {
    const cases: [number, string][] = [
      [GrpcStatus.PERMISSION_DENIED, "PERMISSION_DENIED"],
      [GrpcStatus.INVALID_ARGUMENT, "VALIDATION"],
      [GrpcStatus.FAILED_PRECONDITION, "VALIDATION"],
      [GrpcStatus.OUT_OF_RANGE, "VALIDATION"],
      [GrpcStatus.NOT_FOUND, "NOT_FOUND"],
      [GrpcStatus.ALREADY_EXISTS, "CONFLICT"],
      [GrpcStatus.ABORTED, "CONFLICT"],
      [GrpcStatus.RESOURCE_EXHAUSTED, "RATE_LIMITED"],
      [GrpcStatus.UNIMPLEMENTED, "UNSUPPORTED"],
      [GrpcStatus.DEADLINE_EXCEEDED, "TIMEOUT"],
      [GrpcStatus.CANCELLED, "CANCELLED"],
      [GrpcStatus.UNAVAILABLE, "UNAVAILABLE"],
    ];
    for (const [code, expected] of cases) {
      expect(grpcError(Object.assign(new Error("m"), { code }), "op").code).toBe(expected);
    }
    expect(grpcError(new Error("plain"), "op").message).toBe("op: plain (gRPC ?)");
  });
});

describe("endpoints", () => {
  it("swaps the standard OTLP ports between protocols", () => {
    const http = new SignozConfig({ otlpEndpoint: "http://collector:4318" });
    expect(endpointFor(http, "http/protobuf")).toBe("http://collector:4318");
    expect(endpointFor(http, "grpc")).toBe("http://collector:4317");
    const grpc = new SignozConfig({ otlpProtocol: "grpc", otlpEndpoint: "http://collector:4317" });
    expect(endpointFor(grpc, "http/protobuf")).toBe("http://collector:4318");
    expect(
      endpointFor(new SignozConfig({ otlpEndpoint: "https://ingest.eu.signoz.cloud" }), "grpc"),
    ).toBe("https://ingest.eu.signoz.cloud");
    expect(otlpHttpUrl("http://c:4318/", "logs")).toBe("http://c:4318/v1/logs");
    expect(otlpHttpUrl("http://c:4318/v1/traces", "traces")).toBe("http://c:4318/v1/traces");
  });
});
