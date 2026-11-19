package integration

import (
	"context"
	"strings"
	"testing"

	signoz "github.com/grow-codes-packages/signoz/go"
	"github.com/grow-codes-packages/signoz/go/testkit"
)

// capabilities the deployment under test offers (see conformance "requires").
// "version" is left out: the community image reports an empty version, so
// ServerVersion returns UNSUPPORTED (docs/compatibility.md).
var capabilities = []string{"query", "tail", "labels", "streams", "services", "dependencies"}

// categories runs "core" plus this service's categories.
func categories() []string {
	return append([]string{"core"}, strings.Split("logging,tracing", ",")...)
}

// factories builds the conformance targets.
func factories(t *testing.T) testkit.Factories {
	t.Helper()
	return testkit.Factories{
		"core": testkit.CoreFactory(func(context.Context) (testkit.CoreTarget, error) {
			return signoz.NewClientFromEnv()
		}),
		"logging": testkit.LoggingFactory(func(_ context.Context, opts signoz.BatchOptions) (testkit.LoggingTarget, error) {
			client, err := signoz.NewClientFromEnv()
			if err != nil {
				return testkit.LoggingTarget{}, err
			}
			lc, err := client.NewLogClient(signoz.LogOptions{
				ServiceName:      "grow-conformance-go",
				LogClientOptions: signoz.LogClientOptions{Batch: opts},
			})
			if err != nil {
				_ = client.Close()
				return testkit.LoggingTarget{}, err
			}
			return testkit.LoggingTarget{Client: closingLogClient{lc, client}, Query: client.Logs()}, nil
		}),
		"tracing": testkit.TracingFactory(func(ctx context.Context, serviceName string) (testkit.TracingTarget, error) {
			client, err := signoz.NewClientFromEnv()
			if err != nil {
				return testkit.TracingTarget{}, err
			}
			// The query client lists a service as soon as its spans are queryable (v5
			// aggregation over the spans table), so no warm-up is needed.
			h, err := signoz.InitTracing(ctx, signoz.TracingConfig{ServiceName: serviceName, DisableSetGlobal: true})
			if err != nil {
				_ = client.Close()
				return testkit.TracingTarget{}, err
			}
			t.Cleanup(func() { _ = client.Close() })
			return testkit.TracingTarget{Handle: h, Query: client.Traces()}, nil
		}),
	}
}

// closingLogClient closes the owning client together with the log client.
type closingLogClient struct {
	*signoz.BatchingLogClient
	client *signoz.Client
}

func (c closingLogClient) Close(ctx context.Context) error {
	err := c.BatchingLogClient.Close(ctx)
	_ = c.client.Close()
	return err
}
