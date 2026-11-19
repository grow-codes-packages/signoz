package codes.grow.packages.signoz;

import codes.grow.packages.signoz.core.ValidationException;
import codes.grow.packages.signoz.logging.SortOrder;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Fluent builder of an {@link AggregateQuery} (SigNoz {@code requestType} {@code scalar}, or {@code
 * time_series} with {@link #step}). SigNoz does not echo aliases; values are mapped back to the
 * aliases by aggregation index. Builders from {@link SignozQueryClient#aggregate(Signal)} can
 * {@link #run()} directly:
 *
 * <pre>{@code
 * AggregateResult r = client.query().aggregate(Signal.TRACES)
 *     .filter("service.name = 'checkout'").count("calls").p99("duration_nano", "p99")
 *     .groupBy("name").run();
 * }</pre>
 */
public final class AggregateBuilder {
  private final Signal signal;
  private final SignozQueryClient client;
  private final List<Aggregation> aggregations = new ArrayList<>();
  private final List<String> groupBy = new ArrayList<>();
  private String filter = "";
  private Instant start;
  private Instant end;
  private Duration step;
  private int limit;
  private String orderKey;
  private SortOrder orderDirection;

  AggregateBuilder(Signal signal, SignozQueryClient client) {
    this.signal = signal;
    this.client = client;
  }

  /**
   * Sets the filter expression (replaces an earlier one).
   *
   * @param expression filter expression
   * @return this builder
   */
  public AggregateBuilder filter(String expression) {
    this.filter = expression == null ? "" : expression.trim();
    return this;
  }

  /**
   * Sets the filter from a builder.
   *
   * @param expression filter builder (validated)
   * @return this builder
   */
  public AggregateBuilder filter(FilterBuilder expression) {
    return filter(expression == null ? "" : expression.build());
  }

  /**
   * Adds any aggregate expression.
   *
   * @param expression SigNoz aggregate, for example {@code count()}
   * @param alias result name ({@code null} = the expression)
   * @return this builder
   */
  public AggregateBuilder expression(String expression, String alias) {
    aggregations.add(new Aggregation(expression == null ? "" : expression.trim(), alias));
    return this;
  }

  /**
   * Adds {@code count()}.
   *
   * @param alias result name
   * @return this builder
   */
  public AggregateBuilder count(String alias) {
    return expression("count()", alias);
  }

  /**
   * Adds {@code count_distinct(field)}.
   *
   * @param field field name
   * @param alias result name
   * @return this builder
   */
  public AggregateBuilder countDistinct(String field, String alias) {
    return expression("count_distinct(" + field + ")", alias);
  }

  /**
   * Adds {@code sum(field)}.
   *
   * @param field field name
   * @param alias result name
   * @return this builder
   */
  public AggregateBuilder sum(String field, String alias) {
    return expression("sum(" + field + ")", alias);
  }

  /**
   * Adds {@code avg(field)}.
   *
   * @param field field name
   * @param alias result name
   * @return this builder
   */
  public AggregateBuilder avg(String field, String alias) {
    return expression("avg(" + field + ")", alias);
  }

  /**
   * Adds {@code min(field)}.
   *
   * @param field field name
   * @param alias result name
   * @return this builder
   */
  public AggregateBuilder min(String field, String alias) {
    return expression("min(" + field + ")", alias);
  }

  /**
   * Adds {@code max(field)}.
   *
   * @param field field name
   * @param alias result name
   * @return this builder
   */
  public AggregateBuilder max(String field, String alias) {
    return expression("max(" + field + ")", alias);
  }

  /**
   * Adds {@code p<percentile>(field)} (50, 75, 90, 95, 99, ...).
   *
   * @param percentile percentile
   * @param field field name, for example {@code duration_nano}
   * @param alias result name
   * @return this builder
   */
  public AggregateBuilder percentile(int percentile, String field, String alias) {
    return expression("p" + percentile + "(" + field + ")", alias);
  }

  /**
   * Adds {@code p50(field)}.
   *
   * @param field field name
   * @param alias result name
   * @return this builder
   */
  public AggregateBuilder p50(String field, String alias) {
    return percentile(50, field, alias);
  }

  /**
   * Adds {@code p90(field)}.
   *
   * @param field field name
   * @param alias result name
   * @return this builder
   */
  public AggregateBuilder p90(String field, String alias) {
    return percentile(90, field, alias);
  }

  /**
   * Adds {@code p95(field)}.
   *
   * @param field field name
   * @param alias result name
   * @return this builder
   */
  public AggregateBuilder p95(String field, String alias) {
    return percentile(95, field, alias);
  }

  /**
   * Adds {@code p99(field)}.
   *
   * @param field field name
   * @param alias result name
   * @return this builder
   */
  public AggregateBuilder p99(String field, String alias) {
    return percentile(99, field, alias);
  }

  /**
   * Adds group fields (for example {@code service.name}, {@code name}).
   *
   * @param fields field names
   * @return this builder
   */
  public AggregateBuilder groupBy(String... fields) {
    groupBy.addAll(Arrays.asList(fields));
    return this;
  }

  /**
   * Orders groups by a group field or an aggregation alias.
   *
   * @param key field or alias
   * @param direction direction ({@code null} = descending)
   * @return this builder
   */
  public AggregateBuilder orderBy(String key, SortOrder direction) {
    this.orderKey = key;
    this.orderDirection = direction;
    return this;
  }

  /**
   * Orders groups with a {@code "<key> [asc|desc]"} text, for example {@code "calls desc"}.
   *
   * @param order order text
   * @return this builder
   * @throws ValidationException when the direction is neither asc nor desc
   */
  public AggregateBuilder orderBy(String order) {
    String t = order == null ? "" : order.trim();
    int sp = t.indexOf(' ');
    String key = sp < 0 ? t : t.substring(0, sp);
    String dir = sp < 0 ? "" : t.substring(sp + 1).trim().toLowerCase(Locale.ROOT);
    SortOrder d;
    switch (dir) {
      case "":
      case "desc":
        d = SortOrder.DESC;
        break;
      case "asc":
        d = SortOrder.ASC;
        break;
      default:
        throw new ValidationException("aggregate: orderBy direction must be asc or desc");
    }
    return orderBy(key, d);
  }

  /**
   * Caps the number of groups (1..10000, default 100).
   *
   * @param limit limit
   * @return this builder
   */
  public AggregateBuilder limit(int limit) {
    this.limit = limit;
    return this;
  }

  /**
   * Sets the time window (default: the last hour).
   *
   * @param start start or {@code null}
   * @param end end or {@code null}
   * @return this builder
   */
  public AggregateBuilder between(Instant start, Instant end) {
    this.start = start;
    this.end = end;
    return this;
  }

  /**
   * Makes the query a time series with buckets of {@code step}.
   *
   * @param step bucket size (whole seconds)
   * @return this builder
   */
  public AggregateBuilder step(Duration step) {
    this.step = step;
    return this;
  }

  /**
   * Returns the built query.
   *
   * @return the query
   */
  public AggregateQuery build() {
    return new AggregateQuery(
        signal, filter, aggregations, groupBy, start, end, step, limit, orderKey, orderDirection);
  }

  /**
   * Runs the query on the client that created this builder.
   *
   * @return the result
   * @throws IllegalStateException when the builder came from {@link AggregateQuery#builder}
   */
  public AggregateResult run() {
    return bound().aggregate(build());
  }

  /**
   * Asynchronous {@link #run()}.
   *
   * @return a future with the result
   */
  public CompletableFuture<AggregateResult> runAsync() {
    return bound().aggregateAsync(build());
  }

  private SignozQueryClient bound() {
    if (client == null) {
      throw new IllegalStateException(
          "aggregate: builder is not bound to a client; use SignozQueryClient.aggregate(query)");
    }
    return client;
  }
}
