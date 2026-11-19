import { afterEach, describe, expect, it } from "vitest";
import {
  FilterBuilder,
  SignozClient,
  ValidationError,
  logRowToRecord,
  resolveWindow,
  rfc3339ToNanos,
  spanRowToSpan,
  waterfallToTrace,
} from "../src/index.js";
import type { MockServer } from "./helpers/server.js";
import { API_KEY, fixture, querySpec, startSignoz, type Route } from "./signoz-mock.js";

const servers: MockServer[] = [];
const clients: SignozClient[] = [];
afterEach(async () => {
  await Promise.all(clients.splice(0).map((c) => c.close()));
  await Promise.all(servers.splice(0).map((s) => s.close()));
});

const NOW = Date.UTC(2026, 10, 18, 20, 30);
const TRACE_ID = "fe67ae4bfe67ae4bfe67ae4bfe67ae4b";

async function setup(...routes: Route[]): Promise<{ server: MockServer; client: SignozClient }> {
  const server = await startSignoz(...routes);
  servers.push(server);
  const client = new SignozClient(
    { url: server.url, apiKey: API_KEY, retry: { maxRetries: 1, initialBackoffMs: 1 } },
    { now: () => NOW },
  );
  clients.push(client);
  return { server, client };
}

function logRow(i: number, ms: number, id: string): unknown {
  return {
    timestamp: new Date(ms).toISOString(),
    data: { id, body: `row ${i}`, severity_number: 9, severity_text: "INFO", timestamp: ms * 1e6 },
  };
}

function rawLogs(rows: unknown[]): object {
  return {
    status: "success",
    data: { type: "raw", data: { results: [{ queryName: "A", nextCursor: "x", rows }] } },
  };
}

