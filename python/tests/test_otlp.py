"""OTLP log exporters (HTTP/protobuf + gRPC), encoding, headers and the log clients."""

from __future__ import annotations

import gzip
from collections.abc import Iterator
from concurrent import futures
from datetime import datetime, timezone
from typing import Any

import grpc
import httpx
import pytest
from opentelemetry.proto.collector.logs.v1 import logs_service_pb2_grpc
from opentelemetry.proto.collector.logs.v1.logs_service_pb2 import (
    ExportLogsPartialSuccess,
    ExportLogsServiceRequest,
    ExportLogsServiceResponse,
)

import grow_signoz as sdk
from _signoz import Router, config, json_response

TS = datetime(2026, 11, 18, 20, 0, 0, 123456, tzinfo=timezone.utc)


def record(body: str = "hello", **kw: Any) -> sdk.LogRecord:
    kw.setdefault("resource", {"service.name": "svc"})
    return sdk.LogRecord(body=body, timestamp=TS, **kw)


def protobuf(message: Any = None, status: int = 200) -> httpx.Response:
    payload = (message or ExportLogsServiceResponse()).SerializeToString()
    return httpx.Response(
        status, content=payload, headers={"content-type": "application/x-protobuf"}
    )


def decode(req: httpx.Request) -> ExportLogsServiceRequest:
    content = req.content
    if req.headers.get("content-encoding") == "gzip":
        content = gzip.decompress(content)
    return ExportLogsServiceRequest.FromString(content)


def test_encode_logs_groups_resources_and_types() -> None:
    records = [
        record(
            "a",
            severity=sdk.Severity.ERROR,
            attributes={
                "s": "x",
                "i": 7,
                "big": 2**70,
                "f": 1.5,
                "b": True,
                "raw": b"\x01",
                "list": [1, "two"],
                "map": {"k": "v"},
                "obj": object.__new__(type("Thing", (), {"__str__": lambda self: "thing"})),
                "none": None,
            },
            trace_id="4bf92f3577b34da6a3ce929d0e0e4736",
            span_id="00f067aa0ba902b7",
            trace_flags=1,
        ),
        record("b", trace_id="zz"),
        record("c", resource={"service.name": "other"}),
        sdk.LogRecord(body="naive", timestamp=datetime(2026, 1, 1)),
    ]
    req = sdk.encode_logs(records, "scope-x")
    assert len(req.resource_logs) == 3
    first = req.resource_logs[0]
    assert first.resource.attributes[0].key == "service.name"
    scope = first.scope_logs[0]
    assert scope.scope.name == "scope-x" and scope.scope.version == sdk.__version__
    a, b = scope.log_records
    assert a.body.string_value == "a" and a.severity_number == 17 and a.severity_text == "ERROR"
    assert a.time_unix_nano == 1795032000123456000 == a.observed_time_unix_nano
    attrs = {kv.key: kv.value for kv in a.attributes}
    assert attrs["s"].string_value == "x" and attrs["i"].int_value == 7
    assert attrs["big"].string_value == str(2**70) and attrs["f"].double_value == 1.5
    assert attrs["b"].bool_value is True and attrs["raw"].bytes_value == b"\x01"
    assert [v.int_value or v.string_value for v in attrs["list"].array_value.values] == [1, "two"]
    assert attrs["map"].kvlist_value.values[0].key == "k"
    assert attrs["obj"].string_value == "thing" and "none" not in attrs
    assert a.trace_id.hex() == "4bf92f3577b34da6a3ce929d0e0e4736" and a.flags == 1
    assert b.trace_id == b"" and b.span_id == b""
    assert req.resource_logs[2].scope_logs[0].log_records[0].time_unix_nano > 0


