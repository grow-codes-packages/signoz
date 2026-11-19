// Skeleton created by sdk-kit 0.1.0; owned by this repository (edit freely).
//! Blocking wrapper (feature `blocking`). Each client owns a small tokio runtime (one worker
//! thread, so log batching and span export keep running between calls).
//!
//! Do not use it from inside an async runtime (calls would panic); use the async client there.

use std::future::Future;
use std::time::Duration;

use crate::core::{Error, HealthStatus, Result};
use crate::logging::{
    AttrValue, BatchingLogClient, LogClient as _, LogQueryClient as _, LogRecord, LogSearchResult,
    SearchOptions, WaitOptions,
};
use crate::tracing::{
    DependencyLink, DependencyOptions, Trace, TraceQueryClient as _, TraceSearchQuery,
    TraceSummary, TracingConfig, TracingHandle, WaitForTraceOptions,
};
use crate::{
    AggregateQuery, AggregateResult, FieldValuesOptions, ListFieldsOptions, LogField, LogOptions,
    LogQuery, LogQueryResult, SignozClient as AsyncClient, SignozConfig, SignozQueryClient,
    SpanQueryResult, TimeRange, TraceQuery,
};

/// Blocking client for SigNoz with the same methods as the async client.
#[derive(Debug)]
pub struct SignozClient {
    inner: AsyncClient,
    runtime: tokio::runtime::Runtime,
}

impl SignozClient {
    /// Creates a client from a configuration.
    pub fn new(config: SignozConfig) -> Result<Self> {
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(1)
            .thread_name("grow-signoz-blocking")
            .enable_all()
            .build()
            .map_err(|e| Error::config("cannot start the tokio runtime").with_source(e))?;
        let inner = {
            let _guard = runtime.enter();
            AsyncClient::new(config)?
        };
        Ok(SignozClient { inner, runtime })
    }

    /// Creates a client from the process environment.
    pub fn from_env() -> Result<Self> {
        Self::new(SignozConfig::from_env()?)
    }

    /// The wrapped async client.
    pub fn async_client(&self) -> &AsyncClient {
        &self.inner
    }

    /// Runs a future of the async API to completion on this client's runtime.
    pub fn block_on<F: Future>(&self, future: F) -> F::Output {
        self.runtime.block_on(future)
    }

    /// See [`AsyncClient::health`].
    pub fn health(&self) -> Result<HealthStatus> {
        self.block_on(self.inner.health())
    }

    /// See [`AsyncClient::ping`].
    pub fn ping(&self) -> Result<Duration> {
        self.block_on(self.inner.ping())
    }

    /// See [`AsyncClient::server_version`].
    pub fn server_version(&self) -> Result<String> {
        self.block_on(self.inner.server_version())
    }

    /// Blocking query client (see [`SignozQueryClient`]).
    pub fn query(&self) -> QueryClient<'_> {
        QueryClient { client: self }
    }

    /// See [`AsyncClient::log_client`]. Use [`flush_logs`](Self::flush_logs) /
    /// [`close_logs`](Self::close_logs) to flush it from blocking code.
    pub fn log_client(&self, opts: LogOptions) -> Result<BatchingLogClient> {
        let _guard = self.runtime.enter();
        self.inner.log_client(opts)
    }

    /// Exports everything queued in `logs` now.
    pub fn flush_logs(&self, logs: &BatchingLogClient, timeout: Option<Duration>) -> Result<()> {
        self.block_on(logs.flush(timeout))
    }

    /// Flushes and closes `logs`.
    pub fn close_logs(&self, logs: &BatchingLogClient) -> Result<()> {
        self.block_on(logs.close())
    }

    /// See [`AsyncClient::init_tracing`].
    pub fn init_tracing(&self, tracing: TracingConfig) -> Result<TracingHandle> {
        let _guard = self.runtime.enter();
        self.inner.init_tracing(tracing)
    }

    /// Flushes `handle` (see [`TracingHandle::force_flush`]).
    pub fn flush_tracing(&self, handle: &TracingHandle, timeout: Option<Duration>) -> Result<()> {
        self.block_on(handle.force_flush(timeout))
    }

    /// See [`AsyncClient::close`]. Idempotent.
    pub fn close(&self) {
        self.block_on(self.inner.close());
    }
}

impl Drop for SignozClient {
    fn drop(&mut self) {
        self.inner.transport().close();
    }
}

