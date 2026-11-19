"""SigNoz clients (sync and async): health, ping, version, query clients, OTLP log clients and
tracing.

This file is owned by the service package (generated once by sdk-kit).
"""

from __future__ import annotations

import contextlib
import time
from collections.abc import Callable, Mapping
from datetime import datetime
from types import TracebackType
from typing import Any

import httpx

from ._api import AsyncApi, Call, SyncApi
from ._core import (
    AsyncHttpTransport,
    CancelledError,
    CancelToken,
    ConfigError,
    HealthStatus,
    HttpTransport,
    Request,
    ResourceRegistry,
    Response,
    ServiceError,
    TimeoutError,
    UnavailableError,
    UnsupportedError,
    ValidationError,
)
from ._logging import AsyncBatchingLogClient, AttrValue, BatchingLogClient, BatchOptions
from ._logs import (
    AsyncSignozLogQueryClient,
    AsyncSignozTailHandle,
    SignozLogQueryClient,
)
from ._otlp import AsyncSignozLogExporter, SignozLogExporter
from ._query import AsyncSignozQueryClient, SignozQueryClient
from ._traces import init_tracing as _init_tracing
from ._tracing import TracingConfig, TracingHandle
from .config import OtlpProtocol, SignozConfig

__all__ = [
    "AsyncSignozClient",
    "SignozClient",
    "create_async_log_client",
    "create_log_client",
]

_HEALTH = Request(
    "GET",
    "/api/v1/health",
    operation="health",
    params={"live": 1},
    raise_for_status=False,
    max_retries=0,
)
_PING = Call("GET", "/api/v1/fields/keys", "ping", params={"signal": "logs", "limit": 1})
_VERSION = Call(
    "GET", "/api/v1/version", "server_version", envelope=False, null_on_404=True, auth=False
)
_UNSET: Any = object()


def _health_from_response(resp: Response) -> HealthStatus:
    status = "ok" if resp.ok else "unhealthy"
    details: dict[str, str] = {}
    try:
        body: Any = resp.json()
    except ValidationError:
        body = None
    if isinstance(body, Mapping):
        for key, value in body.items():
            if key == "status" and isinstance(value, str) and value:
                status = value
            elif isinstance(value, (str, int, float, bool)):
                details[str(key)] = str(value)
    if not resp.ok:
        details.setdefault("httpStatus", str(resp.status))
    return HealthStatus(healthy=resp.ok and status == "ok", status=status, details=details)


def _health_from_error(err: ServiceError) -> HealthStatus:
    if isinstance(err, (CancelledError, ConfigError)):
        raise err
    word = "unreachable" if isinstance(err, (UnavailableError, TimeoutError)) else "unhealthy"
    return HealthStatus(healthy=False, status=word, details={"error": err.message})


def _version(result: Any) -> str:
    body = result.data if result is not None else None
    version = body.get("version") if isinstance(body, Mapping) else None
    if not isinstance(version, str) or not version.strip():
        raise UnsupportedError("server_version: the server does not report its version")
    return version.strip().removeprefix("v")


def _resource(
    service_name: str | None,
    service_version: str | None,
    environment: str | None,
    resource: Mapping[str, AttrValue] | None,
) -> dict[str, AttrValue]:
    out: dict[str, AttrValue] = {"service.name": service_name or "unknown_service"}
    if service_version:
        out["service.version"] = service_version
    if environment:
        out["deployment.environment"] = environment
    out.update(resource or {})
    return out


