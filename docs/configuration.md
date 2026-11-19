# Configuration

`SignozConfig` holds every setting of a client. It is **immutable** after construction,
validated when it is built (invalid values raise a `CONFIG` error) and its string/debug form
**redacts every credential** (the API key and the ingestion key print as `[REDACTED]`).

A client talks to two SigNoz endpoints:

- the **query API** (`url`, port 8080) with the `SIGNOZ-API-KEY` header: `ping()`, every query
  function and the live tail;
- the **OTLP collector** (`otlpEndpoint`, ports 4318/4317): the log client, the log exporter and
  `initTracing`. Ingest needs no API key; SigNoz Cloud needs the `ingestionKey`.

## SigNoz settings

| Setting | Default | Environment variables (precedence order) |
| - | - | - |
| `url` — query API base URL | `http://127.0.0.1:8080` | `GROW_SIGNOZ_URL`, `SIGNOZ_URL` |
| `apiKey` — service-account API key (secret), sent as `SIGNOZ-API-KEY` | none (required for `ping()` and every query) | `GROW_SIGNOZ_API_KEY`, `SIGNOZ_API_KEY` |
| `otlpEndpoint` — OTLP collector endpoint | `http://127.0.0.1:4318` (`http/protobuf`), `http://127.0.0.1:4317` (`grpc`) | `GROW_SIGNOZ_OTLP_ENDPOINT`, `SIGNOZ_OTLP_ENDPOINT`, `OTEL_EXPORTER_OTLP_ENDPOINT` |
| `otlpProtocol` — `http/protobuf` or `grpc` | `http/protobuf` | `GROW_SIGNOZ_OTLP_PROTOCOL`, `OTEL_EXPORTER_OTLP_PROTOCOL` |
| `ingestionKey` — SigNoz Cloud ingestion key (secret), sent as the `signoz-ingestion-key` header / gRPC metadata | none (self-hosted collectors ignore it) | `GROW_SIGNOZ_INGESTION_KEY`, `SIGNOZ_INGESTION_KEY` |

Names per language: TypeScript/Java `apiKey`, `otlpEndpoint`, `otlpProtocol`, `ingestionKey`;
Rust/Python `api_key`, `otlp_endpoint`, `otlp_protocol`, `ingestion_key`; Go `APIKey`,
`OTLPEndpoint`, `OTLPProtocol`, `IngestionKey`.

The API key belongs to a SigNoz service account (Settings → Service accounts). Any role works;
the read-only `signoz-viewer` role is enough for every query function.

## Common settings

| Setting | Default | Environment variable |
| - | - | - |
| `connectTimeout` — connection setup | 10 s | `GROW_SIGNOZ_CONNECT_TIMEOUT` |
| `requestTimeout` — per attempt (headers + body) | 30 s | `GROW_SIGNOZ_REQUEST_TIMEOUT` |
| `totalTimeout` — whole call incl. retries and backoff | 120 s | `GROW_SIGNOZ_TOTAL_TIMEOUT` |
| `retry` — retry policy (below) | 3 retries | `GROW_SIGNOZ_MAX_RETRIES` |
| `tls.caCertPath` / `tls.caCertPem` — extra CA | none | `GROW_SIGNOZ_CA_CERT` (path) |
| `tls.clientCertPath` + `tls.clientKeyPath` — mutual TLS | none | `GROW_SIGNOZ_CLIENT_CERT`, `GROW_SIGNOZ_CLIENT_KEY` |
| `tls.insecureSkipVerify` — tests only, logs a warning once | `false` | `GROW_SIGNOZ_INSECURE_SKIP_VERIFY` |
| `proxy` — proxy URL | none (environment proxies are **ignored**) | `GROW_SIGNOZ_PROXY` |
| `headers` — extra request headers | `{}` | — |
| `userAgentSuffix` — appended to the User-Agent | none | — |
| `hooks` — request/response callbacks ([Hooks](hooks.md)) | none | — |
| `logger` — diagnostic logger ([Hooks](hooks.md)) | silent | — |

Every request sends `User-Agent: grow-codes-signoz/<version> (<language>)`.

## From the environment

`fromEnv` resolves each setting with this precedence:

1. the value passed in code (overrides),
2. `GROW_SIGNOZ_<NAME>`,
3. the vendor variables (`SIGNOZ_*`, `OTEL_EXPORTER_OTLP_*`, table above), in that order,
4. the default.

Parsing rules:

- **Durations** accept integer milliseconds (`1500`) or Go-style units: `500ms`, `10s`, `2m`, `1h`,
  combinations such as `1m30s`.
