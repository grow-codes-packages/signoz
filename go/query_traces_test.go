package signoz

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

const fixtureTraceID = "fe67ae4bfe67ae4bfe67ae4bfe67ae4b"

func TestQueryTraces(t *testing.T) {
	m := newMock(t)
	m.json("POST /api/v5/query_range", 200, fixture(t, "queryTraces.raw.ok.json"))
	q := newQueryClient(t, m)
	res, err := q.QueryTraces(t.Context(), TraceQuery{Service: "sdk-fe67ae4b", Operation: "db.query", Filter: "duration_nano >= 1", Limit: 10, Offset: 5, Order: OrderAsc})
	if err != nil || len(res.Spans) == 0 {
		t.Fatalf("%v %+v", err, res)
	}
	spec := specOf(t, queryBody(t, m.last(t)))
	if spec["signal"] != "traces" || spec["offset"].(float64) != 5 ||
		spec["filter"].(map[string]any)["expression"] != "(service.name = 'sdk-fe67ae4b' AND name = 'db.query') AND (duration_nano >= 1)" {
		t.Fatalf("spec %v", spec)
	}
	var db *Span
	for i := range res.Spans {
		if res.Spans[i].Name == "db.query" {
			db = &res.Spans[i]
		}
	}
	if db == nil {
		t.Fatal("db.query span missing")
	}
	if db.Kind != SpanKindClient || db.Status.Code != SpanStatusError || db.Status.Message != "boom" || db.ParentSpanID != "00f067aa0ba902b7" ||
		db.ServiceName != "sdk-fe67ae4b" || db.DurationMicros != 100000 || db.StartMicros != 1795033260020000 || db.EndMicros != db.StartMicros+100000 ||
		len(db.Events) != 1 || db.Events[0].Attributes["exception.message"] != "boom" || db.Events[0].TimeMicros != 1795033260020000 ||
		len(db.Links) != 1 || db.Links[0].SpanID != "b7ad6b7169203331" || db.Attributes["db.system"] != "postgresql" {
		t.Fatalf("span %+v", *db)
	}
	for _, bad := range []TraceQuery{{Limit: -1}, {Order: "x"}, {Offset: -1}} {
		if _, err := q.QueryTraces(t.Context(), bad); !errors.Is(err, ErrValidation) {
			t.Errorf("%+v: %v", bad, err)
		}
	}
	m.json("POST /api/v5/query_range", 200, fixture(t, "queryLogs.empty.json"))
	if res, err := q.QueryTraces(t.Context(), TraceQuery{}); err != nil || len(res.Spans) != 0 {
		t.Fatal(err)
	}
	m.json("POST /api/v5/query_range", 500, nil)
	if _, err := q.QueryTraces(t.Context(), TraceQuery{}); !errors.Is(err, ErrUnavailable) {
		t.Fatal(err)
	}
	m.json("POST /api/v5/query_range", 200, []byte(`{"status":"success","data":{"data":{"results":[{"rows":"x"}]}}}`))
	if _, err := q.QueryTraces(t.Context(), TraceQuery{}); !errors.Is(err, ErrUnavailable) {
		t.Fatal(err)
	}
}

