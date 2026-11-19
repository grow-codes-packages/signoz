// SigNoz TypeScript SDK tour: configuration from the environment, health, ping, OTLP logs and
// traces to the collector, then reading them back with the query API.
//
//   (cd ../../typescript && npm install && npm run build)   # or use the published package
//   npm install
//   GROW_SIGNOZ_URL=http://127.0.0.1:8080 GROW_SIGNOZ_API_KEY=... \
//   GROW_SIGNOZ_OTLP_ENDPOINT=http://127.0.0.1:4318 npm start

import { FilterBuilder, Severity, SignozClient, SignozError, withSpan } from "@grow-codes/signoz";

const client = SignozClient.fromEnv({
  // Warnings only; the default logger is silent.
  logger: { debug() {}, info() {}, warn: console.warn, error: console.error },
});
console.log(`config: ${client.config}`); // keys print as [REDACTED]
const run = Math.random().toString(36).slice(2, 10);

try {
  const health = await client.health(); // never throws for an unreachable service
  console.log("health:", health);
  console.log(`ping: ${(await client.ping({ signal: AbortSignal.timeout(10_000) })).toFixed(1)} ms`);

  // Logs: OTLP to the collector (batched, redacted, never blocks the caller).
  const logs = client.logClient({ serviceName: "example-checkout", environment: "dev" });
  const log = logs.logger("example", { run });
  log.info("order placed", { orderId: 42 });
  log.error("payment failed", { orderId: 42, password: "never-leaves-the-process" });
  await logs.flush();

  // Traces: OpenTelemetry with the SigNoz exporter.
  const tracing = client.initTracing({ serviceName: "example-checkout" });
  const traceId = await withSpan("GET /checkout", async (span) => {
    await withSpan("db.query", () => undefined, { attributes: { "db.system": "postgresql" } });
    return span.spanContext().traceId;
  }, { attributes: { run } });
  await tracing.forceFlush();

  // Read everything back.
  const found = await client.logs.waitForLog(`service.name = 'example-checkout' AND run = '${run}'`, {
    predicate: (r) => r.severity === Severity.Error,
  });
  console.log("log:", found.body, found.attributes);

  const errors = await client.query.queryLogs({
    filter: new FilterBuilder().eq("service.name", "example-checkout").in("severity_text", ["ERROR", "FATAL"]),
    limit: 10,
  });
  console.log(`errors in the last hour: ${errors.records.length}`);

  const trace = await client.traces.waitForTrace(traceId, { minSpans: 2 });
  console.log("trace:", trace.spans.map((s) => `${s.serviceName} ${s.name} ${s.durationMicros}µs`));
  console.log("services:", await client.traces.listServices());
  console.log("operations:", await client.traces.listOperations("example-checkout"));

  const latency = await client.query
    .aggregate("traces")
    .filter("service.name = 'example-checkout'")
    .count("calls")
    .p99("duration_nano", "p99")
    .groupBy("name")
    .run();
  console.log("latency by operation:", latency.rows);
} catch (err) {
  if (err instanceof SignozError) {
    // Canonical code + HTTP status; the message never contains credentials.
    console.error(`${err.code}: ${err.message}`, { httpStatus: err.httpStatus });
    process.exitCode = 1;
  } else {
    throw err;
  }
} finally {
  await client.close(); // flushes the log client and shuts tracing down
}
