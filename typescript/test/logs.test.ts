import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import type { AddressInfo } from "node:net";
import { afterEach, describe, expect, it } from "vitest";
import { SignozClient, type LogRecord } from "../src/index.js";
import type { MockServer } from "./helpers/server.js";
import { API_KEY, fixture, querySpec, startSignoz } from "./signoz-mock.js";

const cleanups: (() => Promise<void> | void)[] = [];
afterEach(async () => {
  for (const fn of cleanups.splice(0).reverse()) await fn();
});

const NOW = Date.UTC(2026, 10, 18, 20, 30);

async function setup(
  ...routes: Parameters<typeof startSignoz>
): Promise<{ server: MockServer; client: SignozClient }> {
  const server = await startSignoz(...routes);
  const client = new SignozClient(
    { url: server.url, apiKey: API_KEY, retry: { maxRetries: 0 } },
    { now: () => NOW },
  );
  cleanups.push(
    () => server.close(),
    () => client.close(),
  );
  return { server, client };
}

type SseHandler = (req: IncomingMessage, res: ServerResponse, index: number) => void;

async function sseServer(
  handler: SseHandler,
): Promise<{ url: string; requests: IncomingMessage[] }> {
  const requests: IncomingMessage[] = [];
  const server: Server = createServer((req, res) => {
    requests.push(req);
    handler(req, res, requests.length - 1);
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

function until(check: () => boolean, timeoutMs = 3000): Promise<void> {
  const start = Date.now();
  return new Promise((resolve, reject) => {
    const tick = (): void => {
      if (check()) resolve();
      else if (Date.now() - start > timeoutMs) reject(new Error("condition not met"));
      else setTimeout(tick, 10);
    };
    tick();
  });
}

function event(body: string, ms: number, id: string): string {
  return `data: ${JSON.stringify({ data: { id, body, severity_number: 9 }, timestamp: new Date(ms).toISOString() })}\n\n`;
}

describe("SignozLogQueryClient", () => {
  it("search maps to queryLogs and returns the cursor", async () => {
    const { client, server } = await setup((req) =>
      req.url === "/api/v5/query_range" && querySpec(req).spec.limit === 2
        ? {
            body: {
              status: "success",
              data: {
                data: {
                  results: [
                    {
                      rows: [
                        { timestamp: "2026-11-18T20:21:00.005Z", data: { id: "b", body: "x" } },
                        { timestamp: "2026-11-18T20:21:00.004Z", data: { id: "a", body: "y" } },
                      ],
                    },
                  ],
                },
              },
            },
          }
        : undefined,
    );
    const page = await client.logs.search("checkout failed", {
      start: new Date(NOW - 10),
      end: new Date(NOW),
      limit: 2,
      order: "asc",
      signal: new AbortController().signal,
    });
    expect(page.records.map((r) => r.body)).toEqual(["x", "y"]);
    expect(page.nextCursor).toBeTypeOf("string");
    const q = querySpec(server.requests[0]!);
    expect(q).toMatchObject({ start: NOW - 10, end: NOW });
    expect(q.spec).toMatchObject({ filter: { expression: "checkout failed" }, limit: 2 });
    const next = await client.logs.search("checkout failed", { cursor: page.nextCursor! });
    expect(next.records).toHaveLength(6);
    expect(next.nextCursor).toBeUndefined();
    expect((await client.logs.search("")).records).toHaveLength(6);
  });

  it("waitForLog polls search until a record matches", async () => {
    const { client } = await setup((req, i) =>
      req.url === "/api/v5/query_range" && i === 0
        ? { body: fixture("queryLogs.empty.json") }
        : undefined,
    );
    const rec = await client.logs.waitForLog("marker-fe67ae4b", {
      predicate: (r) => r.attributes.seq === 3,
      intervalMs: 5,
      timeoutMs: 5000,
    });
    expect(rec.body).toContain("seq 3");
  });

  it("listStreams returns service names and listLabels field names", async () => {
    const { client, server } = await setup();
    expect(await client.logs.listStreams()).toEqual([
      "lat-fe67ae4b",
      "sdk-fe67ae4b",
      "tg-grpc-fe67ae4b",
      "tg-httppb-fe67ae4b",
    ]);
    expect(server.requests[0]!.url).toBe("/api/v1/fields/values?signal=logs&name=service.name");
    const labels = await client.logs.listLabels();
    expect(labels).toContain("service.name");
    expect(labels).toContain("run.id");
    expect([...labels].sort()).toEqual(labels);
    expect(new Set(labels).size).toBe(labels.length);
  });

  it("tail streams SSE records, de-duplicates and reconnects from the last timestamp", async () => {
    const base = Date.now();
    const sse = await sseServer((req, res, index) => {
      res.writeHead(200, { "content-type": "text/event-stream" });
      if (index === 0) {
        res.write(": keep-alive\n\n");
        res.write(event("one", base + 10, "a"));
        res.write(event("two", base + 20, "b"));
        res.write(event("old", base - 60_000, "z"));
        res.write("event: error\ndata: clickhouse hiccup\n\n");
        res.write("data: not-json\n\n");
        res.write("data\n\n");
        res.end(event("three", base + 20, "c"));
      } else {
        res.write(event("two", base + 20, "b"));
        res.write(event("four", base + 30, "d").replace("\n\n", "\r\n\r\n"));
      }
    });
    const client = new SignozClient({ url: sse.url, apiKey: API_KEY, headers: { "x-extra": "1" } });
    cleanups.push(() => client.close());
    const got: LogRecord[] = [];
    const errors: unknown[] = [];
    const handle = client.logs.tail("body CONTAINS 'x'", (r) => got.push(r), {
      start: new Date(base),
      reconnectDelayMs: 5,
      onError: (e) => errors.push(e),
    });
    await until(() => got.length >= 4);
    handle.stop();
    handle.stop();
    expect(got.map((r) => r.body)).toEqual(["one", "two", "three", "four"]);
    const first = new URL(sse.requests[0]!.url!, sse.url);
    expect(first.pathname).toBe("/api/v3/logs/livetail");
    expect(first.searchParams.get("start")).toBe(String(base));
    expect(first.searchParams.get("filter")).toBe("body CONTAINS 'x'");
    expect(sse.requests[0]!.headers["signoz-api-key"]).toBe(API_KEY);
    expect(sse.requests[0]!.headers["x-extra"]).toBe("1");
    expect(sse.requests[0]!.headers.accept).toBe("text/event-stream");
    expect(new URL(sse.requests[1]!.url!, sse.url).searchParams.get("start")).toBe(
      String(base + 20),
    );
    expect(errors.map((e) => (e as Error).message)).toEqual(
      expect.arrayContaining([
        expect.stringContaining("clickhouse hiccup"),
        expect.stringContaining("JSON"),
      ]),
    );
  });

  it("tail reports a bad key once and stops", async () => {
    const sse = await sseServer((_req, res) => {
      res.writeHead(401, { "content-type": "application/json" });
      res.end(JSON.stringify(fixture("ping.unauthenticated.json")));
    });
    const client = new SignozClient({ url: sse.url, apiKey: "wrong-key-abc" });
    cleanups.push(() => client.close());
    const errors: unknown[] = [];
    client.logs.tail("", () => undefined, { reconnectDelayMs: 1, onError: (e) => errors.push(e) });
    await until(() => errors.length > 0);
    await new Promise((r) => setTimeout(r, 50));
    expect(errors).toHaveLength(1);
    expect(errors[0]).toMatchObject({ code: "AUTHENTICATION", httpStatus: 401 });
    expect(new URL(sse.requests[0]!.url!, sse.url).searchParams.has("filter")).toBe(false);
  });

  it("tail treats non-SSE answers as unsupported and retries 5xx", async () => {
    const html = await sseServer((_req, res) => {
      res.writeHead(200, { "content-type": "text/html" });
      res.end("<html></html>");
    });
    const c1 = new SignozClient({ url: html.url, apiKey: API_KEY });
    cleanups.push(() => c1.close());
    const errors: unknown[] = [];
    c1.logs.tail("x", () => undefined, { reconnectDelayMs: 1, onError: (e) => errors.push(e) });
    await until(() => errors.length > 0);
    expect(errors[0]).toMatchObject({ code: "UNSUPPORTED" });

    const flaky = await sseServer((_req, res, index) => {
      if (index === 0) {
        res.writeHead(502, { "content-type": "text/plain" });
        res.end("bad gateway");
      } else if (index === 1) {
        res.writeHead(500, { "content-type": "application/json" });
        res.end('{"error":{"message":"boom"}}');
      } else {
        res.writeHead(200, { "content-type": "text/event-stream" });
        res.end(event("late", Date.now() + 1000, "x"));
      }
    });
    const c2 = new SignozClient({ url: flaky.url, apiKey: API_KEY });
    cleanups.push(() => c2.close());
    const got: string[] = [];
    const errs: unknown[] = [];
    const handle = c2.logs.tail(
      "x",
      (r) => {
        got.push(r.body);
        throw new Error("callback errors are reported");
      },
      { intervalMs: 1, onError: (e) => errs.push(e) },
    );
    await until(() => got.length > 0);
    handle.stop();
    expect(errs.map((e) => (e as { code?: string }).code ?? (e as Error).message)).toEqual(
      expect.arrayContaining(["UNAVAILABLE", "callback errors are reported"]),
    );
    expect((errs[1] as Error).message).toContain("boom");
  });

  it("tail reports network errors and keeps retrying until stopped", async () => {
    const client = new SignozClient({ url: "http://127.0.0.1:1", apiKey: API_KEY });
    cleanups.push(() => client.close());
    const errors: unknown[] = [];
    const handle = client.logs.tail("x", () => undefined, {
      reconnectDelayMs: 1,
      onError: (e) => errors.push(e),
    });
    await until(() => errors.length >= 2);
    handle.stop();
    expect(errors[0]).toMatchObject({ code: "UNAVAILABLE" });
  });

  it("tail without an API key reports ConfigError", async () => {
    const client = new SignozClient({ url: "http://127.0.0.1:1" });
    cleanups.push(() => client.close());
    const errors: unknown[] = [];
    client.logs.tail("x", () => undefined, { onError: (e) => errors.push(e) });
    await until(() => errors.length > 0);
    expect(errors[0]).toMatchObject({ code: "CONFIG" });
  });
});
