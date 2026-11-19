package signoz

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"sort"
	"strings"
	"testing"
	"time"
)

var fixedNow = time.Date(2026, 11, 18, 20, 30, 0, 0, time.UTC)

func newQueryClient(t *testing.T, m *mockSignoz) *QueryClient {
	t.Helper()
	q := newTestClient(t, m.URL, nil).Query()
	q.now = func() time.Time { return fixedNow }
	return q
}

func TestQueryLogs(t *testing.T) {
	m := newMock(t)
	m.json("POST /api/v5/query_range", 200, fixture(t, "queryLogs.raw.ok.json"))
	q := newQueryClient(t, m)
	ctx := t.Context()

	res, err := q.QueryLogs(ctx, LogQuery{Filter: "body CONTAINS 'marker'", Limit: 50, SelectFields: []string{"body"}})
	if err != nil {
		t.Fatal(err)
	}
	if len(res.Records) == 0 || res.NextCursor != "" || len(res.Warnings) != 0 {
		t.Fatalf("result %+v", res)
	}
	r := res.Records[0]
	if r.Body != "marker-fe67ae4b seq 5 ünïcode it's" || r.Severity != SeverityInfo || r.SeverityText != "INFO" ||
		r.TraceID != "fe67ae4bfe67ae4bfe67ae4bfe67ae4b" || r.SpanID != "00f067aa0ba902b7" || r.TraceFlags == nil || *r.TraceFlags != 1 ||
		r.Timestamp.UnixNano() != 1795033260005000000 || r.Resource["service.name"] != "sdk-fe67ae4b" ||
		r.Attributes["run.id"] != "fe67ae4b" || r.Attributes["seq"] != int64(5) || r.Attributes["ok"] != true {
		t.Fatalf("record %+v", r)
	}
	req := m.last(t)
	if req.Header.Get(APIKeyHeader) != testToken {
		t.Fatal("api key header")
	}
	body := queryBody(t, req)
	spec := specOf(t, body)
	if body["requestType"] != "raw" || body["end"].(float64) != float64(fixedNow.UnixMilli()) ||
		body["start"].(float64) != float64(fixedNow.Add(-time.Hour).UnixMilli()) ||
		spec["signal"] != "logs" || spec["limit"].(float64) != 50 ||
		spec["filter"].(map[string]any)["expression"] != "body CONTAINS 'marker'" ||
		fmt.Sprint(spec["order"]) != "[map[direction:desc key:map[name:timestamp]] map[direction:desc key:map[name:id]]]" ||
		fmt.Sprint(spec["selectFields"]) != "[map[name:body]]" {
		t.Fatalf("request %s", req.Body)
	}

	t.Run("empty rows are null", func(t *testing.T) {
		m.json("POST /api/v5/query_range", 200, fixture(t, "queryLogs.empty.json"))
		res, err := q.QueryLogs(ctx, LogQuery{})
		if err != nil || res.Records == nil || len(res.Records) != 0 {
			t.Fatalf("%v %+v", err, res)
		}
		if _, ok := specOf(t, queryBody(t, m.last(t)))["filter"]; ok {
			t.Fatal("empty filter must be omitted")
		}
	})
	t.Run("unknown field warning", func(t *testing.T) {
		m.json("POST /api/v5/query_range", 200, fixture(t, "queryLogs.unknownFieldWarning.json"))
		res, err := q.QueryLogs(ctx, LogQuery{Filter: "nosuchfield_fe67ae4b = 'x'"})
		if err != nil || len(res.Records) != 0 || len(res.Warnings) != 1 || !strings.Contains(res.Warnings[0], "not found in metadata") {
			t.Fatalf("%v %+v", err, res)
		}
		m.json("POST /api/v5/query_range", 200, []byte(`{"status":"success","data":{"warning":{"message":"only a message"},"data":{"results":[]}}}`))
		res, _ = q.QueryLogs(ctx, LogQuery{})
		if len(res.Warnings) != 1 || res.Warnings[0] != "only a message" {
			t.Fatalf("%+v", res)
		}
	})
	t.Run("errors", func(t *testing.T) {
		m.json("POST /api/v5/query_range", 400, fixture(t, "queryLogs.invalidFilter.json"))
		_, err := q.QueryLogs(ctx, LogQuery{Filter: "body CONTAINS ((("})
		if e := asError(t, err); e.Code != ErrorCodeValidation || !strings.Contains(e.Message, "line 1:14") {
			t.Fatalf("%v", e)
		}
		m.json("POST /api/v5/query_range", 401, fixture(t, "queryLogs.unauthenticated.json"))
		if _, err := q.QueryLogs(ctx, LogQuery{}); !errors.Is(err, ErrAuthentication) {
			t.Fatalf("%v", err)
		}
		m.json("POST /api/v5/query_range", 200, []byte(`{"status":"error"}`))
		if _, err := q.QueryLogs(ctx, LogQuery{}); !errors.Is(err, ErrUnavailable) {
			t.Fatalf("%v", err)
		}
		m.json("POST /api/v5/query_range", 200, []byte(`{"status":"success","data":"x"}`))
		if _, err := q.QueryLogs(ctx, LogQuery{}); !errors.Is(err, ErrUnavailable) {
			t.Fatalf("%v", err)
		}
		m.json("POST /api/v5/query_range", 200, []byte(`{"status":"success","data":{"data":{"results":["x"]}}}`))
		if _, err := q.QueryLogs(ctx, LogQuery{}); !errors.Is(err, ErrUnavailable) {
			t.Fatalf("%v", err)
		}
		m.json("POST /api/v5/query_range", 200, []byte(`nope`))
		if _, err := q.QueryLogs(ctx, LogQuery{}); !errors.Is(err, ErrUnavailable) {
			t.Fatalf("%v", err)
		}
		bad := []LogQuery{{Limit: -1}, {Limit: MaxQueryLimit + 1}, {Order: "sideways"}, {Cursor: "!!"},
			{Start: fixedNow, End: fixedNow.Add(-time.Minute)}, {Cursor: encodeCursor(logCursor{V: 2, Order: OrderAsc})}}
		for i, lq := range bad {
			if _, err := q.QueryLogs(ctx, lq); !errors.Is(err, ErrValidation) {
				t.Errorf("case %d: %v", i, err)
			}
		}
	})
	t.Run("500 is retried (idempotent query)", func(t *testing.T) {
		n := 0
		m.on("POST /api/v5/query_range", func(w http.ResponseWriter, _ *http.Request) {
			n++
			if n == 1 {
				w.WriteHeader(500)
				return
			}
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write(fixture(t, "queryLogs.empty.json"))
		})
		if _, err := q.QueryLogs(ctx, LogQuery{}); err != nil || n != 2 {
			t.Fatalf("%v n=%d", err, n)
		}
	})
	t.Run("html route is unsupported", func(t *testing.T) {
		delete(m.routes, "POST /api/v5/query_range")
		if _, err := q.QueryLogs(ctx, LogQuery{}); !errors.Is(err, ErrUnsupported) {
			t.Fatalf("%v", err)
		}
	})
}

