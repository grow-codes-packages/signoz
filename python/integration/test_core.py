"""Core round trips against the real service plus the core, logging and tracing conformance
scenarios."""

from __future__ import annotations

import asyncio
import uuid
from typing import Any

import pytest
from factories import (
    CORE_CAPABILITIES,
    LOGGING_CAPABILITIES,
    TRACING_CAPABILITIES,
    async_core_client,
    core_client,
    logging_target,
    tracing_target,
)

import grow_signoz as sdk
from grow_signoz.testing import ConformanceRunner, assert_scenario, pytest_params


def test_health(client: sdk.SignozClient) -> None:
    status = client.health()
    assert status.healthy, status
    assert status.status == "ok"


def test_ping(client: sdk.SignozClient) -> None:
    assert client.ping() >= 0


def test_server_version(client: sdk.SignozClient) -> None:
    """Community image reports "" → UNSUPPORTED; a later image may return a version."""
    try:
        assert client.server_version().strip()
    except sdk.UnsupportedError as err:
        assert err.code == sdk.ErrorCode.UNSUPPORTED


def test_close_is_idempotent(env: dict[str, str]) -> None:
    c = core_client(env)
    c.close()
    c.close()


def test_async_client(env: dict[str, str]) -> None:
    async def run() -> None:
        async with async_core_client(env) as c:
            assert (await c.health()).healthy
            assert await c.ping() >= 0
            with pytest.raises(sdk.UnsupportedError):
                await c.server_version()

    asyncio.run(run())


def test_bad_api_key_is_rejected(env: dict[str, str]) -> None:
    wrong = "definitely-wrong-api-key"
    with core_client({**env, "GROW_SIGNOZ_API_KEY": wrong}) as c:
        with pytest.raises(sdk.AuthenticationError) as info:
            c.ping()
        assert info.value.http_status == 401
        assert wrong not in str(info.value)


@pytest.mark.parametrize("scenario", pytest_params(categories=["core"]))
def test_core_conformance(env: dict[str, str], scenario: dict[str, Any]) -> None:
    runner = ConformanceRunner(
        {"core": lambda ctx: core_client(env)}, capabilities=CORE_CAPABILITIES
    )
    assert_scenario(runner, scenario)


@pytest.mark.parametrize("scenario", pytest_params(categories=["logging"]))
def test_logging_conformance(
    env: dict[str, str], client: sdk.SignozClient, scenario: dict[str, Any]
) -> None:
    runner = ConformanceRunner(
        {"logging": lambda ctx: logging_target(env, scenario, client, ctx)},
        capabilities=LOGGING_CAPABILITIES,
    )
    result = assert_scenario(runner, scenario)
    assert result.status == "passed"


@pytest.mark.parametrize("scenario", pytest_params(categories=["tracing"]))
def test_tracing_conformance(
    env: dict[str, str], client: sdk.SignozClient, scenario: dict[str, Any]
) -> None:
    service = f"py-conformance-traces-{uuid.uuid4().hex[:8]}"
    runner = ConformanceRunner(
        {"tracing": lambda ctx: tracing_target(env, service, client)},
        capabilities=TRACING_CAPABILITIES,
        service_name=service,
    )
    result = assert_scenario(runner, scenario)
    assert result.status == "passed"
