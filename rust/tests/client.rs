//! `SignozClient` core surface against a mock of the SigNoz query API (taken over from the
//! sdk-kit skeleton test: the demo routes do not exist on SigNoz).

mod common;
mod support;

use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::Arc;
use std::time::Duration;

use common::closed_port;
use grow_signoz as sdk;
use sdk::core::ErrorCode;
use sdk::tracing::TracingConfig;
use sdk::{LogOptions, SignozClient as Client, SignozConfig as Config};
use serde_json::json;
use support::{builder, client, json_body, json_fixture};
use wiremock::matchers::{header, method, path, query_param};
use wiremock::{Mock, MockServer, ResponseTemplate};

async fn signoz_server() -> MockServer {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/api/v1/health"))
        .and(query_param("live", "1"))
        .respond_with(json_fixture(200, "health.ok.json"))
        .mount(&server)
        .await;
    Mock::given(path("/api/v1/fields/keys"))
        .and(query_param("signal", "logs"))
        .and(query_param("limit", "1"))
        .and(header("SIGNOZ-API-KEY", "test-api-key-1234"))
        .respond_with(json_fixture(200, "listLogFields.search.json"))
        .mount(&server)
        .await;
    Mock::given(path("/api/v1/fields/keys"))
        .respond_with(json_fixture(401, "ping.unauthenticated.json"))
        .mount(&server)
        .await;
    Mock::given(path("/api/v1/version"))
        .respond_with(json_body(200, json!({"version": "v0.151.0", "ee": "Y"})))
        .mount(&server)
        .await;
    server
}

#[tokio::test]
async fn client_surface() {
    let server = signoz_server().await;
    let c = client(&server.uri());
    let h = c.health().await.unwrap();
    assert!(h.healthy);
    assert_eq!(h.status, "ok");
    assert!(c.ping().await.unwrap() < Duration::from_secs(5));
    assert_eq!(c.server_version().await.unwrap(), "0.151.0");
    assert_eq!(
        c.config().api_key().unwrap().expose_secret(),
        "test-api-key-1234"
    );
    assert!(format!("{c:?}").contains("[REDACTED]"));
    assert!(!format!("{c:?}").contains("test-api-key-1234"));
    let ua = server.received_requests().await.unwrap()[0]
        .headers
        .get("user-agent")
        .unwrap()
        .to_str()
        .unwrap()
        .to_owned();
    assert!(ua.starts_with("grow-codes-signoz/"), "{ua}");
    assert!(ua.ends_with("(rust)"));

    let bad = Client::new(
        builder(&server.uri())
            .api_key("wrong-key-0000")
            .build()
            .unwrap(),
    )
    .unwrap();
    let err = bad.ping().await.unwrap_err();
    assert_eq!(err.code(), ErrorCode::Authentication);
    assert!(!err.message().contains("wrong-key-0000"));
    let none = Client::new(Config::builder().url(server.uri()).build().unwrap()).unwrap();
    assert_eq!(
        none.ping().await.unwrap_err().code(),
        ErrorCode::Authentication
    );
}

#[tokio::test]
async fn client_ping_rejects_html() {
    let server = MockServer::start().await;
    Mock::given(path("/api/v1/fields/keys"))
        .respond_with(ResponseTemplate::new(200).set_body_raw("<!DOCTYPE html>", "text/html"))
        .mount(&server)
        .await;
    let e = client(&server.uri()).ping().await.unwrap_err();
    assert_eq!(e.code(), ErrorCode::Unsupported);
}

#[tokio::test]
async fn client_health_variants() {
    let server = MockServer::start().await;
    Mock::given(path("/a/api/v1/health"))
        .respond_with(json_body(503, json!({"status": "degraded"})))
        .mount(&server)
        .await;
    Mock::given(path("/b/api/v1/health"))
        .respond_with(ResponseTemplate::new(500))
        .mount(&server)
        .await;
    Mock::given(path("/c/api/v1/health"))
        .respond_with(ResponseTemplate::new(404))
        .mount(&server)
        .await;
    Mock::given(path("/d/api/v1/health"))
        .respond_with(json_body(200, json!({"status": "starting"})))
        .mount(&server)
        .await;
    let health = |p: &str| {
        let c = client(&format!("{}/{p}", server.uri()));
        async move { c.health().await.unwrap() }
    };
    let a = health("a").await;
    assert_eq!((a.healthy, a.status.as_str()), (false, "degraded"));
    assert_eq!(a.details["httpStatus"], "503");
    let b = health("b").await;
    assert_eq!((b.healthy, b.status.as_str()), (false, "unhealthy"));
    let c = health("c").await;
    assert_eq!((c.healthy, c.status.as_str()), (false, "unhealthy"));
    let d = health("d").await;
    assert_eq!((d.healthy, d.status.as_str()), (false, "starting"));
}

