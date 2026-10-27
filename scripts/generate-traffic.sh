#!/usr/bin/env bash
set -euo pipefail
BASE="${1:?base url}"; N="${2:-300}"; RATIO="${3:-0.1}"; PAR="${4:-4}"
KEY="${FINSECURE_API_KEY:-}"
if [[ -z "$KEY" ]]; then
  echo "ERROR: FINSECURE_API_KEY is not set, so every request would get 401." >&2
  echo "Run first:  export \$(grep ^FINSECURE_API_KEY= jenkins/.env)" >&2
  exit 1
fi
PCT=$(awk -v r="$RATIO" 'BEGIN{print int(r*100)}')
export BASE KEY PCT
one() {
  local i=$1 body
  if (( RANDOM % 100 < PCT )); then
    body="{\"transactionId\":\"LOAD-BAD-$i\",\"sourceAccount\":\"ACC12345678\",\"destinationAccount\":\"ACC12345678\",\"amount\":99999,\"currency\":\"JPY\"}"
  else
    amt=$(( (RANDOM % 7000) + 1 ))
    body="{\"transactionId\":\"LOAD-OK-$i\",\"sourceAccount\":\"ACC1234$(printf %04d $((RANDOM%9999)))\",\"destinationAccount\":\"ACC8765$(printf %04d $((RANDOM%9999)))\",\"amount\":${amt}.00,\"currency\":\"THB\"}"
  fi
  curl -s -o /dev/null -w '%{http_code}\n' -H 'Content-Type: application/json' ${KEY:+-H "X-API-Key: $KEY"} -d "$body" "$BASE/validate"
}
export -f one
seq 1 "$N" | xargs -P "$PAR" -I{} bash -c 'one {}; sleep 0.2' | sort | uniq -c
