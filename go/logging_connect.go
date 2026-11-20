package signoz

// Service-owned: generated once by sdk-kit, then maintained in this repository.

import "context"

// connectLogClient is the InitLogging connector: a client from the package's own variables and a
// batching log client with resource attached and onDropped as BatchOptions.OnDrop (records the
// SDK could not export go back to stdout). No network I/O.
func connectLogClient(resource map[string]AttrValue, env map[string]string, onDropped func([]LogRecord)) (*LogConnection, error) {
	cfg, err := ConfigFromEnvWith(Config{}, env)
	if err != nil {
		return nil, err
	}
	client, err := NewClient(cfg)
	if err != nil {
		return nil, err
	}
	opts := LogClientOptions{
		Resource: resource,
		Batch:    BatchOptions{OnDrop: func(records []LogRecord, _ DropReason) { onDropped(records) }},
	}
	logs, err := client.NewLogClient(LogOptions{LogClientOptions: opts})
	if err != nil {
		_ = client.Close()
		return nil, err
	}
	return &LogConnection{Logs: logs, Close: func(ctx context.Context) error {
		err := logs.Close(ctx)
		if cerr := client.Close(); err == nil {
			err = cerr
		}
		return err
	}}, nil
}
