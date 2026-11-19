"""``init_tracing``: OpenTelemetry tracing exporting OTLP to the SigNoz collector."""

from __future__ import annotations

import atexit
import contextlib
import dataclasses
import os
import tempfile
from collections.abc import Mapping, Sequence
from typing import Any

from opentelemetry.sdk.trace import SpanProcessor
from opentelemetry.sdk.trace.export import SpanExporter
from opentelemetry.sdk.trace.id_generator import IdGenerator

from ._core import ConfigError, TlsConfig
from ._otlp import endpoint_for, otlp_headers, otlp_http_url
from ._tracing import PropagatorFactory, TracingConfig, TracingHandle, init_tracing_with
from .config import DEFAULT_OTLP_GRPC_ENDPOINT, DEFAULT_OTLP_HTTP_ENDPOINT, SignozConfig

__all__ = [
    "create_signoz_span_exporter",
    "init_tracing",
    "signoz_tracing_config",
]


def signoz_tracing_config(
    config: TracingConfig | str, signoz: SignozConfig | None = None, **overrides: Any
) -> TracingConfig:
    """Merge SigNoz defaults under a :class:`TracingConfig`.

    ``config`` may be a ``TracingConfig`` or just the service name. Without an explicit
    ``endpoint``, the protocol is ``signoz.otlp_protocol`` unless the tracing config picks
    ``grpc`` (``http/protobuf`` is the ``TracingConfig`` default and cannot be told apart from
    "unset", so it follows the SigNoz config — same rule as Rust and Java). The endpoint defaults
    to the configured collector endpoint for the protocol (4318 ↔ 4317 swapped when the
    protocols differ), headers get the ``signoz-ingestion-key`` (explicit headers win) and TLS
    defaults to ``signoz.tls``. ``signoz`` defaults to :meth:`SignozConfig.from_env`.
    """
    cfg = signoz or SignozConfig.from_env()
    if isinstance(config, str):
        overrides.setdefault("protocol", cfg.otlp_protocol)
        tracing = TracingConfig(service_name=config, **overrides)
    else:
        tracing = dataclasses.replace(config, **overrides) if overrides else config
    if tracing.protocol == "native":
        raise ConfigError("tracing: SigNoz has no native trace protocol; use http/protobuf or grpc")
    protocol = tracing.protocol
    if not tracing.endpoint and protocol == "http/protobuf":
        protocol = cfg.otlp_protocol
    endpoint = tracing.endpoint or endpoint_for(cfg, protocol)
    return dataclasses.replace(
        tracing,
        protocol=protocol,
        endpoint=endpoint,
        headers={**otlp_headers(cfg), **dict(tracing.headers)},
        tls=tracing.tls or cfg.tls,
    )


def _read(path: str | None) -> bytes | None:
    if not path:
        return None
    try:
        with open(path, "rb") as fh:
            return fh.read()
    except OSError:
        raise ConfigError("tracing: TLS certificate files could not be loaded") from None


def _pem_file(pem: str) -> str:
    handle = tempfile.NamedTemporaryFile(  # noqa: SIM115 - removed at exit
        "w", suffix=".pem", delete=False
    )
    with handle:
        handle.write(pem)
    atexit.register(lambda: _remove(handle.name))
    return handle.name


def _remove(path: str) -> None:
    with contextlib.suppress(OSError):
        os.unlink(path)


def _grpc_exporter(config: TracingConfig, endpoint: str) -> SpanExporter:
    try:
        import grpc  # noqa: PLC0415 - optional extra
        from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import (  # noqa: PLC0415
            OTLPSpanExporter as GrpcSpanExporter,
        )
    except ImportError as exc:  # pragma: no cover - depends on the installed extras
        raise ConfigError(
            "tracing: protocol grpc needs the 'grpc' extra (pip install 'grow-signoz[grpc]')"
        ) from exc
    tls = config.tls or TlsConfig()
    secure = endpoint.startswith("https:")
    credentials = None
    if secure:
        if tls.insecure_skip_verify:
            raise ConfigError("tracing: insecure_skip_verify is not supported by the gRPC exporter")
        ca = _read(tls.ca_cert_path) or (tls.ca_cert_pem.encode() if tls.ca_cert_pem else None)
        credentials = grpc.ssl_channel_credentials(
            root_certificates=ca,
            private_key=_read(tls.client_key_path),
            certificate_chain=_read(tls.client_cert_path),
        )
    return GrpcSpanExporter(
        endpoint=endpoint,
        insecure=not secure,
        credentials=credentials,
        headers={k.lower(): v for k, v in config.headers.items()},
        timeout=config.batch.export_timeout_s,
        compression=grpc.Compression.Gzip
        if config.compression == "gzip"
        else grpc.Compression.NoCompression,
    )


def _http_exporter(config: TracingConfig, endpoint: str) -> SpanExporter:
    import requests  # noqa: PLC0415 - dependency of the OTLP/HTTP exporter
    from opentelemetry.exporter.otlp.proto.http import Compression  # noqa: PLC0415
    from opentelemetry.exporter.otlp.proto.http.trace_exporter import (  # noqa: PLC0415
        OTLPSpanExporter as HttpSpanExporter,
    )

    tls = config.tls or TlsConfig()
    ca_path = tls.ca_cert_path or (_pem_file(tls.ca_cert_pem) if tls.ca_cert_pem else None)
    session = None
    if tls.insecure_skip_verify:
        session = requests.Session()
        session.verify = False
    return HttpSpanExporter(
        endpoint=otlp_http_url(endpoint, "traces"),
        certificate_file=ca_path,
        client_key_file=tls.client_key_path,
        client_certificate_file=tls.client_cert_path,
        headers=dict(config.headers),
        timeout=config.batch.export_timeout_s,
        compression=Compression.Gzip if config.compression == "gzip" else Compression.NoCompression,
        session=session,
    )


def create_signoz_span_exporter(config: TracingConfig) -> SpanExporter:
    """The span exporter for a config (the exporter factory of ``init_tracing_with``).

    ``http/protobuf`` → OTLP/HTTP protobuf to ``{endpoint}/v1/traces``; ``grpc`` → OTLP/gRPC
    (extra ``grow-signoz[grpc]``). ``native`` raises :class:`ConfigError`.
    """
    if config.protocol == "native":
        raise ConfigError("tracing: SigNoz has no native trace protocol; use http/protobuf or grpc")
    if config.protocol == "grpc":
        return _grpc_exporter(config, config.endpoint or DEFAULT_OTLP_GRPC_ENDPOINT)
    return _http_exporter(config, config.endpoint or DEFAULT_OTLP_HTTP_ENDPOINT)


def init_tracing(
    config: TracingConfig | str,
    *,
    signoz: SignozConfig | None = None,
    propagator_factories: Mapping[str, PropagatorFactory] | None = None,
    span_processors: Sequence[SpanProcessor] = (),
    id_generator: IdGenerator | None = None,
    **overrides: Any,
) -> TracingHandle:
    """Set up OpenTelemetry tracing that exports to the SigNoz collector (SPEC §5).

    ``config`` is a :class:`TracingConfig` or the service name (``**overrides`` are then
    ``TracingConfig`` fields). Endpoint, protocol default and ingestion key come from ``signoz``
    (default :meth:`SignozConfig.from_env`); explicit ``TracingConfig`` values win. Raises
    :class:`ConfigError` for invalid settings.
    """
    resolved = signoz_tracing_config(config, signoz, **overrides)
    return init_tracing_with(
        resolved,
        create_signoz_span_exporter,
        propagator_factories=propagator_factories,
        span_processors=span_processors,
        id_generator=id_generator,
    )