describe("queryLogs", () => {
  it("sends a v5 raw query and maps rows to records", async () => {
    const { server, client } = await setup();
    const result = await client.query.queryLogs({
      filter: new FilterBuilder().eq("run.id", "fe67ae4b"),
      limit: 10,
      selectFields: ["body"],
    });
    const q = querySpec(server.requests[0]!);
    expect(q.requestType).toBe("raw");
    expect(q.end).toBe(NOW);
    expect(q.start).toBe(NOW - 3_600_000);
    expect(q.spec).toMatchObject({
      name: "A",
      signal: "logs",
      filter: { expression: "run.id = 'fe67ae4b'" },
      limit: 10,
      selectFields: [{ name: "body" }],
      order: [
        { key: { name: "timestamp" }, direction: "desc" },
        { key: { name: "id" }, direction: "desc" },
      ],
    });
    expect(server.requests[0]!.headers["signoz-api-key"]).toBe(API_KEY);
    expect(result.records).toHaveLength(6);
    expect(result.nextCursor).toBeUndefined();
    const first = result.records[0]!;
    expect(first).toMatchObject({
      id: "0mUr6HvHZ86gIh7CSEuDpOA7FHO",
      timeUnixNano: "1795033260005000000",
      severity: 9,
      severityText: "INFO",
      body: "marker-fe67ae4b seq 5 ünïcode it's",
      attributes: { ok: true, seq: 5, "run.id": "fe67ae4b" },
      resource: { "deployment.environment": "test", "service.name": "sdk-fe67ae4b" },
      traceId: TRACE_ID,
      spanId: "00f067aa0ba902b7",
      traceFlags: 1,
      scopeName: "grow-sdk-verify",
    });
    expect(first.timestamp.toISOString()).toBe("2026-11-18T20:21:00.005Z");
  });

  it("returns empty results (rows: null) and surfaces warnings through the logger", async () => {
    const warnings: string[] = [];
    const server = await startSignoz((req) =>
      req.url === "/api/v5/query_range"
        ? {
            body: fixture(
              querySpec(req).spec.filter
                ? "queryLogs.unknownFieldWarning.json"
                : "queryLogs.empty.json",
            ),
          }
        : undefined,
    );
    servers.push(server);
    const client = new SignozClient({
      url: server.url,
      apiKey: API_KEY,
      logger: {
        debug() {},
        info() {},
        warn: (m: string) => {
          warnings.push(m);
          throw new Error("logger failures are ignored");
        },
        error() {},
      },
    });
    clients.push(client);
    expect(await client.query.queryLogs()).toEqual({ records: [], warnings: [] });
    const warned = await client.query.queryLogs({ filter: "nosuchfield_fe67ae4b = 'x'" });
    expect(warned.records).toEqual([]);
    expect(warned.warnings[0]).toContain("not found in metadata");
    expect(warnings[0]).toContain("queryLogs: key `nosuchfield_fe67ae4b`");
  });

  it("maps a malformed filter to ValidationError and a bad key to AuthenticationError", async () => {
    const { client, server } = await setup((req) =>
      req.url === "/api/v5/query_range" && req.body.includes("body = (")
        ? { status: 400, body: fixture("queryLogs.invalidFilter.json") }
        : undefined,
    );
    const err = await client.query.queryLogs({ filter: "body = (" }).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ValidationError);
    expect((err as Error).message).toContain("Found 2 syntax errors");
    expect(server.requests).toHaveLength(1);
    const bad = new SignozClient({ url: server.url, apiKey: "wrong-key-123" });
    clients.push(bad);
    await expect(bad.query.queryLogs()).rejects.toMatchObject({ code: "AUTHENTICATION" });
  });

  it("retries 5xx (read-only POST queries are idempotent)", async () => {
    const { client, server } = await setup((req, i) =>
      req.url === "/api/v5/query_range" && i === 0
        ? { status: 503, body: { status: "error" } }
        : undefined,
    );
    expect((await client.query.queryLogs()).records).toHaveLength(6);
    expect(server.requests).toHaveLength(2);
  });

  it("pages with the SDK keyset cursor without losing rows in the boundary millisecond", async () => {
    // rows 5..1 desc; rows 3 and 2 share a millisecond
    const base = NOW - 60_000;
    const all = [
      { i: 5, ms: base + 4, id: "e" },
      { i: 4, ms: base + 3, id: "d" },
      { i: 3, ms: base + 2, id: "c2" },
      { i: 2, ms: base + 2, id: "c1" },
      { i: 1, ms: base + 1, id: "a" },
    ];
    const { client, server } = await setup((req) => {
      if (req.url !== "/api/v5/query_range") return undefined;
      const q = querySpec(req);
      const expr = (q.spec.filter as { expression?: string } | undefined)?.expression ?? "";
      const excluded = [...expr.matchAll(/'([^']+)'/g)].map((m) => m[1]);
      const asc = (q.spec.order as { direction: string }[])[0]!.direction === "asc";
      const rows = all
        .filter((r) => r.ms >= q.start && r.ms < q.end && !excluded.includes(r.id))
        .sort((a, b) =>
          asc ? a.ms - b.ms || (a.id < b.id ? -1 : 1) : b.ms - a.ms || (a.id < b.id ? 1 : -1),
        )
        .slice(0, Number(q.spec.limit))
        .map((r) => logRow(r.i, r.ms, r.id));
      return { body: rawLogs(rows) };
    });
    const seen: string[] = [];
    let cursor: string | undefined;
    for (let page = 0; page < 10; page++) {
      const res = await client.query.queryLogs({
        filter: "run = 'x'",
        limit: 2,
        ...(cursor === undefined ? {} : { cursor }),
      });
      seen.push(...res.records.map((r) => r.body));
      cursor = res.nextCursor;
      if (cursor === undefined) break;
    }
    expect(seen).toEqual(["row 5", "row 4", "row 3", "row 2", "row 1"]);
    const second = querySpec(server.requests[1]!);
    expect(second.end).toBe(base + 3 + 1);
    expect((second.spec.filter as { expression: string }).expression).toBe(
      "(run = 'x') AND (id NOT IN ('d'))",
    );

    const asc: string[] = [];
    cursor = undefined;
    for (let page = 0; page < 10; page++) {
      const res = await client.query.queryLogs({
        limit: 3,
        orderBy: "asc",
        ...(cursor === undefined ? {} : { cursor }),
      });
      asc.push(...res.records.map((r) => r.body));
      cursor = res.nextCursor;
      if (cursor === undefined) break;
    }
    expect(asc).toEqual(["row 1", "row 2", "row 3", "row 4", "row 5"]);

    // a boundary millisecond spanning several pages accumulates excluded ids
    const tie = [1, 2, 3].map((i) => ({ i, ms: base + 9, id: `t${i}` }));
    all.splice(0, all.length, ...tie);
    const ties: string[] = [];
    cursor = undefined;
    for (let page = 0; page < 10; page++) {
      const res = await client.query.queryLogs({
        limit: 1,
        ...(cursor === undefined ? {} : { cursor }),
      });
      ties.push(...res.records.map((r) => r.body));
      cursor = res.nextCursor;
      if (cursor === undefined) break;
    }
    expect(ties).toEqual(["row 3", "row 2", "row 1"]);
  });

  it("validates options and cursors", async () => {
    const { client } = await setup();
    await expect(client.query.queryLogs({ limit: 0 })).rejects.toBeInstanceOf(ValidationError);
    await expect(client.query.queryLogs({ limit: 10_001 })).rejects.toBeInstanceOf(ValidationError);
    await expect(client.query.queryLogs({ orderBy: "up" as never })).rejects.toBeInstanceOf(
      ValidationError,
    );
    await expect(client.query.queryLogs({ cursor: "garbage" })).rejects.toThrow("invalid cursor");
    const wrongShape = Buffer.from(JSON.stringify({ v: 2 })).toString("base64url");
    await expect(client.query.queryLogs({ cursor: wrongShape })).rejects.toThrow("invalid cursor");
    await expect(
      client.query.queryLogs({ start: new Date(NOW), end: new Date(NOW - 1) }),
    ).rejects.toBeInstanceOf(ValidationError);
    expect(() => resolveWindow({ start: new Date(Number.NaN) })).toThrow(ValidationError);
    expect(resolveWindow({ end: new Date(1_000_000_000) })).toEqual({
      start: 1_000_000_000 - 3_600_000,
      end: 1_000_000_000,
    });
  });
});

