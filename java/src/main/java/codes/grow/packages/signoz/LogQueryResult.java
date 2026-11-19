package codes.grow.packages.signoz;

import codes.grow.packages.signoz.logging.LogRecord;
import java.util.List;
import java.util.Optional;

/**
 * One page of {@link SignozQueryClient#queryLogs}.
 *
 * @param records normalized records of the page, ordered by timestamp then id
 * @param nextCursor set when the page is full; pass it back as {@link LogQuery.Builder#cursor}
 *     (with the same filter) for the next page
 * @param warnings server warnings, for example an unknown field in the filter (SigNoz then answers
 *     with no rows instead of an error)
 */
public record LogQueryResult(
    List<LogRecord> records, Optional<String> nextCursor, List<String> warnings) {
  /**
   * Copies the lists and fills {@code null} values.
   *
   * @param records records
   * @param nextCursor next cursor
   * @param warnings warnings
   */
  public LogQueryResult {
    records = records == null ? List.of() : List.copyOf(records);
    nextCursor = nextCursor == null ? Optional.empty() : nextCursor;
    warnings = warnings == null ? List.of() : List.copyOf(warnings);
  }
}
