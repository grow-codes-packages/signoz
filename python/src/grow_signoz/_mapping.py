"""Converts SigNoz rows (logs, spans, waterfall) into the normalized SDK models."""

from __future__ import annotations

import dataclasses
import json
import re
from collections.abc import Mapping
from datetime import datetime, timedelta, timezone
from typing import Any

from ._core import ValidationError
from ._logging import AttrValue, LogRecord, Severity, to_attr_value
from ._tracing import (
    Span,
    SpanEvent,
    SpanKindName,
    SpanLink,
    SpanStatus,
    StatusCodeName,
    Trace,
)

__all__ = [
    "SignozLogRecord",
    "SignozTrace",
    "log_row_to_record",
    "nanos_to_ms",
    "rfc3339_to_nanos",
    "span_row_to_span",
    "waterfall_span_to_span",
    "waterfall_to_trace",
]

_EPOCH = datetime(1970, 1, 1, tzinfo=timezone.utc)
_RFC3339 = re.compile(
    r"^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}:\d{2})$",
    re.IGNORECASE,
)
_HEX = re.compile(r"^[0-9a-f]+$")


@dataclasses.dataclass(frozen=True)
class SignozLogRecord(LogRecord):
    """A :class:`LogRecord` read from SigNoz, with the row id and the exact timestamp.

    ``timestamp`` has microsecond precision (Python ``datetime``); ``time_unix_nano`` keeps the
    exact epoch nanoseconds.
    """

    #: SigNoz row id (KSUID-like, assigned at ingest; used for ordering ties).
    id: str = ""
    #: Exact timestamp in epoch nanoseconds.
    time_unix_nano: int = 0
    #: Instrumentation scope name, when set.
    scope_name: str | None = None


@dataclasses.dataclass(frozen=True)
class SignozTrace(Trace):
    """A :class:`Trace` read from the SigNoz waterfall API."""

    #: ``False`` when SigNoz returned only a window of a very large trace.
    complete: bool = True
    #: Total spans SigNoz reports for the trace.
    total_spans: int = 0
    #: Service of the root span.
    root_service_name: str | None = None
    #: Name of the root span.
    root_name: str | None = None


def _obj(value: Any) -> Mapping[str, Any]:
    return value if isinstance(value, Mapping) else {}


def _str(value: Any) -> str | None:
    return value if isinstance(value, str) and value != "" else None


def _num(value: Any) -> float | int | None:
    if isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        return value
    if isinstance(value, str):
        try:
            return int(value.strip())
        except ValueError:
            try:
                return float(value.strip())
            except ValueError:
                return None
    return None


def _hex_id(value: Any, width: int) -> str | None:
    text = _str(value)
    if text is None:
        return None
    text = text.strip().lower()
    if not _HEX.match(text) or len(text) > width:
        return None
    text = text.rjust(width, "0")
    return None if set(text) == {"0"} else text


def rfc3339_to_nanos(text: str) -> int:
    """Parse an RFC 3339 timestamp with up to nanosecond precision into epoch nanoseconds.

    Raises :class:`ValidationError` when invalid.
    """
    match = _RFC3339.match(text.strip())
    if match is None:
        raise ValidationError("signoz: invalid timestamp")
    year, month, day, hour, minute, second, frac, tz = match.groups()
    offset = timezone.utc
    if tz.upper() != "Z":
        sign = 1 if tz[0] == "+" else -1
        offset = timezone(sign * timedelta(hours=int(tz[1:3]), minutes=int(tz[4:6])))
    try:
        moment = datetime(
            int(year), int(month), int(day), int(hour), int(minute), int(second), tzinfo=offset
        )
    except ValueError as exc:
        raise ValidationError("signoz: invalid timestamp") from exc
    delta = moment - _EPOCH
    seconds = delta.days * 86_400 + delta.seconds
    return seconds * 1_000_000_000 + int((frac or "").ljust(9, "0"))


def nanos_to_ms(nanos: int) -> int:
    """Epoch milliseconds (floor) of epoch nanoseconds."""
    return nanos // 1_000_000


