//! Query client against a mock of the SigNoz query API (recorded fixtures).

mod support;

use std::time::{Duration, SystemTime, UNIX_EPOCH};

use grow_signoz as sdk;
use sdk::logging::{SearchOptions, Severity, SortOrder, WaitOptions};
use sdk::tracing::{DependencyOptions, SpanKind, StatusCode, TraceSearchQuery};
use sdk::{
    AggregateBuilder, AggregateQuery, Aggregation, ErrorCode, FieldValuesOptions,
    ListFieldsOptions, LogQuery, Signal, TimeRange, TraceQuery,
};
use serde_json::{json, Value};
use support::{body, client, fixture_json, json_body, json_fixture, spec};
use wiremock::matchers::{header, method, path, query_param};
use wiremock::{Mock, MockServer, Request, ResponseTemplate};

const QR: &str = "/api/v5/query_range";

fn at(ms: u64) -> SystemTime {
    UNIX_EPOCH + Duration::from_millis(ms)
}

async fn mount_qr(server: &MockServer, resp: ResponseTemplate) {
    Mock::given(method("POST"))
        .and(path(QR))
        .respond_with(resp)
        .mount(server)
        .await;
}

fn last_body(reqs: &[Request]) -> Value {
    body(reqs.last().unwrap())
}

#[tokio::test]
async fn query_logs_maps_rows_and_request() {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path(QR))
        .and(header("SIGNOZ-API-KEY", "test-api-key-1234"))
        .respond_with(json_fixture(200, "queryLogs.raw.ok.json"))
        .mount(&server)
        .await;
    let c = client(&server.uri());
    let page = c
        .query()
        .query_logs(
            LogQuery::new("service.name = 'sdk-fe67ae4b'")
                .range(Some(at(1_000)), Some(at(2_000)))
                .select_fields(["body"]),
        )
        .await
        .unwrap();
    assert_eq!(page.records.len(), 6);
    assert!(page.next_cursor.is_none());
    assert!(page.warnings.is_empty());
    let r = &page.records[0];
    assert_eq!(r.body, "marker-fe67ae4b seq 5 ünïcode it's");
    assert_eq!(r.severity, Severity::Info);
    assert_eq!(r.severity_text, "INFO");
    assert_eq!(
        r.trace_id.as_deref(),
        Some("fe67ae4bfe67ae4bfe67ae4bfe67ae4b")
    );
    assert_eq!(r.span_id.as_deref(), Some("00f067aa0ba902b7"));
    assert_eq!(r.trace_flags, Some(1));
    assert_eq!(r.attributes["seq"], json!(5));
    assert_eq!(r.attributes["ok"], json!(true));
    assert_eq!(r.attributes["run.id"], json!("fe67ae4b"));
    assert_eq!(r.resource["service.name"], json!("sdk-fe67ae4b"));
    assert_eq!(
        r.timestamp,
        UNIX_EPOCH + Duration::from_nanos(1_795_033_260_005_000_000)
    );
    assert_eq!(page.records[5].severity, Severity::Error);

    let b = last_body(&server.received_requests().await.unwrap());
    assert_eq!(b["start"], json!(1000));
    assert_eq!(b["end"], json!(2000));
    assert_eq!(b["requestType"], json!("raw"));
    let s = &b["compositeQuery"]["queries"][0];
    assert_eq!(s["type"], json!("builder_query"));
    assert_eq!(s["spec"]["name"], json!("A"));
    assert_eq!(s["spec"]["signal"], json!("logs"));
    assert_eq!(s["spec"]["limit"], json!(100));
    assert_eq!(
        s["spec"]["filter"]["expression"],
        json!("service.name = 'sdk-fe67ae4b'")
    );
    assert_eq!(s["spec"]["selectFields"], json!([{"name": "body"}]));
    assert_eq!(
        s["spec"]["order"],
        json!([{"key": {"name": "timestamp"}, "direction": "desc"},
               {"key": {"name": "id"}, "direction": "desc"}])
    );
    let text = serde_json::to_string(&page).unwrap();
    assert!(text.contains("\"records\""));
}

#[tokio::test]
async fn query_logs_default_window_and_empty_rows() {
    let server = MockServer::start().await;
    mount_qr(&server, json_fixture(200, "queryLogs.empty.json")).await;
    let c = client(&server.uri());
    let page = c.query().query_logs(LogQuery::default()).await.unwrap();
    assert!(page.records.is_empty());
    assert!(page.next_cursor.is_none());
    let b = last_body(&server.received_requests().await.unwrap());
    let (s, e) = (b["start"].as_i64().unwrap(), b["end"].as_i64().unwrap());
    assert_eq!(e - s, 3_600_000);
    assert!(b["compositeQuery"]["queries"][0]["spec"]
        .get("filter")
        .is_none());
}

#[tokio::test]
async fn query_logs_unknown_field_warning() {
    let server = MockServer::start().await;
    mount_qr(
        &server,
        json_fixture(200, "queryLogs.unknownFieldWarning.json"),
    )
    .await;
    let c = client(&server.uri());
    let page = c
        .query()
        .query_logs(LogQuery::new("nosuchfield = 'x'"))
        .await
        .unwrap();
    assert!(page.records.is_empty());
    assert_eq!(page.warnings.len(), 1);
    assert!(page.warnings[0].contains("not found in metadata"));
}

