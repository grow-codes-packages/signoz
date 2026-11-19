# Client and status

| Function | Request | Behaviour |
| - | - | - |
| `fromEnv()` | — | builds the config from `GROW_SIGNOZ_*` and the vendor variables ([Configuration](../configuration.md)) |
| `health()` | `GET /api/v1/health?live=1` (no API key) | never throws for an unhealthy or unreachable server: returns `healthy=false` with `status` `unhealthy`/`unreachable`; only `CANCELLED` and `CONFIG` raise |
| `ping()` | `GET /api/v1/fields/keys?signal=logs&limit=1` with `SIGNOZ-API-KEY` | authenticated round trip, returns the latency; `AUTHENTICATION` for a bad key, `CONFIG` without one |
| `serverVersion()` | `GET /api/v1/version` (no API key) | version without the leading `v`; **`UNSUPPORTED`** when the server reports an empty version (the official community image does) |
| `close()` | — | idempotent; flushes and closes every log client, shuts down every tracing handle and stops every tail created through the client, then releases connections. Every later call raises `CANCELLED` |

=== "TypeScript"

    ```ts
    import { SignozClient, UnsupportedError } from "@grow-codes/signoz";

    const client = SignozClient.fromEnv();
    const health = await client.health(); // { healthy, status, details }
    const ms = await client.ping({ signal: AbortSignal.timeout(5_000) });
    try {
      console.log(await client.serverVersion());
    } catch (err) {
      if (!(err instanceof UnsupportedError)) throw err; // community image: empty version
    }
    await client.close();
    ```

=== "Rust"

    ```rust
    use grow_signoz::{ErrorCode, SignozClient};

    let client = SignozClient::from_env()?;
    let health = client.health().await?;
    let latency = client.ping().await?;
    match client.server_version().await {
        Ok(v) => println!("{v}"),
        Err(e) if e.code() == ErrorCode::Unsupported => println!("version not reported"),
        Err(e) => return Err(e),
    }
    client.close().await;
    ```

=== "Go"

    ```go
    client, err := signoz.NewClientFromEnv()
    if err != nil {
        return err
    }
    defer client.Close()
    health, err := client.Health(ctx)
    latency, err := client.Ping(ctx)
    if v, err := client.ServerVersion(ctx); errors.Is(err, signoz.ErrUnsupported) {
        fmt.Println("version not reported")
    } else if err == nil {
        fmt.Println(v)
    }
    ```

=== "Python"

    ```python
    import grow_signoz as sdk

    with sdk.SignozClient.from_env() as client:
        health = client.health()
        latency_ms = client.ping()
        try:
            print(client.server_version())
        except sdk.UnsupportedError:
            print("version not reported")

    # async
    async with sdk.AsyncSignozClient.from_env() as aclient:
        print(await aclient.health())
    ```

=== "Java"

    ```java
    try (SignozClient client = SignozClient.fromEnv()) {
      HealthStatus health = client.health();
      Duration latency = client.ping();
      try {
        System.out.println(client.serverVersion());
      } catch (UnsupportedException e) {
        System.out.println("version not reported");
      }
      client.pingAsync().join(); // every call has an …Async variant
    }
    ```

See [Client](../client.md) for the shared client contract (hooks, logger, timeouts, retries).
