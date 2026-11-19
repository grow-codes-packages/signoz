// Created by sdk-kit 0.1.0; owned by this repository.
//! Conformance scenarios for `core`, `logging` and `tracing` against the real SigNoz stack
//! (Docker), plus the blocking client round trip.

use std::sync::Arc;

use grow_signoz as sdk;
use grow_signoz_integration as support;
use sdk::logging::LogClient;
use sdk::testing::logging::{LoggingHandler, LoggingTarget};
use sdk::testing::tracing::{TracingHandler, TracingTarget};
use sdk::testing::{ConformanceRunner, CoreHandler};
use sdk::tracing::TracingConfig;
use sdk::{LogOptions, SignozClient as Client, SignozConfig as Config};
use support::{conformance_dir, load_bootstrap_env};

use sdk::blocking::SignozClient as BlockingClient;

#[test]
fn blocking_round_trip() {
    load_bootstrap_env();
    let c = BlockingClient::from_env().expect("client");
    assert!(c.health().expect("health").healthy);
    c.ping().expect("ping");
    assert!(c
        .query()
        .list_labels()
        .expect("labels")
        .contains(&"body".to_owned()));
    c.close();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn conformance() {
    load_bootstrap_env();
    let service = format!("grow-conformance-rust-{}", sdk::testing::run_id());
    let config = Config::from_env().expect("config");
    let logging_config = config.clone();
    let tracing_config = config.clone();
    let tracing_service = service.clone();
    let runner = ConformanceRunner::new(conformance_dir())
        // "version" is left out: the community image reports an empty version, so
        // server_version() is UNSUPPORTED (docs/compatibility.md).
        .capabilities([
            "query",
            "tail",
            "labels",
            "streams",
            "services",
            "dependencies",
        ])
        .var("serviceName", service)
        .handler(CoreHandler::new(Client::from_env))
        .handler(LoggingHandler::new(move |batch| {
            let config = logging_config.clone();
            async move {
                let client = Client::new(config)?;
                let logs = client.log_client(LogOptions {
                    batch,
                    ..LogOptions::service("grow-conformance-rust")
                })?;
                let client_arc: Arc<dyn LogClient> = Arc::new(logs);
                Ok(LoggingTarget {
                    client: client_arc,
                    query: Some(client.logs()),
                })
            }
        }))
        .handler(TracingHandler::new(move || {
            let config = tracing_config.clone();
            let service = tracing_service.clone();
            async move {
                // The query client lists a service as soon as its spans are queryable (v5
                // aggregation over the spans table), so no warm-up is needed.
                let handle = sdk::init_tracing_with_config(
                    &config,
                    TracingConfig::new(service).with_set_global(false),
                )?;
                Ok(TracingTarget {
                    handle,
                    query: Some(Client::new(config)?.traces()),
                })
            }
        }));
    let report = runner.run().await.expect("scenarios load");
    println!("{report}");
    report.assert_success();
}
