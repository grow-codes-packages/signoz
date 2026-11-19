//! OTLP log exporter (HTTP protobuf + gRPC), the SigNoz log client and `init_tracing`
//! against local mock collectors.

mod support;

use std::collections::BTreeMap;
use std::io::Read as _;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, UNIX_EPOCH};

use grow_signoz as sdk;
use opentelemetry::trace::{Span as _, Tracer as _};
use opentelemetry_proto::tonic::collector::logs::v1::logs_service_server::{
    LogsService, LogsServiceServer,
};
use opentelemetry_proto::tonic::collector::logs::v1::{
    ExportLogsPartialSuccess, ExportLogsServiceRequest, ExportLogsServiceResponse,
};
use opentelemetry_proto::tonic::collector::trace::v1::trace_service_server::{
    TraceService, TraceServiceServer,
};
use opentelemetry_proto::tonic::collector::trace::v1::{
    ExportTraceServiceRequest, ExportTraceServiceResponse,
};
use opentelemetry_proto::tonic::common::v1::any_value::Value as V;
use prost::Message as _;
use sdk::core::{ErrorCode, TlsConfig};
use sdk::logging::{ExportOptions, LogClient, LogExporter, LogInput, LogRecord, Severity};
use sdk::tracing::{Protocol, TracingConfig};
use sdk::{
    build_otlp_logs_request, create_span_exporter, new_log_client, tracing_config_for, LogOptions,
    OtlpLogExporter, SignozConfig,
};
use serde_json::json;
use support::{builder, json_body};
use tonic::metadata::MetadataMap;
use wiremock::matchers::{header, method, path};
use wiremock::{Mock, MockServer, Request, ResponseTemplate};

fn fixtures(name: &str) -> String {
    format!("{}/tests/fixtures/{name}", env!("CARGO_MANIFEST_DIR"))
}

fn record(body: &str) -> LogRecord {
    let mut r = LogRecord::new(Severity::Warn, body);
    r.resource.insert("service.name".into(), json!("svc-a"));
    r
}

fn decode_logs(req: &Request) -> ExportLogsServiceRequest {
    let mut raw = req.body.clone();
    if req
        .headers
        .get("content-encoding")
        .is_some_and(|v| v == "gzip")
    {
        let mut out = Vec::new();
        flate2::read::GzDecoder::new(&raw[..])
            .read_to_end(&mut out)
            .unwrap();
        raw = out;
    }
    ExportLogsServiceRequest::decode(&raw[..]).unwrap()
}

#[test]
fn otlp_request_mapping() {
    let mut a = record("hello");
    a.timestamp = UNIX_EPOCH + Duration::from_nanos(1_795_033_260_005_000_123);
    a.observed_timestamp = Some(UNIX_EPOCH + Duration::from_secs(2));
    a.severity_text = "WARN".into();
    a.trace_id = Some("4bf92f3577b34da6a3ce929d0e0e4736".into());
    a.span_id = Some("00f067aa0ba902b7".into());
    a.trace_flags = Some(1);
    for (k, v) in [
        ("s", json!("x")),
        ("i", json!(7)),
        ("f", json!(1.5)),
        ("big", json!(u64::MAX)),
        ("b", json!(true)),
        ("arr", json!([1, "two", null])),
        ("obj", json!({"k": "v", "n": null})),
        ("nothing", json!(null)),
    ] {
        a.attributes.insert(k.into(), v);
    }
    let mut b = record("other resource");
    b.resource.insert("service.version".into(), json!("2"));
    b.trace_id = Some("zz".into());
    b.span_id = Some("123".into());
    let c = record("same resource as a");
    let req = build_otlp_logs_request(&[a, b, c]);
    assert_eq!(req.resource_logs.len(), 2);
    let rl = &req.resource_logs[0];
    let scope = &rl.scope_logs[0];
    assert_eq!(scope.scope.as_ref().unwrap().name, sdk::LOG_SCOPE_NAME);
    assert_eq!(scope.log_records.len(), 2);
    let lr = &scope.log_records[0];
    assert_eq!(lr.time_unix_nano, 1_795_033_260_005_000_123);
    assert_eq!(lr.observed_time_unix_nano, 2_000_000_000);
    assert_eq!(lr.severity_number, 13);
    assert_eq!(lr.severity_text, "WARN");
    assert_eq!(lr.trace_id.len(), 16);
    assert_eq!(
        lr.span_id,
        vec![0x00, 0xf0, 0x67, 0xaa, 0x0b, 0xa9, 0x02, 0xb7]
    );
    assert_eq!(lr.flags, 1);
    let attr = |k: &str| {
        lr.attributes
            .iter()
            .find(|kv| kv.key == k)
            .and_then(|kv| kv.value.clone())
            .and_then(|v| v.value)
    };
    assert_eq!(attr("s"), Some(V::StringValue("x".into())));
    assert_eq!(attr("i"), Some(V::IntValue(7)));
    assert_eq!(attr("f"), Some(V::DoubleValue(1.5)));
    assert!(matches!(attr("big"), Some(V::DoubleValue(_))));
    assert_eq!(attr("b"), Some(V::BoolValue(true)));
    match attr("arr") {
        Some(V::ArrayValue(a)) => assert_eq!(a.values.len(), 2),
        other => panic!("{other:?}"),
    }
    match attr("obj") {
        Some(V::KvlistValue(kv)) => assert_eq!(kv.values.len(), 1),
        other => panic!("{other:?}"),
    }
    assert!(attr("nothing").is_none());
    let other = &req.resource_logs[1].scope_logs[0].log_records[0];
    assert!(other.trace_id.is_empty() && other.span_id.is_empty());
    assert_eq!(
        req.resource_logs[1]
            .resource
            .as_ref()
            .unwrap()
            .attributes
            .len(),
        2
    );
}

