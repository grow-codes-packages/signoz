"""SignozQueryClient: logs (own cursor), spans, traces (waterfall), services, operations,
dependencies, log fields and error mapping — sync and async."""

from __future__ import annotations

import base64
import json
from datetime import datetime, timedelta, timezone
from typing import Any

import httpx
import pytest

import grow_signoz as sdk
from _signoz import (
    API_KEY,
    NOW,
    Router,
    aclient,
    client,
    config,
    fixture,
    json_response,
    success,
)

QR = "/api/v5/query_range"
TRACE_ID = "fe67ae4bfe67ae4bfe67ae4bfe67ae4b"
WATERFALL = f"/api/v4/traces/{TRACE_ID}/waterfall"
BASE_NS = 1_795_033_260_005_000_000


def log_row(row_id: str, nanos: int, body: str = "x", envelope: bool = True) -> dict[str, Any]:
    data: dict[str, Any] = {"id": row_id, "timestamp": nanos, "body": body, "severity_number": 9}
    row: dict[str, Any] = {"data": data}
    if envelope:
        seconds, frac = divmod(nanos, 1_000_000_000)
        stamp = datetime.fromtimestamp(seconds, timezone.utc).strftime("%Y-%m-%dT%H:%M:%S")
        row["timestamp"] = f"{stamp}.{frac:09d}Z"
    return row


def raw(rows: list[dict[str, Any]] | None, warning: dict[str, Any] | None = None) -> httpx.Response:
    data: dict[str, Any] = {"type": "raw", "data": {"results": [{"queryName": "A", "rows": rows}]}}
    if warning is not None:
        data["warning"] = warning
    return success(data)


def spec_of(body: dict[str, Any]) -> dict[str, Any]:
    return body["compositeQuery"]["queries"][0]["spec"]  # type: ignore[no-any-return]


def decode_cursor(cursor: str) -> dict[str, Any]:
    return json.loads(base64.urlsafe_b64decode(cursor + "=" * (-len(cursor) % 4)))  # type: ignore[no-any-return]


# --------------------------------------------------------------------------- logs


def test_query_logs_fixture_mapping() -> None:
    router = Router().add("POST", QR, json_response(fixture("queryLogs.raw.ok")))
    page = client(router).query.query_logs(
        sdk.FilterBuilder().eq("service.name", "sdk-fe67ae4b"), select_fields=["body"]
    )
    assert page.next_cursor is None and page.warnings == []
    rec = page.records[0]
    assert isinstance(rec, sdk.SignozLogRecord)
    assert rec.body == "marker-fe67ae4b seq 5 ünïcode it's"
    assert rec.severity == sdk.Severity.INFO and rec.severity_text == "INFO"
    assert rec.attributes == {"ok": True, "seq": 5, "run.id": "fe67ae4b"}
    assert rec.resource["service.name"] == "sdk-fe67ae4b"
    assert rec.trace_id == TRACE_ID and rec.span_id == "00f067aa0ba902b7"
    assert rec.trace_flags == 1
    assert rec.id == "0mUr6HvHZ86gIh7CSEuDpOA7FHO"
    assert rec.time_unix_nano == 1795033260005000000
    assert rec.scope_name == "grow-sdk-verify"
    body = router.bodies(QR)[0]
    req = router.requests[0]
    assert req.headers["SIGNOZ-API-KEY"] == API_KEY
    assert body["requestType"] == "raw"
    assert body["end"] == sdk.to_epoch_ms(NOW)
    assert body["end"] - body["start"] == 3_600_000
    spec = spec_of(body)
    assert spec["signal"] == "logs" and spec["name"] == "A"
    assert spec["filter"] == {"expression": "service.name = 'sdk-fe67ae4b'"}
    assert spec["selectFields"] == [{"name": "body"}]
    assert spec["limit"] == 100
    assert spec["order"] == [
        {"key": {"name": "timestamp"}, "direction": "desc"},
        {"key": {"name": "id"}, "direction": "desc"},
    ]


