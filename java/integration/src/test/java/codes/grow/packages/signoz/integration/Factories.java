package codes.grow.packages.signoz.integration;

import codes.grow.packages.signoz.SignozClient;
import codes.grow.packages.signoz.core.EnvReader;
import codes.grow.packages.signoz.testing.ConformanceRunner;

/**
 * Conformance targets of this service (owned by the service repository; generated once).
 *
 * <p>Register one target per category: {@code core} is the client; add {@code secrets}, {@code
 * logging} or {@code tracing} targets as the service implements them. Categories without a target
 * do not run. Every capability left out of {@link #CAPABILITIES} must be justified in {@code
 * docs/compatibility.md}.
 */
final class Factories {
  /** Capabilities the deployment under test offers (see the scenario {@code requires}). */
  static final String[] CAPABILITIES = {"version"};

  private Factories() {}

  static void register(ConformanceRunner.Builder runner, EnvReader env) {
    runner.target("core", scenario -> SignozClient.builder().fromEnv(env).build());
  }
}
