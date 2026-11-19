"""Builder for SigNoz v5 filter expressions (search syntax)."""

from __future__ import annotations

import math
import re
from collections.abc import Iterable, Sequence
from typing import Literal, TypeAlias

from ._core import ValidationError

__all__ = [
    "FilterBuilder",
    "FilterOperator",
    "FilterValue",
    "and_filters",
    "escape_like_pattern",
    "filter_literal",
    "quote_filter_string",
]

#: A literal accepted by :class:`FilterBuilder` comparisons.
FilterValue: TypeAlias = "str | int | float | bool"
#: Comparison operators of the SigNoz search syntax.
FilterOperator = Literal["=", "!=", "<", "<=", ">", ">="]

_FIELD = re.compile(r"^[A-Za-z0-9_.:-]+$")
_OR = re.compile(r"\bOR\b", re.IGNORECASE)
_OPERATORS = frozenset({"=", "!=", "<", "<=", ">", ">="})


def quote_filter_string(value: str) -> str:
    """Quote a string literal: ``'…'`` with ``\\`` → ``\\\\`` and ``'`` → ``\\'``."""
    return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"


def escape_like_pattern(value: str) -> str:
    """Escape a value for ``CONTAINS``/``LIKE`` so it matches literally (``\\``, ``%``, ``_``).

    String quoting (:func:`quote_filter_string`) is applied afterwards.
    """
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")


def filter_literal(value: FilterValue) -> str:
    """Render a literal: numbers bare, booleans ``true``/``false``, strings quoted."""
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, int):
        return str(value)
    if isinstance(value, float):
        if not math.isfinite(value):
            raise ValidationError("filter: numbers must be finite")
        if value.is_integer() and abs(value) < 1e15:
            return str(int(value))
        return repr(value)
    if isinstance(value, str):
        return quote_filter_string(value)
    raise ValidationError("filter: values must be str, int, float or bool")


def _field(name: str) -> str:
    if not isinstance(name, str) or not _FIELD.match(name):
        raise ValidationError(f"filter: invalid field name {name!r}")
    return name


def _list(values: Iterable[FilterValue]) -> str:
    items = list(values)
    if not items:
        raise ValidationError("filter: IN lists must not be empty")
    return "(" + ", ".join(filter_literal(v) for v in items) + ")"


def _text(part: FilterBuilder | str | None) -> str:
    if part is None:
        return ""
    if isinstance(part, FilterBuilder):
        return part.build()
    return part.strip()


