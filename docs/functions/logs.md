# Log queries

All log queries go to the query API with the `SIGNOZ-API-KEY` header. Filters are SigNoz search
expressions (`service.name = 'checkout' AND severity_text IN ('ERROR')`); build them safely with
the [`FilterBuilder`](filter.md). Bare words search the body.

| Function | Request | Defaults and behaviour |
| - | - | - |
| `queryLogs` | `POST /api/v5/query_range`, `requestType: raw`, signal `logs` | window last hour, limit 100 (max 10,000), order `desc` (ties by `id`), optional `selectFields`; returns normalized records, `nextCursor` and `warnings` |
| `search` | same as `queryLogs` | the shared `LogQueryClient` form: `search(query, {start, end, limit, order, cursor})` → `LogSearchResult` |
| `tail` | `GET /api/v3/logs/livetail` (server-sent events) | calls `onRecord` per new record; reconnects after the stream ends (default 1 s) from the last seen timestamp and skips records already delivered (by id); `AUTHENTICATION`, `PERMISSION_DENIED` and `UNSUPPORTED` stop it after `onError` |
| `listStreams` | `GET /api/v1/fields/values?signal=logs&name=service.name` | services that sent logs (SigNoz has no streams; `service.name` is the natural stream) |
| `listLabels` | `GET /api/v1/fields/keys?signal=logs` | attribute and resource field names |
| `listLogFields` | `GET /api/v1/fields/keys?signal=logs` | `LogField {name, context, dataType}`; optional `search` text and `limit` |
| `listFieldValues` | `GET /api/v1/fields/values` | values of one field (`source` logs or traces, optional `search` text) |
| `waitForLog` | polls `search` | test helper: first record matching `query` (and an optional predicate) within the timeout (default 30 s), else `TIMEOUT` |

## Paging: the SDK's own keyset cursor

The server's `nextCursor` is the last row's millisecond and skips the other rows of that
millisecond. The SDK pages with its own cursor instead: the next page repeats the boundary
millisecond and excludes the ids already returned in it (`id NOT IN (...)`), so no row is lost
or repeated. The cursor is opaque but has the **same wire format in every language**:

```
base64url (no padding) of {"v":1,"s":<startMs>,"e":<endMs>,"o":"asc|desc","m":<boundaryMs>,"x":[ids]}
```

`"x"` may be `null` or missing (Go writes `null` when no ids are excluded); every language accepts
both. A cursor fixes the window and the order of the first page; pass the same filter with it.

=== "TypeScript"

    ```ts
    import { FilterBuilder } from "@grow-codes/signoz";

    const filter = new FilterBuilder().eq("service.name", "checkout").in("severity_text", ["ERROR", "FATAL"]);
    let cursor: string | undefined;
    do {
      const page = await client.query.queryLogs({ filter, limit: 500, ...(cursor ? { cursor } : {}) });
      for (const r of page.records) console.log(r.timestamp, r.severityText, r.body);
      cursor = page.nextCursor;
    } while (cursor !== undefined);

    const hits = await client.logs.search("timeout", { limit: 20 });
    const handle = client.logs.tail("severity_text = 'ERROR'", (r) => console.log(r.body));
    handle.stop();
    console.log(await client.logs.listStreams(), await client.logs.listLabels());
    console.log(await client.query.listLogFields({ search: "http" }));
    console.log(await client.query.listFieldValues("service.name"));
    const one = await client.logs.waitForLog("body CONTAINS 'order 42'", { timeoutMs: 30_000 });
    ```

=== "Rust"

    ```rust
    use std::sync::Arc;
    use grow_signoz::logging::{LogQueryClient, LogRecord, SearchOptions, TailOptions, WaitOptions};
    use grow_signoz::{FieldValuesOptions, FilterBuilder, ListFieldsOptions, LogQuery};

    let q = client.query();
    let filter = FilterBuilder::new()
        .eq("service.name", "checkout")
        .in_list("severity_text", ["ERROR", "FATAL"])
        .build()?;
    let mut cursor = None;
    loop {
        let mut lq = LogQuery::new(filter.as_str()).limit(500);
        if let Some(c) = cursor.take() {
            lq = lq.cursor(c);
        }
        let page = q.query_logs(lq).await?;
        for r in &page.records {
            println!("{} {}", r.severity_text, r.body);
        }
        match page.next_cursor {
            Some(c) => cursor = Some(c),
            None => break,
        }
    }
    let hits = q.search("timeout", SearchOptions::default()).await?;
    let handle = q
        .tail(
            "severity_text = 'ERROR'",
            Arc::new(|r: LogRecord| println!("{}", r.body)),
            TailOptions::default(),
        )
        .await?;
    handle.stop();
    let streams = q.list_streams().await?;
    let fields = q.list_log_fields(ListFieldsOptions::default()).await?;
    let values = q.list_field_values("service.name", FieldValuesOptions::default()).await?;
    let one = q.wait_for_log("body CONTAINS 'order 42'", WaitOptions::default()).await?;
    ```

