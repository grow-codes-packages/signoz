package signoz

import (
	"bufio"
	"context"
	"io"
	"log/slog"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"
)

// DefaultTailReconnect is the wait before Tail reconnects a dropped stream.
const DefaultTailReconnect = time.Second

// sseTail is a running live tail.
type sseTail struct {
	q        *QueryClient
	filter   string
	onRecord func(LogRecord)
	interval time.Duration
	cancel   context.CancelFunc
	done     chan struct{}
	once     sync.Once

	lastMs    int64
	seen      map[string]struct{}
	seenOrder []string
}

// maxTailSeen bounds the ids remembered for de-duplication across reconnects.
const maxTailSeen = 10000

// Stop ends the tail and waits for the reader to finish. It is idempotent.
func (s *sseTail) Stop() {
	s.once.Do(func() {
		s.cancel()
		<-s.done
		s.q.tailsMu.Lock()
		delete(s.q.tails, s)
		s.q.tailsMu.Unlock()
	})
}

// Tail streams new log records matching query (a SigNoz filter expression;
// empty = all) from the live tail API (GET /api/v3/logs/livetail, server-sent
// events) and calls onRecord for each, in arrival order, until the handle is
// stopped, ctx is done or the client is closed. The first connection is made
// before Tail returns, so a bad API key fails at once (AUTHENTICATION). A
// dropped stream is reconnected after opts.Interval (default 1 s) from the last
// seen timestamp; records already delivered are skipped by id. opts.Start is
// where tailing begins (default now).
func (q *QueryClient) Tail(ctx context.Context, query string, onRecord func(LogRecord), opts TailOptions) (TailHandle, error) {
	const op = "logs.tail"
	if onRecord == nil {
		return nil, ValidationError(op, "onRecord is required")
	}
	start := opts.Start
	if start.IsZero() {
		start = q.now()
	}
	interval := opts.Interval
	if interval <= 0 {
		interval = DefaultTailReconnect
	}
	tctx, cancel := context.WithCancel(context.WithoutCancel(ctx))
	s := &sseTail{
		q: q, filter: query, onRecord: onRecord, interval: interval, cancel: cancel,
		done: make(chan struct{}), lastMs: start.UnixMilli(), seen: map[string]struct{}{},
	}
	body, err := s.connect(tctx, op)
	if err != nil {
		cancel()
		return nil, err
	}
	q.tailsMu.Lock()
	if q.tails == nil {
		q.tails = map[*sseTail]struct{}{}
	}
	q.tails[s] = struct{}{}
	q.tailsMu.Unlock()
	go func() {
		select {
		case <-ctx.Done():
			cancel()
		case <-tctx.Done():
		}
	}()
	go s.run(tctx, body)
	return s, nil
}

// stopTails stops every running tail (Client.Close).
func (q *QueryClient) stopTails() {
	q.tailsMu.Lock()
	tails := make([]*sseTail, 0, len(q.tails))
	for s := range q.tails {
		tails = append(tails, s)
	}
	q.tailsMu.Unlock()
	for _, s := range tails {
		s.Stop()
	}
}

