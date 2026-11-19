package codes.grow.packages.signoz.examples;

import codes.grow.packages.signoz.AggregateResult;
import codes.grow.packages.signoz.FilterBuilder;
import codes.grow.packages.signoz.LogClientOptions;
import codes.grow.packages.signoz.LogQuery;
import codes.grow.packages.signoz.LogQueryResult;
import codes.grow.packages.signoz.Signal;
import codes.grow.packages.signoz.SignozClient;
import codes.grow.packages.signoz.SignozQueryClient;
import codes.grow.packages.signoz.logging.BatchingLogClient;
import codes.grow.packages.signoz.logging.LogInput;
import codes.grow.packages.signoz.logging.LogRecord;
import codes.grow.packages.signoz.logging.Severity;
import codes.grow.packages.signoz.logging.WaitForLogOptions;
import codes.grow.packages.signoz.tracing.TracingConfig;
import codes.grow.packages.signoz.tracing.TracingHandle;
import codes.grow.packages.signoz.tracing.query.Trace;
import codes.grow.packages.signoz.tracing.query.TraceSearchQuery;
import codes.grow.packages.signoz.tracing.query.WaitForTraceOptions;
import io.opentelemetry.api.trace.Span;
import java.time.Duration;
import java.util.Map;

/**
 * Sends logs and a trace to the SigNoz collector over OTLP, then reads them back through the query
 * API: filtered log queries with keyset paging, the trace waterfall, trace search, services and a
 * count aggregate.
 */
public final class QueryExample {
  private QueryExample() {}

  /**
   * Runs the example.
   *
   * @param args unused
   */
  public static void main(String[] args) {
    String service = "java-example-" + Long.toHexString(System.nanoTime());
    try (SignozClient client = SignozClient.fromEnv()) {
      SignozQueryClient q = client.query();

      // 1. logs: OTLP to the collector (no API key needed), closed (flushed) by client.close()
      BatchingLogClient logs =
          client.logClient(LogClientOptions.builder().serviceName(service).build());
      logs.send(LogInput.of(Severity.ERROR, "payment failed", Map.of("order.id", 42)));
      logs.logger("checkout").info("order created", Map.of("order.id", 43));
      logs.flush(Duration.ofSeconds(10));

      // 2. read them back with a filter expression rendered by FilterBuilder
      String filter =
          FilterBuilder.create()
              .eq("service.name", service)
              .in("severity_text", "ERROR", "FATAL")
              .build();
      LogRecord first = q.waitForLog(filter, WaitForLogOptions.of(Duration.ofSeconds(60), Duration.ofSeconds(1)));
      System.out.println("found: " + first.body() + " " + first.attributes());

      // keyset paging: pass nextCursor back until it is empty
      String cursor = null;
      do {
        LogQueryResult page =
            q.queryLogs(
                LogQuery.builder()
                    .filter(FilterBuilder.create().eq("service.name", service))
                    .limit(1)
                    .cursor(cursor)
                    .build());
        page.records().forEach(r -> System.out.println("page: " + r.severityText() + " " + r.body()));
        cursor = page.nextCursor().orElse(null);
      } while (cursor != null);

      // 3. a trace: OpenTelemetry spans exported to the collector
      TracingHandle tracing =
          client.initTracing(TracingConfig.builder(service).setGlobal(false).build());
      Span span = tracing.tracer("example").spanBuilder("GET /checkout").startSpan();
      String traceId = span.getSpanContext().getTraceId();
      span.end();
      tracing.forceFlush();
      Trace trace =
          client
              .traces()
              .waitForTrace(traceId, new WaitForTraceOptions(Duration.ofSeconds(60), Duration.ofSeconds(1), 1));
      System.out.println("trace " + trace.traceId() + " has " + trace.spans().size() + " span(s)");
      System.out.println("search: " + q.searchTraces(TraceSearchQuery.forService(service)));
      System.out.println("services: " + q.listServices());
      System.out.println("operations: " + q.listOperations(service));

      // 4. an aggregate: count of log records per severity
      AggregateResult counts =
          q.aggregate(Signal.LOGS)
              .filter(FilterBuilder.create().eq("service.name", service))
              .count("records")
              .groupBy("severity_text")
              .run();
      counts.rows().forEach(r -> System.out.println(r.groups() + " -> " + r.values()));
    }
  }
}
