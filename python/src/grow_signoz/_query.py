"""SigNoz query API: logs and spans (``/api/v5/query_range``), traces (waterfall), services,
operations, dependencies, log fields and aggregations — sync and async clients."""

from __future__ import annotations

import base64
import binascii
import dataclasses
import json
from collections.abc import Callable, Mapping, Sequence
from datetime import datetime, timedelta, timezone
from typing import Any, Literal

from ._api import ApiResult, AsyncApi, Call, Op, SyncApi
from ._core import CancelToken, ValidationError
from ._mapping import (
    SignozLogRecord,
    SignozTrace,
    log_row_to_record,
    nanos_to_ms,
    span_row_to_span,
    waterfall_to_trace,
)
from ._tracing import (
    AsyncBaseTraceQueryClient,
    BaseTraceQueryClient,
    DependencyLink,
    Span,
    TraceSearchQuery,
    TraceSummary,
    normalize_trace_id,
)
from .aggregate import (
    DEFAULT_QUERY_WINDOW_S,
    AggregateBuilder,
    AsyncAggregateBuilder,
    SignozSignal,
    _AggregateSpec,
    query_range_op,
    to_epoch_ms,
)
from .filter import FilterBuilder, and_filters, quote_filter_string

__all__ = [
    "DEFAULT_LOG_LIMIT",
    "DEFAULT_TRACE_SEARCH_LIMIT",
    "MAX_QUERY_LIMIT",
    "AsyncSignozQueryClient",
    "LogField",
    "LogQueryResult",
    "SignozQueryClient",
    "SortOrder",
    "SpanQueryResult",
    "resolve_window",
]

#: Default page size of ``query_logs`` / ``query_traces``.
DEFAULT_LOG_LIMIT = 100
#: Default number of traces returned by ``search_traces``.
DEFAULT_TRACE_SEARCH_LIMIT = 20
#: Largest row limit the SigNoz query API accepts.
MAX_QUERY_LIMIT = 10_000

#: Sort order of raw queries.
SortOrder = Literal["asc", "desc"]

_EPOCH = datetime(1970, 1, 1, tzinfo=timezone.utc)


@dataclasses.dataclass(frozen=True)
class LogQueryResult:
    """One page of :meth:`SignozQueryClient.query_logs`."""

    records: list[SignozLogRecord]
    #: Set when the page is full; pass it back as ``cursor`` for the next page.
    next_cursor: str | None = None
    #: Server warnings, e.g. unknown fields in the filter (SigNoz answers 200 with no rows).
    warnings: list[str] = dataclasses.field(default_factory=list)


@dataclasses.dataclass(frozen=True)
class SpanQueryResult:
    """Result of :meth:`SignozQueryClient.query_traces`: one :class:`Span` per matching span."""

    spans: list[Span]
    warnings: list[str] = dataclasses.field(default_factory=list)


@dataclasses.dataclass(frozen=True)
class LogField:
    """A log field known to SigNoz (one entry per name and context)."""

    name: str
    #: ``log``, ``resource``, ``attribute`` or ``scope``.
    context: str
    #: ``string``, ``number`` or ``bool``.
    data_type: str
    description: str | None = None


# --------------------------------------------------------------------------- helpers


def _ms_to_datetime(ms: int) -> datetime:
    return _EPOCH + timedelta(milliseconds=ms)


def resolve_window(
    start: datetime | None, end: datetime | None, now: datetime | None = None
) -> tuple[int, int]:
    """``(start_ms, end_ms)``; defaults: ``end`` = now, ``start`` = one hour before ``end``.

    Raises :class:`ValidationError` when ``start`` is not before ``end``.
    """
    end_dt = end or now or datetime.now(timezone.utc)
    start_dt = start or (end_dt - timedelta(seconds=DEFAULT_QUERY_WINDOW_S))
    start_ms, end_ms = to_epoch_ms(start_dt), to_epoch_ms(end_dt)
    if start_ms >= end_ms:
        raise ValidationError("query: start must be before end")
    return start_ms, end_ms