#[tokio::test]
async fn http_exporter_sends_protobuf() {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/otlp/v1/logs"))
        .and(header("content-type", "application/x-protobuf"))
        .and(header("signoz-ingestion-key", "ingest-key-1234"))
        .and(header("x-extra", "export"))
        .respond_with(json_body(200, json!({"partialSuccess": {}})))
        .mount(&server)
        .await;
    let cfg = builder("http://127.0.0.1:1")
        .otlp_endpoint(format!("{}/otlp/", server.uri()))
        .ingestion_key("ingest-key-1234")
        .build()
        .unwrap();
    let mut extra = BTreeMap::new();
    extra.insert("x-extra".to_owned(), "export".to_owned());
    let exp = OtlpLogExporter::with_headers(&cfg, &extra).unwrap();
    assert_eq!(exp.protocol(), Protocol::HttpProtobuf);
    assert!(format!("{exp:?}").contains("http/protobuf"));
    let res = exp
        .export(&[record("one"), record("two")], ExportOptions::new(true))
        .await
        .unwrap();
    assert_eq!((res.accepted, res.rejected), (2, 0));
    assert!(res.error.is_none());
    let reqs = server.received_requests().await.unwrap();
    assert_eq!(reqs[0].headers.get("content-encoding").unwrap(), "gzip");
    let decoded = decode_logs(&reqs[0]);
    assert_eq!(decoded.resource_logs[0].scope_logs[0].log_records.len(), 2);
    let res = exp
        .export(&[record("plain")], ExportOptions::new(false))
        .await
        .unwrap();
    assert_eq!(res.accepted, 1);
    let reqs = server.received_requests().await.unwrap();
    assert!(reqs[1].headers.get("content-encoding").is_none());
    assert_eq!(
        exp.export(&[], ExportOptions::default())
            .await
            .unwrap()
            .accepted,
        0
    );
    exp.shutdown().await.unwrap();
    exp.shutdown().await.unwrap();
    let e = exp
        .export(&[record("late")], ExportOptions::default())
        .await
        .unwrap_err();
    assert_eq!(e.code(), ErrorCode::Cancelled);
}