// logStore serves query_range raw log queries from an in-memory table, applying
// the window, "id NOT IN" exclusions, order and limit like SigNoz.
type logStore struct{ rows []map[string]any }

func (s *logStore) handler(t *testing.T) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			Start, End     int64
			CompositeQuery struct {
				Queries []struct {
					Spec struct {
						Filter *struct{ Expression string }
						Order  []struct{ Direction string }
						Limit  int
					}
				}
			}
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			t.Error(err)
		}
		spec := body.CompositeQuery.Queries[0].Spec
		excluded := map[string]bool{}
		if spec.Filter != nil {
			if i := strings.Index(spec.Filter.Expression, "id NOT IN ("); i >= 0 {
				list := strings.TrimSuffix(spec.Filter.Expression[i+len("id NOT IN ("):], "))")
				list = strings.TrimSuffix(list, ")")
				for _, id := range strings.Split(list, ", ") {
					excluded[strings.Trim(id, "'")] = true
				}
			}
		}
		var out []map[string]any
		for _, row := range s.rows {
			ms := row["ns"].(int64) / 1e6
			if ms >= body.Start && ms < body.End && !excluded[row["id"].(string)] {
				out = append(out, row)
			}
		}
		desc := spec.Order[0].Direction == "desc"
		sort.SliceStable(out, func(i, j int) bool {
			a, b := out[i]["ns"].(int64), out[j]["ns"].(int64)
			if a == b {
				return (out[i]["id"].(string) > out[j]["id"].(string)) == desc
			}
			return (a > b) == desc
		})
		if len(out) > spec.Limit {
			out = out[:spec.Limit]
		}
		rows := make([]map[string]any, 0, len(out))
		for _, row := range out {
			rows = append(rows, map[string]any{
				"timestamp": time.Unix(0, row["ns"].(int64)).UTC().Format(time.RFC3339Nano),
				"data":      map[string]any{"id": row["id"], "body": row["id"], "timestamp": row["ns"]},
			})
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"status": "success", "data": map[string]any{"type": "raw",
			"data": map[string]any{"results": []any{map[string]any{"queryName": "A", "nextCursor": "ignored", "rows": rows}}}}})
	}
}

