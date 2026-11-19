# Functions

The SigNoz API of this SDK. Every function exists in all five languages with the same behaviour,
defaults and error codes; names follow each language's convention. Each module page shows every
function with an example per language and the request it sends.

| Module | Page | What it covers |
| - | - | - |
| Client and status | [Client and status](functions/client.md) | `fromEnv`, `health`, `ping`, `serverVersion`, `close` |
| Sending logs and traces | [Ingest](functions/ingest.md) | `logClient` / `createLogClient`, the OTLP log exporter, `initTracing` |
| Log queries | [Logs](functions/logs.md) | `queryLogs` (keyset cursor), `search`, `tail`, `listStreams`, `listLabels`, `listLogFields`, `listFieldValues`, `waitForLog` |
| Trace queries | [Traces](functions/traces.md) | `queryTraces`, `getTrace`, `searchTraces`, `listServices`, `listOperations`, `getDependencies`, `waitForTrace` |
| Aggregations | [Aggregate and raw queries](functions/aggregate.md) | `aggregate` builder (count, percentiles, …; scalar and time series), `queryRange` |
| Filters | [FilterBuilder](functions/filter.md) | `FilterBuilder`, `andFilters`, `quoteFilterString`, `escapeLikePattern`, `filterLiteral` |

## Names per language

| Function | TypeScript | Rust | Go | Python | Java |
| - | - | - | - | - | - |
| from environment | `SignozClient.fromEnv()` | `SignozClient::from_env()` | `signoz.NewClientFromEnv()` | `SignozClient.from_env()` | `SignozClient.fromEnv()` |
| health | `health()` | `health()` | `Health(ctx)` | `health()` | `health()` |
| ping | `ping()` → ms | `ping()` → `Duration` | `Ping(ctx)` → `time.Duration` | `ping()` → ms | `ping()` → `Duration` |
| server version | `serverVersion()` | `server_version()` | `ServerVersion(ctx)` | `server_version()` | `serverVersion()` |
| query module | `client.query` (= `client.traces`) | `client.query()` | `client.Query()` | `client.query` (= `client.traces`) | `client.query()` |
| log query module | `client.logs` | `client.logs()` / `client.query()` | `client.Logs()` / `client.Query()` | `client.logs` | `client.logs()` / `client.query()` |
| log client | `logClient(opts)` / `createLogClient(cfg, opts)` | `log_client(opts)` / `new_log_client(&cfg, opts)` | `NewLogClient(opts)` / `signoz.NewLogClient(cfg, opts)` | `log_client(...)` / `create_log_client(cfg, ...)` | `logClient(opts)` / `SignozClient.createLogClient(cfg, opts)` |
| log exporter | `new SignozLogExporter(cfg)` | `OtlpLogExporter::new(&cfg)` | `NewOTLPLogExporter(cfg)` | `SignozLogExporter(cfg)` | `client.logExporter()` / `SignozLogExporter.create(cfg)` |
| tracing setup | `initTracing(cfg)` / `client.initTracing(cfg)` | `init_tracing(cfg)` / `client.init_tracing(cfg)` | `signoz.InitTracing(ctx, tc)` / `client.InitTracing(ctx, tc)` | `init_tracing(cfg)` / `client.init_tracing(cfg)` | `SignozTracing.initTracing(cfg, tc)` / `client.initTracing(tc)` |
| raw logs query | `queryLogs(opts)` | `query_logs(LogQuery)` | `QueryLogs(ctx, LogQuery)` | `query_logs(filter, ...)` | `queryLogs(LogQuery)` |
| log search | `search(query, opts)` | `search(query, opts)` | `Search(ctx, query, opts)` | `search(query, ...)` | `search(query, opts)` |
| live tail | `tail(query, onRecord, opts)` | `tail(query, on_record, opts)` | `Tail(ctx, query, onRecord, opts)` | `tail(query, on_record, ...)` | `tail(query, onRecord, opts)` |
| streams / labels | `listStreams()` / `listLabels()` | `list_streams()` / `list_labels()` | `ListStreams(ctx)` / `ListLabels(ctx)` | `list_streams()` / `list_labels()` | `listStreams()` / `listLabels()` |
| log fields | `listLogFields(opts)` | `list_log_fields(opts)` | `ListLogFields(ctx, opts)` | `list_log_fields(...)` | `listLogFields(opts)` |
| field values | `listFieldValues(name, opts)` | `list_field_values(name, opts)` | `ListFieldValues(ctx, name, opts)` | `list_field_values(name, ...)` | `listFieldValues(name, opts)` |
| wait for log | `waitForLog(query, opts)` | `wait_for_log(query, opts)` | `WaitForLog(ctx, query, opts)` | `wait_for_log(query, ...)` | `waitForLog(query, opts)` |
| raw spans query | `queryTraces(opts)` | `query_traces(TraceQuery)` | `QueryTraces(ctx, TraceQuery)` | `query_traces(...)` | `queryTraces(TraceQuery)` |
| trace | `getTrace(id)` → `null` | `get_trace(id)` → `None` | `GetTrace(ctx, id)` → `nil` | `get_trace(id)` → `None` | `getTrace(id)` → `Optional.empty()` |
| trace search | `searchTraces(query)` | `search_traces(&query)` | `SearchTraces(ctx, query)` | `search_traces(query)` | `searchTraces(query)` |
| services | `listServices(range?)` | `list_services()` / `list_services_in_range(r)` | `ListServices(ctx)` / `ListServicesInRange(ctx, r)` | `list_services(start=, end=)` | `listServices()` / `listServices(range)` |
| operations | `listOperations(service, range?)` | `list_operations(s)` / `list_operations_in_range(s, r)` | `ListOperations(ctx, s)` / `ListOperationsInRange(ctx, s, r)` | `list_operations(s, start=, end=)` | `listOperations(s)` / `listOperations(s, range)` |
| dependencies | `getDependencies(opts)` | `get_dependencies(opts)` | `GetDependencies(ctx, opts)` | `get_dependencies(start=, end=)` | `getDependencies(start, end)` |
| wait for trace | `waitForTrace(id, opts)` | `wait_for_trace(id, opts)` | `WaitForTrace(ctx, id, opts)` | `wait_for_trace(id, ...)` | `waitForTrace(id, opts)` |
| aggregate | `aggregate(signal)…run()` | `aggregate(AggregateBuilder)` | `Aggregate(ctx, NewAggregate(signal)….Query())` | `aggregate(signal)….run()` | `aggregate(signal)….run()` |
| raw query range | `queryRange(body)` | `query_range(&body)` | `QueryRange(ctx, body)` | `query_range(body)` | `queryRange(body)` |
| filter builder | `new FilterBuilder()` | `FilterBuilder::new()` | `signoz.NewFilter()` | `FilterBuilder()` | `FilterBuilder.create()` |

