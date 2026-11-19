// Skeleton created by sdk-kit 0.1.0; owned by this repository (edit freely).
//! `SignozConfig`: common settings (SPEC §2.2) plus the SigNoz query key, OTLP collector
//! endpoint, protocol and SigNoz Cloud ingestion key.

use std::fmt;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use crate::core::{
    CommonConfig, CommonConfigBuilder, EnvReader, Error, Hooks, Result, RetryPolicy, Secret,
    TlsConfig,
};
use crate::tracing::Protocol;

/// Query API endpoint used when neither the builder nor the environment sets one
/// (SigNoz UI + API port 8080).
pub const DEFAULT_URL: &str = "http://127.0.0.1:8080";

/// Default OTLP/HTTP endpoint of the SigNoz collector.
pub const DEFAULT_OTLP_HTTP_ENDPOINT: &str = "http://127.0.0.1:4318";

/// Default OTLP/gRPC endpoint of the SigNoz collector.
pub const DEFAULT_OTLP_GRPC_ENDPOINT: &str = "http://127.0.0.1:4317";

/// Header that carries the service-account API key on query calls (redacted everywhere).
pub const API_KEY_HEADER: &str = "SIGNOZ-API-KEY";

/// Header / gRPC metadata that carries the SigNoz Cloud ingestion key on exports.
pub const INGESTION_KEY_HEADER: &str = "signoz-ingestion-key";

/// Vendor variables checked (in order) after `GROW_SIGNOZ_URL`.
const VENDOR_URL_VARS: &[&str] = &["SIGNOZ_URL"];
/// Vendor variables checked (in order) after `GROW_SIGNOZ_API_KEY`.
const VENDOR_API_KEY_VARS: &[&str] = &["SIGNOZ_API_KEY"];
/// Vendor variables checked (in order) after `GROW_SIGNOZ_INGESTION_KEY`.
const VENDOR_INGESTION_KEY_VARS: &[&str] = &["SIGNOZ_INGESTION_KEY"];
/// Vendor variables checked (in order) after `GROW_SIGNOZ_OTLP_ENDPOINT`.
const VENDOR_OTLP_ENDPOINT_VARS: &[&str] = &["SIGNOZ_OTLP_ENDPOINT", "OTEL_EXPORTER_OTLP_ENDPOINT"];
/// Vendor variables checked (in order) after `GROW_SIGNOZ_OTLP_PROTOCOL`.
const VENDOR_OTLP_PROTOCOL_VARS: &[&str] = &["OTEL_EXPORTER_OTLP_PROTOCOL"];

/// Immutable client configuration; build it with [`SignozConfig::builder`] or
/// [`SignozConfig::from_env`]. `Debug` output redacts every credential.
///
/// Query calls ([`SignozQueryClient`](crate::SignozQueryClient), `ping`) go to
/// [`url`](Self::url) with the [`api_key`](Self::api_key); ingest (the OTLP log exporter and
/// [`init_tracing`](crate::init_tracing)) goes to the collector at
/// [`otlp_endpoint`](Self::otlp_endpoint). An ingest-only client needs no API key.
#[derive(Clone)]
pub struct SignozConfig {
    common: CommonConfig,
    api_key: Option<Secret>,
    otlp_endpoint: String,
    otlp_protocol: Protocol,
    ingestion_key: Option<Secret>,
}

impl fmt::Debug for SignozConfig {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("SignozConfig")
            .field("common", &self.common)
            .field("api_key", &self.api_key)
            .field(
                "otlp_endpoint",
                &crate::core::redact::redact_url(&self.otlp_endpoint),
            )
            .field("otlp_protocol", &self.otlp_protocol.as_str())
            .field("ingestion_key", &self.ingestion_key)
            .finish()
    }
}

impl SignozConfig {
    /// A builder with nothing set.
    pub fn builder() -> SignozConfigBuilder {
        SignozConfigBuilder::default()
    }

    /// Configuration from the process environment
    /// (explicit > `GROW_SIGNOZ_*` > vendor variables > defaults).
    pub fn from_env() -> Result<Self> {
        Self::builder().build_from_env()
    }

    /// Settings shared by every Grow Codes SDK.
    pub fn common(&self) -> &CommonConfig {
        &self.common
    }

    /// Query API base URL (default [`DEFAULT_URL`]).
    pub fn url(&self) -> &url::Url {
        self.common.url()
    }