def create_log_client(
    config: SignozConfig | None = None,
    *,
    service_name: str | None = None,
    service_version: str | None = None,
    environment: str | None = None,
    protocol: OtlpProtocol | None = None,
    endpoint: str | None = None,
    headers: Mapping[str, str] | None = None,
    options: BatchOptions | None = None,
    resource: Mapping[str, AttrValue] | None = None,
    redaction: Any = _UNSET,
    correlate: bool = True,
    http_transport: httpx.BaseTransport | None = None,
) -> BatchingLogClient:
    """A :class:`BatchingLogClient` sending OTLP logs to the SigNoz collector.

    Needs no API key (only ``ingestion_key`` for SigNoz Cloud). ``service_name`` (default
    ``unknown_service``), ``service_version`` and ``environment`` become resource attributes;
    ``protocol``/``endpoint`` default to the config; ``options`` tunes the batch processor;
    ``redaction`` defaults to ``RedactionProcessor()`` (``None`` disables it).
    """
    cfg = config or SignozConfig.from_env()
    exporter = SignozLogExporter(
        cfg, protocol=protocol, endpoint=endpoint, headers=headers, http_transport=http_transport
    )
    kwargs: dict[str, Any] = {}
    if redaction is not _UNSET:
        kwargs["redaction"] = redaction
    return BatchingLogClient(
        exporter,
        options=options,
        resource=_resource(service_name, service_version, environment, resource),
        correlate=correlate,
        **kwargs,
    )


def create_async_log_client(
    config: SignozConfig | None = None,
    *,
    service_name: str | None = None,
    service_version: str | None = None,
    environment: str | None = None,
    protocol: OtlpProtocol | None = None,
    endpoint: str | None = None,
    headers: Mapping[str, str] | None = None,
    options: BatchOptions | None = None,
    resource: Mapping[str, AttrValue] | None = None,
    redaction: Any = _UNSET,
    correlate: bool = True,
    http_transport: httpx.AsyncBaseTransport | None = None,
) -> AsyncBatchingLogClient:
    """Async :func:`create_log_client` (an :class:`AsyncBatchingLogClient`)."""
    cfg = config or SignozConfig.from_env()
    exporter = AsyncSignozLogExporter(
        cfg, protocol=protocol, endpoint=endpoint, headers=headers, http_transport=http_transport
    )
    kwargs: dict[str, Any] = {}
    if redaction is not _UNSET:
        kwargs["redaction"] = redaction
    return AsyncBatchingLogClient(
        exporter,
        options=options,
        resource=_resource(service_name, service_version, environment, resource),
        correlate=correlate,
        **kwargs,
    )


class _Stopper:
    """Adapts ``close()``/``shutdown()`` objects to the resource registry's ``stop()``."""

    def __init__(self, stop: Callable[[], Any]) -> None:
        self.stop = stop