def _check_limit(limit: int | None, default: int, operation: str) -> int:
    if limit is None or limit == 0:
        return default
    if isinstance(limit, bool) or not isinstance(limit, int) or not 0 < limit <= MAX_QUERY_LIMIT:
        raise ValidationError(
            f"{operation}: limit must be an integer between 1 and {MAX_QUERY_LIMIT}"
        )
    return limit


def _check_order(order: str | None, operation: str) -> SortOrder:
    if order is None or order == "":
        return "desc"
    if order not in ("asc", "desc"):
        raise ValidationError(f'{operation}: order_by must be "asc" or "desc"')
    return order  # type: ignore[return-value]


def _filter_text(value: str | FilterBuilder | None) -> str:
    if value is None:
        return ""
    return value.build() if isinstance(value, FilterBuilder) else value.strip()


def _ns_string(ms: int) -> str:
    return str(ms * 1_000_000)


def _obj(value: Any) -> Mapping[str, Any]:
    return value if isinstance(value, Mapping) else {}


def _arr(value: Any) -> list[Any]:
    return list(value) if isinstance(value, (list, tuple)) else []


def _int(value: Any) -> int:
    if isinstance(value, bool):
        return 0
    if isinstance(value, (int, float)):
        return int(value)
    if isinstance(value, str):
        try:
            return int(float(value))
        except ValueError:
            return 0
    return 0


def _encode_cursor(cursor: Mapping[str, Any]) -> str:
    raw = json.dumps(cursor, separators=(",", ":")).encode("utf-8")
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def _decode_cursor(text: str, operation: str) -> dict[str, Any]:
    try:
        padded = text + "=" * (-len(text) % 4)
        cursor = json.loads(base64.urlsafe_b64decode(padded.encode("ascii")))
    except (ValueError, binascii.Error, UnicodeError):
        cursor = None
    if (
        isinstance(cursor, dict)
        and cursor.get("v") == 1
        and cursor.get("o") in ("asc", "desc")
        and all(isinstance(cursor.get(k), int) for k in ("s", "e", "m"))
        and isinstance(cursor.get("x", []), list)
        and all(isinstance(i, str) for i in cursor.get("x", []))
    ):
        return cursor
    raise ValidationError(f"{operation}: invalid cursor")


def _raw_body(
    signal: SignozSignal, spec: Mapping[str, Any], window: tuple[int, int], request_type: str
) -> dict[str, Any]:
    return {
        "start": window[0],
        "end": window[1],
        "requestType": request_type,
        "compositeQuery": {
            "queries": [{"type": "builder_query", "spec": {"name": "A", "signal": signal, **spec}}]
        },
    }


def _rows(res: ApiResult) -> list[Any]:
    results = _arr(_obj(_obj(res.data).get("data")).get("results"))
    return _arr(_obj(results[0]).get("rows")) if results else []


# --------------------------------------------------------------------------- operations


def query_logs_op(
    *,
    filter: str | FilterBuilder | None,  # noqa: A002 - public parameter name of the SPEC
    start: datetime | None,
    end: datetime | None,
    limit: int | None,
    order_by: str | None,
    cursor: str | None,
    select_fields: Sequence[str] | None,
    now: datetime,
) -> Op[LogQueryResult]:
    """Raw logs query with the SDK's own cursor (see :meth:`SignozQueryClient.query_logs`)."""
    operation = "query_logs"
    size = _check_limit(limit, DEFAULT_LOG_LIMIT, operation)
    order = _check_order(order_by, operation)
    expression = _filter_text(filter)
    prev: dict[str, Any] | None = None
    if cursor:
        prev = _decode_cursor(cursor, operation)
        order = prev["o"]
        window = (prev["s"], prev["m"] + 1) if order == "desc" else (prev["m"], prev["e"])
        excluded: list[str] = prev.get("x") or []
        if excluded:
            expression = and_filters(
                expression,
                "id NOT IN (" + ", ".join(quote_filter_string(i) for i in excluded) + ")",
            )
    else:
        window = resolve_window(start, end, now)
    spec: dict[str, Any] = {
        "order": [
            {"key": {"name": "timestamp"}, "direction": order},
            {"key": {"name": "id"}, "direction": order},
        ],
        "limit": size,
    }
    if expression:
        spec["filter"] = {"expression": expression}
    if select_fields:
        spec["selectFields"] = [{"name": f} for f in select_fields]
    res: ApiResult = yield from query_range_op(_raw_body("logs", spec, window, "raw"), operation)
    records = [log_row_to_record(r) for r in _rows(res)]
    next_cursor = None
    if records and len(records) >= size:
        boundary = nanos_to_ms(records[-1].time_unix_nano)
        ids: list[str] = []
        if prev is not None and prev["m"] == boundary:
            ids.extend(prev.get("x") or [])
        for record in records:
            if record.id and nanos_to_ms(record.time_unix_nano) == boundary:
                ids.append(record.id)
        next_cursor = _encode_cursor(
            {
                "v": 1,
                "s": prev["s"] if prev else window[0],
                "e": prev["e"] if prev else window[1],
                "o": order,
                "m": boundary,
                "x": list(dict.fromkeys(ids)),
            }
        )
    return LogQueryResult(records, next_cursor, list(res.warnings))


