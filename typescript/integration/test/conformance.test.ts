// Runs the shared conformance scenarios (conformance/scenarios) against the real SigNoz stack.
// Capabilities: core has no "version" (the community image reports an empty version →
// serverVersion raises UNSUPPORTED); logging declares query/tail/streams/labels; tracing declares
// query/services/dependencies.

import assert from "node:assert/strict";
import { after, before, test } from "node:test";
import { SignozClient, SignozConfig, createLogClient, initTracing, type BatchOptions } from "@grow-codes/signoz";
import { formatReport, runConformance, type LoggingTarget, type TracingTarget } from "@grow-codes/signoz/testing";
import { loadBootstrapEnv, scenariosDir } from "./env.ts";

let query: SignozClient;
const keepAlive = setInterval(() => undefined, 1000);

before(() => {
  loadBootstrapEnv();
  query = SignozClient.fromEnv();
});

after(async () => {
  await query.close();
  clearInterval(keepAlive);
});

test("conformance: core", async () => {
  const report = await runConformance({
    scenariosDir,
    categories: ["core"],
    factories: { core: () => SignozClient.fromEnv() },
    capabilities: { core: [] },
  });
  console.log(formatReport(report));
  assert.equal(report.failed, 0, formatReport(report));
  assert.ok(report.passed > 0);
});

test("conformance: logging", async () => {
  const report = await runConformance({
    scenariosDir,
    categories: ["logging"],
    factories: {
      logging: (ctx): LoggingTarget => ({
        client: createLogClient(SignozConfig.fromEnv(), {
          serviceName: `conformance-logs-${ctx.run}`,
          batch: (ctx.batchOptions ?? {}) as BatchOptions,
        }),
        query: query.logs,
      }),
    },
    capabilities: { logging: ["query", "tail", "streams", "labels"] },
  });
  console.log(formatReport(report));
  assert.equal(report.failed, 0, formatReport(report));
  assert.equal(report.skipped, 0, formatReport(report));
});

test("conformance: tracing", async () => {
  const report = await runConformance({
    scenariosDir,
    categories: ["tracing"],
    factories: {
      tracing: (ctx): TracingTarget => ({
        handle: initTracing({ serviceName: `conformance-traces-${ctx.run}`, signoz: SignozConfig.fromEnv() }),
        query: query.traces,
      }),
    },
    capabilities: { tracing: ["query", "services", "dependencies"] },
  });
  console.log(formatReport(report));
  assert.equal(report.failed, 0, formatReport(report));
  assert.equal(report.skipped, 0, formatReport(report));
});
