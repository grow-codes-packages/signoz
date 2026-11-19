// Created by sdk-kit 0.1.0; owned by this repository.
//! The SigNoz Rust SDK against a running SigNoz: health, an authenticated ping, sending logs
//! over OTLP, querying them back with a filter, tracing a request and reading the trace, and an
//! aggregation.
//!
//! ```sh
//! GROW_SIGNOZ_URL=http://127.0.0.1:8080 \
//! GROW_SIGNOZ_API_KEY=... \
//! GROW_SIGNOZ_OTLP_ENDPOINT=http://127.0.0.1:4318 cargo run
//! ```

use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use grow_signoz as sdk;
use opentelemetry::trace::{Span as _, SpanKind, Tracer as _};
use sdk::core::{ErrorInfo, Hooks};
use sdk::logging::{attrs, LogClient, LogQueryClient, WaitOptions};
use sdk::tracing::{TraceQueryClient, TracingConfig, WaitForTraceOptions};
use sdk::{AggregateBuilder, ErrorCode, FilterBuilder, LogOptions, Signal};

use sdk::SignozClient as Client;
use sdk::SignozConfig as Config;

/// Prints every retry.
struct RetryLogger;

impl Hooks for RetryLogger {
    fn on_error(&self, info: &ErrorInfo) {
        if info.will_retry {
            eprintln!(
                "retrying {} {} (attempt {})",
                info.method, info.url, info.attempt
            );
        }
    }
}

#[tokio::main]
async fn main() {
    if let Err(err) = run().await {
        eprintln!("error [{}]: {err}", err.code());
        std::process::exit(1);
    }
}

async fn run() -> sdk::Result<()> {
    // explicit > GROW_SIGNOZ_* > vendor variables > defaults; invalid values name the
    // variable, never its value.
    let config = Config::builder()
        .hooks(Arc::new(RetryLogger))
        .build_from_env()?;
    println!("config: {config:?}"); // the API key and ingestion key print as [REDACTED]

    let client = Client::new(config)?;
    let health = client.health().await?; // never fails for an unreachable service
    println!("health: {} ({})", health.healthy, health.status);

    match client.ping().await {
        Ok(latency) => println!("ping: {} ms", latency.as_millis()),
        Err(e) if e.code() == ErrorCode::Authentication => {
            println!("ping: check GROW_SIGNOZ_API_KEY ({e})");
            return Err(e);
        }
        Err(e) => return Err(e),
    }
    match client.server_version().await {
        Ok(version) => println!("server version: {version}"),
        Err(e) if e.code() == ErrorCode::Unsupported => {
            println!("server version: not reported by this image")
        }
        Err(e) => return Err(e),
    }

    // Logs: batched OTLP export to the collector, then query them back.
    let run = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos()
        .to_string();
    let logs = client.log_client(LogOptions::service("example-rust").environment("dev"))?;
    let logger = logs.logger(Some("example"), attrs! {"run" => run.as_str()});
    logger.info("checkout started", attrs! {"order.id" => 42});
    logger.error(
        "payment failed",
        attrs! {"order.id" => 42, "password" => "redacted-before-send"},
    );
    logs.flush(None).await?;

    let filter = FilterBuilder::new()
        .eq("service.name", "example-rust")
        .eq("run", run.as_str())
        .in_list("severity_text", ["ERROR", "FATAL"])
        .build()?;
    let q = client.query();
    let rec = q
        .wait_for_log(
            &filter,
            WaitOptions::default().timeout(Duration::from_secs(30)),
        )
        .await?;
    println!(
        "log: {} {:?} attrs={:?}",
        rec.severity_text, rec.body, rec.attributes
    );

    // Tracing: OpenTelemetry spans exported to the collector, then read back.
    let tracing = client.init_tracing(TracingConfig::new("example-rust").with_set_global(false))?;
    let tracer = tracing.tracer("example", None);
    let mut span = tracer
        .span_builder("GET /checkout")
        .with_kind(SpanKind::Server)
        .start(&tracer);
    let trace_id = span.span_context().trace_id().to_string();
    span.end();
    tracing.force_flush(None).await?;
    let tr = q
        .wait_for_trace(
            &trace_id,
            WaitForTraceOptions::default().timeout(Duration::from_secs(60)),
        )
        .await?;
    println!(
        "trace {}: {} span(s), root {:?}",
        tr.trace_id,
        tr.spans.len(),
        tr.spans[0].name
    );

    // Aggregation: calls and p99 latency per operation over the last hour.
    let agg = q
        .aggregate(
            AggregateBuilder::new(Signal::Traces)
                .filter("service.name = 'example-rust'")
                .count("calls")
                .percentile(99, "duration_nano", "p99")
                .group_by(["name"]),
        )
        .await?;
    for row in &agg.rows {
        println!(
            "operation {}: calls={} p99={}ns",
            row.groups["name"], row.values["calls"], row.values["p99"]
        );
    }
    client.close().await; // flushes log clients and tracing handles
    Ok(())
}