def test_query_logs_own_cursor_repeats_boundary_millisecond() -> None:
    ms = 1_000_000
    router = Router()
    # desc page 1: rows 5, 4a, 4b where 4a/4b share a millisecond → boundary ms 4
    router.add(
        "POST",
        QR,
        raw(
            [
                log_row("e", BASE_NS + 5 * ms),
                log_row("d2", BASE_NS + 4 * ms + 7),
                log_row("d1", BASE_NS + 4 * ms + 3),
            ]
        ),
    )
    router.add(
        "POST",
        QR,
        raw(
            [
                log_row("d0", BASE_NS + 4 * ms),
                log_row("c", BASE_NS + 3 * ms),
                log_row("b", BASE_NS + 3 * ms),
            ]
        ),
    )
    router.add("POST", QR, raw([log_row("a", BASE_NS + 1 * ms)]))
    q = client(router).query
    start = NOW - timedelta(minutes=10)
    p1 = q.query_logs("body CONTAINS 'x'", start=start, end=NOW, limit=3)
    assert p1.next_cursor is not None
    c1 = decode_cursor(p1.next_cursor)
    assert c1["m"] == (BASE_NS + 4 * ms) // ms
    assert c1["x"] == ["d2", "d1"]
    assert c1["o"] == "desc"
    p2 = q.query_logs("body CONTAINS 'x'", limit=3, cursor=p1.next_cursor)
    assert p2.next_cursor is not None
    c2 = decode_cursor(p2.next_cursor)
    assert c2["m"] == (BASE_NS + 3 * ms) // ms and c2["x"] == ["c", "b"]
    assert (
        (c2["s"], c2["e"]) == (c1["s"], c1["e"]) == (sdk.to_epoch_ms(start), sdk.to_epoch_ms(NOW))
    )
    p3 = q.query_logs("body CONTAINS 'x'", limit=3, cursor=p2.next_cursor)
    assert p3.next_cursor is None
    bodies = router.bodies(QR)
    assert bodies[1]["end"] == c1["m"] + 1 and bodies[1]["start"] == c1["s"]
    assert spec_of(bodies[1])["filter"]["expression"] == (
        "(body CONTAINS 'x') AND (id NOT IN ('d2', 'd1'))"
    )
    assert spec_of(bodies[2])["filter"]["expression"].endswith("(id NOT IN ('c', 'b'))")


def test_query_logs_cursor_asc_accumulates_ids_in_same_millisecond() -> None:
    router = Router()
    router.add("POST", QR, raw([log_row("a", BASE_NS), log_row("b", BASE_NS + 1)]))
    router.add("POST", QR, raw([log_row("c", BASE_NS + 2), log_row("d", BASE_NS + 3)]))
    q = client(router).query
    p1 = q.query_logs(limit=2, order_by="asc")
    assert p1.next_cursor is not None
    p2 = q.query_logs(limit=2, cursor=p1.next_cursor, order_by="desc")  # cursor order wins
    assert p2.next_cursor is not None
    assert decode_cursor(p2.next_cursor)["x"] == ["a", "b", "c", "d"]
    second = router.bodies(QR)[1]
    assert second["start"] == BASE_NS // 1_000_000
    assert spec_of(second)["order"][0]["direction"] == "asc"
    assert spec_of(second)["filter"]["expression"] == "id NOT IN ('a', 'b')"