func TestQueryLogsPaging(t *testing.T) {
	base := fixedNow.Add(-10 * time.Minute).UnixNano()
	store := &logStore{}
	// ten rows: several share a millisecond (ids are not time ordered on purpose)
	offsets := []int64{0, 1e6, 1e6 + 10, 1e6 + 20, 2e6, 2e6, 2e6 + 5, 3e6, 3e6, 4e6}
	ids := []string{"k", "c", "x", "a", "q", "b", "z", "m", "d", "e"}
	for i, off := range offsets {
		store.rows = append(store.rows, map[string]any{"ns": base + off, "id": ids[i]})
	}
	m := newMock(t)
	m.on("POST /api/v5/query_range", store.handler(t))
	q := newQueryClient(t, m)
	for _, order := range []SortOrder{OrderDesc, OrderAsc} {
		for _, limit := range []int{1, 2, 3, 4} {
			got, err := SearchAll(t.Context(), q, "", SearchOptions{Limit: limit, Order: order}, 0)
			if err != nil {
				t.Fatal(err)
			}
			seen := map[string]bool{}
			for _, r := range got {
				if seen[r.Body] {
					t.Fatalf("%s limit %d: duplicate %s", order, limit, r.Body)
				}
				seen[r.Body] = true
			}
			if len(got) != len(ids) {
				t.Fatalf("%s limit %d: got %d records", order, limit, len(got))
			}
			for i := 1; i < len(got); i++ {
				if (order == OrderDesc && got[i].Timestamp.After(got[i-1].Timestamp)) || (order == OrderAsc && got[i].Timestamp.Before(got[i-1].Timestamp)) {
					t.Fatalf("%s limit %d: out of order", order, limit)
				}
			}
		}
	}
	// the cursor keeps the window and order of the first page
	page, _ := q.QueryLogs(t.Context(), LogQuery{Limit: 2, Order: OrderAsc})
	c, err := decodeCursor("t", page.NextCursor)
	if err != nil || c.Order != OrderAsc || c.Start != fixedNow.Add(-time.Hour).UnixMilli() || c.End != fixedNow.UnixMilli() {
		t.Fatalf("%v %+v", err, c)
	}
	next, _ := q.QueryLogs(t.Context(), LogQuery{Limit: 2, Order: OrderDesc, Cursor: page.NextCursor})
	if next.Records[0].Body != "x" {
		t.Fatalf("cursor order wins: %+v", next.Records)
	}
}

