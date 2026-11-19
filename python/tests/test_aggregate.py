"""Aggregate builder: request rendering, alias mapping from __result_<i>, time series."""

from __future__ import annotations

import json
from datetime import datetime, timedelta, timezone

import pytest

import grow_signoz as sdk
from _signoz import NOW, Router, aclient, client, json_response, success

QR = "/api/v5/query_range"


def scalar(columns: list[dict[str, object]], rows: list[list[object]]) -> dict[str, object]:
    return {"type": "scalar", "data": {"results": [{"columns": columns, "data": rows}]}}


def test_scalar_with_groups_and_aliases() -> None:
    router = Router()
    router.add(
        "POST",
        QR,
        success(
            scalar(
                [
                    {"name": "name", "columnType": "group"},
                    {"name": "__result_0", "columnType": "aggregation", "aggregationIndex": 0},
                    {"name": "__result_1", "columnType": "aggregation", "aggregationIndex": 1},
                    {"name": "__result_7", "columnType": "aggregation", "aggregationIndex": 7},
                ],
                [["db.query", 1, "100000000", None], ["GET /verify", 1, 150000000.0, 3]],
            )
        ),
    )
    c = client(router)
    start = NOW - timedelta(minutes=5)
    result = (
        c.query.aggregate("traces")
        .filter(sdk.FilterBuilder().eq("service.name", "x"))
        .count("calls")
        .p99()
        .group_by("name", sdk.GroupByField("service.name", "resource", "string"))
        .order_by("calls")
        .limit(10)
        .between(start, NOW)
        .run()
    )
    assert result.rows[0] == sdk.AggregateRow(
        {"name": "db.query"},
        {"calls": 1.0, "p99(duration_nano)": 100000000.0, "__result_7": None},
    )
    assert result.rows[1].values["p99(duration_nano)"] == 150000000.0
    assert result.rows[1].values["__result_7"] == 3.0
    body = router.bodies(QR)[0]
    spec = body["compositeQuery"]["queries"][0]["spec"]
    assert body["requestType"] == "scalar"
    assert (body["start"], body["end"]) == (sdk.to_epoch_ms(start), sdk.to_epoch_ms(NOW))
    assert spec["aggregations"] == [
        {"expression": "count()", "alias": "calls"},
        {"expression": "p99(duration_nano)"},
    ]
    assert spec["filter"] == {"expression": "service.name = 'x'"}
    assert spec["groupBy"] == [
        {"name": "name"},
        {"name": "service.name", "fieldContext": "resource", "fieldDataType": "string"},
    ]
    assert spec["order"] == [{"key": {"name": "calls"}, "direction": "desc"}]
    assert spec["limit"] == 10


def test_count_fixture_and_default_window() -> None:
    from _signoz import fixture

    router = Router().add("POST", QR, json_response(fixture("aggregate.count")))
    result = client(router).query.aggregate("logs").count().run()
    assert result.rows == [sdk.AggregateRow({}, {"count()": 12.0})]
    assert result.warnings == []
    body = router.bodies(QR)[0]
    assert body["end"] - body["start"] == 3_600_000
    assert body["end"] == sdk.to_epoch_ms(NOW)


def test_time_series_fixture() -> None:
    from _signoz import fixture

    router = Router().add("POST", QR, json_response(fixture("aggregate.timeSeries")))
    series = client(router).query.aggregate("logs").count("n").time_series(60)
    assert series == [
        sdk.AggregateSeries(
            "n",
            {},
            [sdk.AggregatePoint(datetime(2026, 11, 18, 20, 21, tzinfo=timezone.utc), 12.0)],
        )
    ]
    spec = router.bodies(QR)[0]["compositeQuery"]["queries"][0]["spec"]
    assert spec["stepInterval"] == 60
    assert router.bodies(QR)[0]["requestType"] == "time_series"


def test_time_series_labels_and_unknown_index() -> None:
    data = {
        "data": {
            "results": [
                {
                    "aggregations": [
                        {
                            "index": 0,
                            "series": [
                                {
                                    "labels": [{"key": {"name": "service.name"}, "value": "a"}],
                                    "values": [{"timestamp": 1000, "value": "2.5"}],
                                }
                            ],
                        },
                        {"index": 5, "alias": "__result_5", "series": [{"values": []}]},
                    ]
                }
            ]
        }
    }
    router = Router().add("POST", QR, success(data))
    series = client(router).query.aggregate("traces").count().time_series()
    assert series[0].labels == {"service.name": "a"}
    assert series[0].points[0].value == 2.5
    assert series[1].alias == "__result_5"
    assert "stepInterval" not in router.bodies(QR)[0]["compositeQuery"]["queries"][0]["spec"]


def test_every_aggregation_helper_and_validation() -> None:
    b = sdk.AggregateBuilder("traces", lambda op: None, lambda: NOW)
    b.count_distinct("trace_id").sum("n", "s").avg("n").min("n").max("n").p50().p90().p95()
    b.percentile(25, "duration_nano", "q").expression(" rate() ", "r")
    assert b.aliases() == [
        "count_distinct(trace_id)",
        "s",
        "avg(n)",
        "min(n)",
        "max(n)",
        "p50(duration_nano)",
        "p90(duration_nano)",
        "p95(duration_nano)",
        "q",
        "r",
    ]
    for bad in (
        lambda: sdk.AggregateBuilder("metrics", lambda op: None),  # type: ignore[arg-type]
        lambda: b.expression(" "),
        lambda: b.percentile(42),
        lambda: b.sum("bad field"),
        lambda: b.group_by("bad field"),
        lambda: b.order_by("x", "up"),
        lambda: b.limit(0),
        lambda: sdk.AggregateBuilder("logs", lambda op: None).to_request(),
        lambda: b.between(NOW, NOW).to_request(),
    ):
        with pytest.raises(sdk.ValidationError):
            bad()
    with pytest.raises(sdk.ValidationError):
        client(Router()).query.aggregate("logs").count().time_series(0)


async def test_async_aggregate() -> None:
    from _signoz import fixture

    router = Router()
    router.add("POST", QR, json_response(fixture("aggregate.count")))
    router.add("POST", QR, json_response(fixture("aggregate.timeSeries")))
    async with aclient(router) as c:
        result = await c.query.aggregate("logs").count("n").run()
        assert result.rows[0].values == {"n": 12.0}
        series = await c.query.aggregate("logs").count("n").time_series(60)
        assert series[0].points[0].value == 12.0
    assert json.loads(router.requests[0].content)["requestType"] == "scalar"