def query_traces_op(
    *,
    service: str | None,
    operation: str | None,
    filter: str | FilterBuilder | None,  # noqa: A002
    start: datetime | None,
    end: datetime | None,
    limit: int | None,
    offset: int,
    order_by: str | None,
    now: datetime,
    window: tuple[int, int] | None = None,
) -> Op[SpanQueryResult]:
    """Raw spans query (see :meth:`SignozQueryClient.query_traces`)."""
    op = "query_traces"
    size = _check_limit(limit, DEFAULT_LOG_LIMIT, op)
    order = _check_order(order_by, op)
    if isinstance(offset, bool) or not isinstance(offset, int) or offset < 0:
        raise ValidationError(f"{op}: offset must be >= 0")
    builder = FilterBuilder()
    if service:
        builder = builder.eq("service.name", service)
    if operation:
        builder = builder.eq("name", operation)
    expression = and_filters(builder, _filter_text(filter))
    spec: dict[str, Any] = {
        "order": [{"key": {"name": "timestamp"}, "direction": order}],
        "limit": size,
    }
    if offset:
        spec["offset"] = offset
    if expression:
        spec["filter"] = {"expression": expression}
    span_window = window or resolve_window(start, end, now)
    res: ApiResult = yield from query_range_op(_raw_body("traces", spec, span_window, "raw"), op)
    spans = [s for s in (span_row_to_span(r) for r in _rows(res)) if s is not None]
    return SpanQueryResult(spans, list(res.warnings))


def _waterfall_call(trace_id: str, operation: str) -> Call:
    return Call(
        "POST", f"/api/v4/traces/{trace_id}/waterfall", operation, json={}, null_on_404=True
    )


def get_trace_op(trace_id: str, now: datetime) -> Op[SignozTrace | None]:
    """Waterfall lookup; a windowed (incomplete) trace is completed with raw span queries."""
    tid = normalize_trace_id(trace_id)
    res: ApiResult | None = yield _waterfall_call(tid, "get_trace")
    if res is None:
        return None
    trace = waterfall_to_trace(tid, res.data)
    if not trace.spans:
        return None
    if trace.complete:
        return trace
    data = _obj(res.data)
    window = (_int(data.get("startTimestampMillis")), _int(data.get("endTimestampMillis")) + 1)
    spans: list[Span] = []
    while True:
        page = yield from query_traces_op(
            service=None,
            operation=None,
            filter=f"trace_id = {quote_filter_string(tid)}",
            start=None,
            end=None,
            limit=MAX_QUERY_LIMIT,
            offset=len(spans),
            order_by="asc",
            now=now,
            window=window,
        )
        spans.extend(page.spans)
        if len(page.spans) < MAX_QUERY_LIMIT:
            break
    return dataclasses.replace(trace, spans=spans, complete=len(spans) >= trace.total_spans)


