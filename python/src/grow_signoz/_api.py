"""SigNoz HTTP conventions and the sans-I/O call driver shared by the sync and async clients.

Query operations are written once as generators that ``yield`` :class:`Call` objects (or a list
of calls to run concurrently, at most :data:`MAX_CONCURRENCY` at a time) and receive the decoded
:class:`ApiResult` (``None`` for a tolerated 404). :class:`SyncApi` and :class:`AsyncApi` drive
those generators over the shared transports.
"""

from __future__ import annotations

import asyncio
import dataclasses
import json
from collections.abc import Generator, Mapping, Sequence
from concurrent.futures import ThreadPoolExecutor
from typing import Any, TypeVar

import httpx

from ._core import (
    AsyncHttpTransport,
    CancelToken,
    ConfigError,
    HttpTransport,
    Request,
    Response,
    UnavailableError,
    UnsupportedError,
    error_from_status,
    parse_retry_after,
    redact_text,
)
from .config import API_KEY_HEADER, SignozConfig

__all__ = ["MAX_CONCURRENCY", "ApiResult", "AsyncApi", "Call", "Op", "SyncApi"]

T = TypeVar("T")

#: Parallel requests of one operation (``search_traces`` waterfall fills).
MAX_CONCURRENCY = 4


@dataclasses.dataclass(frozen=True)
class Call:
    """One SigNoz API request.

    ``envelope`` unwraps ``{"status":"success","data":…}`` (legacy v1 endpoints return bare
    JSON); ``null_on_404`` yields ``None`` instead of raising :class:`NotFoundError`; ``auth``
    sends the ``SIGNOZ-API-KEY`` header.
    """

    method: str
    path: str
    operation: str
    params: Mapping[str, Any] | None = None
    json: Any = None
    envelope: bool = True
    null_on_404: bool = False
    auth: bool = True


@dataclasses.dataclass(frozen=True)
class ApiResult:
    """Decoded payload (``data`` of the envelope) and the envelope's warning messages."""

    data: Any
    warnings: list[str] = dataclasses.field(default_factory=list)


#: An operation: yields calls (or lists of calls), receives results, returns ``T``.
Op = Generator["Call | list[Call]", Any, T]


def _is_json(content_type: str) -> bool:
    lower = content_type.lower()
    return "json" in lower


def _warnings(body: Any) -> list[str]:
    data = body.get("data") if isinstance(body, Mapping) else None
    warning = data.get("warning") if isinstance(data, Mapping) else None
    if not isinstance(warning, Mapping):
        return []
    out: list[str] = []
    for item in warning.get("warnings") or []:
        message = item.get("message") if isinstance(item, Mapping) else None
        if isinstance(message, str) and message:
            out.append(message)
    message = warning.get("message")
    if not out and isinstance(message, str) and message:
        out.append(message)
    return out


class _ApiBase:
    def __init__(self, config: SignozConfig) -> None:
        self.config = config

    def secrets(self) -> list[str]:
        """Credential values to redact."""
        return self.config.known_secrets()

    def auth_headers(self, operation: str) -> dict[str, str]:
        """``{"SIGNOZ-API-KEY": key}``; :class:`ConfigError` when no key is configured."""
        key = self.config.api_key
        if key is None:
            raise ConfigError(
                f"{operation}: api_key is required for SigNoz query calls "
                "(GROW_SIGNOZ_API_KEY / SIGNOZ_API_KEY)"
            )
        return {API_KEY_HEADER: key.reveal()}

    def request_for(self, call: Call) -> Request:
        return Request(
            call.method,
            call.path,
            operation=call.operation,
            params=call.params,
            json=call.json,
            headers=self.auth_headers(call.operation) if call.auth else None,
            # Every SigNoz query endpoint is read-only, so POST queries are safe to retry.
            idempotent=True,
            raise_for_status=not call.null_on_404,
        )

    def decode(self, call: Call, resp: Response) -> ApiResult | None:
        """Map a response: 404 → ``None`` (``null_on_404``), other errors raised, HTML (the
        SPA index the server returns for unknown routes) → :class:`UnsupportedError`."""
        if resp.status >= 400:
            if resp.status == 404 and call.null_on_404:
                return None
            raise error_from_status(
                resp.status,
                call.operation,
                content=resp.content,
                headers=resp.headers,
                known_secrets=self.secrets(),
                retry_after=parse_retry_after(resp.headers.get("retry-after")),
            )
        if not _is_json(resp.headers.get("content-type", "")):
            raise UnsupportedError(
                f"{call.operation}: endpoint not available on this server version "
                f"(non-JSON response, HTTP {resp.status})",
                http_status=resp.status,
            )
        text = resp.content.strip()
        try:
            body = json.loads(text) if text else None
        except ValueError:
            raise UnavailableError(
                f"{call.operation}: invalid JSON in response", http_status=resp.status
            ) from None
        if not call.envelope:
            return ApiResult(body)
        if not isinstance(body, Mapping) or body.get("status") != "success":
            raise UnavailableError(
                f"{call.operation}: unexpected response shape", http_status=resp.status
            )
        secrets = self.secrets()
        warnings = [redact_text(w, secrets) for w in _warnings(body)]
        return ApiResult(body.get("data"), warnings)


