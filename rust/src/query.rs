//! [`SignozQueryClient`]: reads logs, traces, services and aggregations from the SigNoz query
//! API (`POST /api/v5/query_range` and friends) with the `SIGNOZ-API-KEY` header.

use std::fmt;
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use serde_json::{json, Map, Value};

use crate::config::{SignozConfig, API_KEY_HEADER};
use crate::core::{Error, HttpTransport, Method, Response, Result};
use crate::logging::{Attributes, TailHandle};

/// Time window of a query without an explicit start: the hour before the end (the end
/// defaults to now).
pub const DEFAULT_QUERY_WINDOW: Duration = Duration::from_secs(3600);

/// Largest row limit the SigNoz query API accepts.
pub const MAX_QUERY_LIMIT: usize = 10_000;

/// Default page size of raw logs and spans queries.
pub const DEFAULT_SEARCH_LIMIT: usize = 100;

/// Default number of traces returned by `search_traces`.
pub const DEFAULT_TRACE_SEARCH_LIMIT: usize = 20;

pub(crate) const LOG_TARGET: &str = "grow_signoz::query";

/// A SigNoz telemetry signal for the query builder.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
pub enum Signal {
    /// Logs (default).
    #[default]
    Logs,
    /// Traces (spans).
    Traces,
}

impl Signal {
    /// Wire value: `logs` or `traces`.
    pub fn as_str(self) -> &'static str {
        match self {
            Signal::Logs => "logs",
            Signal::Traces => "traces",
        }
    }
}

/// A query time window. `None` means the default: end = now, start = end − 1 h.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct TimeRange {
    /// Window start (inclusive).
    pub start: Option<SystemTime>,
    /// Window end (exclusive).
    pub end: Option<SystemTime>,
}

impl TimeRange {
    /// A window from `start` to now.
    pub fn since(start: SystemTime) -> Self {
        TimeRange {
            start: Some(start),
            end: None,
        }
    }

    /// A window from `start` to `end`.
    pub fn between(start: SystemTime, end: SystemTime) -> Self {
        TimeRange {
            start: Some(start),
            end: Some(end),
        }
    }

    /// Resolves the defaults against the clock.
    pub(crate) fn resolve(self) -> (SystemTime, SystemTime) {
        let end = self.end.unwrap_or_else(SystemTime::now);
        let start = self
            .start
            .unwrap_or_else(|| end.checked_sub(DEFAULT_QUERY_WINDOW).unwrap_or(UNIX_EPOCH));
        (start, end)
    }
}

/// Query client of the SigNoz query API. Get it from
/// [`SignozClient::query`](crate::SignozClient::query); cheap to clone. It implements
/// [`LogQueryClient`](crate::logging::LogQueryClient) and
/// [`TraceQueryClient`](crate::tracing::TraceQueryClient).
#[derive(Clone)]
pub struct SignozQueryClient {
    pub(crate) inner: Arc<QueryInner>,
}

pub(crate) struct QueryInner {
    pub(crate) transport: Arc<HttpTransport>,
    pub(crate) config: SignozConfig,
    pub(crate) tails: Mutex<Vec<TailHandle>>,
}

impl fmt::Debug for SignozQueryClient {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("SignozQueryClient")
            .field("url", &self.inner.config.url().as_str())
            .finish_non_exhaustive()
    }
}

impl SignozQueryClient {
    pub(crate) fn new(transport: Arc<HttpTransport>, config: SignozConfig) -> Self {
        SignozQueryClient {
            inner: Arc::new(QueryInner {
                transport,
                config,
                tails: Mutex::new(Vec::new()),
            }),
        }
    }

    pub(crate) fn transport(&self) -> &HttpTransport {
        &self.inner.transport
    }

    /// Stops every running tail (client close).
    pub(crate) fn stop_tails(&self) {
        let tails =
            std::mem::take(&mut *self.inner.tails.lock().unwrap_or_else(|e| e.into_inner()));
        for t in tails {
            t.stop();
        }
    }

    /// Performs an authenticated, idempotent request and returns the JSON body (unwrapped
    /// from the `{"status","data"}` envelope when `wrapped`).
    pub(crate) async fn call(
        &self,
        op: &str,
        method: Method,
        path: &str,
        query: &[(&str, String)],
        body: Option<&Value>,
        wrapped: bool,
    ) -> Result<Value> {
        let mut req = self
            .transport()
            .request(method, path)
            .operation(op)
            .idempotent(true);
        for (k, v) in query {
            req = req.query(*k, v.clone());
        }
        if let Some(key) = self.inner.config.api_key() {
            req = req.secret_header(API_KEY_HEADER, key);
        }
        if let Some(b) = body {
            req = req.json(b);
        }
        let resp = req.send().await?;
        check_not_html(op, &resp)?;
        let value: Value = serde_json::from_slice(resp.bytes()).map_err(|_| invalid_json(op))?;
        if !wrapped {
            return Ok(value);
        }
        match value.get("status").and_then(Value::as_str) {
            None | Some("success") => {}
            Some(other) => {
                return Err(
                    Error::unavailable(format!("{op}: unexpected response status {other}"))
                        .with_status(resp.status()),
                )
            }
        }
        Ok(value.get("data").cloned().unwrap_or(Value::Null))
    }

