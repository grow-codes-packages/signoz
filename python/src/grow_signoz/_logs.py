"""``LogQueryClient`` for SigNoz: search, live tail over server-sent events, streams, labels."""

from __future__ import annotations

import asyncio
import contextlib
import json
import ssl
import threading
from collections.abc import Callable, Iterable
from datetime import datetime, timedelta
from typing import Any
from urllib.parse import quote

import httpx

from ._api import AsyncApi, SyncApi
from ._core import (
    AuthenticationError,
    BaseConfig,
    ConfigError,
    PermissionDeniedError,
    ServiceError,
    UnavailableError,
    UnsupportedError,
    error_from_status,
    redact_text,
    to_seconds,
    truncate,
    user_agent,
)
from ._logging import (
    AsyncBaseLogQueryClient,
    BaseLogQueryClient,
    LogRecord,
    LogSearchResult,
    SearchOrder,
)
from ._mapping import SignozLogRecord, log_row_to_record, nanos_to_ms
from ._query import AsyncSignozQueryClient, SignozQueryClient
from .aggregate import to_epoch_ms

__all__ = [
    "AsyncSignozLogQueryClient",
    "AsyncSignozTailHandle",
    "LIVE_TAIL_PATH",
    "SignozLogQueryClient",
    "SignozTailHandle",
    "SseParser",
]

#: Live tail endpoint (server-sent events).
LIVE_TAIL_PATH = "/api/v3/logs/livetail"
_NO_TAIL_TEXT = "logs.tail: live tail not available on this server version"
_FATAL = (AuthenticationError, PermissionDeniedError, UnsupportedError, ConfigError)
#: Read timeout of the live-tail stream; a quiet stream reconnects (resuming, de-duplicated).
TAIL_READ_TIMEOUT_S = 15.0


def _verify(config: BaseConfig) -> ssl.SSLContext | bool:
    tls = config.tls
    if tls.insecure_skip_verify:
        return False
    if not (tls.ca_cert_path or tls.ca_cert_pem or tls.client_cert_path):
        return True
    try:
        ctx = ssl.create_default_context(cafile=tls.ca_cert_path, cadata=tls.ca_cert_pem)
        if tls.client_cert_path and tls.client_key_path:
            ctx.load_cert_chain(tls.client_cert_path, tls.client_key_path)
    except (OSError, ssl.SSLError, ValueError):
        raise ConfigError("config: TLS certificate files could not be loaded") from None
    return ctx


class SseParser:
    """Incremental ``text/event-stream`` parser: feed text, get ``(event, data)`` pairs."""

    def __init__(self) -> None:
        self._buffer = ""

    def feed(self, text: str) -> list[tuple[str, str]]:
        self._buffer += text.replace("\r\n", "\n").replace("\r", "\n")
        events: list[tuple[str, str]] = []
        while "\n\n" in self._buffer:
            raw, self._buffer = self._buffer.split("\n\n", 1)
            event = "message"
            data: list[str] = []
            for line in raw.split("\n"):
                if not line or line.startswith(":"):
                    continue
                name, _, value = line.partition(":")
                value = value.removeprefix(" ")
                if name == "event":
                    event = value
                elif name == "data":
                    data.append(value)
            if data:
                events.append((event, "\n".join(data)))
        return events


class _TailState:
    """Resume point and de-duplication by row id within the newest millisecond."""

    def __init__(
        self,
        since_ms: int,
        on_record: Callable[[LogRecord], None],
        on_error: Callable[[Exception], None] | None,
        secrets: Callable[[], list[str]],
    ) -> None:
        self.since_ms = since_ms
        self._seen: set[str] = set()
        self._on_record = on_record
        self._on_error = on_error
        self._secrets = secrets

    def error(self, exc: Exception) -> None:
        if self._on_error is not None:
            with contextlib.suppress(Exception):
                self._on_error(exc)

    def deliver(self, record: SignozLogRecord) -> None:
        ms = nanos_to_ms(record.time_unix_nano)
        if ms < self.since_ms or (record.id and record.id in self._seen):
            return
        if ms > self.since_ms:
            self.since_ms = ms
            self._seen = set()
        if record.id:
            self._seen.add(record.id)
        try:
            self._on_record(record)
        except Exception as exc:  # noqa: BLE001 - reported, the tail keeps running
            self.error(exc)

    def handle(self, event: str, data: str) -> None:
        if event == "error":
            self.error(
                UnavailableError(f"logs.tail: {truncate(redact_text(data, self._secrets()), 300)}")
            )
            return
        try:
            self.deliver(log_row_to_record(json.loads(data)))
        except Exception as exc:  # noqa: BLE001 - one bad event must not stop the tail
            self.error(exc)

    def url(self, base: str, query: str) -> str:
        url = f"{base}{LIVE_TAIL_PATH}?start={self.since_ms}"
        if query.strip():
            url += "&filter=" + quote(query, safe="")
        return url


def _status_error(
    status: int, content: bytes, headers: httpx.Headers, secrets: list[str]
) -> ServiceError:
    return error_from_status(
        status, "logs.tail", content=content, headers=headers, known_secrets=secrets
    )


