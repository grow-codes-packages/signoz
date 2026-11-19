package signoz

import (
	"bytes"
	"compress/gzip"
	"context"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	collogspb "go.opentelemetry.io/proto/otlp/collector/logs/v1"
	coltracepb "go.opentelemetry.io/proto/otlp/collector/trace/v1"
	logspb "go.opentelemetry.io/proto/otlp/logs/v1"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	_ "google.golang.org/grpc/encoding/gzip" // server side gzip
	"google.golang.org/grpc/metadata"
	grpcstatus "google.golang.org/grpc/status"
	"google.golang.org/protobuf/proto"
)

// collector is a mock OTLP/HTTP collector.
type collector struct {
	*httptest.Server
	mu        sync.Mutex
	logs      []*collogspb.ExportLogsServiceRequest
	headers   []http.Header
	traces    int
	status    int
	response  []byte
	respType  string
	traceHdrs []http.Header
}

func newCollector(t *testing.T) *collector {
	t.Helper()
	c := &collector{status: 200}
	c.Server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body io.Reader = r.Body
		if r.Header.Get("Content-Encoding") == "gzip" {
			zr, err := gzip.NewReader(r.Body)
			if err != nil {
				w.WriteHeader(400)
				return
			}
			body = zr
		}
		data, _ := io.ReadAll(body)
		c.mu.Lock()
		defer c.mu.Unlock()
		switch r.URL.Path {
		case "/v1/logs":
			req := &collogspb.ExportLogsServiceRequest{}
			if err := proto.Unmarshal(data, req); err != nil {
				w.WriteHeader(400)
				return
			}
			c.logs = append(c.logs, req)
			c.headers = append(c.headers, r.Header.Clone())
			if c.respType != "" {
				w.Header().Set("Content-Type", c.respType)
			}
			w.WriteHeader(c.status)
			_, _ = w.Write(c.response)
		case "/v1/traces":
			req := &coltracepb.ExportTraceServiceRequest{}
			_ = proto.Unmarshal(data, req)
			c.traces++
			c.traceHdrs = append(c.traceHdrs, r.Header.Clone())
			w.Header().Set("Content-Type", "application/x-protobuf")
			b, _ := proto.Marshal(&coltracepb.ExportTraceServiceResponse{})
			_, _ = w.Write(b)
		default:
			w.WriteHeader(404)
		}
	}))
	t.Cleanup(c.Close)
	return c
}

func (c *collector) logCount() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	n := 0
	for _, r := range c.logs {
		for _, rl := range r.ResourceLogs {
			for _, sl := range rl.ScopeLogs {
				n += len(sl.LogRecords)
			}
		}
	}
	return n
}

func (c *collector) traceCalls() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.traces
}

func sampleRecords() []LogRecord {
	flags := uint8(1)
	ts := time.Unix(1795032424, 581483281)
	return []LogRecord{
		{
			Timestamp: ts, ObservedTimestamp: ts, Severity: SeverityError, SeverityText: "ERROR", Body: "boom ünïcode",
			Attributes: map[string]AttrValue{"s": "x", "n": int64(7), "f": 1.5, "b": true, "list": []AttrValue{"a", int64(1)},
				"map": map[string]AttrValue{"k": "v"}, "nil": nil, "dur": time.Second},
			Resource: map[string]AttrValue{"service.name": "svc-a"},
			TraceID:  "4bf92f3577b34da6a3ce929d0e0e4736", SpanID: "00f067aa0ba902b7", TraceFlags: &flags,
		},
		{Timestamp: ts, Severity: SeverityInfo, Body: "second", Resource: map[string]AttrValue{"service.name": "svc-b"}, TraceID: "zz", SpanID: "short"},
		{Severity: SeverityInfo, Body: "third", Resource: map[string]AttrValue{"service.name": "svc-a"}},
	}
}

