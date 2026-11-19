// Integration tests against the real SigNoz stack (docker/compose.yml): every public function of
// @grow-codes/signoz is called at least once with a round-trip assertion (send → query back).

import assert from "node:assert/strict";
import { after, before, test } from "node:test";
import { context, trace, SpanKind, SpanStatusCode } from "@opentelemetry/api";
import {
  FilterBuilder,
  Severity,
  SignozClient,
  SignozConfig,
  SignozError,
  SignozLogExporter,
  createLogClient,
  createSignozSpanExporter,
  initTracing,
  resolveTracingConfig,
  signozTracingDefaults,
  waitForLog,
  type LogRecord,
} from "@grow-codes/signoz";
import { loadBootstrapEnv, runId } from "./env.ts";

const run = `${runId
  .replace(/[^a-z0-9]/gi, "")
  .slice(0, 8)
  .toLowerCase()}${Math.random().toString(36).slice(2, 8)}`;
let client: SignozClient;
// Library timers are unref'd; keep the event loop alive while the suite polls the service.
const keepAlive = setInterval(() => undefined, 1000);

before(() => {
  loadBootstrapEnv();
  assert.ok(process.env.GROW_SIGNOZ_URL, "GROW_SIGNOZ_URL must be set");
  assert.ok(process.env.SIGNOZ_API_KEY, "SIGNOZ_API_KEY must come from /bootstrap/env");
  client = SignozClient.fromEnv();
});

after(async () => {
  await client.close();
  await client.close();
  clearInterval(keepAlive);
});

async function poll<T>(
  fn: () => Promise<T>,
  ok: (v: T) => boolean,
  timeoutMs = 60_000,
): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  let last: T;
  for (;;) {
    last = await fn();
    if (ok(last) || Date.now() > deadline) return last;
    await new Promise((r) => setTimeout(r, 1000));
  }
}

test("config reads the bootstrap environment and redacts the keys", () => {
  const cfg = SignozConfig.fromEnv();
  assert.equal(cfg.otlpEndpoint, process.env.SIGNOZ_OTLP_ENDPOINT);
  assert.ok(cfg.apiKey);
  assert.ok(!String(cfg).includes(cfg.apiKey.reveal()));
});

test("health reports a healthy service", async () => {
  const health = await client.health();
  assert.equal(health.healthy, true, JSON.stringify(health));
  assert.equal(health.status, "ok");
});

test("ping succeeds with the bootstrap key", async () => {
  const latency = await client.ping();
  assert.equal(typeof latency, "number");
  assert.ok(latency >= 0);
});

test("ping with a wrong key raises AUTHENTICATION and never leaks it", async () => {
  const wrong = "wrong-key-for-integration";
  const bad = SignozClient.fromEnv({ apiKey: wrong });
  try {
    await assert.rejects(bad.ping(), (err: unknown) => {
      assert.ok(err instanceof SignozError);
      assert.equal(err.code, "AUTHENTICATION");
      assert.equal(err.httpStatus, 401);
      assert.ok(!err.message.includes(wrong));
      return true;
    });
  } finally {
    await bad.close();
  }
});

test("serverVersion: UNSUPPORTED on the community image (empty version) or a version string", async () => {
  try {
    assert.match(await client.serverVersion(), /\S/);
  } catch (err) {
    assert.ok(err instanceof SignozError);
    assert.equal(err.code, "UNSUPPORTED");
  }
});