describe("traces", () => {
  it("queryTraces sends a raw traces query and maps spans", async () => {
    const { client, server } = await setup();
    const res = await client.query.queryTraces({
      service: "sdk-fe67ae4b",
      operation: "db.query",
      filter: "has_error = true",
      limit: 5,
      offset: 10,
      orderBy: "asc",
    });
    const q = querySpec(server.requests[0]!);
    expect(q.spec).toMatchObject({
      signal: "traces",
      limit: 5,
      offset: 10,
      filter: {
        expression: "(service.name = 'sdk-fe67ae4b' AND name = 'db.query') AND (has_error = true)",
      },
      order: [{ key: { name: "timestamp" }, direction: "asc" }],
    });
    expect(res.spans).toHaveLength(1);
    expect(res.spans[0]).toEqual({
      traceId: TRACE_ID,
      spanId: "1111111111111111",
      parentSpanId: "00f067aa0ba902b7",
      name: "db.query",
      kind: "client",
      serviceName: "sdk-fe67ae4b",
      startMicros: Date.UTC(2026, 10, 18, 20, 21, 0, 20) * 1000,
      endMicros: Date.UTC(2026, 10, 18, 20, 21, 0, 20) * 1000 + 100_000,
      durationMicros: 100_000,
      status: { code: "error", message: "boom" },
      attributes: { "db.system": "postgresql" },
      events: [
        {
          name: "exception",
          timeMicros: 1795033260020000,
          attributes: { "exception.message": "boom" },
        },
      ],
      links: [
        { traceId: "0af7651916cd43dd8448eb211c80319c", spanId: "b7ad6b7169203331", attributes: {} },
      ],
    });
    await expect(client.query.queryTraces({ offset: -1 })).rejects.toBeInstanceOf(ValidationError);
    await client.query.queryTraces();
    expect(querySpec(server.requests[1]!).spec.filter).toBeUndefined();
  });

  it("getTrace maps the waterfall and returns null for unknown traces", async () => {
    const { client, server } = await setup();
    const trace = await client.traces.getTrace(TRACE_ID.toUpperCase());
    expect(server.requests[0]).toMatchObject({
      method: "POST",
      url: `/api/v4/traces/${TRACE_ID}/waterfall`,
      body: "{}",
    });
    expect(trace).not.toBeNull();
    expect(trace!.complete).toBe(true);
    expect(trace!.totalSpans).toBe(3);
    expect(trace!.rootServiceName).toBe("sdk-fe67ae4b");
    expect(trace!.rootName).toBe("GET /verify");
    expect(trace!.spans.map((s) => s.name)).toEqual(["GET /verify", "db.query", "SELECT users"]);
    const [root, db, select] = trace!.spans;
    expect(root).toMatchObject({
      kind: "server",
      status: { code: "ok" },
      startMicros: 1795033260000000,
      durationMicros: 150000,
    });
    expect(root!.parentSpanId).toBeUndefined();
    expect(db).toMatchObject({
      parentSpanId: "00f067aa0ba902b7",
      status: { code: "error", message: "boom" },
      events: [
        {
          name: "exception",
          timeMicros: 1795033260020000,
          attributes: { "exception.message": "boom" },
        },
      ],
      links: [
        { traceId: "0af7651916cd43dd8448eb211c80319c", spanId: "b7ad6b7169203331", attributes: {} },
      ],
    });
    expect(select).toMatchObject({
      serviceName: "sdk-db-fe67ae4b",
      status: { code: "unset" },
      endMicros: 1795033260021000 + 98000,
    });
    expect(await client.traces.getTrace("0123456789abcdef0123456789abcdef")).toBeNull();
    await expect(client.traces.getTrace("not-hex")).rejects.toBeInstanceOf(ValidationError);
  });

  it("waitForTrace polls until the trace exists", async () => {
    const { client } = await setup((req, i) =>
      req.url.startsWith("/api/v4/traces/") && i < 1
        ? { status: 404, body: fixture("getTrace.notFound.json") }
        : undefined,
    );
    const trace = await client.traces.waitForTrace(TRACE_ID, {
      minSpans: 3,
      intervalMs: 5,
      timeoutMs: 5000,
    });
    expect(trace.spans).toHaveLength(3);
  });

  it("searchTraces uses trace mode and fills start/spanCount from the waterfall", async () => {
    const { client, server } = await setup();
    const hits = await client.traces.searchTraces({
      service: "sdk-fe67ae4b",
      operation: "GET /verify",
      tags: { "http.method": "GET" },
      minDurationMicros: 100,
      maxDurationMicros: 200_000,
      filter: "has_error = false",
      start: new Date(NOW - 60_000),
      end: new Date(NOW),
    });
    const q = querySpec(server.requests[0]!);
    expect(q.requestType).toBe("trace");
    expect(q.start).toBe(NOW - 60_000);
    expect(q.spec.limit).toBe(20);
    expect((q.spec.filter as { expression: string }).expression).toBe(
      "(service.name = 'sdk-fe67ae4b' AND name = 'GET /verify' AND http.method = 'GET' AND duration_nano >= 100000 AND duration_nano <= 200000000) AND (has_error = false)",
    );
    expect(hits).toEqual([
      {
        traceId: TRACE_ID,
        rootServiceName: "sdk-fe67ae4b",
        rootName: "GET /verify",
        startMicros: 1795033260000000,
        durationMicros: 150000,
        spanCount: 3,
      },
    ]);
  });

  it("searchTraces keeps the trace-mode values when the waterfall is gone", async () => {
    const { client } = await setup(
      (req) =>
        req.url.startsWith("/api/v4/traces/")
          ? { status: 404, body: fixture("getTrace.notFound.json") }
          : undefined,
      (req) =>
        req.url === "/api/v5/query_range"
          ? {
              body: {
                status: "success",
                data: {
                  data: {
                    results: [
                      { rows: [{ data: { trace_id: TRACE_ID, span_count: 2 } }, { data: {} }] },
                    ],
                  },
                },
              },
            }
          : undefined,
    );
    expect(await client.traces.searchTraces({})).toEqual([
      { traceId: TRACE_ID, startMicros: 0, durationMicros: 0, spanCount: 2 },
    ]);
  });

  it("listServices and listOperations use v5 aggregations", async () => {
    const { client, server } = await setup();
    expect(await client.traces.listServices()).toEqual([
      "sdk-db-fe67ae4b",
      "sdk-fe67ae4b",
      "tg-grpc-fe67ae4b",
      "tg-httppb-fe67ae4b",
    ]);
    const q = querySpec(server.requests[0]!);
    expect(q.requestType).toBe("scalar");
    expect(q.spec).toMatchObject({
      signal: "traces",
      groupBy: [{ name: "service.name" }],
      aggregations: [{ expression: "count()" }],
    });
    expect(
      await client.traces.listOperations("sdk-fe67ae4b", { start: new Date(NOW - 1000) }),
    ).toEqual(["GET /verify", "db.query"]);
    const q2 = querySpec(server.requests[1]!);
    expect(q2.start).toBe(NOW - 1000);
    expect(q2.spec.filter).toEqual({ expression: "service.name = 'sdk-fe67ae4b'" });
    await expect(client.traces.listOperations(" ")).rejects.toBeInstanceOf(ValidationError);
  });

  it("getDependencies posts nanosecond strings to the legacy bare-array endpoint", async () => {
    const { client, server } = await setup();
    const deps = await client.traces.getDependencies({
      start: new Date(NOW - 1000),
      end: new Date(NOW),
    });
    expect(JSON.parse(server.requests[0]!.body)).toEqual({
      start: `${NOW - 1000}000000`,
      end: `${NOW}000000`,
      tags: [],
    });
    expect(deps).toEqual([
      { parent: "sdk-fe67ae4b", child: "postgresql", callCount: 1 },
      { parent: "sdk-fe67ae4b", child: "sdk-db-fe67ae4b", callCount: 1 },
    ]);
  });
});

