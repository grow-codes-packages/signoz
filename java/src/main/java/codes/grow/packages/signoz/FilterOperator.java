package codes.grow.packages.signoz;

/** Comparison operator of {@link FilterBuilder#where}. */
public enum FilterOperator {
  /** {@code =}. */
  EQ("="),
  /** {@code !=}. */
  NE("!="),
  /** {@code <}. */
  LT("<"),
  /** {@code <=}. */
  LTE("<="),
  /** {@code >}. */
  GT(">"),
  /** {@code >=}. */
  GTE(">=");

  private final String symbol;

  FilterOperator(String symbol) {
    this.symbol = symbol;
  }

  /**
   * Returns the operator as written in a filter expression.
   *
   * @return the symbol, for example {@code ">="}
   */
  public String symbol() {
    return symbol;
  }
}
