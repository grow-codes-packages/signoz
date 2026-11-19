"""SigNoz test helpers: recorded fixtures and a routing httpx mock transport."""

from __future__ import annotations

import json
from collections import defaultdict, deque
from collections.abc import Callable
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import httpx

import grow_signoz as sdk
from _support import fast_retry

FIXTURES = Path(__file__).parent / "fixtures"
API_KEY = "signoz-test-api-key-123"
NOW = datetime(2026, 11, 18, 20, 30, tzinfo=timezone.utc)


def fixture(name: str) -> Any:
    """A recorded response body from ``tests/fixtures/<name>.json``."""
    return json.loads((FIXTURES / f"{name}.json").read_text(encoding="utf-8"))


def fixture_text(name: str) -> str:
    return (FIXTURES / name).read_text(encoding="utf-8")


Handler = Callable[[httpx.Request], httpx.Response]


def json_response(body: Any, status: int = 200, **headers: str) -> httpx.Response:
    return httpx.Response(
        status,
        content=json.dumps(body).encode(),
        headers={"content-type": "application/json", **headers},
    )


def success(data: Any) -> httpx.Response:
    return json_response({"status": "success", "data": data})


class Router:
    """Routes ``(method, path)`` to queued responses or handlers; records every request."""

    def __init__(self) -> None:
        self.routes: dict[tuple[str, str], deque[httpx.Response | Handler]] = defaultdict(deque)
        self.defaults: dict[tuple[str, str], httpx.Response | Handler] = {}
        self.requests: list[httpx.Request] = []

    def add(self, method: str, path: str, *replies: httpx.Response | Handler) -> Router:
        self.routes[(method, path)].extend(replies)
        return self

    def default(self, method: str, path: str, reply: httpx.Response | Handler) -> Router:
        self.defaults[(method, path)] = reply
        return self

    def handle(self, request: httpx.Request) -> httpx.Response:
        request.read()
        self.requests.append(request)
        key = (request.method, request.url.path)
        queue = self.routes.get(key)
        reply = queue.popleft() if queue else self.defaults.get(key)
        if reply is None:
            return httpx.Response(
                200, content=b"<!doctype html><html></html>", headers={"content-type": "text/html"}
            )
        if callable(reply):
            return reply(request)
        return httpx.Response(reply.status_code, headers=reply.headers, content=reply.content)

    def transport(self) -> httpx.MockTransport:
        return httpx.MockTransport(self.handle)

    def bodies(self, path: str) -> list[Any]:
        return [json.loads(r.content) for r in self.requests if r.url.path == path]


def config(**kw: Any) -> sdk.SignozConfig:
    kw.setdefault("url", "http://signoz.test:8080")
    kw.setdefault("api_key", API_KEY)
    kw.setdefault("retry", fast_retry())
    return sdk.SignozConfig(**kw)


def client(router: Router, **kw: Any) -> sdk.SignozClient:
    return sdk.SignozClient(config(**kw), http_transport=router.transport(), clock=lambda: NOW)


def aclient(router: Router, **kw: Any) -> sdk.AsyncSignozClient:
    return sdk.AsyncSignozClient(config(**kw), http_transport=router.transport(), clock=lambda: NOW)