func TestLogRecordFromRow(t *testing.T) {
	r := logRecordFromRow(rawRow{Timestamp: "2026-11-18T20:21:24Z", Data: map[string]any{
		"severity_text": "warning", "body": "b", "trace_id": "", "attributes_number": map[string]any{"f": json.Number("1.5")},
	}})
	if r.Severity != SeverityWarn || r.SeverityText != "warning" || r.TraceFlags != nil || r.Attributes["f"] != 1.5 || r.Timestamp.Unix() != 1795033284 {
		t.Fatalf("%+v", r)
	}
	r = logRecordFromRow(rawRow{Data: map[string]any{"severity_number": json.Number("17"), "timestamp": "2026-11-18T20:21:24Z"}})
	if r.SeverityText != "ERROR" || r.Timestamp.Unix() != 1795033284 {
		t.Fatalf("%+v", r)
	}
	r = logRecordFromRow(rawRow{Data: map[string]any{"timestamp": "garbage"}})
	if r.Severity != SeverityInfo || r.SeverityText != "INFO" || r.Timestamp.UnixNano() != 0 {
		t.Fatalf("%+v", r)
	}
}

func TestSearchAndWait(t *testing.T) {
	m := newMock(t)
	m.json("POST /api/v5/query_range", 200, fixture(t, "queryLogs.raw.ok.json"))
	q := newQueryClient(t, m)
	res, err := q.Search(t.Context(), "marker-fe67ae4b", SearchOptions{Limit: 3, Order: OrderAsc})
	if err != nil || len(res.Records) < 3 || res.NextCursor == "" {
		t.Fatalf("%v %+v", err, res)
	}
	if spec := specOf(t, queryBody(t, m.last(t))); spec["filter"].(map[string]any)["expression"] != "marker-fe67ae4b" {
		t.Fatalf("query passed through: %v", spec)
	}
	rec, err := q.WaitForLog(t.Context(), "marker", WaitForLogOptions{Search: SearchOptions{Limit: MaxQueryLimit}, Predicate: func(r LogRecord) bool {
		return r.Attributes["seq"] == int64(4)
	}})
	if err != nil || rec.Attributes["seq"] != int64(4) {
		t.Fatalf("%v %+v", err, rec)
	}
	m.json("POST /api/v5/query_range", 400, fixture(t, "queryLogs.badSignal.json"))
	if _, err := q.Search(t.Context(), "x", SearchOptions{}); !errors.Is(err, ErrValidation) {
		t.Fatal(err)
	}
}

