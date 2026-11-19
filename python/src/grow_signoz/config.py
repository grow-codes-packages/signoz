"""Configuration for the SigNoz client.

This file is owned by the service package (generated once by sdk-kit).
"""

from __future__ import annotations

import dataclasses
from collections.abc import Mapping
from typing import Any, ClassVar, Literal, TypeVar
from urllib.parse import urlsplit

from ._core import BaseConfig, ConfigError, EnvReader, Secret

__all__ = [
    "API_KEY_HEADER",
    "DEFAULT_OTLP_GRPC_ENDPOINT",
    "DEFAULT_OTLP_HTTP_ENDPOINT",
    "DEFAULT_URL",
    "INGESTION_KEY_HEADER",
    "OTLP_PROTOCOLS",
    "OtlpProtocol",
    "SignozConfig",
]

#: Default query API endpoint (SigNoz UI + API, port 8080).
DEFAULT_URL = "http://127.0.0.1:8080"
#: Default OTLP/HTTP endpoint of the SigNoz collector.
DEFAULT_OTLP_HTTP_ENDPOINT = "http://127.0.0.1:4318"
#: Default OTLP/gRPC endpoint of the SigNoz collector.
DEFAULT_OTLP_GRPC_ENDPOINT = "http://127.0.0.1:4317"
#: Header carrying the query API key.
API_KEY_HEADER = "SIGNOZ-API-KEY"
#: Header / gRPC metadata carrying the SigNoz Cloud ingestion key.
INGESTION_KEY_HEADER = "signoz-ingestion-key"

#: OTLP export protocol used for logs and traces sent to the collector.
OtlpProtocol = Literal["http/protobuf", "grpc"]
#: Accepted values of :attr:`SignozConfig.otlp_protocol`.
OTLP_PROTOCOLS: tuple[str, ...] = ("http/protobuf", "grpc")

_C = TypeVar("_C", bound="SignozConfig")


@dataclasses.dataclass(frozen=True, repr=False)
class SignozConfig(BaseConfig):
    """Immutable settings for :class:`~grow_signoz.SignozClient`.

    Common fields (``url``, timeouts, ``retry``, ``tls``, ``proxy``, ``headers``,
    ``user_agent_suffix``, ``hooks``, ``logger``) come from :class:`BaseConfig`. Durations are
    float seconds; ``datetime.timedelta`` is accepted. ``api_key`` and ``ingestion_key`` accept a
    ``str`` and are stored as :class:`Secret`; ``repr()`` never shows them.

    Service fields:

    * ``api_key`` — query API key sent as ``SIGNOZ-API-KEY``. Needed only for query calls and
      :meth:`~grow_signoz.SignozClient.ping`; an ingest-only client does not need it.
    * ``otlp_endpoint`` — collector endpoint (default ``http://127.0.0.1:4318`` for
      ``http/protobuf``, ``http://127.0.0.1:4317`` for ``grpc``).
    * ``otlp_protocol`` — ``"http/protobuf"`` (default) or ``"grpc"``.
    * ``ingestion_key`` — SigNoz Cloud ingestion key, sent as ``signoz-ingestion-key`` on every
      OTLP export (self-hosted collectors ignore it).

    Environment (``from_env``): explicit keyword > ``GROW_SIGNOZ_<NAME>`` > vendor variable >
    default. ``URL`` (``SIGNOZ_URL``), ``API_KEY`` (``SIGNOZ_API_KEY``), ``OTLP_ENDPOINT``
    (``SIGNOZ_OTLP_ENDPOINT``, ``OTEL_EXPORTER_OTLP_ENDPOINT``), ``OTLP_PROTOCOL``
    (``OTEL_EXPORTER_OTLP_PROTOCOL``), ``INGESTION_KEY`` (``SIGNOZ_INGESTION_KEY``), plus the
    common ``CONNECT_TIMEOUT``, ``REQUEST_TIMEOUT``, ``TOTAL_TIMEOUT``, ``MAX_RETRIES``,
    ``CA_CERT``, ``CLIENT_CERT``, ``CLIENT_KEY``, ``INSECURE_SKIP_VERIFY``, ``PROXY``.
    """

    #: Vendor variables per GROW_* suffix, in precedence order.
    ENV_VENDOR: ClassVar[Mapping[str, tuple[str, ...]]] = {
        "URL": ("SIGNOZ_URL",),
        "API_KEY": ("SIGNOZ_API_KEY",),
        "OTLP_ENDPOINT": ("SIGNOZ_OTLP_ENDPOINT", "OTEL_EXPORTER_OTLP_ENDPOINT"),
        "OTLP_PROTOCOL": ("OTEL_EXPORTER_OTLP_PROTOCOL",),
        "INGESTION_KEY": ("SIGNOZ_INGESTION_KEY",),
    }

    url: str = DEFAULT_URL
    api_key: Secret | None = None
    otlp_endpoint: str = ""
    otlp_protocol: OtlpProtocol = "http/protobuf"
    ingestion_key: Secret | None = None

    def __post_init__(self) -> None:
        super().__post_init__()
        protocol = str(self.otlp_protocol).strip().lower() or "http/protobuf"
        object.__setattr__(self, "otlp_protocol", protocol)
        endpoint = (self.otlp_endpoint or "").strip()
        if not endpoint:
            endpoint = (
                DEFAULT_OTLP_GRPC_ENDPOINT if protocol == "grpc" else DEFAULT_OTLP_HTTP_ENDPOINT
            )
        object.__setattr__(self, "otlp_endpoint", endpoint.rstrip("/"))

    @classmethod
    def _env_values(cls, reader: EnvReader) -> dict[str, Any]:
        values = super()._env_values(reader)
        vendor = cls.ENV_VENDOR
        for field, suffix in (("api_key", "API_KEY"), ("ingestion_key", "INGESTION_KEY")):
            secret = reader.string(suffix, vendor.get(suffix, ()))
            if secret is not None:
                values[field] = Secret(secret)
        endpoint = reader.string("OTLP_ENDPOINT", vendor.get("OTLP_ENDPOINT", ()))
        if endpoint is not None:
            values["otlp_endpoint"] = endpoint
        found = reader.lookup("OTLP_PROTOCOL", vendor.get("OTLP_PROTOCOL", ()))
        if found is not None:
            var, raw = found
            protocol = raw.strip().lower()
            if protocol not in OTLP_PROTOCOLS:
                raise ConfigError(f"config: {var} must be http/protobuf or grpc")
            values["otlp_protocol"] = protocol
        return values

    def validate(self: _C) -> _C:
        """Common checks plus SigNoz rules; raises :class:`ConfigError`."""
        super().validate()
        if self.api_key is not None and not self.api_key.reveal().strip():
            raise ConfigError("config: api_key must not be blank")
        if self.ingestion_key is not None and not self.ingestion_key.reveal().strip():
            raise ConfigError("config: ingestion_key must not be blank")
        if self.otlp_protocol not in OTLP_PROTOCOLS:
            raise ConfigError("config: otlp_protocol must be http/protobuf or grpc")
        try:
            parts = urlsplit(self.otlp_endpoint)
        except ValueError:
            raise ConfigError("config: otlp_endpoint must be an http(s) URL") from None
        if parts.scheme not in ("http", "https") or not parts.hostname:
            raise ConfigError("config: otlp_endpoint must be an http(s) URL")
        return self

    def with_changes(self: _C, **changes: Any) -> _C:
        """Copy with some fields replaced (validated)."""
        return dataclasses.replace(self, **changes).validate()
