#!/usr/bin/env bash
# Proves a rolling update drops no requests: sends requests through the gateway while the order service is restarted.
# Needs: kubectl context pointing at the release, the gateway reachable on localhost:8080 and the order service on :8081
# (kubectl port-forward), and DEV_TOKEN_ENABLED=true (local clusters only). Exits non-zero if any request fails.
set -euo pipefail
DEPLOY="${DEPLOY:-deploy/orders-order-service}"
TOKEN="$(curl -fsS localhost:8081/dev/token -H 'Content-Type: application/json' -d '{"customerId":"rollout-probe"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')"
codes="$(mktemp)"

( end=$((SECONDS + 240)); while [ "$SECONDS" -lt "$end" ]; do
    curl -s -o /dev/null -m 5 -w '%{http_code}\n' localhost:8080/api/v1/products -H "Authorization: Bearer $TOKEN" >> "$codes" || echo 000 >> "$codes"
    sleep 0.15
  done ) &
loop=$!
sleep 3
echo "restarting ${DEPLOY} while requests flow..."
kubectl rollout restart "$DEPLOY" > /dev/null
kubectl rollout status "$DEPLOY" --timeout=240s
sleep 4
kill "$loop" 2>/dev/null || true
wait "$loop" 2>/dev/null || true

total=$(wc -l < "$codes" | tr -d ' ')
failed=$(grep -vc '^200$' "$codes" || true)
echo "requests during the rollout: ${total}; not 200: ${failed}"
sort "$codes" | uniq -c
[ "$total" -gt 50 ] || { echo "too few requests were sent to be meaningful" >&2; exit 1; }
[ "$failed" -eq 0 ]