def _wrap(exc: Exception) -> Exception:
    if isinstance(exc, ServiceError):
        return exc
    return UnavailableError(f"logs.tail: {type(exc).__name__}")


class SignozTailHandle:
    """A running live tail (daemon thread); :meth:`stop` is idempotent."""

    def __init__(
        self,
        api: SyncApi,
        query: str,
        state: _TailState,
        reconnect_delay_s: float,
    ) -> None:
        self._api = api
        self._query = query
        self._state = state
        self._delay = reconnect_delay_s
        self._stop = threading.Event()
        self._lock = threading.Lock()
        self._response: httpx.Response | None = None
        config = api.config
        self._config = config
        self._client = httpx.Client(
            transport=api.http_transport,
            trust_env=False,
            verify=_verify(config),
            proxy=config.proxy,
            timeout=httpx.Timeout(
                connect=config.connect_timeout,
                read=TAIL_READ_TIMEOUT_S,
                write=config.request_timeout,
                pool=config.request_timeout,
            ),
        )
        self._thread = threading.Thread(target=self._run, name="grow-signoz-tail", daemon=True)
        self._thread.start()

    @property
    def stopped(self) -> bool:
        """``True`` after :meth:`stop` or a fatal error."""
        return self._stop.is_set()

    def _headers(self) -> dict[str, str]:
        return {
            "User-Agent": user_agent(self._config.user_agent_suffix),
            **dict(self._config.headers),
            **self._api.auth_headers("logs.tail"),
            "Accept": "text/event-stream",
        }

    def _connect(self) -> None:
        url = self._state.url(self._config.url.rstrip("/"), self._query)
        with self._client.stream("GET", url, headers=self._headers()) as resp:
            with self._lock:
                self._response = resp
            try:
                if resp.status_code >= 400:
                    raise _status_error(
                        resp.status_code, resp.read(), resp.headers, self._api.secrets()
                    )
                if "event-stream" not in resp.headers.get("content-type", "").lower():
                    raise UnsupportedError(_NO_TAIL_TEXT)
                parser = SseParser()
                for text in resp.iter_text():
                    if self._stop.is_set():
                        return
                    for event, data in parser.feed(text):
                        self._state.handle(event, data)
            finally:
                with self._lock:
                    self._response = None

    def _run(self) -> None:
        while not self._stop.is_set():
            try:
                self._connect()
            except httpx.ReadTimeout:
                pass  # quiet stream: reconnect from the last seen timestamp
            except Exception as exc:  # noqa: BLE001 - reported to on_error
                if self._stop.is_set():
                    break
                error = _wrap(exc)
                self._state.error(error)
                if isinstance(error, _FATAL):
                    self._stop.set()
                    break
            self._stop.wait(self._delay)
        self._client.close()

    def stop(self) -> None:
        """Stop the tail and close its connection (idempotent)."""
        self._stop.set()
        with self._lock:
            resp = self._response
        if resp is not None:
            with contextlib.suppress(Exception):
                resp.close()
        if self._thread is not threading.current_thread():
            self._thread.join(timeout=5.0)


class AsyncSignozTailHandle:
    """A running async live tail (task on the current event loop); ``await stop()``."""

    def __init__(self, api: AsyncApi, query: str, state: _TailState, reconnect_delay_s: float):
        self._api = api
        self._query = query
        self._state = state
        self._delay = reconnect_delay_s
        config = api.config
        self._config = config
        self._client = httpx.AsyncClient(
            transport=api.http_transport,
            trust_env=False,
            verify=_verify(config),
            proxy=config.proxy,
            timeout=httpx.Timeout(
                connect=config.connect_timeout,
                read=TAIL_READ_TIMEOUT_S,
                write=config.request_timeout,
                pool=config.request_timeout,
            ),
        )
        self.stopped = False
        self._task = asyncio.get_running_loop().create_task(self._run())

    async def _connect(self) -> None:
        headers = {
            "User-Agent": user_agent(self._config.user_agent_suffix),
            **dict(self._config.headers),
            **self._api.auth_headers("logs.tail"),
            "Accept": "text/event-stream",
        }
        url = self._state.url(self._config.url.rstrip("/"), self._query)
        async with self._client.stream("GET", url, headers=headers) as resp:
            if resp.status_code >= 400:
                raise _status_error(
                    resp.status_code, await resp.aread(), resp.headers, self._api.secrets()
                )
            if "event-stream" not in resp.headers.get("content-type", "").lower():
                raise UnsupportedError(_NO_TAIL_TEXT)
            parser = SseParser()
            async for text in resp.aiter_text():
                for event, data in parser.feed(text):
                    self._state.handle(event, data)

    async def _run(self) -> None:
        try:
            while True:
                try:
                    await self._connect()
                except httpx.ReadTimeout:
                    pass
                except Exception as exc:  # noqa: BLE001 - reported to on_error
                    error = _wrap(exc)
                    self._state.error(error)
                    if isinstance(error, _FATAL):
                        self.stopped = True
                        return
                await asyncio.sleep(self._delay)
        finally:
            await self._client.aclose()

    async def stop(self) -> None:
        """Cancel the tail task and close its connection (idempotent)."""
        self.stopped = True
        if not self._task.done():
            self._task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await self._task


