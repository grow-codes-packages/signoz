"""OTLP log exporters (HTTP/protobuf and gRPC) to the SigNoz collector, sync and async."""

from __future__ import annotations

import asyncio
import dataclasses
import gzip as _gzip
import json
from collections.abc import Mapping, Sequence
from datetime import datetime, timezone
from typing import Any
from urllib.parse import urlsplit, urlunsplit

from opentelemetry.proto.collector.logs.v1.logs_service_pb2 import (
    ExportLogsServiceRequest,
    ExportLogsServiceResponse,
)
from opentelemetry.proto.common.v1.common_pb2 import (
    AnyValue,
    ArrayValue,
    InstrumentationScope,
    KeyValue,
    KeyValueList,
)
from opentelemetry.proto.logs.v1.logs_pb2 import LogRecord as PbLogRecord
from opentelemetry.proto.logs.v1.logs_pb2 import ResourceLogs, ScopeLogs
from opentelemetry.proto.resource.v1.resource_pb2 import Resource

from ._core import (
    AsyncHttpTransport,
    AuthenticationError,
    CancelledError,
    ConfigError,
    ConflictError,
    HttpTransport,
    NotFoundError,
    PermissionDeniedError,
    RateLimitError,
    Request,
    Response,
    ServiceError,
    TimeoutError,
    UnavailableError,
    UnsupportedError,
    ValidationError,
    redact_text,
    truncate,
    user_agent,
)
from ._logging import ExportResult, LogRecord
from ._version import __version__
from .config import INGESTION_KEY_HEADER, OtlpProtocol, SignozConfig

__all__ = [
    "OTLP_LOGS_GRPC_METHOD",
    "SCOPE_NAME",
    "AsyncSignozLogExporter",
    "SignozLogExporter",
    "encode_logs",
    "endpoint_for",
    "grpc_error",
    "otlp_headers",
    "otlp_http_url",
]

#: gRPC method of the OTLP logs service.
OTLP_LOGS_GRPC_METHOD = "/opentelemetry.proto.collector.logs.v1.LogsService/Export"
#: Default instrumentation scope name of exported records.
SCOPE_NAME = "grow-signoz"

_INT64_MIN, _INT64_MAX = -(2**63), 2**63 - 1


def otlp_http_url(endpoint: str, signal: str) -> str:
    """Append ``/v1/<signal>`` to an OTLP/HTTP base endpoint unless it already ends with it."""
    base = endpoint.rstrip("/")
    suffix = f"/v1/{signal}"
    return base if base.endswith(suffix) else base + suffix


def endpoint_for(config: SignozConfig, protocol: OtlpProtocol | str) -> str:
    """Endpoint for ``protocol``: the configured one when the protocols match, otherwise the
    configured endpoint with the standard port swapped (4318 ↔ 4317)."""
    if protocol == config.otlp_protocol:
        return config.otlp_endpoint
    parts = urlsplit(config.otlp_endpoint)
    netloc = parts.netloc
    if protocol == "grpc" and parts.port == 4318:
        netloc = netloc[: netloc.rfind(":")] + ":4317"
    elif protocol == "http/protobuf" and parts.port == 4317:
        netloc = netloc[: netloc.rfind(":")] + ":4318"
    return urlunsplit((parts.scheme, netloc, parts.path, parts.query, parts.fragment)).rstrip("/")


def otlp_headers(config: SignozConfig, extra: Mapping[str, str] | None = None) -> dict[str, str]:
    """Headers for OTLP exports: config headers, ``extra`` and the Cloud ingestion key."""
    headers = {**dict(config.headers), **dict(extra or {})}
    if config.ingestion_key is not None:
        headers[INGESTION_KEY_HEADER] = config.ingestion_key.reveal()
    return headers


def _any_value(value: Any) -> AnyValue:
    if isinstance(value, bool):
        return AnyValue(bool_value=value)
    if isinstance(value, int):
        if _INT64_MIN <= value <= _INT64_MAX:
            return AnyValue(int_value=value)
        return AnyValue(string_value=str(value))
    if isinstance(value, float):
        return AnyValue(double_value=value)
    if isinstance(value, str):
        return AnyValue(string_value=value)
    if isinstance(value, bytes):
        return AnyValue(bytes_value=value)
    if isinstance(value, Mapping):
        return AnyValue(kvlist_value=KeyValueList(values=_key_values(value)))
    if isinstance(value, (list, tuple)):
        return AnyValue(array_value=ArrayValue(values=[_any_value(v) for v in value]))
    return AnyValue(string_value=str(value))