#[tokio::test]
async fn http_exporter_partial_success_and_errors() {
    let server = MockServer::start().await;
    Mock::given(path("/json/v1/logs"))
        .respond_with(json_body(
            200,
            json!({"partialSuccess": {"rejectedLogRecords": "1"}}),
        ))
        .mount(&server)
        .await;
    Mock::given(path("/json2/v1/logs"))
        .respond_with(json_body(
            200,
            json!({"partialSuccess": {"rejectedLogRecords": 9}}),
        ))
        .mount(&server)
        .await;
    let proto = ExportLogsServiceResponse {
        partial_success: Some(ExportLogsPartialSuccess {
            rejected_log_records: 1,
            error_message: "bad".into(),
        }),
    };
    Mock::given(path("/proto/v1/logs"))
        .respond_with(
            ResponseTemplate::new(200)
                .set_body_raw(proto.encode_to_vec(), "application/x-protobuf"),
        )
        .mount(&server)
        .await;
    Mock::given(path("/empty/v1/logs"))
        .respond_with(ResponseTemplate::new(200))
        .mount(&server)
        .await;
    Mock::given(path("/bad/v1/logs"))
        .respond_with(ResponseTemplate::new(400).set_body_raw(
            support::fixture("otlp.logs.http.badRequest.json"),
            "application/json",
        ))
        .mount(&server)
        .await;
    Mock::given(path("/busy/v1/logs"))
        .respond_with(ResponseTemplate::new(503))
        .mount(&server)
        .await;
    let exporter = |p: &str| {
        let cfg = builder("http://127.0.0.1:1")
            .otlp_endpoint(format!("{}/{p}/v1/logs", server.uri()))
            .build()
            .unwrap();
        OtlpLogExporter::new(&cfg).unwrap()
    };
    let two = [record("a"), record("b")];
    let r = exporter("json")
        .export(&two, ExportOptions::default())
        .await
        .unwrap();
    assert_eq!((r.accepted, r.rejected), (1, 1));
    assert_eq!(r.error.unwrap().code(), ErrorCode::Validation);
    let r = exporter("json2")
        .export(&two, ExportOptions::default())
        .await
        .unwrap();
    assert_eq!((r.accepted, r.rejected), (0, 2));
    let r = exporter("proto")
        .export(&two, ExportOptions::default())
        .await
        .unwrap();
    assert_eq!(r.rejected, 1);
    let r = exporter("empty")
        .export(&two, ExportOptions::default())
        .await
        .unwrap();
    assert_eq!(r.accepted, 2);
    let e = exporter("bad")
        .export(&two, ExportOptions::default())
        .await
        .unwrap_err();
    assert_eq!(e.code(), ErrorCode::Validation);
    assert!(e.message().contains("length mismatch"), "{e}");
    let e = exporter("busy")
        .export(&two, ExportOptions::default())
        .await
        .unwrap_err();
    assert_eq!(e.code(), ErrorCode::Unavailable);
    // the exporter does not retry itself (the batch processor does)
    let busy = server
        .received_requests()
        .await
        .unwrap()
        .iter()
        .filter(|r| r.url.path() == "/busy/v1/logs")
        .count();
    assert_eq!(busy, 1);
}

type Seen = Arc<Mutex<Vec<(MetadataMap, ExportLogsServiceRequest)>>>;

#[derive(Clone, Default)]
struct Logs {
    seen: Seen,
}

#[tonic::async_trait]
impl LogsService for Logs {
    async fn export(
        &self,
        request: tonic::Request<ExportLogsServiceRequest>,
    ) -> Result<tonic::Response<ExportLogsServiceResponse>, tonic::Status> {
        let md = request.metadata().clone();
        let code = md
            .get("x-status")
            .and_then(|v| v.to_str().ok())
            .and_then(|v| v.parse::<i32>().ok());
        self.seen.lock().unwrap().push((md, request.into_inner()));
        if let Some(code) = code {
            return Err(tonic::Status::new(
                tonic::Code::from_i32(code),
                "denied for ingest-key-1234",
            ));
        }
        Ok(tonic::Response::new(ExportLogsServiceResponse {
            partial_success: Some(ExportLogsPartialSuccess {
                rejected_log_records: 0,
                error_message: String::new(),
            }),
        }))
    }
}

#[derive(Clone, Default)]
struct Traces {
    seen: Arc<Mutex<Vec<(MetadataMap, ExportTraceServiceRequest)>>>,
}

#[tonic::async_trait]
impl TraceService for Traces {
    async fn export(
        &self,
        request: tonic::Request<ExportTraceServiceRequest>,
    ) -> Result<tonic::Response<ExportTraceServiceResponse>, tonic::Status> {
        let md = request.metadata().clone();
        self.seen.lock().unwrap().push((md, request.into_inner()));
        Ok(tonic::Response::new(ExportTraceServiceResponse::default()))
    }
}

/// A local OTLP/gRPC collector; returns its endpoint.
async fn grpc_collector(logs: Logs, traces: Traces) -> String {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    tokio::spawn(async move {
        tonic::transport::Server::builder()
            .add_service(
                LogsServiceServer::new(logs)
                    .accept_compressed(tonic::codec::CompressionEncoding::Gzip),
            )
            .add_service(
                TraceServiceServer::new(traces)
                    .accept_compressed(tonic::codec::CompressionEncoding::Gzip),
            )
            .serve_with_incoming(tokio_stream::wrappers::TcpListenerStream::new(listener))
            .await
            .unwrap();
    });
    format!("http://{addr}")
}