def test_helpers() -> None:
    assert sdk.otlp_http_url("http://c:4318/", "logs") == "http://c:4318/v1/logs"
    assert sdk.otlp_http_url("http://c:4318/v1/logs", "logs") == "http://c:4318/v1/logs"
    cfg = config(otlp_endpoint="http://col:4318", ingestion_key="ingest-1", headers={"x": "1"})
    assert sdk.endpoint_for(cfg, "http/protobuf") == "http://col:4318"
    assert sdk.endpoint_for(cfg, "grpc") == "http://col:4317"
    grpc_cfg = config(otlp_endpoint="https://col:4317", otlp_protocol="grpc")
    assert sdk.endpoint_for(grpc_cfg, "http/protobuf") == "https://col:4318"
    assert sdk.endpoint_for(config(otlp_endpoint="http://col:9999"), "grpc") == "http://col:9999"
    assert sdk.otlp_headers(cfg, {"y": "2"}) == {
        "x": "1",
        "y": "2",
        sdk.INGESTION_KEY_HEADER: "ingest-1",
    }
    for code, cls in (
        ("UNAUTHENTICATED", sdk.AuthenticationError),
        ("PERMISSION_DENIED", sdk.PermissionDeniedError),
        ("INVALID_ARGUMENT", sdk.ValidationError),
        ("NOT_FOUND", sdk.NotFoundError),
        ("ALREADY_EXISTS", sdk.ConflictError),
        ("RESOURCE_EXHAUSTED", sdk.RateLimitError),
        ("UNIMPLEMENTED", sdk.UnsupportedError),
        ("DEADLINE_EXCEEDED", sdk.TimeoutError),
        ("CANCELLED", sdk.CancelledError),
        ("UNAVAILABLE", sdk.UnavailableError),
    ):
        err = sdk.grpc_error(code, "detail secret-1234", "op", ["secret-1234"])
        assert isinstance(err, cls) and "secret-1234" not in str(err)
    assert sdk.grpc_error("UNAVAILABLE", "", "op", []).retryable


def test_http_exporter_sends_protobuf_with_gzip_and_ingestion_key() -> None:
    router = Router()
    partial = ExportLogsServiceResponse(
        partial_success=ExportLogsPartialSuccess(rejected_log_records=1, error_message="bad one")
    )
    router.add("POST", "/prefix/v1/logs", protobuf(), protobuf(partial))
    router.add(
        "POST",
        "/prefix/v1/logs",
        httpx.Response(
            200, content=b"\xff\xff", headers={"content-type": "application/x-protobuf"}
        ),
    )
    router.add("POST", "/prefix/v1/logs", json_response({"partialSuccess": {}}))
    router.add("POST", "/prefix/v1/logs", json_response({"code": 3, "message": "bad trace"}, 400))
    cfg = config(otlp_endpoint="http://col.test:4318/prefix", ingestion_key="ingest-key-9")
    exporter = sdk.SignozLogExporter(cfg, http_transport=router.transport(), headers={"x-a": "1"})
    assert exporter.export([]) == sdk.ExportResult(accepted=0)
    assert exporter.export([record()], gzip=True, timeout_s=5) == sdk.ExportResult(1)
    req = router.requests[0]
    assert str(req.url) == "http://col.test:4318/prefix/v1/logs"
    assert req.headers["content-encoding"] == "gzip"
    assert req.headers["content-type"] == "application/x-protobuf"
    assert req.headers["signoz-ingestion-key"] == "ingest-key-9"
    assert req.headers["x-a"] == "1"
    assert "signoz-api-key" not in req.headers
    decoded = ExportLogsServiceRequest.FromString(gzip.decompress(req.content))
    assert decoded.resource_logs[0].scope_logs[0].log_records[0].body.string_value == "hello"
    result = exporter.export([record("1"), record("2")], gzip=False, timeout_s=0)
    assert (result.accepted, result.rejected) == (1, 1)
    assert isinstance(result.error, sdk.ValidationError) and "bad one" in str(result.error)
    assert "content-encoding" not in router.requests[1].headers
    assert exporter.export([record()]).accepted == 1  # unreadable protobuf body
    assert exporter.export([record()]).accepted == 1  # JSON success body
    with pytest.raises(sdk.ValidationError):
        exporter.export([record()])
    exporter.shutdown()
    exporter.shutdown()
    assert exporter.closed


def test_exporter_validation() -> None:
    with pytest.raises(sdk.ConfigError):
        sdk.SignozLogExporter(config(), protocol="thrift")  # type: ignore[arg-type]
    with pytest.raises(sdk.ConfigError):
        sdk.SignozLogExporter(config(), endpoint="ftp://x")
    exp = sdk.SignozLogExporter(
        config(otlp_endpoint="https://c:4317"), protocol="grpc", endpoint="https://c:4317"
    )
    exp.shutdown()
    with pytest.raises(sdk.UnavailableError):
        exp.export([record()])
    insecure = sdk.SignozLogExporter(
        config(tls=sdk.TlsConfig(insecure_skip_verify=True)),
        protocol="grpc",
        endpoint="https://c:4317",
    )
    with pytest.raises(sdk.ConfigError):
        insecure.export([record()])
    bad_ca = sdk.SignozLogExporter(
        config(tls=sdk.TlsConfig(ca_cert_path="/nope/ca.pem")),
        protocol="grpc",
        endpoint="https://c:4317",
    )
    with pytest.raises(sdk.ConfigError):
        bad_ca.export([record()])


