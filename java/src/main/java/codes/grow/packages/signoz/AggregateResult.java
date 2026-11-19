package codes.grow.packages.signoz;

import java.util.List;

/**
 * Result of {@link SignozQueryClient#aggregate(AggregateQuery)}: {@code rows} for scalar queries,
 * {@code series} for time series ({@link AggregateBuilder#step}).
 *
 * @param rows one row per group (scalar queries)
 * @param series one series per aggregation and group (time-series queries)
 * @param warnings server warnings (for example unknown fields in the filter)
 */
public record AggregateResult(
    List<AggregateRow> rows, List<AggregateSeries> series, List<String> warnings) {
  /**
   * Copies the lists.
   *
   * @param rows rows
   * @param series series
   * @param warnings warnings
   */
  public AggregateResult {
    rows = rows == null ? List.of() : List.copyOf(rows);
    series = series == null ? List.of() : List.copyOf(series);
    warnings = warnings == null ? List.of() : List.copyOf(warnings);
  }
}
