# Compatibility

## Runtimes

| Language | Supported | Tested in CI |
| - | - | - |
| TypeScript | Node.js ≥ 22.12, ESM and CommonJS | Node 22, 24 |
| Rust | MSRV 1.88, edition 2021, tokio | stable, 1.88 |
| Go | Go ≥ 1.26 | 1.26, 1.27 |
| Python | ≥ 3.10 | 3.10, 3.13, 3.14 |
| Java | ≥ 17 (Java 17 bytecode) | Temurin 17, 21, 25 |

## SigNoz versions

The integration suite runs against the same images as the Grow stack templates:

| Component | Image / version |
| - | - |
| SigNoz (query service + UI) | `signoz/signoz-community:v0.150.0` |
| SigNoz OTel Collector | `v0.144.15` (release binary, OTLP gRPC 4317 / HTTP 4318) |
| ClickHouse | `25.12.5` (one-node cluster, embedded Keeper) |

The SDK uses these query API versions: `POST /api/v5/query_range` (logs, spans, aggregations,
services, operations), `POST /api/v4/traces/{id}/waterfall` (traces), `GET /api/v3/logs/livetail`
(tail), `GET /api/v1/fields/keys|values` (fields, labels, streams), `POST /api/v1/dependency_graph`
(dependencies), `GET /api/v1/health` and `GET /api/v1/version`. SigNoz has changed its query API
several times; older servers without v5 `query_range` answer unknown routes with the web UI, which
the SDK reports as `UNSUPPORTED`. Only MIT-licensed community APIs are used (no `ee/` endpoints).
When the stack catalog bumps the SigNoz image, `docker/compose.service.yml` is bumped in the same
release cycle.

## Functions the service or a language cannot serve

Every public function is called against the real service in the Docker suite. These behaviours
cannot be produced by the self-hosted community stack and are covered by unit tests (mock HTTP /
gRPC servers) in all five languages:

| Function / behaviour | Reason |
| - | - |
| `serverVersion()` returning a value | the community image reports `"version": ""`; the SDK raises `UNSUPPORTED` (checked against the real server) |
| SigNoz Cloud ingestion key (`signoz-ingestion-key` header / metadata) and Cloud TLS endpoints | needs a SigNoz Cloud tenant; the self-hosted collector ignores the header |
| `getTrace` completion of a windowed waterfall (`hasMore`, more than ~10,000 spans) | too large to generate in the test stack |
| Retries on 429 and 5xx, `Retry-After` | the server does not produce them on demand |
| gRPC export error-code mapping (Rust, Java success-path parsing) | the collector accepts every valid request; the mapping is tested with mock gRPC servers |

## Skipped conformance scenarios

| Scenario step | Reason |
| - | - |
| `core.health` → `serverVersion` | gated by the `version` capability, which SigNoz community does not declare (empty version) |

Capabilities declared by every language: logging `query`, `tail`, `streams`, `labels`; tracing
`query`, `services`, `dependencies`.

## Service behaviour the SDK smooths over

- **Paging.** The server `nextCursor` skips rows in the millisecond before it; the SDK uses its
  own keyset cursor (same wire format in all languages, see [Logs](functions/logs.md#paging-the-sdks-own-keyset-cursor)).
- **Aggregation aliases** are not echoed (`__result_<i>`); the SDK maps them back by index.
- **Unknown routes** return `200 text/html` (the UI) → `UNSUPPORTED`.
- **Log timestamps** are int64 nanoseconds (beyond 2^53): decoded exactly in every language
  (TypeScript keeps `timeUnixNano` as a string).
- **Waterfall times** are milliseconds: span start times read through `getTrace` and
  `searchTraces.startMicros` have millisecond precision.
- **Empty results** come back as `rows: null`; the SDK returns empty lists.
- **`CONTAINS`** is a LIKE pattern on the server (`_` and `%` are wildcards); `FilterBuilder`
  escapes them.
- **Span link attributes** are dropped by SigNoz; links come back with empty attributes.
- **Unknown filter fields** return no rows plus a warning instead of an error.

## Language differences

The API is the same in every language; only the conventions differ (naming, durations, async
model). Java cannot change the process environment, so `loadEnv` sets **system properties**
instead and returns the names it set. The differences below are documented on purpose.

### Tracing protocol: explicit vs default

`initTracing` takes the protocol from the SigNoz config (`otlpProtocol`) when the tracing config
does not choose one and has no explicit endpoint.

| Language | Tracing config protocol field | Effect |
| - | - | - |
| TypeScript | optional (`undefined` = not set) | an explicit `http/protobuf` or `grpc` always wins |
| Go | `""` = not set | an explicit `ProtocolHTTPProtobuf` or `ProtocolGRPC` always wins |
| Rust, Python, Java | defaults to `http/protobuf` (cannot be told apart from "not set") | only an explicit `grpc` wins; `http/protobuf` follows the SigNoz config |

So with `otlpProtocol = grpc` and a tracing config that asks for `http/protobuf` without an
endpoint, TypeScript and Go export over HTTP while Rust, Python and Java export over gRPC. Set
the `endpoint` in the tracing config (or set `otlpProtocol` itself) to get the same result
everywhere. In Python, passing just a service name (`init_tracing("svc")`) also follows the
SigNoz config.

### TLS `insecureSkipVerify` and OTLP/gRPC

`insecureSkipVerify` applies to the query API and to OTLP/HTTP exports in every language. The
gRPC libraries differ:

| Language | OTLP/gRPC logs | OTLP/gRPC traces |
| - | - | - |
| TypeScript | host name check skipped; the certificate chain is still verified | not applied (full verification) |
| Rust | not applied (full verification) | not applied (full verification) |
| Go | applied | applied |
| Python | `CONFIG` error (grpcio cannot skip verification) | `CONFIG` error |
| Java | applied | applied |

Add the CA with `tls.caCertPath` / `tls.caCertPem` instead, or use `http/protobuf`.

### Aggregate percentiles

TypeScript and Python check the percentile (5, 10, 20, 25, 50, 75, 90, 95, 99) and raise
`VALIDATION` before sending; Rust, Go and Java send any `p<N>` and let the server decide.

### Records without a severity

A log record without a severity is sent and read back as **INFO** (`severity_number` 9,
`severityText` `"INFO"`) in every language.

### Result shapes

TypeScript `getTrace` returns a `SignozTrace` with `complete`, `totalSpans`, `rootServiceName` and
`rootName` in addition to the shared `Trace`; TypeScript log records carry the row `id` and
`timeUnixNano`. The other languages return the shared models.
