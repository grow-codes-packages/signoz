# Mirrors the Grow stack template `signoz-clickhouse` (platform seed gro1285-observability-templates.ts):
# ClickHouse 25.12.5 + one-node `cluster` with embedded Keeper + SigNoz histogramQuantile UDF (SHA-256 pinned).
FROM clickhouse/clickhouse-server:25.12.5
ARG TARGETARCH
RUN set -eu; \
    case "${TARGETARCH:-amd64}" in \
      amd64) SUM=33997073eb6d82b7be4f27f9fc8ec9e28a395e0f20674d5d54bb2fa28a75488d ;; \
      arm64) SUM=e5605ebffa82a450ebbcdf6cf19dad546e1e40d52dbf3e03cfd2d2d5b7394211 ;; \
      *) exit 1 ;; esac; \
    mkdir -p /opt/signoz/user_scripts /etc/clickhouse-server/config.d; \
    wget -qO /tmp/hq.tgz "https://github.com/SigNoz/signoz/releases/download/histogram-quantile%2Fv0.0.1/histogram-quantile_linux_${TARGETARCH:-amd64}.tar.gz"; \
    echo "$SUM  /tmp/hq.tgz" | sha256sum -c -; \
    tar -xzf /tmp/hq.tgz -C /tmp histogram-quantile; \
    mv /tmp/histogram-quantile /opt/signoz/user_scripts/histogramQuantile; \
    chmod 0755 /opt/signoz/user_scripts/histogramQuantile; rm -f /tmp/hq.tgz
COPY signoz.xml /etc/clickhouse-server/config.d/signoz.xml
COPY signoz_function.xml /etc/clickhouse-server/signoz_function.xml
