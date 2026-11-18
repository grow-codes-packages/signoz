#!/bin/sh
# Same sequence as the stack template: schema migrations (idempotent), then the collector.
set -eu
/opt/signoz/bin/signoz-otel-collector migrate ready
/opt/signoz/bin/signoz-otel-collector migrate bootstrap
/opt/signoz/bin/signoz-otel-collector migrate sync up
/opt/signoz/bin/signoz-otel-collector migrate async up
exec /opt/signoz/bin/signoz-otel-collector --config=/etc/signoz/collector.yaml