func TestBuildOTLPLogsRequest(t *testing.T) {
	req := BuildOTLPLogsRequest(sampleRecords())
	if len(req.ResourceLogs) != 2 {
		t.Fatalf("resources: %d", len(req.ResourceLogs))
	}
	a := req.ResourceLogs[0]
	if a.Resource.Attributes[0].Value.GetStringValue() != "svc-a" || len(a.ScopeLogs[0].LogRecords) != 2 || a.ScopeLogs[0].Scope.Name != LogScopeName {
		t.Fatalf("grouping: %v", a)
	}
	lr := a.ScopeLogs[0].LogRecords[0]
	if lr.SeverityNumber != logspb.SeverityNumber_SEVERITY_NUMBER_ERROR || lr.Body.GetStringValue() != "boom ünïcode" ||
		len(lr.TraceId) != 16 || len(lr.SpanId) != 8 || lr.Flags != 1 || lr.TimeUnixNano != 1795032424581483281 || lr.ObservedTimeUnixNano == 0 {
		t.Fatalf("record: %v", lr)
	}
	got := map[string]string{}
	for _, kv := range lr.Attributes {
		got[kv.Key] = kv.Value.String()
	}
	if _, ok := got["nil"]; ok || len(got) != 7 || !strings.Contains(got["map"], "kvlist") || !strings.Contains(got["list"], "array") || !strings.Contains(got["dur"], "1s") {
		t.Fatalf("attributes: %v", got)
	}
	bad := req.ResourceLogs[1].ScopeLogs[0].LogRecords[0]
	if bad.TraceId != nil || bad.SpanId != nil {
		t.Fatal("invalid ids must be dropped")
	}
	if a.ScopeLogs[0].LogRecords[1].TimeUnixNano != 0 {
		t.Fatal("zero timestamp stays unset")
	}
}

func TestOTLPLogExporterHTTP(t *testing.T) {
	col := newCollector(t)
	cfg := Config{OTLPEndpoint: col.URL + "/", IngestionKey: NewSecret("ingest-SECRET-9"),
		CommonConfig: CommonConfig{Headers: map[string]string{"X-Extra": "1"}}}
	exp, err := NewOTLPLogExporter(cfg)
	if err != nil {
		t.Fatal(err)
	}
	res, err := exp.Export(t.Context(), sampleRecords(), ExportOptions{Gzip: true})
	if err != nil || res.Accepted != 3 || res.Rejected != 0 {
		t.Fatalf("%v %+v", err, res)
	}
	h := col.headers[0]
	if h.Get(IngestionKeyHeader) != "ingest-SECRET-9" || h.Get("Content-Encoding") != "gzip" ||
		h.Get("Content-Type") != "application/x-protobuf" || h.Get("X-Extra") != "1" {
		t.Fatalf("headers %v", h)
	}
	if res, err := exp.Export(t.Context(), nil, ExportOptions{}); err != nil || res.Accepted != 0 {
		t.Fatal("empty batch")
	}

	t.Run("partial success protobuf", func(t *testing.T) {
		b, _ := proto.Marshal(&collogspb.ExportLogsServiceResponse{PartialSuccess: &collogspb.ExportLogsPartialSuccess{RejectedLogRecords: 2}})
		col.mu.Lock()
		col.response, col.respType = b, "application/x-protobuf"
		col.mu.Unlock()
		res, err := exp.Export(t.Context(), sampleRecords(), ExportOptions{})
		if err != nil || res.Accepted != 1 || res.Rejected != 2 || !errors.Is(res.Error, ErrValidation) {
			t.Fatalf("%v %+v", err, res)
		}
	})
	t.Run("partial success json", func(t *testing.T) {
		col.mu.Lock()
		col.response, col.respType = []byte(`{"partialSuccess":{"rejectedLogRecords":"9"}}`), "application/json"
		col.mu.Unlock()
		res, err := exp.Export(t.Context(), sampleRecords(), ExportOptions{})
		if err != nil || res.Accepted != 0 || res.Rejected != 3 {
			t.Fatalf("%v %+v", err, res)
		}
		col.mu.Lock()
		col.response = []byte(`garbage`)
		col.mu.Unlock()
		if res, _ := exp.Export(t.Context(), sampleRecords(), ExportOptions{}); res.Accepted != 3 {
			t.Fatalf("garbage json: %+v", res)
		}
		col.mu.Lock()
		col.response, col.respType = []byte{0xff, 0xff}, "application/x-protobuf"
		col.mu.Unlock()
		if res, _ := exp.Export(t.Context(), sampleRecords(), ExportOptions{}); res.Accepted != 3 {
			t.Fatalf("garbage protobuf: %+v", res)
		}
	})
	t.Run("bad request", func(t *testing.T) {
		col.mu.Lock()
		col.status, col.response, col.respType = 400, fixture(t, "otlp.logs.http.badRequest.json"), "application/json"
		col.mu.Unlock()
		_, err := exp.Export(t.Context(), sampleRecords(), ExportOptions{})
		if e := asError(t, err); e.Code != ErrorCodeValidation || e.Retryable || strings.Contains(e.Message, "SECRET") {
			t.Fatalf("%v", e)
		}
		col.mu.Lock()
		col.status = 503
		col.mu.Unlock()
		if _, err := exp.Export(t.Context(), sampleRecords(), ExportOptions{}); !IsRetryable(err) {
			t.Fatalf("503 retryable: %v", err)
		}
	})
	if err := exp.Shutdown(t.Context()); err != nil {
		t.Fatal(err)
	}
	if err := exp.Shutdown(t.Context()); err != nil {
		t.Fatal("shutdown twice")
	}
	if _, err := exp.Export(t.Context(), sampleRecords(), ExportOptions{}); !errors.Is(err, ErrCancelled) {
		t.Fatalf("after shutdown: %v", err)
	}
	if _, err := NewOTLPLogExporter(Config{OTLPEndpoint: "nope"}); !errors.Is(err, ErrConfig) {
		t.Fatalf("invalid endpoint: %v", err)
	}
	if _, err := NewOTLPLogExporter(Config{CommonConfig: CommonConfig{TLS: TLSConfig{CACertPEM: "bad"}}}); !errors.Is(err, ErrConfig) {
		t.Fatalf("invalid tls: %v", err)
	}
}

