//! OTLP log exporter to the SigNoz collector (OTLP/HTTP protobuf or OTLP/gRPC) and the
//! batching [`LogClient`](crate::logging::LogClient) built on it.

use std::collections::BTreeMap;
use std::io::Write as _;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, UNIX_EPOCH};

use async_trait::async_trait;
use opentelemetry_proto::tonic::collector::logs::v1::logs_service_client::LogsServiceClient;
use opentelemetry_proto::tonic::collector::logs::v1::{
    ExportLogsServiceRequest, ExportLogsServiceResponse,
};
use opentelemetry_proto::tonic::common::v1::{
    any_value, AnyValue, ArrayValue, InstrumentationScope, KeyValue, KeyValueList,
};
use opentelemetry_proto::tonic::logs::v1::{LogRecord as OtlpLogRecord, ResourceLogs, ScopeLogs};
use opentelemetry_proto::tonic::resource::v1::Resource;
use prost::Message as _;
use serde_json::Value;
use tonic::codec::CompressionEncoding;
use tonic::metadata::{MetadataKey, MetadataMap, MetadataValue};
use tonic::transport::{Certificate, Channel, ClientTlsConfig, Endpoint, Identity};

use crate::config::{SignozConfig, INGESTION_KEY_HEADER};
use crate::core::redact::{is_sensitive_header, redact_text, truncate};
use crate::core::transport::{default_user_agent, MAX_VENDOR_MESSAGE};
use crate::core::{
    CommonConfig, Error, ErrorCode, HttpTransport, Result, RetryPolicy, Secret, TlsConfig,
};
use crate::http::{ca_bundle, identity_pem};
use crate::logging::{
    AttrValue, Attributes, BatchOptions, BatchingLogClient, ExportOptions, ExportResult,
    LogExporter, LogRecord, RedactionProcessor, Severity,
};
use crate::tracing::Protocol;

/// Instrumentation scope of records exported by this package.
pub const LOG_SCOPE_NAME: &str = "grow-codes-signoz";

const OP: &str = "logs.export";

/// Appends `/v1/<signal>` to the endpoint path unless it already ends with it.
pub(crate) fn otlp_signal_url(endpoint: &str, signal: &str) -> Result<url::Url> {
    let mut u = url::Url::parse(endpoint)
        .ok()
        .filter(|u| u.host_str().is_some())
        .ok_or_else(|| Error::config("otlpEndpoint is invalid"))?;
    let suffix = format!("/v1/{signal}");
    if !u.path().ends_with(&suffix) {
        let base = u.path().trim_end_matches('/').to_owned();
        u.set_path(&format!("{base}{suffix}"));
    }
    Ok(u)
}

/// A copy of `common` with another base URL and retry count plus `extra` headers (overriding
/// same-named ones); hooks, TLS, proxy, headers and timeouts are kept.
pub(crate) fn derive_common(
    common: &CommonConfig,
    url: &str,
    max_retries: u32,
    extra: &BTreeMap<String, String>,
) -> Result<CommonConfig> {
    let retry = RetryPolicy {
        max_retries,
        ..common.retry().clone()
    };
    let tls = common.tls().clone();
    let skip = tls.insecure_skip_verify;
    let mut b = CommonConfig::builder()
        .url(url)
        .connect_timeout(common.connect_timeout())
        .request_timeout(common.request_timeout())
        .total_timeout(common.total_timeout())
        .retry(retry)
        .tls(tls)
        .insecure_skip_verify(skip);
    if let Some(p) = common.proxy() {
        b = b.proxy(p);
    }
    for (k, v) in common.headers() {
        if !extra.keys().any(|e| e.eq_ignore_ascii_case(k)) {
            b = b.header(k, v);
        }
    }
    for (k, v) in extra {
        b = b.header(k, v);
    }
    if let Some(s) = common.user_agent_suffix() {
        b = b.user_agent_suffix(s);
    }
    if let Some(h) = common.hooks() {
        b = b.hooks(h.clone());
    }
    b.build(url)
}

