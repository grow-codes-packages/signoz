package signoz

import (
	"bytes"
	"context"
	"encoding/json"
	"log/slog"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"
)

// DefaultQueryWindow is the time window of a query without an explicit Start:
// the hour before End (End defaults to now).
const DefaultQueryWindow = time.Hour

// MaxQueryLimit is the largest row limit the SigNoz query API accepts.
const MaxQueryLimit = 10000

// Signal is a SigNoz telemetry signal for the query builder.
type Signal string

// Signals understood by the query API.
const (
	SignalLogs   Signal = "logs"
	SignalTraces Signal = "traces"
)

// TimeRange is a query time window. Zero values mean the defaults: End = now,
// Start = End - DefaultQueryWindow.
type TimeRange struct {
	Start time.Time
	End   time.Time
}

// QueryClient reads logs, traces, services and aggregations from the SigNoz
// query API (POST /api/v5/query_range and friends) with the SIGNOZ-API-KEY
// header. Get it from Client.Query. It implements LogQueryClient and
// TraceQueryClient and is safe for concurrent use.
type QueryClient struct {
	t      *httpTransport
	apiKey Secret
	now    func() time.Time

	tailsMu sync.Mutex
	tails   map[*sseTail]struct{}
}

var (
	_ LogQueryClient   = (*QueryClient)(nil)
	_ TraceQueryClient = (*QueryClient)(nil)
)

// warn reports the warnings SigNoz attaches to a successful query (for example
// an unknown field in a filter) at warn level through the configured logger.
func (q *QueryClient) warn(op string, warnings []string) {
	for _, w := range warnings {
		q.t.log(slog.LevelWarn, "signoz query warning", slog.String("operation", op), slog.String("warning", w))
	}
}

func (q *QueryClient) authHeader() http.Header {
	h := http.Header{}
	if !q.apiKey.IsZero() {
		h.Set(APIKeyHeader, q.apiKey.Reveal())
	}
	return h
}

// window resolves a time range against the clock.
func (q *QueryClient) window(r TimeRange) (time.Time, time.Time) {
	end := r.End
	if end.IsZero() {
		end = q.now()
	}
	start := r.Start
	if start.IsZero() {
		start = end.Add(-DefaultQueryWindow)
	}
	return start, end
}

// checkNotHTML maps the SPA index page (served with 200 for unknown routes) to
// UNSUPPORTED.
func checkNotHTML(op string, res *response) error {
	ct := strings.ToLower(res.Header.Get("Content-Type"))
	body := bytes.TrimSpace(res.Body)
	if strings.Contains(ct, "text/html") || bytes.HasPrefix(body, []byte("<!")) || bytes.HasPrefix(bytes.ToLower(body), []byte("<html")) {
		return &Error{Code: ErrorCodeUnsupported, Message: op + ": endpoint not available on this server version", HTTPStatus: res.Status, RequestID: res.RequestID}
	}
	return nil
}

// envelope is the {"status","data","error"} wrapper of /api/v2+ and /api/v5.
type envelope struct {
	Status string          `json:"status"`
	Data   json.RawMessage `json:"data"`
}

// call performs an authenticated request and returns the raw JSON body
// (unwrapped from the envelope when wrapped is true).
func (q *QueryClient) call(ctx context.Context, op, method, path string, query url.Values, body any, wrapped bool) ([]byte, error) {
	res, err := q.t.do(ctx, &request{
		Method: method, Path: path, Query: query, JSON: body,
		Header: q.authHeader(), Operation: op, Idempotent: true,
	})
	if err != nil {
		return nil, err
	}
	if err := checkNotHTML(op, res); err != nil {
		return nil, err
	}
	if !wrapped {
		return res.Body, nil
	}
	var env envelope
	if err := res.decodeJSON(op, &env); err != nil {
		return nil, err
	}
	if env.Status != "" && env.Status != "success" {
		return nil, &Error{Code: ErrorCodeUnavailable, Message: op + ": unexpected response status " + env.Status, HTTPStatus: res.Status, Retryable: true}
	}
	return env.Data, nil
}