- **Booleans** accept `true/false`, `1/0`, `yes/no`, `on/off` (any case).
- An **empty** variable counts as unset.
- A present but **invalid** value raises a `CONFIG` error that names the variable, never its value.

=== "TypeScript"

    ```ts
    import { SignozClient, SignozConfig } from "@grow-codes/signoz";

    // Environment only
    const client = SignozClient.fromEnv();

    // Environment with explicit overrides (explicit values win)
    const config = SignozConfig.fromEnv({ requestTimeoutMs: 5_000 });

    // Everything in code
    const explicit = new SignozConfig({
      url: "https://signoz.example.com",
      apiKey: process.env.MY_SIGNOZ_API_KEY,
      otlpEndpoint: "https://otel.example.com:4318",
      otlpProtocol: "http/protobuf",
      connectTimeoutMs: 5_000,
      requestTimeoutMs: 15_000,
      totalTimeoutMs: 60_000,
      retry: { maxRetries: 5, initialBackoffMs: 200 },
      tls: { caCertPath: "/etc/ssl/internal-ca.pem" },
      proxy: "http://proxy.internal:3128",
      headers: { "x-team": "payments" },
      userAgentSuffix: "my-app/1.4",
    });
    const custom = new SignozClient(explicit);
    console.log(String(explicit)); // API key shown as [REDACTED]
    ```

=== "Rust"

    ```rust
    use std::time::Duration;
    use grow_signoz::tracing::Protocol;
    use grow_signoz::{RetryPolicy, SignozClient, SignozConfig};

    // Environment only
    let client = SignozClient::from_env()?;

    // Environment with explicit overrides (explicit values win)
    let config = SignozConfig::builder()
        .request_timeout(Duration::from_secs(5))
        .build_from_env()?;

    // Everything in code
    let explicit = SignozConfig::builder()
        .url("https://signoz.example.com")
        .api_key(std::env::var("MY_SIGNOZ_API_KEY").unwrap_or_default())
        .otlp_endpoint("https://otel.example.com:4317")
        .otlp_protocol(Protocol::Grpc)
        .connect_timeout(Duration::from_secs(5))
        .request_timeout(Duration::from_secs(15))
        .total_timeout(Duration::from_secs(60))
        .retry(RetryPolicy::default().with_max_retries(5))
        .ca_cert_path("/etc/ssl/internal-ca.pem")
        .proxy("http://proxy.internal:3128")
        .header("x-team", "payments")
        .user_agent_suffix("my-app/1.4")
        .build()?;
    let custom = SignozClient::new(explicit)?;
    println!("{:?}", custom.config()); // API key shown as [REDACTED]
    ```

=== "Go"

    ```go
    // Environment only
    client, err := signoz.NewClientFromEnv()

    // Everything in code: zero values mean "use the default"
    cfg := signoz.Config{
        CommonConfig: signoz.CommonConfig{
            URL:             "https://signoz.example.com",
            ConnectTimeout:  5 * time.Second,
            RequestTimeout:  15 * time.Second,
            TotalTimeout:    time.Minute,
            Retry:           &signoz.RetryPolicy{MaxRetries: 5, InitialBackoff: 200 * time.Millisecond},
            TLS:             signoz.TLSConfig{CACertPath: "/etc/ssl/internal-ca.pem"},
            Proxy:           "http://proxy.internal:3128",
            Headers:         map[string]string{"x-team": "payments"},
            UserAgentSuffix: "my-app/1.4",
        },
        APIKey:       signoz.NewSecret(os.Getenv("MY_SIGNOZ_API_KEY")),
        OTLPEndpoint: "https://otel.example.com:4318",
        OTLPProtocol: signoz.ProtocolHTTPProtobuf,
    }
    if err := cfg.Validate(); err != nil {
        log.Fatal(err) // errors.Is(err, signoz.ErrConfig)
    }
    custom, err := signoz.NewClient(cfg)

    // Environment with explicit overrides (explicit values win; nil env = process environment)
    merged, err := signoz.ConfigFromEnvWith(signoz.Config{
        CommonConfig: signoz.CommonConfig{RequestTimeout: 5 * time.Second},
    }, nil)
    fmt.Println(merged) // API key shown as [REDACTED]
    ```

