# grow-signoz (Rust)

Rust SDK for SigNoz by [Grow Codes](https://grow.codes): OTLP log and trace ingest to the SigNoz
collector, and a query client for the SigNoz query API (logs, live tail, traces, services,
operations, dependencies, fields, aggregations). Async on tokio, with an optional blocking
client.

```toml
[dependencies]
grow-signoz = "0.1.0"
# blocking client:   features = ["blocking"]
# test fakes/runner: features = ["testing"] (dev-dependency)
```

```rust,no_run
use grow_signoz::logging::{attrs, LogClient};
use grow_signoz::{FilterBuilder, LogOptions, LogQuery, SignozClient, SignozConfig};

#[tokio::main]
async fn main() -> grow_signoz::Result<()> {
    // explicit > GROW_SIGNOZ_* > SIGNOZ_* / OTEL_EXPORTER_OTLP_* > defaults
    let client = SignozClient::new(SignozConfig::from_env()?)?;
    println!("{:?}", client.health().await?);

    let logs = client.log_client(LogOptions::service("checkout"))?;
    logs.logger(None, attrs! {}).info("order placed", attrs! { "order.id" => 42 });
    logs.flush(None).await?;

    let filter = FilterBuilder::new().eq("service.name", "checkout").build()?;
    let page = client.query().query_logs(LogQuery::new(filter)).await?;
    println!("{} records", page.records.len());
    client.close().await; // flushes log clients and tracing handles
    Ok(())
}
```

| Variable | Meaning | Default |
| - | - | - |
| `GROW_SIGNOZ_URL` / `SIGNOZ_URL` | query API | `http://127.0.0.1:8080` |
| `GROW_SIGNOZ_API_KEY` / `SIGNOZ_API_KEY` | service-account key (`SIGNOZ-API-KEY`) | none |
| `GROW_SIGNOZ_OTLP_ENDPOINT` / `SIGNOZ_OTLP_ENDPOINT` / `OTEL_EXPORTER_OTLP_ENDPOINT` | collector | `http://127.0.0.1:4318` (`:4317` for gRPC) |
| `GROW_SIGNOZ_OTLP_PROTOCOL` / `OTEL_EXPORTER_OTLP_PROTOCOL` | `http/protobuf` or `grpc` | `http/protobuf` |
| `GROW_SIGNOZ_INGESTION_KEY` / `SIGNOZ_INGESTION_KEY` | SigNoz Cloud ingestion key | none |

- Errors: one `Error` enum with `code()`, `http_status()`, `request_id()`, `is_retryable()`,
  `retry_after()`; messages never contain credentials.
- Retries with full-jitter backoff and `Retry-After`, connect/request/total timeouts,
  cancellation, request hooks, `tracing` debug events.
- Paging uses the SDK's own cursor (the server's `nextCursor` skips rows of the boundary
  millisecond).
- gRPC over TLS (`https` endpoints) uses the system roots plus the configured CA and client
  certificate; `insecure_skip_verify` applies to HTTP only.
- MSRV 1.88. Licence Apache-2.0. Documentation: https://grow-codes-packages.github.io/signoz/

Development: `make deps build lint test coverage license pack docs` (see `Makefile`).