test("logs over OTLP/HTTP protobuf and gRPC round-trip through queryLogs", async () => {
  for (const protocol of ["http/protobuf", "grpc"] as const) {
    const service = `ts-logs-${protocol === "grpc" ? "grpc" : "http"}-${run}`;
    const logs = client.logClient({
      serviceName: service,
      environment: "test",
      protocol,
      batch: { flushIntervalMs: 200 },
    });
    logs.send({
      message: `roundtrip ${run} ${protocol} it's ünïcode`,
      severity: Severity.Error,
      attributes: { run, protocol, n: 7, ok: true, password: "hunter2-secret" },
      traceId: "4bf92f3577b34da6a3ce929d0e0e4736",
      spanId: "00f067aa0ba902b7",
    });
    logs.logger("it").warn(`facade ${run} ${protocol}`, { run, protocol });
    await logs.flush();
    assert.deepEqual(logs.stats().failed, 0);
    const filter = new FilterBuilder().eq("service.name", service).eq("run", run);
    const page = await poll(
      () => client.query.queryLogs({ filter, orderBy: "asc" }),
      (p) => p.records.length >= 2,
    );
    assert.equal(page.records.length, 2, `${protocol}: ${JSON.stringify(page)}`);
    const rec = page.records.find((r) => r.severity === Severity.Error);
    assert.ok(rec, "error record");
    assert.equal(rec.body, `roundtrip ${run} ${protocol} it's ünïcode`);
    assert.equal(rec.attributes.n, 7);
    assert.equal(rec.attributes.ok, true);
    assert.equal(rec.attributes.password, "[REDACTED]");
    assert.equal(rec.resource["service.name"], service);
    assert.equal(rec.resource["deployment.environment"], "test");
    assert.equal(rec.traceId, "4bf92f3577b34da6a3ce929d0e0e4736");
    assert.equal(rec.spanId, "00f067aa0ba902b7");
    assert.match(rec.id, /\S/);
    assert.match(rec.timeUnixNano, /^\d{19}$/);
    const warn = page.records.find((r) => r.severity === Severity.Warn);
    assert.equal(warn?.body, `facade ${run} ${protocol}`);
  }
});

test("queryLogs pages with the SDK cursor (exact, no duplicates) and selectFields", async () => {
  const logs = createLogClient(SignozConfig.fromEnv(), {
    serviceName: `ts-paging-${run}`,
    batch: { flushIntervalMs: 200 },
  });
  const base = Date.now();
  // pairs share a millisecond, so page boundaries fall inside one millisecond
  const records = Array.from({ length: 11 }, (_, i) => ({
    message: `page ${run} #${i}`,
    attributes: { run, i },
    timestamp: new Date(base + Math.floor(i / 2)),
  }));
  logs.sendBatch(records);
  await logs.close();
  const filter = `run = '${run}' AND body CONTAINS 'page'`;
  await poll(
    () => client.query.queryLogs({ filter, limit: 100 }),
    (p) => p.records.length >= 11,
  );
  for (const orderBy of ["desc", "asc"] as const) {
    const seen: string[] = [];
    let cursor: string | undefined;
    let pages = 0;
    do {
      const page = await client.query.queryLogs({
        filter,
        limit: 3,
        orderBy,
        ...(cursor ? { cursor } : {}),
      });
      seen.push(...page.records.map((r) => r.body));
      cursor = page.nextCursor;
      pages++;
    } while (cursor !== undefined && pages < 20);
    assert.equal(seen.length, 11, `${orderBy}: ${JSON.stringify(seen)}`);
    assert.equal(new Set(seen).size, 11);
  }
  const slim = await client.query.queryLogs({ filter, limit: 1, selectFields: ["body"] });
  assert.equal(slim.records.length, 1);
  assert.match(slim.records[0]!.body, /^page /);
});

test("queryLogs: unknown fields warn, malformed filters raise VALIDATION", async () => {
  const warned = await client.query.queryLogs({ filter: `nosuchfield_${run} = 'x'` });
  assert.equal(warned.records.length, 0);
  assert.ok(
    warned.warnings.some((w) => w.includes(`nosuchfield_${run}`)),
    JSON.stringify(warned.warnings),
  );
  await assert.rejects(client.query.queryLogs({ filter: "body = (" }), (err: unknown) => {
    assert.ok(err instanceof SignozError);
    assert.equal(err.code, "VALIDATION");
    assert.equal(err.httpStatus, 400);
    return true;
  });
});

