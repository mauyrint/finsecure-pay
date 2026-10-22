#!/usr/bin/env bash
# Deploys IMAGE to EKS and waits for the rollout. Automatically rolls back if the rollout fails.
# Required env: IMAGE, APP_VERSION. Optional: CHANGE_CAUSE, FINSECURE_API_KEY (else Secret must exist).
source "$(dirname "$0")/lib.sh"
cd "$(dirname "$0")/.."
: "${IMAGE:?IMAGE is required}" "${APP_VERSION:?APP_VERSION is required}"
export IMAGE APP_VERSION
export CHANGE_CAUSE="${CHANGE_CAUSE:-manual deploy ${APP_VERSION}}"
export CONFIG_CHECKSUM="$( (sha256sum k8s/02-configmap.yaml 2>/dev/null || shasum -a 256 k8s/02-configmap.yaml) | cut -c1-16)"

ensure_kubeconfig
log "Deploying ${IMAGE} to namespace ${NS} (context: $(kubectl config current-context))"

# 1. Secret from the Jenkins credential - written via a 0600 temp file, never echoed or committed
if [[ -n "${FINSECURE_API_KEY:-}" ]]; then
  umask 077; tmp="$(mktemp)"; trap 'rm -f "$tmp"' EXIT
  printf 'FINSECURE_SECURITY_API_KEY=%s\n' "$FINSECURE_API_KEY" > "$tmp"
  kubectl -n "$NS" create secret generic finsecure-pay-secrets --from-env-file="$tmp" \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  log "Secret finsecure-pay-secrets applied"
fi

# 2. Supporting objects, then the rendered Deployment
kubectl apply -f k8s/01-serviceaccount.yaml -f k8s/02-configmap.yaml -f k8s/20-service.yaml \
              -f k8s/30-hpa.yaml -f k8s/31-pdb.yaml
envsubst '${IMAGE} ${APP_VERSION} ${CONFIG_CHECKSUM} ${CHANGE_CAUSE}' < k8s/10-deployment.yaml \
  | kubectl apply -f -

# 3. Wait for the rolling update; undo on failure (e.g. image cannot start, probes never pass)
if ! kubectl -n "$NS" rollout status "$DEPLOY" --timeout=300s; then
  log "Rollout FAILED - rolling back to the previous revision"
  kubectl -n "$NS" rollout undo "$DEPLOY" || true
  kubectl -n "$NS" rollout status "$DEPLOY" --timeout=300s || true
  exit 1
fi

log "Rollout complete"
kubectl -n "$NS" get pods -l app.kubernetes.io/name=finsecure-pay -o wide
kubectl -n "$NS" rollout history "$DEPLOY" | tail -n 5