func TestGetTrace(t *testing.T) {
	m := newMock(t)
	m.json("POST /api/v4/traces/"+fixtureTraceID+"/waterfall", 200, fixture(t, "getTrace.waterfall.ok.json"))
	q := newQueryClient(t, m)
	tr, err := q.GetTrace(t.Context(), strings.ToUpper(fixtureTraceID))
	if err != nil || tr == nil || len(tr.Spans) != 3 || tr.TraceID != fixtureTraceID {
		t.Fatalf("%v %+v", err, tr)
	}
	root, db, sel := tr.Spans[0], tr.Spans[1], tr.Spans[2]
	if root.Name != "GET /verify" || root.Kind != SpanKindServer || root.Status.Code != SpanStatusOK || root.ParentSpanID != "" || root.StartMicros != 1795033260000000 || len(root.Links) != 0 {
		t.Fatalf("root %+v", root)
	}
	if db.Status.Code != SpanStatusError || len(db.Links) != 1 || db.Links[0].TraceID != "0af7651916cd43dd8448eb211c80319c" ||
		db.Events[0].Attributes["exception.message"] != "boom" || db.EndMicros != db.StartMicros+100000 {
		t.Fatalf("db %+v", db)
	}
	if sel.Status.Code != SpanStatusUnset || sel.ServiceName != "sdk-db-fe67ae4b" || sel.ParentSpanID != db.SpanID {
		t.Fatalf("select %+v", sel)
	}
	if string(m.last(t).Body) != "{}" {
		t.Fatalf("body %s", m.last(t).Body)
	}
	tree := tr.Tree()
	if len(tree) != 1 || len(tree[0].Children) != 1 || len(tree[0].Children[0].Children) != 1 {
		t.Fatal("tree")
	}

	t.Run("not found is nil", func(t *testing.T) {
		m.json("POST /api/v4/traces/"+fixtureTraceID+"/waterfall", 404, fixture(t, "getTrace.notFound.json"))
		if tr, err := q.GetTrace(t.Context(), fixtureTraceID); err != nil || tr != nil {
			t.Fatalf("%v %v", err, tr)
		}
		m.json("POST /api/v4/traces/"+fixtureTraceID+"/waterfall", 200, []byte(`{"status":"success","data":{"spans":[]}}`))
		if tr, err := q.GetTrace(t.Context(), fixtureTraceID); err != nil || tr != nil {
			t.Fatalf("%v %v", err, tr)
		}
	})
	t.Run("errors", func(t *testing.T) {
		if _, err := q.GetTrace(t.Context(), "xyz"); !errors.Is(err, ErrValidation) {
			t.Fatal(err)
		}
		m.json("POST /api/v4/traces/"+fixtureTraceID+"/waterfall", 401, fixture(t, "ping.unauthenticated.json"))
		if _, err := q.GetTrace(t.Context(), fixtureTraceID); !errors.Is(err, ErrAuthentication) {
			t.Fatal(err)
		}
		m.json("POST /api/v4/traces/"+fixtureTraceID+"/waterfall", 200, []byte(`{"status":"success","data":[]}`))
		if _, err := q.GetTrace(t.Context(), fixtureTraceID); !errors.Is(err, ErrUnavailable) {
			t.Fatal(err)
		}
	})
	t.Run("windowed waterfall is completed with raw queries", func(t *testing.T) {
		m.json("POST /api/v4/traces/"+fixtureTraceID+"/waterfall", 200,
			[]byte(`{"status":"success","data":{"startTimestampMillis":1795033260000,"endTimestampMillis":1795033260150,"totalSpansCount":10001,"hasMore":true,"spans":[{"span_id":"00f067aa0ba902b7","time_unix":1795033260000}]}}`))
		var calls atomic.Int32
		m.on("POST /api/v5/query_range", func(w http.ResponseWriter, r *http.Request) {
			var body map[string]any
			_ = json.NewDecoder(r.Body).Decode(&body)
			spec := body["compositeQuery"].(map[string]any)["queries"].([]any)[0].(map[string]any)["spec"].(map[string]any)
			offset, _ := spec["offset"].(float64)
			n := MaxQueryLimit
			if offset > 0 {
				n = 1
			}
			calls.Add(1)
			rows := make([]any, n)
			for i := range rows {
				rows[i] = map[string]any{"timestamp": "2026-11-18T20:21:00.02Z", "data": map[string]any{
					"trace_id": fixtureTraceID, "span_id": fmt.Sprintf("%016x", int(offset)+i+1), "name": "s"}}
			}
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(map[string]any{"status": "success", "data": map[string]any{"data": map[string]any{"results": []any{map[string]any{"rows": rows}}}}})
		})
		tr, err := q.GetTrace(t.Context(), fixtureTraceID)
		if err != nil || len(tr.Spans) != MaxQueryLimit+1 || calls.Load() != 2 {
			t.Fatalf("%v spans=%d calls=%d", err, len(tr.Spans), calls.Load())
		}
		m.json("POST /api/v5/query_range", 401, nil)
		if _, err := q.GetTrace(t.Context(), fixtureTraceID); !errors.Is(err, ErrAuthentication) {
			t.Fatal(err)
		}
	})
}

