package signoz

import (
	"context"
	"fmt"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

// TraceQuery is a spans query for QueryClient.QueryTraces. The zero value lists
// the newest 100 spans of the last hour.
type TraceQuery struct {
	// Service keeps spans of this service.name.
	Service string
	// Operation keeps spans with this name.
	Operation string
	// Filter is an extra SigNoz filter expression, for example
	// "duration_nano >= 120000000 AND http.method = 'GET'".
	Filter string
	// Start and End bound the query (default: the last hour).
	Start time.Time
	End   time.Time
	// Limit caps the number of spans (default 100, at most MaxQueryLimit).
	Limit int
	// Order is OrderDesc (newest first, default) or OrderAsc.
	Order SortOrder
	// Offset skips that many spans (offset paging over a fixed window).
	Offset int
}

// SpanQueryResult is the result of QueryClient.QueryTraces: one Span per
// matching span.
type SpanQueryResult struct {
	Spans []Span `json:"spans"`
	// Warnings are server warnings (for example unknown fields in the filter).
	Warnings []string `json:"warnings"`
}

func (tq TraceQuery) filter() string {
	f := NewFilter()
	if tq.Service != "" {
		f = f.Eq("service.name", tq.Service)
	}
	if tq.Operation != "" {
		f = f.Eq("name", tq.Operation)
	}
	return AndFilters(f.String(), tq.Filter)
}

// QueryTraces runs a raw spans query (POST /api/v5/query_range, signal
// traces) and returns one normalized Span per matching span.
func (q *QueryClient) QueryTraces(ctx context.Context, tq TraceQuery) (SpanQueryResult, error) {
	const op = "queryTraces"
	limit, err := checkLimit(op, tq.Limit, DefaultSearchLimit)
	if err != nil {
		return SpanQueryResult{}, err
	}
	order, err := checkOrder(op, tq.Order)
	if err != nil {
		return SpanQueryResult{}, err
	}
	if tq.Offset < 0 {
		return SpanQueryResult{}, ValidationError(op, "offset must be >= 0")
	}
	start, end := q.window(TimeRange{Start: tq.Start, End: tq.End})
	spec := builderSpec{
		Signal: SignalTraces,
		Filter: filterOf(tq.filter()),
		Order:  []orderSpec{{Key: fieldRef{Name: "timestamp"}, Direction: string(order)}},
		Limit:  limit,
		Offset: tq.Offset,
	}
	raw, warnings, err := q.queryRange(ctx, op, "raw", start, end, spec)
	if err != nil {
		return SpanQueryResult{}, err
	}
	var res rawResult
	if raw != nil {
		if err := decode(op, raw, &res); err != nil {
			return SpanQueryResult{}, err
		}
	}
	out := SpanQueryResult{Spans: make([]Span, 0, len(res.Rows)), Warnings: warnings}
	for _, row := range res.Rows {
		out.Spans = append(out.Spans, spanFromRaw(row))
	}
	return out, nil
}

// spanKindOf maps SigNoz kind_string ("Server", "Client", ...) to SpanKind.
func spanKindOf(s string) SpanKind {
	switch k := SpanKind(strings.ToLower(strings.TrimSpace(s))); k {
	case SpanKindInternal, SpanKindServer, SpanKindClient, SpanKindProducer, SpanKindConsumer:
		return k
	}
	return SpanKindUnspecified
}

// spanStatusOf maps status_code (0/1/2, omitted for Unset) and its string form.
func spanStatusOf(d map[string]any) SpanStatus {
	st := SpanStatus{Code: SpanStatusUnset, Message: str(d, "status_message")}
	code, ok := num(d, "status_code")
	if !ok {
		switch strings.ToLower(str(d, "status_code_string")) {
		case "ok":
			code = 1
		case "error":
			code = 2
		}
	}
	switch code {
	case 1:
		st.Code = SpanStatusOK
	case 2:
		st.Code = SpanStatusError
	}
	return st
}

func spanBase(d map[string]any) Span {
	res := mapOf(d, "resource")
	s := Span{
		TraceID:      strings.ToLower(str(d, "trace_id")),
		SpanID:       strings.ToLower(str(d, "span_id")),
		ParentSpanID: strings.ToLower(str(d, "parent_span_id")),
		Name:         str(d, "name"),
		Kind:         spanKindOf(str(d, "kind_string")),
		ServiceName:  str(res, "service.name"),
		Status:       spanStatusOf(d),
		Attributes:   attrsOf(mapOf(d, "attributes")),
		Events:       []SpanEvent{},
		Links:        []SpanLink{},
	}
	dur, _ := num(d, "duration_nano")
	s.DurationMicros = dur / 1000
	return s
}

func eventsOf(d map[string]any, attrKey string) []SpanEvent {
	items, _ := d["events"].([]any)
	out := []SpanEvent{}
	for _, it := range items {
		e, ok := it.(map[string]any)
		if !ok {
			continue
		}
		ns, _ := num(e, "timeUnixNano")
		out = append(out, SpanEvent{Name: str(e, "name"), TimeMicros: ns / 1000, Attributes: attrsOf(mapOf(e, attrKey))})
	}
	return out
}

// spanFromRaw maps a raw spans row. Start is the RFC 3339 ns row timestamp.
func spanFromRaw(row rawRow) Span {
	d := row.Data
	s := spanBase(d)
	s.StartMicros = rowNanos(row) / 1000
	s.EndMicros = s.StartMicros + s.DurationMicros
	s.Events = eventsOf(d, "attributes")
	links, _ := d["links"].([]any)
	for _, it := range links {
		l, ok := it.(map[string]any)
		if !ok {
			continue
		}
		sid := strings.ToLower(str(l, "spanId"))
		if sid == "" || sid == s.ParentSpanID {
			continue // the parent reference, not a span link
		}
		s.Links = append(s.Links, SpanLink{TraceID: strings.ToLower(str(l, "traceId")), SpanID: sid, Attributes: map[string]any{}})
	}
	return s
}

// spanFromWaterfall maps a waterfall span (time_unix is milliseconds; links are
// the FOLLOWS_FROM references; SigNoz drops link attributes).
func spanFromWaterfall(d map[string]any) Span {
	s := spanBase(d)
	ms, _ := num(d, "time_unix")
	s.StartMicros = ms * 1000
	s.EndMicros = s.StartMicros + s.DurationMicros
	s.Events = eventsOf(d, "attributeMap")
	refs, _ := d["references"].([]any)
	for _, it := range refs {
		r, ok := it.(map[string]any)
		if !ok || !strings.EqualFold(str(r, "refType"), "FOLLOWS_FROM") || str(r, "spanId") == "" {
			continue
		}
		s.Links = append(s.Links, SpanLink{TraceID: strings.ToLower(str(r, "traceId")), SpanID: strings.ToLower(str(r, "spanId")), Attributes: map[string]any{}})
	}
	return s
}

type waterfall struct {
	StartTimestampMillis int64            `json:"startTimestampMillis"`
	EndTimestampMillis   int64            `json:"endTimestampMillis"`
	RootServiceName      string           `json:"rootServiceName"`
	RootEntryPoint       string           `json:"rootServiceEntryPoint"`
	TotalSpansCount      int              `json:"totalSpansCount"`
	Spans                []map[string]any `json:"spans"`
	HasMore              bool             `json:"hasMore"`
}

func (q *QueryClient) waterfall(ctx context.Context, op, traceID string) (*waterfall, error) {
	raw, err := q.call(ctx, op, http.MethodPost, "/api/v4/traces/"+traceID+"/waterfall", nil, struct{}{}, true)
	if err != nil {
		if CodeOf(err) == ErrorCodeNotFound {
			return nil, nil
		}
		return nil, err
	}
	var w waterfall
	if err := decode(op, raw, &w); err != nil {
		return nil, err
	}
	return &w, nil
}

// GetTrace returns the trace with every span (POST
// /api/v4/traces/{id}/waterfall), or nil when it does not exist. Span start
// times have millisecond precision. A trace larger than the server's waterfall
// window is completed with raw span queries.
func (q *QueryClient) GetTrace(ctx context.Context, traceID string) (*Trace, error) {
	const op = "getTrace"
	id, err := NormalizeTraceID(traceID)
	if err != nil {
		return nil, err
	}
	w, err := q.waterfall(ctx, op, id)
	if err != nil || w == nil || len(w.Spans) == 0 {
		return nil, err
	}
	t := &Trace{TraceID: id}
	if w.HasMore || w.TotalSpansCount > len(w.Spans) {
		spans, err := q.allSpans(ctx, id, time.UnixMilli(w.StartTimestampMillis), time.UnixMilli(w.EndTimestampMillis+1))
		if err != nil {
			return nil, err
		}
		t.Spans = spans
	} else {
		for _, s := range w.Spans {
			t.Spans = append(t.Spans, spanFromWaterfall(s))
		}
	}
	SortSpans(t.Spans)
	return t, nil
}

// allSpans pages through every span of a trace with a frozen window.
func (q *QueryClient) allSpans(ctx context.Context, id string, start, end time.Time) ([]Span, error) {
	var out []Span
	for {
		page, err := q.QueryTraces(ctx, TraceQuery{
			Filter: "trace_id = " + QuoteFilterString(id), Start: start, End: end,
			Limit: MaxQueryLimit, Order: OrderAsc, Offset: len(out),
		})
		if err != nil {
			return nil, err
		}
		out = append(out, page.Spans...)
		if len(page.Spans) < MaxQueryLimit {
			return out, nil
		}
	}
}

// WaitForTrace polls GetTrace until the trace has at least opts.MinSpans spans
// (TIMEOUT after opts.Timeout, default 30 s).
func (q *QueryClient) WaitForTrace(ctx context.Context, traceID string, opts WaitForTraceOptions) (*Trace, error) {
	return WaitForTrace(ctx, q, traceID, opts)
}

// searchConcurrency bounds the parallel waterfall calls of SearchTraces.
const searchConcurrency = 4

// SearchTraces finds traces (requestType "trace"), newest first, at most
// tsq.Limit (default 20): a trace matches when any of its spans matches
// service, operation, tags and the duration bounds. StartMicros, SpanCount
// (all spans of the trace), RootServiceName and RootName come from each hit's
// waterfall (millisecond precision); DurationMicros is the root span's.
func (q *QueryClient) SearchTraces(ctx context.Context, tsq TraceSearchQuery) ([]TraceSummary, error) {
	const op = "searchTraces"
	tsq = tsq.WithDefaults()
	limit, err := checkLimit(op, tsq.Limit, DefaultTraceSearchLimit)
	if err != nil {
		return nil, err
	}
	f := NewFilter()
	if tsq.Service != "" {
		f = f.Eq("service.name", tsq.Service)
	}
	if tsq.Operation != "" {
		f = f.Eq("name", tsq.Operation)
	}
	keys := make([]string, 0, len(tsq.Tags))
	for k := range tsq.Tags {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	for _, k := range keys {
		f = f.Eq(k, tsq.Tags[k])
	}
	if tsq.MinDuration > 0 {
		f = f.Gte("duration_nano", tsq.MinDuration.Nanoseconds())
	}
	if tsq.MaxDuration > 0 {
		f = f.Lte("duration_nano", tsq.MaxDuration.Nanoseconds())
	}
	expr, err := f.Build()
	if err != nil {
		return nil, err
	}
	start, end := q.window(TimeRange{Start: tsq.Start, End: tsq.End})
	raw, _, err := q.queryRange(ctx, op, "trace", start, end, builderSpec{
		Signal: SignalTraces,
		Filter: filterOf(expr),
		Order:  []orderSpec{{Key: fieldRef{Name: "timestamp"}, Direction: string(OrderDesc)}},
		Limit:  limit,
	})
	if err != nil {
		return nil, err
	}
	var res rawResult
	if raw != nil {
		if err := decode(op, raw, &res); err != nil {
			return nil, err
		}
	}
	out := make([]TraceSummary, 0, len(res.Rows))
	for _, row := range res.Rows {
		d := row.Data
		id, err := NormalizeTraceID(str(d, "trace_id"))
		if err != nil {
			continue
		}
		dur, _ := num(d, "duration_nano")
		count, _ := num(d, "span_count")
		out = append(out, TraceSummary{
			TraceID:         id,
			RootServiceName: str(d, "service.name"),
			RootName:        str(d, "name"),
			DurationMicros:  dur / 1000,
			SpanCount:       int(count),
		})
	}
	errs := make([]error, len(out))
	sem := make(chan struct{}, searchConcurrency)
	var wg sync.WaitGroup
	for i := range out {
		wg.Add(1)
		sem <- struct{}{}
		go func(s *TraceSummary, errp *error) {
			defer func() { <-sem; wg.Done() }()
			w, err := q.waterfall(ctx, op, s.TraceID)
			if err != nil {
				*errp = err
				return
			}
			if w == nil {
				return
			}
			s.StartMicros = w.StartTimestampMillis * 1000
			if w.TotalSpansCount > 0 {
				s.SpanCount = w.TotalSpansCount
			}
			if w.RootServiceName != "" {
				s.RootServiceName = w.RootServiceName
			}
			if w.RootEntryPoint != "" {
				s.RootName = w.RootEntryPoint
			}
		}(&out[i], &errs[i])
	}
	wg.Wait()
	for _, err := range errs {
		if err != nil {
			return nil, err
		}
	}
	return out, nil
}

// groupNames runs count() grouped by field and returns the sorted group values.
func (q *QueryClient) groupNames(ctx context.Context, field, filter string, r TimeRange) ([]string, error) {
	res, err := q.Aggregate(ctx, AggregateQuery{
		Signal: SignalTraces, Filter: filter, GroupBy: []string{field},
		Aggregations: []Aggregation{{Expression: "count()", Alias: "count"}},
		Start:        r.Start, End: r.End, Limit: MaxQueryLimit,
	})
	if err != nil {
		return nil, err
	}
	seen := map[string]bool{}
	out := []string{}
	for _, row := range res.Rows {
		v, ok := row.Groups[field]
		if !ok {
			continue
		}
		s := fmt.Sprint(v)
		if s != "" && !seen[s] {
			seen[s] = true
			out = append(out, s)
		}
	}
	sort.Strings(out)
	return out, nil
}

// ListServices returns every service with spans in the last hour, sorted.
func (q *QueryClient) ListServices(ctx context.Context) ([]string, error) {
	return q.ListServicesInRange(ctx, TimeRange{})
}

// ListServicesInRange returns every service with spans in r (count() grouped
// by service.name), sorted.
func (q *QueryClient) ListServicesInRange(ctx context.Context, r TimeRange) ([]string, error) {
	return q.groupNames(ctx, "service.name", "", r)
}

// ListOperations returns the span names of service in the last hour, sorted.
func (q *QueryClient) ListOperations(ctx context.Context, service string) ([]string, error) {
	return q.ListOperationsInRange(ctx, service, TimeRange{})
}

// ListOperationsInRange returns the span names of service in r, sorted.
func (q *QueryClient) ListOperationsInRange(ctx context.Context, service string, r TimeRange) ([]string, error) {
	if strings.TrimSpace(service) == "" {
		return nil, ValidationError("listOperations", "service is required")
	}
	return q.groupNames(ctx, "name", NewFilter().Eq("service.name", service).String(), r)
}

// GetDependencies returns the service dependency graph in the window (POST
// /api/v1/dependency_graph; default the last hour), sorted by parent, child.
// Edges come from client→server spans and from client spans with db.system or
// messaging.system.
func (q *QueryClient) GetDependencies(ctx context.Context, opts DependencyOptions) ([]DependencyLink, error) {
	const op = "getDependencies"
	start, end := q.window(TimeRange(opts))
	body := map[string]any{
		"start": strconv.FormatInt(start.UnixNano(), 10),
		"end":   strconv.FormatInt(end.UnixNano(), 10),
		"tags":  []any{},
	}
	raw, err := q.call(ctx, op, http.MethodPost, "/api/v1/dependency_graph", nil, body, false)
	if err != nil {
		return nil, err
	}
	var edges []map[string]any
	if err := decode(op, raw, &edges); err != nil {
		return nil, err
	}
	out := make([]DependencyLink, 0, len(edges))
	for _, e := range edges {
		n, _ := num(e, "callCount")
		out = append(out, DependencyLink{Parent: str(e, "parent"), Child: str(e, "child"), CallCount: n})
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Parent != out[j].Parent {
			return out[i].Parent < out[j].Parent
		}
		return out[i].Child < out[j].Child
	})
	return out, nil
}
