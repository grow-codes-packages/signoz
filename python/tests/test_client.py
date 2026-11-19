"""SigNoz client (sync + async): config, health, ping, version, close and polling helpers."""

from __future__ import annotations

import asyncio
import time
from typing import Any

import httpx
import pytest

import grow_signoz as sdk
from _signoz import API_KEY, Router, aclient, client, config, fixture, json_response, success
from _support import MockServer, Reply, closed_port_url, make_config, spawn_capturing

P = sdk.ENV_PREFIX

# --------------------------------------------------------------------------- config


def test_config_defaults_and_env_precedence() -> None:
    cfg = sdk.SignozConfig()
    assert cfg.url == sdk.DEFAULT_URL == "http://127.0.0.1:8080"
    assert cfg.otlp_endpoint == sdk.DEFAULT_OTLP_HTTP_ENDPOINT
    assert cfg.otlp_protocol == "http/protobuf"
    assert cfg.api_key is None and cfg.ingestion_key is None
    grpc = sdk.SignozConfig(otlp_protocol="GRPC ")  # type: ignore[arg-type]
    assert (grpc.otlp_protocol, grpc.otlp_endpoint) == ("grpc", sdk.DEFAULT_OTLP_GRPC_ENDPOINT)
    env = {
        "SIGNOZ_URL": "http://vendor:8080",
        "SIGNOZ_API_KEY": "vendor-key-1",
        "OTEL_EXPORTER_OTLP_ENDPOINT": "http://otel:4318/",
        "OTEL_EXPORTER_OTLP_PROTOCOL": "grpc",
        "SIGNOZ_INGESTION_KEY": "ingest-key-1",
    }
    cfg = sdk.SignozConfig.from_env(env)
    assert cfg.url == "http://vendor:8080"
    assert cfg.api_key is not None and cfg.api_key.reveal() == "vendor-key-1"
    assert cfg.otlp_endpoint == "http://otel:4318"
    assert cfg.otlp_protocol == "grpc"
    assert cfg.ingestion_key is not None and cfg.ingestion_key.reveal() == "ingest-key-1"
    env |= {
        "SIGNOZ_OTLP_ENDPOINT": "http://sig:4318",
        P + "URL": "http://grow:8080",
        P + "API_KEY": "grow-key-12",
        P + "OTLP_PROTOCOL": "http/protobuf",
        P + "INGESTION_KEY": "grow-ingest",
    }
    cfg = sdk.SignozConfig.from_env(env)
    assert cfg.url == "http://grow:8080"
    assert cfg.api_key is not None and cfg.api_key.reveal() == "grow-key-12"
    assert cfg.otlp_endpoint == "http://sig:4318"
    assert cfg.otlp_protocol == "http/protobuf"
    env[P + "OTLP_ENDPOINT"] = "http://grow-otlp:4318"
    assert sdk.SignozConfig.from_env(env).otlp_endpoint == "http://grow-otlp:4318"
    explicit = sdk.SignozConfig.from_env(env, url="http://explicit:1", api_key="explicit-key")
    assert explicit.url == "http://explicit:1"
    assert explicit.api_key is not None and explicit.api_key.reveal() == "explicit-key"
    text = repr(explicit) + str(explicit)
    for secret in ("explicit-key", "grow-ingest"):
        assert secret not in text
    assert sorted(explicit.known_secrets()) == ["explicit-key", "grow-ingest"]


def test_config_validation() -> None:
    with pytest.raises(sdk.ConfigError, match="OTEL_EXPORTER_OTLP_PROTOCOL"):
        sdk.SignozConfig.from_env({"OTEL_EXPORTER_OTLP_PROTOCOL": "thrift"})
    for kwargs, fragment in (
        ({"api_key": " "}, "api_key"),
        ({"ingestion_key": ""}, "ingestion_key"),
        ({"otlp_protocol": "thrift"}, "otlp_protocol"),
        ({"otlp_endpoint": "ftp://x"}, "otlp_endpoint"),
        ({"otlp_endpoint": "http://[::1"}, "otlp_endpoint"),
    ):
        with pytest.raises(sdk.ConfigError, match=fragment):
            sdk.SignozConfig(**kwargs).validate()  # type: ignore[arg-type]
    cfg = sdk.SignozConfig().with_changes(api_key="k-1234")
    assert isinstance(cfg.api_key, sdk.Secret)
    with pytest.raises(sdk.ConfigError):
        cfg.with_changes(url="nope")


