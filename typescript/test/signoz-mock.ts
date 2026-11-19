// Mock SigNoz API for unit tests, answering with the responses recorded from the real server
// (test/fixtures, copied from the GRO-1372 prep run).

import { readFileSync } from "node:fs";
import {
  type MockResponse,
  type MockServer,
  type RecordedRequest,
  startServer,
} from "./helpers/server.js";

export const API_KEY = "test-api-key-0123456789";

/** Loads a recorded fixture (JSON parsed, `.txt` as text). */
export function fixture(name: string): object {
  return JSON.parse(readFileSync(new URL(`./fixtures/${name}`, import.meta.url), "utf8")) as object;
}

export interface QueryBody {
  start: number;
  end: number;
  requestType: string;
  compositeQuery: { queries: { type: string; spec: Record<string, unknown> }[] };
}

/** Parses a recorded v5 query_range body. */
export function querySpec(req: RecordedRequest): QueryBody & { spec: Record<string, unknown> } {
  const body = JSON.parse(req.body) as QueryBody;
  return { ...body, spec: body.compositeQuery.queries[0]?.spec ?? {} };
}

export type Route = (req: RecordedRequest, index: number) => MockResponse | undefined;

/**
 * Starts a mock server. `routes` are tried first; default answers mirror the real server: auth on
 * every authenticated route (401 fixture), health/version fixtures, HTML for unknown paths.
 */
export async function startSignoz(...routes: Route[]): Promise<MockServer> {
  return startServer((req, index) => {
    for (const route of routes) {
      const res = route(req, index);
      if (res !== undefined) return res;
    }
    const path = req.url.split("?")[0] ?? "";
    if (path === "/api/v1/health") return { body: fixture("health.ok.json") };
    if (path === "/api/v1/version") return { body: fixture("serverVersion.ok.json") };
    if (path.startsWith("/api/") && req.headers["signoz-api-key"] !== API_KEY) {
      return { status: 401, body: fixture("ping.unauthenticated.json") };
    }
    if (path === "/api/v1/fields/keys") return { body: fixture("listLogFields.ok.json") };
    if (path === "/api/v1/fields/values") return { body: fixture("listStreams.fieldValues.json") };
    if (path === "/api/v1/dependency_graph") return { body: fixture("getDependencies.ok.json") };
    if (path.startsWith("/api/v4/traces/")) {
      return path.includes("fe67ae4bfe67ae4bfe67ae4bfe67ae4b")
        ? { body: fixture("getTrace.waterfall.ok.json") }
        : { status: 404, body: fixture("getTrace.notFound.json") };
    }
    if (path === "/api/v5/query_range") {
      const q = querySpec(req);
      if (q.requestType === "trace") return { body: fixture("searchTraces.traceMode.json") };
      if (q.requestType === "time_series") return { body: fixture("aggregate.timeSeries.json") };
      if (q.requestType === "scalar") {
        const group = (q.spec.groupBy as { name: string }[] | undefined)?.[0]?.name;
        if (group === "service.name") return { body: fixture("listServices.scalar.json") };
        if (group === "name") return { body: fixture("listOperations.scalar.json") };
        return { body: fixture("aggregate.count.json") };
      }
      if (q.spec.signal === "traces") return { body: fixture("queryTraces.raw.ok.json") };
      return { body: fixture("queryLogs.raw.ok.json") };
    }
    return {
      status: 200,
      headers: { "content-type": "text/html" },
      body: "<!doctype html><html></html>",
    };
  });
}