#[tokio::test]
async fn query_logs_warning_message_fallback_and_redaction() {
    let server = MockServer::start().await;
    mount_qr(
        &server,
        json_body(
            200,
            json!({"status": "success", "data": {"warning": {"message": "key test-api-key-1234 odd", "warnings": []},
                   "data": {"results": []}}}),
        ),
    )
    .await;
    let page = client(&server.uri())
        .query()
        .query_logs(LogQuery::default())
        .await
        .unwrap();
    assert_eq!(page.warnings, vec!["key [REDACTED] odd".to_owned()]);
}

#[tokio::test]
async fn query_logs_errors() {
    let server = MockServer::start().await;
    Mock::given(path(QR))
        .and(|r: &Request| spec(r)["filter"]["expression"] == json!("bad ((("))
        .respond_with(json_fixture(400, "queryLogs.invalidFilter.json"))
        .mount(&server)
        .await;
    Mock::given(path(QR))
        .and(|r: &Request| spec(r)["filter"]["expression"] == json!("unauth"))
        .respond_with(json_fixture(401, "queryLogs.unauthenticated.json"))
        .mount(&server)
        .await;
    Mock::given(path(QR))
        .and(|r: &Request| spec(r)["filter"]["expression"] == json!("forbidden"))
        .respond_with(json_fixture(403, "error.forbidden.json"))
        .mount(&server)
        .await;
    Mock::given(path(QR))
        .and(|r: &Request| spec(r)["filter"]["expression"] == json!("html"))
        .respond_with(
            ResponseTemplate::new(200).set_body_raw("<!doctype html><html></html>", "text/html"),
        )
        .mount(&server)
        .await;
    Mock::given(path(QR))
        .and(|r: &Request| spec(r)["filter"]["expression"] == json!("htmlish"))
        .respond_with(ResponseTemplate::new(200).set_body_raw("  <HTML>x</HTML>", "text/plain"))
        .mount(&server)
        .await;
    Mock::given(path(QR))
        .and(|r: &Request| spec(r)["filter"]["expression"] == json!("garbage"))
        .respond_with(ResponseTemplate::new(200).set_body_raw("{nope", "application/json"))
        .mount(&server)
        .await;
    Mock::given(path(QR))
        .and(|r: &Request| spec(r)["filter"]["expression"] == json!("status"))
        .respond_with(json_body(200, json!({"status": "error", "data": null})))
        .mount(&server)
        .await;
    Mock::given(path(QR))
        .and(|r: &Request| spec(r)["filter"]["expression"] == json!("gone"))
        .respond_with(ResponseTemplate::new(501).set_body_raw(
            r#"{"status":"error","error":{"code":"unsupported","message":"nope"}}"#,
            "application/json",
        ))
        .mount(&server)
        .await;
    let q = client(&server.uri());
    let q = q.query();
    let run = |f: &'static str| async move { q.query_logs(LogQuery::new(f)).await.unwrap_err() };
    let e = run("bad (((").await;
    assert_eq!(e.code(), ErrorCode::Validation);
    assert_eq!(e.http_status(), Some(400));
    assert!(e.message().contains("Found 2 syntax errors"), "{e}");
    assert_eq!(run("unauth").await.code(), ErrorCode::Authentication);
    let e = run("forbidden").await;
    assert_eq!(e.code(), ErrorCode::PermissionDenied);
    assert!(e.message().contains("not authorized"));
    assert_eq!(run("html").await.code(), ErrorCode::Unsupported);
    assert_eq!(run("htmlish").await.code(), ErrorCode::Unsupported);
    assert_eq!(run("garbage").await.code(), ErrorCode::Unavailable);
    assert_eq!(run("status").await.code(), ErrorCode::Unavailable);
    assert_eq!(run("gone").await.code(), ErrorCode::Unsupported);

    // client-side validation
    let e = q
        .query_logs(LogQuery::default().limit(10_001))
        .await
        .unwrap_err();
    assert_eq!(e.code(), ErrorCode::Validation);
    let e = q
        .query_logs(LogQuery::default().range(Some(at(5)), Some(at(5))))
        .await
        .unwrap_err();
    assert_eq!(e.code(), ErrorCode::Validation);
    for bad in [
        "%%%",
        "e30",
        "eyJ2IjoyLCJzIjowLCJlIjowLCJvIjoiZGVzYyIsIm0iOjB9",
    ] {
        let e = q
            .query_logs(LogQuery::default().cursor(bad))
            .await
            .unwrap_err();
        assert_eq!(e.code(), ErrorCode::Validation, "{bad}");
        assert!(e.message().contains("invalid cursor"));
    }
}

fn row(id: &str, ns: u64) -> Value {
    json!({"data": {"id": id, "timestamp": ns, "body": id, "severity_text": "", "severity_number": 0,
                    "trace_id": "", "span_id": ""}})
}

fn rows_page(rows: Vec<Value>) -> ResponseTemplate {
    json_body(
        200,
        json!({"status": "success", "data": {"type": "raw", "data": {"results": [{"rows": rows, "nextCursor": "x"}]}}}),
    )
}