def _key_values(values: Mapping[str, Any]) -> list[KeyValue]:
    return [KeyValue(key=str(k), value=_any_value(v)) for k, v in values.items() if v is not None]


def _nanos(value: datetime) -> int:
    if value.tzinfo is None:
        value = value.replace(tzinfo=timezone.utc)
    delta = value - datetime(1970, 1, 1, tzinfo=timezone.utc)
    return (delta.days * 86_400 + delta.seconds) * 1_000_000_000 + delta.microseconds * 1000


def _id_bytes(value: str | None, width: int) -> bytes:
    if not value:
        return b""
    try:
        raw = bytes.fromhex(value.rjust(width * 2, "0"))
    except ValueError:
        return b""
    return raw if len(raw) == width else b""


def encode_logs(
    records: Sequence[LogRecord], scope_name: str = SCOPE_NAME
) -> ExportLogsServiceRequest:
    """Build an OTLP ``ExportLogsServiceRequest``, grouping records by identical resource."""
    groups: dict[str, tuple[Mapping[str, Any], list[PbLogRecord]]] = {}
    for record in records:
        key = json.dumps(record.resource, sort_keys=True, default=str)
        entry = groups.setdefault(key, (record.resource, []))
        pb = PbLogRecord(
            time_unix_nano=_nanos(record.timestamp),
            observed_time_unix_nano=_nanos(record.observed_timestamp or record.timestamp),
            severity_number=int(record.severity),  # type: ignore[arg-type]
            severity_text=record.severity_text,
            body=AnyValue(string_value=record.body),
            attributes=_key_values(record.attributes),
        )
        if record.trace_id:
            pb.trace_id = _id_bytes(record.trace_id, 16)
            pb.span_id = _id_bytes(record.span_id, 8)
            pb.flags = int(record.trace_flags or 0) & 0xFF
        entry[1].append(pb)
    scope = InstrumentationScope(name=scope_name, version=__version__)
    return ExportLogsServiceRequest(
        resource_logs=[
            ResourceLogs(
                resource=Resource(attributes=_key_values(resource)),
                scope_logs=[ScopeLogs(scope=scope, log_records=items)],
            )
            for resource, items in groups.values()
        ]
    )


def _result(count: int, payload: bytes | None, retries: int) -> ExportResult:
    rejected = 0
    message = ""
    if payload:
        try:
            response = ExportLogsServiceResponse.FromString(payload)
            rejected = int(response.partial_success.rejected_log_records)
            message = response.partial_success.error_message
        except Exception:  # noqa: BLE001 - an unreadable success body counts as accepted
            rejected = 0
    rejected = min(max(rejected, 0), count)
    error = None
    if rejected:
        detail = f": {truncate(message, 300)}" if message else ""
        error = ValidationError(f"logs.export: collector rejected {rejected} records{detail}")
    return ExportResult(accepted=count - rejected, rejected=rejected, error=error, retries=retries)


def grpc_error(code_name: str, detail: str, operation: str, secrets: list[str]) -> ServiceError:
    """Map a gRPC status code name (``"UNAVAILABLE"``…) to the SPEC error classes."""
    message = redact_text(
        f"{operation}: {truncate(' '.join(detail.split()), 300)} (gRPC {code_name})", secrets
    )
    classes: dict[str, type[ServiceError]] = {
        "UNAUTHENTICATED": AuthenticationError,
        "PERMISSION_DENIED": PermissionDeniedError,
        "INVALID_ARGUMENT": ValidationError,
        "FAILED_PRECONDITION": ValidationError,
        "OUT_OF_RANGE": ValidationError,
        "NOT_FOUND": NotFoundError,
        "ALREADY_EXISTS": ConflictError,
        "ABORTED": ConflictError,
        "RESOURCE_EXHAUSTED": RateLimitError,
        "UNIMPLEMENTED": UnsupportedError,
        "DEADLINE_EXCEEDED": TimeoutError,
        "CANCELLED": CancelledError,
    }
    cls = classes.get(code_name, UnavailableError)
    if cls is RateLimitError or cls is UnavailableError:
        return cls(message, retryable=True)
    return cls(message)