class SyncApi(_ApiBase):
    """Runs operations over a synchronous :class:`HttpTransport`."""

    def __init__(
        self,
        transport: HttpTransport,
        config: SignozConfig,
        http_transport: httpx.BaseTransport | None = None,
    ) -> None:
        super().__init__(config)
        self.transport = transport
        #: Optional httpx transport shared with streaming calls (live tail); tests inject one.
        self.http_transport = http_transport

    def call(self, call: Call, cancel: CancelToken | None = None) -> ApiResult | None:
        resp = self.transport.request(self.request_for(call), cancel=cancel)
        result = self.decode(call, resp)
        if result is not None:
            for warning in result.warnings:
                self.transport.log.warn(f"{call.operation}: {warning}")
        return result

    def call_many(
        self, calls: Sequence[Call], cancel: CancelToken | None = None
    ) -> list[ApiResult | None]:
        if len(calls) <= 1:
            return [self.call(c, cancel) for c in calls]
        with ThreadPoolExecutor(max_workers=min(MAX_CONCURRENCY, len(calls))) as pool:
            return list(pool.map(lambda c: self.call(c, cancel), calls))

    def run(self, op: Op[T], cancel: CancelToken | None = None) -> T:
        """Drive ``op`` to completion."""
        try:
            item = next(op)
            while True:
                result: Any = (
                    self.call_many(item, cancel)
                    if isinstance(item, list)
                    else self.call(item, cancel)
                )
                item = op.send(result)
        except StopIteration as stop:
            return stop.value  # type: ignore[no-any-return]
        finally:
            op.close()


class AsyncApi(_ApiBase):
    """Runs operations over an :class:`AsyncHttpTransport`."""

    def __init__(
        self,
        transport: AsyncHttpTransport,
        config: SignozConfig,
        http_transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        super().__init__(config)
        self.transport = transport
        #: Optional httpx transport shared with streaming calls (live tail); tests inject one.
        self.http_transport = http_transport

    async def call(self, call: Call) -> ApiResult | None:
        resp = await self.transport.request(self.request_for(call))
        result = self.decode(call, resp)
        if result is not None:
            for warning in result.warnings:
                self.transport.log.warn(f"{call.operation}: {warning}")
        return result

    async def call_many(self, calls: Sequence[Call]) -> list[ApiResult | None]:
        semaphore = asyncio.Semaphore(MAX_CONCURRENCY)

        async def one(c: Call) -> ApiResult | None:
            async with semaphore:
                return await self.call(c)

        return list(await asyncio.gather(*(one(c) for c in calls)))

    async def run(self, op: Op[T]) -> T:
        """Drive ``op`` to completion."""
        try:
            item = next(op)
            while True:
                result: Any = (
                    await self.call_many(item) if isinstance(item, list) else await self.call(item)
                )
                item = op.send(result)
        except StopIteration as stop:
            return stop.value  # type: ignore[no-any-return]
        finally:
            op.close()
