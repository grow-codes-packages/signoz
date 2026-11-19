package signoz

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func sseEvent(id string, ms int64, body string) string {
	return fmt.Sprintf("data: {\"data\":{\"id\":%q,\"body\":%q,\"timestamp\":%d,\"severity_number\":9},\"timestamp\":\"x\"}\n\n", id, body, ms*1e6)
}

func TestTail(t *testing.T) {
	m := newMock(t)
	var conns atomic.Int32
	var mu sync.Mutex
	var starts []string
	m.on("GET /api/v3/logs/livetail", func(w http.ResponseWriter, r *http.Request) {
		n := conns.Add(1)
		mu.Lock()
		starts = append(starts, r.URL.Query().Get("start")+"|"+r.URL.Query().Get("filter"))
		mu.Unlock()
		w.Header().Set("Content-Type", "text/event-stream")
		fl := w.(http.Flusher)
		if n == 1 {
			_, _ = fmt.Fprint(w, ": comment\n\n")
			_, _ = fmt.Fprint(w, sseEvent("a", 1000, "first"))
			_, _ = fmt.Fprint(w, sseEvent("a", 1000, "first again"))
			_, _ = fmt.Fprint(w, "event: error\ndata: {\"error\":\"temporary\"}\n\n")
			_, _ = fmt.Fprint(w, "data: not json\n\n")
			_, _ = fmt.Fprint(w, "data: {\"data\":{\"id\":\"b\",\n")
			_, _ = fmt.Fprint(w, "data: \"body\":\"multi\",\"timestamp\":2000000000}}\n\n")
			fl.Flush()
			return // stream ends: client reconnects
		}
		_, _ = fmt.Fprint(w, sseEvent("b", 2000, "dup after reconnect"))
		_, _ = fmt.Fprint(w, sseEvent("c", 1500, "late but new"))
		_, _ = fmt.Fprint(w, sseEvent("d", 3000, "unterminated"))
		fl.Flush()
		<-r.Context().Done()
	})
	q := newQueryClient(t, m)
	got := make(chan LogRecord, 16)
	h, err := q.Tail(t.Context(), "body CONTAINS 'x'", func(r LogRecord) {
		if r.Body == "panic" {
			panic("ignored")
		}
		got <- r
	}, TailOptions{Start: time.UnixMilli(500), Interval: 10 * time.Millisecond})
	if err != nil {
		t.Fatal(err)
	}
	var bodies []string
	timeout := time.After(5 * time.Second)
	for len(bodies) < 4 {
		select {
		case r := <-got:
			bodies = append(bodies, r.Body)
		case <-timeout:
			t.Fatalf("timeout, got %v", bodies)
		}
	}
	if strings.Join(bodies, ",") != "first,multi,late but new,unterminated" {
		t.Fatalf("bodies %v", bodies)
	}
	h.Stop()
	h.Stop()
	mu.Lock()
	defer mu.Unlock()
	if starts[0] != "500|body CONTAINS 'x'" || starts[1] != "2000|body CONTAINS 'x'" {
		t.Fatalf("starts %v", starts)
	}
	if r := m.requests()[0]; r.Header.Get(APIKeyHeader) != testToken || r.Header.Get("Accept") != "text/event-stream" {
		t.Fatalf("headers %v", r.Header)
	}
}