# --------------------------------------------------------------------------- core calls


def test_health_ping_version() -> None:
    router = Router()
    router.add("GET", "/api/v1/health", json_response(fixture("health.ok")))
    router.add("GET", "/api/v1/fields/keys", json_response(fixture("listLogFields.search")))
    router.add("GET", "/api/v1/version", json_response({"version": "v0.151.0"}))
    with client(router) as c:
        assert c.health() == sdk.HealthStatus(True, "ok", {})
        assert c.ping() >= 0
        assert c.server_version() == "0.151.0"
        assert API_KEY not in repr(c)
        assert c.traces is c.query
    health, ping, version = router.requests
    assert health.url.params["live"] == "1"
    assert "signoz-api-key" not in health.headers
    assert ping.headers["SIGNOZ-API-KEY"] == API_KEY
    assert ping.url.params["signal"] == "logs" and ping.url.params["limit"] == "1"
    assert "signoz-api-key" not in version.headers
    assert ping.headers["user-agent"].startswith("grow-codes-signoz/")


def test_server_version_unsupported_and_errors() -> None:
    router = Router()
    router.add("GET", "/api/v1/version", json_response(fixture("serverVersion.ok")))
    router.add("GET", "/api/v1/version", httpx.Response(404))
    router.add("GET", "/api/v1/version", json_response({"error": "x"}, 403))
    c = client(router)
    with pytest.raises(sdk.UnsupportedError, match="does not report"):
        c.server_version()
    with pytest.raises(sdk.UnsupportedError):
        c.server_version()
    with pytest.raises(sdk.PermissionDeniedError):
        c.server_version()
    c.close()


def test_health_variants() -> None:
    router = Router()
    router.add("GET", "/api/v1/health", json_response({"status": "degraded"}, 503))
    router.add("GET", "/api/v1/health", httpx.Response(500, text="boom"))
    router.add("GET", "/api/v1/health", json_response({"status": "ok", "build": 7}))
    c = client(router)
    assert c.health() == sdk.HealthStatus(False, "degraded", {"httpStatus": "503"})
    assert c.health() == sdk.HealthStatus(False, "unhealthy", {"httpStatus": "500"})
    assert c.health() == sdk.HealthStatus(True, "ok", {"build": "7"})
    assert len(router.requests) == 3  # health never retries


def test_core_health_unreachable() -> None:
    """core.health.unreachable"""
    c = sdk.SignozClient(make_config(closed_port_url()))
    status = c.health()
    assert (status.healthy, status.status) == (False, "unreachable")
    assert "error" in status.details


def test_health_timeout_is_unreachable(server: MockServer) -> None:
    server.enqueue("/api/v1/health", Reply(200, delay=1.0))
    c = sdk.SignozClient(make_config(server.url, request_timeout=0.1))
    assert c.health().status == "unreachable"


def test_health_raises_only_cancelled_and_config() -> None:
    router = Router()
    c = client(router)
    c.close()
    with pytest.raises(sdk.CancelledError):
        c.health()
    token = sdk.CancelToken()
    token.cancel()
    with pytest.raises(sdk.CancelledError):
        client(router).health(cancel=token)


def test_ping_errors_never_leak_the_key() -> None:
    router = Router()
    router.add("GET", "/api/v1/fields/keys", json_response(fixture("ping.unauthenticated"), 401))
    with pytest.raises(sdk.AuthenticationError) as info:
        client(router).ping()
    assert info.value.http_status == 401
    assert API_KEY not in str(info.value)
    with pytest.raises(sdk.ConfigError, match="api_key is required"):
        sdk.SignozClient(config(api_key=None), http_transport=router.transport()).ping()


def test_core_close_idempotent_closes_resources() -> None:
    """core.close.idempotent"""
    router = Router()
    c = client(router)
    stopped: list[int] = []

    class Res:
        def stop(self) -> None:
            stopped.append(1)

    c.resources.register(Res())
    logs = c.log_client(service_name="svc")
    handle = c.init_tracing("svc-trace", set_global=False)
    c.close()
    c.close()
    assert stopped == [1]
    assert handle.is_shut_down
    logs.send(sdk.LogInput("after close"))
    assert logs.stats().dropped == 1


