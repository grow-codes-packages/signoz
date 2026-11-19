# @grow-codes/signoz

TypeScript SDK for [SigNoz](https://grow-codes-packages.github.io/signoz/) by Grow Codes.
Node.js 22.12 or newer, ESM and CommonJS, full type definitions.

```sh
npm install @grow-codes/signoz
```

```ts
import { SignozClient } from "@grow-codes/signoz";

// Reads GROW_SIGNOZ_URL, GROW_SIGNOZ_API_KEY, GROW_SIGNOZ_OTLP_ENDPOINT, timeouts, TLS and proxy settings.
const client = SignozClient.fromEnv();
try {
  console.log(await client.health()); // { healthy: true, status: "ok", details: {} }
  console.log(`ping: ${(await client.ping()).toFixed(1)} ms`);
} finally {
  await client.close();
}
```

Every error is a `SignozError` subclass with a canonical `code` (`NOT_FOUND`,
`AUTHENTICATION`, `RATE_LIMITED`, …), `httpStatus`, `requestId` and `retryable`. Credentials
never appear in messages, logs, hook data or `toString()` output.

Test helpers (in-memory fakes and the conformance runner) live in
`@grow-codes/signoz/testing`.

Documentation: https://grow-codes-packages.github.io/signoz/ · Licence: Apache-2.0.
SigNoz is a trademark of SigNoz, Inc.; this package is not affiliated with or endorsed by it.