#[tokio::test]
async fn query_logs_cursor_repeats_boundary_millisecond() {
    let server = MockServer::start().await;
    let ms = 1_795_033_260_000u64;
    // page 2 (desc): filter carries the excluded ids
    Mock::given(path(QR))
        .and(|r: &Request| {
            spec(r)["filter"]["expression"]
                .as_str()
                .is_some_and(|f| f.contains("NOT IN"))
        })
        .respond_with(rows_page(vec![
            row("c", (ms + 1) * 1_000_000),
            row("d", ms * 1_000_000),
        ]))
        .up_to_n_times(1)
        .mount(&server)
        .await;
    // page 3: fewer rows than the limit
    Mock::given(path(QR))
        .and(|r: &Request| {
            spec(r)["filter"]["expression"]
                .as_str()
                .is_some_and(|f| f.contains("'d'"))
        })
        .respond_with(rows_page(vec![row("e", ms * 1_000_000 - 1)]))
        .mount(&server)
        .await;
    // page 1
    mount_qr(
        &server,
        rows_page(vec![
            row("a", (ms + 1) * 1_000_000 + 500),
            row("b", (ms + 1) * 1_000_000 + 10),
        ]),
    )
    .await;
    let c = client(&server.uri());
    let q = c.query();
    let p1 = q
        .query_logs(
            LogQuery::new("service.name = 'x'")
                .limit(2)
                .range(Some(at(ms - 10_000)), Some(at(ms + 10_000))),
        )
        .await
        .unwrap();
    assert_eq!(p1.records.len(), 2);
    assert_eq!(p1.records[0].severity, Severity::Info);
    assert_eq!(p1.records[0].severity_text, "INFO");
    assert!(p1.records[0].trace_id.is_none() && p1.records[0].trace_flags.is_none());
    let c1 = p1.next_cursor.clone().unwrap();
    let p2 = q
        .query_logs(LogQuery::new("service.name = 'x'").limit(2).cursor(c1))
        .await
        .unwrap();
    let reqs = server.received_requests().await.unwrap();
    let b2 = body(&reqs[1]);
    assert_eq!(b2["start"], json!(ms - 10_000));
    assert_eq!(b2["end"], json!(ms + 2), "end = boundary ms + 1");
    assert_eq!(
        b2["compositeQuery"]["queries"][0]["spec"]["filter"]["expression"],
        json!("(service.name = 'x') AND (id NOT IN ('a', 'b'))")
    );
    assert_eq!(p2.records.len(), 2);
    let p3 = q
        .query_logs(
            LogQuery::new("service.name = 'x'")
                .limit(2)
                .cursor(p2.next_cursor.unwrap()),
        )
        .await
        .unwrap();
    let b3 = body(&server.received_requests().await.unwrap()[2]);
    assert_eq!(b3["end"], json!(ms + 1));
    assert_eq!(
        b3["compositeQuery"]["queries"][0]["spec"]["filter"]["expression"],
        json!("(service.name = 'x') AND (id NOT IN ('d'))")
    );
    assert_eq!(p3.records.len(), 1);
    assert!(p3.next_cursor.is_none());
}

#[tokio::test]
async fn query_logs_cursor_asc_keeps_previous_ids_in_same_millisecond() {
    let server = MockServer::start().await;
    let ms = 1_795_033_260_000u64;
    mount_qr(
        &server,
        rows_page(vec![row("a", ms * 1_000_000), row("b", ms * 1_000_000 + 7)]),
    )
    .await;
    let c = client(&server.uri());
    let q = c.query();
    let p1 = q
        .query_logs(
            LogQuery::default()
                .limit(2)
                .order(SortOrder::Asc)
                .range(Some(at(ms - 5)), Some(at(ms + 5))),
        )
        .await
        .unwrap();
    let p2 = q
        .query_logs(LogQuery::default().limit(2).cursor(p1.next_cursor.unwrap()))
        .await
        .unwrap();
    let b2 = body(&server.received_requests().await.unwrap()[1]);
    assert_eq!(b2["start"], json!(ms));
    assert_eq!(b2["end"], json!(ms + 5));
    let s = &b2["compositeQuery"]["queries"][0]["spec"];
    assert_eq!(s["order"][0]["direction"], json!("asc"));
    assert_eq!(s["filter"]["expression"], json!("id NOT IN ('a', 'b')"));
    // same millisecond again: previous ids are kept, new ones appended once
    let _p3 = q
        .query_logs(LogQuery::default().limit(2).cursor(p2.next_cursor.unwrap()))
        .await
        .unwrap();
    let b3 = body(&server.received_requests().await.unwrap()[2]);
    assert_eq!(
        b3["compositeQuery"]["queries"][0]["spec"]["filter"]["expression"],
        json!("id NOT IN ('a', 'b')")
    );
}

#[tokio::test]
async fn query_logs_rfc3339_fallback_and_severity_text() {
    let server = MockServer::start().await;
    mount_qr(
        &server,
        rows_page(vec![
            json!({"timestamp": "2026-11-18T20:21:00.005Z", "data": {"id": "x", "body": "b", "severity_text": "warn",
                   "trace_id": "ABCDEF0123456789ABCDEF0123456789", "span_id": "00F067AA0BA902B7", "trace_flags": "1"}}),
            json!({"data": {"id": "y", "timestamp": "2026-11-18T20:21:00Z", "severity_number": 21}}),
            json!({"data": {"id": "z", "timestamp": "nonsense"}}),
        ]),
    )
    .await;
    let page = client(&server.uri())
        .query()
        .query_logs(LogQuery::default())
        .await
        .unwrap();
    let r = &page.records[0];
    assert_eq!(r.severity, Severity::Warn);
    assert_eq!(r.severity_text, "warn");
    assert_eq!(
        r.trace_id.as_deref(),
        Some("abcdef0123456789abcdef0123456789")
    );
    assert_eq!(r.span_id.as_deref(), Some("00f067aa0ba902b7"));
    assert_eq!(r.trace_flags, Some(1));
    assert_eq!(
        r.timestamp,
        UNIX_EPOCH + Duration::from_millis(1_795_033_260_005)
    );
    assert_eq!(page.records[1].severity, Severity::Fatal);
    assert_eq!(page.records[1].severity_text, "FATAL");
    assert_eq!(page.records[2].timestamp, UNIX_EPOCH);
}

