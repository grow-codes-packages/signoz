package signoz

import (
	"context"
)

// InitTracing starts OpenTelemetry tracing that exports spans to SigNoz. The
// collector endpoint, protocol, ingestion key and TLS settings come from the
// environment (ConfigFromEnv: GROW_SIGNOZ_OTLP_ENDPOINT, SIGNOZ_OTLP_ENDPOINT,
// ...) unless tc sets them. An API key is not needed. Use Client.InitTracing to
// take them from an existing client instead.
func InitTracing(ctx context.Context, tc TracingConfig) (*TracingHandle, error) {
	cfg, err := ConfigFromEnv()
	if err != nil {
		return nil, err
	}
	return initTracing(ctx, cfg, tc)
}

func initTracing(ctx context.Context, cfg Config, tc TracingConfig) (*TracingHandle, error) {
	tc, err := TracingConfigFor(cfg, tc)
	if err != nil {
		return nil, err
	}
	return InitTracingWith(ctx, tc, NewOTLPExporter)
}

// TracingConfigFor fills the export settings tc leaves empty from cfg:
// Protocol (Config.OTLPProtocol), Endpoint (Config.OTLPEndpoint, with
// /v1/traces for OTLP/HTTP; the protocol's default port when tc picks another
// protocol than cfg), TLS, and the signoz-ingestion-key header when an
// ingestion key is configured. ProtocolNative is a CONFIG error.
func TracingConfigFor(cfg Config, tc TracingConfig) (TracingConfig, error) {
	cfg = cfg.withServiceDefaults()
	if tc.Protocol == ProtocolNative {
		return tc, ConfigError("tracing: SigNoz ingests OTLP only (protocol grpc or http/protobuf)")
	}
	if tc.Protocol == "" {
		tc.Protocol = cfg.OTLPProtocol
	}
	if tc.Endpoint == "" {
		endpoint := cfg.OTLPEndpoint
		if tc.Protocol != cfg.OTLPProtocol {
			endpoint = DefaultOTLPHTTPEndpoint
			if tc.Protocol == ProtocolGRPC {
				endpoint = DefaultOTLPGRPCEndpoint
			}
		}
		if tc.Protocol != ProtocolGRPC {
			u, err := otlpSignalURL(endpoint, "traces")
			if err != nil {
				return tc, err
			}
			endpoint = u.String()
		}
		tc.Endpoint = endpoint
	}
	if tc.TLS == (TLSConfig{}) {
		tc.TLS = cfg.TLS
	}
	headers := map[string]string{}
	for k, v := range cfg.Headers {
		headers[k] = v
	}
	if !cfg.IngestionKey.IsZero() {
		headers[IngestionKeyHeader] = cfg.IngestionKey.Reveal()
	}
	for k, v := range tc.Headers {
		headers[k] = v
	}
	if len(headers) > 0 {
		tc.Headers = headers
	}
	return tc, nil
}
