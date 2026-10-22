#!/usr/bin/env bash
# One-time cluster bootstrap by an ADMIN (the CI user can only edit the finsecure namespace).
#   1) eksctl create cluster -f infra/eks/cluster.yaml
#   2) scripts/bootstrap-cluster.sh
set -euo pipefail
cd "$(dirname "$0")/.."
aws eks update-kubeconfig --region "${AWS_REGION:-ap-southeast-1}" --name "${EKS_CLUSTER:-finsecure-eks}"
kubectl apply -f k8s/00-namespace.yaml
kubectl get nodes -o wide
kubectl -n kube-system get deploy metrics-server
echo "Namespace ready. Next: scripts/install-monitoring.sh, then run the Jenkins pipeline."
