//! Live tail (server-sent events) against a mock of `/api/v3/logs/livetail`.

mod common;
mod support;

use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, UNIX_EPOCH};

use common::{closed_port, Recorder};
use grow_signoz as sdk;
use sdk::core::ErrorCode;
use sdk::logging::{LogRecord, RecordCallback, TailOptions};
use support::{builder, client, fixture, json_fixture};
use wiremock::matchers::{header, method, path, query_param};
use wiremock::{Mock, MockServer, Request, ResponseTemplate};

const TAIL: &str = "/api/v3/logs/livetail";

fn sse(body: String) -> ResponseTemplate {
    ResponseTemplate::new(200).set_body_raw(body, "text/event-stream")
}

fn collector() -> (Arc<Mutex<Vec<LogRecord>>>, RecordCallback) {
    let seen = Arc::new(Mutex::new(Vec::new()));
    let s = seen.clone();
    let cb: RecordCallback = Arc::new(move |r| s.lock().unwrap().push(r));
    (seen, cb)
}

async fn wait_until(mut f: impl FnMut() -> bool) {
    let deadline = Instant::now() + Duration::from_secs(5);
    while !f() && Instant::now() < deadline {
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
}

fn opts(ms: u64) -> TailOptions {
    TailOptions {
        interval: Duration::from_millis(ms),
        start: Some(UNIX_EPOCH + Duration::from_millis(1_795_033_000_000)),
    }
}

#[tokio::test]
async fn tail_delivers_once_and_reconnects_from_last_timestamp() {
    let server = MockServer::start().await;
    let event = fixture("tail.sse.txt");
    let body = format!(
        ": keep-alive\r\n\nevent: error\ndata: boom test-api-key-1234\n\ndata: {{not json\n\ndata: {{\"x\":1}}\n\n{}{}data: {{\"data\":{{\"id\":\"second\",\"timestamp\":1795033285000000000,\"body\":\"multi\"}},\ndata: \"timestamp\":\"x\"}}",
        event.trim_end(),
        "\n\n",
    );
    Mock::given(method("GET"))
        .and(path(TAIL))
        .and(header("SIGNOZ-API-KEY", "test-api-key-1234"))
        .and(header("accept", "text/event-stream"))
        .and(query_param("filter", "body CONTAINS 'tail'"))
        .respond_with(sse(body))
        .mount(&server)
        .await;
    let recorder = Recorder::new();
    let c = sdk::SignozClient::new(
        builder(&server.uri())
            .hooks(recorder.clone())
            .header("x-extra", "1")
            .build()
            .unwrap(),
    )
    .unwrap();
    let (seen, cb) = collector();
    let handle = c
        .query()
        .tail("body CONTAINS 'tail'", cb, opts(30))
        .await
        .unwrap();
    let deadline = Instant::now() + Duration::from_secs(5);
    while server.received_requests().await.unwrap().len() < 3 && Instant::now() < deadline {
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
    handle.stop();
    handle.stop();
    let got = seen.lock().unwrap().clone();
    let bodies: Vec<&str> = got.iter().map(|r| r.body.as_str()).collect();
    assert_eq!(bodies, ["tail-fe67ae4b match", "multi"], "{got:?}");
    assert_eq!(got[0].resource["service.name"], "tail-svc-fe67ae4b");
    let reqs = server.received_requests().await.unwrap();
    let start = |r: &Request| {
        r.url
            .query_pairs()
            .find(|(k, _)| k == "start")
            .map(|(_, v)| v.into_owned())
            .unwrap()
    };
    assert_eq!(start(&reqs[0]), "1795033000000");
    assert_eq!(start(&reqs[1]), "1795033285000");
    assert!(reqs[0].headers.get("x-extra").is_some());
    let requests = recorder.requests();
    assert!(!requests.is_empty());
    assert_eq!(requests[0].headers["signoz-api-key"], "[REDACTED]");
    assert!(recorder.responses().iter().all(|r| r.status == 200));
    c.close().await;
}

#[tokio::test]
async fn tail_without_filter_and_panicking_callback() {
    let server = MockServer::start().await;
    Mock::given(path(TAIL))
        .respond_with(sse(fixture("tail.sse.txt")))
        .mount(&server)
        .await;
    let c = client(&server.uri());
    let calls = Arc::new(Mutex::new(0));
    let n = calls.clone();
    let cb: RecordCallback = Arc::new(move |_| {
        *n.lock().unwrap() += 1;
        panic!("callback failure must not stop the tail");
    });
    let handle = c
        .query()
        .tail(
            "",
            cb,
            TailOptions {
                interval: Duration::ZERO,
                start: None,
            },
        )
        .await
        .unwrap();
    wait_until(|| *calls.lock().unwrap() >= 1).await;
    assert_eq!(*calls.lock().unwrap(), 1);
    let req = &server.received_requests().await.unwrap()[0];
    assert!(!req.url.query_pairs().any(|(k, _)| k == "filter"));
    // closing the client stops running tails
    c.close().await;
    assert!(handle.is_stopped());
}

#[tokio::test]
async fn tail_connect_errors() {
    let server = MockServer::start().await;
    Mock::given(path(TAIL))
        .and(header("SIGNOZ-API-KEY", "test-api-key-1234"))
        .respond_with(ResponseTemplate::new(200).set_body_raw("<html>", "text/html"))
        .mount(&server)
        .await;
    Mock::given(path(TAIL))
        .respond_with(json_fixture(401, "ping.unauthenticated.json"))
        .mount(&server)
        .await;
    let recorder = Recorder::new();
    let ok_key = client(&server.uri());
    let (_, cb) = collector();
    let e = ok_key
        .query()
        .tail("x", cb.clone(), TailOptions::default())
        .await
        .unwrap_err();
    assert_eq!(e.code(), ErrorCode::Unsupported);
    let bad = sdk::SignozClient::new(
        builder(&server.uri())
            .api_key("wrong-key-9999")
            .hooks(recorder.clone())
            .build()
            .unwrap(),
    )
    .unwrap();
    let e = bad
        .query()
        .tail("x", cb.clone(), TailOptions::default())
        .await
        .unwrap_err();
    assert_eq!(e.code(), ErrorCode::Authentication);
    assert!(e.message().contains("unauthenticated"), "{e}");
    assert!(!e.message().contains("wrong-key-9999"));
    assert_eq!(recorder.errors().len(), 1);

    let down = client(&format!("http://127.0.0.1:{}", closed_port()));
    let e = down
        .query()
        .tail("x", cb.clone(), TailOptions::default())
        .await
        .unwrap_err();
    assert_eq!(e.code(), ErrorCode::Unavailable);

    let closed = client(&server.uri());
    closed.close().await;
    let e = closed
        .query()
        .tail("x", cb.clone(), TailOptions::default())
        .await
        .unwrap_err();
    assert_eq!(e.code(), ErrorCode::Cancelled);

    let bad_tls = sdk::SignozClient::new(
        builder(&server.uri())
            .common(|b| b.ca_cert_pem("not a pem"))
            .build()
            .unwrap(),
    );
    assert!(bad_tls.is_err());
}

#[tokio::test]
async fn tail_stops_after_non_retryable_reconnect_error() {
    let server = MockServer::start().await;
    Mock::given(path(TAIL))
        .respond_with(sse(fixture("tail.sse.txt")))
        .up_to_n_times(1)
        .mount(&server)
        .await;
    Mock::given(path(TAIL))
        .respond_with(ResponseTemplate::new(503))
        .up_to_n_times(1)
        .mount(&server)
        .await;
    Mock::given(path(TAIL))
        .respond_with(ResponseTemplate::new(403).set_body_raw("forbidden here", "text/plain"))
        .mount(&server)
        .await;
    let c = client(&server.uri());
    let (seen, cb) = collector();
    let handle = c.query().tail("x", cb, opts(20)).await.unwrap();
    wait_until(|| handle.is_stopped()).await;
    assert!(handle.is_stopped());
    assert_eq!(seen.lock().unwrap().len(), 1);
    assert_eq!(server.received_requests().await.unwrap().len(), 3);
}

#[tokio::test]
async fn tail_request_timeout() {
    let server = MockServer::start().await;
    Mock::given(path(TAIL))
        .respond_with(sse(String::new()).set_delay(Duration::from_secs(2)))
        .mount(&server)
        .await;
    let c = sdk::SignozClient::new(
        builder(&server.uri())
            .request_timeout(Duration::from_millis(100))
            .build()
            .unwrap(),
    )
    .unwrap();
    let (_, cb) = collector();
    let e = c
        .query()
        .tail("x", cb, TailOptions::default())
        .await
        .unwrap_err();
    assert_eq!(e.code(), ErrorCode::Timeout);
}

#[tokio::test]
async fn tail_through_the_trait_object() {
    let server = MockServer::start().await;
    Mock::given(path(TAIL))
        .respond_with(sse(fixture("tail.sse.txt")))
        .mount(&server)
        .await;
    let c = client(&server.uri());
    let (seen, cb) = collector();
    let handle = c.logs().tail("x", cb, opts(50)).await.unwrap();
    wait_until(|| !seen.lock().unwrap().is_empty()).await;
    handle.stop();
    assert_eq!(seen.lock().unwrap().len(), 1);
}

#[tokio::test]
async fn tail_http_client_honours_tls_and_proxy_settings() {
    let dir = format!("{}/tests/fixtures", env!("CARGO_MANIFEST_DIR"));
    let c = sdk::SignozClient::new(
        builder("http://signoz.invalid:8080")
            .tls(sdk::core::TlsConfig {
                ca_cert_path: Some(format!("{dir}/ca.pem").into()),
                ca_cert_pem: Some(std::fs::read_to_string(format!("{dir}/ca.pem")).unwrap()),
                client_cert_path: Some(format!("{dir}/client.pem").into()),
                client_key_path: Some(format!("{dir}/client.key").into()),
                insecure_skip_verify: true,
            })
            .proxy(format!("http://127.0.0.1:{}", closed_port()))
            .user_agent_suffix("it/1")
            .build()
            .unwrap(),
    )
    .unwrap();
    let (_, cb) = collector();
    let e = c
        .query()
        .tail("x", cb.clone(), TailOptions::default())
        .await
        .unwrap_err();
    assert_eq!(e.code(), ErrorCode::Unavailable);

    let server = MockServer::start().await;
    Mock::given(path(TAIL))
        .and(query_param("filter", "a"))
        .respond_with(
            ResponseTemplate::new(400)
                .set_body_raw(r#"{"error":"bad filter"}"#, "application/json"),
        )
        .mount(&server)
        .await;
    Mock::given(path(TAIL))
        .and(query_param("filter", "b"))
        .respond_with(ResponseTemplate::new(400).set_body_raw(r#"{"other":1}"#, "application/json"))
        .mount(&server)
        .await;
    Mock::given(path(TAIL))
        .respond_with(ResponseTemplate::new(502).set_body_raw("", "text/plain"))
        .mount(&server)
        .await;
    let c = client(&server.uri());
    let e = c
        .query()
        .tail("a", cb.clone(), TailOptions::default())
        .await
        .unwrap_err();
    assert_eq!(e.code(), ErrorCode::Validation);
    assert!(e.message().contains("bad filter"));
    let e = c
        .query()
        .tail("b", cb.clone(), TailOptions::default())
        .await
        .unwrap_err();
    assert_eq!(e.message(), "logs.tail: invalid request (HTTP 400)");
    let e = c
        .query()
        .tail("c", cb, TailOptions::default())
        .await
        .unwrap_err();
    assert_eq!(e.code(), ErrorCode::Unavailable);
    for bad in [
        sdk::core::TlsConfig {
            ca_cert_pem: Some("nope".into()),
            ..Default::default()
        },
        sdk::core::TlsConfig {
            ca_cert_path: Some("/nonexistent".into()),
            ..Default::default()
        },
    ] {
        // the core transport rejects these too, so the client cannot be built at all
        assert!(sdk::SignozClient::new(builder(&server.uri()).tls(bad).build().unwrap()).is_err());
    }
}
