"""Integration tests against the real SigNoz stack (docker/compose.yml): every public function of
grow_signoz is called at least once with a round-trip assertion (send → query back)."""

from __future__ import annotations

import asyncio
import time
import uuid
from collections.abc import Callable
from datetime import datetime, timedelta, timezone
from typing import Any, TypeVar

import pytest
from opentelemetry.trace import SpanKind, Status, StatusCode, set_span_in_context

import grow_signoz as sdk

T = TypeVar("T")
RUN = uuid.uuid4().hex[:10]


def poll(fn: Callable[[], T], ok: Callable[[T], bool], timeout: float = 60.0) -> T:
    deadline = time.monotonic() + timeout
    while True:
        value = fn()
        if ok(value) or time.monotonic() > deadline:
            return value
        time.sleep(1.0)


def cfg(env: dict[str, str], **kw: Any) -> sdk.SignozConfig:
    return sdk.SignozConfig.from_env(env, **kw)


def test_config_reads_bootstrap_env_and_redacts(env: dict[str, str]) -> None:
    c = cfg(env)
    assert c.otlp_endpoint == env["SIGNOZ_OTLP_ENDPOINT"]
    assert c.api_key is not None
    assert c.api_key.reveal() not in repr(c)


def test_raw_request(client: sdk.SignozClient) -> None:
    resp = client.request(sdk.Request("GET", "/api/v1/health"))
    assert resp.status == 200 and resp.json()["status"] == "ok"


def test_logs_http_and_grpc_round_trip(client: sdk.SignozClient) -> None:
    for protocol in ("http/protobuf", "grpc"):
        tag = "grpc" if protocol == "grpc" else "http"
        service = f"py-logs-{tag}-{RUN}"
        logs = client.log_client(
            service_name=service,
            service_version="1.0.0",
            environment="test",
            protocol=protocol,  # type: ignore[arg-type]
            options=sdk.BatchOptions(flush_interval_s=0.2),
        )
        logs.send(
            sdk.LogInput(
                f"roundtrip {RUN} {protocol} it's ünïcode",
                severity=sdk.Severity.ERROR,
                attributes={
                    "run": RUN,
                    "protocol": protocol,
                    "n": 7,
                    "ok": True,
                    "password": "hunter2-secret",
                },
                trace_id="4bf92f3577b34da6a3ce929d0e0e4736",
                span_id="00f067aa0ba902b7",
            )
        )
        logs.logger("it").warn(f"facade {RUN} {protocol}", {"run": RUN, "protocol": protocol})
        logs.flush(30)
        assert logs.stats().failed == 0
        flt = sdk.FilterBuilder().eq("service.name", service).eq("run", RUN)
        page = poll(
            lambda flt=flt: client.query.query_logs(flt, order_by="asc"),
            lambda p: len(p.records) >= 2,
        )
        assert len(page.records) == 2, page
        rec = next(r for r in page.records if r.severity == sdk.Severity.ERROR)
        assert rec.body == f"roundtrip {RUN} {protocol} it's ünïcode"
        assert rec.attributes["n"] == 7 and rec.attributes["ok"] is True
        assert rec.attributes["password"] == "[REDACTED]"
        assert rec.resource["service.name"] == service
        assert rec.resource["deployment.environment"] == "test"
        assert rec.resource["service.version"] == "1.0.0"
        assert rec.trace_id == "4bf92f3577b34da6a3ce929d0e0e4736"
        assert rec.span_id == "00f067aa0ba902b7"
        assert rec.id and len(str(rec.time_unix_nano)) == 19
        warn = next(r for r in page.records if r.severity == sdk.Severity.WARN)
        assert warn.body == f"facade {RUN} {protocol}"