class _ExporterBase:
    def __init__(
        self,
        config: SignozConfig,
        *,
        protocol: OtlpProtocol | None,
        endpoint: str | None,
        headers: Mapping[str, str] | None,
        scope_name: str,
    ) -> None:
        self.config = config
        self.protocol: OtlpProtocol = protocol or config.otlp_protocol
        if self.protocol not in ("http/protobuf", "grpc"):
            raise ConfigError("logs exporter: protocol must be http/protobuf or grpc")
        self.endpoint = (endpoint or endpoint_for(config, self.protocol)).rstrip("/")
        parts = urlsplit(self.endpoint)
        if parts.scheme not in ("http", "https") or not parts.hostname:
            raise ConfigError("logs exporter: endpoint must be an http(s) URL")
        self.headers = otlp_headers(config, headers)
        self.scope_name = scope_name
        self.closed = False
        self._grpc: Any = None
        self._channel: Any = None
        self._origin = urlunsplit((parts.scheme, parts.netloc, "", "", ""))
        self._path = urlsplit(otlp_http_url(self.endpoint, "logs")).path

    def _transport_config(self) -> SignozConfig:
        return dataclasses.replace(self.config, url=self._origin)

    def _http_request(self, records: Sequence[LogRecord], gzip: bool, timeout_s: float) -> Request:
        body = encode_logs(records, self.scope_name).SerializeToString()
        headers = {
            **self.headers,
            "Content-Type": "application/x-protobuf",
            "Accept": "application/x-protobuf",
        }
        if gzip:
            body = _gzip.compress(body)
            headers["Content-Encoding"] = "gzip"
        return Request(
            "POST",
            self._path,
            operation="logs.export",
            content=body,
            headers=headers,
            timeout=timeout_s if timeout_s > 0 else None,
        )

    @staticmethod
    def _http_result(count: int, resp: Response) -> ExportResult:
        protobuf = "protobuf" in resp.headers.get("content-type", "").lower()
        return _result(count, resp.content if protobuf else None, resp.attempts - 1)

    def _grpc_stub(self) -> Any:
        if self._grpc is not None:
            return self._grpc
        try:
            import grpc  # noqa: PLC0415 - optional extra
            from opentelemetry.proto.collector.logs.v1.logs_service_pb2_grpc import (  # noqa: PLC0415
                LogsServiceStub,
            )
        except ImportError as exc:  # pragma: no cover - depends on the installed extras
            raise ConfigError(
                "logs exporter: protocol grpc needs the 'grpc' extra "
                "(pip install 'grow-signoz[grpc]')"
            ) from exc
        parts = urlsplit(self.endpoint)
        port = parts.port or (443 if parts.scheme == "https" else 80)
        target = f"{parts.hostname}:{port}"
        options = [("grpc.primary_user_agent", user_agent(self.config.user_agent_suffix))]
        if parts.scheme == "https":
            tls = self.config.tls
            if tls.insecure_skip_verify:
                raise ConfigError("logs exporter: insecure_skip_verify is not supported over gRPC")
            ca = _read(tls.ca_cert_path) or (tls.ca_cert_pem.encode() if tls.ca_cert_pem else None)
            credentials = grpc.ssl_channel_credentials(
                root_certificates=ca,
                private_key=_read(tls.client_key_path),
                certificate_chain=_read(tls.client_cert_path),
            )
            self._channel = grpc.secure_channel(target, credentials, options=options)
        else:
            self._channel = grpc.insecure_channel(target, options=options)
        self._grpc = LogsServiceStub(self._channel)  # type: ignore[no-untyped-call]
        return self._grpc

    def _export_grpc(
        self, records: Sequence[LogRecord], gzip: bool, timeout_s: float
    ) -> ExportResult:
        import grpc  # noqa: PLC0415 - optional extra

        if self.closed:
            raise UnavailableError("logs.export: exporter is shut down")
        stub = self._grpc_stub()
        metadata = [(k.lower(), v) for k, v in self.headers.items()]
        try:
            response = stub.Export(
                encode_logs(records, self.scope_name),
                metadata=metadata,
                timeout=timeout_s if timeout_s > 0 else self.config.request_timeout,
                compression=grpc.Compression.Gzip if gzip else grpc.Compression.NoCompression,
            )
        except grpc.RpcError as exc:
            code = exc.code() if hasattr(exc, "code") else None
            detail = exc.details() if hasattr(exc, "details") else str(exc)
            raise grpc_error(
                code.name if code is not None else "UNKNOWN",
                str(detail or ""),
                "logs.export",
                self.config.known_secrets(),
            ) from None
        return _result(len(records), response.SerializeToString(), 0)

    def _close_grpc(self) -> None:
        if self._channel is not None:
            self._channel.close()
            self._channel = None
            self._grpc = None


