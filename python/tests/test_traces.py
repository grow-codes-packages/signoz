"""init_tracing / span exporter factory and the row mapping helpers."""

from __future__ import annotations

import gzip

import pytest
from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import OTLPSpanExporter as GrpcExp
from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter as HttpExp
from opentelemetry.proto.collector.trace.v1.trace_service_pb2 import ExportTraceServiceRequest

import grow_signoz as sdk
from _signoz import config, fixture
from _support import MockServer, Reply


def test_signoz_tracing_config_defaults_and_overrides() -> None:
    cfg = config(otlp_endpoint="http://col:4318", ingestion_key="ingest-1")
    resolved = sdk.signoz_tracing_config("svc", cfg)
    assert (resolved.service_name, resolved.protocol) == ("svc", "http/protobuf")
    assert resolved.endpoint == "http://col:4318"
    assert resolved.headers == {"signoz-ingestion-key": "ingest-1"}
    assert resolved.tls == cfg.tls
    grpc = sdk.signoz_tracing_config(sdk.TracingConfig("svc", protocol="grpc"), cfg)
    assert grpc.endpoint == "http://col:4317"
    explicit = sdk.signoz_tracing_config(
        sdk.TracingConfig("svc", endpoint="http://other:1", headers={"signoz-ingestion-key": "x"}),
        cfg,
        environment="prod",
    )
    assert explicit.endpoint == "http://other:1"
    assert explicit.headers == {"signoz-ingestion-key": "x"}
    assert explicit.environment == "prod"
    from_grpc_config = sdk.signoz_tracing_config(
        "svc", config(otlp_protocol="grpc", otlp_endpoint="http://g:4317")
    )
    assert (from_grpc_config.protocol, from_grpc_config.endpoint) == ("grpc", "http://g:4317")
    with pytest.raises(sdk.ConfigError):
        sdk.signoz_tracing_config("svc", cfg, protocol="native")
    with pytest.raises(sdk.ConfigError):
        sdk.create_signoz_span_exporter(sdk.TracingConfig("svc", protocol="native"))


def test_exporter_factory_variants(tmp_path: object) -> None:
    http = sdk.create_signoz_span_exporter(sdk.TracingConfig("svc", compression="none"))
    assert isinstance(http, HttpExp)
    http.shutdown()
    pem = sdk.create_signoz_span_exporter(
        sdk.TracingConfig("svc", tls=sdk.TlsConfig(ca_cert_pem="-----BEGIN CERTIFICATE-----\n"))
    )
    pem.shutdown()
    insecure = sdk.create_signoz_span_exporter(
        sdk.TracingConfig(
            "svc", endpoint="https://c:4318", tls=sdk.TlsConfig(insecure_skip_verify=True)
        )
    )
    insecure.shutdown()
    grpc = sdk.create_signoz_span_exporter(sdk.TracingConfig("svc", protocol="grpc"))
    assert isinstance(grpc, GrpcExp)
    grpc.shutdown()
    secure = sdk.create_signoz_span_exporter(
        sdk.TracingConfig(
            "svc",
            protocol="grpc",
            endpoint="https://c:4317",
            compression="none",
            headers={"X-A": "1"},
        )
    )
    secure.shutdown()
    with pytest.raises(sdk.ConfigError):
        sdk.create_signoz_span_exporter(
            sdk.TracingConfig(
                "svc",
                protocol="grpc",
                endpoint="https://c:4317",
                tls=sdk.TlsConfig(insecure_skip_verify=True),
            )
        )
    with pytest.raises(sdk.ConfigError):
        sdk.create_signoz_span_exporter(
            sdk.TracingConfig(
                "svc",
                protocol="grpc",
                endpoint="https://c:4317",
                tls=sdk.TlsConfig(ca_cert_path="/nope.pem"),
            )
        )


def test_init_tracing_exports_to_collector(server: MockServer) -> None:
    server.defaults["/v1/traces"] = Reply(200, b"")
    cfg = config(otlp_endpoint=server.url, ingestion_key="ingest-7")
    handle = sdk.init_tracing("svc-x", signoz=cfg, set_global=False, service_version="1.0")
    with handle.tracer("t").start_as_current_span("op") as span:
        span.set_attribute("k", "v")
    handle.force_flush(5)
    handle.shutdown()
    seen = [s for s in server.seen if s.path == "/v1/traces"]
    assert seen
    headers = {k.lower(): v for k, v in seen[0].headers.items()}
    assert headers["signoz-ingestion-key"] == "ingest-7"
    body = seen[0].body
    if headers.get("content-encoding") == "gzip":
        body = gzip.decompress(body)
    req = ExportTraceServiceRequest.FromString(body)
    span_pb = req.resource_spans[0].scope_spans[0].spans[0]
    assert span_pb.name == "op"


