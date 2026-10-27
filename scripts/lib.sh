#!/usr/bin/env bash
set -euo pipefail
NS="${K8S_NAMESPACE:-finsecure}"
DEPLOY="deploy/finsecure-pay"
log() { printf '\033[1;34m[%s]\033[0m %s\n' "$(date +%H:%M:%S)" "$*"; }

ensure_kubeconfig() {
  if [[ -n "${AWS_ACCESS_KEY_ID:-}" && -n "${EKS_CLUSTER:-}" ]]; then
    mkdir -p "$(dirname "${KUBECONFIG:-$PWD/.kube/config}")"
    aws eks update-kubeconfig --region "${AWS_REGION:?}" --name "$EKS_CLUSTER" \
      --kubeconfig "${KUBECONFIG:-$PWD/.kube/config}" >/dev/null
    export KUBECONFIG="${KUBECONFIG:-$PWD/.kube/config}"
  fi
}

service_url() {
  local host=""
  for _ in $(seq 1 60); do
    host="$(kubectl -n "$NS" get svc finsecure-pay -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || true)"
    [[ -n "$host" ]] && break
    sleep 5
  done
  [[ -n "$host" ]] || { echo "LoadBalancer hostname not assigned" >&2; return 1; }
  echo "http://${host}"
}