/// A lazily connecting gRPC channel to `endpoint` (TLS for `https`, with the configured extra
/// CAs and client certificate; `insecure_skip_verify` is not supported for gRPC).
pub(crate) fn grpc_channel(
    endpoint: &str,
    tls: &TlsConfig,
    connect_timeout: Duration,
) -> Result<Channel> {
    let u = url::Url::parse(endpoint)
        .ok()
        .filter(|u| u.host_str().is_some() && matches!(u.scheme(), "http" | "https"))
        .ok_or_else(|| Error::config("otlpEndpoint is invalid"))?;
    let authority = match u.port() {
        Some(p) => format!("{}:{p}", u.host_str().unwrap_or_default()),
        None => u.host_str().unwrap_or_default().to_owned(),
    };
    let bad = |_| Error::config("otlpEndpoint cannot be used for gRPC");
    let mut ep = Endpoint::from_shared(format!("{}://{authority}", u.scheme()))
        .map_err(bad)?
        .connect_timeout(connect_timeout)
        .user_agent(default_user_agent())
        .map_err(bad)?;
    if u.scheme() == "https" {
        let mut cfg = ClientTlsConfig::new()
            .with_native_roots()
            .domain_name(u.host_str().unwrap_or_default());
        if let Some(ca) = ca_bundle(tls)? {
            cfg = cfg.ca_certificate(Certificate::from_pem(ca));
        }
        if let Some((cert, key)) = identity_pem(tls)? {
            cfg = cfg.identity(Identity::from_pem(cert, key));
        }
        ep = ep
            .tls_config(cfg)
            .map_err(|_| Error::config("TLS settings cannot be used for gRPC"))?;
    }
    Ok(ep.connect_lazy())
}

/// gRPC metadata from export headers (`CONFIG` for invalid names or values).
pub(crate) fn grpc_metadata(headers: &BTreeMap<String, String>) -> Result<MetadataMap> {
    let mut md = MetadataMap::new();
    for (k, v) in headers {
        let key = MetadataKey::from_bytes(k.to_ascii_lowercase().as_bytes())
            .map_err(|_| Error::config(format!("invalid export header name {k:?}")))?;
        let value = MetadataValue::try_from(v.as_str())
            .map_err(|_| Error::config(format!("invalid value for export header {k:?}")))?;
        md.insert(key, value);
    }
    Ok(md)
}

/// Maps a gRPC status to the SDK error classes.
pub(crate) fn grpc_error(op: &str, status: &tonic::Status, secrets: &[Secret]) -> Error {
    use tonic::Code;
    let values: Vec<&str> = secrets.iter().map(Secret::expose_secret).collect();
    let msg = truncate(&redact_text(status.message(), &values), MAX_VENDOR_MESSAGE);
    let code = match status.code() {
        Code::InvalidArgument | Code::OutOfRange | Code::FailedPrecondition => {
            ErrorCode::Validation
        }
        Code::Unauthenticated => ErrorCode::Authentication,
        Code::PermissionDenied => ErrorCode::PermissionDenied,
        Code::NotFound => ErrorCode::NotFound,
        Code::ResourceExhausted => ErrorCode::RateLimited,
        Code::DeadlineExceeded => ErrorCode::Timeout,
        Code::Unimplemented => ErrorCode::Unsupported,
        Code::Cancelled => ErrorCode::Cancelled,
        _ => ErrorCode::Unavailable,
    };
    Error::new(code, format!("{op}: {msg} (gRPC {:?})", status.code()))
}

enum Sink {
    Http {
        transport: Box<HttpTransport>,
        url: String,
        ingestion_key: Option<Secret>,
    },
    Grpc {
        channel: Channel,
        metadata: MetadataMap,
        timeout: Duration,
    },
}

/// A [`LogExporter`] that sends records to the SigNoz collector over OTLP/HTTP protobuf
/// (`POST <endpoint>/v1/logs`) or OTLP/gRPC (`LogsService/Export`), with the
/// `signoz-ingestion-key` header when an ingestion key is configured. Retries are left to the
/// [`BatchProcessor`](crate::logging::BatchProcessor).
pub struct OtlpLogExporter {
    sink: Sink,
    secrets: Vec<Secret>,
    closed: AtomicBool,
}

impl std::fmt::Debug for OtlpLogExporter {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let protocol = match self.sink {
            Sink::Http { .. } => "http/protobuf",
            Sink::Grpc { .. } => "grpc",
        };
        f.debug_struct("OtlpLogExporter")
            .field("protocol", &protocol)
            .field("closed", &self.closed.load(Ordering::SeqCst))
            .finish_non_exhaustive()
    }
}

impl OtlpLogExporter {
    /// Builds the exporter from the config (OTLP endpoint, protocol, ingestion key, TLS,
    /// proxy, headers, timeouts and hooks). Must be called inside a tokio runtime for gRPC.
    pub fn new(config: &SignozConfig) -> Result<Self> {
        Self::with_headers(config, &BTreeMap::new())
    }

