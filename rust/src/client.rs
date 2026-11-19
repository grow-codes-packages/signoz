// Skeleton created by sdk-kit 0.1.0; owned by this repository (edit freely).
//! `SignozClient`: entry point of the SDK (SPEC §2.4).

use std::sync::Arc;
use std::time::{Duration, Instant};

use futures::future::BoxFuture;
use serde_json::Value;

use crate::config::{SignozConfig, API_KEY_HEADER};
use crate::core::lifecycle::CloseGroup;
use crate::core::{Error, ErrorCode, HealthStatus, HttpTransport, Result};
use crate::logging::{BatchingLogClient, LogClient as _};
use crate::otlp_logs::{new_log_client, LogOptions};
use crate::query::{check_not_html, SignozQueryClient};
use crate::tracing::{TracingConfig, TracingHandle};

/// Async client for SigNoz. Cheap to clone (clones share one connection pool).
///
/// The client talks to two endpoints: the query API ([`SignozConfig::url`], port 8080) for
/// [`health`](Self::health), [`ping`](Self::ping), [`server_version`](Self::server_version)
/// and [`query`](Self::query), and the OTLP collector ([`SignozConfig::otlp_endpoint`]) for
/// [`log_client`](Self::log_client) and [`init_tracing`](Self::init_tracing).
#[derive(Debug, Clone)]
pub struct SignozClient {
    inner: Arc<Inner>,
}

#[derive(Debug)]
struct Inner {
    config: SignozConfig,
    transport: Arc<HttpTransport>,
    query: SignozQueryClient,
    on_close: CloseGroup,
}

impl SignozClient {
    /// Creates a client from a configuration.
    pub fn new(config: SignozConfig) -> Result<Self> {
        config.validate()?;
        let transport = Arc::new(HttpTransport::new(config.common())?);
        for s in config.known_secrets() {
            transport.add_known_secret(&s);
        }
        let query = SignozQueryClient::new(Arc::clone(&transport), config.clone());
        Ok(SignozClient {
            inner: Arc::new(Inner {
                config,
                transport,
                query,
                on_close: CloseGroup::new(),
            }),
        })
    }

    /// Creates a client from the process environment.
    pub fn from_env() -> Result<Self> {
        Self::new(SignozConfig::from_env()?)
    }

    /// The configuration in use.
    pub fn config(&self) -> &SignozConfig {
        &self.inner.config
    }

    /// The HTTP transport of the query API (for service modules).
    pub fn transport(&self) -> &HttpTransport {
        &self.inner.transport
    }

    /// The query client (logs, traces, services, aggregations) of the SigNoz query API. It
    /// implements [`LogQueryClient`](crate::logging::LogQueryClient) and
    /// [`TraceQueryClient`](crate::tracing::TraceQueryClient).
    pub fn query(&self) -> &SignozQueryClient {
        &self.inner.query
    }

    /// The logging-category query client (search, tail, streams, labels, wait_for_log); the
    /// same object as [`query`](Self::query).
    pub fn logs(&self) -> Arc<dyn crate::logging::LogQueryClient> {
        Arc::new(self.inner.query.clone())
    }

    /// The tracing-category query client (get_trace, search_traces, services, operations,
    /// dependencies, wait_for_trace); the same object as [`query`](Self::query).
    pub fn traces(&self) -> Arc<dyn crate::tracing::TraceQueryClient> {
        Arc::new(self.inner.query.clone())
    }

    /// Registers work that [`SignozClient::close`] runs (flush, stop watchers, …).
    pub fn on_close<F>(&self, f: F)
    where
        F: FnOnce() -> BoxFuture<'static, ()> + Send + 'static,
    {
        self.inner.on_close.register(f);
    }

