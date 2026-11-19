package codes.grow.packages.signoz;

import codes.grow.packages.signoz.core.ErrorCode;
import codes.grow.packages.signoz.core.HttpTransport;
import codes.grow.packages.signoz.core.Json;
import codes.grow.packages.signoz.core.Redaction;
import codes.grow.packages.signoz.core.SignozException;
import codes.grow.packages.signoz.core.TransportRequest;
import codes.grow.packages.signoz.core.TransportResponse;
import codes.grow.packages.signoz.core.UnavailableException;
import codes.grow.packages.signoz.core.UnsupportedException;
import codes.grow.packages.signoz.core.ValidationException;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Authenticated request helper of the query API: adds {@code SIGNOZ-API-KEY}, marks the read-only
 * POST queries idempotent, maps the SPA index page (served with 200 for unknown routes) to {@link
 * UnsupportedException} and unwraps the {@code {"status","data"}} envelope.
 */
final class SignozApi {
  private final SignozConfig config;
  private final HttpTransport transport;

  SignozApi(SignozConfig config, HttpTransport transport) {
    this.config = config;
    this.transport = transport;
  }

  SignozConfig config() {
    return config;
  }

  HttpTransport transport() {
    return transport;
  }

  /** Adds the API key header when one is configured. */
  TransportRequest.Builder authorize(TransportRequest.Builder b) {
    config.apiKey().ifPresent(k -> b.header(SignozConfig.API_KEY_HEADER, k.reveal()));
    return b;
  }

  /** Redacts every known secret from a text. */
  String redact(String text) {
    return Redaction.redactText(text, config.knownSecrets());
  }

  /** Logs a warning through the configured logger (never throws). */
  void warn(String message, Map<String, Object> fields) {
    try {
      config.logger().warn(message, Redaction.redactFields(fields));
    } catch (RuntimeException ignored) {
      // the logger must never break a call
    }
  }

  /**
   * Sends an authenticated, idempotent request and returns the decoded body.
   *
   * @param op operation name
   * @param b request builder
   * @param envelope unwrap {@code data} from the {@code {"status","data"}} envelope
   * @return a future with the JSON (the envelope's {@code data}, or the whole body)
   */
  CompletableFuture<JsonNode> call(String op, TransportRequest.Builder b, boolean envelope) {
    TransportRequest req = authorize(b).operation(op).idempotent(true).build();
    return transport
        .sendAsync(req)
        .thenApply(
            r -> {
              checkNotHtml(op, r);
              JsonNode body = json(op, r);
              if (!envelope) {
                return body;
              }
              String status = body.path("status").asText("");
              if (!status.isEmpty() && !status.equals("success")) {
                throw new UnavailableException(
                    op + ": unexpected response status " + redact(status),
                    r.status(),
                    r.requestId().orElse(null),
                    null,
                    null);
              }
              return body.path("data");
            });
  }

  /**
   * Fails with {@link UnsupportedException} when the response is the HTML index page.
   *
   * @param op operation name
   * @param r response
   */
  static void checkNotHtml(String op, TransportResponse r) {
    String ct = r.header("Content-Type").orElse("").toLowerCase(Locale.ROOT);
    String head = new String(r.body(), 0, Math.min(r.body().length, 64), StandardCharsets.UTF_8);
    String lower = head.trim().toLowerCase(Locale.ROOT);
    if (ct.contains("text/html") || lower.startsWith("<!") || lower.startsWith("<html")) {
      throw (UnsupportedException)
          SignozException.of(
              ErrorCode.UNSUPPORTED,
              op + ": endpoint not available on this server version",
              r.status(),
              r.requestId().orElse(null),
              null,
              null);
    }
  }

  /**
   * Decodes a body as JSON; invalid JSON is an {@link UnavailableException}.
   *
   * @param op operation name
   * @param r response
   * @return the tree
   */
  static JsonNode json(String op, TransportResponse r) {
    try {
      return Json.tree(r.body());
    } catch (ValidationException e) {
      throw new UnavailableException(
          op + ": invalid JSON response (HTTP " + r.status() + ")",
          r.status(),
          r.requestId().orElse(null),
          null,
          null);
    }
  }

  /**
   * Runs a future-producing call; a synchronous exception becomes a failed future.
   *
   * @param <T> result type
   * @param call the call
   * @return the future
   */
  static <T> CompletableFuture<T> defer(Supplier<CompletableFuture<T>> call) {
    try {
      return call.get();
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
  }
}
