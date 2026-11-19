package codes.grow.packages.signoz;

/** SigNoz telemetry signal of a query-builder query. */
public enum Signal {
  /** Log records ({@code "logs"}). */
  LOGS("logs"),
  /** Spans ({@code "traces"}). */
  TRACES("traces");

  private final String wire;

  Signal(String wire) {
    this.wire = wire;
  }

  /**
   * Returns the wire value.
   *
   * @return {@code "logs"} or {@code "traces"}
   */
  public String wireValue() {
    return wire;
  }
}