#[tokio::test]
async fn search_wait_and_trait_object() {
    let server = MockServer::start().await;
    mount_qr(&server, json_fixture(200, "queryLogs.page1.json")).await;
    let c = client(&server.uri());
    let logs = c.logs();
    let page = logs
        .search("marker", SearchOptions::default().limit(2))
        .await
        .unwrap();
    assert_eq!(page.records.len(), 2);
    assert!(page.next_cursor.is_some());
    assert!(page.total.is_none());
    let found = logs
        .wait_for_log(
            "marker",
            WaitOptions::default()
                .timeout(Duration::from_secs(5))
                .predicate(|r| r.body.contains("seq 4")),
        )
        .await
        .unwrap();
    assert!(found.body.contains("seq 4"));
    let spec0 = spec(&server.received_requests().await.unwrap()[0]);
    assert_eq!(spec0["filter"]["expression"], json!("marker"));
    assert_eq!(spec0["limit"], json!(2));
}

#[tokio::test]
async fn log_fields_labels_values_streams() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/api/v1/fields/keys"))
        .and(query_param("searchText", "run"))
        .and(query_param("limit", "5"))
        .respond_with(json_fixture(200, "listLogFields.search.json"))
        .mount(&server)
        .await;
    Mock::given(method("GET"))
        .and(path("/api/v1/fields/keys"))
        .and(query_param("signal", "logs"))
        .respond_with(json_body(
            200,
            json!({"status": "success", "data": {"keys": {
                "service.name": [{"name": "service.name", "fieldContext": "resource", "fieldDataType": "string"},
                                  {"name": "", "fieldContext": "attribute", "fieldDataType": "string", "description": "dup"}],
                "body": [{"name": "body", "fieldContext": "log", "fieldDataType": "string"}]}}}),
        ))
        .mount(&server)
        .await;
    Mock::given(method("GET"))
        .and(path("/api/v1/fields/values"))
        .and(query_param("name", "service.name"))
        .and(query_param("signal", "logs"))
        .respond_with(json_body(
            200,
            json!({"status": "success", "data": {"values": {
                "stringValues": ["b-svc", "a-svc", "b-svc"], "numberValues": [3, 1.5], "boolValues": [true, null]}}}),
        ))
        .mount(&server)
        .await;
    Mock::given(method("GET"))
        .and(path("/api/v1/fields/values"))
        .and(query_param("signal", "traces"))
        .and(query_param("searchText", "fe67"))
        .respond_with(json_fixture(200, "listStreams.fieldValues.json"))
        .mount(&server)
        .await;
    let c = client(&server.uri());
    let q = c.query();
    let fields = q
        .list_log_fields(ListFieldsOptions {
            search_text: Some("run".into()),
            limit: 5,
        })
        .await
        .unwrap();
    assert_eq!(fields.len(), 1);
    assert_eq!(
        (
            fields[0].name.as_str(),
            fields[0].context.as_str(),
            fields[0].data_type.as_str()
        ),
        ("run.id", "attribute", "string")
    );
    let all = q
        .list_log_fields(ListFieldsOptions::default())
        .await
        .unwrap();
    assert_eq!(all.len(), 3);
    assert_eq!(all[1].name, "service.name");
    assert_eq!(all[1].context, "attribute");
    assert_eq!(all[1].description.as_deref(), Some("dup"));
    assert!(serde_json::to_string(&all[0]).unwrap().contains("dataType"));
    assert_eq!(
        c.logs().list_labels().await.unwrap(),
        vec!["body", "service.name"]
    );
    let values = q
        .list_field_values("service.name", FieldValuesOptions::default())
        .await
        .unwrap();
    assert_eq!(
        values,
        vec![
            json!(1.5),
            json!(3),
            json!("a-svc"),
            json!("b-svc"),
            json!(true)
        ]
    );
    assert_eq!(
        c.logs().list_streams().await.unwrap(),
        vec!["a-svc", "b-svc"]
    );
    let traces = q
        .list_field_values(
            "service.name",
            FieldValuesOptions {
                signal: Signal::Traces,
                search_text: Some("fe67".into()),
            },
        )
        .await
        .unwrap();
    assert_eq!(traces.len(), 4);
    let e = q
        .list_field_values("bad name", FieldValuesOptions::default())
        .await
        .unwrap_err();
    assert_eq!(e.code(), ErrorCode::Validation);
}

#[tokio::test]
async fn aggregate_scalar_maps_aliases_by_index() {
    let server = MockServer::start().await;
    mount_qr(&server, json_fixture(200, "listOperations.scalar.json")).await;
    let c = client(&server.uri());
    let res = c
        .query()
        .aggregate(
            AggregateBuilder::new(Signal::Traces)
                .filter("service.name = 'sdk'")
                .count("calls")
                .percentile(99, "duration_nano", "p99")
                .group_by(["name"])
                .order_by("calls desc")
                .limit(5)
                .between(at(1_000), at(61_000)),
        )
        .await
        .unwrap();
    assert_eq!(res.rows.len(), 2);
    assert_eq!(res.rows[0].groups["name"], json!("GET /verify"));
    assert_eq!(res.rows[0].values["calls"], 1.0);
    assert!(res.rows[0].values["p99"] > 0.0);
    assert!(res.series.is_empty());
    let b = last_body(&server.received_requests().await.unwrap());
    assert_eq!(b["requestType"], json!("scalar"));
    let s = &b["compositeQuery"]["queries"][0]["spec"];
    assert_eq!(
        s["aggregations"],
        json!([{"expression": "count()", "alias": "calls"}, {"expression": "p99(duration_nano)", "alias": "p99"}])
    );
    assert_eq!(
        s["groupBy"],
        json!([{"name": "name", "fieldContext": "span", "fieldDataType": "string"}])
    );
    assert_eq!(
        s["order"],
        json!([{"key": {"name": "calls"}, "direction": "desc"}])
    );
    assert_eq!(s["limit"], json!(5));
    assert_eq!(s["signal"], json!("traces"));
}

