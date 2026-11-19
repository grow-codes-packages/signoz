# Troubleshooting

## The client cannot connect

- `health()` returns `healthy: false, status: "unreachable"`: the URL is wrong or the service is
  down. Check `GROW_SIGNOZ_URL` (default `http://127.0.0.1:8080`) and that the port is
  reachable from where your code runs (inside Docker, `127.0.0.1` is the container itself).
- Calls fail with `UNAVAILABLE` after several seconds: the client retried with backoff. Lower
  `maxRetries` or `totalTimeout` to fail faster.
- Behind a corporate proxy: `HTTP_PROXY`/`HTTPS_PROXY` are **ignored** on purpose. Set `proxy`
  (or `GROW_SIGNOZ_PROXY`).

## `CONFIG` errors

The message names the setting or environment variable, never its value. Typical causes: a URL
without `http://` or `https://`, a duration such as `10 sec` (use `10s` or `10000`), a client
certificate without its key, `MAX_RETRIES` below 0.

## `AUTHENTICATION` or `PERMISSION_DENIED`

- `ping()` is the quickest credential check: it makes one authenticated call.
- Make sure the key variable is the one this SDK reads (`GROW_SIGNOZ_API_KEY`, then
  `SIGNOZ_API_KEY`); explicit config values win over the environment. Without any key, query
  calls raise `CONFIG` before sending anything.
- The key must be a SigNoz **service-account API key** (Settings → Service accounts), not a
  SigNoz Cloud ingestion key and not a user session token. Any role works for queries.
- The SDK never prints credentials, so `toString()` of the config shows `[REDACTED]` even when a
  key is set.

## Logs or spans do not show up

- Ingest goes to the **collector** (`otlpEndpoint`, default `http://127.0.0.1:4318`), not to the
  query URL (port 8080). Check `GROW_SIGNOZ_OTLP_ENDPOINT` and `GROW_SIGNOZ_OTLP_PROTOCOL`
  (`grpc` uses port 4317).
- The log client batches: call `flush()` (or `close()` the client) before you exit, and check
  `stats()` for `dropped` and `failed` records.
- New data is queryable after a few seconds; use `waitForLog` / `waitForTrace` in tests.
- SigNoz Cloud needs `ingestionKey` (`GROW_SIGNOZ_INGESTION_KEY` / `SIGNOZ_INGESTION_KEY`).

## A query returns no rows

- An **unknown field** in the filter does not fail: SigNoz returns no rows plus a warning. Read
  `warnings` on the result or turn on the [logger](hooks.md#logger) (warnings are logged at warn
  level).
- The default window is the **last hour**; pass `start`/`end` for older data.
- `CONTAINS` is a LIKE pattern on the server; the [`FilterBuilder`](functions/filter.md) escapes
  `%` and `_` for you, a raw expression does not.

## `UNSUPPORTED`

- `serverVersion()` on the official community image: it reports an empty version.
- Any call to a route the server does not have: SigNoz answers unknown routes with its web UI
  (`200 text/html`), which the SDK maps to `UNSUPPORTED`. Check that `url` points at the SigNoz
  query service (port 8080) and that the server version matches
  [Compatibility](compatibility.md).

## TLS errors

- Self-signed or private CA: set `tls.caCertPath` (`GROW_SIGNOZ_CA_CERT`).
- Mutual TLS: set both `tls.clientCertPath` and `tls.clientKeyPath`.
- `tls.insecureSkipVerify` only for local tests. Over OTLP/gRPC it is not honoured by every
  language ([Compatibility](compatibility.md#tls-insecureskipverify-and-otlpgrpc)); add the CA
  with `tls.caCertPath` or use `http/protobuf`.

## `RATE_LIMITED`

The service asked the client to slow down. The client waits for `Retry-After` up to
`maxRetryAfter` (60 s); longer waits fail at once with `retryAfter` set on the error so your code
can schedule a later attempt.

## `TIMEOUT`

`requestTimeout` (30 s) bounds one attempt and `totalTimeout` (120 s) the whole call including
retries. Raise them for slow operations, or pass a per-call deadline (signal, ctx, cancel token,
future timeout).

## See what the client does

Turn on the [logger](hooks.md#logger) or add [hooks](hooks.md#hooks): both see every attempt with
redacted headers and URLs.

## Docker integration tests fail

- Run one language: `make test-docker LANGS=go`.
- In CI, download the `compose-logs-<lang>` artifact of the failed job.
- Locally, the stack is removed after each run; rerun with Docker logs open
  (`docker compose -f docker/compose.yml logs -f`) in a second terminal.

## Still stuck?

Open an issue with the language, package version, SigNoz version and a minimal reproduction
(with secrets removed): <https://github.com/grow-codes-packages/signoz/issues>.
