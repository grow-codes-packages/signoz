# Grow Codes SDK for SigNoz

[![CI](https://github.com/grow-codes-packages/signoz/actions/workflows/ci.yml/badge.svg)](https://github.com/grow-codes-packages/signoz/actions/workflows/ci.yml)
[![Latest release](https://img.shields.io/github/v/release/grow-codes-packages/signoz?sort=semver)](https://github.com/grow-codes-packages/signoz/releases)
[![Docs](https://img.shields.io/badge/docs-grow--codes--packages.github.io-blue)](https://grow-codes-packages.github.io/signoz/)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)
[![Grow Codes](https://img.shields.io/badge/Grow%20Codes-grow.codes-2ea44f)](https://grow.codes)

| Language | Package | Coverage |
| - | - | - |
| TypeScript | [![npm](https://img.shields.io/npm/v/@grow-codes/signoz)](https://www.npmjs.com/package/@grow-codes/signoz) | [![TypeScript coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/signoz/badges/coverage-typescript.json)](https://github.com/grow-codes-packages/signoz/actions/workflows/ci.yml) |
| Rust | [![crates.io](https://img.shields.io/crates/v/grow-signoz)](https://crates.io/crates/grow-signoz) [![docs.rs](https://img.shields.io/docsrs/grow-signoz)](https://docs.rs/grow-signoz) | [![Rust coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/signoz/badges/coverage-rust.json)](https://github.com/grow-codes-packages/signoz/actions/workflows/ci.yml) |
| Go | [![Go Reference](https://pkg.go.dev/badge/github.com/grow-codes-packages/signoz/go.svg)](https://pkg.go.dev/github.com/grow-codes-packages/signoz/go) | [![Go coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/signoz/badges/coverage-go.json)](https://github.com/grow-codes-packages/signoz/actions/workflows/ci.yml) |
| Python | [![PyPI](https://img.shields.io/pypi/v/grow-signoz)](https://pypi.org/project/grow-signoz/) | [![Python coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/signoz/badges/coverage-python.json)](https://github.com/grow-codes-packages/signoz/actions/workflows/ci.yml) |
| Java | [![Maven Central](https://img.shields.io/maven-central/v/codes.grow.packages/signoz)](https://central.sonatype.com/artifact/codes.grow.packages/signoz) | [![Java coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/signoz/badges/coverage-java.json)](https://github.com/grow-codes-packages/signoz/actions/workflows/ci.yml) |

Open-source client libraries for [SigNoz](#trademarks-and-disclaimer) in **TypeScript, Rust, Go,
Python and Java** — one API, five idiomatic packages, tested against a real SigNoz in Docker.

- **Typed client** with timeouts, retries with jitter, `Retry-After`, cancellation and one error
  model (`SignozError` with canonical codes) in every language.
- **Secrets stay secret**: credentials are redacted in `toString`, logs, hooks and error messages.
- **Logging**: batched, compressed OTLP log shipping to the SigNoz collector (HTTP or gRPC) with
  back-pressure, redaction, a logger facade and adapters for the common logging frameworks of each
  language; log queries with a lossless cursor, live tail and field discovery.
- **Tracing**: OpenTelemetry setup in one call with the SigNoz exporter, span helpers, context
  propagation, framework middleware and a trace query client (waterfall, search, services,
  operations, dependencies).
- **Query API**: aggregations (count, percentiles, …) as scalars or time series, raw
  `query_range` requests and a `FilterBuilder` for safe filter expressions.

## Contents

- [Supported service version](#supported-service-version)
- [Install](#install)
- [Quickstart](#quickstart)
- [Configuration](#configuration)
- [Function overview](#function-overview)
- [Testing with Docker](#testing-with-docker)
- [Compatibility](#compatibility)
- [Contributing](#contributing)
- [Security](#security)
- [Licence](#licence)
- [Trademarks and disclaimer](#trademarks-and-disclaimer)
- [About Grow Codes](#about-grow-codes)

## Supported service version

Tested in CI against the images of the Grow Codes stack catalog:
**`signoz/signoz-community:v0.150.0`** with the SigNoz OTel Collector **`v0.144.15`** and
ClickHouse **`25.12.5`**. The query client uses the APIs of that release (`/api/v5/query_range`,
the v4 trace waterfall, the v3 live tail). Only the MIT-licensed community APIs are used. See
[Compatibility](#compatibility) and [docs/compatibility.md](docs/compatibility.md).

## Install

| Language | Package | Install |
| - | - | - |
| TypeScript (Node.js ≥ 22.12) | `@grow-codes/signoz` | `npm install @grow-codes/signoz` |
| Rust (MSRV 1.88) | `grow-signoz` | `cargo add grow-signoz` |
| Go (1.26+) | `github.com/grow-codes-packages/signoz/go` | `go get github.com/grow-codes-packages/signoz/go` |
| Python (≥ 3.10) | `grow-signoz` | `pip install grow-signoz` |
| Java (17+) | `codes.grow.packages:signoz` | see below |

Maven:

```xml
<dependency>
  <groupId>codes.grow.packages</groupId>
  <artifactId>signoz</artifactId>
  <version>0.1.0</version>
</dependency>
```

Gradle (Kotlin DSL):

```kotlin
implementation("codes.grow.packages:signoz:0.1.0")
```

## Quickstart

Every package reads its configuration from the environment or from an explicit config object:

```sh
export GROW_SIGNOZ_URL=http://127.0.0.1:8080             # query API
export GROW_SIGNOZ_API_KEY=...                           # service-account key (queries only)
export GROW_SIGNOZ_OTLP_ENDPOINT=http://127.0.0.1:4318   # OTLP collector (logs + traces)
```

The examples send a log over OTLP and read it back through the query API.

### TypeScript

```ts
import { SignozClient } from "@grow-codes/signoz";

const client = SignozClient.fromEnv();
try {
  console.log((await client.health()).healthy, await client.ping(), "ms");
  const logs = client.logClient({ serviceName: "checkout" });
  logs.logger("orders").info("order placed", { "order.id": 42 });
  await logs.flush();
  const rec = await client.logs.waitForLog("service.name = 'checkout' AND body = 'order placed'");
  console.log(rec.body, rec.attributes);
} finally {
  await client.close();
}
```

### Rust

```rust
use grow_signoz::logging::{attrs, LogClient, LogQueryClient, WaitOptions};
use grow_signoz::{LogOptions, SignozClient};

#[tokio::main]
async fn main() -> grow_signoz::Result<()> {
    let client = SignozClient::from_env()?;
    println!("{} {:?}", client.health().await?.healthy, client.ping().await?);
    let logs = client.log_client(LogOptions::service("checkout"))?;
    logs.logger(Some("orders"), attrs! {}).info("order placed", attrs! {"order.id" => 42});
    logs.flush(None).await?;
    let rec = client
        .query()
        .wait_for_log("service.name = 'checkout' AND body = 'order placed'", WaitOptions::default())
        .await?;
    println!("{} {:?}", rec.body, rec.attributes);
    client.close().await;
    Ok(())
}
```

### Go

```go
package main

import (
	"context"
	"fmt"
	"log"

	signoz "github.com/grow-codes-packages/signoz/go"
)

func main() {
	ctx := context.Background()
	client, err := signoz.NewClientFromEnv()
	if err != nil {
		log.Fatal(err)
	}
	defer client.Close()

	logs, err := client.NewLogClient(signoz.LogOptions{ServiceName: "checkout"})
	if err != nil {
		log.Fatal(err)
	}
	logs.Logger("orders", nil).Info(ctx, "order placed", map[string]signoz.AttrValue{"order.id": 42})
	_ = logs.Flush(ctx)
	rec, err := client.Query().WaitForLog(ctx, "service.name = 'checkout' AND body = 'order placed'", signoz.WaitForLogOptions{})
	if err != nil {
		log.Fatal(err)
	}
	fmt.Println(rec.Body, rec.Attributes)
}
```

### Python

```python
import grow_signoz as sdk

with sdk.SignozClient.from_env() as client:
    print(client.health().healthy, client.ping(), "ms")
    logs = client.log_client(service_name="checkout")
    logs.logger("orders").info("order placed", {"order.id": 42})
    logs.flush(10)
    rec = client.logs.wait_for_log("service.name = 'checkout' AND body = 'order placed'")
    print(rec.body, dict(rec.attributes))
```

Async code uses `AsyncSignozClient` with the same methods (`async with`, `await client.health()`).

### Java

```java
import codes.grow.packages.signoz.LogClientOptions;
import codes.grow.packages.signoz.SignozClient;
import codes.grow.packages.signoz.logging.LogRecord;
import codes.grow.packages.signoz.logging.WaitForLogOptions;
import java.time.Duration;
import java.util.Map;

try (SignozClient client = SignozClient.fromEnv()) {
    System.out.println(client.health().healthy() + " " + client.ping());
    var logs = client.logClient(LogClientOptions.builder().serviceName("checkout").build());
    logs.logger("orders").info("order placed", Map.of("order.id", 42));
    logs.flush(Duration.ofSeconds(10));
    LogRecord rec = client.query().waitForLog(
        "service.name = 'checkout' AND body = 'order placed'",
        WaitForLogOptions.of(Duration.ofSeconds(30), Duration.ofSeconds(1)));
    System.out.println(rec.body() + " " + rec.attributes());
}
```

More complete programs live in [examples/](examples/) — one folder per language.

## Configuration

All settings can be passed in code or through environment variables. Precedence: **explicit value >
`GROW_SIGNOZ_*` variable > vendor variable > default.** A present but invalid value raises a
`ConfigError` that names the variable (never its value).

| Setting | Environment variables (in order) | Default |
| - | - | - |
| `url` — query API | `GROW_SIGNOZ_URL`, `SIGNOZ_URL` | `http://127.0.0.1:8080` |
| `apiKey` — service-account key, sent as `SIGNOZ-API-KEY` (queries, `ping`, tail) | `GROW_SIGNOZ_API_KEY`, `SIGNOZ_API_KEY` | none |
| `otlpEndpoint` — OTLP collector | `GROW_SIGNOZ_OTLP_ENDPOINT`, `SIGNOZ_OTLP_ENDPOINT`, `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://127.0.0.1:4318` (HTTP), `http://127.0.0.1:4317` (gRPC) |
| `otlpProtocol` — `http/protobuf` or `grpc` | `GROW_SIGNOZ_OTLP_PROTOCOL`, `OTEL_EXPORTER_OTLP_PROTOCOL` | `http/protobuf` |
| `ingestionKey` — SigNoz Cloud, sent as `signoz-ingestion-key` | `GROW_SIGNOZ_INGESTION_KEY`, `SIGNOZ_INGESTION_KEY` | none |
| `connectTimeout` | `GROW_SIGNOZ_CONNECT_TIMEOUT` | 10 s |
| `requestTimeout` (per attempt) | `GROW_SIGNOZ_REQUEST_TIMEOUT` | 30 s |
| `totalTimeout` (including retries) | `GROW_SIGNOZ_TOTAL_TIMEOUT` | 120 s |
| `retry.maxRetries` | `GROW_SIGNOZ_MAX_RETRIES` | 3 |
| `tls.caCertPath` | `GROW_SIGNOZ_CA_CERT` | system roots |
| `tls.clientCertPath` / `tls.clientKeyPath` | `GROW_SIGNOZ_CLIENT_CERT` / `GROW_SIGNOZ_CLIENT_KEY` | none |
| `tls.insecureSkipVerify` (tests only) | `GROW_SIGNOZ_INSECURE_SKIP_VERIFY` | `false` |
| `proxy` | `GROW_SIGNOZ_PROXY` | none (environment proxies are ignored) |
| `headers`, `userAgentSuffix`, `hooks`, `logger` | — | none |

Ingest (log client, `initTracing`) needs no API key. Durations accept milliseconds (`1500`) or
units (`500ms`, `10s`, `1m30s`); booleans accept `true/false/1/0/yes/no/on/off`. Field names
follow each language's convention (`api_key` in Python and Rust, `APIKey` in Go). The full
reference is in the [documentation](https://grow-codes-packages.github.io/signoz/configuration/).

## Function overview

<!-- service:function-overview — the service fills this section; keep the heading. -->

The same functions exist in every language, named per language convention (`queryLogs` in
TypeScript and Java, `query_logs` in Python and Rust, `QueryLogs` in Go). Every function has an
example in all five languages on the [Functions](https://grow-codes-packages.github.io/signoz/functions/)
pages.

### Client

| Function | Description |
| - | - |
| `fromEnv`, `health`, `ping`, `serverVersion`, `close` | config from the environment, unauthenticated liveness, authenticated round trip, server version (`UNSUPPORTED` on the community image, which reports none), idempotent shutdown that flushes log clients and tracing; later calls raise `CANCELLED` |

| TypeScript | Rust | Go | Python | Java |
| - | - | - | - | - |
| `await client.ping()` | `client.ping().await?` | `client.Ping(ctx)` | `client.ping()` | `client.ping()` |

### Ingest (OTLP to the collector)

| Function | Description |
| - | - |
| `logClient` / `createLogClient` | batching log client (queue, drop policy, gzip, redaction, trace correlation, `stats()`), logger facade and framework adapters |
| log exporter | `SignozLogExporter` / `OtlpLogExporter` / `NewOTLPLogExporter`: OTLP/HTTP protobuf or OTLP/gRPC, ingestion key, partial success |
| `initTracing` | OpenTelemetry tracing with the OTLP exporter for the collector, plus span helpers, propagation and middleware |

| TypeScript | Rust | Go | Python | Java |
| - | - | - | - | - |
| `client.logClient({ serviceName: "api" })` | `client.log_client(LogOptions::service("api"))?` | `client.NewLogClient(signoz.LogOptions{ServiceName: "api"})` | `client.log_client(service_name="api")` | `client.logClient(LogClientOptions.builder().serviceName("api").build())` |
| `client.initTracing({ serviceName: "api" })` | `client.init_tracing(TracingConfig::new("api"))?` | `client.InitTracing(ctx, signoz.TracingConfig{ServiceName: "api"})` | `client.init_tracing("api")` | `client.initTracing(TracingConfig.builder("api").build())` |

### Logs (query API)

| Function | Description |
| - | - |
| `queryLogs` | raw log query (v5 `query_range`) with filter, window, limit, order, selected fields and a lossless keyset cursor |
| `search`, `tail`, `listStreams`, `listLabels`, `waitForLog` | the shared `LogQueryClient`: search, SSE live tail with reconnect and de-duplication, services, field names, test helper |
| `listLogFields`, `listFieldValues` | field keys (name, context, type) and values |

| TypeScript | Rust | Go | Python | Java |
| - | - | - | - | - |
| `client.query.queryLogs({ filter, limit: 100 })` | `q.query_logs(LogQuery::new(filter).limit(100)).await?` | `q.QueryLogs(ctx, signoz.LogQuery{Filter: f, Limit: 100})` | `client.query.query_logs(f, limit=100)` | `q.queryLogs(LogQuery.builder().filter(f).limit(100).build())` |

### Traces (query API)

| Function | Description |
| - | - |
| `queryTraces` | raw span query (service, operation, filter, offset paging) |
| `getTrace` | full trace from the waterfall (`null`/`None` when unknown; large traces completed with span queries) |
| `searchTraces`, `listServices`, `listOperations`, `getDependencies`, `waitForTrace` | the shared `TraceQueryClient` |

| TypeScript | Rust | Go | Python | Java |
| - | - | - | - | - |
| `await client.traces.getTrace(id)` | `q.get_trace(&id).await?` | `q.GetTrace(ctx, id)` | `client.traces.get_trace(id)` | `q.getTrace(id)` |

### Aggregate, raw queries and filters

| Function | Description |
| - | - |
| `aggregate` | builder for count, count distinct, sum, avg, min, max, percentiles; group by, order, limit; scalar or time series; aliases mapped back from `__result_<i>` |
| `queryRange` | raw `POST /api/v5/query_range` body (formulas, PromQL, ClickHouse SQL) → data + warnings |
| `FilterBuilder` | safe SigNoz filter expressions (`service.name = 'x' AND severity_text IN (...)`), `andFilters`, quoting helpers |

| TypeScript | Rust | Go | Python | Java |
| - | - | - | - | - |
| `client.query.aggregate("traces").count("n").p99("duration_nano", "p99").groupBy("name").run()` | `q.aggregate(AggregateBuilder::new(Signal::Traces).count("n").percentile(99, "duration_nano", "p99").group_by(["name"])).await?` | `q.Aggregate(ctx, signoz.NewAggregate(signoz.SignalTraces).Count("n").Percentile(99, "duration_nano", "p99").GroupBy("name").Query())` | `client.query.aggregate("traces").count("n").p99("duration_nano", "p99").group_by("name").run()` | `q.aggregate(Signal.TRACES).count("n").percentile(99, "duration_nano", "p99").groupBy("name").run()` |
| `new FilterBuilder().eq("service.name", "api").build()` | `FilterBuilder::new().eq("service.name", "api").build()?` | `signoz.NewFilter().Eq("service.name", "api").Build()` | `FilterBuilder().eq("service.name", "api").build()` | `FilterBuilder.create().eq("service.name", "api").build()` |

<!-- /service:function-overview -->

## Testing with Docker

The integration suite runs every public function against a real SigNoz stack in Docker
(`docker/compose.service.yml`: SigNoz `v0.150.0`, the SigNoz OTel Collector and ClickHouse, plus a
one-shot `bootstrap` that creates the admin user, a `signoz-viewer` service account and its API
key). Each language package is packed and installed into a separate consumer project first, so the
tests use the package exactly as you would; then the shared conformance scenarios run.

```sh
make test-docker                     # all five languages
make test-docker LANGS="go python"   # a subset
```

No host ports are published and every run uses its own Compose project, so runs never collide; the
stack is always removed afterwards (`docker compose down -v`). The stack is healthy after about
one minute and needs less than 1 GiB of RAM. Unit tests need no Docker: `make test` or
`make coverage`.

## Compatibility

| | TypeScript | Rust | Go | Python | Java |
| - | - | - | - | - | - |
| Runtime | Node.js ≥ 22.12, ESM + CJS | stable, MSRV 1.88 | 1.26, 1.27 | 3.10 – 3.14 | 17, 21, 25 |
| Async | `Promise` + `AbortSignal` | `async` (tokio), `blocking` feature | `context.Context` | sync + `asyncio` | sync + `CompletableFuture` |
| OTLP logs | HTTP protobuf + gRPC | HTTP protobuf + gRPC | HTTP protobuf + gRPC | HTTP protobuf + gRPC | HTTP protobuf + gRPC |
| Log adapters | pino, winston | `tracing` layer, `log` | `slog`, zap | `logging`, structlog | Logback, Log4j2, JUL |
| SigNoz | `v0.150.0` | `v0.150.0` | `v0.150.0` | `v0.150.0` | `v0.150.0` |

Functions that the community stack cannot serve (SigNoz Cloud ingestion key, very large traces,
server version) and the few documented language differences (tracing protocol defaults,
`insecureSkipVerify` over gRPC) are listed in [docs/compatibility.md](docs/compatibility.md).

## Contributing

Contributions are welcome — please read [CONTRIBUTING.md](CONTRIBUTING.md) and
[AGENTS.md](AGENTS.md) first. All five languages keep the same API; a change lands in all of them
together. Parts of this repository are generated by
[sdk-kit](https://github.com/grow-codes-packages/sdk-kit) and changed there.

## Security

Please report vulnerabilities privately to [opensource@grow.codes](mailto:opensource@grow.codes).
See [SECURITY.md](SECURITY.md).

## Licence

Licensed under the [Apache License, Version 2.0](LICENSE). See [NOTICE](NOTICE) for attributions.

## Trademarks and disclaimer

This is an independent, community-maintained project. It is **not affiliated with, endorsed or
sponsored by SigNoz, Inc.**. SigNoz is a trademark of SigNoz, Inc.; it is used here only to name the
service this SDK connects to. The SigNoz server is licensed separately by its owner
(MIT core + SigNoz Enterprise licence (`ee/`)).

The software is provided "AS IS", without warranties or conditions of any kind. Grow Codes is not
responsible for any damage, data loss, security incident, outage or cost that results from using it.
See [LICENSE](LICENSE) and [NOTICE](NOTICE).

## About Grow Codes

This SDK is built and maintained by [Grow Codes](https://grow.codes). Grow Codes publishes the
client libraries it builds as open source in the
[grow-codes-packages](https://github.com/grow-codes-packages) organisation, generated from one
shared kit so that every service and every language behaves the same way. Learn more at
[grow.codes](https://grow.codes).