#[tokio::test]
async fn core_health_unreachable() {
    let c = client(&format!("http://127.0.0.1:{}", closed_port()));
    let h = c.health().await.unwrap();
    assert!(!h.healthy);
    assert_eq!(h.status, "unreachable");
    assert!(h.details.contains_key("error"));
    c.close().await;
    assert_eq!(c.health().await.unwrap_err().code(), ErrorCode::Cancelled);
}

#[tokio::test]
async fn client_server_version_variants() {
    let server = MockServer::start().await;
    Mock::given(path("/a/api/v1/version"))
        .respond_with(ResponseTemplate::new(404))
        .mount(&server)
        .await;
    Mock::given(path("/b/api/v1/version"))
        .respond_with(json_fixture(200, "serverVersion.ok.json"))
        .mount(&server)
        .await;
    Mock::given(path("/c/api/v1/version"))
        .respond_with(ResponseTemplate::new(403))
        .mount(&server)
        .await;
    Mock::given(path("/d/api/v1/version"))
        .respond_with(ResponseTemplate::new(200).set_body_raw("<html></html>", "text/html"))
        .mount(&server)
        .await;
    Mock::given(path("/e/api/v1/version"))
        .respond_with(ResponseTemplate::new(200).set_body_raw("nope", "application/json"))
        .mount(&server)
        .await;
    Mock::given(path("/f/api/v1/version"))
        .respond_with(json_body(200, json!({"version": "1.2.3"})))
        .mount(&server)
        .await;
    let version = |p: &str| {
        let c = client(&format!("{}/{p}", server.uri()));
        async move { c.server_version().await }
    };
    assert_eq!(
        version("a").await.unwrap_err().code(),
        ErrorCode::Unsupported
    );
    let b = version("b").await.unwrap_err();
    assert_eq!(b.code(), ErrorCode::Unsupported);
    assert!(b.message().contains("does not report"));
    assert_eq!(
        version("c").await.unwrap_err().code(),
        ErrorCode::PermissionDenied
    );
    assert_eq!(
        version("d").await.unwrap_err().code(),
        ErrorCode::Unsupported
    );
    assert_eq!(
        version("e").await.unwrap_err().code(),
        ErrorCode::Unavailable
    );
    assert_eq!(version("f").await.unwrap(), "1.2.3");
}

#[tokio::test]
async fn core_close_idempotent() {
    let server = signoz_server().await;
    let c = client(&server.uri());
    let closed = Arc::new(AtomicU32::new(0));
    let counter = closed.clone();
    c.on_close(move || {
        Box::pin(async move {
            counter.fetch_add(1, Ordering::SeqCst);
        })
    });
    let lc = c.log_client(LogOptions::service("svc")).unwrap();
    let handle = c
        .init_tracing(TracingConfig::new("svc").with_set_global(false))
        .unwrap();
    assert!(!c.is_closed());
    c.close().await;
    c.close().await;
    assert!(c.is_closed());
    assert!(handle.is_shutdown());
    assert_eq!(sdk::logging::LogClient::stats(&lc).dropped, 0);
    assert_eq!(closed.load(Ordering::SeqCst), 1);
    assert_eq!(c.ping().await.unwrap_err().code(), ErrorCode::Cancelled);
    assert!(c.transport().is_closed());
    assert_eq!(
        c.log_client(LogOptions::default()).unwrap_err().code(),
        ErrorCode::Cancelled
    );
    assert_eq!(
        c.init_tracing(TracingConfig::new("x")).unwrap_err().code(),
        ErrorCode::Cancelled
    );
}

#[tokio::test]
async fn client_config_errors() {
    let err = Config::builder().url("mailto:x").build().unwrap_err();
    assert_eq!(err.code(), ErrorCode::Config);
    let cfg = Config::builder()
        .ca_cert_path("/nonexistent/ca.pem")
        .build()
        .unwrap();
    assert_eq!(Client::new(cfg).unwrap_err().code(), ErrorCode::Config);
    let _ = Client::from_env();
}