class SignozClient:
    """Synchronous SigNoz client. Thread-safe; use as a context manager or call :meth:`close`.

    * :meth:`health`, :meth:`ping`, :meth:`server_version`
    * :attr:`query` — :class:`SignozQueryClient` (logs, spans, traces, services, aggregations);
      :attr:`traces` is the same object (``TraceQueryClient``)
    * :attr:`logs` — :class:`SignozLogQueryClient` (``LogQueryClient``: search, tail, …)
    * :meth:`log_client` — OTLP log client; :meth:`init_tracing` — OpenTelemetry tracing

    :meth:`close` flushes and closes every log client, tracing handle and tail created through
    it. Every I/O method accepts ``cancel=`` (a :class:`CancelToken`).
    """

    def __init__(
        self,
        config: SignozConfig | None = None,
        *,
        http_transport: httpx.BaseTransport | None = None,
        clock: Callable[[], datetime] | None = None,
    ) -> None:
        if config is None:
            config = SignozConfig.from_env()
        self.config = config.validate()
        self._resources = ResourceRegistry()
        self._transport = HttpTransport(
            self.config, known_secrets=self.config.known_secrets, http_transport=http_transport
        )
        self._api = SyncApi(self._transport, self.config, http_transport)
        self.query = SignozQueryClient(self._api, clock)
        self.logs = SignozLogQueryClient(self.query, self._api, self._resources.register)

    @classmethod
    def from_env(cls, env: Mapping[str, str] | None = None, **overrides: Any) -> SignozClient:
        """Build a client from ``GROW_SIGNOZ_*`` and vendor environment variables."""
        return cls(SignozConfig.from_env(env, **overrides))

    @property
    def traces(self) -> SignozQueryClient:
        """Tracing-category query client (the same object as :attr:`query`)."""
        return self.query

    @property
    def resources(self) -> ResourceRegistry:
        """Background resources (log clients, tracing handles, tails) stopped by :meth:`close`."""
        return self._resources

    def request(self, req: Request, *, cancel: CancelToken | None = None) -> Response:
        """Send a raw request through the client's transport (retries, hooks, redaction)."""
        return self._transport.request(req, cancel=cancel)

    def health(self, *, cancel: CancelToken | None = None) -> HealthStatus:
        """Unauthenticated liveness (``GET /api/v1/health?live=1``, also checks ClickHouse).

        Never raises for an unhealthy or unreachable service (only ``CancelledError`` and
        ``ConfigError``).
        """
        try:
            resp = self._transport.request(_HEALTH, cancel=cancel)
        except ServiceError as err:
            return _health_from_error(err)
        return _health_from_response(resp)

    def ping(self, *, cancel: CancelToken | None = None) -> float:
        """Authenticated round trip (``GET /api/v1/fields/keys`` with ``SIGNOZ-API-KEY``).

        Returns the latency in milliseconds; :class:`AuthenticationError` on a bad key,
        :class:`ConfigError` without one.
        """
        started = time.monotonic()
        self._api.call(_PING, cancel)
        return (time.monotonic() - started) * 1000.0

    def server_version(self, *, cancel: CancelToken | None = None) -> str:
        """Server version from ``GET /api/v1/version`` (leading ``v`` stripped).

        The official community image reports an empty version → :class:`UnsupportedError`.
        """
        return _version(self._api.call(_VERSION, cancel))

    def log_client(
        self,
        *,
        service_name: str | None = None,
        service_version: str | None = None,
        environment: str | None = None,
        protocol: OtlpProtocol | None = None,
        endpoint: str | None = None,
        headers: Mapping[str, str] | None = None,
        options: BatchOptions | None = None,
        resource: Mapping[str, AttrValue] | None = None,
        redaction: Any = _UNSET,
        correlate: bool = True,
    ) -> BatchingLogClient:
        """An OTLP log client for the collector (see :func:`create_log_client`); it is
        flushed and closed by :meth:`close`."""
        client = create_log_client(
            self.config,
            service_name=service_name,
            service_version=service_version,
            environment=environment,
            protocol=protocol,
            endpoint=endpoint,
            headers=headers,
            options=options,
            resource=resource,
            redaction=redaction,
            correlate=correlate,
        )
        self._resources.register(_Stopper(client.close))
        return client

    def init_tracing(self, config: TracingConfig | str, **overrides: Any) -> TracingHandle:
        """Tracing to the collector with this client's config (see :func:`init_tracing`); the
        handle is shut down by :meth:`close`."""
        handle = _init_tracing(config, signoz=self.config, **overrides)
        self._resources.register(_Stopper(handle.shutdown))
        return handle

    def close(self) -> None:
        """Flush and close log clients, tracing handles and tails, then close connections.

        Idempotent.
        """
        self._resources.stop_all()
        self._transport.close()

    def __enter__(self) -> SignozClient:
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> None:
        self.close()

    def __repr__(self) -> str:
        return f"SignozClient(config={self.config!r})"