def search_traces_op(
    query: TraceSearchQuery,
    filter: str | FilterBuilder | None,  # noqa: A002
    now: datetime,
) -> Op[list[TraceSummary]]:
    """Trace search (``requestType: trace``) plus a waterfall fill per hit, 4 at a time."""
    op = "search_traces"
    size = _check_limit(query.limit, DEFAULT_TRACE_SEARCH_LIMIT, op)
    builder = FilterBuilder()
    if query.service:
        builder = builder.eq("service.name", query.service)
    if query.operation:
        builder = builder.eq("name", query.operation)
    for key in sorted(query.tags):
        builder = builder.eq(key, query.tags[key])
    if query.min_duration_micros:
        builder = builder.gte("duration_nano", int(query.min_duration_micros) * 1000)
    if query.max_duration_micros:
        builder = builder.lte("duration_nano", int(query.max_duration_micros) * 1000)
    expression = and_filters(builder, _filter_text(filter))
    spec: dict[str, Any] = {
        "order": [{"key": {"name": "timestamp"}, "direction": "desc"}],
        "limit": size,
    }
    if expression:
        spec["filter"] = {"expression": expression}
    window = resolve_window(query.start, query.end, now)
    res: ApiResult = yield from query_range_op(_raw_body("traces", spec, window, "trace"), op)
    hits: list[TraceSummary] = []
    for row in _rows(res):
        data = _obj(_obj(row).get("data"))
        try:
            tid = normalize_trace_id(str(data.get("trace_id") or ""))
        except ValidationError:
            continue
        service = data.get("service.name")
        name = data.get("name")
        hits.append(
            TraceSummary(
                trace_id=tid,
                start_micros=0,
                duration_micros=_int(data.get("duration_nano")) // 1000,
                span_count=_int(data.get("span_count")),
                root_service_name=service if isinstance(service, str) and service else None,
                root_name=name if isinstance(name, str) and name else None,
            )
        )
    if not hits:
        return []
    waterfalls: list[ApiResult | None] = yield [_waterfall_call(h.trace_id, op) for h in hits]
    out: list[TraceSummary] = []
    for hit, wf in zip(hits, waterfalls, strict=True):
        if wf is None:
            out.append(hit)
            continue
        data = _obj(wf.data)
        trace = waterfall_to_trace(hit.trace_id, data)
        start_ms = _int(data.get("startTimestampMillis"))
        start_micros = (
            start_ms * 1000 if start_ms else (trace.spans[0].start_micros if trace.spans else 0)
        )
        out.append(
            dataclasses.replace(
                hit,
                start_micros=start_micros,
                span_count=trace.total_spans or hit.span_count,
                root_service_name=trace.root_service_name or hit.root_service_name,
                root_name=trace.root_name or hit.root_name,
            )
        )
    return out


def _group_names_op(
    field: str, filter_expr: str, start: datetime | None, end: datetime | None, now: datetime
) -> Op[list[str]]:
    start_ms, end_ms = resolve_window(start, end, now)
    spec = (
        _AggregateSpec("traces")
        .count("count")
        .group_by(field)
        .limit(MAX_QUERY_LIMIT)
        .between(_ms_to_datetime(start_ms), _ms_to_datetime(end_ms))
    )
    if filter_expr:
        spec.filter(filter_expr)
    result = yield from spec.scalar_op()
    names = {str(v) for v in (r.groups.get(field) for r in result.rows) if v not in (None, "")}
    return sorted(names)


def list_services_op(start: datetime | None, end: datetime | None, now: datetime) -> Op[list[str]]:
    """Every service with spans in the window (``count()`` grouped by ``service.name``)."""
    return _group_names_op("service.name", "", start, end, now)


def list_operations_op(
    service: str, start: datetime | None, end: datetime | None, now: datetime
) -> Op[list[str]]:
    """Span names of ``service`` in the window."""
    if not isinstance(service, str) or not service.strip():
        raise ValidationError("list_operations: service must not be empty")
    return _group_names_op(
        "name", FilterBuilder().eq("service.name", service).build(), start, end, now
    )