def test_query_logs_warnings_empty_rows_and_validation() -> None:
    router = Router()
    router.add("POST", QR, json_response(fixture("queryLogs.unknownFieldWarning")))
    router.add("POST", QR, json_response(fixture("queryLogs.empty")))
    router.add("POST", QR, raw([], {"message": "only top-level"}))
    lines: list[str] = []

    class Log:
        def debug(self, msg: str, *a: Any, **k: Any) -> None: ...
        def info(self, msg: str, *a: Any, **k: Any) -> None: ...
        def warn(self, msg: str, *a: Any, **k: Any) -> None:
            lines.append(msg)

        def error(self, msg: str, *a: Any, **k: Any) -> None: ...

    q = sdk.SignozClient(config(logger=Log()), http_transport=router.transport()).query
    warned = q.query_logs("nosuchfield_fe67ae4b = 'x'")
    assert warned.records == []
    assert any("nosuchfield_fe67ae4b" in w for w in warned.warnings)
    assert q.query_logs().records == []
    assert q.query_logs().warnings == ["only top-level"]
    assert lines and "query_logs" in lines[0]
    for kwargs in (
        {"limit": 10_001},
        {"limit": -1},
        {"order_by": "sideways"},
        {"cursor": "not-a-cursor"},
        {"cursor": base64.urlsafe_b64encode(b'{"v":2}').decode()},
        {"start": NOW, "end": NOW},
    ):
        with pytest.raises(sdk.ValidationError):
            q.query_logs(**kwargs)
    assert spec_of(router.bodies(QR)[1])["limit"] == 100


def test_query_logs_limit_zero_is_default() -> None:
    router = Router().add("POST", QR, raw(None))
    client(router).query.query_logs(limit=0)
    assert spec_of(router.bodies(QR)[0])["limit"] == 100
    assert "filter" not in spec_of(router.bodies(QR)[0])


def test_error_mapping() -> None:
    router = Router()
    router.add("POST", QR, json_response(fixture("queryLogs.invalidFilter"), 400))
    router.add("POST", QR, json_response(fixture("queryLogs.unauthenticated"), 401))
    router.add("POST", QR, json_response(fixture("error.forbidden"), 403))
    router.add(
        "POST", QR, httpx.Response(200, text="<html>", headers={"content-type": "text/html"})
    )
    router.add(
        "POST", QR, httpx.Response(200, text="{nope", headers={"content-type": "application/json"})
    )
    router.add("POST", QR, json_response({"status": "error"}))
    router.add("POST", QR, *[httpx.Response(503)] * 4)
    q = client(router).query
    with pytest.raises(sdk.ValidationError) as bad:
        q.query_logs("body = (")
    assert bad.value.http_status == 400 and "syntax" in str(bad.value).lower()
    with pytest.raises(sdk.AuthenticationError):
        q.query_logs()
    with pytest.raises(sdk.PermissionDeniedError):
        q.query_logs()
    with pytest.raises(sdk.UnsupportedError, match="not available"):
        q.query_logs()
    with pytest.raises(sdk.UnavailableError, match="invalid JSON"):
        q.query_logs()
    with pytest.raises(sdk.UnavailableError, match="unexpected response"):
        q.query_logs()
    with pytest.raises(sdk.UnavailableError):
        q.query_logs()
    assert len([r for r in router.requests if r.url.path == QR]) == 10  # 503 retried 3 times
    with pytest.raises(sdk.ConfigError):
        sdk.SignozClient(config(api_key=None), http_transport=router.transport()).query.query_logs()


def test_query_range_raw() -> None:
    router = Router().add("POST", QR, json_response(fixture("aggregate.count")))
    res = client(router).query.query_range({"start": 1, "end": 2}, operation="custom")
    assert "__result_0" in json.dumps(res.data)
    assert isinstance(res, sdk.ApiResult)


# --------------------------------------------------------------------------- spans + traces


