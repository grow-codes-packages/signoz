// Skeleton created by sdk-kit 0.1.0; owned by this repository (edit freely).
//! # grow-signoz
//!
//! Rust SDK for [SigNoz](https://grow-codes-packages.github.io/signoz/) by Grow Codes.
//!
//! ```no_run
//! # async fn demo() -> grow_signoz::Result<()> {
//! use grow_signoz::logging::{attrs, LogClient};
//! use grow_signoz::{FilterBuilder, LogOptions, LogQuery, SignozClient, SignozConfig};
//!
//! let client = SignozClient::new(SignozConfig::from_env()?)?;
//! let health = client.health().await?;
//! println!("healthy: {}", health.healthy);
//!
//! // Ingest: OTLP logs to the SigNoz collector.
//! let logs = client.log_client(LogOptions::service("checkout"))?;
//! logs.logger(None, attrs! {}).info("order placed", attrs! { "order.id" => "o-1" });
//! logs.flush(None).await?;
//!
//! // Query: the SigNoz query API with SIGNOZ-API-KEY.
//! let filter = FilterBuilder::new().eq("service.name", "checkout").build()?;
//! let page = client.query().query_logs(LogQuery::new(filter)).await?;
//! println!("{} records", page.records.len());
//! client.close().await;
//! # Ok(())
//! # }
//! ```
//!
//! Ingest goes to the collector (`SignozConfig::otlp_endpoint`, OTLP/HTTP protobuf by default
//! or OTLP/gRPC): [`SignozClient::log_client`] and [`SignozClient::init_tracing`] /
//! [`init_tracing`]. Queries go to the query API (`SignozConfig::url`) with the
//! `SIGNOZ-API-KEY` header: [`SignozClient::query`] returns a [`SignozQueryClient`] with
//! `query_logs`, `tail`, `query_traces`, `get_trace`, `search_traces`, `list_services`,
//! `list_operations`, `get_dependencies`, `list_log_fields`, `list_field_values` and
//! `aggregate`. Filter expressions are built with [`FilterBuilder`], aggregations with
//! [`AggregateBuilder`].
//!
//! SigNoz is a trademark of SigNoz, Inc. This crate is not affiliated with or endorsed by
//! SigNoz, Inc.
//!
//! Features: `blocking` (a blocking client), `testing` (in-memory fakes and the conformance
//! runner), `tracing-layer` and `log-bridge` (logging adapters, on by default), `tower` and
//! `reqwest-middleware` (tracing instrumentation).
#![cfg_attr(docsrs, feature(doc_cfg))]

pub mod core;

mod aggregate;
mod client;
mod config;
mod filter;
mod http;
mod init_tracing;
mod otlp_logs;
mod query;
mod query_logs;
mod query_traces;
mod tail;

#[cfg(feature = "blocking")]
#[cfg_attr(docsrs, doc(cfg(feature = "blocking")))]
pub mod blocking;

#[cfg(feature = "testing")]
#[cfg_attr(docsrs, doc(cfg(feature = "testing")))]
pub mod testing;

pub mod logging;
pub mod tracing;

pub use crate::core::{Error, ErrorCode, HealthStatus, Result, RetryPolicy, Secret};

pub use crate::config::{
    API_KEY_HEADER, DEFAULT_OTLP_GRPC_ENDPOINT, DEFAULT_OTLP_HTTP_ENDPOINT, DEFAULT_URL,
    INGESTION_KEY_HEADER,
};

pub use crate::aggregate::{
    AggregateBuilder, AggregatePoint, AggregateQuery, AggregateResult, AggregateRow,
    AggregateSeries, Aggregation,
};
pub use crate::filter::{
    and_filters, escape_like_pattern, filter_literal, quote_filter_string, FilterBuilder,
    FilterOperator, FilterValue,
};
pub use crate::init_tracing::{
    create_span_exporter, init_tracing, init_tracing_with_config, tracing_config_for,
};
pub use crate::otlp_logs::{
    build_otlp_logs_request, new_log_client, LogOptions, OtlpLogExporter, LOG_SCOPE_NAME,
};
pub use crate::query::{
    Signal, SignozQueryClient, TimeRange, DEFAULT_QUERY_WINDOW, DEFAULT_SEARCH_LIMIT,
    DEFAULT_TRACE_SEARCH_LIMIT, MAX_QUERY_LIMIT,
};
pub use crate::query_logs::{
    FieldValuesOptions, ListFieldsOptions, LogField, LogQuery, LogQueryResult,
};
pub use crate::query_traces::{SpanQueryResult, TraceQuery};
pub use crate::tail::DEFAULT_TAIL_RECONNECT;

pub use crate::client::SignozClient;

pub use crate::config::SignozConfig;

pub use crate::config::SignozConfigBuilder;

/// Service-prefixed alias of [`Error`].
pub type SignozError = Error;