def get_dependencies_op(
    start: datetime | None, end: datetime | None, now: datetime
) -> Op[list[DependencyLink]]:
    """Dependency edges (``POST /api/v1/dependency_graph``, bare JSON array)."""
    start_ms, end_ms = resolve_window(start, end, now)
    res: ApiResult = yield Call(
        "POST",
        "/api/v1/dependency_graph",
        "get_dependencies",
        json={"start": _ns_string(start_ms), "end": _ns_string(end_ms), "tags": []},
        envelope=False,
    )
    out = [
        DependencyLink(str(e["parent"]), str(e["child"]), _int(e.get("callCount")))
        for e in (_obj(x) for x in _arr(res.data))
        if isinstance(e.get("parent"), str) and isinstance(e.get("child"), str)
    ]
    return sorted(out, key=lambda d: (d.parent, d.child))


def list_log_fields_op(search: str | None, limit: int | None) -> Op[list[LogField]]:
    """Log fields (``GET /api/v1/fields/keys?signal=logs``)."""
    res: ApiResult = yield Call(
        "GET",
        "/api/v1/fields/keys",
        "list_log_fields",
        params={"signal": "logs", "searchText": search or None, "limit": limit or None},
    )
    out: list[LogField] = []
    for name, entries in _obj(_obj(res.data).get("keys")).items():
        for entry in (_obj(e) for e in _arr(entries)):
            entry_name = entry.get("name")
            description = entry.get("description")
            out.append(
                LogField(
                    name=entry_name if isinstance(entry_name, str) and entry_name else str(name),
                    context=str(entry.get("fieldContext") or ""),
                    data_type=str(entry.get("fieldDataType") or ""),
                    description=description or None if isinstance(description, str) else None,
                )
            )
    return sorted(out, key=lambda f: (f.name, f.context))


def list_field_values_op(
    name: str, source: SignozSignal, search: str | None
) -> Op[list[str | int | float | bool]]:
    """Distinct values of a field (``GET /api/v1/fields/values``), sorted."""
    if source not in ("logs", "traces"):
        raise ValidationError('list_field_values: source must be "logs" or "traces"')
    res: ApiResult = yield Call(
        "GET",
        "/api/v1/fields/values",
        "list_field_values",
        params={"signal": source, "name": name, "searchText": search or None},
    )
    values = _obj(_obj(res.data).get("values"))
    seen: dict[Any, str | int | float | bool] = {}
    for key in ("stringValues", "numberValues", "boolValues"):
        for value in _arr(values.get(key)):
            if isinstance(value, (str, int, float, bool)):
                seen.setdefault((type(value).__name__, value), value)
    return sorted(seen.values(), key=str)


# --------------------------------------------------------------------------- clients