#[tokio::test]
async fn aggregate_time_series_and_unnamed() {
    let server = MockServer::start().await;
    Mock::given(path(QR))
        .and(|r: &Request| body(r)["requestType"] == json!("time_series"))
        .respond_with(json_body(
            200,
            json!({"status": "success", "data": {"data": {"results": [{"aggregations": [
                {"index": 0, "series": [{"labels": [{"key": {"name": "service.name"}, "value": "svc"}],
                                         "values": [{"timestamp": 1795033260000u64, "value": 12}, {"timestamp": 1795033320000u64, "value": 3.5}]}]},
                {"index": 7, "series": [{"values": [{"timestamp": 1, "value": "x"}]}]}]}]}}}),
        ))
        .mount(&server)
        .await;
    mount_qr(&server, json_fixture(200, "aggregate.count.json")).await;
    let c = client(&server.uri());
    let q = c.query();
    let ts = q
        .aggregate(
            AggregateBuilder::new(Signal::Logs)
                .count("n")
                .group_by(["service.name"])
                .step(Duration::from_millis(1500)),
        )
        .await
        .unwrap();
    assert_eq!(ts.series.len(), 2);
    assert_eq!(ts.series[0].alias, "n");
    assert_eq!(ts.series[0].labels["service.name"], json!("svc"));
    assert_eq!(ts.series[0].points.len(), 2);
    assert_eq!(ts.series[0].points[0].value, 12.0);
    assert_eq!(ts.series[0].points[0].time, at(1_795_033_260_000));
    assert_eq!(ts.series[1].alias, "__result_7");
    assert_eq!(ts.series[1].points[0].value, 0.0);
    let b = body(&server.received_requests().await.unwrap()[0]);
    let s = &b["compositeQuery"]["queries"][0]["spec"];
    assert_eq!(s["stepInterval"], json!(1));
    assert_eq!(
        s["groupBy"][0],
        json!({"name": "service.name", "fieldContext": "resource", "fieldDataType": "string"})
    );

    // scalar without groupBy and without alias; zero step is scalar too
    let res = q
        .aggregate(AggregateQuery {
            signal: Signal::Logs,
            aggregations: vec![
                Aggregation::new("count()", ""),
                Aggregation::new("avg(x)", "a"),
            ],
            group_by: vec!["k8s.pod".into()],
            step_interval: Some(Duration::ZERO),
            order_by: Some("count() ".into()),
            ..AggregateQuery::default()
        })
        .await
        .unwrap();
    assert_eq!(res.rows.len(), 1);
    assert_eq!(res.rows[0].values["count()"], 12.0);
    let b = last_body(&server.received_requests().await.unwrap());
    let s = &b["compositeQuery"]["queries"][0]["spec"];
    assert_eq!(s["groupBy"][0], json!({"name": "k8s.pod"}));
    assert_eq!(s["aggregations"][0], json!({"expression": "count()"}));
    assert_eq!(s["order"][0]["direction"], json!("desc"));
    let built = AggregateBuilder::new(Signal::Traces)
        .count_distinct("trace_id", "t")
        .avg("d", "a")
        .sum("d", "s")
        .min("d", "mi")
        .max("d", "ma")
        .expr("rate()", "r")
        .query();
    let exprs: Vec<&str> = built
        .aggregations
        .iter()
        .map(|a| a.expression.as_str())
        .collect();
    assert_eq!(
        exprs,
        [
            "count_distinct(trace_id)",
            "avg(d)",
            "sum(d)",
            "min(d)",
            "max(d)",
            "rate()"
        ]
    );
}

#[tokio::test]
async fn aggregate_validation_and_no_results() {
    let server = MockServer::start().await;
    mount_qr(
        &server,
        json_body(
            200,
            json!({"status": "success", "data": {"data": {"results": []}}}),
        ),
    )
    .await;
    let c = client(&server.uri());
    let q = c.query();
    let empty = q
        .aggregate(AggregateBuilder::new(Signal::Logs).count("n"))
        .await
        .unwrap();
    assert!(empty.rows.is_empty() && empty.series.is_empty());
    let cases: Vec<AggregateQuery> = vec![
        AggregateBuilder::new(Signal::Logs).query(),
        AggregateBuilder::new(Signal::Logs).expr(" ", "x").query(),
        AggregateBuilder::new(Signal::Logs)
            .count("n")
            .group_by(["bad field"])
            .query(),
        AggregateBuilder::new(Signal::Logs)
            .count("n")
            .order_by("n sideways")
            .query(),
        AggregateBuilder::new(Signal::Logs)
            .count("n")
            .limit(10_001)
            .query(),
    ];
    for case in cases {
        let e = q.aggregate(case.clone()).await.unwrap_err();
        assert_eq!(e.code(), ErrorCode::Validation, "{case:?}");
    }
}