test("LogQueryClient: search, waitForLog, listStreams, listLabels, listLogFields, listFieldValues", async () => {
  const service = `ts-search-${run}`;
  const logs = createLogClient(SignozConfig.fromEnv(), { serviceName: service });
  logs.send({ message: `search ${run} needle_${run}`, attributes: { [`label_${run}`]: "v" } });
  await logs.close();
  const found = await client.logs.waitForLog(`needle_${run}`, { timeoutMs: 60_000 });
  assert.equal(found.body, `search ${run} needle_${run}`);
  const again = await waitForLog(client.logs, `service.name = '${service}'`, {
    predicate: (r) => r.attributes[`label_${run}`] === "v",
    timeoutMs: 30_000,
  });
  assert.equal(again.resource["service.name"], service);
  const page = await client.logs.search(`needle_${run}`, { limit: 10, order: "asc" });
  assert.equal(page.records.length, 1);
  assert.equal(page.nextCursor, undefined);
  const streams = await poll(
    () => client.logs.listStreams(),
    (s) => s.includes(service),
    30_000,
  );
  assert.ok(streams.includes(service), JSON.stringify(streams));
  const labels = await poll(
    () => client.logs.listLabels(),
    (l) => l.includes(`label_${run}`),
    30_000,
  );
  assert.ok(labels.includes(`label_${run}`));
  const fields = await client.query.listLogFields({ search: `label_${run}` });
  assert.deepEqual(
    fields.map((f) => [f.name, f.context, f.dataType]),
    [[`label_${run}`, "attribute", "string"]],
  );
  const values = await client.query.listFieldValues("service.name", {
    source: "logs",
    search: service,
  });
  assert.ok(values.includes(service), JSON.stringify(values));
});

test("tail delivers matching records over live tail (SSE)", async () => {
  const got: LogRecord[] = [];
  const errors: unknown[] = [];
  const handle = client.logs.tail(`body CONTAINS 'tail-${run}'`, (r) => got.push(r), {
    onError: (e) => errors.push(e),
  });
  try {
    await new Promise((r) => setTimeout(r, 1500));
    const logs = createLogClient(SignozConfig.fromEnv(), { serviceName: `ts-tail-${run}` });
    logs.send({ message: `tail-${run} match` });
    logs.send({ message: `other ${run} nomatch` });
    await logs.close();
    await poll(
      async () => got.length,
      (n) => n >= 1,
      30_000,
    );
  } finally {
    handle.stop();
  }
  assert.deepEqual(
    got.map((r) => r.body),
    [`tail-${run} match`],
    JSON.stringify(errors.map((e) => String(e))),
  );
});

test("SignozLogExporter exports directly", async () => {
  const exporter = new SignozLogExporter(SignozConfig.fromEnv());
  const result = await exporter.export(
    [
      {
        timestamp: new Date(),
        severity: Severity.Info,
        severityText: "INFO",
        body: `exporter ${run}`,
        attributes: { run },
        resource: { "service.name": `ts-exporter-${run}` },
      },
    ],
    { gzip: true },
  );
  await exporter.shutdown();
  assert.deepEqual(result, { accepted: 1, rejected: 0 });
  const rec = await client.logs.waitForLog(`exporter ${run}`, { timeoutMs: 60_000 });
  assert.equal(rec.resource["service.name"], `ts-exporter-${run}`);
});

test("aggregate counts logs (scalar + time series)", async () => {
  const logs = createLogClient(SignozConfig.fromEnv(), { serviceName: `ts-agg-${run}` });
  for (let i = 0; i < 5; i++)
    logs.send({ message: `agg ${run} ${i}`, attributes: { agg: run, seq: i } });
  await logs.close();
  const filter = new FilterBuilder().eq("agg", run);
  const scalar = await poll(
    () => client.query.aggregate("logs").filter(filter).count("n").max("seq", "maxSeq").run(),
    (r) => r.rows[0]?.values.n === 5,
  );
  assert.deepEqual(scalar.rows, [{ groups: {}, values: { n: 5, maxSeq: 4 } }]);
  const series = await client.query.aggregate("logs").filter(filter).count("n").timeSeries(60);
  const total = series
    .filter((s) => s.alias === "n")
    .flatMap((s) => s.points)
    .reduce((a, p) => a + p.value, 0);
  assert.equal(total, 5);
  const raw = await client.query.queryRange({
    start: Date.now() - 3_600_000,
    end: Date.now() + 1000,
    requestType: "scalar",
    compositeQuery: {
      queries: [
        {
          type: "builder_query",
          spec: {
            name: "A",
            signal: "logs",
            filter: { expression: filter.build() },
            aggregations: [{ expression: "count()" }],
          },
        },
      ],
    },
  });
  assert.ok(JSON.stringify(raw.data).includes("__result_0"));
});