def test_query_traces_fixture() -> None:
    router = Router().add("POST", QR, json_response(fixture("queryTraces.raw.ok")))
    result = client(router).query.query_traces(
        service="sdk-fe67ae4b",
        operation="db.query",
        filter="duration_nano > 1",
        offset=5,
        order_by="asc",
        limit=50,
    )
    assert result.spans
    db = next(s for s in result.spans if s.name == "db.query")
    assert db.kind == "client"
    assert db.status.code == "error"
    assert db.events[0].name == "exception"
    assert db.events[0].attributes == {"exception.message": "boom"}
    assert db.parent_span_id is not None
    assert all(link.span_id != db.parent_span_id for link in db.links)
    spec = spec_of(router.bodies(QR)[0])
    assert spec["signal"] == "traces"
    assert spec["filter"]["expression"] == (
        "(service.name = 'sdk-fe67ae4b' AND name = 'db.query') AND (duration_nano > 1)"
    )
    assert (spec["offset"], spec["limit"]) == (5, 50)
    assert spec["order"] == [{"key": {"name": "timestamp"}, "direction": "asc"}]
    with pytest.raises(sdk.ValidationError):
        client(router).query.query_traces(offset=-1)


def test_get_trace_waterfall_and_missing() -> None:
    router = Router()
    router.add("POST", WATERFALL, json_response(fixture("getTrace.waterfall.ok")))
    router.add("POST", WATERFALL, json_response(fixture("getTrace.notFound"), 404))
    router.add("POST", WATERFALL, success({"spans": []}))
    q = client(router).query
    trace = q.get_trace(TRACE_ID.upper())
    assert isinstance(trace, sdk.SignozTrace)
    assert trace.complete and trace.total_spans == 3 and len(trace.spans) == 3
    assert trace.root_service_name == "sdk-fe67ae4b" and trace.root_name == "GET /verify"
    root = trace.roots()[0]
    assert root.name == "GET /verify" and root.kind == "server"
    assert root.start_micros == 1795033260000 * 1000
    ids = {s.span_id for s in trace.spans}
    assert all(s.parent_span_id in ids for s in trace.spans if s is not root)
    assert q.get_trace(TRACE_ID) is None
    assert q.get_trace(TRACE_ID) is None
    assert router.bodies(WATERFALL)[0] == {}
    with pytest.raises(sdk.ValidationError):
        q.get_trace("not-hex")


def test_get_trace_incomplete_is_completed_with_raw_spans() -> None:
    wf = fixture("getTrace.waterfall.ok")
    wf["data"]["hasMore"] = True
    wf["data"]["totalSpansCount"] = 5
    spans = fixture("queryTraces.raw.ok")
    router = Router()
    router.add("POST", WATERFALL, json_response(wf))
    router.add("POST", QR, json_response(spans))
    trace = client(router).query.get_trace(TRACE_ID)
    assert trace is not None
    n = len(spans["data"]["data"]["results"][0]["rows"])
    assert len(trace.spans) == n
    assert trace.complete is (n >= 5)
    body = router.bodies(QR)[0]
    assert body["start"] == wf["data"]["startTimestampMillis"]
    assert body["end"] == wf["data"]["endTimestampMillis"] + 1
    assert spec_of(body)["filter"]["expression"] == f"trace_id = '{TRACE_ID}'"
    assert spec_of(body)["limit"] == sdk.MAX_QUERY_LIMIT


def test_search_traces_fills_from_waterfall() -> None:
    other = "0123456789abcdef0123456789abcdef"
    mode = fixture("searchTraces.traceMode")
    rows = mode["data"]["data"]["results"][0]["rows"]
    rows.append({"data": {"trace_id": other, "duration_nano": 5000, "span_count": 1}})
    rows.append({"data": {"trace_id": "", "span_count": 1}})
    router = Router()
    router.add("POST", QR, json_response(mode))
    router.add("POST", WATERFALL, json_response(fixture("getTrace.waterfall.ok")))
    router.add("POST", f"/api/v4/traces/{other}/waterfall", httpx.Response(404))
    q = client(router).query
    hits = q.search_traces(
        sdk.TraceSearchQuery(
            service="sdk-fe67ae4b",
            operation="GET /verify",
            tags={"z": "1", "a": "2"},
            min_duration_micros=100,
            max_duration_micros=900_000,
            limit=5,
        ),
        filter="has_error = true",
    )
    assert [h.trace_id for h in hits] == [TRACE_ID, other]
    first = hits[0]
    assert first.start_micros == 1795033260000 * 1000
    assert first.span_count == 3
    assert first.duration_micros == 150000
    assert (first.root_service_name, first.root_name) == ("sdk-fe67ae4b", "GET /verify")
    assert hits[1] == sdk.TraceSummary(other, 0, 5, 1)
    spec = spec_of(router.bodies(QR)[0])
    assert router.bodies(QR)[0]["requestType"] == "trace"
    assert spec["limit"] == 5
    assert spec["filter"]["expression"] == (
        "(service.name = 'sdk-fe67ae4b' AND name = 'GET /verify' AND a = '2' AND z = '1'"
        " AND duration_nano >= 100000 AND duration_nano <= 900000000) AND (has_error = true)"
    )