#[tokio::test]
async fn query_traces_maps_spans() {
    let server = MockServer::start().await;
    mount_qr(&server, json_fixture(200, "queryTraces.raw.ok.json")).await;
    let c = client(&server.uri());
    let res = c
        .query()
        .query_traces(
            TraceQuery::service("sdk-fe67ae4b")
                .operation("db.query")
                .filter("duration_nano >= 1")
                .range(Some(at(1_000)), Some(at(5_000)))
                .limit(7),
        )
        .await
        .unwrap();
    assert_eq!(res.spans.len(), 1);
    let s = &res.spans[0];
    assert_eq!(s.kind, SpanKind::Client);
    assert_eq!(s.name, "db.query");
    assert_eq!(s.duration_micros, 100_000);
    assert_eq!(s.end_micros, s.start_micros + 100_000);
    assert!(s.start_micros > 0);
    assert_eq!(s.events.len(), 1);
    assert_eq!(s.events[0].attributes["exception.message"], json!("boom"));
    assert_eq!(s.links.len(), 1, "the parent reference is dropped");
    assert_eq!(s.links[0].span_id, "b7ad6b7169203331");
    assert_eq!(s.attributes["db.system"], json!("postgresql"));
    let b = last_body(&server.received_requests().await.unwrap());
    let sp = &b["compositeQuery"]["queries"][0]["spec"];
    assert_eq!(
        sp["filter"]["expression"],
        json!("(service.name = 'sdk-fe67ae4b' AND name = 'db.query') AND (duration_nano >= 1)")
    );
    assert_eq!(sp["signal"], json!("traces"));
    assert_eq!(sp["limit"], json!(7));
    assert!(sp.get("offset").is_none());
    let q = TraceQuery {
        order: SortOrder::Asc,
        offset: 3,
        ..TraceQuery::default()
    };
    let _ = c.query().query_traces(q).await.unwrap();
    let sp = spec(server.received_requests().await.unwrap().last().unwrap());
    assert_eq!(sp["offset"], json!(3));
    assert_eq!(sp["order"][0]["direction"], json!("asc"));
    assert!(sp.get("filter").is_none());
    let e = c
        .query()
        .query_traces(TraceQuery::default().limit(20_000))
        .await
        .unwrap_err();
    assert_eq!(e.code(), ErrorCode::Validation);
}

#[tokio::test]
async fn get_trace_waterfall_and_not_found() {
    let server = MockServer::start().await;
    let id = "fe67ae4bfe67ae4bfe67ae4bfe67ae4b";
    Mock::given(method("POST"))
        .and(path(format!("/api/v4/traces/{id}/waterfall")))
        .respond_with(json_fixture(200, "getTrace.waterfall.ok.json"))
        .mount(&server)
        .await;
    Mock::given(method("POST"))
        .and(path(
            "/api/v4/traces/0af7651916cd43dd8448eb211c80319c/waterfall",
        ))
        .respond_with(json_fixture(404, "getTrace.notFound.json"))
        .mount(&server)
        .await;
    Mock::given(method("POST"))
        .and(path(
            "/api/v4/traces/000000000000000000000000000000aa/waterfall",
        ))
        .respond_with(json_body(
            200,
            json!({"status": "success", "data": {"spans": []}}),
        ))
        .mount(&server)
        .await;
    Mock::given(method("POST"))
        .and(path(
            "/api/v4/traces/000000000000000000000000000000bb/waterfall",
        ))
        .respond_with(ResponseTemplate::new(500))
        .mount(&server)
        .await;
    let c = client(&server.uri());
    let traces = c.traces();
    let t = traces.get_trace(&id.to_uppercase()).await.unwrap().unwrap();
    assert_eq!(t.trace_id, id);
    assert_eq!(t.spans.len(), 3);
    let root = &t.spans[0];
    assert_eq!(root.name, "GET /verify");
    assert_eq!(root.kind, SpanKind::Server);
    assert_eq!(root.status.code, StatusCode::Ok);
    assert!(root.parent_span_id.is_none());
    assert_eq!(root.start_micros, 1_795_033_260_000_000);
    assert_eq!(root.service_name, "sdk-fe67ae4b");
    let db = t.spans.iter().find(|s| s.name == "db.query").unwrap();
    assert_eq!(db.parent_span_id.as_deref(), Some("00f067aa0ba902b7"));
    assert_eq!(db.events[0].attributes["exception.message"], json!("boom"));
    assert_eq!(db.events[0].time_micros, 1_795_033_260_020_000);
    assert!(t
        .spans
        .iter()
        .all(|s| s.links.iter().all(|l| !l.span_id.is_empty())));
    assert!(traces
        .get_trace("0af7651916cd43dd8448eb211c80319c")
        .await
        .unwrap()
        .is_none());
    assert!(traces.get_trace("aa").await.unwrap().is_none());
    assert_eq!(
        traces.get_trace("bb").await.unwrap_err().code(),
        ErrorCode::Unavailable
    );
    assert_eq!(
        traces.get_trace("not-hex").await.unwrap_err().code(),
        ErrorCode::Validation
    );
    let waited = traces
        .wait_for_trace(
            id,
            sdk::tracing::WaitForTraceOptions::default().min_spans(3),
        )
        .await
        .unwrap();
    assert_eq!(waited.spans.len(), 3);
}

