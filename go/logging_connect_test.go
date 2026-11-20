package signoz

// Service-owned: generated once by sdk-kit, then maintained in this repository.

import (
	"context"
	"errors"
	"testing"
)

// TestConnectLogClientDefault checks the connector without network I/O.
func TestConnectLogClientDefault(t *testing.T) {
	conn, err := connectLogClient(map[string]AttrValue{"service.name": "t"}, map[string]string{}, func([]LogRecord) {})
	if err != nil {
		if !errors.Is(err, ErrConfig) {
			t.Fatalf("connector failed: %v", err)
		}
		return // the service needs more settings (credentials): initLogging falls back to stdout
	}
	if conn != nil && conn.Close != nil {
		if err := conn.Close(context.Background()); err != nil {
			t.Fatalf("close: %v", err)
		}
	}
}