test("tracing over OTLP/HTTP and gRPC: getTrace, waitForTrace, queryTraces, searchTraces, services, operations", async () => {
  for (const protocol of ["http/protobuf", "grpc"] as const) {
    const service = `ts-trace-${protocol === "grpc" ? "grpc" : "http"}-${run}`;
    const handle = client.initTracing({ serviceName: service, protocol, setGlobal: false });
    const tracer = handle.tracer("integration");
    const root = tracer.startSpan("GET /checkout", {
      kind: SpanKind.SERVER,
      attributes: { run, "http.method": "GET" },
    });
    const ctx = trace.setSpan(context.active(), root);
    const child = tracer.startSpan(
      "db.query",
      { kind: SpanKind.CLIENT, attributes: { n: 2 } },
      ctx,
    );
    child.addEvent("checkpoint", { step: 1 });
    child.setStatus({ code: SpanStatusCode.ERROR, message: "boom" });
    child.end();
    root.end();
    await handle.forceFlush(10_000);
    const traceId = root.spanContext().traceId;
    const t = await client.traces.waitForTrace(traceId, { minSpans: 2, timeoutMs: 60_000 });
    const byName = new Map(t.spans.map((s) => [s.name, s]));
    const r = byName.get("GET /checkout");
    const c = byName.get("db.query");
    assert.ok(r && c, JSON.stringify(t));
    assert.equal(c.parentSpanId, r.spanId);
    assert.equal(r.kind, "server");
    assert.equal(c.kind, "client");
    assert.equal(c.status.code, "error");
    assert.equal(c.status.message, "boom");
    assert.equal(c.events[0]?.name, "checkpoint");
    assert.equal(r.serviceName, service);
    const full = await client.traces.getTrace(traceId);
    assert.equal(full?.complete, true);
    assert.equal(full?.totalSpans, 2);

    const spans = await client.query.queryTraces({ service, operation: "db.query" });
    assert.equal(spans.spans.length, 1);
    assert.equal(spans.spans[0]?.traceId, traceId);
    const hits = await client.traces.searchTraces({ service, tags: { run } });
    assert.equal(hits.length, 1);
    assert.equal(hits[0]?.traceId, traceId);
    assert.equal(hits[0]?.rootName, "GET /checkout");
    assert.equal(hits[0]?.spanCount, 2);
    assert.ok((hits[0]?.startMicros ?? 0) > 0);
    assert.ok((await client.traces.listServices()).includes(service));
    assert.deepEqual(await client.traces.listOperations(service), ["GET /checkout", "db.query"]);
    const agg = await client.query
      .aggregate("traces")
      .filter(`service.name = '${service}'`)
      .count("calls")
      .p99("duration_nano", "p99")
      .groupBy("name")
      .run();
    assert.equal(agg.rows.length, 2);
    assert.ok(agg.rows.every((row) => row.values.calls === 1 && (row.values.p99 ?? 0) > 0));
  }
  assert.equal(await client.traces.getTrace("0123456789abcdef0123456789abcdef"), null);
});

test("getDependencies reports a db client edge", async () => {
  const service = `ts-deps-${run}`;
  const handle = initTracing({
    serviceName: service,
    setGlobal: false,
    signoz: SignozConfig.fromEnv(),
  });
  const tracer = handle.tracer("integration");
  const root = tracer.startSpan("GET /deps", { kind: SpanKind.SERVER });
  const db = tracer.startSpan(
    "SELECT",
    { kind: SpanKind.CLIENT, attributes: { "db.system": "postgresql" } },
    trace.setSpan(context.active(), root),
  );
  db.end();
  root.end();
  await handle.shutdown();
  const deps = await poll(
    () => client.traces.getDependencies(),
    (d) => d.some((e) => e.parent === service && e.child === "postgresql"),
    150_000,
  );
  const edge = deps.find((e) => e.parent === service && e.child === "postgresql");
  assert.ok(edge, JSON.stringify(deps));
  assert.ok(edge.callCount >= 1);
});

test("exporter helpers build the configured exporter", async () => {
  const cfg = SignozConfig.fromEnv();
  const defaults = signozTracingDefaults(cfg, "grpc");
  assert.equal(defaults.protocol, "grpc");
  const exporter = createSignozSpanExporter(
    resolveTracingConfig({ serviceName: "x", ...defaults }),
  );
  await exporter.shutdown();
});