def _nanos_to_datetime(nanos: int) -> datetime:
    return _EPOCH + timedelta(microseconds=nanos // 1000)


def _merge_typed(data: Mapping[str, Any], prefix: str) -> dict[str, AttrValue]:
    out: dict[str, AttrValue] = {}
    for key, value in data.items():
        if key.startswith(prefix):
            for k, v in _obj(value).items():
                if v is not None:
                    out[str(k)] = to_attr_value(v)
    return out


def _row_nanos(row: Mapping[str, Any], data: Mapping[str, Any]) -> int:
    envelope = _str(row.get("timestamp"))
    if envelope is not None:
        return rfc3339_to_nanos(envelope)
    raw = data.get("timestamp")
    if isinstance(raw, int) and not isinstance(raw, bool):
        return raw
    if isinstance(raw, str):
        if raw.isdigit():
            return int(raw)
        return rfc3339_to_nanos(raw)
    raise ValidationError("signoz: log row without timestamp")


def log_row_to_record(row: Any) -> SignozLogRecord:
    """Convert a raw log row (``{timestamp, data}`` from ``query_range`` or live tail).

    Severity comes from ``severity_number`` (fallback: ``severity_text``); attributes merge
    ``attributes_string|number|bool``; resource is ``resources_string``; empty trace/span ids
    are absent. A structured body is returned as a JSON string.
    """
    r = _obj(row)
    data = _obj(r.get("data"))
    nanos = _row_nanos(r, data)
    number = _num(data.get("severity_number"))
    text = _str(data.get("severity_text"))
    severity = Severity.INFO
    if number is not None and 1 <= number <= 24:
        severity = Severity.parse(int(number))
    elif text is not None:
        try:
            severity = Severity.parse(text)
        except ValidationError:
            severity = Severity.INFO
    body = data.get("body")
    if not isinstance(body, str):
        body = json.dumps(body if body is not None else "", separators=(",", ":"))
    trace_id = _hex_id(data.get("trace_id"), 32)
    span_id = _hex_id(data.get("span_id"), 16) if trace_id else None
    flags = _num(data.get("trace_flags")) if trace_id else None
    return SignozLogRecord(
        body=body,
        severity=severity,
        severity_text=text or severity.name,
        timestamp=_nanos_to_datetime(nanos),
        attributes=_merge_typed(data, "attributes_"),
        resource=_merge_typed(data, "resources_"),
        trace_id=trace_id,
        span_id=span_id,
        trace_flags=int(flags) if flags is not None else None,
        id=_str(data.get("id")) or "",
        time_unix_nano=nanos,
        scope_name=_str(data.get("scope_name")),
    )


_KINDS: dict[str, SpanKindName] = {
    "server": "server",
    "client": "client",
    "producer": "producer",
    "consumer": "consumer",
    "internal": "internal",
    "unspecified": "unspecified",
}
_KIND_BY_NUMBER: tuple[SpanKindName, ...] = (
    "unspecified",
    "internal",
    "server",
    "client",
    "producer",
    "consumer",
)


def _span_kind(data: Mapping[str, Any]) -> SpanKindName:
    text = (_str(data.get("kind_string")) or "").strip().lower()
    if text in _KINDS:
        return _KINDS[text]
    n = _num(data.get("kind"))
    if n is not None and 0 <= int(n) < len(_KIND_BY_NUMBER):
        return _KIND_BY_NUMBER[int(n)]
    return "unspecified"


def _span_status(data: Mapping[str, Any]) -> SpanStatus:
    code = _num(data.get("status_code"))
    text = (_str(data.get("status_code_string")) or "").lower()
    name: StatusCodeName = "unset"
    if code == 2 or text == "error":
        name = "error"
    elif code == 1 or text == "ok":
        name = "ok"
    return SpanStatus(name, _str(data.get("status_message")))


def _nanos_value_to_micros(value: Any) -> int:
    n = _num(value)
    return 0 if n is None else int(n) // 1000


def _events(data: Mapping[str, Any]) -> list[SpanEvent]:
    out: list[SpanEvent] = []
    for item in data.get("events") or []:
        event = _obj(item)
        attributes = event.get("attributeMap")
        if not isinstance(attributes, Mapping):
            attributes = event.get("attributes")
        out.append(
            SpanEvent(
                _str(event.get("name")) or "",
                _nanos_value_to_micros(event.get("timeUnixNano")),
                {str(k): to_attr_value(v) for k, v in _obj(attributes).items() if v is not None},
            )
        )
    return out


def _attrs(value: Any) -> dict[str, AttrValue]:
    return {str(k): to_attr_value(v) for k, v in _obj(value).items() if v is not None}


def _span(data: Mapping[str, Any], start_micros: int, links: list[SpanLink]) -> Span | None:
    trace_id = _hex_id(data.get("trace_id"), 32)
    span_id = _hex_id(data.get("span_id"), 16)
    if trace_id is None or span_id is None:
        return None
    duration = _nanos_value_to_micros(data.get("duration_nano"))
    resource = _obj(data.get("resource"))
    return Span(
        trace_id=trace_id,
        span_id=span_id,
        parent_span_id=_hex_id(data.get("parent_span_id"), 16),
        name=_str(data.get("name")) or "",
        kind=_span_kind(data),
        service_name=_str(resource.get("service.name"))
        or _str(data.get("service.name"))
        or "unknown_service",
        start_micros=start_micros,
        end_micros=start_micros + duration,
        duration_micros=duration,
        status=_span_status(data),
        attributes=_attrs(data.get("attributes")),
        events=_events(data),
        links=links,
    )


def _link(trace_id: Any, span_id: Any) -> SpanLink | None:
    t = _hex_id(trace_id, 32)
    s = _hex_id(span_id, 16)
    return None if t is None or s is None else SpanLink(t, s, {})


def span_row_to_span(row: Any) -> Span | None:
    """Convert a raw span row (``requestType: raw``, ``signal: traces``); ``None`` without ids.

    The parent reference SigNoz mirrors into ``links`` is dropped; SigNoz does not store link
    attributes, so they are always ``{}``.
    """
    r = _obj(row)
    data = _obj(r.get("data"))
    ts = _str(r.get("timestamp")) or _str(data.get("timestamp"))
    start = rfc3339_to_nanos(ts) // 1000 if ts is not None else 0
    parent = _hex_id(data.get("parent_span_id"), 16)
    links: list[SpanLink] = []
    for item in data.get("links") or []:
        entry = _obj(item)
        candidate = _link(entry.get("traceId"), entry.get("spanId"))
        if candidate is None or candidate.span_id == parent:
            continue
        links.append(candidate)
    return _span(data, start, links)


def waterfall_span_to_span(value: Any) -> Span | None:
    """Convert one waterfall span; ``None`` without ids.

    ``time_unix`` is in milliseconds, so ``start_micros`` has millisecond precision. Links come
    from ``references`` with ``refType: FOLLOWS_FROM``.
    """
    data = _obj(value)
    start = round((_num(data.get("time_unix")) or 0) * 1000)
    links: list[SpanLink] = []
    for item in data.get("references") or []:
        ref = _obj(item)
        if (_str(ref.get("refType")) or "").upper() != "FOLLOWS_FROM":
            continue
        candidate = _link(ref.get("traceId"), ref.get("spanId"))
        if candidate is not None:
            links.append(candidate)
    return _span(data, int(start), links)


def waterfall_to_trace(trace_id: str, value: Any) -> SignozTrace:
    """Convert the ``data`` of ``/api/v4/traces/{id}/waterfall``."""
    data = _obj(value)
    spans = [s for s in (waterfall_span_to_span(v) for v in data.get("spans") or []) if s]
    total = _num(data.get("totalSpansCount"))
    total_spans = int(total) if total is not None else len(spans)
    return SignozTrace(
        trace_id=trace_id,
        spans=spans,
        complete=data.get("hasMore") is not True and total_spans <= len(spans),
        total_spans=total_spans,
        root_service_name=_str(data.get("rootServiceName")),
        root_name=_str(data.get("rootServiceEntryPoint")),
    )
