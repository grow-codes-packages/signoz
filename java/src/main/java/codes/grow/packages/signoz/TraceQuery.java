package codes.grow.packages.signoz;

import codes.grow.packages.signoz.logging.SortOrder;
import java.time.Instant;
import java.util.Optional;

/**
 * A raw spans query for {@link SignozQueryClient#queryTraces}. The defaults list the newest 100
 * spans of the last hour.
 */
public final class TraceQuery {
  private final String service;
  private final String operation;
  private final String filter;
  private final Instant start;
  private final Instant end;
  private final int limit;
  private final SortOrder order;
  private final int offset;

  private TraceQuery(Builder b) {
    this.service = b.service;
    this.operation = b.operation;
    this.filter = b.filter;
    this.start = b.start;
    this.end = b.end;
    this.limit = b.limit;
    this.order = b.order;
    this.offset = b.offset;
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
   * Keeps spans of this {@code service.name}.
   *
   * @return the service, if set
   */
  public Optional<String> service() {
    return Optional.ofNullable(service);
  }

  /**
   * Keeps spans with this name.
   *
   * @return the operation, if set
   */
  public Optional<String> operation() {
    return Optional.ofNullable(operation);
  }

  /**
   * Extra filter expression ({@code ""} = none).
   *
   * @return the filter
   */
  public String filter() {
    return filter;
  }

  /**
   * Window start.
   *
   * @return the start, if set
   */
  public Optional<Instant> start() {
    return Optional.ofNullable(start);
  }

  /**
   * Window end (exclusive).
   *
   * @return the end, if set
   */
  public Optional<Instant> end() {
    return Optional.ofNullable(end);
  }

  /**
   * Maximum number of spans (0 = default 100).
   *
   * @return the limit
   */
  public int limit() {
    return limit;
  }

  /**
   * Order by timestamp; {@code null} = newest first.
   *
   * @return the order, if set
   */
  public Optional<SortOrder> order() {
    return Optional.ofNullable(order);
  }

  /**
   * Spans to skip (offset paging over a fixed window).
   *
   * @return the offset
   */
  public int offset() {
    return offset;
  }

  /** The combined filter expression (service, operation and the extra filter). */
  String expression() {
    FilterBuilder f = FilterBuilder.create();
    if (service != null && !service.isEmpty()) {
      f = f.eq("service.name", service);
    }
    if (operation != null && !operation.isEmpty()) {
      f = f.eq("name", operation);
    }
    return FilterBuilder.andFilters(f.toString(), filter);
  }

  /** Builder of {@link TraceQuery}. */
  public static final class Builder {
    private String service;
    private String operation;
    private String filter = "";
    private Instant start;
    private Instant end;
    private int limit;
    private SortOrder order;
    private int offset;

    private Builder() {}

    /**
     * Keeps spans of this {@code service.name}.
     *
     * @param service service name
     * @return this builder
     */
    public Builder service(String service) {
      this.service = service;
      return this;
    }

    /**
     * Keeps spans with this name.
     *
     * @param operation span name
     * @return this builder
     */
    public Builder operation(String operation) {
      this.operation = operation;
      return this;
    }

    /**
     * Sets an extra filter expression, for example {@code duration_nano >= 120000000}.
     *
     * @param filter expression
     * @return this builder
     */
    public Builder filter(String filter) {
      this.filter = filter == null ? "" : filter.trim();
      return this;
    }

    /**
     * Sets the extra filter from a builder.
     *
     * @param filter filter builder
     * @return this builder
     */
    public Builder filter(FilterBuilder filter) {
      return filter(filter == null ? "" : filter.build());
    }

    /**
     * Sets the window start (default: one hour before the end).
     *
     * @param start start
     * @return this builder
     */
    public Builder start(Instant start) {
      this.start = start;
      return this;
    }

    /**
     * Sets the window end, exclusive (default: now).
     *
     * @param end end
     * @return this builder
     */
    public Builder end(Instant end) {
      this.end = end;
      return this;
    }

    /**
     * Sets the maximum number of spans (1..10000, default 100).
     *
     * @param limit limit
     * @return this builder
     */
    public Builder limit(int limit) {
      this.limit = limit;
      return this;
    }

    /**
     * Sets the order (default newest first).
     *
     * @param order order
     * @return this builder
     */
    public Builder order(SortOrder order) {
      this.order = order;
      return this;
    }

    /**
     * Skips spans (offset paging).
     *
     * @param offset spans to skip (&gt;= 0)
     * @return this builder
     */
    public Builder offset(int offset) {
      this.offset = offset;
      return this;
    }

    /**
     * Builds the query.
     *
     * @return the query
     */
    public TraceQuery build() {
      return new TraceQuery(this);
    }
  }
}
