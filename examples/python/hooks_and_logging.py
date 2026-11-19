"""Hooks, logging, retries and timeouts. Hook data and log fields are always redacted."""

from __future__ import annotations

import logging

import grow_signoz as sdk


def main() -> None:
    logging.basicConfig(level=logging.INFO)
    logging.getLogger(sdk.LOGGER_NAME).setLevel(logging.DEBUG)  # silent unless enabled

    hooks = sdk.Hooks(
        on_request=lambda info: print(f"-> {info.method} {info.url} (attempt {info.attempt})"),
        on_response=lambda info: print(f"<- {info.status} in {info.duration_ms:.0f} ms"),
        on_error=lambda info: print(f"!! {info.error} retry={info.will_retry}"),
    )
    config = sdk.SignozConfig.from_env(
        hooks=hooks,
        request_timeout=5.0,
        total_timeout=30.0,
        retry=sdk.RetryPolicy(max_retries=2, initial_backoff=0.2),
        user_agent_suffix="example/1.0",
    )
    print(config)  # credentials print as [REDACTED]
    with sdk.SignozClient(config) as client:
        print(client.health())
        # Python logging → SigNoz: a logging.Handler that ships records over OTLP.
        logs = client.log_client(service_name="example-py-logging")
        logging.getLogger("app").addHandler(sdk.LogHandler(logs))
        logging.getLogger("app").warning("disk almost full", extra={"disk": "/var"})
        logs.flush(10)


if __name__ == "__main__":
    main()
