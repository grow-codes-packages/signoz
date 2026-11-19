"""Send logs and a trace to SigNoz over OTLP, then read them back with the query API."""

from __future__ import annotations

import uuid

import grow_signoz as sdk


def main() -> None:
    run = uuid.uuid4().hex[:8]
    # Reads GROW_SIGNOZ_URL / SIGNOZ_URL, SIGNOZ_API_KEY, SIGNOZ_OTLP_ENDPOINT … from the environment.
    with sdk.SignozClient.from_env() as client:
        print(f"health: {client.health()}")
        print(f"ping: {client.ping():.1f} ms")
        try:
            print(f"server version: {client.server_version()}")
        except sdk.UnsupportedError:
            print("this SigNoz build does not report its version")

        # Logs: OTLP to the collector (needs no API key), queried back with the API key.
        logs = client.log_client(service_name="example-py", environment="dev")
        log = logs.logger("example")
        log.info(f"hello from grow-signoz {run}", {"run": run, "password": "redacted-before-send"})
        logs.flush(10)
        record = client.logs.wait_for_log(f"run = '{run}'", timeout_s=60)
        print(f"found log: {record.body!r} attributes={dict(record.attributes)}")

        # Filters render SigNoz search expressions with correct escaping.
        flt = sdk.FilterBuilder().eq("service.name", "example-py").contains("body", run)
        page = client.query.query_logs(flt, limit=10)
        print(f"query_logs: {len(page.records)} record(s), warnings={page.warnings}")

        # Tracing: OpenTelemetry with the OTLP exporter preconfigured for the collector.
        tracing = client.init_tracing("example-py", set_global=False)
        with tracing.tracer("example").start_as_current_span("checkout") as span:
            span.set_attribute("run", run)
            trace_id = format(span.get_span_context().trace_id, "032x")
        tracing.force_flush(10)
        trace = client.traces.wait_for_trace(trace_id, timeout_s=60)
        print(f"trace {trace.trace_id}: {[s.name for s in trace.spans]}")

        # Aggregations: aliases are mapped back from SigNoz's __result_<i> columns.
        stats = (
            client.query.aggregate("traces")
            .filter(f"service.name = 'example-py'")
            .count("calls")
            .p99("duration_nano", "p99")
            .group_by("name")
            .run()
        )
        for row in stats.rows:
            print(f"  {row.groups['name']}: calls={row.values['calls']} p99={row.values['p99']} ns")


if __name__ == "__main__":
    main()
