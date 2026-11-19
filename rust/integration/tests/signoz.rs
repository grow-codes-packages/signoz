//! Calls every public function of the crate against the real SigNoz stack from
//! `docker/compose.yml` with round-trip assertions (send → query back).

use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use grow_signoz as sdk;
use grow_signoz_integration::load_bootstrap_env;
use opentelemetry::trace::{
    Link, Span as _, SpanBuilder, SpanKind as OtelKind, Status, TraceContextExt, Tracer as _,
};
use opentelemetry::Context;
use sdk::logging::{
    attrs, LogClient, LogInput, LogQueryClient, LogRecord, RecordCallback, SearchOptions, Severity,
    SortOrder, TailOptions, WaitOptions,
};
use sdk::tracing::{
    DependencyOptions, Protocol, SpanKind, StatusCode, TraceSearchQuery, TracingConfig,
    WaitForTraceOptions,
};
use sdk::{
    and_filters, build_otlp_logs_request, AggregateBuilder, ErrorCode, FieldValuesOptions,
    FilterBuilder, ListFieldsOptions, LogOptions, LogQuery, OtlpLogExporter, Signal, SignozClient,
    SignozConfig, SignozQueryClient, TimeRange, TraceQuery,
};
use serde_json::json;

fn run_id() -> String {
    sdk::testing::run_id()
}

fn ago(d: Duration) -> SystemTime {
    SystemTime::now() - d
}

/// Polls `f` every second for up to two minutes.
async fn poll<F, Fut>(what: &str, mut f: F)
where
    F: FnMut() -> Fut,
    Fut: std::future::Future<Output = sdk::Result<bool>>,
{
    let deadline = Instant::now() + Duration::from_secs(120);
    let mut last = None;
    while Instant::now() < deadline {
        match f().await {
            Ok(true) => return,
            Ok(false) => {}
            Err(e) => last = Some(e),
        }
        tokio::time::sleep(Duration::from_secs(1)).await;
    }
    panic!("{what}: condition not met (last error {last:?})");
}

