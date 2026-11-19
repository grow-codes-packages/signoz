# Ingest: logs and traces

Logs and spans go to the **SigNoz OTLP collector** (`otlpEndpoint`), not to the query API. No API
key is needed; SigNoz Cloud needs the `ingestionKey`, which the SDK sends as the
`signoz-ingestion-key` header (OTLP/HTTP) or gRPC metadata on every export.

| Protocol | Logs | Traces |
| - | - | - |
| `http/protobuf` (default) | `POST {otlpEndpoint}/v1/logs`, protobuf body, gzip | `POST {otlpEndpoint}/v1/traces` (official OpenTelemetry exporter) |
| `grpc` | `opentelemetry.proto.collector.logs.v1.LogsService/Export` | OTLP/gRPC trace exporter |

When the protocol of a log client or tracing config differs from `otlpProtocol` and no endpoint
is given, the default port is swapped (4318 ↔ 4317) on the configured host.

## Log client: `logClient` / `createLogClient`

A batching [`LogClient`](../logging.md) (queue, batch size and bytes, flush interval, drop policy,
redaction, `stats()`) that exports OTLP logs. The client-bound form is flushed and closed by
`client.close()`; the free function takes a config (default: from the environment). Options:
service name/version/environment (resource attributes), protocol, endpoint, extra headers, batch
options, extra resource attributes, redaction and automatic trace correlation (on by default:
`trace_id`/`span_id` of the active OpenTelemetry span).

=== "TypeScript"

    ```ts
    const logs = client.logClient({ serviceName: "checkout", environment: "prod" });
    // or: createLogClient(SignozConfig.fromEnv(), { serviceName: "checkout" })
    const log = logs.logger("payments", { region: "eu" });
    log.info("order placed", { orderId: 42 });
    log.error("payment failed", { orderId: 42, password: "never leaves the process" });
    await logs.flush();
    console.log(logs.stats()); // queued, sent, dropped, failed, retries
    ```

=== "Rust"

    ```rust
    use grow_signoz::logging::{attrs, LogClient};
    use grow_signoz::LogOptions;

    let logs = client.log_client(LogOptions::service("checkout").environment("prod"))?;
    // or: grow_signoz::new_log_client(&config, LogOptions::service("checkout"))?
    let log = logs.logger(Some("payments"), attrs! {"region" => "eu"});
    log.info("order placed", attrs! {"order.id" => 42});
    log.error("payment failed", attrs! {"order.id" => 42, "password" => "redacted"});
    logs.flush(None).await?;
    ```

=== "Go"

    ```go
    logs, err := client.NewLogClient(signoz.LogOptions{ServiceName: "checkout", Environment: "prod"})
    // or: signoz.NewLogClient(cfg, signoz.LogOptions{...})
    if err != nil {
        return err
    }
    log := logs.Logger("payments", map[string]signoz.AttrValue{"region": "eu"})
    log.Info(ctx, "order placed", map[string]signoz.AttrValue{"order.id": 42})
    log.Error(ctx, "payment failed", map[string]signoz.AttrValue{"password": "redacted"})
    _ = logs.Flush(ctx)
    // slog: slog.New(signoz.NewSlogHandler(logs, nil)); see Logging for the adapters
    ```

=== "Python"

    ```python
    logs = client.log_client(service_name="checkout", environment="prod")
    # or: sdk.create_log_client(config, service_name="checkout")
    log = logs.logger("payments", {"region": "eu"})
    log.info("order placed", {"order.id": 42})
    log.error("payment failed", {"order.id": 42, "password": "redacted"})
    logs.flush(10)
    # logging.Handler / structlog processor: see Logging
    ```

=== "Java"

    ```java
    BatchingLogClient logs =
        client.logClient(
            LogClientOptions.builder().serviceName("checkout").environment("prod").build());
    // or: SignozClient.createLogClient(config, options)
    logs.logger("payments").info("order placed", Map.of("order.id", 42));
    logs.send(LogInput.of(Severity.ERROR, "payment failed", Map.of("order.id", 42)));
    logs.flush(Duration.ofSeconds(10));
    ```

A record without a severity is sent (and read back) as **INFO** in every language.

## OTLP log exporter

The exporter behind the log client, for your own pipeline: one export call per batch, OTLP/HTTP
protobuf (optional gzip) or OTLP/gRPC. A partial success (`rejectedLogRecords`) is reported in
the export result; HTTP/gRPC errors map to the SDK error codes.

