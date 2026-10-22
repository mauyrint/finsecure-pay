#!/usr/bin/env bash
# Usage: scripts/rollback.sh            -> undo to the previous ReplicaSet revision
#        scripts/rollback.sh <image-tag> -> redeploy a specific known-good tag
source "$(dirname "$0")/lib.sh"
cd "$(dirname "$0")/.."
TAG="${1:-}"
ensure_kubeconfig

log "Rollout history before rollback:"
kubectl -n "$NS" rollout history "$DEPLOY" | tail -n 6

if [[ -n "$TAG" ]]; then
  log "Rolling back to tag ${TAG}"
  IMAGE="${DOCKERHUB_REPO:?DOCKERHUB_REPO is required}:${TAG}" APP_VERSION="$TAG" \
    CHANGE_CAUSE="ROLLBACK to ${TAG}" "$(dirname "$0")/deploy.sh"
else
  log "Rolling back to the previous revision"
  kubectl -n "$NS" rollout undo "$DEPLOY"
  kubectl -n "$NS" rollout status "$DEPLOY" --timeout=300s
fi

log "Now running image: $(kubectl -n "$NS" get "$DEPLOY" -o jsonpath='{.spec.template.spec.containers[0].image}')"
kubectl -n "$NS" rollout history "$DEPLOY" | tail -n 6
