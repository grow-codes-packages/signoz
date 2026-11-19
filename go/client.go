package signoz

import (
	"context"
	"errors"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

// DefaultCloseTimeout bounds how long Client.Close waits for log clients and
// tracing handles created by the client to flush.
const DefaultCloseTimeout = 30 * time.Second

// Client is the SigNoz client. It is safe for concurrent use.
//
// The client talks to two endpoints: the query API (Config.URL, port 8080) for
// Health, Ping, ServerVersion and Query(), and the OTLP collector
// (Config.OTLPEndpoint) for NewLogClient and InitTracing.
type Client struct {
	cfg       Config
	transport *httpTransport
	query     *QueryClient

	mu       sync.Mutex
	closed   bool
	logs     []LogClient
	handles  []*TracingHandle
	closeErr error
}

// NewClient validates cfg and returns a client.
func NewClient(cfg Config) (*Client, error) {
	if err := cfg.Validate(); err != nil {
		return nil, err
	}
	cfg = cfg.withServiceDefaults()
	t, err := newHTTPTransport(cfg.CommonConfig)
	if err != nil {
		return nil, err
	}
	t.addSecret(cfg.APIKey.Reveal(), cfg.IngestionKey.Reveal())
	c := &Client{cfg: cfg, transport: t}
	c.query = &QueryClient{t: t, apiKey: cfg.APIKey, now: time.Now}
	return c, nil
}

// NewClientFromEnv builds a client from the environment (see ConfigFromEnv).
func NewClientFromEnv() (*Client, error) {
	cfg, err := ConfigFromEnv()
	if err != nil {
		return nil, err
	}
	return NewClient(cfg)
}

// Config returns the effective configuration (defaults applied).
func (c *Client) Config() Config { return c.cfg }

// Query returns the query client (logs, traces, services, aggregations) of
// the SigNoz query API. It implements LogQueryClient and TraceQueryClient.
func (c *Client) Query() *QueryClient { return c.query }

// Logs returns the logging-category query client (search, tail, streams,
// labels, waitForLog); it is the same object as Query.
func (c *Client) Logs() LogQueryClient { return c.query }

// Traces returns the tracing-category query client (getTrace, searchTraces,
// services, operations, dependencies, waitForTrace); it is the same object as
// Query.
func (c *Client) Traces() TraceQueryClient { return c.query }

// Health checks liveness without credentials (GET /api/v1/health?live=1, which
// also checks ClickHouse). It reports an unreachable or unhealthy service in
// the result; it fails only for CANCELLED and CONFIG.
func (c *Client) Health(ctx context.Context) (HealthStatus, error) {
	return c.transport.checkHealth(ctx, "health", &request{
		Method: http.MethodGet, Path: "/api/v1/health", Query: url.Values{"live": {"1"}},
	})
}

// Ping makes an authenticated round trip (GET /api/v1/fields/keys) and returns
// its latency. A missing or wrong API key fails with an AUTHENTICATION error.
func (c *Client) Ping(ctx context.Context) (time.Duration, error) {
	start := time.Now()
	res, err := c.transport.do(ctx, &request{
		Method: http.MethodGet, Path: "/api/v1/fields/keys",
		Query:  url.Values{"signal": {"logs"}, "limit": {"1"}},
		Header: c.query.authHeader(), Operation: "ping",
	})
	if err != nil {
		return 0, err
	}
	if err := checkNotHTML("ping", res); err != nil {
		return 0, err
	}
	return time.Since(start), nil
}

// ServerVersion returns the server version without a leading "v". The official
// community image reports an empty version; that is an UNSUPPORTED error.
func (c *Client) ServerVersion(ctx context.Context) (string, error) {
	const op = "serverVersion"
	res, err := c.transport.do(ctx, &request{Method: http.MethodGet, Path: "/api/v1/version", Operation: op})
	if err != nil {
		if CodeOf(err) == ErrorCodeNotFound {
			return "", UnsupportedError(op, "the server exposes no version")
		}
		return "", err
	}
	if err := checkNotHTML(op, res); err != nil {
		return "", err
	}
	var body struct {
		Version string `json:"version"`
	}
	if err := res.decodeJSON(op, &body); err != nil {
		return "", err
	}
	v := strings.TrimPrefix(strings.TrimSpace(body.Version), "v")
	if v == "" {
		return "", UnsupportedError(op, "server does not report its version")
	}
	return v, nil
}

// LogOptions configure NewLogClient. The zero value is valid.
type LogOptions struct {
	// LogClientOptions holds batching, resource, redaction and minimum severity.
	LogClientOptions
	// ServiceName, ServiceVersion and Environment set the service.name
	// (default "unknown_service"), service.version and deployment.environment
	// resource attributes unless Resource already has them.
	ServiceName    string
	ServiceVersion string
	Environment    string
	// Protocol overrides Config.OTLPProtocol.
	Protocol Protocol
	// Endpoint overrides Config.OTLPEndpoint.
	Endpoint string
	// Headers are extra export headers (merged over Config.Headers).
	Headers map[string]string
}

// NewLogClient starts a batching LogClient that sends OTLP logs to the SigNoz
// collector described by cfg. It does not need an API key (only IngestionKey
// for SigNoz Cloud). Close it to flush.
func NewLogClient(cfg Config, opts LogOptions) (*BatchingLogClient, error) {
	if opts.Protocol != "" {
		if opts.Protocol != cfg.OTLPProtocol && opts.Endpoint == "" && cfg.OTLPEndpoint != "" {
			cfg.OTLPEndpoint = "" // the other protocol's default port
		}
		cfg.OTLPProtocol = opts.Protocol
	}
	if opts.Endpoint != "" {
		cfg.OTLPEndpoint = opts.Endpoint
	}
	if len(opts.Headers) > 0 {
		h := map[string]string{}
		for k, v := range cfg.Headers {
			h[k] = v
		}
		for k, v := range opts.Headers {
			h[k] = v
		}
		cfg.Headers = h
	}
	exp, err := NewOTLPLogExporter(cfg)
	if err != nil {
		return nil, err
	}
	lo := opts.LogClientOptions
	res := map[string]AttrValue{"service.name": "unknown_service"}
	if opts.ServiceName != "" {
		res["service.name"] = opts.ServiceName
	}
	if opts.ServiceVersion != "" {
		res["service.version"] = opts.ServiceVersion
	}
	if opts.Environment != "" {
		res["deployment.environment"] = opts.Environment
	}
	for k, v := range lo.Resource {
		res[k] = v
	}
	lo.Resource = res
	lc, err := NewBatchingLogClient(exp, lo)
	if err != nil {
		_ = exp.Shutdown(context.Background())
		return nil, err
	}
	return lc, nil
}

// NewLogClient starts a batching LogClient that exports to the SigNoz collector
// with this client's configuration (see the package-level NewLogClient). Close
// of the client also flushes and closes it.
func (c *Client) NewLogClient(opts LogOptions) (*BatchingLogClient, error) {
	lc, err := NewLogClient(c.cfg, opts)
	if err != nil {
		return nil, err
	}
	if err := c.track(func() { c.logs = append(c.logs, lc) }); err != nil {
		_ = lc.Close(context.Background())
		return nil, err
	}
	return lc, nil
}

// InitTracing starts OpenTelemetry tracing that exports to the SigNoz collector
// (see the package-level InitTracing). Endpoint, Protocol and the ingestion key
// header default to this client's configuration. Close of the client also
// shuts the handle down.
func (c *Client) InitTracing(ctx context.Context, tc TracingConfig) (*TracingHandle, error) {
	h, err := initTracing(ctx, c.cfg, tc)
	if err != nil {
		return nil, err
	}
	if err := c.track(func() { c.handles = append(c.handles, h) }); err != nil {
		_ = h.Shutdown(ctx)
		return nil, err
	}
	return h, nil
}

func (c *Client) track(add func()) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return &Error{Code: ErrorCodeCancelled, Message: "signoz: client is closed"}
	}
	add()
	return nil
}

// Close flushes and closes every log client and tracing handle created by this
// client (bounded by DefaultCloseTimeout) and releases connections. It is
// idempotent; later calls return the first result.
func (c *Client) Close() error {
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return c.closeErr
	}
	c.closed = true
	logs, handles := c.logs, c.handles
	c.logs, c.handles = nil, nil
	c.mu.Unlock()

	ctx, cancel := context.WithTimeout(context.Background(), DefaultCloseTimeout)
	defer cancel()
	var errs []error
	for _, lc := range logs {
		if err := lc.Close(ctx); err != nil {
			errs = append(errs, err)
		}
	}
	for _, h := range handles {
		if err := h.Shutdown(ctx); err != nil {
			errs = append(errs, err)
		}
	}
	c.query.stopTails()
	c.transport.close()
	err := errors.Join(errs...)
	c.mu.Lock()
	c.closeErr = err
	c.mu.Unlock()
	return err
}
