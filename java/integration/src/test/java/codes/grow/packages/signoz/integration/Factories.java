package codes.grow.packages.signoz.integration;

import codes.grow.packages.signoz.LogClientOptions;
import codes.grow.packages.signoz.SignozClient;
import codes.grow.packages.signoz.core.EnvReader;
import codes.grow.packages.signoz.logging.BatchOptions;
import codes.grow.packages.signoz.logging.BatchingLogClient;
import codes.grow.packages.signoz.testing.ConformanceRunner;
import codes.grow.packages.signoz.testing.logging.LoggingTarget;
import codes.grow.packages.signoz.testing.tracing.TracingTarget;
import codes.grow.packages.signoz.tracing.TracingConfig;
import codes.grow.packages.signoz.tracing.TracingHandle;

/**
 * Conformance targets of this service (owned by the service repository).
 *
 * <p>{@code core} is the client, {@code logging} a batching OTLP {@code LogClient} to the collector
 * with the query client, {@code tracing} an OTLP/HTTP tracer provider to the collector with the
 * query client. {@code version} is left out: the community image reports an empty version, so
 * {@code serverVersion()} raises {@code UnsupportedException} (docs/compatibility.md). The query
 * client lists a service as soon as its spans are queryable (v5 aggregation over the spans table),
 * so the tracing scenarios need no warm-up.
 */
final class Factories {
  /** Capabilities the deployment under test offers (see the scenario {@code requires}). */
  static final String[] CAPABILITIES = {
    "query", "tail", "labels", "streams", "services", "dependencies"
  };

  /** Service name of the logging and tracing scenarios. */
  static final String SERVICE_NAME = "grow-conformance-java";

  private Factories() {}

  static void register(ConformanceRunner.Builder runner, EnvReader env) {
    runner.serviceName(SERVICE_NAME);
    runner.target("core", scenario -> SignozClient.builder().fromEnv(env).build());
    runner.target(
        "logging",
        scenario -> {
          SignozClient client = SignozClient.builder().fromEnv(env).build();
          BatchingLogClient logs =
              client.logClient(
                  LogClientOptions.builder()
                      .serviceName(SERVICE_NAME)
                      .batchOptions(LoggingTarget.batchOptions(scenario, BatchOptions.defaults()))
                      .build());
          return LoggingTarget.of(logs, client.query());
        });
    runner.target(
        "tracing",
        scenario -> {
          SignozClient client = SignozClient.builder().fromEnv(env).build();
          TracingHandle handle =
              client.initTracing(TracingConfig.builder(SERVICE_NAME).setGlobal(false).build());
          return TracingTarget.of(handle, client.query());
        });
  }
}
