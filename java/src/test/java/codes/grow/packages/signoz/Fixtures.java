package codes.grow.packages.signoz;

import codes.grow.packages.signoz.core.Json;
import codes.grow.packages.signoz.core.MockServer;
import codes.grow.packages.signoz.core.RetryPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Recorded SigNoz responses (fixtures/) and mock helpers. */
final class Fixtures {
  /** Fixed "now" of the unit tests (just after the fixture data). */
  static final Instant NOW = Instant.parse("2026-11-18T20:30:00Z");

  static final String KEY = "test-api-key-123456";

  private Fixtures() {}

  static String read(String name) {
    try (InputStream in = Fixtures.class.getResourceAsStream("/fixtures/" + name)) {
      if (in == null) {
        throw new IllegalArgumentException("missing fixture " + name);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static JsonNode body(MockServer.Recorded r) {
    return Json.tree(r.body().getBytes(StandardCharsets.UTF_8));
  }

  static JsonNode spec(MockServer.Recorded r) {
    return body(r).path("compositeQuery").path("queries").get(0).path("spec");
  }

  static SignozConfig.Builder config(MockServer server) {
    return SignozConfig.builder()
        .url(server.url())
        .apiKey(KEY)
        .retry(RetryPolicy.builder().initialBackoff(Duration.ofMillis(1)).maxRetries(1).build());
  }

  static SignozClient client(MockServer server) {
    return SignozClient.create(config(server).build(), Clock.fixed(NOW, ZoneOffset.UTC));
  }

  /**
   * Routes requests by path prefix; the responder gets the request URI and returns the JSON body
   * ({@code null} = 404 not found).
   */
  static void route(MockServer server, Map<String, Function<String, String>> routes) {
    server.fallback(
        ex -> {
          String path = ex.getRequestURI().getPath();
          for (Map.Entry<String, Function<String, String>> e : routes.entrySet()) {
            if (path.startsWith(e.getKey())) {
              String out = e.getValue().apply(ex.getRequestURI().toString());
              MockServer.send(ex, out == null ? 404 : 200, out == null ? NOT_FOUND : out);
              return;
            }
          }
          MockServer.send(ex, 404, NOT_FOUND);
        });
  }

  static final String NOT_FOUND =
      "{\"status\":\"error\",\"error\":{\"code\":\"not_found\",\"message\":\"trace not found\"}}";

  static List<String> uris(MockServer server) {
    return server.requests().stream().map(MockServer.Recorded::uri).toList();
  }
}