class SignozQueryClient(BaseTraceQueryClient):
    """Query client for SigNoz (``SIGNOZ-API-KEY`` auth); implements ``TraceQueryClient``.

    Logs and spans via ``/api/v5/query_range``, traces via the waterfall API, services and
    operations via v5 aggregations, dependencies via ``/api/v1/dependency_graph``, log fields
    via ``/api/v1/fields/keys``. Get it from :attr:`SignozClient.query`. Every method accepts
    ``cancel=`` (a :class:`CancelToken`).
    """

    def __init__(self, api: SyncApi, clock: Callable[[], datetime] | None = None) -> None:
        self._api = api
        self._clock = clock or (lambda: datetime.now(timezone.utc))

    def query_range(
        self,
        body: Mapping[str, Any],
        *,
        operation: str = "query_range",
        cancel: CancelToken | None = None,
    ) -> ApiResult:
        """Post a raw ``/api/v5/query_range`` body; returns ``data`` plus warnings."""
        return self._api.run(query_range_op(body, operation), cancel)

    def query_logs(
        self,
        filter: str | FilterBuilder | None = None,  # noqa: A002 - SPEC name
        *,
        start: datetime | None = None,
        end: datetime | None = None,
        limit: int | None = None,
        order_by: SortOrder = "desc",
        cursor: str | None = None,
        select_fields: Sequence[str] | None = None,
        cancel: CancelToken | None = None,
    ) -> LogQueryResult:
        """Raw log query: one page of records ordered by timestamp, ties by id.

        ``filter`` is a SigNoz filter expression or :class:`FilterBuilder` (empty: every log;
        bare words search the body). Window default: the last hour (``end`` exclusive). ``limit``
        1..10000 (``None``/0 → 100). Paging uses the SDK's own cursor, never the server's
        ``nextCursor`` (which skips rows in the millisecond before it): the next page repeats the
        boundary millisecond and excludes the ids already returned there.
        """
        return self._api.run(
            query_logs_op(
                filter=filter,
                start=start,
                end=end,
                limit=limit,
                order_by=order_by,
                cursor=cursor,
                select_fields=select_fields,
                now=self._clock(),
            ),
            cancel,
        )

    def query_traces(
        self,
        *,
        service: str | None = None,
        operation: str | None = None,
        filter: str | FilterBuilder | None = None,  # noqa: A002
        start: datetime | None = None,
        end: datetime | None = None,
        limit: int | None = None,
        offset: int = 0,
        order_by: SortOrder = "desc",
        cancel: CancelToken | None = None,
    ) -> SpanQueryResult:
        """Raw span query: one :class:`Span` per matching span (offset paging)."""
        return self._api.run(
            query_traces_op(
                service=service,
                operation=operation,
                filter=filter,
                start=start,
                end=end,
                limit=limit,
                offset=offset,
                order_by=order_by,
                now=self._clock(),
            ),
            cancel,
        )

    def get_trace(self, trace_id: str, *, cancel: CancelToken | None = None) -> SignozTrace | None:
        """Full trace via ``POST /api/v4/traces/{id}/waterfall``; ``None`` when unknown.

        Span start times have millisecond precision. A trace larger than the server's waterfall
        window is completed with raw span queries.
        """
        return self._api.run(get_trace_op(trace_id, self._clock()), cancel)

    def search_traces(
        self,
        query: TraceSearchQuery | None = None,
        *,
        filter: str | FilterBuilder | None = None,  # noqa: A002
        cancel: CancelToken | None = None,
    ) -> list[TraceSummary]:
        """Trace search, newest first (default 20): a trace matches when any span matches.

        ``start_micros``, ``span_count`` (all spans), root service and name come from each
        hit's waterfall (millisecond precision, 4 requests at a time); ``duration_micros`` is
        the root span's.
        """
        return self._api.run(
            search_traces_op(query or TraceSearchQuery(), filter, self._clock()), cancel
        )

    def list_services(
        self,
        *,
        start: datetime | None = None,
        end: datetime | None = None,
        cancel: CancelToken | None = None,
    ) -> list[str]:
        """Every service with spans in the window (default: the last hour), sorted."""
        return self._api.run(list_services_op(start, end, self._clock()), cancel)

    def list_operations(
        self,
        service: str,
        *,
        start: datetime | None = None,
        end: datetime | None = None,
        cancel: CancelToken | None = None,
    ) -> list[str]:
        """Span names of ``service`` in the window (default: the last hour), sorted."""
        return self._api.run(list_operations_op(service, start, end, self._clock()), cancel)

    def get_dependencies(
        self,
        *,
        start: datetime | None = None,
        end: datetime | None = None,
        cancel: CancelToken | None = None,
    ) -> list[DependencyLink]:
        """Service dependency edges in the window (default: the last hour), sorted.

        Edges come from client→server spans and from client spans with ``db.system`` or
        ``messaging.system``.
        """
        return self._api.run(get_dependencies_op(start, end, self._clock()), cancel)

    def list_log_fields(
        self,
        *,
        search: str | None = None,
        limit: int | None = None,
        cancel: CancelToken | None = None,
    ) -> list[LogField]:
        """Log fields, one entry per name and context, sorted by name then context."""
        return self._api.run(list_log_fields_op(search, limit), cancel)

    def list_field_values(
        self,
        name: str,
        *,
        source: SignozSignal = "logs",
        search: str | None = None,
        cancel: CancelToken | None = None,
    ) -> list[str | int | float | bool]:
        """Distinct values of a field for ``logs`` or ``traces``, sorted."""
        return self._api.run(list_field_values_op(name, source, search), cancel)

    def aggregate(self, signal: SignozSignal) -> AggregateBuilder:
        """Start an aggregation (count / percentiles / …) over ``logs`` or ``traces``."""
        return AggregateBuilder(signal, self._api.run, self._clock)


