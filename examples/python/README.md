# Python examples

Install the package, point it at a running SigNoz (query API on 8080, collector on 4318/4317) and
run an example:

```bash
pip install "grow-signoz[grpc]"
export SIGNOZ_URL=http://127.0.0.1:8080
export SIGNOZ_API_KEY=...            # service-account key (viewer is enough)
export SIGNOZ_OTLP_ENDPOINT=http://127.0.0.1:4318
python examples/python/basic.py
```

| File | Shows |
| - | - |
| `basic.py` | OTLP logs + traces to the collector, `wait_for_log`, `FilterBuilder`, `query_logs`, `wait_for_trace`, aggregations |
| `async_basic.py` | async client: log client, `wait_for_log`, `list_services`, cancellation by task |
| `hooks_and_logging.py` | request hooks, the `grow_signoz` logger, retries/timeouts, `logging.Handler` to SigNoz |