/// Blocking view of [`SignozQueryClient`]; get it from [`SignozClient::query`].
#[derive(Debug, Clone, Copy)]
pub struct QueryClient<'a> {
    client: &'a SignozClient,
}

impl QueryClient<'_> {
    fn q(&self) -> &SignozQueryClient {
        self.client.inner.query()
    }

    /// See [`SignozQueryClient::query_range`].
    pub fn query_range(&self, body: &serde_json::Value) -> Result<crate::QueryRangeResult> {
        self.client.block_on(self.q().query_range(body))
    }
    /// See [`SignozQueryClient::query_logs`].
    pub fn query_logs(&self, query: LogQuery) -> Result<LogQueryResult> {
        self.client.block_on(self.q().query_logs(query))
    }
    /// See [`SignozQueryClient::search`].
    pub fn search(&self, query: &str, opts: SearchOptions) -> Result<LogSearchResult> {
        self.client.block_on(self.q().search(query, opts))
    }
    /// See [`LogQueryClient::wait_for_log`](crate::logging::LogQueryClient::wait_for_log).
    pub fn wait_for_log(&self, query: &str, opts: WaitOptions) -> Result<LogRecord> {
        self.client.block_on(self.q().wait_for_log(query, opts))
    }
    /// See [`SignozQueryClient::list_log_fields`].
    pub fn list_log_fields(&self, opts: ListFieldsOptions) -> Result<Vec<LogField>> {
        self.client.block_on(self.q().list_log_fields(opts))
    }
    /// See [`SignozQueryClient::list_field_values`].
    pub fn list_field_values(
        &self,
        name: &str,
        opts: FieldValuesOptions,
    ) -> Result<Vec<AttrValue>> {
        self.client.block_on(self.q().list_field_values(name, opts))
    }
    /// See [`SignozQueryClient::list_labels`].
    pub fn list_labels(&self) -> Result<Vec<String>> {
        self.client.block_on(self.q().list_labels())
    }
    /// See [`SignozQueryClient::list_streams`].
    pub fn list_streams(&self) -> Result<Vec<String>> {
        self.client.block_on(self.q().list_streams())
    }
    /// See [`SignozQueryClient::aggregate`].
    pub fn aggregate(&self, query: impl Into<AggregateQuery>) -> Result<AggregateResult> {
        self.client.block_on(self.q().aggregate(query))
    }
    /// See [`SignozQueryClient::query_traces`].
    pub fn query_traces(&self, query: TraceQuery) -> Result<SpanQueryResult> {
        self.client.block_on(self.q().query_traces(query))
    }
    /// See [`SignozQueryClient::get_trace`].
    pub fn get_trace(&self, trace_id: &str) -> Result<Option<Trace>> {
        self.client.block_on(self.q().get_trace(trace_id))
    }
    /// See [`TraceQueryClient::wait_for_trace`](crate::tracing::TraceQueryClient::wait_for_trace).
    pub fn wait_for_trace(&self, trace_id: &str, opts: WaitForTraceOptions) -> Result<Trace> {
        self.client
            .block_on(self.q().wait_for_trace(trace_id, opts))
    }
    /// See [`SignozQueryClient::search_traces`].
    pub fn search_traces(&self, query: &TraceSearchQuery) -> Result<Vec<TraceSummary>> {
        self.client.block_on(self.q().search_traces(query))
    }
    /// See [`SignozQueryClient::list_services`].
    pub fn list_services(&self) -> Result<Vec<String>> {
        self.client.block_on(self.q().list_services())
    }
    /// See [`SignozQueryClient::list_services_in_range`].
    pub fn list_services_in_range(&self, range: TimeRange) -> Result<Vec<String>> {
        self.client.block_on(self.q().list_services_in_range(range))
    }
    /// See [`SignozQueryClient::list_operations`].
    pub fn list_operations(&self, service: &str) -> Result<Vec<String>> {
        self.client.block_on(self.q().list_operations(service))
    }
    /// See [`SignozQueryClient::list_operations_in_range`].
    pub fn list_operations_in_range(&self, service: &str, range: TimeRange) -> Result<Vec<String>> {
        self.client
            .block_on(self.q().list_operations_in_range(service, range))
    }
    /// See [`SignozQueryClient::get_dependencies`].
    pub fn get_dependencies(&self, opts: DependencyOptions) -> Result<Vec<DependencyLink>> {
        self.client.block_on(self.q().get_dependencies(opts))
    }
}