=== "Python"

    ```python
    import os
    from grow_signoz import RetryPolicy, TlsConfig, SignozClient, SignozConfig

    # Environment only
    client = SignozClient.from_env()

    # Environment with explicit overrides (explicit values win)
    config = SignozConfig.from_env(request_timeout=5.0)

    # Everything in code: durations are float seconds (datetime.timedelta also works)
    explicit = SignozConfig(
        url="https://signoz.example.com",
        api_key=os.environ.get("MY_SIGNOZ_API_KEY"),
        otlp_endpoint="https://otel.example.com:4318",
        otlp_protocol="http/protobuf",
        connect_timeout=5.0,
        request_timeout=15.0,
        total_timeout=60.0,
        retry=RetryPolicy(max_retries=5, initial_backoff=0.2),
        tls=TlsConfig(ca_cert_path="/etc/ssl/internal-ca.pem"),
        proxy="http://proxy.internal:3128",
        headers={"x-team": "payments"},
        user_agent_suffix="my-app/1.4",
    )
    custom = SignozClient(explicit)
    print(repr(explicit))  # API key shown as [REDACTED]
    ```

=== "Java"

    ```java
    import java.time.Duration;
    import codes.grow.packages.signoz.OtlpProtocol;
    import codes.grow.packages.signoz.SignozClient;
    import codes.grow.packages.signoz.SignozConfig;
    import codes.grow.packages.signoz.core.RetryPolicy;

    // Environment only
    SignozClient client = SignozClient.fromEnv();

    // Everything in code
    SignozConfig config = SignozConfig.builder()
        .url("https://signoz.example.com")
        .apiKey(System.getenv("MY_SIGNOZ_API_KEY"))
        .otlpEndpoint("https://otel.example.com:4317")
        .otlpProtocol(OtlpProtocol.GRPC)
        .connectTimeout(Duration.ofSeconds(5))
        .requestTimeout(Duration.ofSeconds(15))
        .totalTimeout(Duration.ofMinutes(1))
        .retry(RetryPolicy.builder().maxRetries(5).initialBackoff(Duration.ofMillis(200)).build())
        .proxy("http://proxy.internal:3128")
        .header("x-team", "payments")
        .userAgentSuffix("my-app/1.4")
        .build();
    SignozClient custom = SignozClient.create(config);
    System.out.println(config); // API key shown as [REDACTED]
    ```

## Retry policy

| Field | Default | Meaning |
| - | - | - |
| `maxRetries` | 3 | retries after the first attempt (so up to 4 attempts) |
| `initialBackoff` | 100 ms | backoff cap of the first retry |
| `maxBackoff` | 10 s | cap of every backoff |
| `multiplier` | 2 | growth of the cap per retry |
| `maxRetryAfter` | 60 s | longest server-requested `Retry-After` the client waits for |

Backoff for retry *n* (0-based) is **full jitter**: a random wait between 0 and
`min(maxBackoff, initialBackoff × multiplier^n)`.

Requests are retried on HTTP 429, 408, 502, 503, 504 and on network errors. **Idempotent**
requests (`GET HEAD OPTIONS PUT DELETE`, or marked idempotent) are also retried on HTTP 500.
Other requests (for example `POST`) are retried only on 429 and on connection errors that happened
before the request was sent, never after an uncertain send.

On 429 and 503 the client honours `Retry-After` (seconds or HTTP date) and waits
`max(retryAfter, backoff)`. When that wait exceeds `maxRetryAfter` or the remaining
`totalTimeout`, it stops at once with `RATE_LIMITED`/`UNAVAILABLE` and sets `retryAfter` on the
error. Set `maxRetries` to 0 to turn retries off.

## Timeouts and cancellation

- `connectTimeout` bounds connection setup, `requestTimeout` each attempt, `totalTimeout` the whole
  call including backoff sleeps. An exhausted timeout raises `TIMEOUT`.
- Cancellation is honoured during requests and backoff sleeps and raises `CANCELLED`:
  TypeScript `{ signal }` (`AbortSignal`), Rust dropping the future, Go `ctx`, Python `cancel=`
  (`CancelToken`) or task cancellation, Java `…Async` futures (`cancel(true)`).

## TLS and proxies

- Add a private CA with `tls.caCertPath` (or the PEM text in `tls.caCertPem`).
- Mutual TLS needs `tls.clientCertPath` and `tls.clientKeyPath` together.
- `tls.insecureSkipVerify` disables certificate checks. Use it for local tests only; the client
  logs a warning once. It applies to the query API and to OTLP/HTTP exports in every language;
  for OTLP/**gRPC** exports it differs per language (see
  [Compatibility](compatibility.md#tls-insecureskipverify-and-otlpgrpc)).
- The same TLS settings are used for an `https://` OTLP endpoint (CA bundle and client
  certificate).
- `HTTP_PROXY`/`HTTPS_PROXY` are **ignored** so that credentials never leave through an unexpected
  proxy. Set `proxy` (or `GROW_SIGNOZ_PROXY`) to use one.