#[tokio::test]
async fn grpc_exporter_and_error_mapping() {
    let logs = Logs::default();
    let endpoint = grpc_collector(logs.clone(), Traces::default()).await;
    let cfg = builder("http://127.0.0.1:1")
        .otlp_protocol(Protocol::Grpc)
        .otlp_endpoint(&endpoint)
        .ingestion_key("ingest-key-1234")
        .header("Authorization", "Bearer header-secret-1")
        .build()
        .unwrap();
    let exp = OtlpLogExporter::new(&cfg).unwrap();
    assert_eq!(exp.protocol(), Protocol::Grpc);
    for gzip in [true, false] {
        let r = exp
            .export(&[record("grpc")], ExportOptions::new(gzip))
            .await
            .unwrap();
        assert_eq!(r.accepted, 1);
    }
    {
        let seen = logs.seen.lock().unwrap();
        assert_eq!(seen.len(), 2);
        let (md, req) = &seen[0];
        assert_eq!(md.get("signoz-ingestion-key").unwrap(), "ingest-key-1234");
        assert_eq!(md.get("authorization").unwrap(), "Bearer header-secret-1");
        assert!(md
            .get("user-agent")
            .unwrap()
            .to_str()
            .unwrap()
            .contains("grow-codes-signoz/"));
        assert_eq!(
            req.resource_logs[0].scope_logs[0].log_records[0].severity_number,
            13
        );
    }
    exp.shutdown().await.unwrap();

    let cases = [
        (3, ErrorCode::Validation),
        (11, ErrorCode::Validation),
        (9, ErrorCode::Validation),
        (16, ErrorCode::Authentication),
        (7, ErrorCode::PermissionDenied),
        (5, ErrorCode::NotFound),
        (8, ErrorCode::RateLimited),
        (4, ErrorCode::Timeout),
        (12, ErrorCode::Unsupported),
        (1, ErrorCode::Cancelled),
        (14, ErrorCode::Unavailable),
    ];
    for (code, want) in cases {
        let cfg = builder("http://127.0.0.1:1")
            .otlp_protocol(Protocol::Grpc)
            .otlp_endpoint(&endpoint)
            .ingestion_key("ingest-key-1234")
            .header("x-status", code.to_string())
            .build()
            .unwrap();
        let e = OtlpLogExporter::new(&cfg)
            .unwrap()
            .export(&[record("x")], ExportOptions::default())
            .await
            .unwrap_err();
        assert_eq!(e.code(), want, "gRPC code {code}");
        assert!(!e.message().contains("ingest-key-1234"), "{e}");
        assert!(e.message().contains("gRPC"));
    }
}

#[tokio::test]
async fn grpc_exporter_config_errors() {
    let bad_header = builder("http://127.0.0.1:1")
        .otlp_protocol(Protocol::Grpc)
        .header("bad header", "x")
        .build()
        .unwrap();
    assert_eq!(
        OtlpLogExporter::new(&bad_header).unwrap_err().code(),
        ErrorCode::Config
    );
    let bad_value = builder("http://127.0.0.1:1")
        .otlp_protocol(Protocol::Grpc)
        .header("x-ok", "line\nbreak")
        .build();
    if let Ok(cfg) = bad_value {
        assert!(OtlpLogExporter::new(&cfg).is_err());
    }
    // https with an extra CA and a client certificate builds a lazy TLS channel
    let tls = builder("http://127.0.0.1:1")
        .otlp_protocol(Protocol::Grpc)
        .otlp_endpoint("https://ingest.eu.signoz.cloud:443")
        .tls(TlsConfig {
            ca_cert_path: Some(fixtures("ca.pem").into()),
            client_cert_path: Some(fixtures("client.pem").into()),
            client_key_path: Some(fixtures("client.key").into()),
            ..TlsConfig::default()
        })
        .build()
        .unwrap();
    assert!(OtlpLogExporter::new(&tls).is_ok());
    let missing_ca = builder("http://127.0.0.1:1")
        .otlp_protocol(Protocol::Grpc)
        .otlp_endpoint("https://ingest.eu.signoz.cloud")
        .ca_cert_path("/nonexistent/ca.pem")
        .build()
        .unwrap();
    assert_eq!(
        OtlpLogExporter::new(&missing_ca).unwrap_err().code(),
        ErrorCode::Config
    );
    let missing_key = builder("http://127.0.0.1:1")
        .otlp_protocol(Protocol::Grpc)
        .otlp_endpoint("https://ingest.eu.signoz.cloud")
        .common(|b| b.client_cert(fixtures("client.pem"), "/nonexistent/key.pem"))
        .build()
        .unwrap();
    assert_eq!(
        OtlpLogExporter::new(&missing_key).unwrap_err().code(),
        ErrorCode::Config
    );
}