func TestOTLPSignalURL(t *testing.T) {
	cases := map[string]string{
		"http://c:4318":            "http://c:4318/v1/logs",
		"http://c:4318/":           "http://c:4318/v1/logs",
		"https://p/otlp":           "https://p/otlp/v1/logs",
		"http://c:4318/v1/logs":    "http://c:4318/v1/logs",
		"https://ingest.signoz.io": "https://ingest.signoz.io/v1/logs",
	}
	for in, want := range cases {
		u, err := otlpSignalURL(in, "logs")
		if err != nil || u.String() != want {
			t.Errorf("%s: %v %v", in, u, err)
		}
	}
	if _, err := otlpSignalURL("::", "logs"); !errors.Is(err, ErrConfig) {
		t.Fatal(err)
	}
}

// grpcCollector is an in-process OTLP/gRPC logs server.
type grpcCollector struct {
	collogspb.UnimplementedLogsServiceServer
	mu    sync.Mutex
	md    []metadata.MD
	count int
	err   error
	resp  *collogspb.ExportLogsServiceResponse
}

func (g *grpcCollector) Export(ctx context.Context, req *collogspb.ExportLogsServiceRequest) (*collogspb.ExportLogsServiceResponse, error) {
	g.mu.Lock()
	defer g.mu.Unlock()
	md, _ := metadata.FromIncomingContext(ctx)
	g.md = append(g.md, md)
	for _, rl := range req.ResourceLogs {
		for _, sl := range rl.ScopeLogs {
			g.count += len(sl.LogRecords)
		}
	}
	if g.err != nil {
		return nil, g.err
	}
	if g.resp != nil {
		return g.resp, nil
	}
	return &collogspb.ExportLogsServiceResponse{}, nil
}