def _read(path: str | None) -> bytes | None:
    if not path:
        return None
    try:
        with open(path, "rb") as fh:
            return fh.read()
    except OSError:
        raise ConfigError("config: TLS certificate files could not be loaded") from None


class SignozLogExporter(_ExporterBase):
    """``LogExporter`` sending OTLP logs to the SigNoz collector.

    HTTP/protobuf (``POST {endpoint}/v1/logs``, optional gzip) or gRPC (``LogsService/Export``,
    extra ``grow-signoz[grpc]``). Adds ``signoz-ingestion-key`` for SigNoz Cloud when configured.
    Failed batches are retried by the :class:`BatchProcessor`.
    """

    def __init__(
        self,
        config: SignozConfig | None = None,
        *,
        protocol: OtlpProtocol | None = None,
        endpoint: str | None = None,
        headers: Mapping[str, str] | None = None,
        scope_name: str = SCOPE_NAME,
        http_transport: Any = None,
    ) -> None:
        super().__init__(
            config or SignozConfig.from_env(),
            protocol=protocol,
            endpoint=endpoint,
            headers=headers,
            scope_name=scope_name,
        )
        self._transport: HttpTransport | None = None
        if self.protocol == "http/protobuf":
            cfg = self._transport_config()
            self._transport = HttpTransport(
                cfg, known_secrets=cfg.known_secrets, http_transport=http_transport
            )

    def export(
        self, records: Sequence[LogRecord], *, gzip: bool = True, timeout_s: float = 30.0
    ) -> ExportResult:
        """Send one batch; raises SDK errors for transport/HTTP failures."""
        if not records:
            return ExportResult(accepted=0)
        if self._transport is None:
            return self._export_grpc(records, gzip, timeout_s)
        resp = self._transport.request(self._http_request(records, gzip, timeout_s))
        return self._http_result(len(records), resp)

    def shutdown(self) -> None:
        """Close connections (idempotent)."""
        if self.closed:
            return
        self.closed = True
        self._close_grpc()
        if self._transport is not None:
            self._transport.close()


class AsyncSignozLogExporter(_ExporterBase):
    """``AsyncLogExporter`` variant of :class:`SignozLogExporter` (gRPC runs in a thread)."""

    def __init__(
        self,
        config: SignozConfig | None = None,
        *,
        protocol: OtlpProtocol | None = None,
        endpoint: str | None = None,
        headers: Mapping[str, str] | None = None,
        scope_name: str = SCOPE_NAME,
        http_transport: Any = None,
    ) -> None:
        super().__init__(
            config or SignozConfig.from_env(),
            protocol=protocol,
            endpoint=endpoint,
            headers=headers,
            scope_name=scope_name,
        )
        self._transport: AsyncHttpTransport | None = None
        if self.protocol == "http/protobuf":
            cfg = self._transport_config()
            self._transport = AsyncHttpTransport(
                cfg, known_secrets=cfg.known_secrets, http_transport=http_transport
            )

    async def export(
        self, records: Sequence[LogRecord], *, gzip: bool = True, timeout_s: float = 30.0
    ) -> ExportResult:
        """Send one batch; raises SDK errors for transport/HTTP failures."""
        if not records:
            return ExportResult(accepted=0)
        if self._transport is None:
            return await asyncio.to_thread(self._export_grpc, records, gzip, timeout_s)
        resp = await self._transport.request(self._http_request(records, gzip, timeout_s))
        return self._http_result(len(records), resp)

    async def shutdown(self) -> None:
        """Close connections (idempotent)."""
        if self.closed:
            return
        self.closed = True
        self._close_grpc()
        if self._transport is not None:
            await self._transport.aclose()
