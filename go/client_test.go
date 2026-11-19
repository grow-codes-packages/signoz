package signoz

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

// newTestClient builds a client for url with fast retries; mutate adjusts the config.
func newTestClient(t *testing.T, url string, mutate func(*Config)) *Client {
	t.Helper()
	cfg := Config{CommonConfig: CommonConfig{URL: url, Retry: fastRetry(3)}, APIKey: NewSecret(testToken)}
	if mutate != nil {
		mutate(&cfg)
	}
	c, err := NewClient(cfg)
	if err != nil {
		t.Fatalf("NewClient: %v", err)
	}
	t.Cleanup(func() { _ = c.Close() })
	return c
}

// fixture reads testdata/<name> (responses recorded from the real stack).
func fixture(t *testing.T, name string) []byte {
	t.Helper()
	b, err := os.ReadFile(filepath.Join("testdata", name))
	if err != nil {
		t.Fatalf("fixture %s: %v", name, err)
	}
	return b
}

// recorded is a request seen by the mock server.
type recorded struct {
	Method, Path, Query string
	Header              http.Header
	Body                []byte
}

// mockSignoz serves routes keyed by "METHOD /path" and records every request.
type mockSignoz struct {
	*httptest.Server
	mu     sync.Mutex
	routes map[string]http.HandlerFunc
	reqs   []recorded
}

func newMock(t *testing.T) *mockSignoz {
	t.Helper()
	m := &mockSignoz{routes: map[string]http.HandlerFunc{}}
	m.Server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		m.mu.Lock()
		m.reqs = append(m.reqs, recorded{Method: r.Method, Path: r.URL.Path, Query: r.URL.RawQuery, Header: r.Header.Clone(), Body: body})
		h, ok := m.routes[r.Method+" "+r.URL.Path]
		m.mu.Unlock()
		if !ok {
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
			_, _ = w.Write([]byte("<!doctype html><html></html>"))
			return
		}
		r.Body = io.NopCloser(strings.NewReader(string(body)))
		h(w, r)
	}))
	t.Cleanup(m.Close)
	return m
}

func (m *mockSignoz) on(route string, h http.HandlerFunc) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.routes[route] = h
}

func (m *mockSignoz) json(route string, status int, body []byte) {
	m.on(route, func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		_, _ = w.Write(body)
	})
}

func (m *mockSignoz) requests() []recorded {
	m.mu.Lock()
	defer m.mu.Unlock()
	return append([]recorded(nil), m.reqs...)
}

func (m *mockSignoz) last(t *testing.T) recorded {
	t.Helper()
	r := m.requests()
	if len(r) == 0 {
		t.Fatal("no request recorded")
	}
	return r[len(r)-1]
}

// queryBody decodes the last query_range request.
func queryBody(t *testing.T, r recorded) map[string]any {
	t.Helper()
	var m map[string]any
	if err := json.Unmarshal(r.Body, &m); err != nil {
		t.Fatalf("body %s: %v", r.Body, err)
	}
	return m
}

func specOf(t *testing.T, body map[string]any) map[string]any {
	t.Helper()
	q := body["compositeQuery"].(map[string]any)["queries"].([]any)[0].(map[string]any)
	return q["spec"].(map[string]any)
}