// decode decodes raw JSON with numbers kept as json.Number (nanosecond
// timestamps exceed 2^53).
func decode(op string, raw []byte, out any) error {
	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.UseNumber()
	if err := dec.Decode(out); err != nil {
		return &Error{Code: ErrorCodeUnavailable, Message: op + ": invalid JSON response"}
	}
	return nil
}

// builderSpec is spec of a v5 builder_query.
type builderSpec struct {
	Name         string            `json:"name"`
	Signal       Signal            `json:"signal"`
	Filter       *filterSpec       `json:"filter,omitempty"`
	SelectFields []fieldRef        `json:"selectFields,omitempty"`
	Aggregations []aggregationSpec `json:"aggregations,omitempty"`
	GroupBy      []fieldRef        `json:"groupBy,omitempty"`
	Order        []orderSpec       `json:"order,omitempty"`
	Limit        int               `json:"limit,omitempty"`
	Offset       int               `json:"offset,omitempty"`
	StepInterval int64             `json:"stepInterval,omitempty"`
}

type filterSpec struct {
	Expression string `json:"expression"`
}

type fieldRef struct {
	Name          string `json:"name"`
	FieldContext  string `json:"fieldContext,omitempty"`
	FieldDataType string `json:"fieldDataType,omitempty"`
}

type aggregationSpec struct {
	Expression string `json:"expression"`
	Alias      string `json:"alias,omitempty"`
}

type orderSpec struct {
	Key       fieldRef `json:"key"`
	Direction string   `json:"direction"`
}

type queryRangeRequest struct {
	Start          int64  `json:"start"`
	End            int64  `json:"end"`
	RequestType    string `json:"requestType"`
	CompositeQuery struct {
		Queries []builderQuery `json:"queries"`
	} `json:"compositeQuery"`
}

type builderQuery struct {
	Type string      `json:"type"`
	Spec builderSpec `json:"spec"`
}

// queryRangeData is data of a v5 query_range response.
type queryRangeData struct {
	Type    string `json:"type"`
	Warning *struct {
		Message  string `json:"message"`
		Warnings []struct {
			Message string `json:"message"`
		} `json:"warnings"`
	} `json:"warning"`
	Data struct {
		Results []json.RawMessage `json:"results"`
	} `json:"data"`
}

// queryRange posts one builder query and returns its first result plus the
// server warnings (also logged).
func (q *QueryClient) queryRange(ctx context.Context, op, requestType string, start, end time.Time, spec builderSpec) (json.RawMessage, []string, error) {
	if spec.Name == "" {
		spec.Name = "A"
	}
	if !end.After(start) {
		return nil, nil, ValidationError(op, "start must be before end")
	}
	req := queryRangeRequest{Start: start.UnixMilli(), End: end.UnixMilli(), RequestType: requestType}
	req.CompositeQuery.Queries = []builderQuery{{Type: "builder_query", Spec: spec}}
	raw, err := q.call(ctx, op, http.MethodPost, "/api/v5/query_range", nil, req, true)
	if err != nil {
		return nil, nil, err
	}
	var data queryRangeData
	if err := decode(op, raw, &data); err != nil {
		return nil, nil, err
	}
	msgs := q.warnings(op, data)
	if len(data.Data.Results) == 0 {
		return nil, msgs, nil
	}
	return data.Data.Results[0], msgs, nil
}

// warnings returns the redacted warning messages of a query_range response
// (also logged).
func (q *QueryClient) warnings(op string, data queryRangeData) []string {
	msgs := []string{}
	if data.Warning != nil {
		for _, w := range data.Warning.Warnings {
			if w.Message != "" {
				msgs = append(msgs, w.Message)
			}
		}
		if len(msgs) == 0 && data.Warning.Message != "" {
			msgs = append(msgs, data.Warning.Message)
		}
		for i, m := range msgs {
			msgs[i] = q.t.redact(m)
		}
		q.warn(op, msgs)
	}
	return msgs
}

