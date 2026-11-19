"""``aggregate(...)`` builder over the v5 ``query_range`` API (scalar and time series)."""

from __future__ import annotations

import dataclasses
import math
import re
from collections.abc import Awaitable, Callable, Mapping
from datetime import datetime, timedelta, timezone
from typing import Any, Literal, TypeAlias, TypeVar

from ._api import ApiResult, Call, Op
from ._core import ValidationError
from .filter import FilterBuilder

__all__ = [
    "DEFAULT_QUERY_WINDOW_S",
    "AggregateBuilder",
    "AggregatePoint",
    "AggregateResult",
    "AggregateRow",
    "AggregateSeries",
    "Aggregation",
    "AsyncAggregateBuilder",
    "GroupByField",
    "GroupValue",
    "SignozSignal",
    "query_range_op",
    "to_epoch_ms",
]

#: Telemetry signal of a SigNoz query.
SignozSignal = Literal["logs", "traces"]
#: A group value as returned by SigNoz.
GroupValue: TypeAlias = "str | int | float | bool | None"

#: Default query window when ``start`` is omitted: one hour before ``end``.
DEFAULT_QUERY_WINDOW_S = 3600.0
_FIELD = re.compile(r"^[A-Za-z0-9_.:-]+$")
_PERCENTILES = (5, 10, 20, 25, 50, 75, 90, 95, 99)
_EPOCH = datetime(1970, 1, 1, tzinfo=timezone.utc)

_B = TypeVar("_B", bound="_AggregateSpec")


def to_epoch_ms(value: datetime) -> int:
    """Epoch milliseconds of a datetime (naive values are UTC)."""
    if value.tzinfo is None:
        value = value.replace(tzinfo=timezone.utc)
    delta = value - _EPOCH
    return (delta.days * 86_400 + delta.seconds) * 1000 + delta.microseconds // 1000


def query_range_op(body: Mapping[str, Any], operation: str) -> Op[ApiResult]:
    """Operation posting one ``/api/v5/query_range`` body; returns ``data`` plus warnings."""
    result: ApiResult = yield Call("POST", "/api/v5/query_range", operation, json=dict(body))
    return result


@dataclasses.dataclass(frozen=True)
class Aggregation:
    """One aggregation: a SigNoz expression such as ``count()`` or ``p99(duration_nano)``."""

    expression: str
    #: Name of the value in results (default: the expression).
    alias: str | None = None


@dataclasses.dataclass(frozen=True)
class GroupByField:
    """A group-by field with an optional context (``resource``/``attribute``/``span``/``log``)."""

    name: str
    field_context: str | None = None
    field_data_type: str | None = None

    def to_json(self) -> dict[str, str]:
        out = {"name": self.name}
        if self.field_context:
            out["fieldContext"] = self.field_context
        if self.field_data_type:
            out["fieldDataType"] = self.field_data_type
        return out


@dataclasses.dataclass(frozen=True)
class AggregateRow:
    """One scalar result row."""

    #: Group-by values by field name.
    groups: dict[str, GroupValue]
    #: Aggregation values by alias.
    values: dict[str, float | None]


@dataclasses.dataclass(frozen=True)
class AggregateResult:
    """Result of :meth:`AggregateBuilder.run`."""

    rows: list[AggregateRow]
    #: Server warnings (for example unknown fields in the filter).
    warnings: list[str] = dataclasses.field(default_factory=list)


@dataclasses.dataclass(frozen=True)
class AggregatePoint:
    """One point of a time series."""

    timestamp: datetime
    value: float


@dataclasses.dataclass(frozen=True)
class AggregateSeries:
    """One series of :meth:`AggregateBuilder.time_series`."""

    #: Alias of the aggregation the series belongs to.
    alias: str
    #: Group-by labels of the series.
    labels: dict[str, GroupValue]
    points: list[AggregatePoint]


def _check_field(name: str) -> str:
    if not isinstance(name, str) or not _FIELD.match(name):
        raise ValidationError(f"aggregate: invalid field name {name!r}")
    return name


def _obj(value: Any) -> Mapping[str, Any]:
    return value if isinstance(value, Mapping) else {}


def _arr(value: Any) -> list[Any]:
    return list(value) if isinstance(value, (list, tuple)) else []


def _number(value: Any) -> float | None:
    if isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        return float(value) if math.isfinite(value) else None
    if isinstance(value, str) and value.strip():
        try:
            parsed = float(value)
        except ValueError:
            return None
        return parsed if math.isfinite(parsed) else None
    return None


def _group(value: Any) -> GroupValue:
    return value if isinstance(value, (str, int, float, bool)) else None


def _first_result(data: Any) -> Mapping[str, Any]:
    results = _arr(_obj(_obj(data).get("data")).get("results"))
    return _obj(results[0]) if results else {}


