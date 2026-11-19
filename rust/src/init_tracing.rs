//! `init_tracing`: OpenTelemetry tracing that exports spans to the SigNoz collector.

use std::collections::{BTreeMap, HashMap};

use opentelemetry_otlp::{SpanExporter, WithExportConfig, WithHttpConfig, WithTonicConfig};

use crate::config::{default_endpoint, SignozConfig, INGESTION_KEY_HEADER};
use crate::core::{Error, Result};
use crate::http::{build_http_client, user_agent};
use crate::otlp_logs::{grpc_channel, grpc_metadata, otlp_signal_url};
use crate::tracing::{init_tracing_with, Compression, Protocol, TracingConfig, TracingHandle};

/// Fills the export settings `tracing` leaves open from `config`:
///
/// - **protocol and endpoint** — when `tracing.endpoint` is unset, the endpoint is the config's
///   OTLP endpoint (`/v1/traces` appended for OTLP/HTTP) and the protocol the config's OTLP
///   protocol. `Protocol::Grpc` on the tracing config forces gRPC (with the gRPC default
///   endpoint when the config uses OTLP/HTTP). `Protocol::Native` is a `CONFIG` error: SigNoz
///   ingests OTLP only.
/// - **TLS** — the config's TLS settings unless `tracing.tls` is set.
/// - **headers** — the config's extra headers, the `signoz-ingestion-key` header when an
///   ingestion key is configured, then `tracing.headers` (which win).
pub fn tracing_config_for(
    config: &SignozConfig,
    mut tracing: TracingConfig,
) -> Result<TracingConfig> {
    if tracing.protocol == Protocol::Native {
        return Err(Error::config(
            "tracing: SigNoz ingests OTLP only (protocol grpc or http/protobuf)",
        ));
    }
    if tracing.endpoint.is_none() {
        let protocol = if tracing.protocol == Protocol::Grpc {
            Protocol::Grpc
        } else {
            config.otlp_protocol()
        };
        let mut endpoint = if protocol == config.otlp_protocol() {
            config.otlp_endpoint().to_owned()
        } else {
            default_endpoint(protocol).to_owned()
        };
        if protocol == Protocol::HttpProtobuf {
            endpoint = otlp_signal_url(&endpoint, "traces")?.to_string();
        }
        tracing.protocol = protocol;
        tracing.endpoint = Some(endpoint);
    }
    if tracing.tls.is_none() {
        tracing.tls = Some(config.common().tls().clone());
    }
    let mut headers: BTreeMap<String, String> = config.common().headers().iter().cloned().collect();
    if let Some(key) = config.ingestion_key() {
        headers.insert(INGESTION_KEY_HEADER.into(), key.expose_secret().to_owned());
    }
    headers.extend(std::mem::take(&mut tracing.headers));
    tracing.headers = headers;
    Ok(tracing)
}

/// Builds the OTLP span exporter for a resolved tracing config (see [`tracing_config_for`]):
/// OTLP/HTTP protobuf with a client that honours TLS, proxy and connect timeout of `config`, or
/// OTLP/gRPC with the headers as metadata (TLS for `https` endpoints).
pub fn create_span_exporter(
    tracing: &TracingConfig,
    config: &SignozConfig,
) -> Result<SpanExporter> {
    let common = config.common();
    let tls = tracing.tls.clone().unwrap_or_else(|| common.tls().clone());
    let endpoint = tracing
        .endpoint
        .clone()
        .unwrap_or_else(|| default_endpoint(tracing.protocol).to_owned());
    let built = match tracing.protocol {
        Protocol::Grpc => {
            let channel = grpc_channel(&endpoint, &tls, common.connect_timeout())?;
            let mut b = opentelemetry_otlp::SpanExporter::builder()
                .with_tonic()
                .with_channel(channel)
                .with_timeout(tracing.batch.export_timeout)
                .with_metadata(grpc_metadata(&tracing.headers)?);
            if tracing.compression == Compression::Gzip {
                b = b.with_compression(opentelemetry_otlp::Compression::Gzip);
            }
            b.build()
        }
        _ => {
            let mut ua = user_agent(common);
            let mut headers: HashMap<String, String> = HashMap::new();
            for (k, v) in &tracing.headers {
                if k.eq_ignore_ascii_case("user-agent") {
                    ua = v.clone();
                } else {
                    headers.insert(k.clone(), v.clone());
                }
            }
            headers.insert("User-Agent".into(), ua);
            let mut b = opentelemetry_otlp::SpanExporter::builder()
                .with_http()
                .with_http_client(build_http_client(common, &tls)?)
                .with_protocol(opentelemetry_otlp::Protocol::HttpBinary)
                .with_timeout(tracing.batch.export_timeout)
                .with_endpoint(otlp_signal_url(&endpoint, "traces")?.to_string())
                .with_headers(headers);
            if tracing.compression == Compression::Gzip {
                b = b.with_compression(opentelemetry_otlp::Compression::Gzip);
            }
            b.build()
        }
    };
    built.map_err(|e| Error::config(format!("cannot build the OTLP exporter: {e}")))
}

/// Starts OpenTelemetry tracing that exports spans to SigNoz with `config`'s collector
/// endpoint, protocol, ingestion key and TLS (see [`tracing_config_for`]). Must be called
/// inside a tokio runtime.
pub fn init_tracing_with_config(
    config: &SignozConfig,
    tracing: TracingConfig,
) -> Result<TracingHandle> {
    let resolved = tracing_config_for(config, tracing)?;
    init_tracing_with(resolved, |t| create_span_exporter(t, config))
}

/// Starts OpenTelemetry tracing that exports spans to SigNoz. The collector endpoint,
/// protocol, ingestion key and TLS settings come from the environment
/// ([`SignozConfig::from_env`]: `GROW_SIGNOZ_OTLP_ENDPOINT`, `SIGNOZ_OTLP_ENDPOINT`, …) unless
/// `tracing` sets them. No API key is needed. Use
/// [`SignozClient::init_tracing`](crate::SignozClient::init_tracing) to take them from an
/// existing client instead.
///
/// ```no_run
/// # async fn demo() -> grow_signoz::Result<()> {
/// use grow_signoz::tracing::{traced, TracingConfig};
///
/// let handle = grow_signoz::init_tracing(TracingConfig::new("checkout"))?;
/// traced("charge", async { Ok::<_, std::io::Error>(()) }).await.ok();
/// handle.shutdown().await?;
/// # Ok(()) }
/// ```
pub fn init_tracing(tracing: TracingConfig) -> Result<TracingHandle> {
    init_tracing_with_config(&SignozConfig::from_env()?, tracing)
}
