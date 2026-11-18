# Mirrors the Grow stack template `signoz-otel-collector` (platform seed gro1285-observability-templates.ts):
# Debian base + official SigNoz OTel Collector release binary v0.144.15 (SHA-256 pinned), non-root user 10001.
FROM debian:trixie-20261104-slim
ARG TARGETARCH
RUN set -eu; \
    apt-get update && apt-get install -y --no-install-recommends ca-certificates wget && rm -rf /var/lib/apt/lists/*; \
    case "${TARGETARCH:-amd64}" in \
      amd64) SUM=232f931ef997083a7b8062e12e44b5bfaaf984b3ce0504c724d8d6c990dca025 ;; \
      arm64) SUM=06fc58f7e82269f3405e02fd7a18834e9f25de3592b013125a6dbdf834564db0 ;; \
      *) exit 1 ;; esac; \
    wget -qO /tmp/c.tgz "https://github.com/SigNoz/signoz-otel-collector/releases/download/v0.144.15/signoz-otel-collector_linux_${TARGETARCH:-amd64}.tar.gz"; \
    echo "$SUM  /tmp/c.tgz" | sha256sum -c -; \
    mkdir -p /opt/signoz/bin /etc/signoz; \
    tar -xzf /tmp/c.tgz -C /tmp; \
    mv /tmp/signoz-otel-collector_linux_*/bin/signoz-otel-collector /opt/signoz/bin/signoz-otel-collector; \
    rm -rf /tmp/c.tgz /tmp/signoz-otel-collector_linux_*; \
    groupadd -r -g 10001 signoz && useradd -r -u 10001 -g signoz -d /nonexistent -s /usr/sbin/nologin signoz
COPY collector.yaml /etc/signoz/collector.yaml
COPY start.sh /opt/signoz/start.sh
USER 10001:10001
WORKDIR /opt/signoz
ENTRYPOINT ["/bin/sh", "/opt/signoz/start.sh"]
