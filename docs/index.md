# SigNoz SDK

[![Grow Codes](https://img.shields.io/badge/Grow%20Codes-grow.codes-2ea44f)](https://grow.codes)
[![CI](https://github.com/grow-codes-packages/signoz/actions/workflows/ci.yml/badge.svg)](https://github.com/grow-codes-packages/signoz/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](license.md)

One API for **SigNoz** in five languages: TypeScript, Rust, Go, Python and Java. Every package
has the same functions, options, defaults, error codes and behaviour, tested against a real
`signoz/signoz-community:v0.150.0 (+ signoz-otel-collector + ClickHouse)` in Docker.

| Language | Package | Coverage |
| - | - | - |
| TypeScript | [`@grow-codes/signoz`](https://www.npmjs.com/package/@grow-codes/signoz) | ![TypeScript coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/signoz/badges/coverage-typescript.json) |
| Rust | [`grow-signoz`](https://crates.io/crates/grow-signoz) | ![Rust coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/signoz/badges/coverage-rust.json) |
| Go | [`github.com/grow-codes-packages/signoz/go`](https://pkg.go.dev/github.com/grow-codes-packages/signoz/go) | ![Go coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/signoz/badges/coverage-go.json) |
| Python | [`grow-signoz`](https://pypi.org/project/grow-signoz/) | ![Python coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/signoz/badges/coverage-python.json) |
| Java | [`codes.grow.packages:signoz`](https://central.sonatype.com/artifact/codes.grow.packages/signoz) | ![Java coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/signoz/badges/coverage-java.json) |

## What you get

- **Client** with health, authenticated ping and server version ([Client](client.md)).
- **Configuration** from code or `GROW_SIGNOZ_*` / vendor environment variables, immutable
  and validated ([Configuration](configuration.md)).
- **Retries** with full-jitter backoff and `Retry-After`, connect/request/total timeouts and
  cancellation in every language.
- **Typed errors** with one set of codes across languages ([Error handling](errors.md)).
- **Redaction everywhere**: credentials never appear in errors, logs, hooks or `toString`
  ([Redaction](redaction.md)).
- **Logging**: batching log client, logger facade, redaction processor, framework adapters and
  log search ([Logging](logging.md)).
- **Tracing**: OpenTelemetry setup, span helpers, propagation, framework adapters and trace
  queries ([Tracing](tracing.md)).
- **Service functions** for the SigNoz API ([Functions](functions.md)).

Start with [Install](install.md) and the [Quickstart](quickstart.md).

!!! note "Independent project"
    This SDK is made by [Grow Codes](https://grow.codes). SigNoz is a trademark of
    SigNoz, Inc.. This project is not affiliated with or endorsed by SigNoz, Inc.. See
    [License & disclaimer](license.md).
