#!/usr/bin/env bash
source "$(dirname "$0")/lib.sh"
EXPECTED="${1:-}"
ensure_kubeconfig
BASE="${2:-$(service_url)}"
KEY_HEADER=()
[[ -n "${FINSECURE_API_KEY:-}" ]] && KEY_HEADER=(-H "X-API-Key: ${FINSECURE_API_KEY}")

log "Smoke testing ${BASE}"
for i in $(seq 1 40); do
  if body="$(curl -fsS --max-time 5 "${BASE}/health" 2>/dev/null)"; then break; fi
  sleep 10; [[ $i == 40 ]] && { echo "health endpoint unreachable" >&2; exit 1; }
done
echo "GET /health -> ${body}"
grep -q '"status":"UP"' <<<"$body" || { echo "service not UP" >&2; exit 1; }
if [[ -n "$EXPECTED" ]]; then
  grep -q "\"version\":\"${EXPECTED}\"" <<<"$body" || { echo "expected version ${EXPECTED}" >&2; exit 1; }
fi

ok=$(curl -s -o /dev/null -w '%{http_code}' ${KEY_HEADER[@]+"${KEY_HEADER[@]}"} -H 'Content-Type: application/json' \
  -d '{"transactionId":"SMOKE-OK-1","sourceAccount":"ACC12345678","destinationAccount":"ACC87654321","amount":10.00,"currency":"USD"}' \
  "${BASE}/validate")
bad=$(curl -s -o /dev/null -w '%{http_code}' ${KEY_HEADER[@]+"${KEY_HEADER[@]}"} -H 'Content-Type: application/json' \
  -d '{"transactionId":"SMOKE-BAD-1","sourceAccount":"ACC12345678","destinationAccount":"ACC12345678","amount":-1,"currency":"XXX"}' \
  "${BASE}/validate")
echo "POST /validate valid -> ${ok} (expect 200), invalid -> ${bad} (expect 422)"
[[ "$ok" == "200" && "$bad" == "422" ]] || exit 1
log "Smoke test PASSED"
