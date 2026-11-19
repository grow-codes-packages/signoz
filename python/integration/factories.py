"""Factories the integration suite and the conformance runner use (owned by this repository).

SigNoz community reports an empty version, so ``version`` is not a core capability
(``server_version`` raises ``UnsupportedError``; see docs/compatibility.md).
"""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any

import grow_signoz as sdk
from grow_signoz.testing import LoggingTarget, ScenarioContext, TracingTarget
from grow_signoz.testing.logging_conformance import batch_options_for

URL_VAR = "GROW_SIGNOZ_URL"

#: Capabilities of the deployment under test for the core scenarios ("version" = serverVersion).
CORE_CAPABILITIES: set[str] = set()


def core_client(env: Mapping[str, str]) -> sdk.SignozClient:
    """A client configured from the test environment (URL, API key, OTLP endpoint)."""
    return sdk.SignozClient.from_env(env)


def async_core_client(env: Mapping[str, str]) -> sdk.AsyncSignozClient:
    return sdk.AsyncSignozClient.from_env(env)


#: Capabilities for the logging scenarios: query, tail, streams, labels.
LOGGING_CAPABILITIES: set[str] = {"query", "tail", "streams", "labels"}


def logging_target(
    env: Mapping[str, str],
    scenario: Mapping[str, Any],
    query: sdk.SignozClient,
    ctx: ScenarioContext,
) -> Any:
    """``LoggingTarget(client, query)``: OTLP log client + the SigNoz log query client."""
    client = sdk.create_log_client(
        sdk.SignozConfig.from_env(env),
        service_name=f"py-conformance-logs-{ctx.run}",
        options=batch_options_for(scenario),
    )
    return LoggingTarget(client=client, query=query.logs)


#: Capabilities for the tracing scenarios: query, services, dependencies.
TRACING_CAPABILITIES: set[str] = {"query", "services", "dependencies"}


def tracing_target(env: Mapping[str, str], service_name: str, query: sdk.SignozClient) -> Any:
    """``TracingTarget(handle, query)``: tracing to the collector + the trace query client."""
    handle = sdk.init_tracing(service_name, signoz=sdk.SignozConfig.from_env(env), set_global=False)
    return TracingTarget(handle=handle, query=query.traces)
