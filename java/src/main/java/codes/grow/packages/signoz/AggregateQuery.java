package codes.grow.packages.signoz;

import codes.grow.packages.signoz.logging.SortOrder;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * A count/percentile query for {@link SignozQueryClient#aggregate(AggregateQuery)}. Build it with
 * {@link #builder(Signal)}:
 *
 * <pre>{@code
 * AggregateQuery q = AggregateQuery.builder(Signal.TRACES)
 *     .filter("service.name = 'checkout'")
 *     .count("calls")
 *     .percentile(99, "duration_nano", "p99")
 *     .groupBy("name")
 *     .build();
 * }</pre>
 *
 * @param signal logs or traces
 * @param filter SigNoz filter expression ({@code ""} = all)
 * @param aggregations values to compute (at least one)
 * @param groupBy field names to group by
 * @param start window start ({@code null} = one hour before the end)
 * @param end window end ({@code null} = now)
 * @param stepInterval bucket size of a time series ({@code null} or zero = scalar, one row per
 *     group); rounded down to whole seconds (at least 1 s)
 * @param limit maximum number of groups (0 = default 100)
 * @param orderKey group field or aggregation alias to order groups by ({@code null} = server order)
 * @param orderDirection direction of {@code orderKey} ({@code null} = descending)
 */
public record AggregateQuery(
    Signal signal,
    String filter,
    List<Aggregation> aggregations,
    List<String> groupBy,
    Instant start,
    Instant end,
    Duration stepInterval,
    int limit,
    String orderKey,
    SortOrder orderDirection) {
  /**
   * Copies the lists and fills {@code null} values.
   *
   * @param signal signal
   * @param filter filter
   * @param aggregations aggregations
   * @param groupBy group fields
   * @param start start
   * @param end end
   * @param stepInterval step
   * @param limit limit
   * @param orderKey order key
   * @param orderDirection order direction
   */
  public AggregateQuery {
    filter = filter == null ? "" : filter.trim();
    aggregations = aggregations == null ? List.of() : List.copyOf(aggregations);
    groupBy = groupBy == null ? List.of() : List.copyOf(groupBy);
  }

  /**
   * Starts a builder over a signal.
   *
   * @param signal logs or traces
   * @return a new builder (its {@code run()} needs a client: use {@link
   *     SignozQueryClient#aggregate(Signal)} for that)
   */
  public static AggregateBuilder builder(Signal signal) {
    return new AggregateBuilder(signal, null);
  }

  /**
   * Whether this is a time-series query.
   *
   * @return true when a positive step is set
   */
  public boolean isTimeSeries() {
    return stepInterval != null && !stepInterval.isZero() && !stepInterval.isNegative();
  }

  /**
   * Window start.
   *
   * @return the start, if set
   */
  public Optional<Instant> startOptional() {
    return Optional.ofNullable(start);
  }
}