    /// Posts one builder query (`POST /api/v5/query_range`) and returns its first result plus
    /// the server warnings (also logged at warn level).
    pub(crate) async fn query_range(
        &self,
        op: &str,
        request_type: &str,
        start: SystemTime,
        end: SystemTime,
        mut spec: Map<String, Value>,
    ) -> Result<(Option<Value>, Vec<String>)> {
        if end <= start {
            return Err(Error::validation(format!("{op}: start must be before end")));
        }
        spec.entry("name").or_insert_with(|| json!("A"));
        let body = json!({
            "start": millis(start),
            "end": millis(end),
            "requestType": request_type,
            "compositeQuery": {"queries": [{"type": "builder_query", "spec": spec}]},
        });
        let data = self
            .call(
                op,
                Method::POST,
                "/api/v5/query_range",
                &[],
                Some(&body),
                true,
            )
            .await?;
        let mut warnings = Vec::new();
        if let Some(w) = data.get("warning").filter(|w| w.is_object()) {
            if let Some(list) = w.get("warnings").and_then(Value::as_array) {
                warnings.extend(
                    list.iter()
                        .filter_map(|x| x.get("message").and_then(Value::as_str))
                        .map(str::to_owned),
                );
            }
            if warnings.is_empty() {
                if let Some(m) = w.get("message").and_then(Value::as_str) {
                    warnings.push(m.to_owned());
                }
            }
            warnings = warnings
                .into_iter()
                .map(|m| self.transport().redact(&m))
                .collect();
            for m in &warnings {
                ::tracing::warn!(target: LOG_TARGET, operation = op, warning = %m, "signoz query warning");
            }
        }
        let first = data
            .pointer("/data/results")
            .and_then(Value::as_array)
            .and_then(|r| r.first())
            .cloned();
        Ok((first, warnings))
    }
}

/// Maps the SPA index page (served with 200 for unknown routes) to `UNSUPPORTED`.
pub(crate) fn check_not_html(op: &str, resp: &Response) -> Result<()> {
    let ct = resp
        .header("content-type")
        .unwrap_or("")
        .to_ascii_lowercase();
    let body = resp.bytes();
    let start = body
        .iter()
        .position(|b| !b.is_ascii_whitespace())
        .map(|i| &body[i..])
        .unwrap_or(&[]);
    let lower: Vec<u8> = start.iter().take(5).map(u8::to_ascii_lowercase).collect();
    if ct.contains("text/html") || start.starts_with(b"<!") || lower.starts_with(b"<html") {
        return Err(Error::unsupported(format!(
            "{op}: endpoint not available on this server version"
        ))
        .with_status(resp.status()));
    }
    Ok(())
}

pub(crate) fn invalid_json(op: &str) -> Error {
    Error::unavailable(format!("{op}: invalid JSON response"))
}

/// `limit` 0 → `default`; outside `1..=MAX_QUERY_LIMIT` → `VALIDATION`.
pub(crate) fn check_limit(op: &str, limit: usize, default: usize) -> Result<usize> {
    match limit {
        0 => Ok(default),
        n if n > MAX_QUERY_LIMIT => Err(Error::validation(format!(
            "{op}: limit must be between 1 and {MAX_QUERY_LIMIT}"
        ))),
        n => Ok(n),
    }
}

/// Milliseconds since the epoch (0 before it).
pub(crate) fn millis(t: SystemTime) -> i64 {
    t.duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

/// Nanoseconds since the epoch (0 before it).
pub(crate) fn nanos(t: SystemTime) -> i64 {
    t.duration_since(UNIX_EPOCH)
        .map(|d| d.as_nanos() as i64)
        .unwrap_or(0)
}

pub(crate) fn from_millis(ms: i64) -> SystemTime {
    UNIX_EPOCH + Duration::from_millis(ms.max(0) as u64)
}

pub(crate) fn filter_spec(expr: &str) -> Option<Value> {
    let t = expr.trim();
    (!t.is_empty()).then(|| json!({ "expression": expr }))
}

// Helpers to read loosely typed row values.

pub(crate) fn str_of(m: &Value, key: &str) -> String {
    match m.get(key) {
        Some(Value::String(s)) => s.clone(),
        Some(Value::Number(n)) => n.to_string(),
        Some(Value::Bool(b)) => b.to_string(),
        _ => String::new(),
    }
}

pub(crate) fn num_of(m: &Value, key: &str) -> Option<i64> {
    match m.get(key)? {
        Value::Number(n) => n
            .as_i64()
            .or_else(|| n.as_u64().map(|u| u.min(i64::MAX as u64) as i64))
            .or_else(|| n.as_f64().map(|f| f as i64)),
        Value::String(s) => s.trim().parse().ok(),
        _ => None,
    }
}

pub(crate) fn attrs_of(maps: &[Option<&Value>]) -> Attributes {
    let mut out = Attributes::new();
    for m in maps.iter().flatten() {
        if let Some(obj) = m.as_object() {
            for (k, v) in obj {
                if !v.is_null() {
                    out.insert(k.clone(), v.clone());
                }
            }
        }
    }
    out
}

/// Text form of a value for sorting / grouping.
pub(crate) fn text_of(v: &Value) -> String {
    match v {
        Value::String(s) => s.clone(),
        other => other.to_string(),
    }
}
