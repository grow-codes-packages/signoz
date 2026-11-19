"""SignozLogQueryClient: search, streams, labels, wait_for_log and live tail (SSE)."""

from __future__ import annotations

import asyncio
import json
import threading
import time
from datetime import timedelta
from typing import Any

import httpx
import pytest

import grow_signoz as sdk
from _signoz import API_KEY, NOW, Router, aclient, client, fixture, fixture_text, json_response

QR = "/api/v5/query_range"
TAIL = sdk.LIVE_TAIL_PATH
START = NOW - timedelta(hours=1)


def sse(*events: str) -> httpx.Response:
    return httpx.Response(
        200, content="".join(events).encode(), headers={"content-type": "text/event-stream"}
    )


def event(row_id: str, nanos: int, body: str) -> str:
    data = {"data": {"id": row_id, "timestamp": nanos, "body": body, "severity_text": "WARN"}}
    return f"data: {json.dumps(data)}\n\n"


def wait(predicate: Any, timeout: float = 5.0) -> None:
    deadline = time.monotonic() + timeout
    while not predicate():
        assert time.monotonic() < deadline, "condition not met"
        time.sleep(0.01)


def test_search_streams_labels_wait_for_log() -> None:
    router = Router()
    router.add("POST", QR, json_response(fixture("queryLogs.page1")))
    router.add("GET", "/api/v1/fields/values", json_response(fixture("listStreams.fieldValues")))
    router.add("GET", "/api/v1/fields/keys", json_response(fixture("listLogFields.ok")))
    router.add("POST", QR, json_response(fixture("queryLogs.empty")))
    router.add("POST", QR, json_response(fixture("queryLogs.raw.ok")))
    logs = client(router).logs
    assert isinstance(logs, sdk.LogQueryClient)
    page = logs.search("marker", limit=2, order="asc")
    assert len(page.records) == 2 and page.next_cursor is not None
    spec = json.loads(router.requests[0].content)["compositeQuery"]["queries"][0]["spec"]
    assert spec["limit"] == 2 and spec["filter"] == {"expression": "marker"}
    streams = logs.list_streams()
    assert streams == sorted(streams) and "sdk-fe67ae4b" in streams
    labels = logs.list_labels()
    assert labels == sorted(set(labels)) and "service.name" in labels
    found = logs.wait_for_log("marker", timeout_s=5, interval_s=0.01)
    assert "marker" in found.body


def test_sse_parser() -> None:
    parser = sdk.SseParser()
    assert parser.feed(": comment\r\nevent: error\r\ndata: a\r\ndata: b\r\n") == []
    assert parser.feed("\r\n") == [("error", "a\nb")]
    assert parser.feed("id: 1\n\ndata:x\n\n") == [("message", "x")]