def _search_result(page: Any) -> LogSearchResult:
    return LogSearchResult(records=list(page.records), next_cursor=page.next_cursor)


def _labels(fields: Iterable[Any]) -> list[str]:
    return sorted({f.name for f in fields})


def _streams(values: Iterable[Any]) -> list[str]:
    return sorted({v for v in values if isinstance(v, str) and v})


def _since(start: datetime | None, now: datetime) -> int:
    return to_epoch_ms(start or now)


class SignozLogQueryClient(BaseLogQueryClient):
    """``LogQueryClient`` for SigNoz. ``query`` strings are SigNoz filter expressions; bare
    words are full-text searches of the log body. Get it from :attr:`SignozClient.logs`."""

    def __init__(
        self,
        query: SignozQueryClient,
        api: SyncApi,
        register: Callable[[SignozTailHandle], None] | None = None,
    ) -> None:
        self._query = query
        self._api = api
        self._register = register

    def search(
        self,
        query: str,
        *,
        start: datetime | None = None,
        end: datetime | None = None,
        limit: int = 100,
        order: SearchOrder = "desc",
        cursor: str | None = None,
    ) -> LogSearchResult:
        """Search logs (default window: the last hour, newest first, 100 per page)."""
        page = self._query.query_logs(
            query, start=start, end=end, limit=limit, order_by=order, cursor=cursor
        )
        return _search_result(page)

    def list_streams(self) -> list[str]:
        """Service names that sent logs (SigNoz has no streams; ``service.name`` is the
        equivalent), sorted."""
        return _streams(self._query.list_field_values("service.name", source="logs"))

    def list_labels(self) -> list[str]:
        """Distinct log field names (attributes, resource attributes, intrinsic fields), sorted."""
        return _labels(self._query.list_log_fields())

    def tail(
        self,
        query: str,
        on_record: Callable[[LogRecord], None],
        *,
        interval_s: float | timedelta = 1.0,
        start: datetime | None = None,
        on_error: Callable[[Exception], None] | None = None,
        reconnect_delay_s: float | timedelta | None = None,
    ) -> SignozTailHandle:
        """Live tail over ``GET /api/v3/logs/livetail`` (server-sent events).

        Calls ``on_record`` once per matching record from a background thread; reconnects after
        the stream ends (resuming at the last seen timestamp, de-duplicating by row id) after
        ``reconnect_delay_s`` (default ``interval_s``). Authentication, permission, unsupported
        and config errors stop the tail after ``on_error``. The client's ``close()`` stops it.
        """
        state = _TailState(
            _since(start, self._query._clock()),
            on_record,
            on_error,
            self._api.secrets,
        )
        delay = to_seconds(reconnect_delay_s if reconnect_delay_s is not None else interval_s)
        handle = SignozTailHandle(self._api, query, state, delay)
        if self._register is not None:
            self._register(handle)
        return handle


class AsyncSignozLogQueryClient(AsyncBaseLogQueryClient):
    """Async :class:`SignozLogQueryClient`; get it from :attr:`AsyncSignozClient.logs`."""

    def __init__(
        self,
        query: AsyncSignozQueryClient,
        api: AsyncApi,
        register: Callable[[AsyncSignozTailHandle], None] | None = None,
    ) -> None:
        self._query = query
        self._api = api
        self._register = register

    async def search(
        self,
        query: str,
        *,
        start: datetime | None = None,
        end: datetime | None = None,
        limit: int = 100,
        order: SearchOrder = "desc",
        cursor: str | None = None,
    ) -> LogSearchResult:
        """See :meth:`SignozLogQueryClient.search`."""
        page = await self._query.query_logs(
            query, start=start, end=end, limit=limit, order_by=order, cursor=cursor
        )
        return _search_result(page)

    async def list_streams(self) -> list[str]:
        """See :meth:`SignozLogQueryClient.list_streams`."""
        return _streams(await self._query.list_field_values("service.name", source="logs"))

    async def list_labels(self) -> list[str]:
        """See :meth:`SignozLogQueryClient.list_labels`."""
        return _labels(await self._query.list_log_fields())

    async def tail(
        self,
        query: str,
        on_record: Callable[[LogRecord], None],
        *,
        interval_s: float | timedelta = 1.0,
        start: datetime | None = None,
        on_error: Callable[[Exception], None] | None = None,
        reconnect_delay_s: float | timedelta | None = None,
    ) -> AsyncSignozTailHandle:
        """See :meth:`SignozLogQueryClient.tail` (runs as a task on the current loop)."""
        state = _TailState(
            _since(start, self._query._clock()),
            on_record,
            on_error,
            self._api.secrets,
        )
        delay = to_seconds(reconnect_delay_s if reconnect_delay_s is not None else interval_s)
        handle = AsyncSignozTailHandle(self._api, query, state, delay)
        if self._register is not None:
            self._register(handle)
        return handle
