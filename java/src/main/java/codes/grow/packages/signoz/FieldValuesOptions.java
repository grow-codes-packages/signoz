package codes.grow.packages.signoz;

/**
 * Options of {@link SignozQueryClient#listFieldValues}.
 *
 * @param signal {@link Signal#LOGS} (default when {@code null}) or {@link Signal#TRACES}
 * @param searchText keep values containing this text ({@code null} = all)
 */
public record FieldValuesOptions(Signal signal, String searchText) {
  /**
   * Fills the default signal.
   *
   * @param signal signal
   * @param searchText search text
   */
  public FieldValuesOptions {
    signal = signal == null ? Signal.LOGS : signal;
  }

  /**
   * Log values without a search text.
   *
   * @return defaults
   */
  public static FieldValuesOptions defaults() {
    return new FieldValuesOptions(Signal.LOGS, null);
  }
}
