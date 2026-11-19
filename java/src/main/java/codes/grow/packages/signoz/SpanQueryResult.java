package codes.grow.packages.signoz;

import codes.grow.packages.signoz.tracing.query.Span;
import java.util.List;

/**
 * Result of {@link SignozQueryClient#queryTraces}: one span per matching span.
 *
 * @param spans normalized spans
 * @param warnings server warnings (for example unknown fields in the filter)
 */
public record SpanQueryResult(List<Span> spans, List<String> warnings) {
  /**
   * Copies the lists.
   *
   * @param spans spans
   * @param warnings warnings
   */
  public SpanQueryResult {
    spans = spans == null ? List.of() : List.copyOf(spans);
    warnings = warnings == null ? List.of() : List.copyOf(warnings);
  }
}
