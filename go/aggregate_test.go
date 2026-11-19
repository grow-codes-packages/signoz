package signoz

import (
	"errors"
	"fmt"
	"math"
	"strings"
	"testing"
	"time"

	"go.opentelemetry.io/otel"
)

func TestAggregate(t *testing.T) {
	m := newMock(t)
	m.json("POST /api/v5/query_range", 200, fixture(t, "listServices.scalar.json"))
	q := newQueryClient(t, m)
	ctx := t.Context()

	aq := NewAggregate(SignalTraces).Where("service.name != ''").Count("calls").Percentile(99, "duration_nano", "p99").
		GroupBy("service.name").Limit(10).OrderBy("calls desc").Between(fixedNow.Add(-time.Hour), fixedNow).Query()
	res, err := q.Aggregate(ctx, aq)
	if err != nil || len(res.Rows) != 4 {
		t.Fatalf("%v %+v", err, res)
	}
	row := res.Rows[1]
	if row.Groups["service.name"] != "sdk-fe67ae4b" || row.Values["calls"] != 2 || row.Values["p99"] != 149500000 {
		t.Fatalf("row %+v", row)
	}
	body := queryBody(t, m.last(t))
	spec := specOf(t, body)
	if body["requestType"] != "scalar" || fmt.Sprint(spec["aggregations"]) != "[map[alias:calls expression:count()] map[alias:p99 expression:p99(duration_nano)]]" ||
		fmt.Sprint(spec["order"]) != "[map[direction:desc key:map[name:calls]]]" || spec["limit"].(float64) != 10 {
		t.Fatalf("spec %v", spec)
	}

	t.Run("time series", func(t *testing.T) {
		m.json("POST /api/v5/query_range", 200, fixture(t, "aggregate.timeSeries.json"))
		res, err := q.Aggregate(ctx, NewAggregate(SignalLogs).Count("").Step(90*time.Second).Query())
		if err != nil || len(res.Series) != 1 || res.Series[0].Alias != "count()" || res.Series[0].Points[0].Value != 12 ||
			res.Series[0].Points[0].Time.UnixMilli() != 1795033260000 {
			t.Fatalf("%v %+v", err, res)
		}
		body := queryBody(t, m.last(t))
		if body["requestType"] != "time_series" || specOf(t, body)["stepInterval"].(float64) != 90 {
			t.Fatalf("body %v", body)
		}
		m.json("POST /api/v5/query_range", 200, []byte(`{"status":"success","data":{"data":{"results":[{"aggregations":[{"index":5,"series":[{"labels":[{"key":{"name":"service.name"},"value":"a"}],"values":[{"timestamp":1,"value":"2"}]}]}]}]}}}`))
		res, _ = q.Aggregate(ctx, NewAggregate(SignalLogs).Count("n").Step(time.Millisecond).Query())
		if res.Series[0].Alias != "__result_5" || res.Series[0].Labels["service.name"] != "a" {
			t.Fatalf("%+v", res)
		}
		m.json("POST /api/v5/query_range", 200, []byte(`{"status":"success","data":{"data":{"results":[{"aggregations":1}]}}}`))
		if _, err := q.Aggregate(ctx, NewAggregate(SignalLogs).Count("n").Step(time.Minute).Query()); !errors.Is(err, ErrUnavailable) {
			t.Fatal(err)
		}
	})
	t.Run("scalar edge cases", func(t *testing.T) {
		m.json("POST /api/v5/query_range", 200, []byte(`{"status":"success","data":{"data":{"results":[{"columns":[{"name":"__result_0","columnType":"aggregation","aggregationIndex":9},{"name":"x","columnType":"group"}],"data":[[12]]}]}}}`))
		res, err := q.Aggregate(ctx, NewAggregate(SignalLogs).CountDistinct("trace_id", "").Avg("a", "avg").Sum("a", "sum").Min("a", "min").Max("a", "max").Expr("count()", "").Query())
		if err != nil || res.Rows[0].Values["__result_9"] != 12 {
			t.Fatalf("%v %+v", err, res)
		}
		m.json("POST /api/v5/query_range", 200, []byte(`{"status":"success","data":{"data":{"results":[]}}}`))
		if res, err := q.Aggregate(ctx, NewAggregate(SignalLogs).Count("n").Query()); err != nil || len(res.Rows) != 0 {
			t.Fatal(err)
		}
		m.json("POST /api/v5/query_range", 200, []byte(`{"status":"success","data":{"data":{"results":[{"columns":1}]}}}`))
		if _, err := q.Aggregate(ctx, NewAggregate(SignalLogs).Count("n").Query()); !errors.Is(err, ErrUnavailable) {
			t.Fatal(err)
		}
		m.json("POST /api/v5/query_range", 200, fixture(t, "aggregate.count.json"))
		res, err = q.Aggregate(ctx, NewAggregate(SignalLogs).Count("n").OrderBy("n").Query())
		if err != nil || len(res.Rows) != 1 || specOf(t, queryBody(t, m.last(t)))["order"] == nil {
			t.Fatalf("%v %+v", err, res)
		}
	})
	t.Run("validation", func(t *testing.T) {
		bad := []AggregateQuery{
			{Signal: "metrics", Aggregations: []Aggregation{{Expression: "count()"}}},
			{Signal: SignalLogs},
			{Signal: SignalLogs, Aggregations: []Aggregation{{Expression: " "}}},
			{Signal: SignalLogs, Aggregations: []Aggregation{{Expression: "count()"}}, StepInterval: -1},
			{Signal: SignalLogs, Aggregations: []Aggregation{{Expression: "count()"}}, Limit: -5},
			{Signal: SignalLogs, Aggregations: []Aggregation{{Expression: "count()"}}, GroupBy: []string{"a b"}},
			{Signal: SignalLogs, Aggregations: []Aggregation{{Expression: "count()"}}, OrderBy: "n sideways"},
			{Signal: SignalLogs, Aggregations: []Aggregation{{Expression: "count()"}}, Start: fixedNow, End: fixedNow},
		}
		for i, b := range bad {
			if _, err := q.Aggregate(ctx, b); !errors.Is(err, ErrValidation) {
				t.Errorf("case %d: %v", i, err)
			}
		}
		m.json("POST /api/v5/query_range", 401, nil)
		if _, err := q.Aggregate(ctx, NewAggregate(SignalLogs).Count("n").Query()); !errors.Is(err, ErrAuthentication) {
			t.Fatal(err)
		}
	})
	if f := groupField("custom"); f.FieldContext != "" {
		t.Fatal(f)
	}
}