    /// Unauthenticated liveness check (`GET /api/v1/health?live=1`, which also checks
    /// ClickHouse). Never fails for an unhealthy or unreachable service; only `CANCELLED` and
    /// `CONFIG` are returned as errors.
    pub async fn health(&self) -> Result<HealthStatus> {
        let resp = self
            .transport()
            .get("/api/v1/health")
            .query("live", "1")
            .operation("health")
            .ok_status(500)
            .ok_status(503)
            .send()
            .await;
        let resp = match resp {
            Ok(resp) => resp,
            Err(err) => return HealthStatus::from_error(err),
        };
        let word = resp
            .json::<Value>()
            .ok()
            .and_then(|b| b.get("status").and_then(Value::as_str).map(str::to_owned));
        Ok(match word {
            Some(w) if resp.status() < 300 && w == "ok" => HealthStatus::healthy(w),
            Some(w) => {
                HealthStatus::unhealthy(w).with_detail("httpStatus", resp.status().to_string())
            }
            None => HealthStatus::unhealthy("unhealthy")
                .with_detail("httpStatus", resp.status().to_string()),
        })
    }

    /// Authenticated round trip (`GET /api/v1/fields/keys`); returns the latency. A missing or
    /// wrong API key fails with `AUTHENTICATION`.
    pub async fn ping(&self) -> Result<Duration> {
        let started = Instant::now();
        let mut req = self
            .transport()
            .get("/api/v1/fields/keys")
            .query("signal", "logs")
            .query("limit", "1")
            .operation("ping");
        if let Some(key) = self.config().api_key() {
            req = req.secret_header(API_KEY_HEADER, key);
        }
        let resp = req.send().await?;
        check_not_html("ping", &resp)?;
        Ok(started.elapsed())
    }

    /// Server version without a leading `v` (`GET /api/v1/version`). The official community
    /// image reports an empty version; that is `UNSUPPORTED`.
    pub async fn server_version(&self) -> Result<String> {
        let op = "serverVersion";
        let resp = self
            .transport()
            .get("/api/v1/version")
            .operation(op)
            .send()
            .await
            .map_err(|e| match e.code() {
                ErrorCode::NotFound => {
                    Error::unsupported(format!("{op}: the server exposes no version"))
                }
                _ => e,
            })?;
        check_not_html(op, &resp)?;
        let body: Value = resp
            .json()
            .map_err(|_| Error::unavailable(format!("{op}: invalid JSON response")))?;
        let version = body
            .get("version")
            .and_then(Value::as_str)
            .unwrap_or("")
            .trim();
        let version = version.strip_prefix('v').unwrap_or(version);
        if version.is_empty() {
            return Err(Error::unsupported(format!(
                "{op}: server does not report its version"
            )));
        }
        Ok(version.to_owned())
    }

    /// Starts a batching log client that exports OTLP logs to the SigNoz collector with this
    /// client's configuration (see [`new_log_client`]). [`close`](Self::close) also flushes and
    /// closes it. Must be called inside a tokio runtime.
    pub fn log_client(&self, opts: LogOptions) -> Result<BatchingLogClient> {
        self.ensure_open()?;
        let lc = new_log_client(&self.inner.config, opts)?;
        let tracked = lc.clone();
        self.on_close(move || {
            Box::pin(async move {
                let _ = tracked.close().await;
            })
        });
        Ok(lc)
    }

    /// Starts OpenTelemetry tracing that exports to the SigNoz collector (see
    /// [`init_tracing`](crate::init_tracing)); endpoint, protocol, TLS and the ingestion key
    /// header default to this client's configuration. [`close`](Self::close) also shuts the
    /// handle down. Must be called inside a tokio runtime.
    pub fn init_tracing(&self, tracing: TracingConfig) -> Result<TracingHandle> {
        self.ensure_open()?;
        let handle = crate::init_tracing::init_tracing_with_config(&self.inner.config, tracing)?;
        let tracked = handle.clone();
        self.on_close(move || {
            Box::pin(async move {
                let _ = tracked.shutdown().await;
            })
        });
        Ok(handle)
    }

    fn ensure_open(&self) -> Result<()> {
        if self.is_closed() {
            return Err(Error::cancelled("signoz: client is closed"));
        }
        Ok(())
    }

    /// Flushes and closes every log client and tracing handle created by this client, stops
    /// live tails, then closes the transport. Idempotent.
    pub async fn close(&self) {
        self.inner.on_close.close().await;
        self.inner.query.stop_tails();
        self.inner.transport.close();
    }

    /// Whether [`SignozClient::close`] was called.
    pub fn is_closed(&self) -> bool {
        self.inner.transport.is_closed()
    }
}
