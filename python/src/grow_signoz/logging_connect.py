"""Connector used by ``init_logging()``.

Service-owned: generated once by sdk-kit, then maintained in this repository. It builds the SDK
log transport from the package's own ``GROW_SIGNOZ_*`` (and vendor) variables.
"""

from __future__ import annotations

from collections.abc import Callable, Mapping, Sequence

from ._logging._batch import BatchOptions
from ._logging._init import LogConnection
from ._logging._model import AttrValue, LogRecord
from .client import SignozClient


def connect_log_client(
    resource: Mapping[str, AttrValue],
    env: Mapping[str, str],
    on_dropped: Callable[[Sequence[LogRecord]], None],
) -> LogConnection | None:
    """Build ``client.log_client(resource=..., options=BatchOptions(on_drop=...))``.

    ``None`` (stdout only) when the client has no ``log_client()``. Must not do network I/O.
    Services may replace it.
    """
    client = SignozClient.from_env(env)
    factory = getattr(client, "log_client", None)
    if not callable(factory):
        client.close()
        return None
    options = BatchOptions(on_drop=lambda records, _reason: on_dropped(records))
    logs = factory(resource=dict(resource), options=options)

    def close() -> None:
        logs.close()
        client.close()

    return LogConnection(logs=logs, close=close)