def test_tail_delivers_dedups_and_stops_on_fatal_error() -> None:
    base = 1_795_033_284_000_000_000
    router = Router()
    router.add(
        "GET",
        TAIL,
        sse(
            fixture_text("tail.sse.txt"),
            event("r2", base + 5_000_000, "second"),
            "event: error\ndata: boom " + API_KEY + "\n\n",
            "data: {not json}\n\n",
        ),
        sse(
            event("r2", base + 5_000_000, "second"),
            event("r1", base, "too old"),
            event("r3", base + 5_000_000, "third"),
        ),
        json_response(fixture("ping.unauthenticated"), 401),
    )
    got: list[sdk.LogRecord] = []
    errors: list[Exception] = []

    def on_record(record: sdk.LogRecord) -> None:
        got.append(record)
        if record.body == "third":
            raise RuntimeError("callback failure is reported")

    c = client(router)
    handle = c.logs.tail(
        "body CONTAINS 'tail'", on_record, start=START, on_error=errors.append, interval_s=0.01
    )
    wait(lambda: handle.stopped)
    assert [r.body for r in got] == ["tail-fe67ae4b match", "second", "third"]
    assert got[1].severity == sdk.Severity.WARN
    kinds = [type(e).__name__ for e in errors]
    assert kinds[0] == "UnavailableError" and API_KEY not in str(errors[0])
    assert "RuntimeError" in kinds and kinds[-1] == "AuthenticationError"
    first = router.requests[0]
    assert first.url.params["start"] == str(sdk.to_epoch_ms(START))
    assert first.url.params["filter"] == "body CONTAINS 'tail'"
    assert first.headers["SIGNOZ-API-KEY"] == API_KEY
    assert first.headers["accept"] == "text/event-stream"
    second = router.requests[1]
    assert second.url.params["start"] == str((base + 5_000_000) // 1_000_000)
    handle.stop()
    handle.stop()
    c.close()


def test_tail_unsupported_html_and_reconnect_on_timeout() -> None:
    calls: list[int] = []

    def flaky(request: httpx.Request) -> httpx.Response:
        calls.append(1)
        if len(calls) == 1:
            raise httpx.ReadTimeout("quiet", request=request)
        if len(calls) == 2:
            raise httpx.ConnectError("down", request=request)
        return httpx.Response(200, text="<html>", headers={"content-type": "text/html"})

    router = Router().default("GET", TAIL, flaky)
    errors: list[Exception] = []
    handle = client(router).logs.tail(
        "", lambda r: None, on_error=errors.append, reconnect_delay_s=0.01
    )
    wait(lambda: handle.stopped)
    assert [type(e).__name__ for e in errors] == ["UnavailableError", "UnsupportedError"]
    assert "filter" not in router.requests[0].url.params


def test_tail_stops_with_client_close() -> None:
    gate = threading.Event()

    def slow(request: httpx.Request) -> httpx.Response:
        gate.wait(0.3)
        return sse()

    router = Router().default("GET", TAIL, slow)
    c = client(router)
    handle = c.logs.tail("x", lambda r: None, interval_s=0.01)
    c.close()
    gate.set()
    assert handle.stopped


async def test_async_logs_client_and_tail() -> None:
    base = 1_795_033_284_000_000_000
    router = Router()
    router.add("POST", QR, json_response(fixture("queryLogs.raw.ok")))
    router.add("GET", "/api/v1/fields/values", json_response(fixture("listStreams.fieldValues")))
    router.add("GET", "/api/v1/fields/keys", json_response(fixture("listLogFields.ok")))
    router.add("POST", QR, json_response(fixture("queryLogs.raw.ok")))
    router.add(
        "GET",
        TAIL,
        sse(event("a", base, "one"), "event: error\ndata: e\n\n"),
        httpx.Response(200, text="{}", headers={"content-type": "application/json"}),
    )
    async with aclient(router) as c:
        logs = c.logs
        assert isinstance(logs, sdk.AsyncLogQueryClient)
        assert (await logs.search("marker")).records
        assert "sdk-fe67ae4b" in await logs.list_streams()
        assert "body" in await logs.list_labels()
        assert (await logs.wait_for_log("marker", timeout_s=5, interval_s=0.01)).body
        got: list[str] = []
        errors: list[Exception] = []
        handle = await logs.tail(
            "q", lambda r: got.append(r.body), on_error=errors.append, interval_s=0.01, start=START
        )
        for _ in range(500):
            if handle.stopped:
                break
            await asyncio.sleep(0.01)
        assert got == ["one"]
        assert [type(e).__name__ for e in errors] == ["UnavailableError", "UnsupportedError"]
        await handle.stop()
        router.default("GET", TAIL, lambda r: httpx.Response(500))
        second = await logs.tail("q", lambda r: None, interval_s=0.01)
    assert second.stopped  # closed by the client


async def test_async_tail_read_timeout_reconnects() -> None:
    calls: list[int] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(1)
        if len(calls) == 1:
            raise httpx.ReadTimeout("quiet", request=request)
        return httpx.Response(403)

    router = Router().default("GET", TAIL, handler)
    async with aclient(router) as c:
        errors: list[Exception] = []
        handle = await c.logs.tail(
            "q", lambda r: None, on_error=errors.append, reconnect_delay_s=0.01
        )
        for _ in range(500):
            if handle.stopped:
                break
            await asyncio.sleep(0.01)
    assert [type(e).__name__ for e in errors] == ["PermissionDeniedError"]


def test_tail_requires_api_key() -> None:
    from _signoz import config

    router = Router()
    c = sdk.SignozClient(config(api_key=None), http_transport=router.transport())
    errors: list[Exception] = []
    handle = c.logs.tail("q", lambda r: None, on_error=errors.append)
    wait(lambda: handle.stopped)
    assert isinstance(errors[0], sdk.ConfigError)
    with pytest.raises(AssertionError):
        wait(lambda: False, timeout=0.01)
