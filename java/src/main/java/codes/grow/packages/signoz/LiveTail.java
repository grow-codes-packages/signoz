package codes.grow.packages.signoz;

import codes.grow.packages.signoz.core.CancelledException;
import codes.grow.packages.signoz.core.ErrorCode;
import codes.grow.packages.signoz.core.Hooks;
import codes.grow.packages.signoz.core.HttpTransport;
import codes.grow.packages.signoz.core.Json;
import codes.grow.packages.signoz.core.Redaction;
import codes.grow.packages.signoz.core.SignozException;
import codes.grow.packages.signoz.core.TransportConfig;
import codes.grow.packages.signoz.core.UnavailableException;
import codes.grow.packages.signoz.core.ValidationException;
import codes.grow.packages.signoz.logging.LogRecord;
import codes.grow.packages.signoz.logging.TailHandle;
import codes.grow.packages.signoz.logging.TailOptions;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A running live tail ({@code GET /api/v3/logs/livetail}, server-sent events): one reader thread,
 * reconnects from the last seen timestamp and de-duplicates by record id.
 */
final class LiveTail implements TailHandle {
  /** Ids remembered for de-duplication across reconnects. */
  static final int MAX_SEEN = 10_000;

  private static final String OP = "logs.tail";

  private final SignozApi api;
  private final String filter;
  private final Consumer<LogRecord> onRecord;
  private final TailOptions options;
  private final Consumer<LiveTail> onStop;
  private final HttpClient http;
  private final Set<String> seen = new HashSet<>();
  private final ArrayDeque<String> seenOrder = new ArrayDeque<>();
  private volatile long lastMs;
  private volatile boolean stopped;
  private volatile InputStream current;
  private Thread reader;

  LiveTail(
      SignozApi api,
      String filter,
      Consumer<LogRecord> onRecord,
      TailOptions options,
      long startMs,
      Consumer<LiveTail> onStop) {
    this.api = api;
    this.filter = filter == null ? "" : filter.trim();
    this.onRecord = onRecord;
    this.options = options;
    this.onStop = onStop;
    this.lastMs = startMs;
    this.http = client(api.transport().config());
  }

  static HttpClient client(TransportConfig cfg) {
    HttpClient.Builder b =
        HttpClient.newBuilder()
            .connectTimeout(cfg.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER);
    if (cfg.tls().isCustom()) {
      b.sslContext(cfg.tls().sslContext());
    }
    cfg.proxy()
        .ifPresent(
            p -> {
              URI u = URI.create(p);
              int port = u.getPort() > 0 ? u.getPort() : "https".equals(u.getScheme()) ? 443 : 80;
              b.proxy(ProxySelector.of(new InetSocketAddress(u.getHost(), port)));
            });
    return b.build();
  }

  /** Opens the first connection (errors are thrown) and starts the reader thread. */
  void start() {
    InputStream body = connect();
    current = body;
    reader = new Thread(() -> run(body), "signoz-live-tail");
    reader.setDaemon(true);
    reader.start();
  }

  long lastMs() {
    return lastMs;
  }

