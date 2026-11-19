# Quickstart

Send a log and a trace to the SigNoz collector over OTLP, then read them back through the query
API. The client reads `GROW_SIGNOZ_URL`, `GROW_SIGNOZ_API_KEY` and `GROW_SIGNOZ_OTLP_ENDPOINT`
(and the vendor variables listed in [Configuration](configuration.md)), or takes the values in
code.

```sh
export GROW_SIGNOZ_URL=http://127.0.0.1:8080             # query API
export GROW_SIGNOZ_API_KEY=...                           # service-account key; never commit it
export GROW_SIGNOZ_OTLP_ENDPOINT=http://127.0.0.1:4318   # OTLP collector (HTTP)
```

=== "TypeScript"

    ```ts
    import { SignozClient, withSpan } from "@grow-codes/signoz";

    const client = SignozClient.fromEnv();
    try {
      console.log("healthy:", (await client.health()).healthy, "ping:", await client.ping(), "ms");

      const logs = client.logClient({ serviceName: "checkout" });
      logs.logger("orders").info("order placed", { "order.id": 42 });
      await logs.flush();
      const record = await client.logs.waitForLog("service.name = 'checkout' AND body = 'order placed'");
      console.log(record.body, record.attributes);

      const tracing = client.initTracing({ serviceName: "checkout" });
      const traceId = await withSpan("GET /checkout", async (span) => span.spanContext().traceId);
      await tracing.forceFlush();
      const trace = await client.traces.waitForTrace(traceId);
      console.log(trace.spans.map((s) => s.name));
    } finally {
      await client.close(); // flushes logs, shuts tracing down
    }
    ```

=== "Rust"

    ```rust
    use grow_signoz::logging::{attrs, LogClient, LogQueryClient, WaitOptions};
    use grow_signoz::tracing::{TraceQueryClient, TracingConfig, WaitForTraceOptions};
    use grow_signoz::{LogOptions, SignozClient};
    use opentelemetry::trace::{Span as _, Tracer as _};

    #[tokio::main]
    async fn main() -> grow_signoz::Result<()> {
        let client = SignozClient::from_env()?;
        println!("healthy: {}", client.health().await?.healthy);
        println!("ping: {:?}", client.ping().await?);

        let logs = client.log_client(LogOptions::service("checkout"))?;
        logs.logger(Some("orders"), attrs! {}).info("order placed", attrs! {"order.id" => 42});
        logs.flush(None).await?;
        let q = client.query();
        let record = q
            .wait_for_log("service.name = 'checkout' AND body = 'order placed'", WaitOptions::default())
            .await?;
        println!("{} {:?}", record.body, record.attributes);

        let tracing = client.init_tracing(TracingConfig::new("checkout"))?;
        let tracer = tracing.tracer("checkout", None);
        let mut span = tracer.start("GET /checkout");
        let trace_id = span.span_context().trace_id().to_string();
        span.end();
        tracing.force_flush(None).await?;
        let trace = q.wait_for_trace(&trace_id, WaitForTraceOptions::default()).await?;
        println!("{} span(s)", trace.spans.len());
        client.close().await;
        Ok(())
    }
    ```