func TestConfig(t *testing.T) {
	t.Run("core.config.env-precedence", func(t *testing.T) {
		env := map[string]string{
			EnvPrefix + "URL": "http://grow:1", "SIGNOZ_URL": "http://vendor:2",
			"SIGNOZ_API_KEY": "vendor-key-1", "SIGNOZ_INGESTION_KEY": "ingest-key-1",
			"SIGNOZ_OTLP_ENDPOINT": "http://collector:4318", "OTEL_EXPORTER_OTLP_ENDPOINT": "http://otel:4318",
		}
		cfg, err := ConfigFromEnvWith(Config{}, env)
		if err != nil || cfg.URL != "http://grow:1" || cfg.APIKey.Reveal() != "vendor-key-1" ||
			cfg.IngestionKey.Reveal() != "ingest-key-1" || cfg.OTLPEndpoint != "http://collector:4318" ||
			cfg.OTLPProtocol != ProtocolHTTPProtobuf {
			t.Fatalf("env: %v %s", err, cfg)
		}
		env[EnvPrefix+"API_KEY"] = "grow-key-1"
		env[EnvPrefix+"INGESTION_KEY"] = "grow-ingest-1"
		env[EnvPrefix+"OTLP_ENDPOINT"] = "http://grow-collector:4317"
		env[EnvPrefix+"OTLP_PROTOCOL"] = "GRPC"
		cfg, _ = ConfigFromEnvWith(Config{}, env)
		if cfg.APIKey.Reveal() != "grow-key-1" || cfg.IngestionKey.Reveal() != "grow-ingest-1" ||
			cfg.OTLPEndpoint != "http://grow-collector:4317" || cfg.OTLPProtocol != ProtocolGRPC {
			t.Fatalf("GROW_* must beat vendor variables: %s", cfg)
		}
		cfg, _ = ConfigFromEnvWith(Config{CommonConfig: CommonConfig{URL: "https://explicit"}, APIKey: NewSecret("explicit-key")}, env)
		if cfg.URL != "https://explicit" || cfg.APIKey.Reveal() != "explicit-key" {
			t.Fatal("explicit must win")
		}
		delete(env, "SIGNOZ_OTLP_ENDPOINT")
		delete(env, EnvPrefix+"OTLP_ENDPOINT")
		cfg, _ = ConfigFromEnvWith(Config{}, env)
		if cfg.OTLPEndpoint != "http://otel:4318" {
			t.Fatalf("OTEL_EXPORTER_OTLP_ENDPOINT fallback: %s", cfg.OTLPEndpoint)
		}
		cfg, _ = ConfigFromEnvWith(Config{}, map[string]string{"OTEL_EXPORTER_OTLP_PROTOCOL": "grpc"})
		if cfg.OTLPProtocol != ProtocolGRPC || cfg.OTLPEndpoint != DefaultOTLPGRPCEndpoint {
			t.Fatalf("grpc default: %s", cfg)
		}
		cfg, _ = ConfigFromEnvWith(Config{}, map[string]string{})
		if cfg.URL != DefaultURL || cfg.OTLPEndpoint != DefaultOTLPHTTPEndpoint || cfg.RequestTimeout != DefaultRequestTimeout || !cfg.APIKey.IsZero() {
			t.Fatalf("defaults: %s", cfg)
		}
		t.Setenv(EnvPrefix+"URL", "http://process-env:9")
		if cfg, err := ConfigFromEnv(); err != nil || cfg.URL != "http://process-env:9" {
			t.Fatalf("process env: %v %s", err, cfg)
		}
	})
	t.Run("core.config.invalid", func(t *testing.T) {
		bad := []map[string]string{
			{EnvPrefix + "TOTAL_TIMEOUT": "forever-xyz"},
			{EnvPrefix + "URL": "ftp://nope"},
			{EnvPrefix + "OTLP_PROTOCOL": "thrift"},
			{EnvPrefix + "OTLP_ENDPOINT": "collector:4318"},
		}
		for _, env := range bad {
			_, err := ConfigFromEnvWith(Config{}, env)
			if !errors.Is(err, ErrConfig) {
				t.Errorf("%v: %v", env, err)
			}
			for _, v := range env {
				if strings.Contains(fmt.Sprint(err), v) && v != "thrift" {
					t.Errorf("error leaks value: %v", err)
				}
			}
		}
		if _, err := NewClient(Config{CommonConfig: CommonConfig{URL: "nope"}}); !errors.Is(err, ErrConfig) {
			t.Fatalf("NewClient: %v", err)
		}
		if _, err := NewClientFromEnv(); err != nil {
			t.Fatalf("NewClientFromEnv with defaults: %v", err)
		}
		t.Setenv(EnvPrefix+"URL", "::bad")
		if _, err := NewClientFromEnv(); !errors.Is(err, ErrConfig) {
			t.Fatalf("NewClientFromEnv: %v", err)
		}
	})
	t.Run("core.redaction.config-string", func(t *testing.T) {
		cfg := Config{APIKey: NewSecret("api-SECRET-1"), IngestionKey: NewSecret("ing-SECRET-2"),
			CommonConfig: CommonConfig{Headers: map[string]string{"X-Token": "hdr-SECRET-3"}}}
		for _, s := range []string{cfg.String(), cfg.GoString(), fmt.Sprintf("%v %+v %#v", cfg, cfg, cfg)} {
			if strings.Contains(s, "SECRET") || !strings.Contains(s, Redacted) {
				t.Fatalf("leak: %s", s)
			}
		}
	})
}