  /** Opens the event stream from {@link #lastMs}. */
  InputStream connect() {
    if (api.transport().isClosed() || stopped) {
      throw new CancelledException(OP + ": client is closed");
    }
    TransportConfig cfg = api.transport().config();
    Map<String, List<String>> query = new LinkedHashMap<>();
    query.put("start", List.of(Long.toString(lastMs)));
    if (!filter.isEmpty()) {
      query.put("filter", List.of(filter));
    }
    String url = api.transport().resolve("/api/v3/logs/livetail", query);
    String safeUrl = api.redact(Redaction.redactUrl(url));
    HttpRequest.Builder rb =
        HttpRequest.newBuilder(URI.create(url))
            .GET()
            .header("User-Agent", cfg.userAgent())
            .header("Accept", "text/event-stream");
    Map<String, String> headers = new LinkedHashMap<>(cfg.headers());
    api.config().apiKey().ifPresent(k -> headers.put(SignozConfig.API_KEY_HEADER, k.reveal()));
    headers.forEach(rb::header);
    Map<String, List<String>> sent = new LinkedHashMap<>();
    headers.forEach((k, v) -> sent.put(k, List.of(v)));
    Hooks hooks = cfg.hooks();
    safe(
        () ->
            hooks.onRequest(
                new Hooks.RequestInfo("GET", safeUrl, Redaction.redactHeaderLists(sent), 1)));
    long began = System.nanoTime();
    HttpResponse<InputStream> resp;
    try {
      resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofInputStream());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CancelledException(OP + ": cancelled (thread interrupted)", e);
    } catch (IOException e) {
      SignozException err =
          new UnavailableException(
              OP + ": " + api.redact(String.valueOf(e.getMessage())), null, null, null, null);
      safe(() -> hooks.onError(new Hooks.ErrorInfo("GET", safeUrl, err, 1, false)));
      throw err;
    }
    int status = resp.statusCode();
    long ms = (System.nanoTime() - began) / 1_000_000L;
    safe(
        () ->
            hooks.onResponse(
                new Hooks.ResponseInfo("GET", safeUrl, status, ms, 1, java.util.Optional.empty())));
    Map<String, List<String>> respHeaders = resp.headers().map();
    if (status >= 300) {
      byte[] data = readAll(resp.body());
      String message =
          HttpTransport.errorMessage(OP, status, respHeaders, data, api.config().knownSecrets());
      SignozException err =
          SignozException.of(ErrorCode.forStatus(status), message, status, null, null, null);
      safe(() -> hooks.onError(new Hooks.ErrorInfo("GET", safeUrl, err, 1, false)));
      throw err;
    }
    String ct = resp.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
    if (!ct.contains("text/event-stream")) {
      closeQuietly(resp.body());
      throw SignozException.of(
          ErrorCode.UNSUPPORTED,
          OP + ": endpoint not available on this server version",
          status,
          null,
          null,
          null);
    }
    return resp.body();
  }

  private static byte[] readAll(InputStream in) {
    try (InputStream s = in) {
      return s.readNBytes(64 * 1024);
    } catch (IOException e) {
      return new byte[0];
    }
  }

  private static void closeQuietly(InputStream in) {
    try {
      in.close();
    } catch (IOException ignored) {
      // closing a dead stream
    }
  }

  private static void safe(Runnable r) {
    try {
      r.run();
    } catch (RuntimeException ignored) {
      // hooks and callbacks must never break the tail
    }
  }

  /** Reads the stream and reconnects until stopped. */
  private void run(InputStream first) {
    InputStream body = first;
    while (!stopped) {
      read(body);
      closeQuietly(body);
      body = null;
      while (!stopped && body == null) {
        try {
          Thread.sleep(Math.max(1, options.interval().toMillis()));
        } catch (InterruptedException e) {
          return;
        }
        if (stopped) {
          return;
        }
        try {
          body = connect();
          current = body;
        } catch (SignozException e) {
          safe(() -> options.onError().accept(e));
          if (!e.retryable() || e instanceof CancelledException) {
            return;
          }
        }
      }
    }
  }

  /** Dispatches the events of one connection. */
  void read(InputStream body) {
    StringBuilder data = new StringBuilder();
    String event = "";
    try (BufferedReader r =
        new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
      String line;
      while (!stopped && (line = r.readLine()) != null) {
        if (line.isEmpty()) {
          dispatch(event, data.toString());
          event = "";
          data.setLength(0);
        } else if (line.startsWith(":")) {
          continue;
        } else if (line.startsWith("event:")) {
          event = line.substring("event:".length()).trim();
        } else if (line.startsWith("data:")) {
          if (data.length() > 0) {
            data.append('\n');
          }
          String v = line.substring("data:".length());
          data.append(v.startsWith(" ") ? v.substring(1) : v);
        }
      }
      if (!stopped && data.length() > 0) {
        dispatch(event, data.toString());
      }
    } catch (IOException e) {
      if (!stopped) {
        safe(
            () ->
                options
                    .onError()
                    .accept(
                        new UnavailableException(
                            OP
                                + ": stream interrupted: "
                                + api.redact(String.valueOf(e.getMessage())))));
      }
    }
  }

  void dispatch(String event, String data) {
    if (data.isEmpty()) {
      return;
    }
    if (event.equals("error")) {
      String text = Redaction.truncate(api.redact(data), Redaction.MAX_VENDOR_TEXT);
      api.warn("signoz live tail error event", Map.of("error", text));
      safe(() -> options.onError().accept(new UnavailableException(OP + ": " + text)));
      return;
    }
    JsonNode row;
    try {
      row = Json.tree(data.getBytes(StandardCharsets.UTF_8));
    } catch (ValidationException e) {
      return;
    }
    if (!row.path("data").isObject()) {
      return;
    }
    String id = Rows.str(row.path("data"), "id");
    if (!id.isEmpty()) {
      if (!seen.add(id)) {
        return;
      }
      seenOrder.addLast(id);
      if (seenOrder.size() > MAX_SEEN) {
        seen.remove(seenOrder.removeFirst());
      }
    }
    long ms = Rows.rowMillis(row);
    if (ms > lastMs) {
      lastMs = ms;
    }
    LogRecord rec;
    try {
      rec = Rows.logRecord(row);
    } catch (RuntimeException e) {
      return;
    }
    safe(() -> onRecord.accept(rec));
  }

  /** Ends the tail and waits briefly for the reader to finish. Idempotent. */
  @Override
  public void stop() {
    if (stopped) {
      return;
    }
    stopped = true;
    InputStream in = current;
    if (in != null) {
      closeQuietly(in);
    }
    Thread t = reader;
    if (t != null && t != Thread.currentThread()) {
      t.interrupt();
      try {
        t.join(2000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    onStop.accept(this);
  }

  boolean isStopped() {
    return stopped;
  }
}