=== "Go"

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

        health, _ := client.Health(ctx)
        latency, err := client.Ping(ctx)
        if err != nil {
            log.Fatal(err) // errors.Is(err, signoz.ErrAuthentication) for a bad key
        }
        fmt.Println("healthy:", health.Healthy, "ping:", latency)

        logs, err := client.NewLogClient(signoz.LogOptions{ServiceName: "checkout"})
        if err != nil {
            log.Fatal(err)
        }
        logs.Logger("orders", nil).Info(ctx, "order placed", map[string]signoz.AttrValue{"order.id": 42})
        _ = logs.Flush(ctx)
        q := client.Query()
        rec, err := q.WaitForLog(ctx, "service.name = 'checkout' AND body = 'order placed'", signoz.WaitForLogOptions{})
        if err != nil {
            log.Fatal(err)
        }
        fmt.Println(rec.Body, rec.Attributes)

        tracing, err := client.InitTracing(ctx, signoz.TracingConfig{ServiceName: "checkout"})
        if err != nil {
            log.Fatal(err)
        }
        _, span := tracing.Tracer("checkout").Start(ctx, "GET /checkout")
        span.End()
        _ = tracing.ForceFlush(ctx)
        trace, err := q.WaitForTrace(ctx, span.SpanContext().TraceID().String(), signoz.WaitForTraceOptions{})
        if err != nil {
            log.Fatal(err)
        }
        fmt.Println(len(trace.Spans), "span(s)")
    }
    ```

=== "Python"

    ```python
    import grow_signoz as sdk

    with sdk.SignozClient.from_env() as client:
        print("healthy:", client.health().healthy, "ping:", client.ping(), "ms")

        logs = client.log_client(service_name="checkout")
        logs.logger("orders").info("order placed", {"order.id": 42})
        logs.flush(10)
        record = client.logs.wait_for_log("service.name = 'checkout' AND body = 'order placed'")
        print(record.body, dict(record.attributes))

        tracing = client.init_tracing("checkout", set_global=False)
        with tracing.tracer("checkout").start_as_current_span("GET /checkout") as span:
            trace_id = format(span.get_span_context().trace_id, "032x")
        tracing.force_flush(10)
        trace = client.traces.wait_for_trace(trace_id)
        print([s.name for s in trace.spans])
    ```

    Async code uses `AsyncSignozClient` with the same methods (`await client.health()`, …).

=== "Java"

    ```java
    import codes.grow.packages.signoz.LogClientOptions;
    import codes.grow.packages.signoz.SignozClient;
    import codes.grow.packages.signoz.logging.BatchingLogClient;
    import codes.grow.packages.signoz.logging.LogRecord;
    import codes.grow.packages.signoz.logging.WaitForLogOptions;
    import codes.grow.packages.signoz.tracing.TracingConfig;
    import codes.grow.packages.signoz.tracing.TracingHandle;
    import codes.grow.packages.signoz.tracing.query.Trace;
    import codes.grow.packages.signoz.tracing.query.WaitForTraceOptions;
    import io.opentelemetry.api.trace.Span;
    import java.time.Duration;
    import java.util.Map;

    public final class Quickstart {
      public static void main(String[] args) {
        try (SignozClient client = SignozClient.fromEnv()) {
          System.out.println("healthy: " + client.health().healthy() + ", ping: " + client.ping());

          BatchingLogClient logs =
              client.logClient(LogClientOptions.builder().serviceName("checkout").build());
          logs.logger("orders").info("order placed", Map.of("order.id", 42));
          logs.flush(Duration.ofSeconds(10));
          LogRecord record =
              client.query().waitForLog(
                  "service.name = 'checkout' AND body = 'order placed'",
                  WaitForLogOptions.of(Duration.ofSeconds(30), Duration.ofSeconds(1)));
          System.out.println(record.body() + " " + record.attributes());

          TracingHandle tracing = client.initTracing(TracingConfig.builder("checkout").build());
          Span span = tracing.tracer("checkout").spanBuilder("GET /checkout").startSpan();
          span.end();
          tracing.forceFlush();
          Trace trace =
              client.traces().waitForTrace(
                  span.getSpanContext().getTraceId(),
                  new WaitForTraceOptions(Duration.ofSeconds(60), Duration.ofSeconds(1), 1));
          System.out.println(trace.spans().size() + " span(s)");
        }
      }
    }
    ```

## Next steps

- [Configuration](configuration.md): endpoints, API and ingestion keys, timeouts, retries, TLS.
- [Functions](functions.md): every SigNoz function with examples in all five languages.
- [Logging](logging.md) and [Tracing](tracing.md): the shared category interfaces and adapters.
- [Error handling](errors.md): error codes and retries.
