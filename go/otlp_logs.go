package signoz

import (
	"bytes"
	"compress/gzip"
	"context"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"sort"
	"strings"
	"sync/atomic"

	collogspb "go.opentelemetry.io/proto/otlp/collector/logs/v1"
	commonpb "go.opentelemetry.io/proto/otlp/common/v1"
	logspb "go.opentelemetry.io/proto/otlp/logs/v1"
	resourcepb "go.opentelemetry.io/proto/otlp/resource/v1"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/credentials"
	"google.golang.org/grpc/credentials/insecure"
	grpcgzip "google.golang.org/grpc/encoding/gzip"
	"google.golang.org/grpc/metadata"
	grpcstatus "google.golang.org/grpc/status"
	"google.golang.org/protobuf/proto"
)

// LogScopeName is the instrumentation scope of records exported by this package.
const LogScopeName = "grow-codes-signoz"

// OTLPLogExporter is a LogExporter that sends records to the SigNoz collector
// over OTLP/HTTP protobuf (POST <endpoint>/v1/logs) or OTLP/gRPC
// (LogsService/Export), with the signoz-ingestion-key header when an ingestion
// key is configured. Retries are left to the BatchProcessor.
type OTLPLogExporter struct {
	protocol Protocol
	http     *httpTransport
	path     string
	conn     *grpc.ClientConn
	grpc     collogspb.LogsServiceClient
	md       metadata.MD
	secrets  []string
	closed   atomic.Bool
}

var _ LogExporter = (*OTLPLogExporter)(nil)

// otlpSignalURL appends /v1/<signal> to the endpoint path unless it already
// ends with it.
func otlpSignalURL(endpoint, signal string) (*url.URL, error) {
	u, err := url.Parse(endpoint)
	if err != nil || u.Host == "" {
		return nil, ConfigError("otlpEndpoint is invalid")
	}
	suffix := "/v1/" + signal
	if !strings.HasSuffix(u.Path, suffix) {
		u.Path = strings.TrimSuffix(u.Path, "/") + suffix
	}
	return u, nil
}

// NewOTLPLogExporter builds the exporter from cfg (OTLPEndpoint, OTLPProtocol,
// IngestionKey, TLS, Proxy, Headers, timeouts, hooks and logger).
func NewOTLPLogExporter(cfg Config) (*OTLPLogExporter, error) {
	if err := cfg.Validate(); err != nil {
		return nil, err
	}
	cfg = cfg.withServiceDefaults()
	e := &OTLPLogExporter{protocol: cfg.OTLPProtocol}
	if !cfg.IngestionKey.IsZero() {
		e.secrets = append(e.secrets, cfg.IngestionKey.Reveal())
	}
	if cfg.OTLPProtocol == ProtocolGRPC {
		return e, e.dialGRPC(cfg)
	}
	u, err := otlpSignalURL(cfg.OTLPEndpoint, "logs")
	if err != nil {
		return nil, err
	}
	e.path = u.Path
	u.Path, u.RawPath = "", ""
	common := cfg.CommonConfig
	common.URL = u.String()
	common.Headers = map[string]string{}
	for k, v := range cfg.Headers {
		common.Headers[k] = v
	}
	if !cfg.IngestionKey.IsZero() {
		common.Headers[IngestionKeyHeader] = cfg.IngestionKey.Reveal()
	}
	t, err := newHTTPTransport(common)
	if err != nil {
		return nil, err
	}
	t.addSecret(e.secrets...)
	e.http = t
	return e, nil
}

func (e *OTLPLogExporter) dialGRPC(cfg Config) error {
	u, err := url.Parse(cfg.OTLPEndpoint)
	if err != nil || u.Host == "" {
		return ConfigError("otlpEndpoint is invalid")
	}
	creds := insecure.NewCredentials()
	if u.Scheme == "https" {
		tlsCfg, err := buildTLS(cfg.TLS)
		if err != nil {
			return err
		}
		creds = credentials.NewTLS(tlsCfg)
	}
	conn, err := grpc.NewClient(u.Host, grpc.WithTransportCredentials(creds), grpc.WithUserAgent(UserAgent))
	if err != nil {
		return ConfigError("otlpEndpoint cannot be used for gRPC")
	}
	e.conn = conn
	e.grpc = collogspb.NewLogsServiceClient(conn)
	e.md = metadata.MD{}
	for k, v := range cfg.Headers {
		e.md.Set(strings.ToLower(k), v)
		if IsSensitiveHeader(k) {
			e.secrets = append(e.secrets, v)
		}
	}
	if !cfg.IngestionKey.IsZero() {
		e.md.Set(IngestionKeyHeader, cfg.IngestionKey.Reveal())
	}
	return nil
}

