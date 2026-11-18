// Skeleton created by sdk-kit 0.1.0; owned by this repository (edit freely).
//! # grow-signoz
//!
//! Rust SDK for [SigNoz](https://grow-codes-packages.github.io/signoz/) by Grow Codes.
//!
//! ```no_run
//! # async fn demo() -> grow_signoz::Result<()> {
//! use grow_signoz::{SignozClient, SignozConfig};
//!
//! let client = SignozClient::new(SignozConfig::from_env()?)?;
//! let health = client.health().await?;
//! println!("healthy: {}", health.healthy);
//! client.close().await;
//! # Ok(())
//! # }
//! ```
//!
//! Features: `blocking` (a blocking client), `testing` (in-memory fakes and the conformance
//! runner)
//! , `tracing-layer` and `log-bridge` (logging adapters, on by default)
//! , `tower` and `reqwest-middleware` (tracing instrumentation)
//! .
#![cfg_attr(docsrs, feature(doc_cfg))]

pub mod core;

mod client;
mod config;

#[cfg(feature = "blocking")]
#[cfg_attr(docsrs, doc(cfg(feature = "blocking")))]
pub mod blocking;

#[cfg(feature = "testing")]
#[cfg_attr(docsrs, doc(cfg(feature = "testing")))]
pub mod testing;

pub mod logging;
pub mod tracing;

pub use crate::core::{Error, ErrorCode, HealthStatus, Result, RetryPolicy, Secret};

pub use crate::config::DEFAULT_URL;

pub use crate::client::SignozClient;

pub use crate::config::SignozConfig;

pub use crate::config::SignozConfigBuilder;

/// Service-prefixed alias of [`Error`].
pub type SignozError = Error;