func TestTailErrors(t *testing.T) {
	m := newMock(t)
	q := newQueryClient(t, m)
	ctx := t.Context()
	if _, err := q.Tail(ctx, "", nil, TailOptions{}); !errors.Is(err, ErrValidation) {
		t.Fatal(err)
	}
	m.json("GET /api/v3/logs/livetail", 401, fixture(t, "ping.unauthenticated.json"))
	if _, err := q.Tail(ctx, "", func(LogRecord) {}, TailOptions{}); !errors.Is(err, ErrAuthentication) {
		t.Fatal(err)
	}
	if r := m.last(t); strings.Contains(r.Query, "filter") {
		t.Fatalf("empty filter must be omitted: %s", r.Query)
	}
	m.json("GET /api/v3/logs/livetail", 200, []byte(`{}`))
	if _, err := q.Tail(ctx, "", func(LogRecord) {}, TailOptions{}); !errors.Is(err, ErrUnsupported) {
		t.Fatal(err)
	}
	dead := newTestClient(t, "http://127.0.0.1:1", nil).Query()
	if _, err := dead.Tail(ctx, "", func(LogRecord) {}, TailOptions{}); !errors.Is(err, ErrUnavailable) {
		t.Fatal(err)
	}

	t.Run("reconnect stops on a non-retryable error", func(t *testing.T) {
		var n atomic.Int32
		m.on("GET /api/v3/logs/livetail", func(w http.ResponseWriter, _ *http.Request) {
			if n.Add(1) > 1 {
				w.WriteHeader(403)
				return
			}
			w.Header().Set("Content-Type", "text/event-stream")
		})
		h, err := q.Tail(ctx, "", func(LogRecord) {}, TailOptions{Interval: time.Millisecond})
		if err != nil {
			t.Fatal(err)
		}
		deadline := time.Now().Add(5 * time.Second)
		for n.Load() < 2 && time.Now().Before(deadline) {
			time.Sleep(5 * time.Millisecond)
		}
		<-h.(*sseTail).done
		h.Stop()
	})
	t.Run("reconnect retries unavailable", func(t *testing.T) {
		var n atomic.Int32
		m.on("GET /api/v3/logs/livetail", func(w http.ResponseWriter, _ *http.Request) {
			switch n.Add(1) {
			case 1:
				w.Header().Set("Content-Type", "text/event-stream")
			case 2:
				w.WriteHeader(503)
			default:
				w.Header().Set("Content-Type", "text/event-stream")
				_, _ = fmt.Fprint(w, sseEvent("z", 1, "back"))
			}
		})
		got := make(chan string, 4)
		h, err := q.Tail(ctx, "", func(r LogRecord) { got <- r.Body }, TailOptions{Interval: time.Millisecond})
		if err != nil {
			t.Fatal(err)
		}
		select {
		case b := <-got:
			if b != "back" {
				t.Fatal(b)
			}
		case <-time.After(5 * time.Second):
			t.Fatal("no record after reconnect")
		}
		h.Stop()
	})
	t.Run("ctx cancel and client close stop the tail", func(t *testing.T) {
		m.on("GET /api/v3/logs/livetail", func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Content-Type", "text/event-stream")
			w.(http.Flusher).Flush()
			<-r.Context().Done()
		})
		cctx, cancel := context.WithCancel(ctx)
		h, err := q.Tail(cctx, "", func(LogRecord) {}, TailOptions{})
		if err != nil {
			t.Fatal(err)
		}
		cancel()
		select {
		case <-h.(*sseTail).done:
		case <-time.After(5 * time.Second):
			t.Fatal("ctx cancel did not stop the tail")
		}
		c := newTestClient(t, m.URL, nil)
		h2, err := c.Query().Tail(ctx, "", func(LogRecord) {}, TailOptions{})
		if err != nil {
			t.Fatal(err)
		}
		_ = c.Close()
		select {
		case <-h2.(*sseTail).done:
		case <-time.After(5 * time.Second):
			t.Fatal("close did not stop the tail")
		}
		if _, err := c.Query().Tail(ctx, "", func(LogRecord) {}, TailOptions{}); !errors.Is(err, ErrCancelled) {
			t.Fatal(err)
		}
	})
}

func TestTailDedupBound(t *testing.T) {
	s := &sseTail{q: newQueryClient(t, newMock(t)), onRecord: func(LogRecord) {}, seen: map[string]struct{}{}}
	for i := 0; i < maxTailSeen+5; i++ {
		s.dispatch("", fmt.Sprintf(`{"data":{"id":"%d","timestamp":1}}`, i))
	}
	if len(s.seen) != maxTailSeen || len(s.seenOrder) != maxTailSeen {
		t.Fatalf("seen %d", len(s.seen))
	}
	s.dispatch("", "")
	s.dispatch("", `{"timestamp":"x"}`)
	if !IsRetryable(ErrUnavailable) || IsRetryable(errors.New("x")) {
		t.Fatal("IsRetryable")
	}
}
