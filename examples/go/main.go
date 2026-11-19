// Command example shows the SigNoz Go SDK against a running SigNoz: health,
// an authenticated ping, sending logs over OTLP, querying them back with a
// filter, tracing a request and reading the trace, and an aggregation.
//
//	GROW_SIGNOZ_URL=http://127.0.0.1:8080 \
//	GROW_SIGNOZ_API_KEY=... \
//	GROW_SIGNOZ_OTLP_ENDPOINT=http://127.0.0.1:4318 go run .
package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"time"

	"go.opentelemetry.io/otel/trace"

	signoz "github.com/grow-codes-packages/signoz/go"
)

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
}

func run() error {
	cfg, err := signoz.ConfigFromEnv()
	if err != nil {
		return err // CONFIG error naming the variable, never its value
	}
	cfg.Logger = slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: slog.LevelWarn}))
	fmt.Println("config:", cfg) // the API key and ingestion key print as [REDACTED]

	client, err := signoz.NewClient(cfg)
	if err != nil {
		return err
	}
	defer func() { _ = client.Close() }() // flushes log clients and tracing handles

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()

	health, err := client.Health(ctx)
	if err != nil {
		return err
	}
	fmt.Printf("health: healthy=%t status=%s\n", health.Healthy, health.Status)

	latency, err := client.Ping(ctx)
	switch {
	case errors.Is(err, signoz.ErrAuthentication):
		return fmt.Errorf("check GROW_SIGNOZ_API_KEY: %w", err)
	case err != nil:
		return err
	}
	fmt.Println("ping:", latency)

	if v, err := client.ServerVersion(ctx); errors.Is(err, signoz.ErrUnsupported) {
		fmt.Println("server version: not reported by this image")
	} else if err == nil {
		fmt.Println("server version:", v)
	}

	// Logs: batched OTLP export to the collector, then query them back.
	run := fmt.Sprint(time.Now().UnixNano())
	logs, err := client.NewLogClient(signoz.LogOptions{ServiceName: "example-go", Environment: "dev"})
	if err != nil {
		return err
	}
	logger := logs.Logger("example", map[string]signoz.AttrValue{"run": run})
	logger.Info(ctx, "checkout started", map[string]signoz.AttrValue{"order.id": 42})
	logger.Error(ctx, "payment failed", map[string]signoz.AttrValue{"order.id": 42, "password": "redacted-before-send"})
	if err := logs.Flush(ctx); err != nil {
		return err
	}

	filter, err := signoz.NewFilter().
		Eq("service.name", "example-go").
		Eq("run", run).
		In("severity_text", "ERROR", "FATAL").
		Build()
	if err != nil {
		return err
	}
	q := client.Query()
	rec, err := q.WaitForLog(ctx, filter, signoz.WaitForLogOptions{Timeout: 30 * time.Second})
	if err != nil {
		return err
	}
	fmt.Printf("log: %s %q attrs=%v\n", rec.SeverityText, rec.Body, rec.Attributes)

	// Tracing: OpenTelemetry spans exported to the collector, then read back.
	tracing, err := client.InitTracing(ctx, signoz.TracingConfig{ServiceName: "example-go"})
	if err != nil {
		return err
	}
	_, span := tracing.Tracer("example").Start(ctx, "GET /checkout", trace.WithSpanKind(trace.SpanKindServer))
	span.End()
	if err := tracing.ForceFlush(ctx); err != nil {
		return err
	}
	tr, err := q.WaitForTrace(ctx, span.SpanContext().TraceID().String(), signoz.WaitForTraceOptions{Timeout: 60 * time.Second})
	if err != nil {
		return err
	}
	fmt.Printf("trace %s: %d span(s), root %q\n", tr.TraceID, len(tr.Spans), tr.Spans[0].Name)

	// Aggregation: calls and p99 latency per operation over the last hour.
	agg, err := q.Aggregate(ctx, signoz.NewAggregate(signoz.SignalTraces).
		Where("service.name = 'example-go'").
		Count("calls").
		Percentile(99, "duration_nano", "p99").
		GroupBy("name").
		Query())
	if err != nil {
		return err
	}
	for _, row := range agg.Rows {
		fmt.Printf("operation %v: calls=%v p99=%vns\n", row.Groups["name"], row.Values["calls"], row.Values["p99"])
	}
	return nil
}