    /// Like [`new`](Self::new) with extra export headers (merged over the config headers).
    pub fn with_headers(config: &SignozConfig, headers: &BTreeMap<String, String>) -> Result<Self> {
        config.validate()?;
        let common = config.common();
        let mut secrets: Vec<Secret> = config.ingestion_key().into_iter().cloned().collect();
        let sink = if config.otlp_protocol() == Protocol::Grpc {
            let mut all: BTreeMap<String, String> = common.headers().iter().cloned().collect();
            all.extend(headers.iter().map(|(k, v)| (k.clone(), v.clone())));
            for (k, v) in &all {
                if is_sensitive_header(k) {
                    secrets.push(Secret::new(v.clone()));
                }
            }
            if let Some(key) = config.ingestion_key() {
                all.insert(INGESTION_KEY_HEADER.into(), key.expose_secret().to_owned());
            }
            Sink::Grpc {
                channel: grpc_channel(
                    config.otlp_endpoint(),
                    common.tls(),
                    common.connect_timeout(),
                )?,
                metadata: grpc_metadata(&all)?,
                timeout: common.request_timeout(),
            }
        } else {
            let url = otlp_signal_url(config.otlp_endpoint(), "logs")?;
            let derived = derive_common(common, url.as_str(), 0, headers)?;
            let transport = HttpTransport::new(&derived)?;
            for s in &secrets {
                transport.add_known_secret(s);
            }
            Sink::Http {
                transport: Box::new(transport),
                url: url.to_string(),
                ingestion_key: config.ingestion_key().cloned(),
            }
        };
        Ok(OtlpLogExporter {
            sink,
            secrets,
            closed: AtomicBool::new(false),
        })
    }

    /// The export protocol.
    pub fn protocol(&self) -> Protocol {
        match self.sink {
            Sink::Http { .. } => Protocol::HttpProtobuf,
            Sink::Grpc { .. } => Protocol::Grpc,
        }
    }

    async fn export_http(
        transport: &HttpTransport,
        url: &str,
        ingestion_key: Option<&Secret>,
        req: &ExportLogsServiceRequest,
        gzip: bool,
    ) -> Result<i64> {
        let mut body = req.encode_to_vec();
        let mut builder = transport
            .post(url)
            .operation(OP)
            .idempotent(false)
            .header("accept", "application/x-protobuf");
        if gzip {
            let mut enc = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::default());
            let _ = enc.write_all(&body);
            body = enc.finish().unwrap_or_default();
            builder = builder.header("content-encoding", "gzip");
        }
        if let Some(key) = ingestion_key {
            builder = builder.secret_header(INGESTION_KEY_HEADER, key);
        }
        let resp = builder.body(body, "application/x-protobuf").send().await?;
        let bytes = resp.bytes();
        if bytes.is_empty() {
            return Ok(0);
        }
        if resp.header("content-type").unwrap_or("").contains("json") {
            let v: Value = serde_json::from_slice(bytes).unwrap_or(Value::Null);
            let n = v.pointer("/partialSuccess/rejectedLogRecords");
            return Ok(n
                .and_then(|n| {
                    n.as_i64()
                        .or_else(|| n.as_str().and_then(|s| s.parse().ok()))
                })
                .unwrap_or(0));
        }
        Ok(ExportLogsServiceResponse::decode(bytes.as_ref())
            .ok()
            .and_then(|r| r.partial_success)
            .map(|p| p.rejected_log_records)
            .unwrap_or(0))
    }

    async fn export_grpc(
        &self,
        channel: &Channel,
        metadata: &MetadataMap,
        timeout: Duration,
        req: ExportLogsServiceRequest,
        gzip: bool,
    ) -> Result<i64> {
        let mut client = LogsServiceClient::new(channel.clone());
        if gzip {
            client = client.send_compressed(CompressionEncoding::Gzip);
        }
        let mut request = tonic::Request::new(req);
        *request.metadata_mut() = metadata.clone();
        request.set_timeout(timeout);
        match client.export(request).await {
            Ok(resp) => Ok(resp
                .into_inner()
                .partial_success
                .map(|p| p.rejected_log_records)
                .unwrap_or(0)),
            Err(status) => Err(grpc_error(OP, &status, &self.secrets)),
        }
    }
}

