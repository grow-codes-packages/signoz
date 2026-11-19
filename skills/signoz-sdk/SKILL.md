---
name: signoz-sdk
description: Add and use the Grow Codes SDK for SigNoz (`signoz`) in TypeScript, Rust, Go, Python or Java projects. Use when code must send logs or traces to SigNoz over OTLP or read them back through the SigNoz query API (logs, traces, services, aggregations, filters) - installing @grow-codes/signoz, the grow-signoz crate or PyPI package, the Go module github.com/grow-codes-packages/signoz/go or Maven codes.grow.packages:signoz, configuring it with GROW_SIGNOZ_* variables, handling SignozError codes, testing with the bundled fakes, or running the Docker integration suite of this repository.
---

# SigNoz SDK (Grow Codes)

One API for SigNoz in five languages (categories: logging,tracing). Pick the package for the
project's language, configure it from the environment, and handle errors by their canonical code.

## 1. Add the package

| Language | Command | Import |
| - | - | - |
| TypeScript | `npm install @grow-codes/signoz` | `import { SignozClient } from "@grow-codes/signoz"` |
| Rust | `cargo add grow-signoz` (add `tokio` with `macros,rt-multi-thread`) | `use grow_signoz::SignozClient;` |
| Go | `go get github.com/grow-codes-packages/signoz/go` | `signoz "github.com/grow-codes-packages/signoz/go"` |
| Python | `pip install grow-signoz` (or `uv add grow-signoz`) | `from grow_signoz import SignozClient` |
| Java | Maven `codes.grow.packages:signoz` (Gradle `implementation("codes.grow.packages:signoz:<version>")`) | `import codes.grow.packages.signoz.SignozClient;` |

Check the latest version in the README badges before pinning one.

## 2. Configure

Prefer environment configuration so no credential is written into code:

```sh
export GROW_SIGNOZ_URL=https://signoz.example.internal:8080    # query API (default http://127.0.0.1:8080)
export GROW_SIGNOZ_API_KEY=...                                 # service-account key, from your secret store
export GROW_SIGNOZ_OTLP_ENDPOINT=https://otel.example.internal:4318   # collector (default http://127.0.0.1:4318)
export GROW_SIGNOZ_OTLP_PROTOCOL=http/protobuf                 # or grpc (port 4317)
export GROW_SIGNOZ_INGESTION_KEY=...                           # SigNoz Cloud only
```

- The **API key** is only needed for queries, `ping()` and the live tail (any service-account
  role works; `signoz-viewer` is enough). Ingest-only code (log client, tracing) needs none.
- Precedence: explicit config value > `GROW_SIGNOZ_*` > vendor variables (`SIGNOZ_URL`,
  `SIGNOZ_API_KEY`, `SIGNOZ_OTLP_ENDPOINT`, `OTEL_EXPORTER_OTLP_ENDPOINT`,
  `OTEL_EXPORTER_OTLP_PROTOCOL`, `SIGNOZ_INGESTION_KEY`) > default.
Other variables: `…_CONNECT_TIMEOUT`, `…_REQUEST_TIMEOUT`, `…_TOTAL_TIMEOUT` (`10s`, `1500`),
`…_MAX_RETRIES`, `…_CA_CERT`, `…_CLIENT_CERT`, `…_CLIENT_KEY`, `…_PROXY`. Environment proxies
(`HTTPS_PROXY`) are ignored unless `proxy` is set.

## 3. Use

Create one client per process and close it on shutdown (TypeScript `await client.close()`, Rust
`client.close().await`, Go `defer client.Close()`, Python `with …`/`close()`, Java
try-with-resources).

- `health()` never throws for an unhealthy service; check `healthy`.
- `ping()` verifies the API key (`AUTHENTICATION` when wrong, `CONFIG` when missing).
- `serverVersion()` raises `UNSUPPORTED` on the official community image (it reports no version).
- Any call on a closed client raises `CANCELLED`.
- All I/O is retried with jitter on 429/502/503/504 and connection errors; only idempotent calls
  are retried on 500.