func TestFilterBuilder(t *testing.T) {
	f := NewFilter().Eq("service.name", "checkout").In("severity_text", "ERROR", "FATAL").Gte("seq", 3).Eq("ok", true)
	if got := f.String(); got != "service.name = 'checkout' AND severity_text IN ('ERROR', 'FATAL') AND seq >= 3 AND ok = true" {
		t.Fatal(got)
	}
	base := NewFilter().Eq("a", 1)
	x, y := base.Eq("b", 2), base.Eq("c", 3)
	if x.String() != "a = 1 AND b = 2" || y.String() != "a = 1 AND c = 3" || base.String() != "a = 1" {
		t.Fatal("builder must be immutable")
	}
	cases := map[string]*FilterBuilder{
		`body CONTAINS 'it\'s 50\\% a\\_b back\\\\slash'`: NewFilter().Contains("body", `it's 50% a_b back\slash`),
		`NOT body CONTAINS 'x'`:                           NewFilter().NotContains("body", "x"),
		`service.name LIKE 'sdk-%'`:                       NewFilter().Like("service.name", "sdk-%"),
		`body REGEXP 'a.*b'`:                              NewFilter().Regexp("body", "a.*b"),
		`run.id EXISTS AND x NOT EXISTS`:                  NewFilter().Exists("run.id").NotExists("x"),
		`'needle it\'s'`:                                  NewFilter().Text("needle it's"),
		`a != 'x' AND b > 1.5 AND c < 2 AND d <= 3`:       NewFilter().Ne("a", "x").Gt("b", 1.5).Lt("c", int64(2)).Lte("d", uint8(3)),
		`n NOT IN (1, 2)`:                                 NewFilter().NotIn("n", 1, 2),
		`x = 1 AND (a = 1 OR b = 2)`:                      NewFilter().Eq("x", 1).Raw(" a = 1 OR b = 2 "),
		`a = 1`:                                           NewFilter().Raw("a = 1").Raw("  "),
		`((a = 1) OR (b = 2 AND c = 3))`:                  NewFilter().Or(NewFilter().Eq("a", 1), nil, NewFilter(), NewFilter().Eq("b", 2).Eq("c", 3)),
		`(a = 1)`:                                         NewFilter().Or(NewFilter().Eq("a", 1)),
		`NOT (a = 1 AND b = 2)`:                           NewFilter().Not(NewFilter().Eq("a", 1).Eq("b", 2)).Not(nil).Not(NewFilter()),
		`d = '1s' AND f = 0.25`:                           NewFilter().Eq("d", time.Second).Eq("f", float32(0.25)),
		`s = '[1 2]'`:                                     NewFilter().Eq("s", []int{1, 2}),
	}
	for want, fb := range cases {
		got, err := fb.Build()
		if err != nil || got != want {
			t.Errorf("want %s got %s (%v)", want, got, err)
		}
	}
	if !NewFilter().IsEmpty() || NewFilter().Or().String() != "" || NewFilter().Eq("a", 1).IsEmpty() {
		t.Fatal("empty")
	}
	bad := []*FilterBuilder{
		NewFilter().Eq("bad field", 1),
		NewFilter().In("a"),
		NewFilter().In("a", math.NaN()),
		NewFilter().Eq("a", math.Inf(1)),
		NewFilter().Where("a", "~", 1),
		NewFilter().Contains("", "x"),
		NewFilter().Or(NewFilter().Eq("bad field", 1)),
		NewFilter().Not(NewFilter().In("a")),
		NewFilter().Not(NewFilter().In("a").Eq("b", 1)),
		NewFilter().In("a").Eq("b", 1),
	}
	for i, b := range bad {
		if _, err := b.Build(); !errors.Is(err, ErrValidation) {
			t.Errorf("case %d: %v", i, err)
		}
	}
	if lit, err := FilterLiteral("x'y"); err != nil || lit != `'x\'y'` {
		t.Fatal(lit, err)
	}
	if AndFilters(" ", "a", "b OR c") != "(a) AND (b OR c)" || AndFilters("a") != "a" || AndFilters() != "" {
		t.Fatal("AndFilters")
	}
	if EscapeLikePattern(`%_\`) != `\%\_\\` || QuoteFilterString(`a\'`) != `'a\\\''` {
		t.Fatal("escaping")
	}
}

func TestInitTracing(t *testing.T) {
	col := newCollector(t)
	t.Setenv(EnvPrefix+"OTLP_ENDPOINT", col.URL)
	t.Setenv(EnvPrefix+"INGESTION_KEY", "ingest-SECRET-3")
	prev := otel.GetTracerProvider()
	t.Cleanup(func() { otel.SetTracerProvider(prev) })
	h, err := InitTracing(t.Context(), TracingConfig{ServiceName: "svc", Headers: map[string]string{"X-A": "1"}, Compression: CompressionNone})
	if err != nil {
		t.Fatal(err)
	}
	_, span := h.Tracer("t").Start(t.Context(), "op")
	span.End()
	if err := h.Shutdown(t.Context()); err != nil {
		t.Fatal(err)
	}
	if col.traceCalls() != 1 {
		t.Fatalf("calls %d", col.traceCalls())
	}
	col.mu.Lock()
	hdr := col.traceHdrs[0]
	col.mu.Unlock()
	if hdr.Get(IngestionKeyHeader) != "ingest-SECRET-3" || hdr.Get("X-A") != "1" {
		t.Fatalf("headers %v", hdr)
	}
	if strings.Contains(h.Config().String(), "SECRET") {
		t.Fatal("tracing config leaks the ingestion key")
	}
	t.Setenv(EnvPrefix+"URL", "nope")
	if _, err := InitTracing(t.Context(), TracingConfig{ServiceName: "svc"}); !errors.Is(err, ErrConfig) {
		t.Fatal(err)
	}
}

func TestTracingConfigFor(t *testing.T) {
	cfg := Config{OTLPEndpoint: "http://collector:4318", CommonConfig: CommonConfig{TLS: TLSConfig{InsecureSkipVerify: true}}}
	tc, err := TracingConfigFor(cfg, TracingConfig{ServiceName: "s"})
	if err != nil || tc.Endpoint != "http://collector:4318/v1/traces" || tc.Protocol != ProtocolHTTPProtobuf || !tc.TLS.InsecureSkipVerify || tc.Headers != nil {
		t.Fatalf("%v %+v", err, tc)
	}
	tc, _ = TracingConfigFor(cfg, TracingConfig{ServiceName: "s", Protocol: ProtocolGRPC})
	if tc.Endpoint != DefaultOTLPGRPCEndpoint {
		t.Fatal(tc.Endpoint)
	}
	tc, _ = TracingConfigFor(Config{OTLPProtocol: ProtocolGRPC, OTLPEndpoint: "http://c:4317"}, TracingConfig{ServiceName: "s"})
	if tc.Endpoint != "http://c:4317" || tc.Protocol != ProtocolGRPC {
		t.Fatal(tc.Endpoint)
	}
	tc, _ = TracingConfigFor(Config{OTLPProtocol: ProtocolGRPC}, TracingConfig{ServiceName: "s", Protocol: ProtocolHTTPProtobuf})
	if tc.Endpoint != DefaultOTLPHTTPEndpoint+"/v1/traces" {
		t.Fatal(tc.Endpoint)
	}
	tc, _ = TracingConfigFor(cfg, TracingConfig{ServiceName: "s", Endpoint: "https://x/v1/traces", TLS: TLSConfig{CACertPath: "/ca"}})
	if tc.Endpoint != "https://x/v1/traces" || tc.TLS.CACertPath != "/ca" {
		t.Fatal(tc)
	}
	if _, err := TracingConfigFor(cfg, TracingConfig{ServiceName: "s", Protocol: ProtocolNative}); !errors.Is(err, ErrConfig) {
		t.Fatal(err)
	}
	if _, err := TracingConfigFor(Config{OTLPEndpoint: "http://[::1"}, TracingConfig{ServiceName: "s"}); !errors.Is(err, ErrConfig) {
		t.Fatal(err)
	}
	tc, _ = TracingConfigFor(Config{CommonConfig: CommonConfig{Headers: map[string]string{"X-B": "2"}}}, TracingConfig{ServiceName: "s"})
	if tc.Headers["X-B"] != "2" {
		t.Fatal(tc.Headers)
	}
}
