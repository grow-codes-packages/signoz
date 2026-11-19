"""Grow Codes SDK for SigNoz.

>>> from grow_signoz import SignozClient, SignozConfig
>>> client = SignozClient.from_env()  # doctest: +SKIP
"""

from . import _core
from ._core import *  # noqa: F403
from ._version import __version__
from .client import AsyncSignozClient, SignozClient, create_async_log_client, create_log_client
from .config import (
    API_KEY_HEADER,
    DEFAULT_OTLP_GRPC_ENDPOINT,
    DEFAULT_OTLP_HTTP_ENDPOINT,
    DEFAULT_URL,
    INGESTION_KEY_HEADER,
    OTLP_PROTOCOLS,
    OtlpProtocol,
    SignozConfig,
)

__all__ = [
    "__version__",
    "SignozClient",
    "AsyncSignozClient",
    "SignozConfig",
    "DEFAULT_URL",
    "DEFAULT_OTLP_HTTP_ENDPOINT",
    "DEFAULT_OTLP_GRPC_ENDPOINT",
    "API_KEY_HEADER",
    "INGESTION_KEY_HEADER",
    "OTLP_PROTOCOLS",
    "OtlpProtocol",
    "create_log_client",
    "create_async_log_client",
]
__all__ += _core.__all__

from . import _logging  # noqa: E402
from ._logging import *  # noqa: E402, F403

__all__ += _logging.__all__

from . import _tracing  # noqa: E402
from ._tracing import *  # noqa: E402, F403

__all__ += _tracing.__all__

# --------------------------------------------------------------------------- service modules
from ._api import ApiResult  # noqa: E402
from ._logs import (  # noqa: E402
    LIVE_TAIL_PATH,
    AsyncSignozLogQueryClient,
    AsyncSignozTailHandle,
    SignozLogQueryClient,
    SignozTailHandle,
    SseParser,
)
from ._mapping import (  # noqa: E402
    SignozLogRecord,
    SignozTrace,
    log_row_to_record,
    nanos_to_ms,
    rfc3339_to_nanos,
    span_row_to_span,
    waterfall_span_to_span,
    waterfall_to_trace,
)
from ._otlp import (  # noqa: E402
    OTLP_LOGS_GRPC_METHOD,
    AsyncSignozLogExporter,
    SignozLogExporter,
    encode_logs,
    endpoint_for,
    grpc_error,
    otlp_headers,
    otlp_http_url,
)
from ._query import (  # noqa: E402
    DEFAULT_LOG_LIMIT,
    DEFAULT_TRACE_SEARCH_LIMIT,
    MAX_QUERY_LIMIT,
    AsyncSignozQueryClient,
    LogField,
    LogQueryResult,
    SignozQueryClient,
    SortOrder,
    SpanQueryResult,
    resolve_window,
)
from ._traces import create_signoz_span_exporter, init_tracing, signoz_tracing_config  # noqa: E402
from .aggregate import (  # noqa: E402
    DEFAULT_QUERY_WINDOW_S,
    AggregateBuilder,
    AggregatePoint,
    AggregateResult,
    AggregateRow,
    AggregateSeries,
    Aggregation,
    AsyncAggregateBuilder,
    GroupByField,
    GroupValue,
    SignozSignal,
    to_epoch_ms,
)
from .filter import (  # noqa: E402
    FilterBuilder,
    FilterOperator,
    FilterValue,
    and_filters,
    escape_like_pattern,
    filter_literal,
    quote_filter_string,
)

__all__ += [
    "ApiResult",
    # query
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
    # logs query + tail
    "LIVE_TAIL_PATH",
    "AsyncSignozLogQueryClient",
    "AsyncSignozTailHandle",
    "SignozLogQueryClient",
    "SignozTailHandle",
    "SseParser",
    # mapping
    "SignozLogRecord",
    "SignozTrace",
    "log_row_to_record",
    "nanos_to_ms",
    "rfc3339_to_nanos",
    "span_row_to_span",
    "waterfall_span_to_span",
    "waterfall_to_trace",
    # OTLP export
    "OTLP_LOGS_GRPC_METHOD",
    "AsyncSignozLogExporter",
    "SignozLogExporter",
    "encode_logs",
    "endpoint_for",
    "grpc_error",
    "otlp_headers",
    "otlp_http_url",
    # tracing
    "create_signoz_span_exporter",
    "init_tracing",
    "signoz_tracing_config",
    # aggregate
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
    "to_epoch_ms",
    # filter
    "FilterBuilder",
    "FilterOperator",
    "FilterValue",
    "and_filters",
    "escape_like_pattern",
    "filter_literal",
    "quote_filter_string",
]
