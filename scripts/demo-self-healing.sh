#!/usr/bin/env bash
source "$(dirname "$0")/lib.sh"
log "Pods before:"; kubectl -n "$NS" get pods -l app.kubernetes.io/name=finsecure-pay -o wide
VICTIM="$(kubectl -n "$NS" get pods -l app.kubernetes.io/name=finsecure-pay -o jsonpath='{.items[0].metadata.name}')"
log "Deleting pod ${VICTIM}"
kubectl -n "$NS" delete pod "$VICTIM" --wait=false
sleep 2; log "Immediately after delete:"; kubectl -n "$NS" get pods -l app.kubernetes.io/name=finsecure-pay
kubectl -n "$NS" wait --for=condition=Ready pod -l app.kubernetes.io/name=finsecure-pay --timeout=180s
log "Recovered:"; kubectl -n "$NS" get pods -l app.kubernetes.io/name=finsecure-pay -o wide
kubectl -n "$NS" get events --sort-by=.lastTimestamp | grep -Ei 'killing|created|started|scheduled' | tail -n 8
