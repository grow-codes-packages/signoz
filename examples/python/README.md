# Python examples

Install the package, point it at a running SigNoz and run an example:

```bash
pip install grow-signoz
export GROW_SIGNOZ_URL=http://127.0.0.1:8080
export GROW_SIGNOZ_TOKEN=...   # when the service needs one
python examples/python/basic.py
```

| File | Shows |
| - | - |
| `basic.py` | sync client: health, ping, server version, error handling |
| `async_basic.py` | async client, cancellation by task |
| `hooks_and_logging.py` | request hooks, the `grow_signoz` logger, retries and timeouts |
