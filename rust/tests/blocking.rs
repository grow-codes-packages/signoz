//! Blocking client (feature `blocking`).
#![cfg(feature = "blocking")]

use std::io::{Read, Write};
use std::net::TcpListener;

use grow_signoz as sdk;
use sdk::core::ErrorCode;

use sdk::blocking::SignozClient as Client;

use sdk::SignozConfig as Config;

/// Minimal HTTP server on a thread (no async runtime involved).
fn serve(responses: Vec<&'static str>) -> String {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let addr = listener.local_addr().unwrap();
    std::thread::spawn(move || {
        for body in responses {
            let (mut stream, _) = listener.accept().unwrap();
            let mut buf = [0u8; 4096];
            let _ = stream.read(&mut buf);
            let (status, json) = body.split_once(' ').unwrap();
            let resp = format!(
                "HTTP/1.1 {status} X\r\ncontent-type: application/json\r\ncontent-length: {}\r\nconnection: close\r\n\r\n{json}",
                json.len()
            );
            stream.write_all(resp.as_bytes()).unwrap();
        }
    });
    format!("http://{addr}")
}

#[test]
fn blocking_client() {
    let url = serve(vec![
        r#"200 {"status":"ok"}"#,
        r#"200 {"pong":true}"#,
        r#"200 {"version":"2.0.0"}"#,
    ]);
    let cfg = Config::builder()
        .url(url)
        .api_key("t-1234")
        .build()
        .unwrap();
    let c = Client::new(cfg).unwrap();
    assert!(c.health().unwrap().healthy);
    c.ping().unwrap();
    assert_eq!(c.server_version().unwrap(), "2.0.0");
    assert!(!c.async_client().is_closed());
    assert_eq!(c.block_on(async { 7 }), 7);
    c.close();
    c.close();
    assert_eq!(c.ping().unwrap_err().code(), ErrorCode::Cancelled);
    let _ = Client::from_env();
    let bad = Config::builder()
        .ca_cert_path("/nonexistent")
        .build()
        .unwrap();
    assert_eq!(Client::new(bad).unwrap_err().code(), ErrorCode::Config);
}

mod support;

#[test]
fn blocking_query_logs_and_tracing() {
    use sdk::logging::{SearchOptions, WaitOptions};
    use sdk::tracing::{DependencyOptions, TraceSearchQuery, TracingConfig, WaitForTraceOptions};
    use sdk::{
        AggregateBuilder, FieldValuesOptions, ListFieldsOptions, LogOptions, LogQuery, Signal,
        TimeRange, TraceQuery,
    };
    use serde_json::json;
    use support::{json_body, json_fixture};
    use wiremock::matchers::{method, path};
    use wiremock::{Mock, MockServer, Request};

    let rt = tokio::runtime::Runtime::new().unwrap();
    let server = rt.block_on(async {
        let server = MockServer::start().await;
        let qr = |kind: &'static str, fixture: &'static str| {
            Mock::given(path("/api/v5/query_range"))
                .and(move |r: &Request| support::body(r)["requestType"] == json!(kind))
                .respond_with(json_fixture(200, fixture))
        };
        qr("raw", "queryLogs.raw.ok.json").mount(&server).await;
        qr("scalar", "listServices.scalar.json")
            .mount(&server)
            .await;
        qr("trace", "searchTraces.traceMode.json")
            .mount(&server)
            .await;
        Mock::given(path("/api/v1/fields/keys"))
            .respond_with(json_fixture(200, "listLogFields.search.json"))
            .mount(&server)
            .await;
        Mock::given(path("/api/v1/fields/values"))
            .respond_with(json_fixture(200, "listStreams.fieldValues.json"))
            .mount(&server)
            .await;
        Mock::given(method("POST"))
            .and(path(
                "/api/v4/traces/fe67ae4bfe67ae4bfe67ae4bfe67ae4b/waterfall",
            ))
            .respond_with(json_fixture(200, "getTrace.waterfall.ok.json"))
            .mount(&server)
            .await;
        Mock::given(path("/api/v1/dependency_graph"))
            .respond_with(json_fixture(200, "getDependencies.ok.json"))
            .mount(&server)
            .await;
        Mock::given(path("/v1/logs"))
            .respond_with(json_body(200, json!({"partialSuccess": {}})))
            .mount(&server)
            .await;
        Mock::given(path("/v1/traces"))
            .respond_with(wiremock::ResponseTemplate::new(200))
            .mount(&server)
            .await;
        server
    });
    let c = Client::new(
        support::builder(&server.uri())
            .otlp_endpoint(server.uri())
            .build()
            .unwrap(),
    )
    .unwrap();
    let q = c.query();
    let id = "fe67ae4bfe67ae4bfe67ae4bfe67ae4b";
    assert_eq!(q.query_logs(LogQuery::default()).unwrap().records.len(), 6);
    let raw = q
        .query_range(&json!({"start": 1, "end": 2, "requestType": "raw"}))
        .unwrap();
    assert!(raw.data.pointer("/data/results").is_some());
    assert_eq!(
        q.search("x", SearchOptions::default())
            .unwrap()
            .records
            .len(),
        6
    );
    assert!(q.wait_for_log("x", WaitOptions::default()).is_ok());
    assert_eq!(
        q.list_log_fields(ListFieldsOptions::default())
            .unwrap()
            .len(),
        1
    );
    assert_eq!(q.list_labels().unwrap(), vec!["run.id"]);
    assert_eq!(
        q.list_field_values("service.name", FieldValuesOptions::default())
            .unwrap()
            .len(),
        4
    );
    assert_eq!(q.list_streams().unwrap().len(), 4);
    assert!(!q
        .aggregate(
            AggregateBuilder::new(Signal::Traces)
                .count("n")
                .group_by(["service.name"])
        )
        .unwrap()
        .rows
        .is_empty());
    assert_eq!(
        q.query_traces(TraceQuery::default()).unwrap().spans.len(),
        6
    );
    assert_eq!(q.get_trace(id).unwrap().unwrap().spans.len(), 3);
    assert_eq!(
        q.wait_for_trace(id, WaitForTraceOptions::default())
            .unwrap()
            .spans
            .len(),
        3
    );
    assert_eq!(
        q.search_traces(&TraceSearchQuery::default()).unwrap().len(),
        1
    );
    assert!(!q.list_services().unwrap().is_empty());
    assert!(!q
        .list_services_in_range(TimeRange::default())
        .unwrap()
        .is_empty());
    assert!(q.list_operations("svc").is_ok());
    assert!(q
        .list_operations_in_range("svc", TimeRange::default())
        .is_ok());
    assert_eq!(
        q.get_dependencies(DependencyOptions::default())
            .unwrap()
            .len(),
        2
    );

    let logs = c.log_client(LogOptions::service("blocking")).unwrap();
    sdk::logging::LogClient::send(&logs, "from blocking code".into());
    c.flush_logs(&logs, None).unwrap();
    c.close_logs(&logs).unwrap();
    let tracing = c
        .init_tracing(TracingConfig::new("blocking").with_set_global(false))
        .unwrap();
    {
        use opentelemetry::trace::{Span as _, Tracer as _};
        tracing.tracer("it", None).start("blocking-span").end();
    }
    c.flush_tracing(&tracing, None).unwrap();
    c.close();
    let paths: Vec<String> = rt
        .block_on(server.received_requests())
        .unwrap()
        .iter()
        .map(|r| r.url.path().to_owned())
        .collect();
    assert!(paths.iter().any(|p| p == "/v1/logs"));
    assert!(paths.iter().any(|p| p == "/v1/traces"));
}
