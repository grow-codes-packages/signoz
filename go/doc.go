// Package signoz is the Grow Codes SDK for SigNoz.
//
// Create a client from the environment (GROW_SIGNOZ_URL,
// GROW_SIGNOZ_TOKEN, ...) or from a Config literal:
//
//	client, err := signoz.NewClientFromEnv()
//	if err != nil {
//		return err
//	}
//	defer client.Close()
//	status, err := client.Health(ctx)
//
// Every error is an *Error; compare codes with errors.Is against the Err*
// sentinels (errors.Is(err, signoz.ErrNotFound)). Credentials are wrapped in
// Secret and never appear in errors, hooks or logs.
//
// SigNoz is a trademark of SigNoz, Inc.. This package is not affiliated
// with or endorsed by SigNoz, Inc..
package signoz