=== "TypeScript"

    ```ts
    import { SignozLogExporter, Severity } from "@grow-codes/signoz";

    const exporter = new SignozLogExporter(client.config);
    const result = await exporter.export(
      [{ timestamp: new Date(), severity: Severity.Warn, severityText: "WARN", body: "disk 91%",
         attributes: {}, resource: { "service.name": "agent" } }],
      { gzip: true },
    ); // { accepted, rejected }
    await exporter.shutdown();
    ```

=== "Rust"

    ```rust
    use grow_signoz::logging::{ExportOptions, LogExporter};
    use grow_signoz::OtlpLogExporter;

    let exporter = OtlpLogExporter::new(client.config())?;
    let result = exporter.export(&records, ExportOptions::default()).await?;
    exporter.shutdown().await?;
    ```

=== "Go"

    ```go
    exp, err := signoz.NewOTLPLogExporter(cfg)
    if err != nil {
        return err
    }
    res, err := exp.Export(ctx, records, signoz.ExportOptions{Gzip: true}) // res.Accepted, res.Rejected
    _ = exp.Shutdown(ctx)
    ```

=== "Python"

    ```python
    exporter = sdk.SignozLogExporter(client.config)
    result = exporter.export(records)
    exporter.shutdown()
    ```

=== "Java"

    ```java
    SignozLogExporter exporter = client.logExporter();
    ExportResult result = exporter.export(records, new ExportOptions(true, Duration.ofSeconds(10))).join();
    exporter.close();
    ```

## Tracing: `initTracing`

`initTracing` sets up the official OpenTelemetry SDK of each language with the OTLP exporter for
the collector; everything else (sampler, propagators, batch settings, span helpers, framework
adapters) is the shared [Tracing](../tracing.md) setup. Values in the tracing config (endpoint,
headers, TLS) win over the SigNoz config; the `native` protocol raises `CONFIG` (SigNoz ingests
OTLP only).

**Protocol rule.** Without an explicit `endpoint` in the tracing config, the protocol is the
SigNoz config's `otlpProtocol` unless the tracing config picks one. In Rust, Python and Java the
tracing config's default protocol (`http/protobuf`) cannot be told apart from "not set", so only
an explicit `grpc` overrides an HTTP SigNoz config there; in TypeScript and Go an explicit
`http/protobuf` also overrides a gRPC SigNoz config. See [Compatibility](../compatibility.md).

=== "TypeScript"

    ```ts
    import { withSpan } from "@grow-codes/signoz";

    const tracing = client.initTracing({ serviceName: "checkout", serviceVersion: "1.4.0" });
    // or: initTracing({ serviceName: "checkout", signoz: config })
    await withSpan("charge card", async (span) => span.setAttribute("order.id", 42));
    await tracing.shutdown();
    ```

=== "Rust"

    ```rust
    use grow_signoz::tracing::{traced, TracingConfig};

    let tracing = client.init_tracing(TracingConfig::new("checkout"))?;
    // or: grow_signoz::init_tracing(TracingConfig::new("checkout"))? (config from the environment)
    traced("charge card", async { Ok::<_, std::io::Error>(()) }).await?;
    tracing.shutdown().await?;
    ```

=== "Go"

    ```go
    tracing, err := client.InitTracing(ctx, signoz.TracingConfig{ServiceName: "checkout"})
    // or: signoz.InitTracing(ctx, signoz.TracingConfig{...}) (config from the environment)
    if err != nil {
        return err
    }
    defer tracing.Shutdown(context.Background())
    ctx, span := tracing.Tracer("checkout").Start(ctx, "charge card")
    span.End()
    ```

=== "Python"

    ```python
    from grow_signoz import TracingConfig, with_span

    tracing = client.init_tracing(TracingConfig(service_name="checkout"))
    # or: sdk.init_tracing("checkout", signoz=config)
    with with_span("charge card"):
        ...
    tracing.shutdown()
    ```

=== "Java"

    ```java
    TracingHandle tracing = client.initTracing(TracingConfig.builder("checkout").build());
    // or: SignozTracing.initTracing(config, TracingConfig.builder("checkout").build())
    Span span = tracing.tracer("checkout").spanBuilder("charge card").startSpan();
    span.end();
    tracing.shutdown();
    ```
