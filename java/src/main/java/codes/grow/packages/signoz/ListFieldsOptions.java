package codes.grow.packages.signoz;

/**
 * Options of {@link SignozQueryClient#listLogFields(ListFieldsOptions)}.
 *
 * @param searchText keep fields whose name contains this text ({@code null} = all)
 * @param limit maximum number of names (0 = server default)
 */
public record ListFieldsOptions(String searchText, int limit) {
  /**
   * Every field.
   *
   * @return defaults
   */
  public static ListFieldsOptions defaults() {
    return new ListFieldsOptions(null, 0);
  }
}
