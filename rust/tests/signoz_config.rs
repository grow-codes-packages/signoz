//! `SignozConfig`: environment precedence, OTLP settings, validation and redaction.

use grow_signoz as sdk;
use sdk::core::{EnvReader, ErrorCode};
use sdk::tracing::Protocol;
use sdk::{SignozConfig as Config, DEFAULT_OTLP_GRPC_ENDPOINT, DEFAULT_OTLP_HTTP_ENDPOINT};

fn env(vars: &[(&str, &str)]) -> EnvReader {
    EnvReader::from_map(vars.iter().map(|(k, v)| (k.to_string(), v.to_string())))
}

#[test]
fn defaults() {
    let c = Config::builder().build_with_env(&env(&[])).unwrap();
    assert_eq!(c.url().as_str(), "http://127.0.0.1:8080/");
    assert!(c.api_key().is_none());
    assert!(c.ingestion_key().is_none());
    assert_eq!(c.otlp_protocol(), Protocol::HttpProtobuf);
    assert_eq!(c.otlp_endpoint(), DEFAULT_OTLP_HTTP_ENDPOINT);
    let g = Config::builder()
        .otlp_protocol(Protocol::Grpc)
        .build()
        .unwrap();
    assert_eq!(g.otlp_endpoint(), DEFAULT_OTLP_GRPC_ENDPOINT);
    let empty = Config::builder()
        .otlp_endpoint(" ")
        .api_key("")
        .ingestion_key("")
        .build()
        .unwrap();
    assert_eq!(empty.otlp_endpoint(), DEFAULT_OTLP_HTTP_ENDPOINT);
    assert!(empty.api_key().is_none() && empty.ingestion_key().is_none());
}

#[test]
fn vendor_variables() {
    let c = Config::builder()
        .build_with_env(&env(&[
            ("SIGNOZ_URL", "https://signoz.example"),
            ("SIGNOZ_API_KEY", "vendor-key-1"),
            ("SIGNOZ_INGESTION_KEY", "ingest-key-1"),
            ("OTEL_EXPORTER_OTLP_ENDPOINT", "http://otel:4318"),
            ("OTEL_EXPORTER_OTLP_PROTOCOL", "grpc"),
        ]))
        .unwrap();
    assert_eq!(c.url().as_str(), "https://signoz.example/");
    assert_eq!(c.api_key().unwrap().expose_secret(), "vendor-key-1");
    assert_eq!(c.ingestion_key().unwrap().expose_secret(), "ingest-key-1");
    assert_eq!(c.otlp_endpoint(), "http://otel:4318");
    assert_eq!(c.otlp_protocol(), Protocol::Grpc);
}

#[test]
fn grow_variables_win_over_vendor_and_explicit_wins_over_all() {
    let e = env(&[
        ("GROW_SIGNOZ_URL", "http://grow:8080"),
        ("SIGNOZ_URL", "http://vendor:8080"),
        ("GROW_SIGNOZ_API_KEY", "grow-key-1"),
        ("SIGNOZ_API_KEY", "vendor-key-1"),
        ("GROW_SIGNOZ_OTLP_ENDPOINT", "http://grow-otel:4318"),
        ("SIGNOZ_OTLP_ENDPOINT", "http://signoz-otel:4318"),
        ("OTEL_EXPORTER_OTLP_ENDPOINT", "http://otel:4318"),
        ("GROW_SIGNOZ_OTLP_PROTOCOL", "HTTP/PROTOBUF"),
        ("OTEL_EXPORTER_OTLP_PROTOCOL", "grpc"),
        ("GROW_SIGNOZ_INGESTION_KEY", "grow-ingest-1"),
    ]);
    let c = Config::builder().build_with_env(&e).unwrap();
    assert_eq!(c.url().as_str(), "http://grow:8080/");
    assert_eq!(c.api_key().unwrap().expose_secret(), "grow-key-1");
    assert_eq!(c.otlp_endpoint(), "http://grow-otel:4318");
    assert_eq!(c.otlp_protocol(), Protocol::HttpProtobuf);
    assert_eq!(c.ingestion_key().unwrap().expose_secret(), "grow-ingest-1");
    let c = Config::builder()
        .url("http://explicit:1")
        .api_key("explicit-key")
        .ingestion_key("explicit-ingest")
        .otlp_endpoint("http://explicit-otel:4317")
        .otlp_protocol(Protocol::Grpc)
        .build_with_env(&e)
        .unwrap();
    assert_eq!(c.url().as_str(), "http://explicit:1/");
    assert_eq!(c.api_key().unwrap().expose_secret(), "explicit-key");
    assert_eq!(c.otlp_endpoint(), "http://explicit-otel:4317");
    assert_eq!(c.otlp_protocol(), Protocol::Grpc);
    assert_eq!(
        c.ingestion_key().unwrap().expose_secret(),
        "explicit-ingest"
    );
    let _ = Config::from_env();
}

#[test]
fn invalid_values() {
    let e = Config::builder()
        .build_with_env(&env(&[("GROW_SIGNOZ_OTLP_PROTOCOL", "http/json")]))
        .unwrap_err();
    assert_eq!(e.code(), ErrorCode::Config);
    assert!(e.message().contains("GROW_SIGNOZ_OTLP_PROTOCOL"));
    assert!(!e.message().contains("http/json"));
    let e = Config::builder()
        .otlp_protocol(Protocol::Native)
        .build()
        .unwrap_err();
    assert_eq!(e.code(), ErrorCode::Config);
    for bad in ["not a url", "ftp://x:1", "http://"] {
        let e = Config::builder().otlp_endpoint(bad).build().unwrap_err();
        assert_eq!(e.code(), ErrorCode::Config, "{bad}");
    }
    let e = Config::builder()
        .build_with_env(&env(&[("GROW_SIGNOZ_CONNECT_TIMEOUT", "soon")]))
        .unwrap_err();
    assert_eq!(e.code(), ErrorCode::Config);
}

#[test]
fn debug_redacts_credentials() {
    let c = Config::builder()
        .api_key("api-key-secret-1")
        .ingestion_key("ingest-secret-1")
        .otlp_endpoint("https://user:otlp-pw-1@ingest.eu.signoz.cloud:443")
        .header("x-extra", "visible")
        .build()
        .unwrap();
    let text = format!("{c:?}");
    for s in ["api-key-secret-1", "ingest-secret-1", "otlp-pw-1"] {
        assert!(!text.contains(s), "{s} leaked: {text}");
    }
    assert!(text.contains("http/protobuf"));
    assert!(text.contains("visible"));
}

#[test]
fn builder_setters() {
    use std::time::Duration;
    let c = Config::builder()
        .url("http://h:1")
        .connect_timeout(Duration::from_secs(1))
        .request_timeout(Duration::from_secs(2))
        .total_timeout(Duration::from_secs(3))
        .max_retries(1)
        .retry(sdk::RetryPolicy::none())
        .tls(sdk::core::TlsConfig::default())
        .insecure_skip_verify(true)
        .proxy("http://proxy:3128")
        .user_agent_suffix("it/1")
        .common(|b| b.header("x-a", "b"))
        .build()
        .unwrap();
    assert_eq!(c.common().connect_timeout(), Duration::from_secs(1));
    assert_eq!(c.common().proxy(), Some("http://proxy:3128"));
    assert!(c.common().tls().insecure_skip_verify);
    assert!(c.validate().is_ok());
}