#[tokio::test]
async fn get_trace_completes_windowed_waterfall_with_raw_queries() {
    let server = MockServer::start().await;
    let id = "0af7651916cd43dd8448eb211c80319c";
    Mock::given(path(format!("/api/v4/traces/{id}/waterfall")))
        .respond_with(json_body(
            200,
            json!({"status": "success", "data": {"startTimestampMillis": 1000, "endTimestampMillis": 2000,
                   "totalSpansCount": 2, "hasMore": true,
                   "spans": [{"span_id": "1111111111111111", "trace_id": id, "name": "partial", "time_unix": 1000,
                              "references": [{"traceId": id, "spanId": "2222222222222222", "refType": "FOLLOWS_FROM"},
                                             {"traceId": id, "refType": "FOLLOWS_FROM"}]}]}}),
        ))
        .mount(&server)
        .await;
    mount_qr(
        &server,
        rows_page(vec![
            json!({"timestamp": "1970-01-01T00:00:01.5Z", "data": {"trace_id": id, "span_id": "2222222222222222", "name": "b",
                   "status_code_string": "Error", "status_message": "bad", "kind_string": "Internal"}}),
            json!({"data": {"timestamp": 1_000_000_000u64, "trace_id": id, "span_id": "1111111111111111", "name": "a",
                   "status_code_string": "Ok", "parent_span_id": ""}}),
        ]),
    )
    .await;
    let t = client(&server.uri())
        .query()
        .get_trace(id)
        .await
        .unwrap()
        .unwrap();
    assert_eq!(t.spans.len(), 2);
    assert_eq!(t.spans[0].name, "a");
    assert_eq!(t.spans[0].status.code, StatusCode::Ok);
    assert_eq!(t.spans[1].status.code, StatusCode::Error);
    assert_eq!(t.spans[1].status.message.as_deref(), Some("bad"));
    let b = last_body(&server.received_requests().await.unwrap());
    assert_eq!(b["start"], json!(1000));
    assert_eq!(b["end"], json!(2001));
    let s = &b["compositeQuery"]["queries"][0]["spec"];
    assert_eq!(
        s["filter"]["expression"],
        json!(format!("trace_id = '{id}'"))
    );
    assert_eq!(s["limit"], json!(10_000));
    assert_eq!(s["order"][0]["direction"], json!("asc"));
}

