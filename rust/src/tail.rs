//! Live tail (`GET /api/v3/logs/livetail`, server-sent events).

use std::collections::{HashSet, VecDeque};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::time::{Duration, Instant, SystemTime};

use serde_json::Value;

use crate::config::API_KEY_HEADER;
use crate::core::hooks::{guarded, ErrorInfo, RequestInfo, ResponseInfo};
use crate::core::redact::{redact_headers, redact_url, truncate};
use crate::core::transport::MAX_VENDOR_MESSAGE;
use crate::core::{Error, Result};
use crate::http::{build_http_client, status_error};
use crate::logging::{RecordCallback, TailHandle, TailOptions};
use crate::query::{millis, str_of, SignozQueryClient, LOG_TARGET};
use crate::query_logs::{log_record_from_row, row_millis};

/// Wait before a dropped live tail stream is reconnected (when `TailOptions::interval` is
/// zero).
pub const DEFAULT_TAIL_RECONNECT: Duration = Duration::from_secs(1);

/// Ids remembered for de-duplication across reconnects.
const MAX_TAIL_SEEN: usize = 10_000;

const OP: &str = "logs.tail";

/// One server-sent event being assembled.
#[derive(Default)]
struct SseEvent {
    name: String,
    data: String,
}

impl SseEvent {
    /// Feeds one line; returns the finished event (name, data) on a blank line.
    fn line(&mut self, raw: &str) -> Option<(String, String)> {
        let line = raw.trim_end_matches(['\n', '\r']);
        if line.is_empty() {
            return Some((
                std::mem::take(&mut self.name),
                std::mem::take(&mut self.data),
            ));
        }
        if let Some(v) = line.strip_prefix("event:") {
            self.name = v.trim().to_owned();
        } else if let Some(v) = line.strip_prefix("data:") {
            if !self.data.is_empty() {
                self.data.push('\n');
            }
            self.data.push_str(v.strip_prefix(' ').unwrap_or(v));
        }
        None
    }
}

struct TailState {
    q: SignozQueryClient,
    http: reqwest::Client,
    filter: String,
    on_record: RecordCallback,
    last_ms: i64,
    seen: HashSet<String>,
    seen_order: VecDeque<String>,
}

impl SignozQueryClient {
    /// Streams new log records matching `query` (a SigNoz filter expression; empty = all) from
    /// the live tail API and calls `on_record` for each, in arrival order, until the handle is
    /// stopped or the client is closed. The first connection is made before this returns, so a
    /// bad API key fails at once (`AUTHENTICATION`). A dropped stream is reconnected after
    /// `opts.interval` (default 1 s) from the last seen timestamp; records already delivered
    /// are skipped by id. `opts.start` is where tailing begins (default now).
    pub async fn tail(
        &self,
        query: &str,
        on_record: RecordCallback,
        opts: TailOptions,
    ) -> Result<TailHandle> {
        let common = self.inner.config.common();
        let http = build_http_client(common, common.tls())?;
        let interval = if opts.interval.is_zero() {
            DEFAULT_TAIL_RECONNECT
        } else {
            opts.interval
        };
        let state = TailState {
            q: self.clone(),
            http,
            filter: query.to_owned(),
            on_record,
            last_ms: millis(opts.start.unwrap_or_else(SystemTime::now)),
            seen: HashSet::new(),
            seen_order: VecDeque::new(),
        };
        let resp = state.connect().await?;
        let handle = TailHandle::new();
        {
            let mut tails = self.inner.tails.lock().unwrap_or_else(|e| e.into_inner());
            tails.retain(|t| !t.is_stopped());
            tails.push(handle.clone());
        }
        let h = handle.clone();
        tokio::spawn(async move { state.run(resp, h, interval).await });
        Ok(handle)
    }
}

impl TailState {
    async fn run(mut self, mut resp: reqwest::Response, handle: TailHandle, interval: Duration) {
        loop {
            tokio::select! {
                _ = handle.stopped() => return,
                _ = self.read(&mut resp) => {}
            }
            loop {
                tokio::select! {
                    _ = handle.stopped() => return,
                    _ = tokio::time::sleep(interval) => {}
                }
                match self.connect().await {
                    Ok(r) => {
                        resp = r;
                        break;
                    }
                    Err(e) => {
                        ::tracing::warn!(target: LOG_TARGET, error = %e, "signoz live tail reconnect failed");
                        if !e.is_retryable() {
                            handle.stop();
                            return;
                        }
                    }
                }
            }
        }
    }

