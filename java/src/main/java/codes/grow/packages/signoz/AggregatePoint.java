package codes.grow.packages.signoz;

import java.time.Instant;

/**
 * One bucket of a time series.
 *
 * @param time bucket start
 * @param value aggregated value
 */
public record AggregatePoint(Instant time, double value) {}