async fn wait_for<T>(mut f: impl FnMut() -> Option<T>) -> T {
    let deadline = Instant::now() + Duration::from_secs(10);
    loop {
        if let Some(v) = f() {
            return v;
        }
        assert!(Instant::now() < deadline, "timed out");
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
}

#[tokio::test]
async fn log_client_sends_through_the_collector() {
    let server = MockServer::start().await;
    Mock::given(path("/v1/logs"))
        .respond_with(json_body(200, json!({"partialSuccess": {}})))
        .mount(&server)
        .await;
    let c = sdk::SignozClient::new(
        builder("http://127.0.0.1:1")
            .otlp_endpoint(server.uri())
            .build()
            .unwrap(),
    )
    .unwrap();
    let mut opts = LogOptions::service("checkout")
        .service_version("1.0.0")
        .environment("test")
        .batch(sdk::logging::BatchOptions::default());
    opts.resource.insert("host.name".into(), json!("h1"));
    opts.min_severity = Severity::Debug;
    assert!(format!("{opts:?}").contains("checkout"));
    let lc = c.log_client(opts).unwrap();
    lc.send(
        LogInput::new("hello")
            .attribute("password", "hunter2")
            .attribute("n", 1)
            .into(),
    );
    lc.logger(Some("it"), Default::default())
        .debug("debug line", Default::default());
    lc.flush(Some(Duration::from_secs(5))).await.unwrap();
    assert_eq!(lc.stats().sent, 2);
    let reqs = server.received_requests().await.unwrap();
    let req = decode_logs(&reqs[0]);
    let res = req.resource_logs[0].resource.as_ref().unwrap();
    let keys: Vec<&str> = res.attributes.iter().map(|kv| kv.key.as_str()).collect();
    assert_eq!(
        keys,
        [
            "deployment.environment",
            "host.name",
            "service.name",
            "service.version"
        ]
    );
    let lr = &req.resource_logs[0].scope_logs[0].log_records[0];
    let pw = lr
        .attributes
        .iter()
        .find(|kv| kv.key == "password")
        .unwrap();
    assert_eq!(
        pw.value.clone().unwrap().value,
        Some(V::StringValue("[REDACTED]".into()))
    );
    c.close().await;

    // protocol override → gRPC default endpoint replaced by an explicit one
    let logs = Logs::default();
    let endpoint = grpc_collector(logs.clone(), Traces::default()).await;
    let cfg = SignozConfig::builder().build().unwrap();
    let lc = new_log_client(
        &cfg,
        LogOptions {
            protocol: Some(Protocol::Grpc),
            redaction: None,
            ..LogOptions::default()
        }
        .endpoint(endpoint),
    )
    .unwrap();
    lc.send(LogInput::new("via grpc").attribute("token", "kept").into());
    lc.close().await.unwrap();
    let (_, req) = logs.seen.lock().unwrap()[0].clone();
    let res = req.resource_logs[0].resource.as_ref().unwrap();
    assert_eq!(res.attributes[0].key, "service.name");
    assert_eq!(
        res.attributes[0].value.clone().unwrap().value,
        Some(V::StringValue("unknown_service".into()))
    );
    // only the protocol: the endpoint becomes that protocol's default
    let lc = new_log_client(&cfg, LogOptions::default().protocol(Protocol::Grpc)).unwrap();
    lc.close().await.unwrap();
}

#[test]
fn tracing_config_resolution() {
    let http = builder("http://127.0.0.1:1")
        .otlp_endpoint("http://collector:4318/base")
        .ingestion_key("ingest-key-1234")
        .header("x-common", "c")
        .build()
        .unwrap();
    let t = tracing_config_for(
        &http,
        TracingConfig::new("svc").with_header("x-common", "t"),
    )
    .unwrap();
    assert_eq!(t.protocol, Protocol::HttpProtobuf);
    assert_eq!(
        t.endpoint.as_deref(),
        Some("http://collector:4318/base/v1/traces")
    );
    assert_eq!(t.headers["signoz-ingestion-key"], "ingest-key-1234");
    assert_eq!(t.headers["x-common"], "t");
    assert!(t.tls.is_some());
    let t = tracing_config_for(
        &http,
        TracingConfig::new("svc").with_protocol(Protocol::Grpc),
    )
    .unwrap();
    assert_eq!(t.endpoint.as_deref(), Some(sdk::DEFAULT_OTLP_GRPC_ENDPOINT));
    let grpc = builder("http://127.0.0.1:1")
        .otlp_protocol(Protocol::Grpc)
        .otlp_endpoint("http://collector:4317")
        .build()
        .unwrap();
    let t = tracing_config_for(&grpc, TracingConfig::new("svc")).unwrap();
    assert_eq!(t.protocol, Protocol::Grpc);
    assert_eq!(t.endpoint.as_deref(), Some("http://collector:4317"));
    let explicit = tracing_config_for(
        &grpc,
        TracingConfig::new("svc")
            .with_endpoint("http://other:4318")
            .with_tls(TlsConfig::default()),
    )
    .unwrap();
    assert_eq!(explicit.protocol, Protocol::HttpProtobuf);
    assert_eq!(explicit.endpoint.as_deref(), Some("http://other:4318"));
    let e = tracing_config_for(
        &grpc,
        TracingConfig::new("svc").with_protocol(Protocol::Native),
    )
    .unwrap_err();
    assert_eq!(e.code(), ErrorCode::Config);
    // exporters build for https endpoints with TLS settings
    let tls = TlsConfig {
        ca_cert_path: Some(fixtures("ca.pem").into()),
        ..TlsConfig::default()
    };
    let rt = tokio::runtime::Runtime::new().unwrap();
    let _g = rt.enter();
    for protocol in [Protocol::HttpProtobuf, Protocol::Grpc] {
        let t = TracingConfig::new("svc")
            .with_protocol(protocol)
            .with_endpoint("https://ingest.eu.signoz.cloud:443")
            .with_header("User-Agent", "custom/1")
            .with_compression(sdk::tracing::Compression::None)
            .with_tls(tls.clone());
        assert!(create_span_exporter(&t, &grpc).is_ok(), "{protocol:?}");
    }
    let bad = TracingConfig::new("svc").with_tls(TlsConfig {
        ca_cert_pem: Some("garbage".into()),
        ..TlsConfig::default()
    });
    assert_eq!(
        create_span_exporter(&bad, &grpc).unwrap_err().code(),
        ErrorCode::Config
    );
}

#[tokio::test]
async fn init_tracing_exports_over_http_and_grpc() {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/v1/traces"))
        .respond_with(ResponseTemplate::new(200))
        .mount(&server)
        .await;
    let c = sdk::SignozClient::new(
        builder("http://127.0.0.1:1")
            .otlp_endpoint(server.uri())
            .ingestion_key("ingest-key-1234")
            .build()
            .unwrap(),
    )
    .unwrap();
    let handle = c
        .init_tracing(TracingConfig::new("http-svc").with_set_global(false))
        .unwrap();
    let mut span = handle.tracer("it", None).start("http-span");
    span.end();
    handle
        .force_flush(Some(Duration::from_secs(10)))
        .await
        .unwrap();
    let reqs = server.received_requests().await.unwrap();
    assert!(!reqs.is_empty());
    assert_eq!(
        reqs[0].headers.get("signoz-ingestion-key").unwrap(),
        "ingest-key-1234"
    );
    assert!(reqs[0]
        .headers
        .get("user-agent")
        .unwrap()
        .to_str()
        .unwrap()
        .starts_with("grow-codes-signoz/"));
    c.close().await;
    assert!(handle.is_shutdown());

    let traces = Traces::default();
    let endpoint = grpc_collector(Logs::default(), traces.clone()).await;
    let h = sdk::init_tracing(
        TracingConfig::new("grpc-svc")
            .with_protocol(Protocol::Grpc)
            .with_endpoint(endpoint)
            .with_header("x-team", "obs")
            .with_set_global(false),
    )
    .unwrap();
    let mut span = h.tracer("it", Some("1")).start("grpc-span");
    span.end();
    h.force_flush(Some(Duration::from_secs(10))).await.unwrap();
    let (md, req) = wait_for(|| traces.seen.lock().unwrap().first().cloned()).await;
    assert_eq!(md.get("x-team").unwrap(), "obs");
    assert_eq!(
        req.resource_spans[0].scope_spans[0].spans[0].name,
        "grpc-span"
    );
    h.shutdown().await.unwrap();
}