#[tokio::test]
async fn waterfall_links_are_follows_from_references() {
    let server = MockServer::start().await;
    let id = "0af7651916cd43dd8448eb211c80319c";
    Mock::given(path(format!("/api/v4/traces/{id}/waterfall")))
        .respond_with(json_body(
            200,
            json!({"status": "success", "data": {"totalSpansCount": 1, "spans": [
                {"span_id": "1111111111111111", "trace_id": id, "name": "x", "time_unix": 5, "kind_string": "Producer",
                 "status_code_string": "Error", "references": [
                    {"traceId": id, "spanId": "3333333333333333", "refType": "CHILD_OF"},
                    {"traceId": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "spanId": "4444444444444444", "refType": "follows_from"}]}]}}),
        ))
        .mount(&server)
        .await;
    let t = client(&server.uri())
        .query()
        .get_trace(id)
        .await
        .unwrap()
        .unwrap();
    let s = &t.spans[0];
    assert_eq!(s.kind, SpanKind::Producer);
    assert_eq!(s.status.code, StatusCode::Error);
    assert_eq!(s.start_micros, 5000);
    assert_eq!(s.links.len(), 1);
    assert_eq!(s.links[0].trace_id, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    assert!(s.links[0].attributes.is_empty());
}

#[tokio::test]
async fn search_traces_fills_from_waterfall() {
    let server = MockServer::start().await;
    let id = "fe67ae4bfe67ae4bfe67ae4bfe67ae4b";
    Mock::given(path(QR))
        .and(|r: &Request| body(r)["requestType"] == json!("trace"))
        .respond_with(json_body(
            200,
            json!({"status": "success", "data": {"data": {"results": [{"rows": [
                fixture_json("searchTraces.traceMode.json")["data"]["data"]["results"][0]["rows"][0].clone(),
                {"data": {"trace_id": "not-hex"}},
                {"data": {"trace_id": "0af7651916cd43dd8448eb211c80319c", "span_count": 1, "name": "", "duration_nano": 5000}}]}]}}}),
        ))
        .mount(&server)
        .await;
    Mock::given(path(format!("/api/v4/traces/{id}/waterfall")))
        .respond_with(json_fixture(200, "getTrace.waterfall.ok.json"))
        .mount(&server)
        .await;
    Mock::given(path(
        "/api/v4/traces/0af7651916cd43dd8448eb211c80319c/waterfall",
    ))
    .respond_with(json_fixture(404, "getTrace.notFound.json"))
    .mount(&server)
    .await;
    let c = client(&server.uri());
    let mut query = TraceSearchQuery::service("sdk-fe67ae4b")
        .operation("GET /verify")
        .tag("http.method", "GET")
        .limit(5);
    query.min_duration = Some(Duration::from_millis(1));
    query.max_duration = Some(Duration::from_secs(2));
    let res = c.traces().search_traces(&query).await.unwrap();
    assert_eq!(res.len(), 2);
    let s = &res[0];
    assert_eq!(s.trace_id, id);
    assert_eq!(s.span_count, 3);
    assert_eq!(s.start_micros, 1_795_033_260_000_000);
    assert_eq!(s.duration_micros, 150_000);
    assert_eq!(s.root_service_name.as_deref(), Some("sdk-fe67ae4b"));
    assert_eq!(s.root_name.as_deref(), Some("GET /verify"));
    assert_eq!(res[1].start_micros, 0);
    assert_eq!(res[1].span_count, 1);
    assert!(res[1].root_name.is_none());
    let b = body(&server.received_requests().await.unwrap()[0]);
    let sp = &b["compositeQuery"]["queries"][0]["spec"];
    assert_eq!(
        sp["filter"]["expression"],
        json!("service.name = 'sdk-fe67ae4b' AND name = 'GET /verify' AND http.method = 'GET' AND duration_nano >= 1000000 AND duration_nano <= 2000000000")
    );
    assert_eq!(sp["limit"], json!(5));
    let bad = TraceSearchQuery::default().tag("bad key", "x");
    assert_eq!(
        c.query().search_traces(&bad).await.unwrap_err().code(),
        ErrorCode::Validation
    );
    let e = c
        .query()
        .search_traces(&TraceSearchQuery::default().limit(10_001))
        .await
        .unwrap_err();
    assert_eq!(e.code(), ErrorCode::Validation);
}

#[tokio::test]
async fn search_traces_propagates_waterfall_errors() {
    let server = MockServer::start().await;
    mount_qr(&server, json_fixture(200, "searchTraces.traceMode.json")).await;
    Mock::given(path(
        "/api/v4/traces/fe67ae4bfe67ae4bfe67ae4bfe67ae4b/waterfall",
    ))
    .respond_with(json_fixture(401, "ping.unauthenticated.json"))
    .mount(&server)
    .await;
    let e = client(&server.uri())
        .query()
        .search_traces(&TraceSearchQuery::default())
        .await
        .unwrap_err();
    assert_eq!(e.code(), ErrorCode::Authentication);
}

#[tokio::test]
async fn services_operations_dependencies() {
    let server = MockServer::start().await;
    Mock::given(path(QR))
        .and(|r: &Request| spec(r)["groupBy"][0]["name"] == json!("service.name"))
        .respond_with(json_fixture(200, "listServices.scalar.json"))
        .mount(&server)
        .await;
    Mock::given(path(QR))
        .and(|r: &Request| spec(r)["groupBy"][0]["name"] == json!("name"))
        .respond_with(json_fixture(200, "listOperations.scalar.json"))
        .mount(&server)
        .await;
    Mock::given(method("POST"))
        .and(path("/api/v1/dependency_graph"))
        .respond_with(json_fixture(200, "getDependencies.ok.json"))
        .mount(&server)
        .await;
    let c = client(&server.uri());
    let traces = c.traces();
    let svcs = traces.list_services().await.unwrap();
    assert!(svcs.windows(2).all(|w| w[0] <= w[1]));
    assert!(svcs.contains(&"sdk-fe67ae4b".to_owned()));
    let svcs2 = c
        .query()
        .list_services_in_range(TimeRange::since(at(1_000)))
        .await
        .unwrap();
    assert_eq!(svcs, svcs2);
    let b = last_body(&server.received_requests().await.unwrap());
    assert_eq!(b["start"], json!(1000));
    let s = &b["compositeQuery"]["queries"][0]["spec"];
    assert_eq!(s["limit"], json!(10_000));
    assert_eq!(s["signal"], json!("traces"));
    let ops = traces.list_operations("sdk-fe67ae4b").await.unwrap();
    assert_eq!(ops, vec!["GET /verify", "db.query"]);
    let ops2 = c
        .query()
        .list_operations_in_range("sdk-fe67ae4b", TimeRange::between(at(1), at(2)))
        .await
        .unwrap();
    assert_eq!(ops, ops2);
    let s = spec(server.received_requests().await.unwrap().last().unwrap());
    assert_eq!(
        s["filter"]["expression"],
        json!("service.name = 'sdk-fe67ae4b'")
    );
    assert_eq!(
        traces.list_operations(" ").await.unwrap_err().code(),
        ErrorCode::Validation
    );
    let deps = traces
        .get_dependencies(DependencyOptions {
            start: Some(at(1_000)),
            end: Some(at(2_000)),
        })
        .await
        .unwrap();
    assert_eq!(deps.len(), 2);
    assert_eq!(deps[0].parent, "sdk-fe67ae4b");
    assert_eq!(deps[0].child, "postgresql");
    assert_eq!(deps[0].call_count, 1);
    let b = last_body(&server.received_requests().await.unwrap());
    assert_eq!(
        b,
        json!({"start": "1000000000", "end": "2000000000", "tags": []})
    );
}

#[tokio::test]
async fn query_is_retried_as_idempotent() {
    let server = MockServer::start().await;
    Mock::given(path(QR))
        .respond_with(ResponseTemplate::new(503))
        .up_to_n_times(1)
        .mount(&server)
        .await;
    mount_qr(&server, json_fixture(200, "queryLogs.empty.json")).await;
    Mock::given(path("/api/v1/dependency_graph"))
        .respond_with(ResponseTemplate::new(500))
        .up_to_n_times(1)
        .mount(&server)
        .await;
    Mock::given(path("/api/v1/dependency_graph"))
        .respond_with(json_body(200, json!([])))
        .mount(&server)
        .await;
    let c = client(&server.uri());
    c.query().query_logs(LogQuery::default()).await.unwrap();
    assert!(c
        .query()
        .get_dependencies(DependencyOptions::default())
        .await
        .unwrap()
        .is_empty());
    assert_eq!(server.received_requests().await.unwrap().len(), 4);
}

#[tokio::test]
async fn query_without_api_key_sends_no_header() {
    let server = MockServer::start().await;
    mount_qr(&server, json_fixture(401, "queryLogs.unauthenticated.json")).await;
    let c = sdk::SignozClient::new(
        sdk::SignozConfig::builder()
            .url(server.uri())
            .build()
            .unwrap(),
    )
    .unwrap();
    let e = c.query().query_logs(LogQuery::default()).await.unwrap_err();
    assert_eq!(e.code(), ErrorCode::Authentication);
    let reqs = server.received_requests().await.unwrap();
    assert!(reqs[0].headers.get("SIGNOZ-API-KEY").is_none());
    assert!(format!("{:?}", c.query()).contains("SignozQueryClient"));
}
