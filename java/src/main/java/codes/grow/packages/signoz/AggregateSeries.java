package codes.grow.packages.signoz;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One time series of a time-series aggregate.
 *
 * @param alias the aggregation the series belongs to
 * @param labels each group-by field mapped to the series' value
 * @param points the buckets in time order
 */
public record AggregateSeries(
    String alias, Map<String, Object> labels, List<AggregatePoint> points) {
  /**
   * Copies the collections.
   *
   * @param alias alias
   * @param labels labels
   * @param points points
   */
  public AggregateSeries {
    labels = Collections.unmodifiableMap(new LinkedHashMap<>(labels == null ? Map.of() : labels));
    points = points == null ? List.of() : List.copyOf(points);
  }
}
