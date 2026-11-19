//! Shared HTTP helpers for the parts that cannot use the buffered core transport: the live
//! tail stream and the OTLP span exporter.

use std::path::Path;

use serde_json::Value;

use crate::core::redact::truncate;
use crate::core::transport::{default_user_agent, MAX_VENDOR_MESSAGE};
use crate::core::{CommonConfig, Error, ErrorCode, Result, TlsConfig};

/// The User-Agent of a config (standard UA plus the optional suffix).
pub(crate) fn user_agent(common: &CommonConfig) -> String {
    match common.user_agent_suffix() {
        Some(s) if !s.is_empty() => format!("{} {s}", default_user_agent()),
        _ => default_user_agent(),
    }
}

fn read(path: &Path, what: &str) -> Result<Vec<u8>> {
    std::fs::read(path).map_err(|e| {
        Error::config(format!("{what} file {} cannot be read", path.display())).with_source(e)
    })
}

/// PEM bundle of the extra trusted CAs (`None` when there are none).
pub(crate) fn ca_bundle(tls: &TlsConfig) -> Result<Option<Vec<u8>>> {
    let mut ca = Vec::new();
    if let Some(path) = &tls.ca_cert_path {
        ca.extend(read(path, "CA certificate")?);
        ca.push(b'\n');
    }
    if let Some(pem) = &tls.ca_cert_pem {
        ca.extend_from_slice(pem.as_bytes());
    }
    Ok((!ca.is_empty()).then_some(ca))
}

/// Client certificate + key PEM (`None` without a client certificate).
pub(crate) fn identity_pem(tls: &TlsConfig) -> Result<Option<(Vec<u8>, Vec<u8>)>> {
    match (&tls.client_cert_path, &tls.client_key_path) {
        (Some(cert), Some(key)) => Ok(Some((
            read(cert, "client certificate")?,
            read(key, "client key")?,
        ))),
        _ => Ok(None),
    }
}

/// A reqwest client with the TLS, proxy and connect-timeout settings of `common` (TLS from
/// `tls`). No request timeout: callers bound each call themselves.
pub(crate) fn build_http_client(common: &CommonConfig, tls: &TlsConfig) -> Result<reqwest::Client> {
    let mut builder = reqwest::Client::builder()
        .no_proxy()
        .use_rustls_tls()
        .connect_timeout(common.connect_timeout())
        .user_agent(user_agent(common));
    if tls.insecure_skip_verify {
        builder = builder.danger_accept_invalid_certs(true);
    }
    if let Some(ca) = ca_bundle(tls)? {
        let certs = reqwest::Certificate::from_pem_bundle(&ca)
            .map_err(|e| Error::config("CA certificate is not valid PEM").with_source(e))?;
        if certs.is_empty() {
            return Err(Error::config("CA certificate contains no certificate"));
        }
        for cert in certs {
            builder = builder.add_root_certificate(cert);
        }
    }
    if let Some((cert, key)) = identity_pem(tls)? {
        let mut pem = cert;
        pem.push(b'\n');
        pem.extend(key);
        let identity = reqwest::Identity::from_pem(&pem).map_err(|e| {
            Error::config("client certificate or key is not valid PEM").with_source(e)
        })?;
        builder = builder.identity(identity);
    }
    if let Some(proxy) = common.proxy() {
        let proxy = reqwest::Proxy::all(proxy)
            .map_err(|e| Error::config("proxy is not a valid URL").with_source(e.without_url()))?;
        builder = builder.proxy(proxy);
    }
    builder
        .build()
        .map_err(|e| Error::config("cannot build the HTTP client").with_source(e.without_url()))
}

/// A short vendor error text from a SigNoz error envelope, an OTLP status or plain text.
pub(crate) fn vendor_text(body: &[u8]) -> Option<String> {
    if let Ok(v) = serde_json::from_slice::<Value>(body) {
        let msg = v
            .pointer("/error/message")
            .or_else(|| v.get("message"))
            .or_else(|| v.get("error"))
            .and_then(Value::as_str)?;
        let msg = msg.trim();
        return (!msg.is_empty()).then(|| msg.to_owned());
    }
    let text = std::str::from_utf8(body).ok()?.trim();
    if text.is_empty() || text.starts_with('<') {
        return None;
    }
    text.lines().next().map(str::to_owned)
}

/// `"<op>: <reason>[: <vendor text>] (HTTP <status>)"` mapped to the SPEC error class.
pub(crate) fn status_error(
    op: &str,
    status: u16,
    body: &[u8],
    redact: impl Fn(&str) -> String,
) -> Error {
    let code = ErrorCode::from_http_status(status).unwrap_or(ErrorCode::Unavailable);
    let reason = match vendor_text(body) {
        Some(t) => format!(
            "{}: {}",
            code.reason(),
            truncate(&redact(&t), MAX_VENDOR_MESSAGE)
        ),
        None => code.reason().to_owned(),
    };
    Error::from_status(status, format!("{op}: {reason} (HTTP {status})"))
}
