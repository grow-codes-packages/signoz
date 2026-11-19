package codes.grow.packages.signoz;

/**
 * One aggregate expression with the name its values get in the result.
 *
 * @param expression SigNoz aggregate, for example {@code count()}, {@code p99(duration_nano)},
 *     {@code avg(duration_nano)} or {@code count_distinct(trace_id)}
 * @param alias name of the value in {@link AggregateRow#values()} and {@link
 *     AggregateSeries#alias()} ({@code null} = the expression)
 */
public record Aggregation(String expression, String alias) {
  /**
   * The name the value gets in results.
   *
   * @return the alias, or the expression when no alias is set
   */
  public String name() {
    return alias == null || alias.isEmpty() ? expression : alias;
  }
}