def test_search_traces_empty_and_default_limit() -> None:
    router = Router().add("POST", QR, raw(None))
    assert client(router).query.search_traces() == []
    assert spec_of(router.bodies(QR)[0])["limit"] == 20


def test_services_operations_dependencies() -> None:
    router = Router()
    router.add("POST", QR, json_response(fixture("listServices.scalar")))
    router.add("POST", QR, json_response(fixture("listOperations.scalar")))
    router.add("POST", "/api/v1/dependency_graph", json_response(fixture("getDependencies.ok")))
    q = client(router).query
    services = q.list_services()
    assert services == sorted(set(services)) and "sdk-fe67ae4b" in services
    ops = q.list_operations("sdk-fe67ae4b", start=NOW - timedelta(hours=2), end=NOW)
    assert ops == sorted(ops) and ops
    deps = q.get_dependencies()
    assert deps[0] == sdk.DependencyLink("sdk-fe67ae4b", "postgresql", 1)
    s_body, o_body = router.bodies(QR)
    assert spec_of(s_body)["groupBy"] == [{"name": "service.name"}]
    assert spec_of(s_body)["limit"] == sdk.MAX_QUERY_LIMIT
    assert spec_of(o_body)["filter"] == {"expression": "service.name = 'sdk-fe67ae4b'"}
    assert o_body["end"] - o_body["start"] == 7_200_000
    dep = router.bodies("/api/v1/dependency_graph")[0]
    assert dep["end"] == str(sdk.to_epoch_ms(NOW) * 1_000_000) and dep["tags"] == []
    with pytest.raises(sdk.ValidationError):
        q.list_operations(" ")


def test_log_fields_and_values() -> None:
    router = Router()
    router.add("GET", "/api/v1/fields/keys", json_response(fixture("listLogFields.ok")))
    router.add("GET", "/api/v1/fields/values", json_response(fixture("listStreams.fieldValues")))
    router.add(
        "GET",
        "/api/v1/fields/values",
        success({"values": {"stringValues": ["b"], "numberValues": [2, 1], "boolValues": [True]}}),
    )
    q = client(router).query
    fields = q.list_log_fields(search="a", limit=5)
    assert fields == sorted(fields, key=lambda f: (f.name, f.context))
    assert sdk.LogField("app", "attribute", "string") in fields
    keys_req = router.requests[0]
    assert keys_req.url.params["searchText"] == "a" and keys_req.url.params["limit"] == "5"
    values = q.list_field_values("service.name", source="traces", search="sdk")
    assert "sdk-fe67ae4b" in values and values == sorted(values)
    assert router.requests[1].url.params["signal"] == "traces"
    assert q.list_field_values("x") == [1, 2, True, "b"]
    with pytest.raises(sdk.ValidationError):
        q.list_field_values("x", source="metrics")  # type: ignore[arg-type]


