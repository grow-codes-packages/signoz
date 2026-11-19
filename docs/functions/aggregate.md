# Aggregate and raw queries

## `aggregate`: count, percentiles and friends

The aggregate builder renders a v5 builder query (`POST /api/v5/query_range`) over `logs` or
`traces`: a filter, one or more aggregations with aliases, `groupBy`, `orderBy`, `limit` and a
window (default last hour). Run it as a **scalar** query (one row per group) or as a **time
series** (buckets of `step`; default chosen by SigNoz).

| Aggregation | Expression sent |
| - | - |
| `count(alias)` | `count()` |
| `countDistinct(field, alias)` | `count_distinct(field)` |
| `sum` / `avg` / `min` / `max(field, alias)` | `sum(field)` … |
| `percentile(p, field, alias)` (TypeScript/Python also `p50`/`p90`/`p95`/`p99`) | `p<p>(field)`; field defaults to `duration_nano` in TypeScript and Python |
| `expression(expr, alias)` (Go/Rust `Expr`/`expr`) | any SigNoz aggregation expression |

SigNoz does not echo aliases (result columns are `__result_<i>`); the SDK maps them back by
index, so `row.values["p99"]` works. Builder names per language: TypeScript/Python/Java
`filter`, Go `Where`; time series: TypeScript `timeSeries(stepSeconds)`, Python
`time_series(step_s)`, Rust/Go/Java `step(...)` then the result's `series`.

=== "TypeScript"

    ```ts
    const latency = await client.query
      .aggregate("traces")
      .filter("service.name = 'checkout'")
      .count("calls")
      .percentile(99, "duration_nano", "p99")
      .groupBy("name")
      .orderBy("calls", "desc")
      .limit(10)
      .run();
    for (const row of latency.rows) console.log(row.groups.name, row.values.calls, row.values.p99);
    const perMinute = await client.query.aggregate("logs").count("n").timeSeries(60);
    ```

=== "Rust"

    ```rust
    use std::time::Duration;
    use grow_signoz::{AggregateBuilder, Signal};

    let q = client.query();
    let latency = q
        .aggregate(
            AggregateBuilder::new(Signal::Traces)
                .filter("service.name = 'checkout'")
                .count("calls")
                .percentile(99, "duration_nano", "p99")
                .group_by(["name"])
                .order_by("calls desc")
                .limit(10),
        )
        .await?;
    let per_minute = q
        .aggregate(AggregateBuilder::new(Signal::Logs).count("n").step(Duration::from_secs(60)))
        .await?
        .series;
    ```

=== "Go"

    ```go
    q := client.Query()
    latency, err := q.Aggregate(ctx, signoz.NewAggregate(signoz.SignalTraces).
        Where("service.name = 'checkout'").
        Count("calls").
        Percentile(99, "duration_nano", "p99").
        GroupBy("name").
        OrderBy("calls desc").
        Limit(10).
        Query())
    perMinute, err := q.Aggregate(ctx, signoz.NewAggregate(signoz.SignalLogs).Count("n").Step(time.Minute).Query())
    // perMinute.Series
    ```

=== "Python"

    ```python
    latency = (
        client.query.aggregate("traces")
        .filter("service.name = 'checkout'")
        .count("calls")
        .p99("duration_nano", "p99")
        .group_by("name")
        .order_by("calls", "desc")
        .limit(10)
        .run()
    )
    per_minute = client.query.aggregate("logs").count("n").time_series(60)
    ```

=== "Java"

    ```java
    AggregateResult latency =
        client.query()
            .aggregate(Signal.TRACES)
            .filter("service.name = 'checkout'")
            .count("calls")
            .percentile(99, "duration_nano", "p99")
            .groupBy("name")
            .orderBy("calls desc")
            .limit(10)
            .run();
    List<AggregateSeries> perMinute =
        client.query().aggregate(Signal.LOGS).count("n").step(Duration.ofMinutes(1)).run().series();
    ```

## `queryRange`: raw v5 requests

`queryRange(body)` posts any request body to `POST /api/v5/query_range` and returns the
envelope's `data` (undecoded JSON) plus the server `warnings`. Use it for what the typed helpers
do not cover: formulas, several queries at once, PromQL or ClickHouse SQL queries. Errors map
like every other call.

=== "TypeScript"

    ```ts
    const res = await client.query.queryRange({
      start: Date.now() - 3_600_000,
      end: Date.now(),
      requestType: "scalar",
      compositeQuery: { queries: [{ type: "builder_query",
        spec: { name: "A", signal: "logs", aggregations: [{ expression: "count()" }] } }] },
    });
    console.log(res.data, res.warnings);
    ```

=== "Rust"

    ```rust
    use serde_json::json;

    let res = client
        .query()
        .query_range(&json!({
            "start": start_ms, "end": end_ms, "requestType": "scalar",
            "compositeQuery": {"queries": [{"type": "builder_query",
                "spec": {"name": "A", "signal": "logs", "aggregations": [{"expression": "count()"}]}}]}
        }))
        .await?;
    println!("{} {:?}", res.data, res.warnings);
    ```

=== "Go"

    ```go
    res, err := client.Query().QueryRange(ctx, map[string]any{
        "start": start.UnixMilli(), "end": end.UnixMilli(), "requestType": "scalar",
        "compositeQuery": map[string]any{"queries": []any{map[string]any{
            "type": "builder_query",
            "spec": map[string]any{"name": "A", "signal": "logs", "aggregations": []any{map[string]any{"expression": "count()"}}},
        }}},
    })
    fmt.Println(string(res.Data), res.Warnings)
    ```

=== "Python"

    ```python
    res = client.query.query_range({
        "start": start_ms, "end": end_ms, "requestType": "scalar",
        "compositeQuery": {"queries": [{"type": "builder_query",
            "spec": {"name": "A", "signal": "logs", "aggregations": [{"expression": "count()"}]}}]},
    })
    print(res.data, res.warnings)
    ```

=== "Java"

    ```java
    QueryRangeResult res =
        client.query()
            .queryRange(
                Map.of(
                    "start", startMs, "end", endMs, "requestType", "scalar",
                    "compositeQuery",
                    Map.of("queries", List.of(Map.of(
                        "type", "builder_query",
                        "spec", Map.of("name", "A", "signal", "logs",
                            "aggregations", List.of(Map.of("expression", "count()"))))))));
    System.out.println(res.data() + " " + res.warnings());
    ```