/// Polls `query_logs` until at least `n` records match.
async fn wait_for_logs(q: &SignozQueryClient, filter: &str, n: usize) -> Vec<LogRecord> {
    let deadline = Instant::now() + Duration::from_secs(90);
    loop {
        let page = q
            .query_logs(LogQuery::new(filter).range(Some(ago(Duration::from_secs(1800))), None))
            .await
            .expect("query_logs");
        if page.records.len() >= n || Instant::now() > deadline {
            return page.records;
        }
        tokio::time::sleep(Duration::from_secs(1)).await;
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn signoz_round_trip() {
    load_bootstrap_env();
    if std::env::var("GROW_SIGNOZ_URL").is_err() {
        eprintln!("GROW_SIGNOZ_URL is not set; run through docker/compose.yml");
        return;
    }
    let run = run_id();
    let cfg = SignozConfig::from_env().expect("config");
    cfg.validate().unwrap();
    let key = cfg
        .api_key()
        .expect("api key from bootstrap")
        .expose_secret()
        .to_owned();
    assert!(
        !format!("{cfg:?}").contains(&key),
        "api key must be redacted"
    );
    let client = SignozClient::new(cfg.clone()).unwrap();
    let q = client.query();

    // ---- core
    let h = client.health().await.unwrap();
    assert!(h.healthy && h.status == "ok", "{h:?}");
    client.ping().await.unwrap();
    match client.server_version().await {
        Ok(v) => assert!(!v.is_empty()),
        Err(e) => assert_eq!(e.code(), ErrorCode::Unsupported, "{e}"),
    }
    let bad = SignozClient::new(
        SignozConfig::builder()
            .url(cfg.url().as_str())
            .api_key("wrong-key-0000")
            .build()
            .unwrap(),
    )
    .unwrap();
    assert_eq!(
        bad.ping().await.unwrap_err().code(),
        ErrorCode::Authentication
    );
    assert_eq!(
        bad.query()
            .query_logs(LogQuery::default())
            .await
            .unwrap_err()
            .code(),
        ErrorCode::Authentication
    );
    bad.close().await;
    let env_client = SignozClient::from_env().unwrap();
    assert_eq!(env_client.config().url(), cfg.url());
    env_client.close().await;
    let grpc_endpoint = std::env::var("SIGNOZ_OTLP_GRPC_ENDPOINT")
        .unwrap_or_else(|_| cfg.otlp_endpoint().replace("4318", "4317"));
    let grpc_cfg = SignozConfig::builder()
        .otlp_protocol(Protocol::Grpc)
        .otlp_endpoint(&grpc_endpoint)
        .build_from_env()
        .unwrap();
    assert_eq!(grpc_cfg.otlp_protocol(), Protocol::Grpc);

    // ---- live tail first, so it sees the records sent below
    let service = format!("rust-it-{run}");
    let tailed: Arc<Mutex<Vec<LogRecord>>> = Arc::default();
    let sink = tailed.clone();
    let cb: RecordCallback = Arc::new(move |r| sink.lock().unwrap().push(r));
    let tail = q
        .tail(
            &FilterBuilder::new()
                .contains("body", &format!("tail-{run}"))
                .expression(),
            cb,
            TailOptions::default(),
        )
        .await
        .unwrap();

    // ---- logs: send and query back
    let trace_id = "4bf92f3577b34da6a3ce929d0e0e4736";
    let span_id = "00f067aa0ba902b7";
    let lc = client
        .log_client(
            LogOptions::service(&service)
                .service_version("1.0.0")
                .environment("it"),
        )
        .unwrap();
    let special = format!("special {run} it's 50% a_b back\\slash ünïcode");
    lc.send(
        LogInput::new(format!("hello {run}"))
            .attributes(attrs! {"rust.it.run" => run.as_str(), "seq" => 7, "ok" => true, "password" => format!("hunter2-{run}")})
            .into(),
    );
    lc.send(
        LogInput::new(format!("error {run}"))
            .severity(Severity::Error)
            .trace(trace_id, span_id)
            .attributes(attrs! {"rust.it.run" => run.as_str(), "seq" => 8})
            .into(),
    );
    lc.send_batch(vec![
        special.as_str().into(),
        format!("tail-{run} match").into(),
        format!("tail-x{run} nomatch").into(),
    ]);
    lc.logger(Some("it"), attrs! {"component" => "logger"})
        .warn(format!("logger {run}"), attrs! {});
    lc.flush(None).await.unwrap();
    let st = lc.stats();
    assert_eq!((st.sent, st.dropped, st.failed), (6, 0, 0), "{st:?}");
    lc.close().await.unwrap();

    let by_service = FilterBuilder::new().eq("service.name", service.as_str());
    let recs = wait_for_logs(q, &by_service.expression(), 6).await;
    assert_eq!(recs.len(), 6);
    let expr = by_service
        .clone()
        .eq("rust.it.run", run.as_str())
        .in_list("severity_text", ["ERROR", "FATAL"])
        .gte("seq", 8)
        .exists("rust.it.run")
        .build()
        .unwrap();
    let res = q
        .query_logs(LogQuery::new(expr).range(Some(ago(Duration::from_secs(600))), None))
        .await
        .unwrap();
    assert_eq!(res.records.len(), 1);
    let r = &res.records[0];
    assert_eq!(r.body, format!("error {run}"));
    assert_eq!(
        (r.severity, r.severity_text.as_str()),
        (Severity::Error, "ERROR")
    );
    assert_eq!(r.trace_id.as_deref(), Some(trace_id));
    assert_eq!(r.span_id.as_deref(), Some(span_id));
    assert_eq!(r.attributes["seq"], json!(8));
    assert_eq!(r.resource["service.name"], json!(service));
    assert_eq!(r.resource["service.version"], json!("1.0.0"));
    assert_eq!(r.resource["deployment.environment"], json!("it"));
    let hello = q
        .wait_for_log(&format!("hello {run}"), WaitOptions::default())
        .await
        .unwrap();
    assert_eq!(hello.attributes["password"], json!("[REDACTED]"));
    assert_eq!(hello.attributes["ok"], json!(true));
    let base = || FilterBuilder::new().eq("service.name", service.as_str());
    let checks: Vec<(String, usize)> = vec![
        (
            base()
                .contains("body", "it's 50% a_b back\\slash")
                .expression(),
            1,
        ),
        (base().contains("body", "50%_a").expression(), 0),
        (base().not_contains("body", &run).expression(), 0),
        (
            base().like("body", &format!("tail-%{run}%")).expression(),
            2,
        ),
        (
            base()
                .regexp("body", &format!("^tail-x?{run}"))
                .expression(),
            2,
        ),
        (base().text(&format!("logger {run}")).expression(), 1),
        (
            base()
                .or([
                    FilterBuilder::new().eq("seq", 7),
                    FilterBuilder::new().eq("seq", 8),
                ])
                .expression(),
            2,
        ),
        (
            base()
                .not(FilterBuilder::new().exists("seq"))
                .not_exists("rust.it.run")
                .expression(),
            4,
        ),
        (
            base()
                .not_in("severity_text", ["ERROR"])
                .ne("seq", 7)
                .lt("seq", 100)
                .lte("seq", 8)
                .gt("seq", 1)
                .expression(),
            0,
        ),
        (
            and_filters([base().expression(), "seq = 7 OR seq = 8".to_owned()]),
            2,
        ),
    ];
    for (expr, want) in checks {
        let res = q
            .query_logs(LogQuery::new(&expr).limit(100).select_fields(["body"]))
            .await
            .unwrap();
        assert_eq!(res.records.len(), want, "{expr}");
    }
    assert_eq!(
        q.query_logs(LogQuery::new("body CONTAINS ((("))
            .await
            .unwrap_err()
            .code(),
        ErrorCode::Validation
    );
    let warn = q
        .query_logs(LogQuery::new(format!("nosuchfield_{run} = 'x'")))
        .await
        .unwrap();
    assert!(
        warn.records.is_empty() && !warn.warnings.is_empty(),
        "{warn:?}"
    );

    // ---- exporters: OTLP/gRPC log client and the raw exporter
    let glc =
        sdk::new_log_client(&grpc_cfg, LogOptions::service(format!("{service}-grpc"))).unwrap();
    glc.send(format!("grpc {run}").into());
    glc.close().await.unwrap();
    let exp = OtlpLogExporter::new(&cfg).unwrap();
    let mut raw = LogRecord::new(Severity::Debug, format!("raw {run}"));
    raw.resource
        .insert("service.name".into(), json!(format!("{service}-raw")));
    raw.attributes.insert("f".into(), json!(1.5));
    assert_eq!(
        build_otlp_logs_request(std::slice::from_ref(&raw))
            .resource_logs
            .len(),
        1
    );
    let res = sdk::logging::LogExporter::export(
        &exp,
        std::slice::from_ref(&raw),
        sdk::logging::ExportOptions::new(true),
    )
    .await
    .unwrap();
    assert_eq!(res.accepted, 1);
    sdk::logging::LogExporter::shutdown(&exp).await.unwrap();
    for body in [format!("grpc {run}"), format!("raw {run}")] {
        let r = q
            .wait_for_log(
                &body,
                WaitOptions::default().timeout(Duration::from_secs(60)),
            )
            .await
            .unwrap();
        assert!(r.resource["service.name"]
            .as_str()
            .unwrap()
            .starts_with(&service));
        if body.starts_with("raw") {
            assert_eq!(r.severity, Severity::Debug);
            assert_eq!(r.attributes["f"], json!(1.5));
        }
    }

    // ---- tail: only the matching record arrived
    let deadline = Instant::now() + Duration::from_secs(60);
    while tailed.lock().unwrap().is_empty() && Instant::now() < deadline {
        tokio::time::sleep(Duration::from_millis(500)).await;
    }
    tokio::time::sleep(Duration::from_secs(2)).await;
    {
        let got = tailed.lock().unwrap();
        assert_eq!(got.len(), 1, "{got:?}");
        assert_eq!(got[0].body, format!("tail-{run} match"));
        assert_eq!(got[0].resource["service.name"], json!(service));
    }
    tail.stop();

    // ---- paging with a shared millisecond
    let page_service = format!("{service}-page");
    let plc = client
        .log_client(LogOptions::service(&page_service))
        .unwrap();
    let base_ms = (SystemTime::now() - Duration::from_secs(60))
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_millis() as u64;
    let base_t = UNIX_EPOCH + Duration::from_millis(base_ms);
    let ms = Duration::from_millis(1);
    let offsets = [
        Duration::ZERO,
        ms,
        ms + Duration::from_nanos(10),
        ms + Duration::from_nanos(20),
        2 * ms,
        2 * ms,
        3 * ms,
    ];
    for (i, off) in offsets.iter().enumerate() {
        plc.send(
            LogInput::new(format!("page {run} {i}"))
                .timestamp(base_t + *off)
                .into(),
        );
    }
    plc.close().await.unwrap();
    let page_filter = FilterBuilder::new()
        .eq("service.name", page_service.as_str())
        .expression();
    assert_eq!(
        wait_for_logs(q, &page_filter, offsets.len()).await.len(),
        offsets.len()
    );
    for order in [SortOrder::Desc, SortOrder::Asc] {
        let mut bodies = Vec::new();
        let mut cursor: Option<String> = None;
        for _ in 0..20 {
            let mut lq = LogQuery::new(&page_filter).limit(2).order(order);
            lq.cursor = cursor.take();
            let res = q.query_logs(lq).await.unwrap();
            bodies.extend(res.records.into_iter().map(|r| r.body));
            match res.next_cursor {
                Some(c) => cursor = Some(c),
                None => break,
            }
        }
        let mut unique = bodies.clone();
        unique.sort();
        unique.dedup();
        assert_eq!(
            (bodies.len(), unique.len()),
            (offsets.len(), offsets.len()),
            "{order:?}: {bodies:?}"
        );
        let first = if order == SortOrder::Asc { 0 } else { 6 };
        assert_eq!(bodies[0], format!("page {run} {first}"));
    }
    let page = client
        .logs()
        .search(&page_filter, SearchOptions::default().limit(3))
        .await
        .unwrap();
    assert_eq!(page.records.len(), 3);
    assert!(page.next_cursor.is_some());

    // ---- fields, streams, labels, values
    poll("list_labels", || async {
        Ok(client
            .logs()
            .list_labels()
            .await?
            .contains(&"rust.it.run".to_owned()))
    })
    .await;
    let fields = q
        .list_log_fields(ListFieldsOptions {
            search_text: Some("rust.it".into()),
            limit: 50,
        })
        .await
        .unwrap();
    assert!(
        fields.iter().any(|f| f.name == "rust.it.run"
            && f.context == "attribute"
            && f.data_type == "string"),
        "{fields:?}"
    );
    poll("list_streams", || async {
        Ok(client.logs().list_streams().await?.contains(&service))
    })
    .await;
    poll("list_field_values", || async {
        let vals = q
            .list_field_values(
                "service.name",
                FieldValuesOptions {
                    signal: Signal::Logs,
                    search_text: Some(run.clone()),
                },
            )
            .await?;
        Ok(vals.contains(&json!(service)))
    })
    .await;

    // ---- aggregate logs
    let agg = AggregateBuilder::new(Signal::Logs)
        .filter(by_service.expression())
        .count("n")
        .group_by(["service.name"])
        .order_by("n desc")
        .limit(5)
        .between(
            ago(Duration::from_secs(1800)),
            SystemTime::now() + Duration::from_secs(60),
        );
    let res = q.aggregate(agg.clone()).await.unwrap();
    assert_eq!(res.rows.len(), 1, "{res:?}");
    assert_eq!(res.rows[0].groups["service.name"], json!(service));
    assert_eq!(res.rows[0].values["n"], 6.0);
    let ts = q
        .aggregate(agg.step(Duration::from_secs(60)))
        .await
        .unwrap();
    let mut total = 0.0;
    for s in &ts.series {
        assert_eq!(s.alias, "n");
        total += s.points.iter().map(|p| p.value).sum::<f64>();
    }
    assert_eq!(total, 6.0, "{ts:?}");

    // ---- raw query_range
    let now_ms = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_millis() as i64;
    let raw = q
        .query_range(&json!({
            "start": now_ms - 3_600_000,
            "end": now_ms + 1_000,
            "requestType": "scalar",
            "compositeQuery": {"queries": [{
                "type": "builder_query",
                "spec": {
                    "name": "A",
                    "signal": "logs",
                    "filter": {"expression": by_service.expression()},
                    "aggregations": [{"expression": "count()"}]
                }
            }]}
        }))
        .await
        .unwrap();
    assert!(raw.data.to_string().contains("__result_0"), "{raw:?}");

    // ---- tracing round trip
    let trace_service = format!("rust-it-trace-{run}");
    let db_service = format!("rust-it-db-{run}");
    let h = client
        .init_tracing(
            TracingConfig::new(&trace_service)
                .with_service_version("1.0.0")
                .with_set_global(false),
        )
        .unwrap();
    let hdb = sdk::init_tracing(
        TracingConfig::new(&db_service)
            .with_protocol(Protocol::Grpc)
            .with_endpoint(&grpc_endpoint)
            .with_set_global(false),
    )
    .unwrap();
    let tracer = h.tracer("it", None);
    let db_tracer = hdb.tracer("it", None);
    let mut linked = tracer.start(format!("linked-{run}"));
    let linked_sc = linked.span_context().clone();
    linked.end();
    let root = tracer.build_with_context(
        SpanBuilder::from_name(format!("GET /it-{run}")).with_kind(OtelKind::Server),
        &Context::new(),
    );
    let root_cx = Context::new().with_span(root);
    let mut call = tracer.build_with_context(
        SpanBuilder::from_name("call-db")
            .with_kind(OtelKind::Client)
            .with_links(vec![Link::with_context(linked_sc.clone())]),
        &root_cx,
    );
    call.add_event("checkpoint", vec![]);
    let remote = Context::new().with_remote_span_context(call.span_context().clone());
    let mut server = db_tracer.build_with_context(
        SpanBuilder::from_name("SELECT users").with_kind(OtelKind::Server),
        &remote,
    );
    server.set_status(Status::error("boom"));
    server.end();
    call.end();
    root_cx.span().set_status(Status::Ok);
    root_cx.span().end();
    let root_trace = root_cx.span().span_context().trace_id().to_string();
    h.force_flush(None).await.unwrap();
    hdb.force_flush(None).await.unwrap();

    let traces = client.traces();
    let tr = traces
        .wait_for_trace(
            &root_trace,
            WaitForTraceOptions::default()
                .timeout(Duration::from_secs(60))
                .min_spans(3),
        )
        .await
        .unwrap();
    let by_name = |n: &str| tr.spans.iter().find(|s| s.name == n).cloned().unwrap();
    let (r, c, s) = (
        by_name(&format!("GET /it-{run}")),
        by_name("call-db"),
        by_name("SELECT users"),
    );
    assert_eq!(r.kind, SpanKind::Server);
    assert_eq!(r.status.code, StatusCode::Ok);
    assert!(r.parent_span_id.is_none());
    assert_eq!(r.service_name, trace_service);
    assert_eq!(c.kind, SpanKind::Client);
    assert_eq!(c.parent_span_id.as_deref(), Some(r.span_id.as_str()));
    assert_eq!(c.events.len(), 1);
    assert_eq!(c.events[0].name, "checkpoint");
    assert_eq!(c.links.len(), 1, "{c:?}");
    assert_eq!(c.links[0].span_id, linked_sc.span_id().to_string());
    assert_eq!(s.service_name, db_service);
    assert_eq!(s.parent_span_id.as_deref(), Some(c.span_id.as_str()));
    assert_eq!(s.status.code, StatusCode::Error);
    assert_eq!(s.status.message.as_deref(), Some("boom"));
    assert_eq!(tr.tree()[0].span.name, format!("GET /it-{run}"));

    let spans = q
        .query_traces(
            TraceQuery::service(&trace_service)
                .operation("call-db")
                .range(Some(ago(Duration::from_secs(600))), None),
        )
        .await
        .unwrap();
    assert_eq!(spans.spans.len(), 1, "{spans:?}");
    assert_eq!(spans.spans[0].trace_id, root_trace);
    assert!(spans.spans[0].start_micros > 0);
    assert_eq!(spans.spans[0].links.len(), 1);
    let mut search = TraceSearchQuery::service(&trace_service)
        .operation(format!("GET /it-{run}"))
        .limit(5);
    search.min_duration = Some(Duration::from_nanos(1));
    let sum = traces.search_traces(&search).await.unwrap();
    assert_eq!(sum.len(), 1, "{sum:?}");
    assert_eq!(sum[0].trace_id, root_trace);
    assert_eq!(sum[0].span_count, 3);
    assert!(sum[0].start_micros > 0);
    assert_eq!(
        sum[0].root_service_name.as_deref(),
        Some(trace_service.as_str())
    );
    let svcs = traces.list_services().await.unwrap();
    assert!(
        svcs.contains(&trace_service) && svcs.contains(&db_service),
        "{svcs:?}"
    );
    let svcs = q
        .list_services_in_range(TimeRange::since(ago(Duration::from_secs(600))))
        .await
        .unwrap();
    assert!(svcs.contains(&trace_service));
    let ops = traces.list_operations(&trace_service).await.unwrap();
    assert_eq!(
        ops,
        vec![
            format!("GET /it-{run}"),
            "call-db".to_owned(),
            format!("linked-{run}")
        ]
    );
    let ops = q
        .list_operations_in_range(&db_service, TimeRange::since(ago(Duration::from_secs(600))))
        .await
        .unwrap();
    assert_eq!(ops, vec!["SELECT users"]);
    let agg = q
        .aggregate(
            AggregateBuilder::new(Signal::Traces)
                .filter(
                    FilterBuilder::new()
                        .eq("service.name", trace_service.as_str())
                        .expression(),
                )
                .count("calls")
                .percentile(99, "duration_nano", "p99")
                .group_by(["name"]),
        )
        .await
        .unwrap();
    assert_eq!(agg.rows.len(), 3, "{agg:?}");
    assert_eq!(agg.rows[0].values["calls"], 1.0);
    assert!(agg.rows[0].values["p99"] > 0.0);
    assert!(traces
        .get_trace("0af7651916cd43dd8448eb211c80319c")
        .await
        .unwrap()
        .is_none());
    h.shutdown().await.unwrap();
    hdb.shutdown().await.unwrap();

    // ---- dependencies
    poll("get_dependencies", || async {
        let deps = traces
            .get_dependencies(DependencyOptions {
                start: Some(ago(Duration::from_secs(1800))),
                end: None,
            })
            .await?;
        Ok(deps
            .iter()
            .any(|d| d.parent == trace_service && d.child == db_service && d.call_count >= 1))
    })
    .await;

    client.close().await;
    client.close().await;
}