func TestWaitForTraceAndSearch(t *testing.T) {
	m := newMock(t)
	var n atomic.Int32
	m.on("POST /api/v4/traces/"+fixtureTraceID+"/waterfall", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if n.Add(1) == 1 {
			w.WriteHeader(404)
			_, _ = w.Write(fixture(t, "getTrace.notFound.json"))
			return
		}
		_, _ = w.Write(fixture(t, "getTrace.waterfall.ok.json"))
	})
	q := newQueryClient(t, m)
	tr, err := q.WaitForTrace(t.Context(), fixtureTraceID, WaitForTraceOptions{Interval: time.Millisecond, MinSpans: 3})
	if err != nil || len(tr.Spans) != 3 {
		t.Fatalf("%v %+v", err, tr)
	}

	m.json("POST /api/v5/query_range", 200, fixture(t, "searchTraces.traceMode.json"))
	res, err := q.SearchTraces(t.Context(), TraceSearchQuery{Service: "sdk-fe67ae4b", Operation: "GET /verify",
		Tags: map[string]string{"http.method": "GET", "a": "b"}, MinDuration: time.Millisecond, MaxDuration: time.Second})
	if err != nil || len(res) != 1 {
		t.Fatalf("%v %+v", err, res)
	}
	s := res[0]
	if s.TraceID != fixtureTraceID || s.StartMicros != 1795033260000000 || s.SpanCount != 3 || s.DurationMicros != 150000 ||
		s.RootServiceName != "sdk-fe67ae4b" || s.RootName != "GET /verify" {
		t.Fatalf("summary %+v", s)
	}
	var req recorded
	for _, r := range m.requests() {
		if r.Path == "/api/v5/query_range" {
			req = r
		}
	}
	body := queryBody(t, req)
	spec := specOf(t, body)
	if body["requestType"] != "trace" || spec["limit"].(float64) != DefaultTraceSearchLimit ||
		spec["filter"].(map[string]any)["expression"] != "service.name = 'sdk-fe67ae4b' AND name = 'GET /verify' AND a = 'b' AND http.method = 'GET' AND duration_nano >= 1000000 AND duration_nano <= 1000000000" {
		t.Fatalf("request %s", req.Body)
	}
	if _, err := q.SearchTraces(t.Context(), TraceSearchQuery{Tags: map[string]string{"bad key": "x"}}); !errors.Is(err, ErrValidation) {
		t.Fatal(err)
	}
	if _, err := q.SearchTraces(t.Context(), TraceSearchQuery{Limit: MaxQueryLimit + 1}); !errors.Is(err, ErrValidation) {
		t.Fatal(err)
	}
	m.json("POST /api/v5/query_range", 200, []byte(`{"status":"success","data":{"data":{"results":[{"rows":[{"data":{"trace_id":"zz-not-hex"}},{"data":{"trace_id":"abc","name":"n"}}]}]}}}`))
	m.json("POST /api/v4/traces/00000000000000000000000000000abc/waterfall", 404, fixture(t, "getTrace.notFound.json"))
	res, err = q.SearchTraces(t.Context(), TraceSearchQuery{})
	if err != nil || len(res) != 1 || res[0].TraceID != "00000000000000000000000000000abc" || res[0].StartMicros != 0 || res[0].RootName != "n" {
		t.Fatalf("%v %+v", err, res)
	}
	m.json("POST /api/v4/traces/00000000000000000000000000000abc/waterfall", 500, nil)
	if _, err := q.SearchTraces(t.Context(), TraceSearchQuery{}); !errors.Is(err, ErrUnavailable) {
		t.Fatal(err)
	}
	m.json("POST /api/v5/query_range", 200, []byte(`{"status":"success","data":{"data":{"results":[{"rows":1}]}}}`))
	if _, err := q.SearchTraces(t.Context(), TraceSearchQuery{}); !errors.Is(err, ErrUnavailable) {
		t.Fatal(err)
	}
	m.json("POST /api/v5/query_range", 401, nil)
	if _, err := q.SearchTraces(t.Context(), TraceSearchQuery{}); !errors.Is(err, ErrAuthentication) {
		t.Fatal(err)
	}
}