    /// Service-account API key sent as `SIGNOZ-API-KEY` on query calls, when configured.
    pub fn api_key(&self) -> Option<&Secret> {
        self.api_key.as_ref()
    }

    /// OTLP collector endpoint for logs and traces.
    pub fn otlp_endpoint(&self) -> &str {
        &self.otlp_endpoint
    }

    /// OTLP protocol: [`Protocol::HttpProtobuf`] (default) or [`Protocol::Grpc`].
    pub fn otlp_protocol(&self) -> Protocol {
        self.otlp_protocol
    }

    /// SigNoz Cloud ingestion key, when configured.
    pub fn ingestion_key(&self) -> Option<&Secret> {
        self.ingestion_key.as_ref()
    }

    /// Credentials that must never appear in errors, hooks or logs.
    pub(crate) fn known_secrets(&self) -> Vec<Secret> {
        self.api_key
            .iter()
            .chain(self.ingestion_key.iter())
            .cloned()
            .collect()
    }

    /// Checks the service rules (common rules are checked when building): the OTLP protocol
    /// is `grpc` or `http/protobuf` and the OTLP endpoint is an absolute http(s) URL.
    pub fn validate(&self) -> Result<()> {
        if self.otlp_protocol == Protocol::Native {
            return Err(Error::config(
                "otlpProtocol must be \"http/protobuf\" or \"grpc\"",
            ));
        }
        let ok = url::Url::parse(&self.otlp_endpoint)
            .map(|u| matches!(u.scheme(), "http" | "https") && u.host_str().is_some())
            .unwrap_or(false);
        if !ok {
            return Err(Error::config(
                "otlpEndpoint must be an absolute http or https URL",
            ));
        }
        Ok(())
    }

    /// A copy with another OTLP protocol and/or endpoint. When only the protocol changes, the
    /// endpoint becomes that protocol's default.
    pub(crate) fn with_otlp(&self, protocol: Option<Protocol>, endpoint: Option<&str>) -> Self {
        let mut c = self.clone();
        if let Some(p) = protocol {
            if p != c.otlp_protocol && endpoint.is_none() {
                c.otlp_endpoint = default_endpoint(p).to_owned();
            }
            c.otlp_protocol = p;
        }
        if let Some(e) = endpoint {
            c.otlp_endpoint = e.to_owned();
        }
        c
    }
}

/// The default collector endpoint of a protocol.
pub(crate) fn default_endpoint(protocol: Protocol) -> &'static str {
    if protocol == Protocol::Grpc {
        DEFAULT_OTLP_GRPC_ENDPOINT
    } else {
        DEFAULT_OTLP_HTTP_ENDPOINT
    }
}

/// Builder for [`SignozConfig`]. Values set here win over the environment.
#[derive(Debug, Clone, Default)]
pub struct SignozConfigBuilder {
    common: CommonConfigBuilder,
    api_key: Option<Secret>,
    otlp_endpoint: Option<String>,
    otlp_protocol: Option<Protocol>,
    ingestion_key: Option<Secret>,
}