func TestClientCore(t *testing.T) {
	m := newMock(t)
	m.json("GET /api/v1/health", 200, fixture(t, "health.ok.json"))
	m.json("GET /api/v1/fields/keys", 200, fixture(t, "listLogFields.search.json"))
	m.json("GET /api/v1/version", 200, fixture(t, "serverVersion.ok.json"))
	c := newTestClient(t, m.URL, nil)
	ctx := t.Context()

	t.Run("health", func(t *testing.T) {
		h, err := c.Health(ctx)
		if err != nil || !h.Healthy || h.Status != "ok" {
			t.Fatalf("%v %+v", err, h)
		}
		if r := m.last(t); r.Query != "live=1" || r.Header.Get(APIKeyHeader) != "" {
			t.Fatalf("health request %+v", r)
		}
		m.json("GET /api/v1/health", 503, []byte(`{"status":"down"}`))
		h, _ = c.Health(ctx)
		if h.Healthy || h.Status != "down" {
			t.Fatalf("unhealthy: %+v", h)
		}
		m.json("GET /api/v1/health", 200, fixture(t, "health.ok.json"))
		dead := newTestClient(t, "http://127.0.0.1:1", func(cfg *Config) { cfg.Retry = fastRetry(0) })
		if h, err := dead.Health(ctx); err != nil || h.Status != "unreachable" {
			t.Fatalf("unreachable: %v %+v", err, h)
		}
	})
	t.Run("ping", func(t *testing.T) {
		if d, err := c.Ping(ctx); err != nil || d <= 0 {
			t.Fatalf("%v %v", err, d)
		}
		r := m.last(t)
		if r.Header.Get(APIKeyHeader) != testToken || r.Query != "limit=1&signal=logs" || !strings.HasPrefix(r.Header.Get("User-Agent"), "grow-codes-signoz/") {
			t.Fatalf("ping request %+v", r)
		}
		m.json("GET /api/v1/fields/keys", 401, fixture(t, "ping.unauthenticated.json"))
		_, err := c.Ping(ctx)
		if e := asError(t, err); e.Code != ErrorCodeAuthentication || e.HTTPStatus != 401 || strings.Contains(e.Message, testToken) {
			t.Fatalf("401: %v", e)
		}
		m.on("GET /api/v1/fields/keys", func(w http.ResponseWriter, _ *http.Request) {
			w.Header().Set("Content-Type", "text/html")
			_, _ = w.Write([]byte("<!doctype html>"))
		})
		if _, err := c.Ping(ctx); !errors.Is(err, ErrUnsupported) {
			t.Fatalf("html: %v", err)
		}
	})
	t.Run("serverVersion", func(t *testing.T) {
		if _, err := c.ServerVersion(ctx); !errors.Is(err, ErrUnsupported) {
			t.Fatalf("empty version: %v", err)
		}
		m.json("GET /api/v1/version", 200, []byte(`{"version":"v0.150.0","ee":"Y"}`))
		if v, err := c.ServerVersion(ctx); err != nil || v != "0.150.0" {
			t.Fatalf("%v %q", err, v)
		}
		m.json("GET /api/v1/version", 200, []byte(`not json`))
		if _, err := c.ServerVersion(ctx); !errors.Is(err, ErrUnavailable) {
			t.Fatalf("bad json: %v", err)
		}
		m.json("GET /api/v1/version", 404, []byte(`{}`))
		if _, err := c.ServerVersion(ctx); !errors.Is(err, ErrUnsupported) {
			t.Fatalf("404: %v", err)
		}
		m.json("GET /api/v1/version", 403, fixture(t, "error.forbidden.json"))
		if _, err := c.ServerVersion(ctx); !errors.Is(err, ErrPermissionDenied) {
			t.Fatalf("403: %v", err)
		}
		m.on("GET /api/v1/version", func(w http.ResponseWriter, _ *http.Request) { _, _ = w.Write([]byte("<html>")) })
		if _, err := c.ServerVersion(ctx); !errors.Is(err, ErrUnsupported) {
			t.Fatalf("html: %v", err)
		}
	})
	t.Run("accessors", func(t *testing.T) {
		if c.Query() == nil || c.Logs() != LogQueryClient(c.Query()) || c.Traces() != TraceQueryClient(c.Query()) {
			t.Fatal("accessors")
		}
		if c.Config().OTLPEndpoint != DefaultOTLPHTTPEndpoint || c.Config().APIKey.Reveal() != testToken {
			t.Fatalf("config %s", c.Config())
		}
	})
}