class _AggregateSpec:
    """Builder state and request rendering shared by the sync and async builders."""

    def __init__(self, signal: SignozSignal, clock: Callable[[], datetime] | None = None) -> None:
        if signal not in ("logs", "traces"):
            raise ValidationError('aggregate: signal must be "logs" or "traces"')
        self.signal: SignozSignal = signal
        self._clock = clock or (lambda: datetime.now(timezone.utc))
        self._aggregations: list[Aggregation] = []
        self._group_by: list[GroupByField] = []
        self._order: list[tuple[str, str]] = []
        self._filter = ""
        self._limit: int | None = None
        self._start: datetime | None = None
        self._end: datetime | None = None

    def filter(self: _B, expression: str | FilterBuilder) -> _B:
        """Set the filter expression (replaces an earlier one)."""
        self._filter = (
            expression.build() if isinstance(expression, FilterBuilder) else expression.strip()
        )
        return self

    def expression(self: _B, expression: str, alias: str | None = None) -> _B:
        """Add any aggregation expression."""
        text = expression.strip()
        if not text:
            raise ValidationError("aggregate: expression must not be empty")
        self._aggregations.append(Aggregation(text, alias))
        return self

    def count(self: _B, alias: str | None = None) -> _B:
        """``count()``."""
        return self.expression("count()", alias)

    def count_distinct(self: _B, field: str, alias: str | None = None) -> _B:
        """``count_distinct(field)``."""
        return self.expression(f"count_distinct({_check_field(field)})", alias)

    def sum(self: _B, field: str, alias: str | None = None) -> _B:
        """``sum(field)``."""
        return self.expression(f"sum({_check_field(field)})", alias)

    def avg(self: _B, field: str, alias: str | None = None) -> _B:
        """``avg(field)``."""
        return self.expression(f"avg({_check_field(field)})", alias)

    def min(self: _B, field: str, alias: str | None = None) -> _B:
        """``min(field)``."""
        return self.expression(f"min({_check_field(field)})", alias)

    def max(self: _B, field: str, alias: str | None = None) -> _B:
        """``max(field)``."""
        return self.expression(f"max({_check_field(field)})", alias)

    def percentile(
        self: _B, percentile: int, field: str = "duration_nano", alias: str | None = None
    ) -> _B:
        """``p<percentile>(field)``; percentile is one of 5, 10, 20, 25, 50, 75, 90, 95, 99."""
        if isinstance(percentile, bool) or percentile not in _PERCENTILES:
            raise ValidationError(
                "aggregate: percentile must be 5, 10, 20, 25, 50, 75, 90, 95 or 99"
            )
        return self.expression(f"p{percentile}({_check_field(field)})", alias)

    def p50(self: _B, field: str = "duration_nano", alias: str | None = None) -> _B:
        """``p50(field)`` (default field ``duration_nano``)."""
        return self.percentile(50, field, alias)

    def p90(self: _B, field: str = "duration_nano", alias: str | None = None) -> _B:
        """``p90(field)``."""
        return self.percentile(90, field, alias)

    def p95(self: _B, field: str = "duration_nano", alias: str | None = None) -> _B:
        """``p95(field)``."""
        return self.percentile(95, field, alias)

    def p99(self: _B, field: str = "duration_nano", alias: str | None = None) -> _B:
        """``p99(field)``."""
        return self.percentile(99, field, alias)

    def group_by(self: _B, *fields: str | GroupByField) -> _B:
        """Add group-by fields."""
        for item in fields:
            group = item if isinstance(item, GroupByField) else GroupByField(item)
            _check_field(group.name)
            self._group_by.append(group)
        return self

    def order_by(self: _B, key: str, direction: str = "desc") -> _B:
        """Order by a group field or an aggregation alias/expression."""
        if direction not in ("asc", "desc"):
            raise ValidationError('aggregate: direction must be "asc" or "desc"')
        self._order.append((key, direction))
        return self

    def limit(self: _B, limit: int) -> _B:
        """Maximum number of groups (positive integer)."""
        if isinstance(limit, bool) or not isinstance(limit, int) or limit <= 0:
            raise ValidationError("aggregate: limit must be a positive integer")
        self._limit = limit
        return self

    def between(self: _B, start: datetime, end: datetime) -> _B:
        """Time window (default: the last hour)."""
        self._start = start
        self._end = end
        return self

    def aliases(self) -> list[str]:
        """Aliases in aggregation order (the expression when no alias was given)."""
        return [a.alias or a.expression for a in self._aggregations]

    def to_request(
        self, request_type: Literal["scalar", "time_series"] = "scalar", step_s: int | None = None
    ) -> dict[str, Any]:
        """The request body that :meth:`run` / :meth:`time_series` send (for inspection)."""
        if not self._aggregations:
            raise ValidationError("aggregate: add at least one aggregation (count(), p99(), …)")
        end = self._end or self._clock()
        start = self._start or (end - timedelta(seconds=DEFAULT_QUERY_WINDOW_S))
        start_ms, end_ms = to_epoch_ms(start), to_epoch_ms(end)
        if start_ms >= end_ms:
            raise ValidationError("aggregate: start must be before end")
        spec: dict[str, Any] = {
            "name": "A",
            "signal": self.signal,
            "aggregations": [
                {"expression": a.expression}
                if a.alias is None
                else {"expression": a.expression, "alias": a.alias}
                for a in self._aggregations
            ],
        }
        if self._filter:
            spec["filter"] = {"expression": self._filter}
        if self._group_by:
            spec["groupBy"] = [g.to_json() for g in self._group_by]
        if self._order:
            spec["order"] = [{"key": {"name": k}, "direction": d} for k, d in self._order]
        if self._limit is not None:
            spec["limit"] = self._limit
        if step_s is not None:
            spec["stepInterval"] = step_s
        return {
            "start": start_ms,
            "end": end_ms,
            "requestType": request_type,
            "compositeQuery": {"queries": [{"type": "builder_query", "spec": spec}]},
        }

    def _operation(self) -> str:
        return f"aggregate.{self.signal}"

    def scalar_op(self) -> Op[AggregateResult]:
        """Operation for :meth:`run` (sans-I/O)."""
        body = self.to_request("scalar")
        aliases = self.aliases()
        res = yield from query_range_op(body, self._operation())
        result = _first_result(res.data)
        columns = [_obj(c) for c in _arr(result.get("columns"))]
        rows: list[AggregateRow] = []
        for raw in _arr(result.get("data")):
            values = _arr(raw)
            row = AggregateRow({}, {})
            for i, col in enumerate(columns):
                cell = values[i] if i < len(values) else None
                if col.get("columnType") == "group":
                    row.groups[str(col.get("name"))] = _group(cell)
                else:
                    index = col.get("aggregationIndex")
                    index = index if isinstance(index, int) else 0
                    name = aliases[index] if 0 <= index < len(aliases) else str(col.get("name"))
                    row.values[name] = _number(cell)
            rows.append(row)
        return AggregateResult(rows, list(res.warnings))

    def series_op(self, step_s: int | None = None) -> Op[list[AggregateSeries]]:
        """Operation for :meth:`time_series` (sans-I/O)."""
        if step_s is not None and (
            isinstance(step_s, bool) or not isinstance(step_s, int) or step_s <= 0
        ):
            raise ValidationError("aggregate: step_s must be a positive integer")
        body = self.to_request("time_series", step_s)
        aliases = self.aliases()
        res = yield from query_range_op(body, self._operation())
        out: list[AggregateSeries] = []
        for agg in (_obj(a) for a in _arr(_first_result(res.data).get("aggregations"))):
            index = agg.get("index")
            index = index if isinstance(index, int) else 0
            alias = aliases[index] if 0 <= index < len(aliases) else str(agg.get("alias"))
            for series in (_obj(s) for s in _arr(agg.get("series"))):
                labels: dict[str, GroupValue] = {}
                for label in (_obj(x) for x in _arr(series.get("labels"))):
                    labels[str(_obj(label.get("key")).get("name"))] = _group(label.get("value"))
                points = [
                    AggregatePoint(
                        _EPOCH + timedelta(milliseconds=float(_number(p.get("timestamp")) or 0)),
                        _number(p.get("value")) or 0.0,
                    )
                    for p in (_obj(v) for v in _arr(series.get("values")))
                ]
                out.append(AggregateSeries(alias, labels, points))
        return out


