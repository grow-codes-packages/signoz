package codes.grow.packages.signoz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import java.util.List;

/**
 * Result of {@link SignozQueryClient#queryRange}: the {@code data} member of the response envelope
 * and the server warnings.
 *
 * @param data the envelope's {@code data} (a {@link NullNode} when absent)
 * @param warnings server warnings (also logged)
 */
public record QueryRangeResult(JsonNode data, List<String> warnings) {
  /**
   * Copies the warnings.
   *
   * @param data data
   * @param warnings warnings
   */
  public QueryRangeResult {
    data = data == null || data.isMissingNode() ? NullNode.getInstance() : data;
    warnings = warnings == null ? List.of() : List.copyOf(warnings);
  }
}