Every I/O method takes a cancellation handle: TypeScript `{ signal }`, Rust dropping the future,
Go `ctx`, Python `cancel=` (or the `AsyncSignozClient`), Java the `…Async` variant. Rust also has
a `blocking` feature (`grow_signoz::blocking::SignozClient`) with the same functions.

## Wire facts that apply everywhere

- **Query API version.** Logs, spans and aggregations use `POST /api/v5/query_range`; traces use
  the waterfall API `POST /api/v4/traces/{traceId}/waterfall`; fields use `/api/v1/fields/*`;
  the live tail uses `GET /api/v3/logs/livetail` (server-sent events); dependencies use
  `POST /api/v1/dependency_graph`. These are the versions shipped by `signoz-community:v0.150.0`.
- **Time windows** default to the last hour (`end` = now, `start` = end − 1 h). `start` must be
  before `end` (`VALIDATION`). Log timestamps are nanoseconds; span start times read from the
  waterfall have millisecond precision.
- **Limits**: page sizes are 1 … 10,000; `0` (or unset) means the default (100 for raw queries,
  20 for `searchTraces`). Larger values raise `VALIDATION` before any request.
- **Unknown filter fields** do not fail: SigNoz answers with no rows and a warning. Every query
  result returns the server `warnings`, and the SDK logs them at warn level.
- **Error mapping**: HTTP 400 → `VALIDATION`, 401 → `AUTHENTICATION`, 403 → `PERMISSION_DENIED`,
  404 → `NOT_FOUND` (or `null`/`None` for `getTrace`), 429 → `RATE_LIMITED`, 5xx →
  `UNAVAILABLE`. An unknown route answers `200 text/html` (the UI); the SDK raises `UNSUPPORTED`.
- **A closed client** raises `CANCELLED` ("client is closed") for every call, including
  `logClient()` / `initTracing()` / `tail()`.