def test_query_logs_cursor_paging_and_select_fields(
    env: dict[str, str], client: sdk.SignozClient
) -> None:
    base = datetime.now(timezone.utc)
    with sdk.create_log_client(cfg(env), service_name=f"py-paging-{RUN}") as logs:
        # pairs share a millisecond, so page boundaries fall inside one millisecond
        logs.send_batch(
            sdk.LogInput(
                f"page {RUN} #{i}",
                attributes={"run": RUN, "i": i},
                timestamp=base + timedelta(milliseconds=i // 2),
            )
            for i in range(11)
        )
    flt = f"run = '{RUN}' AND body CONTAINS 'page'"
    poll(lambda: client.query.query_logs(flt), lambda p: len(p.records) >= 11)
    for order in ("desc", "asc"):
        seen: list[str] = []
        cursor: str | None = None
        for _ in range(20):
            page = client.query.query_logs(flt, limit=3, order_by=order, cursor=cursor)  # type: ignore[arg-type]
            seen.extend(r.body for r in page.records)
            cursor = page.next_cursor
            if cursor is None:
                break
        assert len(seen) == 11 and len(set(seen)) == 11, (order, seen)
    slim = client.query.query_logs(flt, limit=1, select_fields=["body"])
    assert len(slim.records) == 1 and slim.records[0].body.startswith("page ")


def test_filter_escaping_round_trip(env: dict[str, str], client: sdk.SignozClient) -> None:
    body = f"esc {RUN} it's a back\\slash 50%_off"
    with sdk.create_log_client(cfg(env), service_name=f"py-esc-{RUN}") as logs:
        logs.send(sdk.LogInput(body, attributes={"run": RUN}))
    exact = sdk.FilterBuilder().eq("run", RUN).eq("body", body)
    page = poll(lambda: client.query.query_logs(exact), lambda p: len(p.records) == 1)
    assert [r.body for r in page.records] == [body]
    contains = sdk.FilterBuilder().eq("run", RUN).contains("body", "back\\slash 50%_off")
    assert [r.body for r in client.query.query_logs(contains).records] == [body]
    wildcard = sdk.FilterBuilder().eq("run", RUN).contains("body", "back_slash")
    assert client.query.query_logs(wildcard).records == []


def test_query_logs_warnings_and_validation(client: sdk.SignozClient) -> None:
    warned = client.query.query_logs(f"nosuchfield_{RUN} = 'x'")
    assert warned.records == []
    assert any(f"nosuchfield_{RUN}" in w for w in warned.warnings), warned.warnings
    with pytest.raises(sdk.ValidationError) as info:
        client.query.query_logs("body = (")
    assert info.value.http_status == 400


def test_log_query_client_functions(env: dict[str, str], client: sdk.SignozClient) -> None:
    service = f"py-search-{RUN}"
    with sdk.create_log_client(cfg(env), service_name=service) as logs:
        logs.send(sdk.LogInput(f"search {RUN} needle_{RUN}", attributes={f"label_{RUN}": "v"}))
    found = client.logs.wait_for_log(f"needle_{RUN}", timeout_s=60)
    assert found.body == f"search {RUN} needle_{RUN}"
    again = sdk.wait_for_log(
        client.logs,
        f"service.name = '{service}'",
        predicate=lambda r: r.attributes.get(f"label_{RUN}") == "v",
        timeout_s=30,
    )
    assert again.resource["service.name"] == service
    page = client.logs.search(f"needle_{RUN}", limit=10, order="asc")
    assert len(page.records) == 1 and page.next_cursor is None
    streams = poll(client.logs.list_streams, lambda s: service in s, 30)
    assert service in streams
    labels = poll(client.logs.list_labels, lambda ls: f"label_{RUN}" in ls, 30)
    assert f"label_{RUN}" in labels
    fields = client.query.list_log_fields(search=f"label_{RUN}")
    assert [(f.name, f.context, f.data_type) for f in fields] == [
        (f"label_{RUN}", "attribute", "string")
    ]
    values = client.query.list_field_values("service.name", source="logs", search=service)
    assert service in values


def test_tail_delivers_matching_records(env: dict[str, str], client: sdk.SignozClient) -> None:
    got: list[sdk.LogRecord] = []
    errors: list[Exception] = []
    handle = client.logs.tail(f"body CONTAINS 'tail-{RUN}'", got.append, on_error=errors.append)
    try:
        time.sleep(1.5)
        with sdk.create_log_client(cfg(env), service_name=f"py-tail-{RUN}") as logs:
            logs.send(sdk.LogInput(f"tail-{RUN} match"))
            logs.send(sdk.LogInput(f"other {RUN} nomatch"))
        poll(lambda: len(got), lambda n: n >= 1, 30)
    finally:
        handle.stop()
    assert [r.body for r in got] == [f"tail-{RUN} match"], errors


def test_log_exporter_direct(env: dict[str, str], client: sdk.SignozClient) -> None:
    for protocol in ("http/protobuf", "grpc"):
        exporter = sdk.SignozLogExporter(cfg(env), protocol=protocol)  # type: ignore[arg-type]
        body = f"exporter {RUN} {protocol}"
        result = exporter.export(
            [
                sdk.LogRecord(
                    body=body,
                    attributes={"run": RUN},
                    resource={"service.name": f"py-exporter-{RUN}"},
                )
            ],
            gzip=True,
            timeout_s=10,
        )
        exporter.shutdown()
        assert (result.accepted, result.rejected) == (1, 0)
        rec = client.logs.wait_for_log(f"body = '{body}'", timeout_s=60)
        assert rec.resource["service.name"] == f"py-exporter-{RUN}"


def test_aggregate_counts_logs(env: dict[str, str], client: sdk.SignozClient) -> None:
    with sdk.create_log_client(cfg(env), service_name=f"py-agg-{RUN}") as logs:
        for i in range(5):
            logs.send(sdk.LogInput(f"agg {RUN} {i}", attributes={"agg": RUN, "seq": i}))
    flt = sdk.FilterBuilder().eq("agg", RUN)
    scalar = poll(
        lambda: client.query.aggregate("logs").filter(flt).count("n").max("seq", "max_seq").run(),
        lambda r: bool(r.rows) and r.rows[0].values.get("n") == 5,
    )
    assert scalar.rows == [sdk.AggregateRow({}, {"n": 5.0, "max_seq": 4.0})]
    series = client.query.aggregate("logs").filter(flt).count("n").time_series(60)
    assert sum(p.value for s in series if s.alias == "n" for p in s.points) == 5
    now_ms = sdk.to_epoch_ms(datetime.now(timezone.utc))
    raw = client.query.query_range(
        {
            "start": now_ms - 3_600_000,
            "end": now_ms + 1000,
            "requestType": "scalar",
            "compositeQuery": {
                "queries": [
                    {
                        "type": "builder_query",
                        "spec": {
                            "name": "A",
                            "signal": "logs",
                            "filter": {"expression": flt.build()},
                            "aggregations": [{"expression": "count()"}],
                        },
                    }
                ]
            },
        }
    )
    assert "__result_0" in str(raw.data)


def _emit_trace(handle: sdk.TracingHandle) -> str:
    tracer = handle.tracer("integration")
    root = tracer.start_span(
        "GET /checkout", kind=SpanKind.SERVER, attributes={"run": RUN, "http.method": "GET"}
    )
    child = tracer.start_span(
        "db.query", kind=SpanKind.CLIENT, attributes={"n": 2}, context=set_span_in_context(root)
    )
    child.add_event("checkpoint", {"step": 1})
    child.set_status(Status(StatusCode.ERROR, "boom"))
    child.end()
    root.end()
    handle.force_flush(10)
    return format(root.get_span_context().trace_id, "032x")


def test_tracing_round_trip(client: sdk.SignozClient) -> None:
    for protocol in ("http/protobuf", "grpc"):
        tag = "grpc" if protocol == "grpc" else "http"
        service = f"py-trace-{tag}-{RUN}"
        handle = client.init_tracing(service, protocol=protocol, set_global=False)
        trace_id = _emit_trace(handle)
        trace = client.traces.wait_for_trace(trace_id, min_spans=2, timeout_s=60)
        by_name = {s.name: s for s in trace.spans}
        root, child = by_name["GET /checkout"], by_name["db.query"]
        assert child.parent_span_id == root.span_id
        assert (root.kind, child.kind) == ("server", "client")
        assert child.status.code == "error" and child.status.message == "boom"
        assert child.events[0].name == "checkpoint"
        assert root.service_name == service
        full = client.traces.get_trace(trace_id)
        assert full is not None and full.complete and full.total_spans == 2
        spans = client.query.query_traces(service=service, operation="db.query")
        assert [s.trace_id for s in spans.spans] == [trace_id]
        hits = client.traces.search_traces(sdk.TraceSearchQuery(service=service, tags={"run": RUN}))
        assert [h.trace_id for h in hits] == [trace_id]
        assert hits[0].root_name == "GET /checkout" and hits[0].span_count == 2
        assert hits[0].start_micros > 0
        assert service in client.traces.list_services()
        assert client.traces.list_operations(service) == ["GET /checkout", "db.query"]
        agg = (
            client.query.aggregate("traces")
            .filter(f"service.name = '{service}'")
            .count("calls")
            .p99("duration_nano", "p99")
            .group_by("name")
            .run()
        )
        assert len(agg.rows) == 2
        assert all(r.values["calls"] == 1 and (r.values["p99"] or 0) > 0 for r in agg.rows)
    assert client.traces.get_trace("0123456789abcdef0123456789abcdef") is None


def test_get_dependencies_reports_db_edge(env: dict[str, str], client: sdk.SignozClient) -> None:
    service = f"py-deps-{RUN}"
    handle = sdk.init_tracing(service, signoz=cfg(env), set_global=False)
    tracer = handle.tracer("integration")
    root = tracer.start_span("GET /deps", kind=SpanKind.SERVER)
    db = tracer.start_span(
        "SELECT",
        kind=SpanKind.CLIENT,
        attributes={"db.system": "postgresql"},
        context=set_span_in_context(root),
    )
    db.end()
    root.end()
    handle.shutdown()
    deps = poll(
        client.traces.get_dependencies,
        lambda d: any(e.parent == service and e.child == "postgresql" for e in d),
        150,
    )
    edge = next(e for e in deps if e.parent == service and e.child == "postgresql")
    assert edge.call_count >= 1


def test_exporter_helpers(env: dict[str, str]) -> None:
    resolved = sdk.signoz_tracing_config("x", cfg(env), protocol="grpc")
    assert resolved.protocol == "grpc" and resolved.endpoint is not None
    assert resolved.endpoint.endswith(":4317")
    sdk.create_signoz_span_exporter(resolved).shutdown()


def test_async_round_trip(env: dict[str, str]) -> None:
    async def run() -> None:
        async with sdk.AsyncSignozClient.from_env(env) as c:
            service = f"py-async-{RUN}"
            logs = c.log_client(
                service_name=service, options=sdk.BatchOptions(flush_interval_s=0.2)
            )
            got: list[str] = []
            handle = await c.logs.tail(f"body CONTAINS 'atail-{RUN}'", lambda r: got.append(r.body))
            await asyncio.sleep(1.5)
            logs.send(sdk.LogInput(f"async {RUN} needle"))
            logs.send(sdk.LogInput(f"atail-{RUN} match"))
            await logs.flush(30)
            rec = await c.logs.wait_for_log(f"body = 'async {RUN} needle'", timeout_s=60)
            assert rec.resource["service.name"] == service
            page = await c.query.query_logs(sdk.FilterBuilder().eq("service.name", service))
            assert len(page.records) == 2
            assert (await c.logs.search(f"service.name = '{service}'")).records
            assert service in await c.logs.list_streams()
            assert "service.name" in await c.logs.list_labels()
            for _ in range(30):
                if got:
                    break
                await asyncio.sleep(1)
            await handle.stop()
            assert got == [f"atail-{RUN} match"]
            tracing = c.init_tracing(f"py-async-trace-{RUN}", set_global=False)
            trace_id = _emit_trace(tracing)
            trace = await c.traces.wait_for_trace(trace_id, min_spans=2, timeout_s=60)
            assert len(trace.spans) == 2
            hits = await c.traces.search_traces(
                sdk.TraceSearchQuery(service=f"py-async-trace-{RUN}")
            )
            assert hits and hits[0].trace_id == trace_id
            assert f"py-async-trace-{RUN}" in await c.traces.list_services()
            assert await c.traces.list_operations(f"py-async-trace-{RUN}") == [
                "GET /checkout",
                "db.query",
            ]
            assert isinstance(await c.traces.get_dependencies(), list)
            spans = await c.query.query_traces(service=f"py-async-trace-{RUN}")
            assert len(spans.spans) == 2
            agg = (
                await c.query.aggregate("logs")
                .filter(f"service.name = '{service}'")
                .count("n")
                .run()
            )
            assert agg.rows[0].values["n"] == 2
            assert await c.query.list_log_fields(search="service")
            assert service in await c.query.list_field_values("service.name", search=service)
            exporter = sdk.AsyncSignozLogExporter(c.config, protocol="grpc")
            result = await exporter.export(
                [sdk.LogRecord(body=f"async-exp {RUN}", resource={"service.name": service})]
            )
            await exporter.shutdown()
            assert result.accepted == 1
            await c.logs.wait_for_log(f"body = 'async-exp {RUN}'", timeout_s=60)
            alog = sdk.create_async_log_client(c.config, service_name=service)
            alog.send(sdk.LogInput(f"async-client {RUN}"))
            await alog.close()
            await c.logs.wait_for_log(f"body = 'async-client {RUN}'", timeout_s=60)

    asyncio.run(run())
