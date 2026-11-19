package signoz

import (
	"fmt"
	"net/url"
	"strings"
)

// DefaultURL is the default query API endpoint of SigNoz (UI + API port 8080).
const DefaultURL = "http://127.0.0.1:8080"

// Default OTLP endpoints of the SigNoz collector.
const (
	DefaultOTLPHTTPEndpoint = "http://127.0.0.1:4318"
	DefaultOTLPGRPCEndpoint = "http://127.0.0.1:4317"
)

// Header names used by SigNoz. Both are redacted everywhere.
const (
	// APIKeyHeader carries the service-account API key on query calls.
	APIKeyHeader = "SIGNOZ-API-KEY" // #nosec G101 -- header name, not a credential
	// IngestionKeyHeader carries the SigNoz Cloud ingestion key on OTLP exports.
	IngestionKeyHeader = "signoz-ingestion-key"
)

// vendorEnv lists the vendor variables consulted after GROW_SIGNOZ_*.
var vendorEnv = VendorEnv{URL: []string{"SIGNOZ_URL"}}

// Vendor variables of the SigNoz specific fields, in precedence order.
var (
	vendorAPIKeyEnv       = []string{"SIGNOZ_API_KEY"}
	vendorIngestionKeyEnv = []string{"SIGNOZ_INGESTION_KEY"}
	vendorOTLPEndpointEnv = []string{"SIGNOZ_OTLP_ENDPOINT", "OTEL_EXPORTER_OTLP_ENDPOINT"}
	vendorOTLPProtocolEnv = []string{"OTEL_EXPORTER_OTLP_PROTOCOL"}
)

// Config configures a Client. Build it with a struct literal or ConfigFromEnv;
// zero values mean "use the default". Call Validate to check it.
//
// Query calls (QueryClient, Ping) go to URL with APIKey; ingest (logs exporter,
// InitTracing) goes to the collector at OTLPEndpoint. An ingest-only client
// does not need an APIKey.
type Config struct {
	CommonConfig

	// APIKey is the SigNoz service-account key sent as SIGNOZ-API-KEY on query
	// calls (env GROW_SIGNOZ_API_KEY, SIGNOZ_API_KEY). Any role works, viewer
	// included.
	APIKey Secret

	// OTLPEndpoint is the collector endpoint for logs and traces (env
	// GROW_SIGNOZ_OTLP_ENDPOINT, SIGNOZ_OTLP_ENDPOINT, OTEL_EXPORTER_OTLP_ENDPOINT).
	// Default http://127.0.0.1:4318 for OTLP/HTTP and http://127.0.0.1:4317 for
	// OTLP/gRPC.
	OTLPEndpoint string

	// OTLPProtocol is ProtocolHTTPProtobuf (default) or ProtocolGRPC (env
	// GROW_SIGNOZ_OTLP_PROTOCOL, OTEL_EXPORTER_OTLP_PROTOCOL).
	OTLPProtocol Protocol

	// IngestionKey is the SigNoz Cloud ingestion key, sent as the
	// signoz-ingestion-key header / gRPC metadata on exports (env
	// GROW_SIGNOZ_INGESTION_KEY, SIGNOZ_INGESTION_KEY). Self-hosted collectors
	// ignore it.
	IngestionKey Secret
}

// ConfigFromEnv reads the configuration from the process environment.
func ConfigFromEnv() (Config, error) { return ConfigFromEnvWith(Config{}, nil) }

// ConfigFromEnvWith resolves explicit values > GROW_SIGNOZ_* > vendor
// variables > defaults. env replaces the process environment when non-nil.
func ConfigFromEnvWith(explicit Config, env map[string]string) (Config, error) {
	r := NewEnvReader(EnvPrefix, env)
	c := explicit
	if err := c.applyEnv(r, vendorEnv); err != nil {
		return Config{}, err
	}
	if c.APIKey.IsZero() {
		if v, ok := r.String("API_KEY", vendorAPIKeyEnv...); ok {
			c.APIKey = NewSecret(v)
		}
	}
	if c.IngestionKey.IsZero() {
		if v, ok := r.String("INGESTION_KEY", vendorIngestionKeyEnv...); ok {
			c.IngestionKey = NewSecret(v)
		}
	}
	if c.OTLPEndpoint == "" {
		if v, ok := r.String("OTLP_ENDPOINT", vendorOTLPEndpointEnv...); ok {
			c.OTLPEndpoint = v
		}
	}
	if c.OTLPProtocol == "" {
		if v, ok := r.String("OTLP_PROTOCOL", vendorOTLPProtocolEnv...); ok {
			c.OTLPProtocol = Protocol(strings.ToLower(strings.TrimSpace(v)))
		}
	}
	c = c.withServiceDefaults()
	if err := c.Validate(); err != nil {
		return Config{}, err
	}
	return c, nil
}

// withServiceDefaults applies the common defaults plus the OTLP protocol and endpoint.
func (c Config) withServiceDefaults() Config {
	c.CommonConfig = c.withDefaults(DefaultURL)
	if c.OTLPProtocol == "" {
		c.OTLPProtocol = ProtocolHTTPProtobuf
	}
	if c.OTLPEndpoint == "" {
		if c.OTLPProtocol == ProtocolGRPC {
			c.OTLPEndpoint = DefaultOTLPGRPCEndpoint
		} else {
			c.OTLPEndpoint = DefaultOTLPHTTPEndpoint
		}
	}
	return c
}

// Validate checks the configuration and returns a CONFIG error.
func (c Config) Validate() error {
	d := c.withServiceDefaults()
	if err := d.validate(); err != nil {
		return err
	}
	switch d.OTLPProtocol {
	case ProtocolHTTPProtobuf, ProtocolGRPC:
	default:
		return ConfigError("otlpProtocol must be \"http/protobuf\" or \"grpc\"")
	}
	u, err := url.Parse(d.OTLPEndpoint)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
		return ConfigError("otlpEndpoint must be an absolute http or https URL")
	}
	return nil
}

// String renders the configuration with every credential redacted.
func (c Config) String() string {
	return fmt.Sprintf("Config{%s APIKey:%s OTLPEndpoint:%s OTLPProtocol:%s IngestionKey:%s}",
		c.describe(), c.APIKey, RedactURL(c.OTLPEndpoint), string(c.OTLPProtocol), c.IngestionKey)
}

// GoString renders the configuration with every credential redacted.
func (c Config) GoString() string { return c.String() }
