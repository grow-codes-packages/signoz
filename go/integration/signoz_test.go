package integration

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"slices"
	"sort"
	"strings"
	"sync"
	"testing"
	"time"

	"go.opentelemetry.io/otel/codes"
	"go.opentelemetry.io/otel/trace"

	signoz "github.com/grow-codes-packages/signoz/go"
)

// TestSignoz calls every public function of the package against the real
// SigNoz stack from docker/compose.yml with round-trip assertions.
func TestSignoz(t *testing.T) {
	bootstrap := os.Getenv("GROW_BOOTSTRAP_ENV")
	if bootstrap == "" {
		bootstrap = "/bootstrap/env"
	}
	loadBootstrapEnv(t, bootstrap)
	if os.Getenv("GROW_SIGNOZ_URL") == "" {
		t.Skip("GROW_SIGNOZ_URL is not set; run through docker/compose.yml")
	}
	run := randomID()
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Minute)
	defer cancel()

	cfg, err := signoz.ConfigFromEnv()
	must(t, err)
	must(t, cfg.Validate())
	if cfg.APIKey.IsZero() || strings.Contains(cfg.String(), cfg.APIKey.Reveal()) {
		t.Fatal("api key must be set and redacted")
	}
	client, err := signoz.NewClient(cfg)
	must(t, err)
	defer func() { must(t, client.Close()) }()
	q := client.Query()

	t.Run("core", func(t *testing.T) {
		h, err := client.Health(ctx)
		if err != nil || !h.Healthy || h.Status != "ok" {
			t.Fatalf("health %v %+v", err, h)
		}
		if _, err := client.Ping(ctx); err != nil {
			t.Fatalf("ping %v", err)
		}
		if v, err := client.ServerVersion(ctx); err != nil && !errors.Is(err, signoz.ErrUnsupported) {
			t.Fatalf("serverVersion %v", err)
		} else if err == nil && v == "" {
			t.Fatal("empty version without error")
		}
		bad, err := signoz.NewClient(signoz.Config{CommonConfig: cfg.CommonConfig, APIKey: signoz.NewSecret("wrong-key-0000")})
		must(t, err)
		defer func() { _ = bad.Close() }()
		if _, err := bad.Ping(ctx); !errors.Is(err, signoz.ErrAuthentication) {
			t.Fatalf("wrong key: %v", err)
		}
		if _, err := bad.Query().QueryLogs(ctx, signoz.LogQuery{}); !errors.Is(err, signoz.ErrAuthentication) {
			t.Fatalf("wrong key query: %v", err)
		}
		if c2, err := signoz.NewClientFromEnv(); err != nil {
			t.Fatal(err)
		} else if c2.Config().URL != cfg.URL {
			t.Fatal("env client")
		} else {
			must(t, c2.Close())
		}
		cfg2, err := signoz.ConfigFromEnvWith(signoz.Config{OTLPProtocol: signoz.ProtocolGRPC, OTLPEndpoint: os.Getenv("SIGNOZ_OTLP_GRPC_ENDPOINT")}, nil)
		if err != nil || cfg2.OTLPProtocol != signoz.ProtocolGRPC {
			t.Fatalf("explicit config: %v", err)
		}
	})

	service := "go-it-" + run
	// Live tail first, so it sees the records sent below.
	var tailMu sync.Mutex
	var tailed []signoz.LogRecord
	tail, err := q.Tail(ctx, signoz.NewFilter().Contains("body", "tail-"+run).String(), func(r signoz.LogRecord) {
		tailMu.Lock()
		tailed = append(tailed, r)
		tailMu.Unlock()
	}, signoz.TailOptions{})
	must(t, err)
	defer tail.Stop()

	traceID, spanID := "4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7"
	t.Run("logs send and query back", func(t *testing.T) {
		lc, err := client.NewLogClient(signoz.LogOptions{ServiceName: service, ServiceVersion: "1.0.0", Environment: "it"})
		must(t, err)
		special := "special " + run + " it's 50% a_b back\\slash ünïcode"
		lc.Send(ctx, signoz.LogInput{Message: "hello " + run, Severity: signoz.SeverityInfo,
			Attributes: map[string]signoz.AttrValue{"go.it.run": run, "seq": 7, "ok": true, "password": "hunter2-" + run}})
		lc.Send(ctx, signoz.LogInput{Message: "error " + run, Severity: signoz.SeverityError, TraceID: traceID, SpanID: spanID,
			Attributes: map[string]signoz.AttrValue{"go.it.run": run, "seq": 8}})
		lc.SendBatch(ctx, signoz.Entries([]signoz.LogInput{{Message: special}, {Message: "tail-" + run + " match"}, {Message: "tail-x" + run + " nomatch"}}))
		lc.Logger("it", map[string]signoz.AttrValue{"component": "logger"}).Warn(ctx, "logger "+run, nil)
		must(t, lc.Flush(ctx))
		if st := lc.Stats(); st.Sent != 6 || st.Dropped != 0 || st.Failed != 0 {
			t.Fatalf("stats %+v", st)
		}
		must(t, lc.Close(ctx))

		recs, err := signoz.WaitForLogs(ctx, q, signoz.NewFilter().Eq("service.name", service).String(), 6, signoz.WaitForLogOptions{Timeout: 60 * time.Second})
		must(t, err)
		if len(recs) != 6 {
			t.Fatalf("got %d records", len(recs))
		}

		f := signoz.NewFilter().Eq("service.name", service).Eq("go.it.run", run).In("severity_text", "ERROR", "FATAL").Gte("seq", 8).Exists("go.it.run")
		expr, err := f.Build()
		must(t, err)
		res, err := q.QueryLogs(ctx, signoz.LogQuery{Filter: expr, Start: time.Now().Add(-10 * time.Minute)})
		must(t, err)
		if len(res.Records) != 1 {
			t.Fatalf("filtered %d", len(res.Records))
		}
		r := res.Records[0]
		if r.Body != "error "+run || r.Severity != signoz.SeverityError || r.SeverityText != "ERROR" || r.TraceID != traceID || r.SpanID != spanID ||
			r.Attributes["seq"] != int64(8) || r.Resource["service.name"] != service || r.Resource["service.version"] != "1.0.0" ||
			r.Resource["deployment.environment"] != "it" || time.Since(r.Timestamp) > 10*time.Minute {
			t.Fatalf("record %+v", r)
		}
		hello, err := q.WaitForLog(ctx, "hello "+run, signoz.WaitForLogOptions{Timeout: 30 * time.Second})
		must(t, err)
		if hello.Attributes["password"] != signoz.Redacted || hello.Attributes["ok"] != true {
			t.Fatalf("redaction / bool attribute: %+v", hello.Attributes)
		}
		// literal CONTAINS with quote, %, _ and backslash; NOT CONTAINS; LIKE; REGEXP; full text
		checks := map[string]int{
			signoz.NewFilter().Eq("service.name", service).Contains("body", "it's 50% a_b back\\slash").String():                                           1,
			signoz.NewFilter().Eq("service.name", service).Contains("body", "50%_a").String():                                                              0,
			signoz.NewFilter().Eq("service.name", service).NotContains("body", run).String():                                                               0,
			signoz.NewFilter().Eq("service.name", service).Like("body", "tail-%"+run+"%").String():                                                         2,
			signoz.NewFilter().Eq("service.name", service).Regexp("body", "^tail-x?"+run).String():                                                         2,
			signoz.NewFilter().Eq("service.name", service).Text("logger " + run).String():                                                                  1,
			signoz.NewFilter().Eq("service.name", service).Or(signoz.NewFilter().Eq("seq", 7), signoz.NewFilter().Eq("seq", 8)).String():                   2,
			signoz.NewFilter().Eq("service.name", service).Not(signoz.NewFilter().Exists("seq")).NotExists("go.it.run").String():                           4,
			signoz.NewFilter().Eq("service.name", service).NotIn("severity_text", "ERROR").Ne("seq", 7).Lt("seq", 100).Lte("seq", 8).Gt("seq", 1).String(): 0,
			signoz.AndFilters(signoz.NewFilter().Eq("service.name", service).String(), "seq = 7 OR seq = 8"):                                               2,
		}
		for expr, want := range checks {
			res, err := q.QueryLogs(ctx, signoz.LogQuery{Filter: expr, Limit: 100, SelectFields: []string{"body"}})
			if err != nil || len(res.Records) != want {
				t.Errorf("%s: want %d got %d (%v)", expr, want, len(res.Records), err)
			}
		}
		if _, err := q.QueryLogs(ctx, signoz.LogQuery{Filter: "body CONTAINS ((("}); !errors.Is(err, signoz.ErrValidation) {
			t.Fatalf("invalid filter: %v", err)
		}
		warn, err := q.QueryLogs(ctx, signoz.LogQuery{Filter: "nosuchfield_" + run + " = 'x'"})
		if err != nil || len(warn.Records) != 0 || len(warn.Warnings) == 0 {
			t.Fatalf("warnings: %v %+v", err, warn)
		}
	})

	t.Run("exporters http, grpc and raw", func(t *testing.T) {
		grpcCfg := cfg
		grpcCfg.OTLPProtocol = signoz.ProtocolGRPC
		grpcCfg.OTLPEndpoint = os.Getenv("SIGNOZ_OTLP_GRPC_ENDPOINT")
		if grpcCfg.OTLPEndpoint == "" {
			grpcCfg.OTLPEndpoint = strings.Replace(cfg.OTLPEndpoint, "4318", "4317", 1)
		}
		lc, err := signoz.NewLogClient(grpcCfg, signoz.LogOptions{ServiceName: service + "-grpc"})
		must(t, err)
		lc.Send(ctx, signoz.LogInput{Message: "grpc " + run})
		must(t, lc.Close(ctx))

		exp, err := signoz.NewOTLPLogExporter(cfg)
		must(t, err)
		recs := []signoz.LogRecord{{Timestamp: time.Now(), Severity: signoz.SeverityDebug, SeverityText: "DEBUG", Body: "raw " + run,
			Resource: map[string]signoz.AttrValue{"service.name": service + "-raw"}, Attributes: map[string]signoz.AttrValue{"f": 1.5}}}
		if n := len(signoz.BuildOTLPLogsRequest(recs).ResourceLogs); n != 1 {
			t.Fatalf("request %d", n)
		}
		res, err := exp.Export(ctx, recs, signoz.ExportOptions{Gzip: true})
		if err != nil || res.Accepted != 1 {
			t.Fatalf("export %v %+v", err, res)
		}
		must(t, exp.Shutdown(ctx))

		for _, body := range []string{"grpc " + run, "raw " + run} {
			r, err := q.WaitForLog(ctx, body, signoz.WaitForLogOptions{Timeout: 60 * time.Second})
			must(t, err)
			if !strings.HasPrefix(fmt.Sprint(r.Resource["service.name"]), service) {
				t.Fatalf("resource %+v", r.Resource)
			}
			if body == "raw "+run && (r.Severity != signoz.SeverityDebug || r.Attributes["f"] != 1.5) {
				t.Fatalf("raw record %+v", r)
			}
		}
	})

	t.Run("tail", func(t *testing.T) {
		deadline := time.Now().Add(60 * time.Second)
		for time.Now().Before(deadline) {
			tailMu.Lock()
			n := len(tailed)
			tailMu.Unlock()
			if n >= 1 {
				break
			}
			time.Sleep(500 * time.Millisecond)
		}
		tailMu.Lock()
		defer tailMu.Unlock()
		if len(tailed) != 1 || tailed[0].Body != "tail-"+run+" match" || tailed[0].Resource["service.name"] != service {
			t.Fatalf("tailed %+v", tailed)
		}
	})

	t.Run("paging with a shared millisecond", func(t *testing.T) {
		lc, err := client.NewLogClient(signoz.LogOptions{ServiceName: service + "-page"})
		must(t, err)
		base := time.Now().Add(-time.Minute).Truncate(time.Millisecond)
		offsets := []time.Duration{0, time.Millisecond, time.Millisecond + 10, time.Millisecond + 20, 2 * time.Millisecond, 2 * time.Millisecond, 3 * time.Millisecond}
		for i, off := range offsets {
			lc.Send(ctx, signoz.LogInput{Message: fmt.Sprintf("page %s %d", run, i), Timestamp: base.Add(off)})
		}
		must(t, lc.Close(ctx))
		query := signoz.NewFilter().Eq("service.name", service+"-page").String()
		_, err = signoz.WaitForLogs(ctx, q, query, len(offsets), signoz.WaitForLogOptions{Timeout: 60 * time.Second})
		must(t, err)
		for _, order := range []signoz.SortOrder{signoz.OrderDesc, signoz.OrderAsc} {
			var bodies []string
			cursor := ""
			for page := 0; page < 20; page++ {
				res, err := q.QueryLogs(ctx, signoz.LogQuery{Filter: query, Limit: 2, Order: order, Cursor: cursor})
				must(t, err)
				for _, r := range res.Records {
					bodies = append(bodies, r.Body)
				}
				if res.NextCursor == "" {
					break
				}
				cursor = res.NextCursor
			}
			sorted := slices.Clone(bodies)
			sort.Strings(sorted)
			if len(bodies) != len(offsets) || len(slices.Compact(sorted)) != len(offsets) {
				t.Fatalf("%s: %v", order, bodies)
			}
			first := fmt.Sprintf("page %s 6", run)
			if order == signoz.OrderAsc {
				first = fmt.Sprintf("page %s 0", run)
			}
			if bodies[0] != first {
				t.Fatalf("%s: first %s", order, bodies[0])
			}
		}
		page, err := q.Search(ctx, query, signoz.SearchOptions{Limit: 3})
		if err != nil || len(page.Records) != 3 || page.NextCursor == "" {
			t.Fatalf("search %v %+v", err, page)
		}
		all, err := signoz.SearchAll(ctx, q, query, signoz.SearchOptions{Limit: 3}, 0)
		if err != nil || len(all) != len(offsets) {
			t.Fatalf("search all %v %d", err, len(all))
		}
	})

	t.Run("fields streams labels values", func(t *testing.T) {
		poll(t, ctx, "listLabels", func() (bool, error) {
			labels, err := client.Logs().ListLabels(ctx)
			return slices.Contains(labels, "go.it.run"), err
		})
		fields, err := q.ListLogFields(ctx, signoz.ListFieldsOptions{SearchText: "go.it", Limit: 50})
		must(t, err)
		found := false
		for _, f := range fields {
			if f.Name == "go.it.run" && f.Context == "attribute" && f.DataType == "string" {
				found = true
			}
		}
		if !found {
			t.Fatalf("fields %+v", fields)
		}
		poll(t, ctx, "listStreams", func() (bool, error) {
			streams, err := client.Logs().ListStreams(ctx)
			return slices.Contains(streams, service), err
		})
		poll(t, ctx, "listFieldValues", func() (bool, error) {
			vals, err := q.ListFieldValues(ctx, "service.name", signoz.FieldValuesOptions{Signal: signoz.SignalLogs, SearchText: run})
			return slices.Contains(vals, any(service)), err
		})
	})

	t.Run("aggregate logs", func(t *testing.T) {
		agg := signoz.NewAggregate(signoz.SignalLogs).Where(signoz.NewFilter().Eq("service.name", service).String()).
			Count("n").GroupBy("service.name").OrderBy("n desc").Limit(5).Between(time.Now().Add(-30*time.Minute), time.Now().Add(time.Minute))
		res, err := q.Aggregate(ctx, agg.Query())
		must(t, err)
		if len(res.Rows) != 1 || res.Rows[0].Groups["service.name"] != service || res.Rows[0].Values["n"] != 6 {
			t.Fatalf("scalar %+v", res)
		}
		ts, err := q.Aggregate(ctx, agg.Step(time.Minute).Query())
		must(t, err)
		total := 0.0
		for _, s := range ts.Series {
			if s.Alias != "n" {
				t.Fatalf("alias %s", s.Alias)
			}
			for _, p := range s.Points {
				total += p.Value
			}
		}
		if total != 6 {
			t.Fatalf("time series total %v %+v", total, ts)
		}
	})

	traceService, dbService := "go-it-trace-"+run, "go-it-db-"+run
	var rootTrace string
	t.Run("tracing round trip", func(t *testing.T) {
		h, err := client.InitTracing(ctx, signoz.TracingConfig{ServiceName: traceService, ServiceVersion: "1.0.0", DisableSetGlobal: true})
		must(t, err)
		hdb, err := signoz.InitTracing(ctx, signoz.TracingConfig{ServiceName: dbService, Protocol: signoz.ProtocolGRPC,
			Endpoint: os.Getenv("SIGNOZ_OTLP_GRPC_ENDPOINT"), DisableSetGlobal: true})
		must(t, err)
		tracer, dbTracer := h.Tracer("it"), hdb.Tracer("it")

		_, linked := tracer.Start(ctx, "linked-"+run, trace.WithNewRoot())
		linked.End()
		rctx, root := tracer.Start(ctx, "GET /it-"+run, trace.WithSpanKind(trace.SpanKindServer), trace.WithNewRoot())
		cctx, call := tracer.Start(rctx, "call-db", trace.WithSpanKind(trace.SpanKindClient),
			trace.WithLinks(trace.Link{SpanContext: linked.SpanContext()}))
		call.AddEvent("checkpoint")
		remote := trace.ContextWithRemoteSpanContext(context.Background(), trace.SpanContextFromContext(cctx))
		_, server := dbTracer.Start(remote, "SELECT users", trace.WithSpanKind(trace.SpanKindServer))
		server.RecordError(errors.New("boom"))
		server.SetStatus(codes.Error, "boom")
		server.End()
		call.End()
		root.SetStatus(codes.Ok, "")
		root.End()
		must(t, h.ForceFlush(ctx))
		must(t, hdb.ForceFlush(ctx))
		rootTrace = root.SpanContext().TraceID().String()

		tr, err := client.Traces().WaitForTrace(ctx, rootTrace, signoz.WaitForTraceOptions{Timeout: 60 * time.Second, MinSpans: 3})
		must(t, err)
		byName := map[string]signoz.Span{}
		for _, s := range tr.Spans {
			byName[s.Name] = s
		}
		r, c, s := byName["GET /it-"+run], byName["call-db"], byName["SELECT users"]
		if r.Kind != signoz.SpanKindServer || r.Status.Code != signoz.SpanStatusOK || r.ParentSpanID != "" || r.ServiceName != traceService {
			t.Fatalf("root %+v", r)
		}
		if c.Kind != signoz.SpanKindClient || c.ParentSpanID != r.SpanID || len(c.Events) != 1 || c.Events[0].Name != "checkpoint" ||
			len(c.Links) != 1 || c.Links[0].SpanID != linked.SpanContext().SpanID().String() {
			t.Fatalf("call %+v", c)
		}
		if s.ServiceName != dbService || s.ParentSpanID != c.SpanID || s.Status.Code != signoz.SpanStatusError || s.Status.Message != "boom" || s.DurationMicros < 0 {
			t.Fatalf("server %+v", s)
		}
		if tr.Tree()[0].Span.Name != "GET /it-"+run {
			t.Fatal("tree root")
		}

		spans, err := q.QueryTraces(ctx, signoz.TraceQuery{Service: traceService, Operation: "call-db", Start: time.Now().Add(-10 * time.Minute)})
		must(t, err)
		if len(spans.Spans) != 1 || spans.Spans[0].TraceID != rootTrace || spans.Spans[0].StartMicros == 0 || len(spans.Spans[0].Links) != 1 {
			t.Fatalf("queryTraces %+v", spans)
		}
		sum, err := q.SearchTraces(ctx, signoz.TraceSearchQuery{Service: traceService, Operation: "GET /it-" + run, MinDuration: time.Nanosecond, Limit: 5})
		must(t, err)
		if len(sum) != 1 || sum[0].TraceID != rootTrace || sum[0].SpanCount != 3 || sum[0].StartMicros == 0 || sum[0].RootServiceName != traceService {
			t.Fatalf("searchTraces %+v", sum)
		}
		svcs, err := q.ListServices(ctx)
		must(t, err)
		if !slices.Contains(svcs, traceService) || !slices.Contains(svcs, dbService) {
			t.Fatalf("services %v", svcs)
		}
		svcs, err = q.ListServicesInRange(ctx, signoz.TimeRange{Start: time.Now().Add(-10 * time.Minute)})
		must(t, err)
		if !slices.Contains(svcs, traceService) {
			t.Fatalf("services in range %v", svcs)
		}
		ops, err := q.ListOperations(ctx, traceService)
		must(t, err)
		if !slices.Equal(ops, []string{"GET /it-" + run, "call-db", "linked-" + run}) {
			t.Fatalf("operations %v", ops)
		}
		ops, err = q.ListOperationsInRange(ctx, dbService, signoz.TimeRange{Start: time.Now().Add(-10 * time.Minute)})
		if err != nil || !slices.Equal(ops, []string{"SELECT users"}) {
			t.Fatalf("operations in range %v %v", err, ops)
		}
		agg, err := q.Aggregate(ctx, signoz.NewAggregate(signoz.SignalTraces).Where(signoz.NewFilter().Eq("service.name", traceService).String()).
			Count("calls").Percentile(99, "duration_nano", "p99").GroupBy("name").Query())
		must(t, err)
		if len(agg.Rows) != 3 || agg.Rows[0].Values["calls"] != 1 || agg.Rows[0].Values["p99"] <= 0 {
			t.Fatalf("aggregate traces %+v", agg)
		}
		if tr, err := q.GetTrace(ctx, "0af7651916cd43dd8448eb211c80319c"); err != nil || tr != nil {
			t.Fatalf("unknown trace %v %v", err, tr)
		}
		must(t, h.Shutdown(ctx))
		must(t, hdb.Shutdown(ctx))
	})

	t.Run("dependencies", func(t *testing.T) {
		poll(t, ctx, "getDependencies", func() (bool, error) {
			deps, err := client.Traces().GetDependencies(ctx, signoz.DependencyOptions{Start: time.Now().Add(-30 * time.Minute)})
			for _, d := range deps {
				if d.Parent == traceService && d.Child == dbService && d.CallCount >= 1 {
					return true, nil
				}
			}
			return false, err
		})
	})
}

// poll retries fn every second for up to two minutes.
func poll(t *testing.T, ctx context.Context, what string, fn func() (bool, error)) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Minute)
	var lastErr error
	for time.Now().Before(deadline) && ctx.Err() == nil {
		ok, err := fn()
		if ok {
			return
		}
		lastErr = err
		time.Sleep(time.Second)
	}
	t.Fatalf("%s: condition not met (last error %v)", what, lastErr)
}

func must(t *testing.T, err error) {
	t.Helper()
	if err != nil {
		t.Fatal(err)
	}
}

func randomID() string {
	b := make([]byte, 4)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}