func TestServicesOperationsDependencies(t *testing.T) {
	m := newMock(t)
	m.json("POST /api/v5/query_range", 200, fixture(t, "listServices.scalar.json"))
	m.json("POST /api/v1/dependency_graph", 200, fixture(t, "getDependencies.ok.json"))
	q := newQueryClient(t, m)
	ctx := t.Context()
	svcs, err := q.ListServices(ctx)
	if err != nil || strings.Join(svcs, ",") != "sdk-db-fe67ae4b,sdk-fe67ae4b,tg-grpc-fe67ae4b,tg-httppb-fe67ae4b" {
		t.Fatalf("%v %v", err, svcs)
	}
	body := queryBody(t, m.last(t))
	spec := specOf(t, body)
	if body["requestType"] != "scalar" || fmt.Sprint(spec["groupBy"]) != "[map[fieldContext:resource fieldDataType:string name:service.name]]" || spec["limit"].(float64) != MaxQueryLimit {
		t.Fatalf("request %v", body)
	}
	m.json("POST /api/v5/query_range", 200, fixture(t, "listOperations.scalar.json"))
	ops, err := q.ListOperations(ctx, "sdk-fe67ae4b")
	if err != nil || len(ops) == 0 {
		t.Fatalf("%v %v", err, ops)
	}
	spec = specOf(t, queryBody(t, m.last(t)))
	if spec["filter"].(map[string]any)["expression"] != "service.name = 'sdk-fe67ae4b'" || fmt.Sprint(spec["groupBy"]) != "[map[fieldContext:span fieldDataType:string name:name]]" {
		t.Fatalf("spec %v", spec)
	}
	if _, err := q.ListOperations(ctx, " "); !errors.Is(err, ErrValidation) {
		t.Fatal(err)
	}
	m.json("POST /api/v5/query_range", 200, []byte(`{"status":"success","data":{"data":{"results":[{"columns":[{"name":"name","columnType":"group"},{"name":"__result_0","columnType":"aggregation","aggregationIndex":0}],"data":[["b",1],[7,2],["",3],["b",4],[]]}]}}}`))
	ops, _ = q.ListOperationsInRange(ctx, "x", TimeRange{Start: fixedNow.Add(-time.Minute)})
	if strings.Join(ops, ",") != "7,b" {
		t.Fatalf("%v", ops)
	}
	m.json("POST /api/v5/query_range", 401, nil)
	if _, err := q.ListServicesInRange(ctx, TimeRange{}); !errors.Is(err, ErrAuthentication) {
		t.Fatal(err)
	}

	deps, err := q.GetDependencies(ctx, DependencyOptions{Start: fixedNow.Add(-2 * time.Hour)})
	if err != nil || len(deps) != 2 || deps[0] != (DependencyLink{Parent: "sdk-fe67ae4b", Child: "postgresql", CallCount: 1}) {
		t.Fatalf("%v %+v", err, deps)
	}
	var depBody map[string]any
	_ = json.Unmarshal(m.last(t).Body, &depBody)
	if depBody["start"] != fmt.Sprint(fixedNow.Add(-2*time.Hour).UnixNano()) || depBody["end"] != fmt.Sprint(fixedNow.UnixNano()) {
		t.Fatalf("body %v", depBody)
	}
	m.json("POST /api/v1/dependency_graph", 200, []byte(`{"not":"array"}`))
	if _, err := q.GetDependencies(ctx, DependencyOptions{}); !errors.Is(err, ErrUnavailable) {
		t.Fatal(err)
	}
	m.json("POST /api/v1/dependency_graph", 200, []byte(`[{"parent":"b","child":"z"},{"parent":"a","child":"y"},{"parent":"a","child":"x","callCount":2}]`))
	deps, _ = q.GetDependencies(ctx, DependencyOptions{})
	if deps[0].Child != "x" || deps[1].Child != "y" || deps[2].Parent != "b" {
		t.Fatalf("sort %+v", deps)
	}
	m.json("POST /api/v1/dependency_graph", 403, fixture(t, "error.forbidden.json"))
	if _, err := q.GetDependencies(ctx, DependencyOptions{}); !errors.Is(err, ErrPermissionDenied) {
		t.Fatal(err)
	}
}

func TestSpanMappingEdges(t *testing.T) {
	if spanKindOf("weird") != SpanKindUnspecified || spanKindOf("Producer") != SpanKindProducer {
		t.Fatal("kind")
	}
	if spanStatusOf(map[string]any{"status_code_string": "Ok"}).Code != SpanStatusOK || spanStatusOf(map[string]any{"status_code_string": "Error"}).Code != SpanStatusError {
		t.Fatal("status string")
	}
	s := spanFromRaw(rawRow{Data: map[string]any{"events": []any{"x"}, "links": []any{"x", map[string]any{"spanId": ""}}}})
	if len(s.Events) != 0 || len(s.Links) != 0 {
		t.Fatalf("%+v", s)
	}
	w := spanFromWaterfall(map[string]any{"references": []any{"x", map[string]any{"refType": "FOLLOWS_FROM"}}})
	if len(w.Links) != 0 {
		t.Fatal("links")
	}
	if v, ok := num(map[string]any{"a": 1.5}, "a"); !ok || v != 1 {
		t.Fatal("float")
	}
	if v, ok := num(map[string]any{"a": json.Number("2.5")}, "a"); !ok || v != 2 {
		t.Fatal("json float")
	}
	if v, ok := num(map[string]any{"a": "12"}, "a"); !ok || v != 12 {
		t.Fatal("string")
	}
	if _, ok := num(map[string]any{"a": true}, "a"); ok {
		t.Fatal("bool")
	}
	if str(map[string]any{"a": true}, "a") != "true" || str(map[string]any{"a": false}, "a") != "false" || str(map[string]any{"a": 1.0}, "a") != "" || str(map[string]any{"a": json.Number("3")}, "a") != "3" {
		t.Fatal("str")
	}
	if f, ok := floatOf(int64(2)); !ok || f != 2 {
		t.Fatal("floatOf")
	}
	if _, ok := floatOf("x"); ok {
		t.Fatal("floatOf string")
	}
	if f, ok := floatOf(2.5); !ok || f != 2.5 {
		t.Fatal("floatOf float")
	}
}