// Export sends one batch. The result counts records rejected by the
// collector's partial success.
func (e *OTLPLogExporter) Export(ctx context.Context, records []LogRecord, opts ExportOptions) (ExportResult, error) {
	const op = "logs.export"
	if e.closed.Load() {
		return ExportResult{}, &Error{Code: ErrorCodeCancelled, Message: op + ": exporter is shut down"}
	}
	if len(records) == 0 {
		return ExportResult{}, nil
	}
	req := BuildOTLPLogsRequest(records)
	var rejected int64
	var err error
	if e.protocol == ProtocolGRPC {
		rejected, err = e.exportGRPC(ctx, op, req, opts)
	} else {
		rejected, err = e.exportHTTP(ctx, op, req, opts)
	}
	if err != nil {
		return ExportResult{}, err
	}
	n := len(records)
	rej := int(min(max(rejected, 0), int64(n)))
	res := ExportResult{Accepted: n - rej, Rejected: rej}
	if rej > 0 {
		res.Error = ValidationError(op, fmt.Sprintf("collector rejected %d log records", rej))
	}
	return res, nil
}

func (e *OTLPLogExporter) exportHTTP(ctx context.Context, op string, req *collogspb.ExportLogsServiceRequest, opts ExportOptions) (int64, error) {
	body, err := proto.Marshal(req)
	if err != nil {
		return 0, ValidationError(op, "records cannot be encoded")
	}
	h := http.Header{}
	if opts.Gzip {
		var buf bytes.Buffer
		zw := gzip.NewWriter(&buf)
		_, _ = zw.Write(body)
		_ = zw.Close()
		body = buf.Bytes()
		h.Set("Content-Encoding", "gzip")
	}
	h.Set("Accept", "application/x-protobuf")
	res, err := e.http.do(ctx, &request{
		Method: http.MethodPost, Path: e.path, Body: body, ContentType: "application/x-protobuf",
		Header: h, Operation: op, NoRetry: true,
	})
	if err != nil {
		return 0, err
	}
	return rejectedFromHTTP(res), nil
}

// rejectedFromHTTP reads partial_success from a protobuf or JSON response.
func rejectedFromHTTP(res *response) int64 {
	if len(res.Body) == 0 {
		return 0
	}
	if strings.Contains(res.Header.Get("Content-Type"), "json") {
		var j struct {
			PartialSuccess struct {
				RejectedLogRecords json.Number `json:"rejectedLogRecords"`
			} `json:"partialSuccess"`
		}
		if json.Unmarshal(res.Body, &j) == nil {
			n, _ := j.PartialSuccess.RejectedLogRecords.Int64()
			return n
		}
		return 0
	}
	var out collogspb.ExportLogsServiceResponse
	if proto.Unmarshal(res.Body, &out) != nil {
		return 0
	}
	return out.GetPartialSuccess().GetRejectedLogRecords()
}

func (e *OTLPLogExporter) exportGRPC(ctx context.Context, op string, req *collogspb.ExportLogsServiceRequest, opts ExportOptions) (int64, error) {
	if len(e.md) > 0 {
		ctx = metadata.NewOutgoingContext(ctx, e.md)
	}
	var callOpts []grpc.CallOption
	if opts.Gzip {
		callOpts = append(callOpts, grpc.UseCompressor(grpcgzip.Name))
	}
	resp, err := e.grpc.Export(ctx, req, callOpts...)
	if err != nil {
		return 0, grpcError(ctx, op, err, e.secrets)
	}
	return resp.GetPartialSuccess().GetRejectedLogRecords(), nil
}

// grpcError maps a gRPC status to the SDK error classes.
func grpcError(ctx context.Context, op string, err error, secrets []string) error {
	if ctx.Err() != nil {
		return ContextError(op, ctx.Err())
	}
	st, _ := grpcstatus.FromError(err)
	msg := truncate(RedactText(st.Message(), secrets), maxVendorText)
	var code ErrorCode
	switch st.Code() {
	case codes.InvalidArgument, codes.OutOfRange, codes.FailedPrecondition:
		code = ErrorCodeValidation
	case codes.Unauthenticated:
		code = ErrorCodeAuthentication
	case codes.PermissionDenied:
		code = ErrorCodePermissionDenied
	case codes.NotFound:
		code = ErrorCodeNotFound
	case codes.ResourceExhausted:
		code = ErrorCodeRateLimited
	case codes.DeadlineExceeded:
		code = ErrorCodeTimeout
	case codes.Unimplemented:
		code = ErrorCodeUnsupported
	case codes.Canceled:
		code = ErrorCodeCancelled
	default:
		code = ErrorCodeUnavailable
	}
	return &Error{Code: code, Message: fmt.Sprintf("%s: %s (gRPC %s)", op, msg, st.Code()), Retryable: code.Retryable()}
}