describe("fields and aggregations", () => {
  it("listLogFields flattens the keys map", async () => {
    const { client, server } = await setup();
    const fields = await client.query.listLogFields({ search: "s", limit: 50 });
    expect(server.requests[0]!.url).toBe("/api/v1/fields/keys?signal=logs&searchText=s&limit=50");
    expect(fields[0]).toEqual({ name: "app", context: "attribute", dataType: "string" });
    expect(fields.find((f) => f.name === "severity_text")?.description).toContain("Log level");
    expect(fields.map((f) => f.name)).toContain("service.name");
  });

  it("listFieldValues merges typed value lists", async () => {
    const { client, server } = await setup((req) =>
      req.url.startsWith("/api/v1/fields/values?signal=traces")
        ? {
            body: {
              status: "success",
              data: {
                values: { stringValues: ["b", "a"], numberValues: [2], boolValues: [true, null] },
              },
            },
          }
        : undefined,
    );
    expect(await client.query.listFieldValues("x", { source: "traces", search: "q" })).toEqual([
      2,
      "a",
      "b",
      true,
    ]);
    expect(server.requests[0]!.url).toBe("/api/v1/fields/values?signal=traces&name=x&searchText=q");
  });

  it("aggregate maps __result_<i> columns back to aliases", async () => {
    const { client, server } = await setup();
    const res = await client.query
      .aggregate("traces")
      .filter(new FilterBuilder().eq("service.name", "sdk-fe67ae4b"))
      .count("calls")
      .p99("duration_nano", "p99")
      .groupBy({ name: "name", fieldContext: "span", fieldDataType: "string" })
      .orderBy("calls")
      .limit(10)
      .between(new Date(NOW - 1000), new Date(NOW))
      .run();
    expect(res.rows).toEqual([
      { groups: { name: "GET /verify" }, values: { calls: 1, p99: 150000000 } },
      { groups: { name: "db.query" }, values: { calls: 1, p99: 100000000 } },
    ]);
    const q = querySpec(server.requests[0]!);
    expect(q).toMatchObject({ start: NOW - 1000, end: NOW, requestType: "scalar" });
    expect(q.spec).toMatchObject({
      signal: "traces",
      filter: { expression: "service.name = 'sdk-fe67ae4b'" },
      aggregations: [
        { expression: "count()", alias: "calls" },
        { expression: "p99(duration_nano)", alias: "p99" },
      ],
      groupBy: [{ name: "name", fieldContext: "span", fieldDataType: "string" }],
      order: [{ key: { name: "calls" }, direction: "desc" }],
      limit: 10,
    });
    const count = await client.query.aggregate("logs").count().run();
    expect(count.rows).toEqual([{ groups: {}, values: { "count()": 12 } }]);
  });

  it("aggregate builds every expression and time series", async () => {
    const { client, server } = await setup();
    const builder = client.query
      .aggregate("logs")
      .countDistinct("service.name")
      .sum("seq")
      .avg("seq")
      .min("seq")
      .max("seq")
      .p50()
      .p90("duration_nano", "p90")
      .p95()
      .percentile(25, "seq")
      .expression("count()", "n")
      .filter("  run = 'x'  ");
    expect(builder.aliases()).toEqual([
      "count_distinct(service.name)",
      "sum(seq)",
      "avg(seq)",
      "min(seq)",
      "max(seq)",
      "p50(duration_nano)",
      "p90",
      "p95(duration_nano)",
      "p25(seq)",
      "n",
    ]);
    const series = await builder.timeSeries(60);
    expect(querySpec(server.requests[0]!).spec.stepInterval).toBe(60);
    expect(series).toEqual([
      {
        alias: "count_distinct(service.name)",
        labels: {},
        points: [{ timestamp: new Date(1795033260000), value: 12 }],
      },
    ]);
    const labelled = await setup((req) =>
      req.url === "/api/v5/query_range"
        ? {
            body: {
              status: "success",
              data: {
                data: {
                  results: [
                    {
                      aggregations: [
                        {
                          index: 1,
                          alias: "__result_1",
                          series: [
                            {
                              labels: [{ key: { name: "service.name" }, value: "a" }],
                              values: [
                                { timestamp: 1, value: "2.5" },
                                { timestamp: 2, value: null },
                              ],
                            },
                          ],
                        },
                      ],
                    },
                  ],
                },
              },
            },
          }
        : undefined,
    );
    const two = await labelled.client.query.aggregate("traces").count("a").count("b").timeSeries();
    expect(two).toEqual([
      {
        alias: "b",
        labels: { "service.name": "a" },
        points: [
          { timestamp: new Date(1), value: 2.5 },
          { timestamp: new Date(2), value: 0 },
        ],
      },
    ]);
  });

  it("aggregate validates its input", async () => {
    const { client } = await setup();
    const agg = client.query.aggregate("traces");
    expect(() => client.query.aggregate("metrics" as never)).toThrow(ValidationError);
    expect(() => agg.expression(" ")).toThrow(ValidationError);
    expect(() => agg.percentile(42)).toThrow(ValidationError);
    expect(() => agg.sum("bad field")).toThrow(ValidationError);
    expect(() => agg.groupBy("bad field")).toThrow(ValidationError);
    expect(() => agg.orderBy("x", "sideways" as never)).toThrow(ValidationError);
    expect(() => agg.limit(0)).toThrow(ValidationError);
    expect(() => agg.toRequest()).toThrow(ValidationError);
    agg.count();
    expect(() => agg.between(new Date(2), new Date(1)).toRequest()).toThrow(ValidationError);
    await expect(client.query.aggregate("traces").count().timeSeries(0)).rejects.toBeInstanceOf(
      ValidationError,
    );
  });

  it("queryRange accepts a custom body", async () => {
    const { client } = await setup();
    const res = await client.query.queryRange({
      start: NOW - 1,
      end: NOW,
      requestType: "scalar",
      compositeQuery: {
        queries: [
          {
            type: "builder_query",
            spec: { name: "A", signal: "logs", aggregations: [{ expression: "count()" }] },
          },
        ],
      },
    });
    expect(res.warnings).toEqual([]);
    expect(JSON.stringify(res.data)).toContain("__result_0");
  });
});

