package signoz

import (
	"context"
	"encoding/json"
	"fmt"
	"strconv"
	"strings"
	"time"
)

// Aggregation is one aggregate expression with the name its values get in the
// result.
type Aggregation struct {
	// Expression is a SigNoz aggregate, for example "count()",
	// "p99(duration_nano)", "avg(duration_nano)", "count_distinct(trace_id)".
	Expression string
	// Alias names the value in AggregateRow.Values / AggregateSeries.Alias
	// (default: the expression).
	Alias string
}

func (a Aggregation) name() string {
	if a.Alias != "" {
		return a.Alias
	}
	return a.Expression
}

// AggregateQuery is a count/percentile query for QueryClient.Aggregate. Build
// it with NewAggregate or as a literal.
type AggregateQuery struct {
	// Signal is SignalLogs or SignalTraces (required).
	Signal Signal
	// Filter is a SigNoz filter expression (empty = all).
	Filter string
	// Aggregations are the values to compute (at least one).
	Aggregations []Aggregation
	// GroupBy are field names to group by (for example "service.name", "name").
	GroupBy []string
	// Start and End bound the query (default: the last hour).
	Start time.Time
	End   time.Time
	// StepInterval > 0 returns time series with that bucket size (rounded to
	// seconds); 0 returns one row per group (scalar).
	StepInterval time.Duration
	// Limit caps the number of groups (default 100, at most MaxQueryLimit).
	Limit int
	// OrderBy orders groups: a group field name or an aggregation alias,
	// optionally followed by " asc" or " desc" (default desc for aliases).
	OrderBy string
}

// AggregateRow is one group of a scalar aggregate.
type AggregateRow struct {
	// Groups maps each GroupBy field to its value (string, number or bool).
	Groups map[string]any `json:"groups"`
	// Values maps each aggregation alias to its value.
	Values map[string]float64 `json:"values"`
}

// AggregatePoint is one bucket of a time series.
type AggregatePoint struct {
	Time  time.Time `json:"time"`
	Value float64   `json:"value"`
}

// AggregateSeries is one time series of a time-series aggregate.
type AggregateSeries struct {
	// Alias is the aggregation the series belongs to.
	Alias string `json:"alias"`
	// Labels maps each GroupBy field to the series' value.
	Labels map[string]any `json:"labels"`
	// Points are the buckets in time order.
	Points []AggregatePoint `json:"points"`
}

// AggregateResult is the result of QueryClient.Aggregate: Rows for scalar
// queries, Series for time series (StepInterval > 0).
type AggregateResult struct {
	Rows   []AggregateRow    `json:"rows"`
	Series []AggregateSeries `json:"series"`
	// Warnings are server warnings (for example unknown fields in the filter).
	Warnings []string `json:"warnings"`
}

// AggregateBuilder builds an AggregateQuery fluently:
//
//	q := signoz.NewAggregate(signoz.SignalTraces).
//		Where("service.name = 'checkout'").
//		Count("calls").
//		Percentile(99, "duration_nano", "p99").
//		GroupBy("name").
//		Query()
type AggregateBuilder struct{ q AggregateQuery }

// NewAggregate starts an aggregate over signal.
func NewAggregate(signal Signal) *AggregateBuilder {
	return &AggregateBuilder{q: AggregateQuery{Signal: signal}}
}

// Where sets the filter expression.
func (b *AggregateBuilder) Where(filter string) *AggregateBuilder {
	b.q.Filter = filter
	return b
}

// Count adds count() named alias.
func (b *AggregateBuilder) Count(alias string) *AggregateBuilder {
	return b.Expr("count()", alias)
}

// CountDistinct adds count_distinct(field) named alias.
func (b *AggregateBuilder) CountDistinct(field, alias string) *AggregateBuilder {
	return b.Expr("count_distinct("+field+")", alias)
}

// Percentile adds pNN(field) named alias (p is 50, 75, 90, 95, 99, ...).
func (b *AggregateBuilder) Percentile(p int, field, alias string) *AggregateBuilder {
	return b.Expr("p"+strconv.Itoa(p)+"("+field+")", alias)
}

