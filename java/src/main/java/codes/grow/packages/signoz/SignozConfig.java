package codes.grow.packages.signoz;

import codes.grow.packages.signoz.core.ConfigException;
import codes.grow.packages.signoz.core.EnvReader;
import codes.grow.packages.signoz.core.Hooks;
import codes.grow.packages.signoz.core.Logger;
import codes.grow.packages.signoz.core.Redaction;
import codes.grow.packages.signoz.core.RetryPolicy;
import codes.grow.packages.signoz.core.Secret;
import codes.grow.packages.signoz.core.TlsOptions;
import codes.grow.packages.signoz.core.TransportConfig;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable configuration of a {@link SignozClient} (SPEC section 2.2).
 *
 * <p>Query calls ({@link SignozQueryClient}, {@link SignozClient#ping()}) go to {@link #url()} with
 * the {@code SIGNOZ-API-KEY} header; ingest (log exporter, tracing) goes to the collector at {@link
 * #otlpEndpoint()}. An ingest-only client needs no API key.
 *
 * <p>Build one with {@link #builder()} or read it from the environment with {@link #fromEnv()}.
 * Precedence: explicit builder value &gt; SDK variable ({@code GROW_SIGNOZ_<NAME>}) &gt; vendor
 * variables ({@code SIGNOZ_URL}, {@code SIGNOZ_API_KEY}, {@code SIGNOZ_OTLP_ENDPOINT}, {@code
 * OTEL_EXPORTER_OTLP_ENDPOINT}, {@code SIGNOZ_INGESTION_KEY}, {@code OTEL_EXPORTER_OTLP_PROTOCOL})
 * &gt; default. {@link #toString()} never prints credentials.
 */
public final class SignozConfig {
  /** Default query API endpoint (UI + API port 8080). */
  public static final String DEFAULT_URL = "http://127.0.0.1:8080";

  /** Default OTLP/HTTP collector endpoint. */
  public static final String DEFAULT_OTLP_HTTP_ENDPOINT = "http://127.0.0.1:4318";

  /** Default OTLP/gRPC collector endpoint. */
  public static final String DEFAULT_OTLP_GRPC_ENDPOINT = "http://127.0.0.1:4317";

  /** Header carrying the service-account API key on query calls. */
  public static final String API_KEY_HEADER = "SIGNOZ-API-KEY";

  /** Header (gRPC metadata) carrying the SigNoz Cloud ingestion key on OTLP exports. */
  public static final String INGESTION_KEY_HEADER = "signoz-ingestion-key";

  /** Vendor variables read for {@code url} after the SDK variable {@code URL}, in order. */
  static final String[] URL_VENDOR_VARS = {"SIGNOZ_URL"};

  /** Vendor variables read for {@code apiKey} after {@code API_KEY}, in order. */
  static final String[] API_KEY_VENDOR_VARS = {"SIGNOZ_API_KEY"};

  /** Vendor variables read for {@code otlpEndpoint} after {@code OTLP_ENDPOINT}, in order. */
  static final String[] OTLP_ENDPOINT_VENDOR_VARS = {
    "SIGNOZ_OTLP_ENDPOINT", "OTEL_EXPORTER_OTLP_ENDPOINT"
  };

  /** Vendor variables read for {@code otlpProtocol} after {@code OTLP_PROTOCOL}, in order. */
  static final String[] OTLP_PROTOCOL_VENDOR_VARS = {"OTEL_EXPORTER_OTLP_PROTOCOL"};

  /** Vendor variables read for {@code ingestionKey} after {@code INGESTION_KEY}, in order. */
  static final String[] INGESTION_KEY_VENDOR_VARS = {"SIGNOZ_INGESTION_KEY"};

  private final String url;
  private final Secret apiKey;
  private final String otlpEndpoint;
  private final OtlpProtocol otlpProtocol;
  private final Secret ingestionKey;
  private final Duration connectTimeout;
  private final Duration requestTimeout;
  private final Duration totalTimeout;
  private final RetryPolicy retry;
  private final TlsOptions tls;
  private final String proxy;
  private final Map<String, String> headers;
  private final String userAgentSuffix;
  private final Hooks hooks;
  private final Logger logger;

  private SignozConfig(Builder b) {
    this.url = b.url != null ? b.url : DEFAULT_URL;
    this.apiKey = b.apiKey;
    this.otlpProtocol = b.otlpProtocol != null ? b.otlpProtocol : OtlpProtocol.HTTP_PROTOBUF;
    this.otlpEndpoint =
        b.otlpEndpoint != null ? b.otlpEndpoint : defaultOtlpEndpoint(this.otlpProtocol);
    this.ingestionKey = b.ingestionKey;
    this.connectTimeout = b.connectTimeout != null ? b.connectTimeout : Duration.ofSeconds(10);
    this.requestTimeout = b.requestTimeout != null ? b.requestTimeout : Duration.ofSeconds(30);
    this.totalTimeout = b.totalTimeout != null ? b.totalTimeout : Duration.ofSeconds(120);
    this.retry = b.retry != null ? b.retry : RetryPolicy.defaults();
    this.tls = b.tls != null ? b.tls : TlsOptions.defaults();
    this.proxy = b.proxy;
    this.headers = Map.copyOf(b.headers);
    this.userAgentSuffix = b.userAgentSuffix;
    this.hooks = b.hooks != null ? b.hooks : Hooks.NONE;
    this.logger = b.logger != null ? b.logger : Logger.NOOP;
  }

  /**
   * Returns the default collector endpoint of a protocol.
   *
   * @param protocol protocol
   * @return {@link #DEFAULT_OTLP_GRPC_ENDPOINT} or {@link #DEFAULT_OTLP_HTTP_ENDPOINT}
   */
  public static String defaultOtlpEndpoint(OtlpProtocol protocol) {
    return protocol == OtlpProtocol.GRPC ? DEFAULT_OTLP_GRPC_ENDPOINT : DEFAULT_OTLP_HTTP_ENDPOINT;
  }

  /**
   * Starts an empty builder (every field at its default).
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Returns a builder with this configuration's values.
   *
   * @return a builder copy
   */
  public Builder toBuilder() {
    Builder b = new Builder();
    b.url = url;
    b.apiKey = apiKey;
    b.otlpEndpoint = otlpEndpoint;
    b.otlpProtocol = otlpProtocol;
    b.ingestionKey = ingestionKey;
    b.connectTimeout = connectTimeout;
    b.requestTimeout = requestTimeout;
    b.totalTimeout = totalTimeout;
    b.retry = retry;
    b.tls = tls;
    b.proxy = proxy;
    b.headers.putAll(headers);
    b.userAgentSuffix = userAgentSuffix;
    b.hooks = hooks;
    b.logger = logger;
    return b;
  }

  /**
   * Reads the configuration from the process environment.
   *
   * @return the validated configuration
   * @throws ConfigException when a variable holds an invalid value (the message names the variable,
   *     never the value)
   */
  public static SignozConfig fromEnv() {
    return builder().fromEnv(EnvReader.system()).build();
  }

  /**
   * Reads the configuration from an explicit variable map (tests).
   *
   * @param env variable map
   * @return the validated configuration
   */
  public static SignozConfig fromEnv(Map<String, String> env) {
    return builder().fromEnv(EnvReader.of(env)).build();
  }

  /**
   * Checks URL schemes, the OTLP endpoint, positive timeouts and TLS pairs.
   *
   * @throws ConfigException when a rule is violated
   */
  public void validate() {
    checkHttpUrl("url", url);
    checkHttpUrl("otlpEndpoint", otlpEndpoint);
    requirePositive("connectTimeout", connectTimeout);
    requirePositive("requestTimeout", requestTimeout);
    requirePositive("totalTimeout", totalTimeout);
    tls.validate();
    if (proxy != null) {
      try {
        URI p = new URI(proxy);
        if (p.getHost() == null) {
          throw new ConfigException("config: proxy must be an absolute URL");
        }
      } catch (URISyntaxException e) {
        throw new ConfigException("config: proxy is not a valid URL");
      }
    }
  }

  private static void checkHttpUrl(String name, String value) {
    URI u;
    try {
      u = new URI(value);
    } catch (URISyntaxException e) {
      throw new ConfigException("config: " + name + " is not a valid URL");
    }
    String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
    if (!(scheme.equals("http") || scheme.equals("https")) || u.getHost() == null) {
      throw new ConfigException("config: " + name + " must use http or https and include a host");
    }
  }

  private static void requirePositive(String name, Duration d) {
    if (d.isZero() || d.isNegative()) {
      throw new ConfigException("config: " + name + " must be positive");
    }
  }

  /**
   * Returns every credential value of this configuration (API key, ingestion key, sensitive
   * headers), so error messages can redact them.
   *
   * @return secret values
   */
  public List<String> knownSecrets() {
    List<String> out = new ArrayList<>();
    for (Secret s : new Secret[] {apiKey, ingestionKey}) {
      if (s != null && !s.isCleared() && !s.isEmpty()) {
        out.add(s.reveal());
      }
    }
    headers.forEach(
        (k, v) -> {
          if (Redaction.isSensitiveHeader(k) && v != null && !v.isEmpty()) {
            out.add(v);
          }
        });
    return out;
  }

  /**
   * Translates the common fields into transport settings for the query API.
   *
   * @return the transport configuration
   */
  public TransportConfig toTransportConfig() {
    return transportConfig(url, headers);
  }

  /**
   * Transport settings for another base URL (the collector) with other headers.
   *
   * @param baseUrl base URL
   * @param extraHeaders headers sent with every request
   * @return the transport configuration
   */
  TransportConfig transportConfig(String baseUrl, Map<String, String> extraHeaders) {
    return TransportConfig.builder(baseUrl)
        .connectTimeout(connectTimeout)
        .requestTimeout(requestTimeout)
        .totalTimeout(totalTimeout)
        .retry(retry)
        .tls(tls)
        .proxy(proxy)
        .headers(extraHeaders)
        .userAgentSuffix(userAgentSuffix)
        .hooks(hooks)
        .logger(logger)
        .knownSecrets(this::knownSecrets)
        .build();
  }

  /**
   * Returns the query API base URL.
   *
   * @return the URL
   */
  public String url() {
    return url;
  }

  /**
   * Returns the service-account API key sent as {@code SIGNOZ-API-KEY}.
   *
   * @return the key, or empty
   */
  public Optional<Secret> apiKey() {
    return Optional.ofNullable(apiKey);
  }

  /**
   * Returns the collector endpoint (default by protocol: port 4318 for HTTP, 4317 for gRPC).
   *
   * @return the endpoint
   */
  public String otlpEndpoint() {
    return otlpEndpoint;
  }

  /**
   * Returns the OTLP protocol (default {@link OtlpProtocol#HTTP_PROTOBUF}).
   *
   * @return the protocol
   */
  public OtlpProtocol otlpProtocol() {
    return otlpProtocol;
  }

  /**
   * Returns the SigNoz Cloud ingestion key sent as {@code signoz-ingestion-key}.
   *
   * @return the key, or empty
   */
  public Optional<Secret> ingestionKey() {
    return Optional.ofNullable(ingestionKey);
  }

  /**
   * Returns the connect timeout.
   *
   * @return the timeout
   */
  public Duration connectTimeout() {
    return connectTimeout;
  }

  /**
   * Returns the per-attempt timeout.
   *
   * @return the timeout
   */
  public Duration requestTimeout() {
    return requestTimeout;
  }

  /**
   * Returns the total timeout per call including retries.
   *
   * @return the timeout
   */
  public Duration totalTimeout() {
    return totalTimeout;
  }

  /**
   * Returns the retry policy.
   *
   * @return the policy
   */
  public RetryPolicy retry() {
    return retry;
  }

  /**
   * Returns the TLS options.
   *
   * @return the options
   */
  public TlsOptions tls() {
    return tls;
  }

  /**
   * Returns the explicit proxy URL.
   *
   * @return the proxy, or empty
   */
  public Optional<String> proxy() {
    return Optional.ofNullable(proxy);
  }

  /**
   * Returns the extra request headers.
   *
   * @return immutable map
   */
  public Map<String, String> headers() {
    return headers;
  }

  /**
   * Returns the user agent suffix.
   *
   * @return the suffix, or empty
   */
  public Optional<String> userAgentSuffix() {
    return Optional.ofNullable(userAgentSuffix);
  }

  /**
   * Returns the hooks.
   *
   * @return the hooks
   */
  public Hooks hooks() {
    return hooks;
  }

  /**
   * Returns the logger.
   *
   * @return the logger
   */
  public Logger logger() {
    return logger;
  }

  @Override
  public String toString() {
    return "SignozConfig{url="
        + Redaction.redactUrl(url)
        + ", apiKey="
        + (apiKey == null ? "null" : Redaction.REDACTED)
        + ", otlpEndpoint="
        + Redaction.redactUrl(otlpEndpoint)
        + ", otlpProtocol="
        + otlpProtocol.wireValue()
        + ", ingestionKey="
        + (ingestionKey == null ? "null" : Redaction.REDACTED)
        + ", connectTimeout="
        + connectTimeout
        + ", requestTimeout="
        + requestTimeout
        + ", totalTimeout="
        + totalTimeout
        + ", retry="
        + retry
        + ", tls="
        + tls
        + ", proxy="
        + (proxy == null ? "null" : Redaction.redactUrl(proxy))
        + ", headers="
        + Redaction.redactHeaders(headers)
        + ", userAgentSuffix="
        + userAgentSuffix
        + "}";
  }

  /**
   * Builder of the configuration.
   *
   * <p>Unset fields take the environment (when {@link #fromEnv(EnvReader)} is called) or the
   * default.
   */
  public static final class Builder {
    private String url;
    private Secret apiKey;
    private String otlpEndpoint;
    private OtlpProtocol otlpProtocol;
    private Secret ingestionKey;
    private Duration connectTimeout;
    private Duration requestTimeout;
    private Duration totalTimeout;
    private RetryPolicy retry;
    private TlsOptions tls;
    private String proxy;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private String userAgentSuffix;
    private Hooks hooks;
    private Logger logger;

    private Builder() {}

    /**
     * Fills every field that was not set explicitly from the environment.
     *
     * @param env environment reader
     * @return this builder
     * @throws ConfigException when a variable holds an invalid value
     */
    public Builder fromEnv(EnvReader env) {
      if (url == null) {
        url = env.string("URL", URL_VENDOR_VARS).orElse(null);
      }
      if (apiKey == null) {
        apiKey = env.string("API_KEY", API_KEY_VENDOR_VARS).map(Secret::of).orElse(null);
      }
      if (ingestionKey == null) {
        ingestionKey =
            env.string("INGESTION_KEY", INGESTION_KEY_VENDOR_VARS).map(Secret::of).orElse(null);
      }
      if (otlpEndpoint == null) {
        otlpEndpoint = env.string("OTLP_ENDPOINT", OTLP_ENDPOINT_VENDOR_VARS).orElse(null);
      }
      if (otlpProtocol == null) {
        Optional<String> p = env.string("OTLP_PROTOCOL", OTLP_PROTOCOL_VENDOR_VARS);
        if (p.isPresent()) {
          try {
            otlpProtocol = OtlpProtocol.parse(p.get());
          } catch (ConfigException e) {
            String variable = env.source("OTLP_PROTOCOL", OTLP_PROTOCOL_VENDOR_VARS).orElse("");
            throw new ConfigException(
                "config: invalid value in " + variable + " (expected http/protobuf or grpc)");
          }
        }
      }
      if (connectTimeout == null) {
        connectTimeout = env.duration("CONNECT_TIMEOUT").orElse(null);
      }
      if (requestTimeout == null) {
        requestTimeout = env.duration("REQUEST_TIMEOUT").orElse(null);
      }
      if (totalTimeout == null) {
        totalTimeout = env.duration("TOTAL_TIMEOUT").orElse(null);
      }
      if (retry == null && env.integer("MAX_RETRIES").isPresent()) {
        int n = env.integer("MAX_RETRIES").getAsInt();
        if (n < 0) {
          String variable = EnvReader.DEFAULT_PREFIX + "MAX_RETRIES";
          throw new ConfigException("config: invalid value in " + variable + " (expected >= 0)");
        }
        retry = RetryPolicy.builder().maxRetries(n).build();
      }
      if (tls == null) {
        TlsOptions.Builder t = TlsOptions.builder();
        env.string("CA_CERT").ifPresent(p -> t.caCertPath(Paths.get(p)));
        env.string("CLIENT_CERT").ifPresent(p -> t.clientCertPath(Paths.get(p)));
        env.string("CLIENT_KEY").ifPresent(p -> t.clientKeyPath(Paths.get(p)));
        env.bool("INSECURE_SKIP_VERIFY").ifPresent(t::insecureSkipVerify);
        tls = t.build();
      }
      if (proxy == null) {
        proxy = env.string("PROXY").orElse(null);
      }
      return this;
    }

    /**
     * Sets the query API base URL (default {@link #DEFAULT_URL}).
     *
     * @param url {@code http} or {@code https} URL
     * @return this builder
     */
    public Builder url(String url) {
      this.url = url;
      return this;
    }

    /**
     * Sets the service-account API key (any role works, viewer included).
     *
     * @param apiKey key
     * @return this builder
     */
    public Builder apiKey(Secret apiKey) {
      this.apiKey = apiKey;
      return this;
    }

    /**
     * Sets the service-account API key.
     *
     * @param apiKey key
     * @return this builder
     */
    public Builder apiKey(String apiKey) {
      this.apiKey = apiKey == null ? null : Secret.of(apiKey);
      return this;
    }

    /**
     * Sets the collector endpoint for logs and traces.
     *
     * @param endpoint {@code http} or {@code https} URL
     * @return this builder
     */
    public Builder otlpEndpoint(String endpoint) {
      this.otlpEndpoint = endpoint;
      return this;
    }

    /**
     * Sets the OTLP protocol (default {@link OtlpProtocol#HTTP_PROTOBUF}).
     *
     * @param protocol protocol
     * @return this builder
     */
    public Builder otlpProtocol(OtlpProtocol protocol) {
      this.otlpProtocol = protocol;
      return this;
    }

    /**
     * Sets the SigNoz Cloud ingestion key (self-hosted collectors ignore it).
     *
     * @param ingestionKey key
     * @return this builder
     */
    public Builder ingestionKey(Secret ingestionKey) {
      this.ingestionKey = ingestionKey;
      return this;
    }

    /**
     * Sets the SigNoz Cloud ingestion key.
     *
     * @param ingestionKey key
     * @return this builder
     */
    public Builder ingestionKey(String ingestionKey) {
      this.ingestionKey = ingestionKey == null ? null : Secret.of(ingestionKey);
      return this;
    }

    /**
     * Sets the connect timeout (default 10 s).
     *
     * @param d positive duration
     * @return this builder
     */
    public Builder connectTimeout(Duration d) {
      this.connectTimeout = d;
      return this;
    }

    /**
     * Sets the per-attempt timeout (default 30 s).
     *
     * @param d positive duration
     * @return this builder
     */
    public Builder requestTimeout(Duration d) {
      this.requestTimeout = d;
      return this;
    }

    /**
     * Sets the total timeout per call including retries (default 120 s).
     *
     * @param d positive duration
     * @return this builder
     */
    public Builder totalTimeout(Duration d) {
      this.totalTimeout = d;
      return this;
    }

    /**
     * Sets the retry policy (default {@link RetryPolicy#defaults()}).
     *
     * @param retry policy
     * @return this builder
     */
    public Builder retry(RetryPolicy retry) {
      this.retry = retry;
      return this;
    }

    /**
     * Sets the TLS options; environment TLS variables are then ignored.
     *
     * @param tls options
     * @return this builder
     */
    public Builder tls(TlsOptions tls) {
      this.tls = tls;
      return this;
    }

    /**
     * Sets an explicit proxy URL; environment proxies ({@code HTTPS_PROXY}) are never used.
     *
     * @param proxy proxy URL
     * @return this builder
     */
    public Builder proxy(String proxy) {
      this.proxy = proxy;
      return this;
    }

    /**
     * Adds a header sent with every request (query API and OTLP exports).
     *
     * @param name header name
     * @param value header value
     * @return this builder
     */
    public Builder header(String name, String value) {
      this.headers.put(name, value);
      return this;
    }

    /**
     * Sets the text appended to the {@code User-Agent}.
     *
     * @param suffix suffix
     * @return this builder
     */
    public Builder userAgentSuffix(String suffix) {
      this.userAgentSuffix = suffix;
      return this;
    }

    /**
     * Sets request hooks.
     *
     * @param hooks hooks
     * @return this builder
     */
    public Builder hooks(Hooks hooks) {
      this.hooks = hooks;
      return this;
    }

    /**
     * Sets the logger (default silent).
     *
     * @param logger logger
     * @return this builder
     */
    public Builder logger(Logger logger) {
      this.logger = logger;
      return this;
    }

    /**
     * Builds and validates the configuration.
     *
     * @return the configuration
     * @throws ConfigException when a rule is violated
     */
    public SignozConfig build() {
      SignozConfig c = new SignozConfig(this);
      c.validate();
      return c;
    }
  }
}
