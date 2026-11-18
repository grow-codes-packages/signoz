#!/bin/sh
# SigNoz bootstrap for the SDK Docker fixture (POSIX sh; needs curl + jq).
# 1. waits for SigNoz (/api/v1/health?live=1 checks ClickHouse too)
# 2. logs in as the root admin user (created by SIGNOZ_USER_ROOT_* on the signoz service)
# 3. creates service account `grow-sdk-tests` with the managed `signoz-viewer` role
# 4. creates an API key for it (sent as header SIGNOZ-API-KEY)
# 5. writes /bootstrap/env (KEY=value lines)
# Idempotent: when /bootstrap/env already holds a key that still authenticates, it exits 0;
# an existing service account is reused (a new key is added).
set -eu

U="${SIGNOZ_URL:-http://signoz:8080}"
EMAIL="${SIGNOZ_ADMIN_EMAIL:?}"
PASS="${SIGNOZ_ADMIN_PASSWORD:?}"
SA_NAME="${SIGNOZ_SA_NAME:-grow-sdk-tests}"
ROLE_NAME="${SIGNOZ_SA_ROLE:-signoz-viewer}"
TTL="${SIGNOZ_KEY_TTL_SECONDS:-604800}"
OUT="${BOOTSTRAP_ENV:-/bootstrap/env}"

log() { echo "bootstrap: $*" >&2; }
die() { log "ERROR: $*"; exit 1; }

i=0
until curl -fsS "$U/api/v1/health?live=1" >/dev/null 2>&1; do
  i=$((i + 1)); [ "$i" -gt 120 ] && die "SigNoz not healthy after 240 s"
  sleep 2
done

# Already bootstrapped and key still valid?
if [ -s "$OUT" ]; then
  OLD=$(sed -n 's/^SIGNOZ_API_KEY=//p' "$OUT" | head -n1)
  if [ -n "$OLD" ] && [ "$(curl -s -o /dev/null -w '%{http_code}' -H "SIGNOZ-API-KEY: $OLD" "$U/api/v1/fields/keys?signal=logs&limit=1")" = "200" ]; then
    log "existing $OUT still valid, nothing to do"; exit 0
  fi
fi

ENC_EMAIL=$(printf '%s' "$EMAIL" | jq -sRr @uri)
ORG=""
i=0
while [ -z "$ORG" ] || [ "$ORG" = "null" ]; do
  ORG=$(curl -fsS "$U/api/v2/sessions/context?email=$ENC_EMAIL&ref=$(printf '%s' "$U" | jq -sRr @uri)" | jq -r '.data.orgs[0].id // empty' || true)
  i=$((i + 1)); [ "$i" -gt 60 ] && die "root user/org not created (check SIGNOZ_USER_ROOT_*)"
  [ -z "$ORG" ] && sleep 2
done

LOGIN=$(jq -nc --arg e "$EMAIL" --arg p "$PASS" --arg o "$ORG" '{email:$e,password:$p,orgId:$o}')
TOK=$(curl -fsS -X POST "$U/api/v2/sessions/email_password" -H 'content-type: application/json' -d "$LOGIN" | jq -r '.data.accessToken // empty')
[ -n "$TOK" ] || die "admin login failed"
AUTH="Authorization: Bearer $TOK"

api() { # method path [json]
  if [ $# -ge 3 ]; then
    curl -fsS -X "$1" "$U$2" -H "$AUTH" -H 'content-type: application/json' -d "$3"
  else
    curl -fsS -X "$1" "$U$2" -H "$AUTH"
  fi
}

SA=$(api GET /api/v1/service_accounts | jq -r --arg n "$SA_NAME" '.data[]? | select(.name==$n) | .id' | head -n1)
if [ -z "$SA" ]; then
  SA=$(api POST /api/v1/service_accounts "$(jq -nc --arg n "$SA_NAME" '{name:$n}')" | jq -r '.data.id')
  ROLE=$(api GET /api/v1/roles | jq -r --arg n "$ROLE_NAME" '.data[] | select(.name==$n) | .id')
  [ -n "$ROLE" ] || die "role $ROLE_NAME not found"
  api POST /api/v1/service_account_roles "$(jq -nc --arg s "$SA" --arg r "$ROLE" '{serviceAccountId:$s,roleId:$r}')" >/dev/null
  log "service account $SA_NAME created with role $ROLE_NAME"
else
  log "service account $SA_NAME reused"
fi

EXP=$(( $(date +%s) + TTL ))
KEY=$(api POST "/api/v1/service_accounts/$SA/keys" "$(jq -nc --arg n "sdk-$(date +%s)" --argjson x "$EXP" '{name:$n,expiresAt:$x}')" | jq -r '.data.key // empty')
[ -n "$KEY" ] || die "API key creation failed"

i=0
until [ "$(curl -s -o /dev/null -w '%{http_code}' -H "SIGNOZ-API-KEY: $KEY" "$U/api/v1/fields/keys?signal=logs&limit=1")" = "200" ]; do
  i=$((i + 1)); [ "$i" -gt 15 ] && die "new API key is not accepted"
  sleep 1
done

umask 077
TMP="$OUT.tmp"
{
  echo "SIGNOZ_URL=$U"
  echo "SIGNOZ_API_KEY=$KEY"
  echo "SIGNOZ_OTLP_ENDPOINT=${SIGNOZ_OTLP_HTTP_ENDPOINT:-http://signoz-otel-collector:4318}"
  echo "SIGNOZ_OTLP_GRPC_ENDPOINT=${SIGNOZ_OTLP_GRPC_ENDPOINT:-http://signoz-otel-collector:4317}"
  echo "SIGNOZ_OTLP_HTTP_ENDPOINT=${SIGNOZ_OTLP_HTTP_ENDPOINT:-http://signoz-otel-collector:4318}"
  echo "SIGNOZ_ORG_ID=$ORG"
  echo "SIGNOZ_SERVICE_ACCOUNT_ID=$SA"
  echo "SIGNOZ_API_KEY_ROLE=$ROLE_NAME"
  echo "SIGNOZ_ADMIN_EMAIL=$EMAIL"
  echo "SIGNOZ_ADMIN_PASSWORD=$PASS"
} > "$TMP"
chmod 0644 "$TMP"   # test containers may run as non-root; values are throwaway test-only
mv "$TMP" "$OUT"
log "wrote $OUT (keys: $(cut -d= -f1 "$OUT" | tr '\n' ' '))"