func startGRPC(t *testing.T) (*grpcCollector, string) {
	t.Helper()
	lis, err := (&net.ListenConfig{}).Listen(t.Context(), "tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	srv := grpc.NewServer()
	g := &grpcCollector{}
	collogspb.RegisterLogsServiceServer(srv, g)
	go func() { _ = srv.Serve(lis) }()
	t.Cleanup(srv.Stop)
	return g, "http://" + lis.Addr().String()
}

func TestOTLPLogExporterGRPC(t *testing.T) {
	g, endpoint := startGRPC(t)
	exp, err := NewOTLPLogExporter(Config{OTLPProtocol: ProtocolGRPC, OTLPEndpoint: endpoint, IngestionKey: NewSecret("ingest-SECRET-7"),
		CommonConfig: CommonConfig{Headers: map[string]string{"X-Team": "a", "X-Auth-Token": "tok-SECRET-5"}}})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = exp.Shutdown(context.Background()) }()
	res, err := exp.Export(t.Context(), sampleRecords(), ExportOptions{Gzip: true})
	if err != nil || res.Accepted != 3 {
		t.Fatalf("%v %+v", err, res)
	}
	md := g.md[0]
	if md.Get(IngestionKeyHeader)[0] != "ingest-SECRET-7" || md.Get("x-team")[0] != "a" {
		t.Fatalf("metadata %v", md)
	}
	g.mu.Lock()
	g.resp = &collogspb.ExportLogsServiceResponse{PartialSuccess: &collogspb.ExportLogsPartialSuccess{RejectedLogRecords: 1}}
	g.mu.Unlock()
	if res, _ := exp.Export(t.Context(), sampleRecords(), ExportOptions{}); res.Rejected != 1 {
		t.Fatalf("partial: %+v", res)
	}
	cases := map[codes.Code]ErrorCode{
		codes.InvalidArgument:   ErrorCodeValidation,
		codes.Unauthenticated:   ErrorCodeAuthentication,
		codes.PermissionDenied:  ErrorCodePermissionDenied,
		codes.NotFound:          ErrorCodeNotFound,
		codes.ResourceExhausted: ErrorCodeRateLimited,
		codes.DeadlineExceeded:  ErrorCodeTimeout,
		codes.Unimplemented:     ErrorCodeUnsupported,
		codes.Canceled:          ErrorCodeCancelled,
		codes.Unavailable:       ErrorCodeUnavailable,
	}
	for c, want := range cases {
		g.mu.Lock()
		g.err = grpcstatus.Error(c, "bad tok-SECRET-5")
		g.mu.Unlock()
		_, err := exp.Export(t.Context(), sampleRecords(), ExportOptions{})
		if e := asError(t, err); e.Code != want || strings.Contains(e.Message, "SECRET") {
			t.Errorf("%s: %v", c, e)
		}
	}
	ctx, cancel := context.WithCancel(t.Context())
	cancel()
	if _, err := exp.Export(ctx, sampleRecords(), ExportOptions{}); !errors.Is(err, ErrCancelled) {
		t.Fatalf("cancelled: %v", err)
	}
	if _, err := NewOTLPLogExporter(Config{OTLPProtocol: ProtocolGRPC, OTLPEndpoint: "https://localhost:1",
		CommonConfig: CommonConfig{TLS: TLSConfig{CACertPEM: "bad"}}}); !errors.Is(err, ErrConfig) {
		t.Fatalf("tls: %v", err)
	}
	if e, err := NewOTLPLogExporter(Config{OTLPProtocol: ProtocolGRPC, OTLPEndpoint: "https://localhost:1"}); err != nil || e == nil {
		t.Fatalf("tls ok: %v", err)
	} else {
		_ = e.Shutdown(t.Context())
	}
}

func TestNewLogClientEndToEnd(t *testing.T) {
	col := newCollector(t)
	lc, err := NewLogClient(Config{OTLPEndpoint: col.URL}, LogOptions{
		ServiceName: "checkout", ServiceVersion: "1.2.3", Environment: "test",
		Headers:          map[string]string{"X-Run": "r1"},
		LogClientOptions: LogClientOptions{Resource: map[string]AttrValue{"host.name": "h1"}},
	})
	if err != nil {
		t.Fatal(err)
	}
	lc.Logger("app", nil).Info(t.Context(), "hello", map[string]AttrValue{"password": "hunter2", "n": 1})
	if err := lc.Close(t.Context()); err != nil {
		t.Fatal(err)
	}
	col.mu.Lock()
	req, hdr := col.logs[0], col.headers[0]
	col.mu.Unlock()
	res := map[string]string{}
	for _, kv := range req.ResourceLogs[0].Resource.Attributes {
		res[kv.Key] = kv.Value.GetStringValue()
	}
	if res["service.name"] != "checkout" || res["service.version"] != "1.2.3" || res["deployment.environment"] != "test" || res["host.name"] != "h1" || hdr.Get("X-Run") != "r1" {
		t.Fatalf("resource %v headers %v", res, hdr)
	}
	b, _ := proto.Marshal(req)
	if bytes.Contains(b, []byte("hunter2")) {
		t.Fatal("redaction must run before export")
	}

	// default service name, protocol switch picks the gRPC default port
	g, endpoint := startGRPC(t)
	lc2, err := NewLogClient(Config{OTLPEndpoint: endpoint}, LogOptions{Protocol: ProtocolGRPC, Endpoint: endpoint})
	if err != nil {
		t.Fatal(err)
	}
	lc2.Send(t.Context(), LogInput{Message: "x"})
	_ = lc2.Close(t.Context())
	if g.count != 1 {
		t.Fatalf("grpc count %d", g.count)
	}
	lc3, err := NewLogClient(Config{OTLPEndpoint: col.URL}, LogOptions{Protocol: ProtocolGRPC})
	if err != nil {
		t.Fatal(err)
	}
	_ = lc3.Close(t.Context())
}