def test_resolve_window() -> None:
    assert sdk.resolve_window(None, NOW) == (
        sdk.to_epoch_ms(NOW) - 3_600_000,
        sdk.to_epoch_ms(NOW),
    )
    naive = datetime(2026, 1, 1)
    assert sdk.resolve_window(naive, naive + timedelta(seconds=1))[1] - 1000 == sdk.to_epoch_ms(
        naive
    )
    with pytest.raises(sdk.ValidationError):
        sdk.resolve_window(NOW, NOW - timedelta(seconds=1))


def test_wait_for_trace_polls_get_trace() -> None:
    router = Router()
    router.add("POST", WATERFALL, httpx.Response(404))
    router.add("POST", WATERFALL, json_response(fixture("getTrace.waterfall.ok")))
    trace = client(router).traces.wait_for_trace(
        TRACE_ID, timeout_s=5, interval_s=0.01, min_spans=3
    )
    assert len(trace.spans) == 3


# --------------------------------------------------------------------------- async


async def test_async_query_client() -> None:
    router = Router()
    router.add("POST", QR, json_response(fixture("queryLogs.raw.ok")))
    router.add("POST", QR, json_response(fixture("queryTraces.raw.ok")))
    router.add("POST", WATERFALL, json_response(fixture("getTrace.waterfall.ok")))
    router.add("POST", QR, json_response(fixture("searchTraces.traceMode")))
    router.add("POST", WATERFALL, json_response(fixture("getTrace.waterfall.ok")))
    router.add("POST", QR, json_response(fixture("listServices.scalar")))
    router.add("POST", QR, json_response(fixture("listOperations.scalar")))
    router.add("POST", "/api/v1/dependency_graph", json_response(fixture("getDependencies.ok")))
    router.add("GET", "/api/v1/fields/keys", json_response(fixture("listLogFields.ok")))
    router.add("GET", "/api/v1/fields/values", json_response(fixture("listStreams.fieldValues")))
    router.add("POST", QR, json_response(fixture("aggregate.count")))
    router.add("POST", WATERFALL, json_response(fixture("getTrace.waterfall.ok")))
    async with aclient(router) as c:
        q = c.query
        assert (await q.query_logs("x", limit=5)).records
        assert (await q.query_traces(service="s")).spans
        trace = await q.get_trace(TRACE_ID)
        assert trace is not None and len(trace.spans) == 3
        hits = await q.search_traces(sdk.TraceSearchQuery(service="s"), filter="a = 1")
        assert hits[0].span_count == 3
        assert "sdk-fe67ae4b" in await q.list_services(start=NOW - timedelta(hours=1))
        assert await q.list_operations("sdk-fe67ae4b", end=NOW)
        assert (await q.get_dependencies())[0].child == "postgresql"
        assert await q.list_log_fields()
        assert await q.list_field_values("service.name")
        assert (await q.query_range({"a": 1})).data is not None
        waited = await c.traces.wait_for_trace(TRACE_ID, timeout_s=5, interval_s=0.01)
        assert waited.trace_id == TRACE_ID


def test_query_logs_accepts_go_cursor_with_null_ids() -> None:
    # Go encodes an empty id list as "x": null; a missing "x" is accepted too.
    router = Router().add("POST", QR, raw(None)).add("POST", QR, raw(None))
    q = client(router).query
    for body in (
        b'{"v":1,"s":1000,"e":5000,"o":"asc","m":2000,"x":null}',
        b'{"v":1,"s":1000,"e":5000,"o":"desc","m":2000}',
    ):
        cursor = base64.urlsafe_b64encode(body).decode().rstrip("=")
        assert q.query_logs(cursor=cursor).next_cursor is None
    first, second = router.bodies(QR)
    assert (first["start"], first["end"]) == (2000, 5000)
    assert (second["start"], second["end"]) == (1000, 2001)
    assert "filter" not in spec_of(first)
    bad = base64.urlsafe_b64encode(b'{"v":1,"s":true,"e":5,"o":"asc","m":2,"x":null}').decode()
    with pytest.raises(sdk.ValidationError):
        q.query_logs(cursor=bad)