class AggregateBuilder(_AggregateSpec):
    """Fluent builder for SigNoz aggregations (``requestType`` ``scalar`` or ``time_series``).

    ``client.query.aggregate("traces").count("calls").p99("duration_nano", "p99")
    .group_by("name").run()``. Aliases are mapped back from SigNoz's ``__result_<i>`` columns
    by aggregation index. Default window: the last hour.
    """

    def __init__(
        self,
        signal: SignozSignal,
        runner: Callable[[Op[Any]], Any],
        clock: Callable[[], datetime] | None = None,
    ) -> None:
        super().__init__(signal, clock)
        self._runner = runner

    def run(self) -> AggregateResult:
        """Run a scalar query: one row per group."""
        result: AggregateResult = self._runner(self.scalar_op())
        return result

    def time_series(self, step_s: int | None = None) -> list[AggregateSeries]:
        """Run a time-series query with buckets of ``step_s`` seconds (default: SigNoz picks)."""
        result: list[AggregateSeries] = self._runner(self.series_op(step_s))
        return result


class AsyncAggregateBuilder(_AggregateSpec):
    """Async :class:`AggregateBuilder` (``await builder.run()``)."""

    def __init__(
        self,
        signal: SignozSignal,
        runner: Callable[[Op[Any]], Awaitable[Any]],
        clock: Callable[[], datetime] | None = None,
    ) -> None:
        super().__init__(signal, clock)
        self._runner = runner

    async def run(self) -> AggregateResult:
        """Run a scalar query: one row per group."""
        result: AggregateResult = await self._runner(self.scalar_op())
        return result

    async def time_series(self, step_s: int | None = None) -> list[AggregateSeries]:
        """Run a time-series query with buckets of ``step_s`` seconds."""
        result: list[AggregateSeries] = await self._runner(self.series_op(step_s))
        return result