def test_from_env_and_raw_request(monkeypatch: pytest.MonkeyPatch) -> None:
    router = Router().add("GET", "/anything", success({}))
    env = {P + "URL": "http://env:8080", P + "API_KEY": API_KEY}
    with sdk.SignozClient.from_env(env) as c:
        assert c.config.url == "http://env:8080"
    c = sdk.SignozClient(config(), http_transport=router.transport())
    assert c.request(sdk.Request("GET", "/anything")).status == 200
    monkeypatch.setenv(P + "URL", "http://monkey:8080")
    with sdk.SignozClient() as c2:
        assert c2.config.url == "http://monkey:8080"
    with pytest.raises(sdk.ConfigError):
        sdk.SignozClient.from_env({P + "URL": "nope://x"})


# --------------------------------------------------------------------------- async


async def test_async_client(monkeypatch: pytest.MonkeyPatch) -> None:
    router = Router()
    router.add("GET", "/api/v1/health", json_response({"status": "ok"}))
    router.add("GET", "/api/v1/fields/keys", success({"keys": {}}))
    router.add("GET", "/api/v1/version", json_response({"version": "1.2.3"}))
    router.add("GET", "/raw", success({}))
    async with aclient(router) as c:
        assert (await c.health()).healthy
        assert await c.ping() >= 0
        assert await c.server_version() == "1.2.3"
        assert (await c.request(sdk.Request("GET", "/raw"))).status == 200
        assert API_KEY not in repr(c)
        assert c.resources is not None and c.traces is c.query
        logs = c.log_client(service_name="svc")
        handle = c.init_tracing("svc-async", set_global=False)
    await c.close()
    assert handle.is_shut_down
    assert logs.stats().queued == 0
    with pytest.raises(sdk.CancelledError):
        await c.health()
    router.add("GET", "/api/v1/version", httpx.Response(501))
    c2 = sdk.AsyncSignozClient.from_env({P + "URL": "http://x:1"})
    await c2.aclose()
    c3 = aclient(router)
    with pytest.raises(sdk.UnsupportedError):
        await c3.server_version()
    await c3.aclose()
    monkeypatch.setenv(P + "URL", "http://monkey:8080")
    c4 = sdk.AsyncSignozClient()
    assert c4.config.url == "http://monkey:8080"
    await c4.aclose()


async def test_async_health_unreachable() -> None:
    """core.health.unreachable (async)"""
    c = sdk.AsyncSignozClient(make_config(closed_port_url()))
    status = await c.health()
    assert (status.healthy, status.status) == (False, "unreachable")
    await c.aclose()


async def test_async_health_cancel(server: MockServer) -> None:
    server.enqueue("/api/v1/health", Reply(200, delay=2.0))
    c = sdk.AsyncSignozClient(make_config(server.url))
    errors: list[BaseException] = []
    task = spawn_capturing(c.health(), errors)
    await asyncio.sleep(0.1)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert isinstance(errors[0], sdk.CancelledError)
    await c.aclose()


# --------------------------------------------------------------------------- polling


def test_poll_until() -> None:
    values = iter([None, [], [1]])
    assert sdk.poll_until(lambda: next(values), interval_s=0.001) == [1]
    errors = iter([sdk.UnavailableError("flaky"), "ok"])

    def flaky() -> str:
        item = next(errors)
        if isinstance(item, Exception):
            raise item
        return item

    assert sdk.poll_until(flaky, interval_s=0) == "ok"
    started = time.monotonic()
    with pytest.raises(sdk.TimeoutError):
        sdk.poll_until(lambda: None, timeout_s=0.02, interval_s=0.005)
    assert time.monotonic() - started < 1.0


async def test_apoll_until() -> None:
    values: Any = iter([None, "", "done"])

    async def fetch() -> str | None:
        return next(values)  # type: ignore[no-any-return]

    assert await sdk.apoll_until(fetch, interval_s=0.001) == "done"


def test_get_logger_is_silent() -> None:
    logger = sdk.get_logger()
    assert logger.name == sdk.LOGGER_NAME == "grow_signoz"
