# Trace queries

`client.query` (also `client.traces`) implements the shared `TraceQueryClient` and adds the raw
span query. Sending spans is [`initTracing`](ingest.md#tracing-inittracing).

| Function | Request | Defaults and behaviour |
| - | - | - |
| `queryTraces` | `POST /api/v5/query_range`, `requestType: raw`, signal `traces` | one normalized `Span` per matching span; `service` (`service.name = …`), `operation` (`name = …`), extra filter; window last hour, limit 100, `offset` paging, order `desc` |
| `getTrace` | `POST /api/v4/traces/{traceId}/waterfall` | the whole trace with every span; **`null`/`None`/empty** when SigNoz does not know the trace or reports no spans (HTTP 404 included). A trace larger than the waterfall window (more than ~10,000 spans, `hasMore`) is completed with raw span queries. Span start times have millisecond precision |
| `searchTraces` | `POST /api/v5/query_range`, `requestType: trace` + one waterfall per hit (4 at a time) | `TraceSummary {traceId, rootServiceName, rootName, startMicros, durationMicros, spanCount}`; `service`, `operation`, `tags`, `minDuration`/`maxDuration`, window, limit (default 20); `startMicros` and `spanCount` come from the waterfall at millisecond precision |
| `listServices` | `POST /api/v5/query_range`, scalar `count()` grouped by `service.name` | every service with spans in the window (default last hour), sorted |
| `listOperations` | same, filtered by service and grouped by `name` | span names of one service in the window, sorted; empty service → `VALIDATION` |
| `getDependencies` | `POST /api/v1/dependency_graph` | `DependencyLink {parent, child, callCount}` for the window (default last hour) |
| `waitForTrace` | polls `getTrace` | test helper: the trace once it has at least `minSpans` spans (default 1) within the timeout (default 30 s), else `TIMEOUT` |

Trace ids are normalized (32 lower-case hex characters; upper case accepted); an invalid id raises
`VALIDATION` before any request.

=== "TypeScript"

    ```ts
    const spans = await client.query.queryTraces({ service: "checkout", operation: "db.query", limit: 50 });
    const trace = await client.traces.getTrace("4bf92f3577b34da6a3ce929d0e0e4736"); // SignozTrace | null
    const hits = await client.traces.searchTraces({ service: "checkout", tags: { "http.method": "POST" },
      minDurationMicros: 500_000, limit: 10 });
    const services = await client.traces.listServices();
    const ops = await client.traces.listOperations("checkout", { start: new Date(Date.now() - 86_400_000) });
    const deps = await client.traces.getDependencies({});
    const done = await client.traces.waitForTrace(traceId, { minSpans: 2, timeoutMs: 60_000 });
    ```

=== "Rust"

    ```rust
    use std::time::Duration;
    use grow_signoz::tracing::{DependencyOptions, TraceQueryClient, TraceSearchQuery, WaitForTraceOptions};
    use grow_signoz::TraceQuery;

    let q = client.query();
    let spans = q.query_traces(TraceQuery::service("checkout").operation("db.query").limit(50)).await?;
    let trace = q.get_trace("4bf92f3577b34da6a3ce929d0e0e4736").await?; // Option<Trace>
    let hits = q
        .search_traces(&TraceSearchQuery {
            service: Some("checkout".into()),
            min_duration: Some(Duration::from_millis(500)),
            limit: 10,
            ..Default::default()
        })
        .await?;
    let services = q.list_services().await?;
    let ops = q.list_operations("checkout").await?;
    let deps = q.get_dependencies(DependencyOptions::default()).await?;
    let done = q.wait_for_trace(&trace_id, WaitForTraceOptions::default()).await?;
    ```

=== "Go"

    ```go
    q := client.Query()
    spans, err := q.QueryTraces(ctx, signoz.TraceQuery{Service: "checkout", Operation: "db.query", Limit: 50})
    trace, err := q.GetTrace(ctx, "4bf92f3577b34da6a3ce929d0e0e4736") // nil when unknown
    hits, err := q.SearchTraces(ctx, signoz.TraceSearchQuery{Service: "checkout", MinDuration: 500 * time.Millisecond, Limit: 10})
    services, err := q.ListServices(ctx)
    ops, err := q.ListOperationsInRange(ctx, "checkout", signoz.TimeRange{Start: time.Now().Add(-24 * time.Hour)})
    deps, err := q.GetDependencies(ctx, signoz.DependencyOptions{})
    done, err := q.WaitForTrace(ctx, traceID, signoz.WaitForTraceOptions{Timeout: time.Minute, MinSpans: 2})
    ```

=== "Python"

    ```python
    spans = client.query.query_traces(service="checkout", operation="db.query", limit=50)
    trace = client.traces.get_trace("4bf92f3577b34da6a3ce929d0e0e4736")  # None when unknown
    hits = client.traces.search_traces(
        sdk.TraceSearchQuery(service="checkout", min_duration_micros=500_000, limit=10)
    )
    services = client.traces.list_services()
    ops = client.traces.list_operations("checkout")
    deps = client.traces.get_dependencies()
    done = client.traces.wait_for_trace(trace_id, timeout_s=60, min_spans=2)
    ```

=== "Java"

    ```java
    SignozQueryClient q = client.query();
    SpanQueryResult spans =
        q.queryTraces(TraceQuery.builder().service("checkout").operation("db.query").limit(50).build());
    Optional<Trace> trace = q.getTrace("4bf92f3577b34da6a3ce929d0e0e4736");
    List<TraceSummary> hits =
        q.searchTraces(TraceSearchQuery.builder().service("checkout").minDurationMicros(500_000L).limit(10).build());
    List<String> services = q.listServices();
    List<String> ops = q.listOperations("checkout", TimeRange.since(Instant.now().minus(Duration.ofDays(1))));
    List<DependencyLink> deps = q.getDependencies(null, null); // default window
    Trace done = q.waitForTrace(traceId, new WaitForTraceOptions(Duration.ofSeconds(60), Duration.ofSeconds(1), 2));
    ```

## Span mapping

Spans are normalized to the shared model: `traceId`, `spanId`, `parentSpanId`, `name`, `kind`
(`server`, `client`, `producer`, `consumer`, `internal`), `serviceName`, `startMicros`,
`endMicros`, `durationMicros`, `status {code: ok|error|unset, message}`, `attributes` (string,
number and bool maps merged), `events` (with their attributes) and `links`. SigNoz drops span
link attributes, so links come back with empty attributes. TypeScript traces also carry
`complete`, `totalSpans`, `rootServiceName` and `rootName`.
