#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ALERT_EMAIL_TO:?}" "${SMTP_USERNAME:?}" "${SMTP_PASSWORD:?}" "${GRAFANA_ADMIN_PASSWORD:?}"

kubectl create namespace monitoring --dry-run=client -o yaml | kubectl apply -f -
kubectl -n monitoring create secret generic alertmanager-smtp --from-literal=password="$SMTP_PASSWORD" \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl -n monitoring create secret generic grafana-admin --from-literal=admin-user=admin \
  --from-literal=admin-password="$GRAFANA_ADMIN_PASSWORD" --dry-run=client -o yaml | kubectl apply -f -

helm repo add prometheus-community https://prometheus-community.github.io/helm-charts >/dev/null 2>&1 || true
helm repo update >/dev/null
export ALERT_EMAIL_TO SMTP_USERNAME
values="$(mktemp)"; trap 'rm -f "$values"' EXIT
envsubst '${ALERT_EMAIL_TO} ${SMTP_USERNAME}' < monitoring/kube-prometheus-stack-values.yaml > "$values"
helm upgrade --install kps prometheus-community/kube-prometheus-stack -n monitoring \
  -f "$values" --wait --timeout 10m

kubectl apply -f monitoring/servicemonitor.yaml -f monitoring/prometheusrule.yaml \
              -f monitoring/grafana-dashboard-configmap.yaml
echo
echo "Grafana:      kubectl -n monitoring port-forward svc/kps-grafana 3000:80      -> http://localhost:3000"
echo "Prometheus:   kubectl -n monitoring port-forward svc/kps-kube-prometheus-stack-prometheus 9090"
echo "Alertmanager: kubectl -n monitoring port-forward svc/kps-kube-prometheus-stack-alertmanager 9093"