class _Collector(logs_service_pb2_grpc.LogsServiceServicer):
    def __init__(self) -> None:
        self.requests: list[tuple[ExportLogsServiceRequest, dict[str, str]]] = []
        self.fail: grpc.StatusCode | None = None

    def Export(self, request: Any, context: Any) -> Any:
        self.requests.append((request, dict(context.invocation_metadata())))
        if self.fail is not None:
            context.abort(self.fail, "collector says no")
        return ExportLogsServiceResponse()


@pytest.fixture
def collector() -> Iterator[tuple[_Collector, int]]:
    servicer = _Collector()
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=2))
    logs_service_pb2_grpc.add_LogsServiceServicer_to_server(servicer, server)
    port = server.add_insecure_port("127.0.0.1:0")
    server.start()
    yield servicer, port
    server.stop(0)


def test_grpc_exporter(collector: tuple[_Collector, int]) -> None:
    servicer, port = collector
    cfg = config(
        otlp_endpoint=f"http://127.0.0.1:{port}", otlp_protocol="grpc", ingestion_key="ingest-key-9"
    )
    exporter = sdk.SignozLogExporter(cfg)
    assert exporter.protocol == "grpc"
    assert exporter.export([record("g1")], gzip=True, timeout_s=5).accepted == 1
    assert exporter.export([record("g2")], gzip=False, timeout_s=0).accepted == 1
    request, metadata = servicer.requests[0]
    assert request.resource_logs[0].scope_logs[0].log_records[0].body.string_value == "g1"
    assert metadata["signoz-ingestion-key"] == "ingest-key-9"
    assert metadata["user-agent"].startswith("grow-codes-signoz/")
    servicer.fail = grpc.StatusCode.INVALID_ARGUMENT
    with pytest.raises(sdk.ValidationError, match="collector says no"):
        exporter.export([record()])
    exporter.shutdown()


async def test_async_grpc_and_http_exporters(collector: tuple[_Collector, int]) -> None:
    servicer, port = collector
    exporter = sdk.AsyncSignozLogExporter(
        config(), protocol="grpc", endpoint=f"http://127.0.0.1:{port}"
    )
    assert (await exporter.export([record("ag")])).accepted == 1
    assert (await exporter.export([])).accepted == 0
    await exporter.shutdown()
    await exporter.shutdown()
    router = Router().add("POST", "/v1/logs", protobuf())
    http_exp = sdk.AsyncSignozLogExporter(config(), http_transport=router.transport())
    assert (await http_exp.export([record("ah")], gzip=False)).accepted == 1
    await http_exp.shutdown()
    assert servicer.requests


def test_log_client_end_to_end_with_redaction() -> None:
    router = Router().default("POST", "/v1/logs", protobuf())
    with sdk.create_log_client(
        config(),
        service_name="svc",
        service_version="1.2",
        environment="test",
        resource={"host.name": "h"},
        http_transport=router.transport(),
        options=sdk.BatchOptions(flush_interval_s=0.05),
    ) as logs:
        logs.logger("lg").info("hi", {"password": "hunter2"})
        logs.flush(5)
        assert logs.stats().sent == 1
    req = decode(router.requests[0])
    res = {kv.key: kv.value.string_value for kv in req.resource_logs[0].resource.attributes}
    assert res == {
        "service.name": "svc",
        "service.version": "1.2",
        "deployment.environment": "test",
        "host.name": "h",
    }
    attrs = {
        kv.key: kv.value.string_value
        for kv in req.resource_logs[0].scope_logs[0].log_records[0].attributes
    }
    assert attrs["password"] == "[REDACTED]"
    plain = sdk.create_log_client(config(), redaction=None, http_transport=router.transport())
    plain.send(sdk.LogInput("x", attributes={"password": "visible"}))
    plain.close(5)
    last = decode(router.requests[-1])
    assert last.resource_logs[0].resource.attributes[0].value.string_value == "unknown_service"
    assert (
        last.resource_logs[0].scope_logs[0].log_records[0].attributes[0].value.string_value
        == "visible"
    )


async def test_async_log_client() -> None:
    router = Router().default("POST", "/v1/logs", protobuf())
    logs = sdk.create_async_log_client(
        config(), service_name="as", redaction=None, http_transport=router.transport()
    )
    logs.send(sdk.LogInput("async hello"))
    await logs.flush(5)
    await logs.close()
    assert logs.stats().sent == 1