// Shutdown closes connections. It is idempotent.
func (e *OTLPLogExporter) Shutdown(context.Context) error {
	if !e.closed.CompareAndSwap(false, true) {
		return nil
	}
	if e.conn != nil {
		_ = e.conn.Close()
	}
	if e.http != nil {
		e.http.close()
	}
	return nil
}

// BuildOTLPLogsRequest converts records to an OTLP ExportLogsServiceRequest,
// one ResourceLogs per distinct resource. Invalid trace or span ids are left
// out (the collector would reject the whole batch).
func BuildOTLPLogsRequest(records []LogRecord) *collogspb.ExportLogsServiceRequest {
	req := &collogspb.ExportLogsServiceRequest{}
	byResource := map[string]*logspb.ScopeLogs{}
	for _, r := range records {
		key, _ := json.Marshal(r.Resource) // maps marshal with sorted keys
		sl, ok := byResource[string(key)]
		if !ok {
			sl = &logspb.ScopeLogs{Scope: &commonpb.InstrumentationScope{Name: LogScopeName, Version: Version}}
			byResource[string(key)] = sl
			req.ResourceLogs = append(req.ResourceLogs, &logspb.ResourceLogs{
				Resource:  &resourcepb.Resource{Attributes: keyValues(r.Resource)},
				ScopeLogs: []*logspb.ScopeLogs{sl},
			})
		}
		sl.LogRecords = append(sl.LogRecords, otlpLogRecord(r))
	}
	return req
}

func otlpLogRecord(r LogRecord) *logspb.LogRecord {
	lr := &logspb.LogRecord{
		SeverityNumber: logspb.SeverityNumber(r.Severity), // #nosec G115 -- severities are 0..24
		SeverityText:   r.SeverityText,
		Body:           &commonpb.AnyValue{Value: &commonpb.AnyValue_StringValue{StringValue: r.Body}},
		Attributes:     keyValues(r.Attributes),
	}
	if !r.Timestamp.IsZero() {
		lr.TimeUnixNano = uint64(r.Timestamp.UnixNano()) // #nosec G115 -- post-1970 timestamps
	}
	if !r.ObservedTimestamp.IsZero() {
		lr.ObservedTimeUnixNano = uint64(r.ObservedTimestamp.UnixNano()) // #nosec G115 -- post-1970 timestamps
	}
	if b, err := hex.DecodeString(r.TraceID); err == nil && len(b) == 16 {
		lr.TraceId = b
	}
	if b, err := hex.DecodeString(r.SpanID); err == nil && len(b) == 8 {
		lr.SpanId = b
	}
	if r.TraceFlags != nil {
		lr.Flags = uint32(*r.TraceFlags)
	}
	return lr
}

func keyValues(m map[string]AttrValue) []*commonpb.KeyValue {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	out := make([]*commonpb.KeyValue, 0, len(keys))
	for _, k := range keys {
		if v := anyValue(m[k]); v != nil {
			out = append(out, &commonpb.KeyValue{Key: k, Value: v})
		}
	}
	return out
}

func anyValue(v AttrValue) *commonpb.AnyValue {
	switch x := NormalizeAttrValue(v).(type) {
	case nil:
		return nil
	case string:
		return &commonpb.AnyValue{Value: &commonpb.AnyValue_StringValue{StringValue: x}}
	case bool:
		return &commonpb.AnyValue{Value: &commonpb.AnyValue_BoolValue{BoolValue: x}}
	case int64:
		return &commonpb.AnyValue{Value: &commonpb.AnyValue_IntValue{IntValue: x}}
	case float64:
		return &commonpb.AnyValue{Value: &commonpb.AnyValue_DoubleValue{DoubleValue: x}}
	case []AttrValue:
		arr := &commonpb.ArrayValue{}
		for _, e := range x {
			if av := anyValue(e); av != nil {
				arr.Values = append(arr.Values, av)
			}
		}
		return &commonpb.AnyValue{Value: &commonpb.AnyValue_ArrayValue{ArrayValue: arr}}
	case map[string]AttrValue:
		return &commonpb.AnyValue{Value: &commonpb.AnyValue_KvlistValue{KvlistValue: &commonpb.KeyValueList{Values: keyValues(x)}}}
	default:
		return &commonpb.AnyValue{Value: &commonpb.AnyValue_StringValue{StringValue: fmt.Sprint(x)}}
	}
}