func TestClientClose(t *testing.T) {
	col := newCollector(t)
	c := newTestClient(t, "http://127.0.0.1:1", func(cfg *Config) { cfg.OTLPEndpoint = col.URL })
	lc, err := c.NewLogClient(LogOptions{ServiceName: "svc"})
	if err != nil {
		t.Fatal(err)
	}
	h, err := c.InitTracing(t.Context(), TracingConfig{ServiceName: "svc", DisableSetGlobal: true})
	if err != nil {
		t.Fatal(err)
	}
	lc.Send(t.Context(), LogInput{Message: "closing"})
	_, span := h.Tracer("t").Start(context.Background(), "op")
	span.End()
	if err := c.Close(); err != nil {
		t.Fatal(err)
	}
	if err := c.Close(); err != nil {
		t.Fatal("close twice", err)
	}
	if n := col.logCount(); n != 1 {
		t.Fatalf("close must flush logs, got %d", n)
	}
	if col.traceCalls() == 0 {
		t.Fatal("close must flush spans")
	}
	if _, err := c.NewLogClient(LogOptions{}); !errors.Is(err, ErrCancelled) {
		t.Fatalf("after close: %v", err)
	}
	if _, err := c.InitTracing(t.Context(), TracingConfig{ServiceName: "svc", DisableSetGlobal: true}); !errors.Is(err, ErrCancelled) {
		t.Fatalf("after close: %v", err)
	}
	if _, err := c.Ping(t.Context()); !errors.Is(err, ErrCancelled) {
		t.Fatalf("ping after close: %v", err)
	}
	if _, err := c.InitTracing(t.Context(), TracingConfig{}); !errors.Is(err, ErrConfig) {
		t.Fatalf("invalid tracing config: %v", err)
	}
	if _, err := c.NewLogClient(LogOptions{Endpoint: "nope"}); !errors.Is(err, ErrConfig) {
		t.Fatalf("invalid log endpoint: %v", err)
	}
	if _, err := c.NewLogClient(LogOptions{LogClientOptions: LogClientOptions{Batch: BatchOptions{MaxBatchSize: -1}}}); !errors.Is(err, ErrConfig) && !errors.Is(err, ErrValidation) {
		t.Fatalf("invalid batch options: %v", err)
	}
}

func TestClientCloseReportsErrors(t *testing.T) {
	c := newTestClient(t, "http://127.0.0.1:1", nil)
	c.logs = append(c.logs, failingLogClient{})
	if err := c.Close(); err == nil {
		t.Fatal("expected the log client error")
	}
	if err := c.Close(); err == nil {
		t.Fatal("second close returns the first result")
	}
	_ = time.Second
}

type failingLogClient struct{ LogClient }

func (failingLogClient) Close(context.Context) error { return errors.New("boom") }
