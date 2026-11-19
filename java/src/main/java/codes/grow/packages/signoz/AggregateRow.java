package codes.grow.packages.signoz;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One group of a scalar aggregate.
 *
 * @param groups each group-by field mapped to its value (string, number or boolean)
 * @param values each aggregation alias mapped to its value
 */
public record AggregateRow(Map<String, Object> groups, Map<String, Double> values) {
  /**
   * Copies the maps.
   *
   * @param groups groups
   * @param values values
   */
  public AggregateRow {
    groups = Collections.unmodifiableMap(new LinkedHashMap<>(groups == null ? Map.of() : groups));
    values = Collections.unmodifiableMap(new LinkedHashMap<>(values == null ? Map.of() : values));
  }
}
