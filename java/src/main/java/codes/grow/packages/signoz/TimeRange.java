package codes.grow.packages.signoz;

import java.time.Instant;
import java.util.Optional;

/**
 * A query time window. Missing values mean the defaults: end = now, start = end minus {@link
 * SignozQueryClient#DEFAULT_QUERY_WINDOW} (one hour). The end is exclusive.
 *
 * @param start window start, or {@code null}
 * @param end window end, or {@code null}
 */
public record TimeRange(Instant start, Instant end) {
  /**
   * The default window (the last hour).
   *
   * @return a range without bounds
   */
  public static TimeRange defaults() {
    return new TimeRange(null, null);
  }

  /**
   * A window from {@code start} until now.
   *
   * @param start window start
   * @return the range
   */
  public static TimeRange since(Instant start) {
    return new TimeRange(start, null);
  }

  /**
   * Returns the start, if set.
   *
   * @return the start
   */
  public Optional<Instant> startOptional() {
    return Optional.ofNullable(start);
  }

  /**
   * Returns the end, if set.
   *
   * @return the end
   */
  public Optional<Instant> endOptional() {
    return Optional.ofNullable(end);
  }
}