// Avg adds avg(field) named alias.
func (b *AggregateBuilder) Avg(field, alias string) *AggregateBuilder {
	return b.Expr("avg("+field+")", alias)
}

// Sum adds sum(field) named alias.
func (b *AggregateBuilder) Sum(field, alias string) *AggregateBuilder {
	return b.Expr("sum("+field+")", alias)
}

// Min adds min(field) named alias.
func (b *AggregateBuilder) Min(field, alias string) *AggregateBuilder {
	return b.Expr("min("+field+")", alias)
}

// Max adds max(field) named alias.
func (b *AggregateBuilder) Max(field, alias string) *AggregateBuilder {
	return b.Expr("max("+field+")", alias)
}

// Expr adds any aggregate expression named alias.
func (b *AggregateBuilder) Expr(expression, alias string) *AggregateBuilder {
	b.q.Aggregations = append(b.q.Aggregations, Aggregation{Expression: expression, Alias: alias})
	return b
}

// GroupBy adds group fields.
func (b *AggregateBuilder) GroupBy(fields ...string) *AggregateBuilder {
	b.q.GroupBy = append(b.q.GroupBy, fields...)
	return b
}

// Between sets the time window.
func (b *AggregateBuilder) Between(start, end time.Time) *AggregateBuilder {
	b.q.Start, b.q.End = start, end
	return b
}

// Step makes the query a time series with buckets of step.
func (b *AggregateBuilder) Step(step time.Duration) *AggregateBuilder {
	b.q.StepInterval = step
	return b
}

// Limit caps the number of groups.
func (b *AggregateBuilder) Limit(n int) *AggregateBuilder {
	b.q.Limit = n
	return b
}

// OrderBy sets the group order ("calls desc", "name asc").
func (b *AggregateBuilder) OrderBy(order string) *AggregateBuilder {
	b.q.OrderBy = order
	return b
}

// Query returns the built query.
func (b *AggregateBuilder) Query() AggregateQuery {
	q := b.q
	q.Aggregations = append([]Aggregation(nil), b.q.Aggregations...)
	q.GroupBy = append([]string(nil), b.q.GroupBy...)
	return q
}

// groupField builds the groupBy reference; service.name is a resource field.
func groupField(name string) fieldRef {
	switch name {
	case "service.name":
		return fieldRef{Name: name, FieldContext: "resource", FieldDataType: "string"}
	case "name":
		return fieldRef{Name: name, FieldContext: "span", FieldDataType: "string"}
	}
	return fieldRef{Name: name}
}

// Aggregate runs an aggregate query (POST /api/v5/query_range, requestType
// scalar or time_series). SigNoz does not echo aliases (columns are named
// __result_<i>); values are mapped back to the caller's aliases by index.
func (q *QueryClient) Aggregate(ctx context.Context, aq AggregateQuery) (AggregateResult, error) {
	const op = "aggregate"
	if aq.Signal != SignalLogs && aq.Signal != SignalTraces {
		return AggregateResult{}, ValidationError(op, "signal must be \"logs\" or \"traces\"")
	}
	if len(aq.Aggregations) == 0 {
		return AggregateResult{}, ValidationError(op, "at least one aggregation is required")
	}
	if aq.StepInterval < 0 {
		return AggregateResult{}, ValidationError(op, "stepInterval must not be negative")
	}
	limit, err := checkLimit(op, aq.Limit, DefaultSearchLimit)
	if err != nil {
		return AggregateResult{}, err
	}
	spec := builderSpec{Signal: aq.Signal, Filter: filterOf(aq.Filter), Limit: limit}
	aliases := make([]string, len(aq.Aggregations))
	for i, a := range aq.Aggregations {
		if strings.TrimSpace(a.Expression) == "" {
			return AggregateResult{}, ValidationError(op, "aggregation expression is empty")
		}
		aliases[i] = a.name()
		spec.Aggregations = append(spec.Aggregations, aggregationSpec(a))
	}
	for _, g := range aq.GroupBy {
		if err := checkField(g); err != nil {
			return AggregateResult{}, err
		}
		spec.GroupBy = append(spec.GroupBy, groupField(g))
	}
	if aq.OrderBy != "" {
		key, dir, _ := strings.Cut(strings.TrimSpace(aq.OrderBy), " ")
		dir = strings.ToLower(strings.TrimSpace(dir))
		if dir == "" {
			dir = "desc"
		}
		if dir != "asc" && dir != "desc" {
			return AggregateResult{}, ValidationError(op, "orderBy direction must be asc or desc")
		}
		spec.Order = []orderSpec{{Key: fieldRef{Name: key}, Direction: dir}}
	}
	requestType := "scalar"
	if aq.StepInterval > 0 {
		requestType = "time_series"
		spec.StepInterval = max(int64(aq.StepInterval/time.Second), 1)
	}
	start, end := q.window(TimeRange{Start: aq.Start, End: aq.End})
	raw, warnings, err := q.queryRange(ctx, op, requestType, start, end, spec)
	if err != nil {
		return AggregateResult{}, err
	}
	out := AggregateResult{Rows: []AggregateRow{}, Series: []AggregateSeries{}, Warnings: warnings}
	if raw == nil {
		return out, nil
	}
	if requestType == "scalar" {
		return parseScalar(op, raw, aliases, out)
	}
	return parseTimeSeries(op, raw, aliases, out)
}

