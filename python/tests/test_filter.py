"""FilterBuilder: SigNoz search syntax, escaping layers and grouping."""

from __future__ import annotations

import pytest

import grow_signoz as sdk
from grow_signoz import FilterBuilder


def test_ticket_example() -> None:
    f = FilterBuilder.create().eq("service.name", "x").in_("severity_text", ["WARN", "ERROR"])
    assert f.build() == "service.name = 'x' AND severity_text IN ('WARN', 'ERROR')"
    assert str(f) == f.build()
    assert repr(f) == f"FilterBuilder({f.build()!r})"
    assert f == FilterBuilder().eq("service.name", "x").in_("severity_text", ["WARN", "ERROR"])
    assert f != "x"
    assert hash(f) == hash(FilterBuilder(f._clauses))


def test_every_operator() -> None:
    f = (
        FilterBuilder()
        .ne("a", "b")
        .lt("n", 1)
        .lte("n", 2)
        .gt("n", 3)
        .gte("duration_nano", 4.5)
        .eq("ok", True)
        .eq("bad", False)
        .eq("whole", 2.0)
        .not_in("severity_number", [13, 17])
        .like("service.name", "sdk-%")
        .regexp("body", "prep-.*-abc")
        .exists("run.id")
        .not_exists("missing")
        .text("needle")
        .not_contains("body", "y")
    )
    assert f.build() == (
        "a != 'b' AND n < 1 AND n <= 2 AND n > 3 AND duration_nano >= 4.5 AND ok = true"
        " AND bad = false AND whole = 2 AND severity_number NOT IN (13, 17)"
        " AND service.name LIKE 'sdk-%' AND body REGEXP 'prep-.*-abc'"
        " AND run.id EXISTS AND missing NOT EXISTS AND 'needle' AND NOT body CONTAINS 'y'"
    )


def test_escaping_two_layers_for_contains() -> None:
    assert sdk.quote_filter_string("it's a back\\slash test") == "'it\\'s a back\\\\slash test'"
    assert sdk.escape_like_pattern("50%_off\\") == "50\\%\\_off\\\\"
    assert FilterBuilder().contains("body", "back\\slash").build() == (
        "body CONTAINS 'back\\\\\\\\slash'"
    )
    assert FilterBuilder().contains("body", "prep_grpc").build() == "body CONTAINS 'prep\\\\_grpc'"
    assert sdk.filter_literal(1e20) == "1e+20"
    assert sdk.filter_literal(-3) == "-3"


def test_grouping_and_immutability() -> None:
    base = FilterBuilder().eq("service.name", "a")
    grouped = base.or_(FilterBuilder().eq("x", 1), "y = 2", "", FilterBuilder()).not_("z = 3")
    assert grouped.build() == "service.name = 'a' AND ((x = 1) OR (y = 2)) AND NOT (z = 3)"
    assert base.build() == "service.name = 'a'"
    assert base.or_("only = 1").build() == "service.name = 'a' AND (only = 1)"
    assert base.or_("", FilterBuilder()) is base
    assert base.not_("  ") is base
    assert base.not_(FilterBuilder().eq("q", "w")).build() == "service.name = 'a' AND NOT (q = 'w')"
    assert base.raw("a = 1 OR b = 2").build() == "service.name = 'a' AND (a = 1 OR b = 2)"
    assert FilterBuilder().raw(" has(body, 'x') ").build() == "has(body, 'x')"
    assert base.raw("") is base
    assert FilterBuilder().is_empty
    assert not base.is_empty


def test_bad_input_raises_validation() -> None:
    with pytest.raises(sdk.ValidationError):
        FilterBuilder().eq("bad field", "x")
    with pytest.raises(sdk.ValidationError):
        FilterBuilder().in_("a", [])
    with pytest.raises(sdk.ValidationError):
        FilterBuilder().where("a", "~", 1)  # type: ignore[arg-type]
    with pytest.raises(sdk.ValidationError):
        sdk.filter_literal(float("nan"))
    with pytest.raises(sdk.ValidationError):
        sdk.filter_literal(None)  # type: ignore[arg-type]


def test_and_filters() -> None:
    assert sdk.and_filters() == ""
    assert sdk.and_filters(None, " ", "a = 1") == "a = 1"
    assert sdk.and_filters("a = 1 OR b = 2", FilterBuilder().eq("c", 3)) == (
        "(a = 1 OR b = 2) AND (c = 3)"
    )