class AsyncSignozQueryClient(AsyncBaseTraceQueryClient):
    """Async :class:`SignozQueryClient`; get it from :attr:`AsyncSignozClient.query`."""

    def __init__(self, api: AsyncApi, clock: Callable[[], datetime] | None = None) -> None:
        self._api = api
        self._clock = clock or (lambda: datetime.now(timezone.utc))

    async def query_range(
        self, body: Mapping[str, Any], *, operation: str = "query_range"
    ) -> ApiResult:
        """Post a raw ``/api/v5/query_range`` body; returns ``data`` plus warnings."""
        return await self._api.run(query_range_op(body, operation))

    async def query_logs(
        self,
        filter: str | FilterBuilder | None = None,  # noqa: A002
        *,
        start: datetime | None = None,
        end: datetime | None = None,
        limit: int | None = None,
        order_by: SortOrder = "desc",
        cursor: str | None = None,
        select_fields: Sequence[str] | None = None,
    ) -> LogQueryResult:
        """See :meth:`SignozQueryClient.query_logs`."""
        return await self._api.run(
            query_logs_op(
                filter=filter,
                start=start,
                end=end,
                limit=limit,
                order_by=order_by,
                cursor=cursor,
                select_fields=select_fields,
                now=self._clock(),
            )
        )

    async def query_traces(
        self,
        *,
        service: str | None = None,
        operation: str | None = None,
        filter: str | FilterBuilder | None = None,  # noqa: A002
        start: datetime | None = None,
        end: datetime | None = None,
        limit: int | None = None,
        offset: int = 0,
        order_by: SortOrder = "desc",
    ) -> SpanQueryResult:
        """See :meth:`SignozQueryClient.query_traces`."""
        return await self._api.run(
            query_traces_op(
                service=service,
                operation=operation,
                filter=filter,
                start=start,
                end=end,
                limit=limit,
                offset=offset,
                order_by=order_by,
                now=self._clock(),
            )
        )

    async def get_trace(self, trace_id: str) -> SignozTrace | None:
        """See :meth:`SignozQueryClient.get_trace`."""
        return await self._api.run(get_trace_op(trace_id, self._clock()))

    async def search_traces(
        self,
        query: TraceSearchQuery | None = None,
        *,
        filter: str | FilterBuilder | None = None,  # noqa: A002
    ) -> list[TraceSummary]:
        """See :meth:`SignozQueryClient.search_traces`."""
        op = search_traces_op(query or TraceSearchQuery(), filter, self._clock())
        return await self._api.run(op)

    async def list_services(
        self, *, start: datetime | None = None, end: datetime | None = None
    ) -> list[str]:
        """See :meth:`SignozQueryClient.list_services`."""
        return await self._api.run(list_services_op(start, end, self._clock()))

    async def list_operations(
        self, service: str, *, start: datetime | None = None, end: datetime | None = None
    ) -> list[str]:
        """See :meth:`SignozQueryClient.list_operations`."""
        return await self._api.run(list_operations_op(service, start, end, self._clock()))

    async def get_dependencies(
        self, *, start: datetime | None = None, end: datetime | None = None
    ) -> list[DependencyLink]:
        """See :meth:`SignozQueryClient.get_dependencies`."""
        return await self._api.run(get_dependencies_op(start, end, self._clock()))

    async def list_log_fields(
        self, *, search: str | None = None, limit: int | None = None
    ) -> list[LogField]:
        """See :meth:`SignozQueryClient.list_log_fields`."""
        return await self._api.run(list_log_fields_op(search, limit))

    async def list_field_values(
        self, name: str, *, source: SignozSignal = "logs", search: str | None = None
    ) -> list[str | int | float | bool]:
        """See :meth:`SignozQueryClient.list_field_values`."""
        return await self._api.run(list_field_values_op(name, source, search))

    def aggregate(self, signal: SignozSignal) -> AsyncAggregateBuilder:
        """Start an async aggregation over ``logs`` or ``traces``."""
        return AsyncAggregateBuilder(signal, self._api.run, self._clock)