=== "Go"

    ```go
    q := client.Query()
    filter, err := signoz.NewFilter().Eq("service.name", "checkout").In("severity_text", "ERROR", "FATAL").Build()
    if err != nil {
        return err
    }
    cursor := ""
    for {
        page, err := q.QueryLogs(ctx, signoz.LogQuery{Filter: filter, Limit: 500, Cursor: cursor})
        if err != nil {
            return err
        }
        for _, r := range page.Records {
            fmt.Println(r.Timestamp, r.SeverityText, r.Body)
        }
        if cursor = page.NextCursor; cursor == "" {
            break
        }
    }
    hits, err := q.Search(ctx, "timeout", signoz.SearchOptions{Limit: 20})
    handle, err := q.Tail(ctx, "severity_text = 'ERROR'", func(r signoz.LogRecord) { fmt.Println(r.Body) }, signoz.TailOptions{})
    handle.Stop()
    streams, err := q.ListStreams(ctx)
    fields, err := q.ListLogFields(ctx, signoz.ListFieldsOptions{})
    values, err := q.ListFieldValues(ctx, "service.name", signoz.FieldValuesOptions{})
    rec, err := q.WaitForLog(ctx, "body CONTAINS 'order 42'", signoz.WaitForLogOptions{Timeout: 30 * time.Second})
    ```

=== "Python"

    ```python
    flt = sdk.FilterBuilder().eq("service.name", "checkout").in_("severity_text", ["ERROR", "FATAL"])
    cursor = None
    while True:
        page = client.query.query_logs(flt, limit=500, cursor=cursor)
        for r in page.records:
            print(r.timestamp, r.severity_text, r.body)
        if (cursor := page.next_cursor) is None:
            break

    hits = client.logs.search("timeout", limit=20)
    handle = client.logs.tail("severity_text = 'ERROR'", lambda r: print(r.body))
    handle.stop()
    print(client.logs.list_streams(), client.logs.list_labels())
    print(client.query.list_log_fields(search="http"), client.query.list_field_values("service.name"))
    one = client.logs.wait_for_log("body CONTAINS 'order 42'", timeout_s=30)
    ```

=== "Java"

    ```java
    SignozQueryClient q = client.query();
    String filter =
        FilterBuilder.create().eq("service.name", "checkout").in("severity_text", "ERROR", "FATAL").build();
    String cursor = null;
    do {
      LogQueryResult page = q.queryLogs(LogQuery.builder().filter(filter).limit(500).cursor(cursor).build());
      page.records().forEach(r -> System.out.println(r.severityText() + " " + r.body()));
      cursor = page.nextCursor().orElse(null);
    } while (cursor != null);

    LogSearchResult hits = q.search("timeout", SearchOptions.builder().limit(20).build());
    TailHandle handle = q.tail("severity_text = 'ERROR'", r -> System.out.println(r.body()), TailOptions.defaults());
    handle.stop();
    List<String> streams = q.listStreams();
    List<LogField> fields = q.listLogFields();
    List<Object> values = q.listFieldValues("service.name", FieldValuesOptions.defaults());
    LogRecord one = q.waitForLog("body CONTAINS 'order 42'", WaitForLogOptions.of(Duration.ofSeconds(30), Duration.ofSeconds(1)));
    ```

## Record mapping

Rows are normalized to the shared `LogRecord`: `timestamp` (nanoseconds), `severity` from
`severity_number` (else parsed from `severity_text`, else **INFO**), `severityText` (the server
text, else the severity name), `body`, `attributes` (string, number and bool attribute maps
merged), `resource` (`resources_string`), `traceId`/`spanId` (lower-case hex) and `traceFlags`.
TypeScript records also carry the SigNoz row `id` and `timeUnixNano` (a decimal string, exact
beyond 2^53).
