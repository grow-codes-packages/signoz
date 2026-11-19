//! Helpers for the SigNoz service tests: recorded fixtures and mock-backed clients.
#![allow(dead_code)]

use std::time::Duration;

use grow_signoz as sdk;
use sdk::core::{RandomSource, RetryPolicy};
use sdk::{SignozClient, SignozConfig, SignozConfigBuilder};
use serde_json::Value;
use wiremock::{Request, ResponseTemplate};

/// A recorded response from `tests/fixtures/signoz/`.
pub fn fixture(name: &str) -> String {
    let path = format!(
        "{}/tests/fixtures/signoz/{name}",
        env!("CARGO_MANIFEST_DIR")
    );
    std::fs::read_to_string(&path).unwrap_or_else(|e| panic!("{path}: {e}"))
}

/// A recorded JSON fixture.
pub fn fixture_json(name: &str) -> Value {
    serde_json::from_str(&fixture(name)).unwrap()
}

/// `200 application/json` with a fixture body.
pub fn json_fixture(status: u16, name: &str) -> ResponseTemplate {
    ResponseTemplate::new(status).set_body_raw(fixture(name), "application/json")
}

/// `200 application/json` with a JSON value.
pub fn json_body(status: u16, v: Value) -> ResponseTemplate {
    ResponseTemplate::new(status).set_body_raw(v.to_string(), "application/json")
}

/// Retry policy with short backoffs.
pub fn fast() -> RetryPolicy {
    RetryPolicy {
        initial_backoff: Duration::from_millis(5),
        max_backoff: Duration::from_millis(20),
        ..RetryPolicy::default()
    }
    .with_random(RandomSource::fixed(0.0))
}

/// Builder pointing at `url` with the test API key.
pub fn builder(url: &str) -> SignozConfigBuilder {
    SignozConfig::builder()
        .url(url)
        .api_key("test-api-key-1234")
        .retry(fast())
}

/// Client pointing at `url` with the test API key.
pub fn client(url: &str) -> SignozClient {
    SignozClient::new(builder(url).build().unwrap()).unwrap()
}

/// The JSON body of a request.
pub fn body(req: &Request) -> Value {
    serde_json::from_slice(&req.body).unwrap_or(Value::Null)
}

/// The builder spec of a query_range request.
pub fn spec(req: &Request) -> Value {
    body(req)["compositeQuery"]["queries"][0]["spec"].clone()
}
