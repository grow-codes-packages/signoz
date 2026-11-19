package signoz

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"time"
)

// LogQuery is a logs query for QueryClient.QueryLogs. The zero value lists the
// newest 100 records of the last hour.
type LogQuery struct {
	// Filter is a SigNoz filter expression (build one with FilterBuilder), for
	// example "service.name = 'checkout' AND severity_text IN ('ERROR')". Bare
	// words search the body ("timeout" or "'connection reset'"). Empty matches all.
	Filter string
	// Start and End bound the search (End exclusive). Zero values mean
	// End = now, Start = End - DefaultQueryWindow.
	Start time.Time
	End   time.Time
	// Limit is the page size (default 100, at most MaxQueryLimit).
	Limit int
	// Order is OrderDesc (newest first, default) or OrderAsc.
	Order SortOrder
	// Cursor continues a previous page (LogSearchResult.NextCursor).
	Cursor string
	// SelectFields limits the returned columns (id and timestamp are always
	// returned). Empty returns every column.
	SelectFields []string
}

// logCursor is the SDK's own keyset cursor. The server's nextCursor is not
// used: it is the last row's millisecond and skips the other rows of that
// millisecond. The next page instead repeats the boundary millisecond and
// excludes the ids already returned in it.
type logCursor struct {
	V     int       `json:"v"`
	Start int64     `json:"s"`
	End   int64     `json:"e"`
	Order SortOrder `json:"o"`
	Ms    int64     `json:"m"`
	IDs   []string  `json:"x"`
}

func encodeCursor(c logCursor) string {
	b, _ := json.Marshal(c) // plain struct: never fails
	return base64.RawURLEncoding.EncodeToString(b)
}

func decodeCursor(op, s string) (logCursor, error) {
	var c logCursor
	b, err := base64.RawURLEncoding.DecodeString(s)
	if err != nil || json.Unmarshal(b, &c) != nil || c.V != 1 || (c.Order != OrderAsc && c.Order != OrderDesc) {
		return c, ValidationError(op, "invalid cursor")
	}
	return c, nil
}

// LogQueryResult is one page of QueryClient.QueryLogs.
type LogQueryResult struct {
	// Records are the normalized records of the page.
	Records []LogRecord `json:"records"`
	// NextCursor is set when the page is full; pass it back as LogQuery.Cursor
	// (with the same Filter) for the next page.
	NextCursor string `json:"nextCursor,omitempty"`
	// Warnings are server warnings, for example an unknown field in the filter
	// (SigNoz then answers with no rows instead of an error).
	Warnings []string `json:"warnings"`
}

// QueryLogs runs a raw logs query (POST /api/v5/query_range) and returns one
// page of normalized records, ordered by timestamp then id.
//
// Paging uses the SDK's own cursor, not the server's nextCursor (which skips
// the other rows of the boundary millisecond): the next page narrows the window
// to the boundary millisecond and excludes the ids already returned there.
func (q *QueryClient) QueryLogs(ctx context.Context, lq LogQuery) (LogQueryResult, error) {
	const op = "queryLogs"
	limit, err := checkLimit(op, lq.Limit, DefaultSearchLimit)
	if err != nil {
		return LogQueryResult{}, err
	}
	order, err := checkOrder(op, lq.Order)
	if err != nil {
		return LogQueryResult{}, err
	}
	start, end := q.window(TimeRange{Start: lq.Start, End: lq.End})
	filter := lq.Filter
	var prev *logCursor
	if lq.Cursor != "" {
		c, err := decodeCursor(op, lq.Cursor)
		if err != nil {
			return LogQueryResult{}, err
		}
		prev, order = &c, c.Order
		start, end = time.UnixMilli(c.Start), time.UnixMilli(c.End)
		if order == OrderDesc {
			end = time.UnixMilli(c.Ms + 1)
		} else {
			start = time.UnixMilli(c.Ms)
		}
		filter = AndFilters(filter, notInIDs(c.IDs))
	}
	spec := builderSpec{
		Signal: SignalLogs,
		Filter: filterOf(filter),
		Order: []orderSpec{
			{Key: fieldRef{Name: "timestamp"}, Direction: string(order)},
			{Key: fieldRef{Name: "id"}, Direction: string(order)},
		},
		Limit: limit,
	}
	for _, f := range lq.SelectFields {
		spec.SelectFields = append(spec.SelectFields, fieldRef{Name: f})
	}
	raw, warnings, err := q.queryRange(ctx, op, "raw", start, end, spec)
	if err != nil {
		return LogQueryResult{}, err
	}
	var res rawResult
	if raw != nil {
		if err := decode(op, raw, &res); err != nil {
			return LogQueryResult{}, err
		}
	}
	out := LogQueryResult{Records: make([]LogRecord, 0, len(res.Rows)), Warnings: warnings}
	for _, row := range res.Rows {
		out.Records = append(out.Records, logRecordFromRow(row))
	}
	if len(res.Rows) >= limit {
		next := logCursor{V: 1, Order: order, Start: start.UnixMilli(), End: end.UnixMilli()}
		if prev != nil {
			next.Start, next.End = prev.Start, prev.End
		}
		next.Ms = rowMillis(res.Rows[len(res.Rows)-1])
		seen := map[string]bool{}
		if prev != nil && prev.Ms == next.Ms {
			for _, id := range prev.IDs {
				if !seen[id] {
					seen[id] = true
					next.IDs = append(next.IDs, id)
				}
			}
		}
		for _, row := range res.Rows {
			if id := str(row.Data, "id"); id != "" && rowMillis(row) == next.Ms && !seen[id] {
				seen[id] = true
				next.IDs = append(next.IDs, id)
			}
		}
		out.NextCursor = encodeCursor(next)
	}
	return out, nil
}

