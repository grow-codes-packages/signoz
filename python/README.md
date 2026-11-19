# grow-signoz (Python)

Python SDK for [SigNoz](https://grow-codes-packages.github.io/signoz/) by Grow Codes.
Unofficial and community maintained; "SigNoz" is a trademark of SigNoz, Inc..

```bash
pip install grow-signoz          # OTLP/HTTP
pip install "grow-signoz[grpc]"  # + OTLP/gRPC
```

```python
from grow_signoz import FilterBuilder, LogInput, Severity, SignozClient

# Reads GROW_SIGNOZ_URL / SIGNOZ_URL, SIGNOZ_API_KEY, SIGNOZ_OTLP_ENDPOINT, ... from the environment.
with SignozClient.from_env() as client:
    print(client.health())

    logs = client.log_client(service_name="checkout")          # OTLP to the collector
    logs.send(LogInput("payment failed", severity=Severity.ERROR, attributes={"order": 42}))
    logs.flush(10)

    flt = FilterBuilder().eq("service.name", "checkout").in_("severity_text", ["ERROR"])
    page = client.query.query_logs(flt, limit=50)               # /api/v5/query_range
    for record in page.records:
        print(record.timestamp, record.body)

    tracing = client.init_tracing("checkout", set_global=False)  # OpenTelemetry → collector
    with tracing.tracer("app").start_as_current_span("charge"):
        pass
    tracing.force_flush(10)
    print(client.traces.list_services())
```

The async client has the same methods:

```python
import asyncio
from grow_signoz import AsyncSignozClient

async def main() -> None:
    async with AsyncSignozClient.from_env() as client:
        print(await client.health())
        print(await client.query.query_logs("timeout", limit=10))

asyncio.run(main())
```

- Python 3.10 or newer, typed (`py.typed`), `httpx` based, environment proxies ignored unless
  configured.
- Retries with full-jitter backoff and `Retry-After`, connect/request/total timeouts,
  cancellation, hooks and a silent-by-default `logging` logger named `grow_signoz`.
- Credentials are wrapped in `Secret` and never appear in `repr()`, logs or error messages.
- `grow_signoz.testing` ships in-memory fakes and the conformance runner.

Documentation: https://grow-codes-packages.github.io/signoz/ ·
Source: https://github.com/grow-codes-packages/signoz · Licence: Apache-2.0