- Errors carry `code` (`CONFIG`, `AUTHENTICATION`, `PERMISSION_DENIED`, `NOT_FOUND`, `CONFLICT`,
  `RATE_LIMITED`, `VALIDATION`, `UNAVAILABLE`, `TIMEOUT`, `UNSUPPORTED`, `CANCELLED`), `retryable`,
  `httpStatus` and `requestId`. Branch on the code, not on the message text. Go:
  `errors.Is(err, signoz.ErrNotFound)`.

### Logging

Create the log client once, send records with `send`/`sendBatch` or use the logger facade
(`logger("component").info("message", attrs)`) or the framework adapter for the language (pino or
winston, `tracing`/`log`, `slog`/zap, `logging`/structlog, Logback/Log4j2/JUL). Records are batched
and redacted; call `close()` on shutdown so the queue is flushed.
### Tracing

Call `initTracing({ serviceName })` once at startup, wrap work in `withSpan(name, fn)` or the
`traced` helper, propagate context with `inject`/`extract`, and call `shutdown()` on exit. Use the
trace query client to read traces back (`getTrace`, `searchTraces`, `waitForTrace`).

<!-- service:skill-usage — the service adds its own functions and examples here. -->

### SigNoz query API

`client.query` (Rust/Go/Java `query()` / `Query()`; also `client.traces`) reads SigNoz:

| Need | TypeScript | Python | Go |
| - | - | - | - |
| logs page by filter | `query.queryLogs({ filter, limit, cursor })` | `query.query_logs(f, limit=, cursor=)` | `q.QueryLogs(ctx, signoz.LogQuery{...})` |
| next page | pass `nextCursor` back as `cursor` | `next_cursor` | `NextCursor` |
| live tail | `client.logs.tail(filter, onRecord)` | `client.logs.tail(f, on_record)` | `q.Tail(ctx, f, fn, opts)` |
| full trace | `traces.getTrace(id)` → `null` if unknown | `get_trace(id)` → `None` | `GetTrace` → `nil` |
| spans by service | `query.queryTraces({ service, operation })` | `query_traces(service=, operation=)` | `QueryTraces(ctx, signoz.TraceQuery{...})` |
| services / operations | `listServices()` / `listOperations(svc)` | `list_services()` / `list_operations(svc)` | `ListServices` / `ListOperations` |
| count, p99 … | `query.aggregate("traces").count("n").p99("duration_nano", "p99").groupBy("name").run()` | `aggregate("traces")…run()` | `q.Aggregate(ctx, signoz.NewAggregate(signoz.SignalTraces)….Query())` |
| anything else | `query.queryRange(body)` | `query_range(body)` | `QueryRange(ctx, body)` |

Rust and Java use the same names (`query_logs`, `queryLogs`, …; see the
[Functions](https://grow-codes-packages.github.io/signoz/functions/) pages).

- Build filters with `FilterBuilder` (`new FilterBuilder().eq("service.name", svc).in("severity_text", ["ERROR"])`)
  instead of string concatenation; it quotes values and escapes `CONTAINS` wildcards.
- Default window is the last hour; pass `start`/`end` for older data. Page size 1…10,000
  (default 100; 0 means the default).
- An unknown field in a filter returns **no rows plus a warning**, not an error: check
  `warnings` on the result.
- Data is queryable a few seconds after export: call `flush()` on the log client /
  `forceFlush()` on the tracing handle, then use `waitForLog` / `waitForTrace` in tests.
- `getTrace` start times have millisecond precision (waterfall API).

## 4. Test code that uses the SDK

Use the in-memory fakes from the testing module instead of a real SigNoz in unit tests:
TypeScript `@grow-codes/signoz/testing`, Rust feature `testing`, Go package `testkit`, Python
`grow_signoz.testing`, Java `codes.grow.packages.signoz.testing`.

## 5. Work on this repository

Read `AGENTS.md` first. Short version: the five languages keep the same API; files with the
`Generated by sdk-kit` header are changed in sdk-kit, not here; `make lint coverage` must pass
(≥ 90 % lines and branches); `make test-docker LANGS="<lang>"` runs the integration suite against
`signoz/signoz-community:v0.150.0 (+ signoz-otel-collector + ClickHouse)`.
