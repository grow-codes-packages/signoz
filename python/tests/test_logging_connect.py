"""Connector of ``init_logging()`` (service-owned: generated once by sdk-kit)."""

from __future__ import annotations

import grow_signoz as sdk
from grow_signoz.logging_connect import connect_log_client


def test_connect_log_client_without_io() -> None:
    try:
        connection = connect_log_client({"service.name": "t"}, {}, lambda _records: None)
    except sdk.ConfigError:
        return  # the service needs more settings (credentials): init_logging falls back to stdout
    if connection is not None:
        assert callable(connection.logs.send)
        if connection.close is not None:
            connection.close()