// notInIDs renders "id NOT IN ('a', 'b')" (empty for no ids).
func notInIDs(ids []string) string {
	if len(ids) == 0 {
		return ""
	}
	quoted := make([]string, len(ids))
	for i, id := range ids {
		quoted[i] = QuoteFilterString(id)
	}
	return "id NOT IN (" + strings.Join(quoted, ", ") + ")"
}

// rowNanos returns the row timestamp in Unix nanoseconds: the int64
// data.timestamp when present, else the RFC 3339 envelope timestamp.
func rowNanos(row rawRow) int64 {
	if n, ok := num(row.Data, "timestamp"); ok && n > 0 {
		return n
	}
	ts := row.Timestamp
	if ts == "" {
		ts = str(row.Data, "timestamp")
	}
	if t, err := time.Parse(time.RFC3339Nano, ts); err == nil {
		return t.UnixNano()
	}
	return 0
}

func rowMillis(row rawRow) int64 { return rowNanos(row) / int64(time.Millisecond) }

// logRecordFromRow maps a raw logs row (query or live tail) to a LogRecord.
func logRecordFromRow(row rawRow) LogRecord {
	d := row.Data
	rec := LogRecord{
		Timestamp:  time.Unix(0, rowNanos(row)).UTC(),
		Body:       str(d, "body"),
		Attributes: attrsOf(mapOf(d, "attributes_string"), mapOf(d, "attributes_number"), mapOf(d, "attributes_bool")),
		Resource:   attrsOf(mapOf(d, "resources_string")),
		TraceID:    strings.ToLower(str(d, "trace_id")),
		SpanID:     strings.ToLower(str(d, "span_id")),
	}
	text := str(d, "severity_text")
	if n, ok := num(d, "severity_number"); ok && n > 0 {
		rec.Severity = Severity(n)
	} else if s, ok := ParseSeverity(text); ok {
		rec.Severity = s
	}
	rec.SeverityText = text
	if rec.SeverityText == "" && rec.Severity != SeverityUnspecified {
		rec.SeverityText = rec.Severity.String()
	}
	if rec.TraceID != "" {
		f, _ := num(d, "trace_flags")
		flags := uint8(f & 0xff) // #nosec G115 -- masked to one byte
		rec.TraceFlags = &flags
	}
	return rec
}

// Search implements LogQueryClient: query is a SigNoz filter expression (bare
// words search the body).
func (q *QueryClient) Search(ctx context.Context, query string, opts SearchOptions) (LogSearchResult, error) {
	page, err := q.QueryLogs(ctx, LogQuery{
		Filter: query, Start: opts.Start, End: opts.End, Limit: opts.Limit, Order: opts.Order, Cursor: opts.Cursor,
	})
	if err != nil {
		return LogSearchResult{}, err
	}
	return LogSearchResult{Records: page.Records, NextCursor: page.NextCursor}, nil
}

// WaitForLog polls Search until a record matches query (and opts.Predicate);
// TIMEOUT when nothing matches within opts.Timeout (default 30 s).
func (q *QueryClient) WaitForLog(ctx context.Context, query string, opts WaitForLogOptions) (LogRecord, error) {
	return WaitForLog(ctx, q, query, opts)
}

// LogField is one log field known to SigNoz. A name may exist in several
// contexts (for example an attribute and a resource attribute).
type LogField struct {
	// Name is the field name, for example "service.name" or "body".
	Name string `json:"name"`
	// Context is "log", "resource", "attribute" or "scope".
	Context string `json:"context"`
	// DataType is "string", "number" or "bool".
	DataType string `json:"dataType"`
	// Description is the server's description, when any.
	Description string `json:"description,omitempty"`
}

