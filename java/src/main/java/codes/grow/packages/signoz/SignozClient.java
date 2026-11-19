package codes.grow.packages.signoz;

import codes.grow.packages.signoz.core.AuthenticationException;
import codes.grow.packages.signoz.core.CancelledException;
import codes.grow.packages.signoz.core.ConfigException;
import codes.grow.packages.signoz.core.EnvReader;
import codes.grow.packages.signoz.core.HealthCheck;
import codes.grow.packages.signoz.core.HealthStatus;
import codes.grow.packages.signoz.core.Hooks;
import codes.grow.packages.signoz.core.HttpTransport;
import codes.grow.packages.signoz.core.Logger;
import codes.grow.packages.signoz.core.NotFoundException;
import codes.grow.packages.signoz.core.RetryPolicy;
import codes.grow.packages.signoz.core.Secret;
import codes.grow.packages.signoz.core.TlsOptions;
import codes.grow.packages.signoz.core.TransportRequest;
import codes.grow.packages.signoz.core.UnsupportedException;
import codes.grow.packages.signoz.logging.BatchingLogClient;
import codes.grow.packages.signoz.logging.LogQueryClient;
import codes.grow.packages.signoz.tracing.TracingConfig;
import codes.grow.packages.signoz.tracing.TracingHandle;
import codes.grow.packages.signoz.tracing.query.TraceQueryClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Client for SigNoz: {@link #health()}, {@link #ping()}, {@link #serverVersion()}, the query client
 * ({@link #query()}, also as {@link #logs()} and {@link #traces()}), OTLP log clients ({@link
 * #logClient(LogClientOptions)}) and tracing ({@link #initTracing(TracingConfig)}).
 *
 * <p>The client talks to two endpoints: the query API ({@link SignozConfig#url()}, port 8080) and
 * the OTLP collector ({@link SignozConfig#otlpEndpoint()}). Thread-safe; {@link #close()} flushes
 * and closes every log client and tracing handle created through it.
 *
 * <pre>{@code
 * try (SignozClient client = SignozClient.fromEnv()) {
 *   HealthStatus health = client.health();
 *   LogQueryResult page = client.query().queryLogs(
 *       LogQuery.builder().filter(FilterBuilder.create().eq("service.name", "checkout")).build());
 * }
 * }</pre>
 *
 * <p>Every I/O method has a blocking form and an {@code …Async} form returning {@link
 * CompletableFuture}.
 */
public final class SignozClient implements AutoCloseable {
  private final SignozConfig config;
  private final HttpTransport transport;
  private final SignozQueryClient query;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final List<AutoCloseable> resources = new ArrayList<>();

  private SignozClient(SignozConfig config, Clock clock) {
    this.config = config;
    this.transport = new HttpTransport(config.toTransportConfig());
    this.query = new SignozQueryClient(new SignozApi(config, transport), clock);
  }

  /**
   * Creates a client.
   *
   * @param config validated configuration
   * @return a new client
   * @throws ConfigException when TLS or proxy settings are invalid
   */
  public static SignozClient create(SignozConfig config) {
    config.validate();
    return new SignozClient(config, Clock.systemUTC());
  }

  /** Creates a client with a fixed clock (tests). */
  static SignozClient create(SignozConfig config, Clock clock) {
    config.validate();
    return new SignozClient(config, clock);
  }

  /**
   * Creates a client from the SDK and vendor environment variables.
   *
   * @return a new client
   */
  public static SignozClient fromEnv() {
    return create(SignozConfig.fromEnv());
  }

  /**
   * Starts a client builder.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Returns the configuration.
   *
   * @return the configuration
   */
  public SignozConfig config() {
    return config;
  }

  /**
   * Returns the HTTP transport of the query API, for service modules built on this client.
   *
   * @return the transport
   */
  public HttpTransport transport() {
    return transport;
  }

  /**
   * Returns the query client (logs, spans, traces, services, aggregations, log fields, live tail).
   *
   * @return the query client
   */
  public SignozQueryClient query() {
    return query;
  }

  /**
   * Returns the logging-category query client (search, tail, streams, labels, waitForLog); the same
   * object as {@link #query()}.
   *
   * @return the log query client
   */
  public LogQueryClient logs() {
    return query;
  }

  /**
   * Returns the tracing-category query client (getTrace, searchTraces, services, operations,
   * dependencies, waitForTrace); the same object as {@link #query()}.
   *
   * @return the trace query client
   */
  public TraceQueryClient traces() {
    return query;
  }

  /**
   * Registers a resource closed with the client.
   *
   * @param resource the resource
   * @throws CancelledException when the client is closed
   */
  public void register(AutoCloseable resource) {
    synchronized (resources) {
      if (closed.get()) {
        throw new CancelledException("signoz: client is closed");
      }
      resources.add(resource);
    }
  }

  /**
   * Checks liveness without credentials ({@code GET /api/v1/health?live=1}, which also checks
   * ClickHouse). Never throws for an unhealthy or unreachable service.
   *
   * @return the health status
   * @throws CancelledException when interrupted or closed
   */
  public HealthStatus health() {
    return HealthCheck.check(transport, "/api/v1/health?live=1");
  }

  /**
   * Asynchronous {@link #health()}.
   *
   * @return a future with the health status
   */
  public CompletableFuture<HealthStatus> healthAsync() {
    return HealthCheck.checkAsync(transport, "/api/v1/health?live=1");
  }

  /**
   * Performs an authenticated round trip ({@code GET /api/v1/fields/keys?signal=logs&limit=1} with
   * {@code SIGNOZ-API-KEY}) and returns its latency.
   *
   * @return the latency
   * @throws AuthenticationException on a missing or wrong API key
   */
  public Duration ping() {
    return HttpTransport.await(pingAsync(), "ping");
  }

  /**
   * Asynchronous {@link #ping()}.
   *
   * @return a future with the latency
   */
  public CompletableFuture<Duration> pingAsync() {
    long start = System.nanoTime();
    SignozApi api = new SignozApi(config, transport);
    return api.call(
            "ping",
            TransportRequest.get("/api/v1/fields/keys").query("signal", "logs").query("limit", 1),
            false)
        .thenApply(r -> Duration.ofNanos(System.nanoTime() - start));
  }

  /**
   * Returns the server version ({@code GET /api/v1/version}) without a leading {@code v}.
   *
   * @return the version string
   * @throws UnsupportedException when the server reports no version (the official community image
   *     reports an empty one)
   */
  public String serverVersion() {
    return HttpTransport.await(serverVersionAsync(), "serverVersion");
  }

  /**
   * Asynchronous {@link #serverVersion()}.
   *
   * @return a future with the version
   */
  public CompletableFuture<String> serverVersionAsync() {
    String op = "serverVersion";
    return transport
        .sendAsync(TransportRequest.get("/api/v1/version").operation(op).idempotent(true).build())
        .handle(
            (r, err) -> {
              if (err != null) {
                Throwable c =
                    err instanceof CompletionException && err.getCause() != null
                        ? err.getCause()
                        : err;
                if (c instanceof NotFoundException) {
                  throw new UnsupportedException(op + ": the server exposes no version");
                }
                throw err instanceof CompletionException
                    ? (CompletionException) err
                    : new CompletionException(err);
              }
              SignozApi.checkNotHtml(op, r);
              JsonNode v = SignozApi.json(op, r).path("version");
              String text = v.isTextual() ? v.asText().trim() : "";
              if (text.startsWith("v")) {
                text = text.substring(1);
              }
              if (text.isEmpty()) {
                throw new UnsupportedException(op + ": server does not report its version");
              }
              return text;
            });
  }

  /**
   * Builds a batching log client that sends OTLP logs to the SigNoz collector. It does not need an
   * API key (only the ingestion key for SigNoz Cloud). Close it to flush.
   *
   * @param config configuration (collector endpoint, protocol, ingestion key, TLS, headers)
   * @param options resource, batching, redaction and export overrides
   * @return the log client
   */
  public static BatchingLogClient createLogClient(SignozConfig config, LogClientOptions options) {
    LogClientOptions o = options == null ? LogClientOptions.defaults() : options;
    SignozLogExporter exporter = SignozLogExporter.create(o.exportConfig(config));
    BatchingLogClient.Builder b =
        BatchingLogClient.builder(exporter).resource(o.effectiveResource());
    o.batchOptions().ifPresent(b::batchOptions);
    o.redaction().ifPresent(b::redaction);
    return b.build();
  }

  /**
   * Creates a log client for the collector with this client's configuration (see {@link
   * #createLogClient}); {@link #close()} flushes and closes it.
   *
   * @param options resource, batching, redaction and export overrides
   * @return the log client
   * @throws CancelledException when the client is closed
   */
  public BatchingLogClient logClient(LogClientOptions options) {
    BatchingLogClient lc = createLogClient(config, options);
    try {
      register(lc);
    } catch (CancelledException e) {
      lc.close();
      throw e;
    }
    return lc;
  }

  /**
   * Creates a log client with default options ({@code service.name=unknown_service}).
   *
   * @return the log client
   */
  public BatchingLogClient logClient() {
    return logClient(LogClientOptions.defaults());
  }

  /**
   * Creates an OTLP log exporter with this client's configuration (for a custom {@code
   * BatchProcessor}). The caller shuts it down.
   *
   * @return the exporter
   */
  public SignozLogExporter logExporter() {
    return SignozLogExporter.create(config);
  }

  /**
   * Starts tracing to the collector with this client's configuration (see {@link
   * SignozTracing#tracingConfigFor}); {@link #close()} shuts the handle down.
   *
   * @param tracing tracing configuration
   * @return the handle
   * @throws CancelledException when the client is closed
   */
  public TracingHandle initTracing(TracingConfig tracing) {
    TracingHandle h = SignozTracing.initTracing(config, tracing);
    try {
      register(h);
    } catch (CancelledException e) {
      h.shutdown();
      throw e;
    }
    return h;
  }

  /**
   * Returns whether {@link #close()} was called.
   *
   * @return true once closed
   */
  public boolean isClosed() {
    return closed.get();
  }

  /**
   * Flushes and closes log clients, shuts tracing handles down, stops live tails and releases
   * connections. Idempotent; never throws.
   */
  @Override
  public void close() {
    List<AutoCloseable> copy;
    synchronized (resources) {
      if (!closed.compareAndSet(false, true)) {
        return;
      }
      copy = new ArrayList<>(resources);
      resources.clear();
    }
    for (AutoCloseable r : copy) {
      try {
        r.close();
      } catch (Exception ignored) {
        // best effort: close must not throw
      }
    }
    query.stopTails();
    transport.close();
  }

  @Override
  public String toString() {
    return "SignozClient{" + config + "}";
  }

  /** Builder that configures and creates a client in one chain. */
  public static final class Builder {
    private final SignozConfig.Builder config = SignozConfig.builder();

    private Builder() {}

    /**
     * Fills unset fields from an environment reader.
     *
     * @param env reader, e.g. {@link EnvReader#system()}
     * @return this builder
     */
    public Builder fromEnv(EnvReader env) {
      config.fromEnv(env);
      return this;
    }

    /**
     * Sets the query API base URL.
     *
     * @param url URL
     * @return this builder
     */
    public Builder url(String url) {
      config.url(url);
      return this;
    }

    /**
     * Sets the API key.
     *
     * @param apiKey key
     * @return this builder
     */
    public Builder apiKey(String apiKey) {
      config.apiKey(apiKey);
      return this;
    }

    /**
     * Sets the API key.
     *
     * @param apiKey key
     * @return this builder
     */
    public Builder apiKey(Secret apiKey) {
      config.apiKey(apiKey);
      return this;
    }

    /**
     * Sets the collector endpoint.
     *
     * @param endpoint endpoint
     * @return this builder
     */
    public Builder otlpEndpoint(String endpoint) {
      config.otlpEndpoint(endpoint);
      return this;
    }

    /**
     * Sets the OTLP protocol.
     *
     * @param protocol protocol
     * @return this builder
     */
    public Builder otlpProtocol(OtlpProtocol protocol) {
      config.otlpProtocol(protocol);
      return this;
    }

    /**
     * Sets the SigNoz Cloud ingestion key.
     *
     * @param ingestionKey key
     * @return this builder
     */
    public Builder ingestionKey(String ingestionKey) {
      config.ingestionKey(ingestionKey);
      return this;
    }

    /**
     * Sets the SigNoz Cloud ingestion key.
     *
     * @param ingestionKey key
     * @return this builder
     */
    public Builder ingestionKey(Secret ingestionKey) {
      config.ingestionKey(ingestionKey);
      return this;
    }

    /**
     * Sets the connect timeout.
     *
     * @param d duration
     * @return this builder
     */
    public Builder connectTimeout(Duration d) {
      config.connectTimeout(d);
      return this;
    }

    /**
     * Sets the per-attempt timeout.
     *
     * @param d duration
     * @return this builder
     */
    public Builder requestTimeout(Duration d) {
      config.requestTimeout(d);
      return this;
    }

    /**
     * Sets the total timeout.
     *
     * @param d duration
     * @return this builder
     */
    public Builder totalTimeout(Duration d) {
      config.totalTimeout(d);
      return this;
    }

    /**
     * Sets the retry policy.
     *
     * @param retry policy
     * @return this builder
     */
    public Builder retry(RetryPolicy retry) {
      config.retry(retry);
      return this;
    }

    /**
     * Sets the TLS options.
     *
     * @param tls options
     * @return this builder
     */
    public Builder tls(TlsOptions tls) {
      config.tls(tls);
      return this;
    }

    /**
     * Sets the proxy URL.
     *
     * @param proxy URL
     * @return this builder
     */
    public Builder proxy(String proxy) {
      config.proxy(proxy);
      return this;
    }

    /**
     * Adds a header.
     *
     * @param name name
     * @param value value
     * @return this builder
     */
    public Builder header(String name, String value) {
      config.header(name, value);
      return this;
    }

    /**
     * Sets the user agent suffix.
     *
     * @param suffix suffix
     * @return this builder
     */
    public Builder userAgentSuffix(String suffix) {
      config.userAgentSuffix(suffix);
      return this;
    }

    /**
     * Sets hooks.
     *
     * @param hooks hooks
     * @return this builder
     */
    public Builder hooks(Hooks hooks) {
      config.hooks(hooks);
      return this;
    }

    /**
     * Sets the logger.
     *
     * @param logger logger
     * @return this builder
     */
    public Builder logger(Logger logger) {
      config.logger(logger);
      return this;
    }

    /**
     * Builds the configuration and the client.
     *
     * @return a new client
     * @throws ConfigException when the configuration is invalid
     */
    public SignozClient build() {
      return create(config.build());
    }
  }
}