class FilterBuilder:
    """Immutable builder for SigNoz filter expressions.

    ``FilterBuilder().eq("service.name", "x").in_("severity_text", ["WARN", "ERROR"]).build()``
    → ``service.name = 'x' AND severity_text IN ('WARN', 'ERROR')``. Clauses are joined with
    ``AND``; use :meth:`or_` / :meth:`not_` for groups (rendered in parentheses). Every method
    returns a new builder; invalid field names, empty lists and non-finite numbers raise
    :class:`ValidationError`.
    """

    __slots__ = ("_clauses",)

    def __init__(self, clauses: Sequence[str] = ()) -> None:
        self._clauses: tuple[str, ...] = tuple(clauses)

    @classmethod
    def create(cls) -> FilterBuilder:
        """A new empty builder."""
        return cls()

    def _add(self, clause: str) -> FilterBuilder:
        return FilterBuilder((*self._clauses, clause))

    def where(self, name: str, op: FilterOperator, value: FilterValue) -> FilterBuilder:
        """``field <op> value``."""
        if op not in _OPERATORS:
            raise ValidationError(f"filter: unknown operator {op}")
        return self._add(f"{_field(name)} {op} {filter_literal(value)}")

    def eq(self, name: str, value: FilterValue) -> FilterBuilder:
        """``field = value``."""
        return self.where(name, "=", value)

    def ne(self, name: str, value: FilterValue) -> FilterBuilder:
        """``field != value``."""
        return self.where(name, "!=", value)

    def lt(self, name: str, value: FilterValue) -> FilterBuilder:
        """``field < value``."""
        return self.where(name, "<", value)

    def lte(self, name: str, value: FilterValue) -> FilterBuilder:
        """``field <= value``."""
        return self.where(name, "<=", value)

    def gt(self, name: str, value: FilterValue) -> FilterBuilder:
        """``field > value``."""
        return self.where(name, ">", value)

    def gte(self, name: str, value: FilterValue) -> FilterBuilder:
        """``field >= value``."""
        return self.where(name, ">=", value)

    def in_(self, name: str, values: Iterable[FilterValue]) -> FilterBuilder:
        """``field IN (v1, v2, …)`` (non-empty list)."""
        return self._add(f"{_field(name)} IN {_list(values)}")

    def not_in(self, name: str, values: Iterable[FilterValue]) -> FilterBuilder:
        """``field NOT IN (v1, v2, …)`` (non-empty list)."""
        return self._add(f"{_field(name)} NOT IN {_list(values)}")

    def contains(self, name: str, text: str) -> FilterBuilder:
        """``field CONTAINS 'text'`` — matches ``text`` literally (LIKE wildcards escaped)."""
        literal = quote_filter_string(escape_like_pattern(text))
        return self._add(f"{_field(name)} CONTAINS {literal}")

    def not_contains(self, name: str, text: str) -> FilterBuilder:
        """``NOT field CONTAINS 'text'`` (literal match)."""
        return self._add(
            f"NOT {_field(name)} CONTAINS {quote_filter_string(escape_like_pattern(text))}"
        )

    def like(self, name: str, pattern: str) -> FilterBuilder:
        """``field LIKE 'pattern'`` — ``%`` and ``_`` are wildcards; passed as given."""
        return self._add(f"{_field(name)} LIKE {quote_filter_string(pattern)}")

    def regexp(self, name: str, pattern: str) -> FilterBuilder:
        """``field REGEXP 're'``."""
        return self._add(f"{_field(name)} REGEXP {quote_filter_string(pattern)}")

    def exists(self, name: str) -> FilterBuilder:
        """``field EXISTS``."""
        return self._add(f"{_field(name)} EXISTS")

    def not_exists(self, name: str) -> FilterBuilder:
        """``field NOT EXISTS``."""
        return self._add(f"{_field(name)} NOT EXISTS")

    def text(self, value: str) -> FilterBuilder:
        """Full-text search of the log body: ``'text'``."""
        return self._add(quote_filter_string(value))

    def raw(self, expression: str) -> FilterBuilder:
        """A raw expression, passed through untouched (parenthesised when it contains ``OR``)."""
        trimmed = expression.strip()
        if not trimmed:
            return self
        return self._add(f"({trimmed})" if _OR.search(trimmed) else trimmed)

    def or_(self, *alternatives: FilterBuilder | str) -> FilterBuilder:
        """``((a) OR (b) …)`` from builders/expressions; empty alternatives are ignored."""
        parts = [p for p in (_text(a) for a in alternatives) if p]
        if not parts:
            return self
        if len(parts) == 1:
            return self._add(f"({parts[0]})")
        return self._add("(" + " OR ".join(f"({p})" for p in parts) + ")")

    def not_(self, expression: FilterBuilder | str) -> FilterBuilder:
        """``NOT (expression)``."""
        text = _text(expression)
        if not text:
            return self
        return self._add(f"NOT ({text})")

    @property
    def is_empty(self) -> bool:
        """``True`` when no clause was added."""
        return not self._clauses

    def build(self) -> str:
        """The expression (``""`` when empty)."""
        return " AND ".join(self._clauses)

    def __str__(self) -> str:
        return self.build()

    def __repr__(self) -> str:
        return f"FilterBuilder({self.build()!r})"

    def __eq__(self, other: object) -> bool:
        return isinstance(other, FilterBuilder) and other._clauses == self._clauses

    def __hash__(self) -> int:
        return hash(self._clauses)


def and_filters(*parts: FilterBuilder | str | None) -> str:
    """Join expressions with ``AND``, parenthesising each non-empty part when there are several."""
    texts = [t for t in (_text(p) for p in parts) if t]
    if len(texts) <= 1:
        return texts[0] if texts else ""
    return " AND ".join(f"({t})" for t in texts)