// ListFieldsOptions narrow ListLogFields. The zero value lists every field.
type ListFieldsOptions struct {
	// SearchText keeps fields whose name contains it.
	SearchText string
	// Limit caps the number of names (0 = server default).
	Limit int
}

// ListLogFields lists the log fields (GET /api/v1/fields/keys?signal=logs),
// sorted by name and context.
func (q *QueryClient) ListLogFields(ctx context.Context, opts ListFieldsOptions) ([]LogField, error) {
	const op = "listLogFields"
	params := url.Values{"signal": {string(SignalLogs)}}
	if opts.SearchText != "" {
		params.Set("searchText", opts.SearchText)
	}
	if opts.Limit > 0 {
		params.Set("limit", strconv.Itoa(opts.Limit))
	}
	raw, err := q.call(ctx, op, http.MethodGet, "/api/v1/fields/keys", params, nil, true)
	if err != nil {
		return nil, err
	}
	var data struct {
		Keys map[string][]struct {
			Name          string `json:"name"`
			FieldContext  string `json:"fieldContext"`
			FieldDataType string `json:"fieldDataType"`
			Description   string `json:"description"`
		} `json:"keys"`
	}
	if err := decode(op, raw, &data); err != nil {
		return nil, err
	}
	out := []LogField{}
	for name, variants := range data.Keys {
		for _, v := range variants {
			n := v.Name
			if n == "" {
				n = name
			}
			out = append(out, LogField{Name: n, Context: v.FieldContext, DataType: v.FieldDataType, Description: v.Description})
		}
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Name != out[j].Name {
			return out[i].Name < out[j].Name
		}
		return out[i].Context < out[j].Context
	})
	return out, nil
}

// ListLabels implements LogQueryClient: the sorted unique log field names.
func (q *QueryClient) ListLabels(ctx context.Context) ([]string, error) {
	fields, err := q.ListLogFields(ctx, ListFieldsOptions{})
	if err != nil {
		return nil, err
	}
	out := []string{}
	for _, f := range fields {
		if len(out) == 0 || out[len(out)-1] != f.Name {
			out = append(out, f.Name)
		}
	}
	return out, nil
}

// ListStreams implements LogQueryClient. SigNoz has no streams; the service
// names that sent logs are the stream equivalent (sorted).
func (q *QueryClient) ListStreams(ctx context.Context) ([]string, error) {
	values, err := q.ListFieldValues(ctx, "service.name", FieldValuesOptions{Signal: SignalLogs})
	if err != nil {
		return nil, err
	}
	out := []string{}
	for _, v := range values {
		if s, ok := v.(string); ok {
			out = append(out, s)
		}
	}
	sort.Strings(out)
	return out, nil
}

// FieldValuesOptions narrow ListFieldValues.
type FieldValuesOptions struct {
	// Signal is SignalLogs (default) or SignalTraces.
	Signal Signal
	// SearchText keeps values containing it.
	SearchText string
}

// ListFieldValues returns the distinct values of a field (GET
// /api/v1/fields/values): strings, numbers (int64 / float64) and booleans,
// sorted by their text form.
func (q *QueryClient) ListFieldValues(ctx context.Context, name string, opts FieldValuesOptions) ([]any, error) {
	const op = "listFieldValues"
	if err := checkField(name); err != nil {
		return nil, err
	}
	signal := opts.Signal
	if signal == "" {
		signal = SignalLogs
	}
	params := url.Values{"signal": {string(signal)}, "name": {name}}
	if opts.SearchText != "" {
		params.Set("searchText", opts.SearchText)
	}
	raw, err := q.call(ctx, op, http.MethodGet, "/api/v1/fields/values", params, nil, true)
	if err != nil {
		return nil, err
	}
	var data struct {
		Values struct {
			StringValues []any `json:"stringValues"`
			NumberValues []any `json:"numberValues"`
			BoolValues   []any `json:"boolValues"`
		} `json:"values"`
	}
	if err := decode(op, raw, &data); err != nil {
		return nil, err
	}
	seen := map[string]bool{}
	out := []any{}
	for _, group := range [][]any{data.Values.StringValues, data.Values.NumberValues, data.Values.BoolValues} {
		for _, v := range group {
			n := NormalizeAttrValue(v)
			switch n.(type) {
			case string, int64, float64, bool:
			default:
				continue
			}
			key := fmt.Sprintf("%T:%v", n, n)
			if !seen[key] {
				seen[key] = true
				out = append(out, n)
			}
		}
	}
	sort.SliceStable(out, func(i, j int) bool { return fmt.Sprint(out[i]) < fmt.Sprint(out[j]) })
	return out, nil
}