// QueryRangeResult is the result of QueryClient.QueryRange.
type QueryRangeResult struct {
	// Data is the "data" member of the response envelope, undecoded.
	Data json.RawMessage `json:"data"`
	// Warnings are the server warnings (also logged).
	Warnings []string `json:"warnings"`
}

// QueryRange posts a raw request body to POST /api/v5/query_range and returns
// the envelope's data plus the server warnings. It is the escape hatch for
// queries the typed helpers do not cover (formulas, PromQL, ClickHouse SQL,
// several queries at once).
func (q *QueryClient) QueryRange(ctx context.Context, body any) (QueryRangeResult, error) {
	const op = "queryRange"
	raw, err := q.call(ctx, op, http.MethodPost, "/api/v5/query_range", nil, body, true)
	if err != nil {
		return QueryRangeResult{}, err
	}
	var data queryRangeData
	_ = json.Unmarshal(raw, &data) // warnings only; Data is returned as sent by the server
	return QueryRangeResult{Data: json.RawMessage(raw), Warnings: q.warnings(op, data)}, nil
}

// rawRow is one row of a raw (logs / spans) result.
type rawRow struct {
	Timestamp string         `json:"timestamp"`
	Data      map[string]any `json:"data"`
}

type rawResult struct {
	Rows []rawRow `json:"rows"`
}

// checkLimit returns def for 0 and a VALIDATION error outside 1..MaxQueryLimit.
func checkLimit(op string, limit, def int) (int, error) {
	switch {
	case limit == 0:
		return def, nil
	case limit < 0 || limit > MaxQueryLimit:
		return 0, ValidationError(op, "limit must be between 1 and "+strconv.Itoa(MaxQueryLimit))
	}
	return limit, nil
}

// checkOrder returns OrderDesc for "" and a VALIDATION error for unknown orders.
func checkOrder(op string, order SortOrder) (SortOrder, error) {
	switch order {
	case "":
		return OrderDesc, nil
	case OrderAsc, OrderDesc:
		return order, nil
	}
	return "", ValidationError(op, "order must be \"asc\" or \"desc\"")
}

func filterOf(expr string) *filterSpec {
	if strings.TrimSpace(expr) == "" {
		return nil
	}
	return &filterSpec{Expression: expr}
}

// helpers to read loosely typed row values.

func str(m map[string]any, key string) string {
	switch v := m[key].(type) {
	case string:
		return v
	case json.Number:
		return v.String()
	case nil:
		return ""
	case bool:
		if v {
			return "true"
		}
		return "false"
	}
	return ""
}

func num(m map[string]any, key string) (int64, bool) {
	switch v := m[key].(type) {
	case json.Number:
		if i, err := v.Int64(); err == nil {
			return i, true
		}
		if f, err := v.Float64(); err == nil {
			return int64(f), true
		}
	case float64:
		return int64(v), true
	case string:
		n := json.Number(v)
		if i, err := n.Int64(); err == nil {
			return i, true
		}
	}
	return 0, false
}

func floatOf(v any) (float64, bool) {
	switch x := v.(type) {
	case json.Number:
		f, err := x.Float64()
		return f, err == nil
	case float64:
		return x, true
	case int64:
		return float64(x), true
	}
	return 0, false
}

func mapOf(m map[string]any, key string) map[string]any {
	v, _ := m[key].(map[string]any)
	return v
}

func attrsOf(maps ...map[string]any) map[string]AttrValue {
	out := map[string]AttrValue{}
	for _, m := range maps {
		for k, v := range m {
			if n := NormalizeAttrValue(v); n != nil {
				out[k] = n
			}
		}
	}
	return out
}