func parseScalar(op string, raw json.RawMessage, aliases []string, out AggregateResult) (AggregateResult, error) {
	var res struct {
		Columns []struct {
			Name             string `json:"name"`
			ColumnType       string `json:"columnType"`
			AggregationIndex int    `json:"aggregationIndex"`
		} `json:"columns"`
		Data [][]any `json:"data"`
	}
	if err := decode(op, raw, &res); err != nil {
		return out, err
	}
	for _, row := range res.Data {
		r := AggregateRow{Groups: map[string]any{}, Values: map[string]float64{}}
		for i, col := range res.Columns {
			if i >= len(row) {
				break
			}
			if col.ColumnType == "aggregation" {
				name := fmt.Sprintf("__result_%d", col.AggregationIndex)
				if col.AggregationIndex >= 0 && col.AggregationIndex < len(aliases) {
					name = aliases[col.AggregationIndex]
				}
				if f, ok := floatOf(row[i]); ok {
					r.Values[name] = f
				}
				continue
			}
			r.Groups[col.Name] = NormalizeAttrValue(row[i])
		}
		out.Rows = append(out.Rows, r)
	}
	return out, nil
}

func parseTimeSeries(op string, raw json.RawMessage, aliases []string, out AggregateResult) (AggregateResult, error) {
	var res struct {
		Aggregations []struct {
			Index  int `json:"index"`
			Series []struct {
				Labels []struct {
					Key struct {
						Name string `json:"name"`
					} `json:"key"`
					Value any `json:"value"`
				} `json:"labels"`
				Values []struct {
					Timestamp json.Number `json:"timestamp"`
					Value     any         `json:"value"`
				} `json:"values"`
			} `json:"series"`
		} `json:"aggregations"`
	}
	if err := decode(op, raw, &res); err != nil {
		return out, err
	}
	for _, agg := range res.Aggregations {
		alias := fmt.Sprintf("__result_%d", agg.Index)
		if agg.Index >= 0 && agg.Index < len(aliases) {
			alias = aliases[agg.Index]
		}
		for _, s := range agg.Series {
			series := AggregateSeries{Alias: alias, Labels: map[string]any{}, Points: []AggregatePoint{}}
			for _, l := range s.Labels {
				series.Labels[l.Key.Name] = NormalizeAttrValue(l.Value)
			}
			for _, v := range s.Values {
				ms, _ := v.Timestamp.Int64()
				f, _ := floatOf(v.Value)
				series.Points = append(series.Points, AggregatePoint{Time: time.UnixMilli(ms).UTC(), Value: f})
			}
			out.Series = append(out.Series, series)
		}
	}
	return out, nil
}
