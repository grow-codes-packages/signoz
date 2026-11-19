package codes.grow.packages.signoz;

import codes.grow.packages.signoz.logging.SortOrder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * A raw logs query for {@link SignozQueryClient#queryLogs}. The defaults list the newest 100
 * records of the last hour.
 */
public final class LogQuery {
  private final String filter;
  private final Instant start;
  private final Instant end;
  private final int limit;
  private final SortOrder order;
  private final String cursor;
  private final List<String> selectFields;

  private LogQuery(Builder b) {
    this.filter = b.filter;
    this.start = b.start;
    this.end = b.end;
    this.limit = b.limit;
    this.order = b.order;
    this.cursor = b.cursor;
    this.selectFields = List.copyOf(b.selectFields);
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
   * The default query (every record of the last hour, newest first, 100 per page).
   *
   * @return the query
   */
  public static LogQuery defaults() {
    return new Builder().build();
  }

  /**
   * Returns a builder with these values.
   *
   * @return a builder copy
   */
  public Builder toBuilder() {
    Builder b = new Builder();
    b.filter = filter;
    b.start = start;
    b.end = end;
    b.limit = limit;
    b.order = order;
    b.cursor = cursor;
    b.selectFields.addAll(selectFields);
    return b;
  }

  /**
   * SigNoz filter expression ({@code ""} = every record).
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
   * Page size (0 = default 100).
   *
   * @return the limit
   */
  public int limit() {
    return limit;
  }

  /**
   * Order by timestamp (ties by id); {@code null} = newest first.
   *
   * @return the order, if set
   */
  public Optional<SortOrder> order() {
    return Optional.ofNullable(order);
  }

  /**
   * Cursor of the previous page.
   *
   * @return the cursor, if set
   */
  public Optional<String> cursor() {
    return Optional.ofNullable(cursor);
  }

  /**
   * Columns to return besides {@code id} and {@code timestamp} (empty = all).
   *
   * @return field names
   */
  public List<String> selectFields() {
    return selectFields;
  }

  /** Builder of {@link LogQuery}. */
  public static final class Builder {
    private String filter = "";
    private Instant start;
    private Instant end;
    private int limit;
    private SortOrder order;
    private String cursor;
    private final List<String> selectFields = new ArrayList<>();

    private Builder() {}

    /**
     * Sets the filter expression, for example {@code service.name = 'checkout' AND severity_text IN
     * ('ERROR')}; bare words search the body.
     *
     * @param filter expression ({@code null} or empty = all)
     * @return this builder
     */
    public Builder filter(String filter) {
      this.filter = filter == null ? "" : filter.trim();
      return this;
    }

    /**
     * Sets the filter from a builder.
     *
     * @param filter filter builder (validated with {@link FilterBuilder#build()})
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
     * Sets the page size (1..10000, default 100).
     *
     * @param limit page size
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
     * Continues a previous page ({@link LogQueryResult#nextCursor()}).
     *
     * @param cursor cursor or {@code null}
     * @return this builder
     */
    public Builder cursor(String cursor) {
      this.cursor = cursor == null || cursor.isEmpty() ? null : cursor;
      return this;
    }

    /**
     * Limits the returned columns ({@code id} and {@code timestamp} are always returned).
     *
     * @param fields field names
     * @return this builder
     */
    public Builder selectFields(String... fields) {
      this.selectFields.clear();
      this.selectFields.addAll(Arrays.asList(fields));
      return this;
    }

    /**
     * Builds the query (limits are checked when it runs).
     *
     * @return the query
     */
    public LogQuery build() {
      return new LogQuery(this);
    }
  }
}