describe("mapping", () => {
  it("parses nanosecond timestamps exactly", () => {
    expect(rfc3339ToNanos("2026-11-18T20:07:04.581483281Z")).toBe(1795032424581483281n);
    expect(rfc3339ToNanos("2026-11-18T20:07:04Z")).toBe(1795032424000000000n);
    expect(() => rfc3339ToNanos("yesterday")).toThrow(ValidationError);
  });

  it("logRowToRecord handles fallbacks", () => {
    const fromData = logRowToRecord({
      data: {
        timestamp: "1795032424581483281",
        severity_text: "warning",
        body: { msg: "x" },
        trace_id: "",
        resources_string: {},
      },
    });
    expect(fromData).toMatchObject({
      id: "",
      timeUnixNano: "1795032424581483281",
      severity: 13,
      severityText: "warning",
      body: '{"msg":"x"}',
    });
    expect(fromData.traceId).toBeUndefined();
    const numeric = logRowToRecord({
      data: {
        timestamp: 1795032424000000000,
        severity_number: 0,
        trace_id: "00000000000000000000000000000000",
      },
    });
    expect(numeric).toMatchObject({ severity: 9, severityText: "INFO", body: '""' });
    expect(numeric.traceId).toBeUndefined();
    const traced = logRowToRecord({
      timestamp: "2026-11-18T20:07:04Z",
      data: { severity_number: "18", trace_id: TRACE_ID, span_id: "0000000000000000", body: "b" },
    });
    expect(traced).toMatchObject({ severity: 17, traceId: TRACE_ID });
    expect(traced.spanId).toBeUndefined();
    expect(traced.traceFlags).toBeUndefined();
    expect(() => logRowToRecord({ data: {} })).toThrow(ValidationError);
    expect(() => logRowToRecord(null)).toThrow(ValidationError);
  });

  it("span mapping covers kinds, statuses and missing values", () => {
    const s = spanRowToSpan({
      data: {
        kind: 4,
        status_code_string: "Ok",
        events: [{ timeUnixNano: "1000000" }],
        links: [{ traceId: "x" }],
        parent_span_id: "0000000000000000",
      },
    });
    expect(s).toMatchObject({
      kind: "producer",
      status: { code: "ok" },
      startMicros: 0,
      serviceName: "",
      events: [{ name: "", timeMicros: 1000, attributes: {} }],
      links: [],
    });
    expect(s.parentSpanId).toBeUndefined();
    expect(
      spanRowToSpan({
        data: {
          kind: 99,
          timestamp: "2026-11-18T20:07:04Z",
          "service.name": "svc",
          events: [{ timeUnixNano: "bad" }],
        },
      }),
    ).toMatchObject({
      kind: "unspecified",
      serviceName: "svc",
      events: [{ timeMicros: 0 }],
    });
    const trace = waterfallToTrace(TRACE_ID, {
      spans: [
        {
          kind_string: "Consumer",
          time_unix: "5",
          references: [{ refType: "FOLLOWS_FROM", traceId: "zz" }],
        },
      ],
      hasMore: true,
    });
    expect(trace).toMatchObject({
      complete: false,
      totalSpans: 1,
      spans: [{ kind: "consumer", startMicros: 5000, links: [] }],
    });
    expect(waterfallToTrace(TRACE_ID, null)).toEqual({
      traceId: TRACE_ID,
      spans: [],
      complete: true,
      totalSpans: 0,
    });
  });
});