def test_mapping_edge_cases() -> None:
    assert sdk.rfc3339_to_nanos("2026-11-18T20:07:04.581483281Z") == 1795032424581483281
    assert sdk.rfc3339_to_nanos("2026-11-18T21:07:04+01:00") == 1795032424000000000
    for bad in ("nope", "2026-13-01T00:00:00Z"):
        with pytest.raises(sdk.ValidationError):
            sdk.rfc3339_to_nanos(bad)
    assert sdk.nanos_to_ms(1795032424581483281) == 1795032424581
    rec = sdk.log_row_to_record(
        {
            "data": {
                "timestamp": "1795032424581483281",
                "body": {"msg": "s"},
                "severity_text": "warning",
                "trace_id": "00",
                "span_id": "abc",
            }
        }
    )
    assert rec.body == '{"msg":"s"}' and rec.severity == sdk.Severity.WARN
    assert rec.trace_id is None and rec.span_id is None and rec.trace_flags is None
    assert rec.time_unix_nano == 1795032424581483281
    rec2 = sdk.log_row_to_record(
        {
            "data": {
                "timestamp": "2026-11-18T20:07:04Z",
                "severity_text": "LOUD",
                "severity_number": "x",
            }
        }
    )
    assert rec2.severity == sdk.Severity.INFO and rec2.body == '""' and rec2.id == ""
    rec3 = sdk.log_row_to_record({"data": {"timestamp": 5, "severity_number": 1.0}})
    assert rec3.severity == sdk.Severity.TRACE
    with pytest.raises(sdk.ValidationError):
        sdk.log_row_to_record({"data": {}})
    assert sdk.span_row_to_span({"data": {"trace_id": "", "span_id": ""}}) is None
    span = sdk.span_row_to_span(
        {
            "data": {
                "trace_id": "ab",
                "span_id": "cd",
                "kind": "4",
                "status_code_string": "Ok",
                "events": [{"name": "e", "attributes": {"a": [1, None]}}],
                "links": [{"traceId": "ab", "spanId": "ef"}, {"traceId": "x"}],
                "service.name": "flat",
                "duration_nano": "2000",
            }
        }
    )
    assert span is not None
    assert (span.kind, span.status.code, span.service_name) == ("producer", "ok", "flat")
    assert span.start_micros == 0 and span.duration_micros == 2
    assert span.events[0].attributes == {"a": [1]}
    assert [link.span_id for link in span.links] == ["00000000000000ef"]
    odd = sdk.span_row_to_span(
        {
            "data": {
                "trace_id": "ab",
                "span_id": "cd",
                "kind": 99,
                "status_code": 2,
                "timestamp": "2026-11-18T20:07:04Z",
            }
        }
    )
    assert odd is not None and odd.kind == "unspecified" and odd.status.code == "error"
    assert odd.service_name == "unknown_service"
    wf = sdk.waterfall_span_to_span(
        {
            "trace_id": "ab",
            "span_id": "cd",
            "time_unix": 1000,
            "kind_string": "Consumer",
            "references": [
                {"refType": "CHILD_OF", "traceId": "ab", "spanId": "aa"},
                {"refType": "FOLLOWS_FROM", "traceId": "ab", "spanId": "bb"},
                {"refType": "FOLLOWS_FROM", "traceId": "ab"},
            ],
        }
    )
    assert wf is not None and wf.start_micros == 1_000_000 and wf.kind == "consumer"
    assert [link.span_id for link in wf.links] == ["00000000000000bb"]
    trace = sdk.waterfall_to_trace("ab", {"spans": [{"trace_id": "ab", "span_id": "cd"}, "x"]})
    assert trace.total_spans == 1 and trace.complete and trace.root_name is None
    full = sdk.waterfall_to_trace(
        "fe67ae4bfe67ae4bfe67ae4bfe67ae4b", fixture("getTrace.waterfall.ok")["data"]
    )
    assert len(full.spans) == 3