// connect opens the event stream from s.lastMs.
func (s *sseTail) connect(ctx context.Context, op string) (io.ReadCloser, error) {
	t := s.q.t
	if t.closed.Load() {
		return nil, &Error{Code: ErrorCodeCancelled, Message: op + ": client is closed"}
	}
	params := url.Values{"start": {strconv.FormatInt(s.lastMs, 10)}}
	if strings.TrimSpace(s.filter) != "" {
		params.Set("filter", s.filter)
	}
	u := t.resolve(&request{Path: "/api/v3/logs/livetail", Query: params})
	safeURL := t.redact(redactParsedURL(u))
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u.String(), nil)
	if err != nil {
		return nil, ValidationError(op, "request cannot be built")
	}
	req.Header.Set("User-Agent", t.userAgent)
	req.Header.Set("Accept", "text/event-stream")
	for k, v := range t.cfg.Headers {
		req.Header.Set(k, v)
	}
	for k, vs := range s.q.authHeader() {
		req.Header[k] = vs
	}
	t.hooks.request(RequestInfo{Method: http.MethodGet, URL: safeURL, Headers: RedactHeaders(req.Header), Attempt: 1})
	began := t.now()
	resp, err := t.client.Do(req)
	if err != nil {
		e := t.networkError(ctx, ctx, ctx, op, err)
		t.hooks.error(ErrorInfo{Method: http.MethodGet, URL: safeURL, Err: e, Attempt: 1})
		return nil, e
	}
	rid := requestID(resp.Header)
	t.hooks.response(ResponseInfo{Method: http.MethodGet, URL: safeURL, Status: resp.StatusCode, Duration: t.now().Sub(began), Attempt: 1, RequestID: rid})
	if resp.StatusCode >= 300 {
		data, _ := io.ReadAll(io.LimitReader(resp.Body, 64<<10))
		_ = resp.Body.Close()
		e := StatusError(op, resp.StatusCode, rid, vendorErrorText(&response{Status: resp.StatusCode, Header: resp.Header, Body: data}), t.knownSecrets())
		t.hooks.error(ErrorInfo{Method: http.MethodGet, URL: safeURL, Err: e, Attempt: 1})
		return nil, e
	}
	if !strings.Contains(strings.ToLower(resp.Header.Get("Content-Type")), "text/event-stream") {
		_ = resp.Body.Close()
		return nil, &Error{Code: ErrorCodeUnsupported, Message: op + ": endpoint not available on this server version", HTTPStatus: resp.StatusCode, RequestID: rid}
	}
	return resp.Body, nil
}

// run reads the stream and reconnects until ctx is done.
func (s *sseTail) run(ctx context.Context, body io.ReadCloser) {
	defer close(s.done)
	for {
		s.read(ctx, body)
		_ = body.Close()
		for {
			if sleepContext(ctx, s.interval) != nil {
				return
			}
			var err error
			body, err = s.connect(ctx, "logs.tail")
			if err == nil {
				break
			}
			if ctx.Err() != nil {
				return
			}
			s.q.t.log(slog.LevelWarn, "signoz live tail reconnect failed", slog.String("error", err.Error()))
			if !IsRetryable(err) {
				return
			}
		}
	}
}

// IsRetryable reports whether err is an *Error marked retryable.
func IsRetryable(err error) bool {
	e, ok := err.(*Error) //nolint:errorlint // only direct SDK errors carry the flag
	return ok && e.Retryable
}

// read dispatches the events of one connection.
func (s *sseTail) read(ctx context.Context, body io.Reader) {
	sc := bufio.NewScanner(body)
	sc.Buffer(make([]byte, 64<<10), 8<<20)
	var event string
	var data strings.Builder
	for sc.Scan() {
		line := sc.Text()
		switch {
		case line == "":
			s.dispatch(event, data.String())
			event = ""
			data.Reset()
		case strings.HasPrefix(line, ":"):
		case strings.HasPrefix(line, "event:"):
			event = strings.TrimSpace(strings.TrimPrefix(line, "event:"))
		case strings.HasPrefix(line, "data:"):
			if data.Len() > 0 {
				data.WriteByte('\n')
			}
			data.WriteString(strings.TrimPrefix(strings.TrimPrefix(line, "data:"), " "))
		}
		if ctx.Err() != nil {
			return
		}
	}
	if data.Len() > 0 && ctx.Err() == nil {
		s.dispatch(event, data.String())
	}
}

func (s *sseTail) dispatch(event, data string) {
	if data == "" {
		return
	}
	if event == "error" {
		s.q.t.log(slog.LevelWarn, "signoz live tail error event", slog.String("error", s.q.t.redact(truncate(data, maxVendorText))))
		return
	}
	var row rawRow
	if decode("logs.tail", []byte(data), &row) != nil || row.Data == nil {
		return
	}
	ms := rowMillis(row)
	id := str(row.Data, "id")
	if id != "" {
		if _, dup := s.seen[id]; dup {
			return
		}
		s.seen[id] = struct{}{}
		s.seenOrder = append(s.seenOrder, id)
		if len(s.seenOrder) > maxTailSeen {
			delete(s.seen, s.seenOrder[0])
			s.seenOrder = s.seenOrder[1:]
		}
	}
	if ms > s.lastMs {
		s.lastMs = ms
	}
	rec := logRecordFromRow(row)
	safeCall(func() { s.onRecord(rec) })
}