#[async_trait]
impl LogExporter for OtlpLogExporter {
    async fn export(&self, records: &[LogRecord], opts: ExportOptions) -> Result<ExportResult> {
        if self.closed.load(Ordering::SeqCst) {
            return Err(Error::cancelled(format!("{OP}: exporter is shut down")));
        }
        if records.is_empty() {
            return Ok(ExportResult::accepted(0));
        }
        let req = build_otlp_logs_request(records);
        let rejected = match &self.sink {
            Sink::Http {
                transport,
                url,
                ingestion_key,
            } => Self::export_http(transport, url, ingestion_key.as_ref(), &req, opts.gzip).await?,
            Sink::Grpc {
                channel,
                metadata,
                timeout,
            } => {
                self.export_grpc(channel, metadata, *timeout, req, opts.gzip)
                    .await?
            }
        };
        let n = records.len();
        let rej = (rejected.max(0) as usize).min(n);
        let error = (rej > 0)
            .then(|| Error::validation(format!("{OP}: collector rejected {rej} log records")));
        Ok(ExportResult::partial(n - rej, rej, error))
    }

    async fn shutdown(&self) -> Result<()> {
        if !self.closed.swap(true, Ordering::SeqCst) {
            if let Sink::Http { transport, .. } = &self.sink {
                transport.close();
            }
        }
        Ok(())
    }
}

/// Converts records to an OTLP `ExportLogsServiceRequest`, one `ResourceLogs` per distinct
/// resource. Invalid trace or span ids are left out (the collector would reject the whole
/// batch).
pub fn build_otlp_logs_request(records: &[LogRecord]) -> ExportLogsServiceRequest {
    let mut req = ExportLogsServiceRequest::default();
    let mut keys: Vec<String> = Vec::new();
    for r in records {
        let key = serde_json::to_string(&r.resource).unwrap_or_default();
        let idx = match keys.iter().position(|k| *k == key) {
            Some(i) => i,
            None => {
                keys.push(key);
                req.resource_logs.push(ResourceLogs {
                    resource: Some(Resource {
                        attributes: key_values(&r.resource),
                        ..Default::default()
                    }),
                    scope_logs: vec![ScopeLogs {
                        scope: Some(InstrumentationScope {
                            name: LOG_SCOPE_NAME.into(),
                            version: crate::core::transport::SDK_VERSION.into(),
                            ..Default::default()
                        }),
                        ..Default::default()
                    }],
                    ..Default::default()
                });
                keys.len() - 1
            }
        };
        req.resource_logs[idx].scope_logs[0]
            .log_records
            .push(otlp_log_record(r));
    }
    req
}

fn unix_nanos(t: std::time::SystemTime) -> u64 {
    t.duration_since(UNIX_EPOCH)
        .map(|d| d.as_nanos() as u64)
        .unwrap_or(0)
}

fn hex_bytes(s: Option<&str>, len: usize) -> Vec<u8> {
    let Some(s) = s else { return Vec::new() };
    if s.len() != len * 2 || !s.bytes().all(|b| b.is_ascii_hexdigit()) {
        return Vec::new();
    }
    (0..len)
        .map(|i| u8::from_str_radix(&s[i * 2..i * 2 + 2], 16).unwrap_or(0))
        .collect()
}

fn otlp_log_record(r: &LogRecord) -> OtlpLogRecord {
    OtlpLogRecord {
        time_unix_nano: unix_nanos(r.timestamp),
        observed_time_unix_nano: r.observed_timestamp.map(unix_nanos).unwrap_or(0),
        severity_number: i32::from(r.severity.number()),
        severity_text: r.severity_text.clone(),
        body: Some(AnyValue {
            value: Some(any_value::Value::StringValue(r.body.clone())),
        }),
        attributes: key_values(&r.attributes),
        flags: r.trace_flags.map(u32::from).unwrap_or(0),
        trace_id: hex_bytes(r.trace_id.as_deref(), 16),
        span_id: hex_bytes(r.span_id.as_deref(), 8),
        ..Default::default()
    }
}

fn key_values(m: &Attributes) -> Vec<KeyValue> {
    m.iter()
        .filter_map(|(k, v)| {
            any_value(v).map(|value| KeyValue {
                key: k.clone(),
                value: Some(value),
            })
        })
        .collect()
}

fn any_value(v: &AttrValue) -> Option<AnyValue> {
    use any_value::Value as V;
    let value = match v {
        Value::Null => return None,
        Value::String(s) => V::StringValue(s.clone()),
        Value::Bool(b) => V::BoolValue(*b),
        Value::Number(n) => match n.as_i64() {
            Some(i) => V::IntValue(i),
            None => V::DoubleValue(n.as_f64().unwrap_or(0.0)),
        },
        Value::Array(items) => V::ArrayValue(ArrayValue {
            values: items.iter().filter_map(any_value).collect(),
        }),
        Value::Object(map) => V::KvlistValue(KeyValueList {
            values: map
                .iter()
                .filter_map(|(k, v)| {
                    any_value(v).map(|value| KeyValue {
                        key: k.clone(),
                        value: Some(value),
                    })
                })
                .collect(),
        }),
    };
    Some(AnyValue { value: Some(value) })
}

