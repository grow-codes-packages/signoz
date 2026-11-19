package codes.grow.packages.signoz;

import codes.grow.packages.signoz.logging.BatchOptions;
import codes.grow.packages.signoz.logging.RedactionProcessor;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Options of {@link SignozClient#logClient(LogClientOptions)} and {@link
 * SignozClient#createLogClient(SignozConfig, LogClientOptions)}: resource attributes, batching,
 * redaction and export overrides.
 */
public final class LogClientOptions {
  /** {@code service.name} used when none is set. */
  public static final String DEFAULT_SERVICE_NAME = "unknown_service";

  private final String serviceName;
  private final String serviceVersion;
  private final String environment;
  private final OtlpProtocol protocol;
  private final String endpoint;
  private final Map<String, String> headers;
  private final BatchOptions batchOptions;
  private final Map<String, Object> resource;
  private final RedactionProcessor redaction;

  private LogClientOptions(Builder b) {
    this.serviceName = b.serviceName;
    this.serviceVersion = b.serviceVersion;
    this.environment = b.environment;
    this.protocol = b.protocol;
    this.endpoint = b.endpoint;
    this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(b.headers));
    this.batchOptions = b.batchOptions;
    this.resource = Collections.unmodifiableMap(new LinkedHashMap<>(b.resource));
    this.redaction = b.redaction;
  }

  /**
   * Starts a builder.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Defaults: {@code service.name=unknown_service}, the config's protocol and endpoint, default
   * batching and redaction.
   *
   * @return defaults
   */
  public static LogClientOptions defaults() {
    return new Builder().build();
  }

  /**
   * The resource of every record: {@code service.name} (default {@code unknown_service}), {@code
   * service.version} and {@code deployment.environment}, then the explicit resource attributes
   * (which win).
   *
   * @return resource attributes
   */
  public Map<String, Object> effectiveResource() {
    Map<String, Object> r = new LinkedHashMap<>();
    r.put("service.name", serviceName != null ? serviceName : DEFAULT_SERVICE_NAME);
    if (serviceVersion != null) {
      r.put("service.version", serviceVersion);
    }
    if (environment != null) {
      r.put("deployment.environment", environment);
    }
    r.putAll(resource);
    return r;
  }

  /**
   * The export configuration: {@code config} with the protocol, endpoint and headers overridden.
   * Picking another protocol without an endpoint uses that protocol's default port on {@code
   * localhost}.
   *
   * @param config base configuration
   * @return the export configuration
   */
  public SignozConfig exportConfig(SignozConfig config) {
    SignozConfig.Builder b = config.toBuilder();
    if (protocol != null) {
      if (protocol != config.otlpProtocol() && endpoint == null) {
        b.otlpEndpoint(SignozConfig.defaultOtlpEndpoint(protocol));
      }
      b.otlpProtocol(protocol);
    }
    if (endpoint != null) {
      b.otlpEndpoint(endpoint);
    }
    headers.forEach(b::header);
    return b.build();
  }

  /**
   * Returns the {@code service.name}.
   *
   * @return the name, if set
   */
  public Optional<String> serviceName() {
    return Optional.ofNullable(serviceName);
  }

  /**
   * Returns the batch options.
   *
   * @return the options, if set
   */
  public Optional<BatchOptions> batchOptions() {
    return Optional.ofNullable(batchOptions);
  }

  /**
   * Returns the redaction processor.
   *
   * @return the processor, if set
   */
  public Optional<RedactionProcessor> redaction() {
    return Optional.ofNullable(redaction);
  }

  /** Builder of {@link LogClientOptions}. */
  public static final class Builder {
    private String serviceName;
    private String serviceVersion;
    private String environment;
    private OtlpProtocol protocol;
    private String endpoint;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private BatchOptions batchOptions;
    private final Map<String, Object> resource = new LinkedHashMap<>();
    private RedactionProcessor redaction;

    private Builder() {}

    /**
     * Sets {@code service.name} (default {@code unknown_service}).
     *
     * @param serviceName name
     * @return this builder
     */
    public Builder serviceName(String serviceName) {
      this.serviceName = serviceName;
      return this;
    }

    /**
     * Sets {@code service.version}.
     *
     * @param serviceVersion version
     * @return this builder
     */
    public Builder serviceVersion(String serviceVersion) {
      this.serviceVersion = serviceVersion;
      return this;
    }

    /**
     * Sets {@code deployment.environment}.
     *
     * @param environment environment
     * @return this builder
     */
    public Builder environment(String environment) {
      this.environment = environment;
      return this;
    }

    /**
     * Overrides the config's OTLP protocol.
     *
     * @param protocol protocol
     * @return this builder
     */
    public Builder protocol(OtlpProtocol protocol) {
      this.protocol = protocol;
      return this;
    }

    /**
     * Overrides the config's collector endpoint.
     *
     * @param endpoint endpoint
     * @return this builder
     */
    public Builder endpoint(String endpoint) {
      this.endpoint = endpoint;
      return this;
    }

    /**
     * Adds an export header (merged over the config headers).
     *
     * @param name name
     * @param value value
     * @return this builder
     */
    public Builder header(String name, String value) {
      this.headers.put(name, value);
      return this;
    }

    /**
     * Sets the batching options.
     *
     * @param batchOptions options
     * @return this builder
     */
    public Builder batchOptions(BatchOptions batchOptions) {
      this.batchOptions = batchOptions;
      return this;
    }

    /**
     * Adds resource attributes (they win over serviceName, serviceVersion and environment).
     *
     * @param resource attributes
     * @return this builder
     */
    public Builder resource(Map<String, ?> resource) {
      this.resource.putAll(resource);
      return this;
    }

    /**
     * Sets the redaction processor (default {@link RedactionProcessor#defaults()}).
     *
     * @param redaction processor
     * @return this builder
     */
    public Builder redaction(RedactionProcessor redaction) {
      this.redaction = redaction;
      return this;
    }

    /**
     * Builds the options.
     *
     * @return the options
     */
    public LogClientOptions build() {
      return new LogClientOptions(this);
    }
  }
}