func TestLogFields(t *testing.T) {
	m := newMock(t)
	m.json("GET /api/v1/fields/keys", 200, fixture(t, "listLogFields.ok.json"))
	m.json("GET /api/v1/fields/values", 200, fixture(t, "listStreams.fieldValues.json"))
	q := newQueryClient(t, m)
	ctx := t.Context()

	fields, err := q.ListLogFields(ctx, ListFieldsOptions{SearchText: "serv", Limit: 5})
	if err != nil || len(fields) == 0 {
		t.Fatalf("%v %v", err, fields)
	}
	if r := m.last(t); r.Query != "limit=5&searchText=serv&signal=logs" {
		t.Fatalf("query %s", r.Query)
	}
	if !sort.SliceIsSorted(fields, func(i, j int) bool { return fields[i].Name < fields[j].Name }) {
		t.Fatal("sorted")
	}
	labels, err := q.ListLabels(ctx)
	if err != nil || !sort.StringsAreSorted(labels) || len(labels) == 0 {
		t.Fatalf("%v %v", err, labels)
	}
	for i := 1; i < len(labels); i++ {
		if labels[i] == labels[i-1] {
			t.Fatal("labels must be unique")
		}
	}
	m.json("GET /api/v1/fields/keys", 200, []byte(`{"status":"success","data":{"keys":{"a":[{"fieldContext":"attribute"},{"name":"a","fieldContext":"resource"}]}}}`))
	fields, _ = q.ListLogFields(ctx, ListFieldsOptions{})
	if len(fields) != 2 || fields[0].Name != "a" || fields[0].Context != "attribute" || fields[1].Context != "resource" {
		t.Fatalf("%+v", fields)
	}
	streams, err := q.ListStreams(ctx)
	if err != nil || len(streams) != 4 || streams[0] != "lat-fe67ae4b" {
		t.Fatalf("%v %v", err, streams)
	}
	if r := m.last(t); r.Query != "name=service.name&signal=logs" {
		t.Fatalf("query %s", r.Query)
	}
	m.json("GET /api/v1/fields/values", 200, []byte(`{"status":"success","data":{"values":{"stringValues":["b","a","a"],"numberValues":[2,1.5],"boolValues":[true,{"x":1}]}}}`))
	vals, err := q.ListFieldValues(ctx, "seq", FieldValuesOptions{Signal: SignalTraces, SearchText: "1"})
	if err != nil || fmt.Sprint(vals) != "[1.5 2 a b true]" {
		t.Fatalf("%v %v", err, vals)
	}
	if r := m.last(t); r.Query != "name=seq&searchText=1&signal=traces" {
		t.Fatalf("query %s", r.Query)
	}
	if _, err := q.ListFieldValues(ctx, "bad name", FieldValuesOptions{}); !errors.Is(err, ErrValidation) {
		t.Fatal(err)
	}
	for _, route := range []string{"GET /api/v1/fields/keys", "GET /api/v1/fields/values"} {
		m.json(route, 401, fixture(t, "ping.unauthenticated.json"))
	}
	if _, err := q.ListLabels(ctx); !errors.Is(err, ErrAuthentication) {
		t.Fatal(err)
	}
	if _, err := q.ListStreams(ctx); !errors.Is(err, ErrAuthentication) {
		t.Fatal(err)
	}
	for _, route := range []string{"GET /api/v1/fields/keys", "GET /api/v1/fields/values"} {
		m.json(route, 200, []byte(`{"status":"success","data":[1]}`))
	}
	if _, err := q.ListLogFields(ctx, ListFieldsOptions{}); !errors.Is(err, ErrUnavailable) {
		t.Fatal(err)
	}
	if _, err := q.ListFieldValues(ctx, "x", FieldValuesOptions{}); !errors.Is(err, ErrUnavailable) {
		t.Fatal(err)
	}
}

func TestQueryRange(t *testing.T) {
	m := newMock(t)
	m.json("POST /api/v5/query_range", 200, []byte(`{"status":"success","data":{"type":"scalar","warning":{"warnings":[{"message":"w1"}]},"data":{"results":[]}}}`))
	q := newQueryClient(t, m)
	body := map[string]any{"start": 1, "end": 2, "requestType": "scalar"}
	res, err := q.QueryRange(t.Context(), body)
	if err != nil || len(res.Warnings) != 1 || res.Warnings[0] != "w1" || !strings.Contains(string(res.Data), `"scalar"`) {
		t.Fatalf("%v %+v", err, res)
	}
	sent := queryBody(t, m.last(t))
	if sent["requestType"] != "scalar" || m.last(t).Header.Get(APIKeyHeader) != testToken {
		t.Fatalf("request %s", m.last(t).Body)
	}
	m.json("POST /api/v5/query_range", 200, []byte(`{"status":"success","data":null}`))
	res, err = q.QueryRange(t.Context(), body)
	if err != nil || len(res.Warnings) != 0 || res.Warnings == nil {
		t.Fatalf("%v %+v", err, res)
	}
	m.json("POST /api/v5/query_range", 400, fixture(t, "queryLogs.invalidFilter.json"))
	if _, err := q.QueryRange(t.Context(), body); !errors.Is(err, ErrValidation) {
		t.Fatalf("%v", err)
	}
}
