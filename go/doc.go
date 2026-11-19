// Package signoz is the Grow Codes SDK for SigNoz (logging and tracing).
//
// Create a client from the environment (GROW_SIGNOZ_URL / SIGNOZ_URL,
// GROW_SIGNOZ_API_KEY / SIGNOZ_API_KEY, GROW_SIGNOZ_OTLP_ENDPOINT /
// SIGNOZ_OTLP_ENDPOINT, GROW_SIGNOZ_INGESTION_KEY / SIGNOZ_INGESTION_KEY, ...)
// or from a Config literal:
//
//	client, err := signoz.NewClientFromEnv()
//	if err != nil {
//		return err
//	}
//	defer client.Close()
//	status, err := client.Health(ctx)
//
// Ingest goes to the SigNoz OTLP collector: Client.NewLogClient returns a
// batching LogClient (OTLP/HTTP protobuf or OTLP/gRPC) with a Logger facade
// and slog/zap adapters, and Client.InitTracing starts OpenTelemetry tracing.
// Queries go to the SigNoz query API with the SIGNOZ-API-KEY header:
// Client.Query returns a QueryClient with QueryLogs, Tail, QueryTraces,
// GetTrace, SearchTraces, ListServices, ListOperations, GetDependencies,
// ListLogFields and Aggregate. Filter expressions are built with NewFilter and
// aggregations with NewAggregate.
//
// Every error is an *Error; compare codes with errors.Is against the Err*
// sentinels (errors.Is(err, signoz.ErrNotFound)). Credentials are wrapped in
// Secret and never appear in errors, hooks or logs.
//
// SigNoz is a trademark of SigNoz, Inc. This package is not affiliated with or
// endorsed by SigNoz, Inc.
package signoz