impl SignozConfigBuilder {
    /// Query API base URL (default [`DEFAULT_URL`]).
    pub fn url(mut self, url: impl Into<String>) -> Self {
        self.common = self.common.url(url);
        self
    }
    /// Service-account API key (`SIGNOZ-API-KEY`); any role works, viewer included.
    pub fn api_key(mut self, key: impl Into<Secret>) -> Self {
        self.api_key = Some(key.into());
        self
    }
    /// OTLP collector endpoint (default `http://127.0.0.1:4318`, or `:4317` for gRPC).
    pub fn otlp_endpoint(mut self, endpoint: impl Into<String>) -> Self {
        self.otlp_endpoint = Some(endpoint.into());
        self
    }
    /// OTLP protocol (default [`Protocol::HttpProtobuf`]).
    pub fn otlp_protocol(mut self, protocol: Protocol) -> Self {
        self.otlp_protocol = Some(protocol);
        self
    }
    /// SigNoz Cloud ingestion key (`signoz-ingestion-key`); self-hosted collectors ignore it.
    pub fn ingestion_key(mut self, key: impl Into<Secret>) -> Self {
        self.ingestion_key = Some(key.into());
        self
    }
    /// Connect timeout (default 10 s).
    pub fn connect_timeout(mut self, timeout: Duration) -> Self {
        self.common = self.common.connect_timeout(timeout);
        self
    }
    /// Per-attempt timeout (default 30 s).
    pub fn request_timeout(mut self, timeout: Duration) -> Self {
        self.common = self.common.request_timeout(timeout);
        self
    }
    /// Whole-call timeout including retries (default 120 s).
    pub fn total_timeout(mut self, timeout: Duration) -> Self {
        self.common = self.common.total_timeout(timeout);
        self
    }
    /// Retry policy.
    pub fn retry(mut self, retry: RetryPolicy) -> Self {
        self.common = self.common.retry(retry);
        self
    }
    /// Maximum retries (default 3).
    pub fn max_retries(mut self, max_retries: u32) -> Self {
        self.common = self.common.max_retries(max_retries);
        self
    }
    /// TLS settings.
    pub fn tls(mut self, tls: TlsConfig) -> Self {
        self.common = self.common.tls(tls);
        self
    }
    /// Extra trusted CA certificates (PEM file).
    pub fn ca_cert_path(mut self, path: impl Into<PathBuf>) -> Self {
        self.common = self.common.ca_cert_path(path);
        self
    }
    /// Disables TLS verification (tests only).
    pub fn insecure_skip_verify(mut self, skip: bool) -> Self {
        self.common = self.common.insecure_skip_verify(skip);
        self
    }
    /// Proxy URL (environment proxies are ignored).
    pub fn proxy(mut self, proxy: impl Into<String>) -> Self {
        self.common = self.common.proxy(proxy);
        self
    }
    /// Extra request header (also sent on OTLP exports).
    pub fn header(mut self, name: impl Into<String>, value: impl Into<String>) -> Self {
        self.common = self.common.header(name, value);
        self
    }
    /// Text appended to the User-Agent.
    pub fn user_agent_suffix(mut self, suffix: impl Into<String>) -> Self {
        self.common = self.common.user_agent_suffix(suffix);
        self
    }
    /// Request hooks.
    pub fn hooks(mut self, hooks: Arc<dyn Hooks>) -> Self {
        self.common = self.common.hooks(hooks);
        self
    }
    /// Edits any other common setting.
    pub fn common(mut self, f: impl FnOnce(CommonConfigBuilder) -> CommonConfigBuilder) -> Self {
        self.common = f(self.common);
        self
    }

    /// Builds without reading the environment.
    pub fn build(self) -> Result<SignozConfig> {
        let otlp_protocol = self.otlp_protocol.unwrap_or(Protocol::HttpProtobuf);
        let otlp_endpoint = self
            .otlp_endpoint
            .filter(|e| !e.trim().is_empty())
            .unwrap_or_else(|| default_endpoint(otlp_protocol).to_owned());
        let config = SignozConfig {
            common: self.common.build(DEFAULT_URL)?,
            api_key: self.api_key.filter(|k| !k.is_empty()),
            otlp_endpoint,
            otlp_protocol,
            ingestion_key: self.ingestion_key.filter(|k| !k.is_empty()),
        };
        config.validate()?;
        Ok(config)
    }

    /// Fills unset values from the process environment, then builds.
    pub fn build_from_env(self) -> Result<SignozConfig> {
        self.build_with_env(&EnvReader::new())
    }

    /// Fills unset values from `env`, then builds.
    pub fn build_with_env(mut self, env: &EnvReader) -> Result<SignozConfig> {
        self.common = self.common.apply_env(env, VENDOR_URL_VARS)?;
        if self.api_key.is_none() {
            self.api_key = env.secret("API_KEY", VENDOR_API_KEY_VARS);
        }
        if self.ingestion_key.is_none() {
            self.ingestion_key = env.secret("INGESTION_KEY", VENDOR_INGESTION_KEY_VARS);
        }
        if self.otlp_endpoint.is_none() {
            self.otlp_endpoint = env.string("OTLP_ENDPOINT", VENDOR_OTLP_ENDPOINT_VARS);
        }
        if self.otlp_protocol.is_none() {
            if let Some((name, value)) = env.lookup("OTLP_PROTOCOL", VENDOR_OTLP_PROTOCOL_VARS) {
                let protocol = match value.trim().to_ascii_lowercase().as_str() {
                    "grpc" => Protocol::Grpc,
                    "http/protobuf" => Protocol::HttpProtobuf,
                    _ => {
                        return Err(Error::config(format!(
                            "environment variable {name} must be \"http/protobuf\" or \"grpc\""
                        )))
                    }
                };
                self.otlp_protocol = Some(protocol);
            }
        }
        self.build()
    }
}
