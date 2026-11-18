# grow-signoz (Python)

Python SDK for [SigNoz](https://grow-codes-packages.github.io/signoz/) by Grow Codes.
Unofficial and community maintained; "SigNoz" is a trademark of SigNoz, Inc..

```bash
pip install grow-signoz
```

```python
from grow_signoz import SignozClient, SignozConfig

# Reads GROW_SIGNOZ_URL, GROW_SIGNOZ_TOKEN, ... from the environment.
with SignozClient.from_env() as client:
    print(client.health())
    print(client.server_version())
```

The async client has the same methods:

```python
import asyncio
from grow_signoz import AsyncSignozClient

async def main() -> None:
    async with AsyncSignozClient.from_env() as client:
        print(await client.health())

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