class AsyncSignozClient:
    """Asynchronous SigNoz client; use ``async with`` or call :meth:`aclose`.

    Same surface as :class:`SignozClient` with awaited methods (:attr:`query`,
    :attr:`traces`, :attr:`logs`, :meth:`log_client` returns an
    :class:`AsyncBatchingLogClient`). Cancel a call by cancelling its task: the SDK raises
    :class:`CancelledError`, which is also an :class:`asyncio.CancelledError`.
    """

    def __init__(
        self,
        config: SignozConfig | None = None,
        *,
        http_transport: httpx.AsyncBaseTransport | None = None,
        clock: Callable[[], datetime] | None = None,
    ) -> None:
        if config is None:
            config = SignozConfig.from_env()
        self.config = config.validate()
        self._resources = ResourceRegistry()
        self._transport = AsyncHttpTransport(
            self.config, known_secrets=self.config.known_secrets, http_transport=http_transport
        )
        self._api = AsyncApi(self._transport, self.config, http_transport)
        self._log_clients: list[AsyncBatchingLogClient] = []
        self._tails: list[AsyncSignozTailHandle] = []
        self._tracing: list[TracingHandle] = []
        self._closed = False
        self.query = AsyncSignozQueryClient(self._api, clock)
        self.logs = AsyncSignozLogQueryClient(self.query, self._api, self._tails.append)

    @classmethod
    def from_env(cls, env: Mapping[str, str] | None = None, **overrides: Any) -> AsyncSignozClient:
        """Build a client from ``GROW_SIGNOZ_*`` and vendor environment variables."""
        return cls(SignozConfig.from_env(env, **overrides))

    @property
    def traces(self) -> AsyncSignozQueryClient:
        """Tracing-category query client (the same object as :attr:`query`)."""
        return self.query

    @property
    def resources(self) -> ResourceRegistry:
        """Synchronous background resources stopped by :meth:`aclose`."""
        return self._resources

    async def request(self, req: Request) -> Response:
        """Send a raw request through the client's transport."""
        return await self._transport.request(req)

    async def health(self) -> HealthStatus:
        """See :meth:`SignozClient.health`."""
        try:
            resp = await self._transport.request(_HEALTH)
        except ServiceError as err:
            return _health_from_error(err)
        return _health_from_response(resp)

    async def ping(self) -> float:
        """See :meth:`SignozClient.ping`."""
        started = time.monotonic()
        await self._api.call(_PING)
        return (time.monotonic() - started) * 1000.0

    async def server_version(self) -> str:
        """See :meth:`SignozClient.server_version`."""
        return _version(await self._api.call(_VERSION))

    def log_client(
        self,
        *,
        service_name: str | None = None,
        service_version: str | None = None,
        environment: str | None = None,
        protocol: OtlpProtocol | None = None,
        endpoint: str | None = None,
        headers: Mapping[str, str] | None = None,
        options: BatchOptions | None = None,
        resource: Mapping[str, AttrValue] | None = None,
        redaction: Any = _UNSET,
        correlate: bool = True,
    ) -> AsyncBatchingLogClient:
        """An async OTLP log client (see :func:`create_async_log_client`), closed by
        :meth:`aclose`."""
        client = create_async_log_client(
            self.config,
            service_name=service_name,
            service_version=service_version,
            environment=environment,
            protocol=protocol,
            endpoint=endpoint,
            headers=headers,
            options=options,
            resource=resource,
            redaction=redaction,
            correlate=correlate,
        )
        self._log_clients.append(client)
        return client

    def init_tracing(self, config: TracingConfig | str, **overrides: Any) -> TracingHandle:
        """See :meth:`SignozClient.init_tracing`; shut down by :meth:`aclose`."""
        handle = _init_tracing(config, signoz=self.config, **overrides)
        self._tracing.append(handle)
        return handle

    async def aclose(self) -> None:
        """Flush and close log clients, tracing handles and tails, then close connections.

        Idempotent.
        """
        if self._closed:
            return
        self._closed = True
        for tail in self._tails:
            with contextlib.suppress(Exception):
                await tail.stop()
        for client in self._log_clients:
            with contextlib.suppress(Exception):
                await client.close()
        for handle in self._tracing:
            with contextlib.suppress(Exception):
                handle.shutdown()
        self._resources.stop_all()
        await self._transport.aclose()

    close = aclose

    async def __aenter__(self) -> AsyncSignozClient:
        return self

    async def __aexit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> None:
        await self.aclose()

    def __repr__(self) -> str:
        return f"AsyncSignozClient(config={self.config!r})"