/// Options for [`SignozClient::log_client`](crate::SignozClient::log_client) /
/// [`new_log_client`]. The default is valid.
#[derive(Clone)]
pub struct LogOptions {
    /// Batch settings.
    pub batch: BatchOptions,
    /// `service.name` resource attribute (default `unknown_service`) unless `resource` has it.
    pub service_name: Option<String>,
    /// `service.version` resource attribute.
    pub service_version: Option<String>,
    /// `deployment.environment` resource attribute.
    pub environment: Option<String>,
    /// Extra resource attributes (win over the three above).
    pub resource: Attributes,
    /// Redaction applied to every record (default [`RedactionProcessor::default`]; `None`
    /// disables it).
    pub redaction: Option<RedactionProcessor>,
    /// Minimum severity of loggers from `LogClient::logger` (default `Info`).
    pub min_severity: Severity,
    /// Overrides the config's OTLP protocol (the endpoint becomes that protocol's default
    /// unless `endpoint` is set).
    pub protocol: Option<Protocol>,
    /// Overrides the config's OTLP endpoint.
    pub endpoint: Option<String>,
    /// Extra export headers (merged over the config headers).
    pub headers: BTreeMap<String, String>,
}

impl Default for LogOptions {
    fn default() -> Self {
        LogOptions {
            batch: BatchOptions::default(),
            service_name: None,
            service_version: None,
            environment: None,
            resource: Attributes::new(),
            redaction: Some(RedactionProcessor::default()),
            min_severity: Severity::Info,
            protocol: None,
            endpoint: None,
            headers: BTreeMap::new(),
        }
    }
}

impl std::fmt::Debug for LogOptions {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("LogOptions")
            .field("service_name", &self.service_name)
            .field("protocol", &self.protocol)
            .field("endpoint", &self.endpoint)
            .finish_non_exhaustive()
    }
}

impl LogOptions {
    /// Options with a service name.
    pub fn service(name: impl Into<String>) -> Self {
        LogOptions {
            service_name: Some(name.into()),
            ..LogOptions::default()
        }
    }
    /// Sets `service.version`.
    pub fn service_version(mut self, v: impl Into<String>) -> Self {
        self.service_version = Some(v.into());
        self
    }
    /// Sets `deployment.environment`.
    pub fn environment(mut self, env: impl Into<String>) -> Self {
        self.environment = Some(env.into());
        self
    }
    /// Sets the batch options.
    pub fn batch(mut self, batch: BatchOptions) -> Self {
        self.batch = batch;
        self
    }
    /// Overrides the protocol.
    pub fn protocol(mut self, protocol: Protocol) -> Self {
        self.protocol = Some(protocol);
        self
    }
    /// Overrides the endpoint.
    pub fn endpoint(mut self, endpoint: impl Into<String>) -> Self {
        self.endpoint = Some(endpoint.into());
        self
    }
}

/// Starts a batching [`LogClient`](crate::logging::LogClient) that sends OTLP logs to the
/// SigNoz collector described by `config`. It needs no API key (only the ingestion key for
/// SigNoz Cloud). Must be called inside a tokio runtime; close it to flush.
pub fn new_log_client(config: &SignozConfig, opts: LogOptions) -> Result<BatchingLogClient> {
    let cfg = config.with_otlp(opts.protocol, opts.endpoint.as_deref());
    let exporter = std::sync::Arc::new(OtlpLogExporter::with_headers(&cfg, &opts.headers)?);
    let mut resource = Attributes::new();
    resource.insert(
        "service.name".into(),
        Value::String(
            opts.service_name
                .clone()
                .filter(|s| !s.is_empty())
                .unwrap_or_else(|| "unknown_service".into()),
        ),
    );
    if let Some(v) = opts.service_version.filter(|s| !s.is_empty()) {
        resource.insert("service.version".into(), Value::String(v));
    }
    if let Some(e) = opts.environment.filter(|s| !s.is_empty()) {
        resource.insert("deployment.environment".into(), Value::String(e));
    }
    resource.extend(opts.resource);
    BatchingLogClient::builder(exporter)
        .batch_options(opts.batch)
        .resource(resource)
        .redaction(opts.redaction)
        .min_severity(opts.min_severity)
        .build()
}