    /// Opens the event stream from `last_ms`.
    async fn connect(&self) -> Result<reqwest::Response> {
        let transport = self.q.transport();
        if transport.is_closed() {
            return Err(Error::cancelled(format!("{OP}: client is closed")));
        }
        let config = &self.q.inner.config;
        let common = config.common();
        let mut url = common.url().clone();
        let base = url.path().trim_end_matches('/').to_owned();
        url.set_path(&format!("{base}/api/v3/logs/livetail"));
        {
            let mut pairs = url.query_pairs_mut();
            pairs.append_pair("start", &self.last_ms.to_string());
            if !self.filter.trim().is_empty() {
                pairs.append_pair("filter", &self.filter);
            }
        }
        let mut headers: Vec<(String, String)> = vec![
            ("user-agent".into(), transport.user_agent().to_owned()),
            ("accept".into(), "text/event-stream".into()),
        ];
        headers.extend(common.headers().iter().cloned());
        if let Some(key) = config.api_key() {
            headers.push((API_KEY_HEADER.into(), key.expose_secret().to_owned()));
        }
        let safe_url = transport.redact(&redact_url(url.as_str()));
        let hooks = common.hooks().cloned();
        if let Some(h) = &hooks {
            let info = RequestInfo {
                method: "GET".into(),
                url: safe_url.clone(),
                headers: redact_headers(headers.iter().map(|(k, v)| (k, v)))
                    .into_iter()
                    .map(|(k, v)| (k, transport.redact(&v)))
                    .collect(),
                attempt: 1,
            };
            guarded(|| h.on_request(&info));
        }
        let fail = |err: Error| {
            if let Some(h) = &hooks {
                let info = ErrorInfo {
                    method: "GET".into(),
                    url: safe_url.clone(),
                    error: err.clone(),
                    attempt: 1,
                    will_retry: false,
                    retry_delay: None,
                };
                guarded(|| h.on_error(&info));
            }
            err
        };
        let mut req = self.http.get(url);
        for (k, v) in &headers {
            req = req.header(k, v);
        }
        let started = Instant::now();
        let resp = match tokio::time::timeout(common.request_timeout(), req.send()).await {
            Err(_) => {
                return Err(fail(Error::timeout(format!(
                    "{OP}: request timed out after {} ms",
                    common.request_timeout().as_millis()
                ))))
            }
            Ok(Err(e)) => {
                return Err(fail(
                    Error::unavailable(format!("{OP}: connection failed"))
                        .with_source(e.without_url()),
                ))
            }
            Ok(Ok(r)) => r,
        };
        let status = resp.status().as_u16();
        if let Some(h) = &hooks {
            let info = ResponseInfo {
                method: "GET".into(),
                url: safe_url.clone(),
                status,
                duration: started.elapsed(),
                attempt: 1,
                request_id: None,
            };
            guarded(|| h.on_response(&info));
        }
        if status >= 300 {
            let body = resp.bytes().await.unwrap_or_default();
            return Err(fail(status_error(OP, status, &body, |t| {
                transport.redact(t)
            })));
        }
        let ct = resp
            .headers()
            .get("content-type")
            .and_then(|v| v.to_str().ok())
            .unwrap_or("")
            .to_ascii_lowercase();
        if !ct.contains("text/event-stream") {
            return Err(fail(
                Error::unsupported(format!(
                    "{OP}: endpoint not available on this server version"
                ))
                .with_status(status),
            ));
        }
        Ok(resp)
    }

    /// Dispatches the events of one connection until it ends.
    async fn read(&mut self, resp: &mut reqwest::Response) {
        let mut buf: Vec<u8> = Vec::new();
        let mut event = SseEvent::default();
        while let Ok(Some(chunk)) = resp.chunk().await {
            buf.extend_from_slice(&chunk);
            while let Some(pos) = buf.iter().position(|b| *b == b'\n') {
                let line: Vec<u8> = buf.drain(..=pos).collect();
                if let Some((name, data)) = event.line(&String::from_utf8_lossy(&line)) {
                    self.dispatch(&name, &data);
                }
            }
        }
        if !buf.is_empty() {
            let _ = event.line(&String::from_utf8_lossy(&buf));
        }
        if !event.data.is_empty() {
            self.dispatch(&event.name, &event.data);
        }
    }

    fn dispatch(&mut self, event: &str, data: &str) {
        if data.is_empty() {
            return;
        }
        if event == "error" {
            let text = self
                .q
                .transport()
                .redact(&truncate(data, MAX_VENDOR_MESSAGE));
            ::tracing::warn!(target: LOG_TARGET, error = %text, "signoz live tail error event");
            return;
        }
        let Ok(row) = serde_json::from_str::<Value>(data) else {
            return;
        };
        let Some(d) = row.get("data").filter(|d| d.is_object()) else {
            return;
        };
        let id = str_of(d, "id");
        if !id.is_empty() {
            if !self.seen.insert(id.clone()) {
                return;
            }
            self.seen_order.push_back(id);
            if self.seen_order.len() > MAX_TAIL_SEEN {
                if let Some(old) = self.seen_order.pop_front() {
                    self.seen.remove(&old);
                }
            }
        }
        self.last_ms = self.last_ms.max(row_millis(&row));
        let record = log_record_from_row(&row);
        let cb = &self.on_record;
        let _ = catch_unwind(AssertUnwindSafe(|| cb(record)));
    }
}
