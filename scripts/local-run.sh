#!/usr/bin/env bash
# Task 2 evidence: build, run and test the container locally.
set -euo pipefail
cd "$(dirname "$0")/.."
TAG="${1:-1.0.0-local}"
mvn -B -ntp clean verify
docker build -t "finsecure-pay:${TAG}" --build-arg VERSION="$TAG" .
docker rm -f finsecure-pay-local >/dev/null 2>&1 || true
docker run -d --name finsecure-pay-local -p 8080:8080 -p 8081:8081 "finsecure-pay:${TAG}"
for _ in $(seq 1 30); do curl -fs http://localhost:8080/health && break; sleep 2; done; echo
curl -s -H 'Content-Type: application/json' -d '{"transactionId":"LOCAL-0001","sourceAccount":"ACC12345678","destinationAccount":"ACC87654321","amount":250.00,"currency":"USD"}' http://localhost:8080/validate; echo
curl -s -H 'Content-Type: application/json' -d '{"transactionId":"LOCAL-0002","sourceAccount":"ACC12345678","destinationAccount":"ACC12345678","amount":20000,"currency":"JPY"}' http://localhost:8080/validate; echo
docker ps --filter name=finsecure-pay-local
echo "Stop with: docker rm -f finsecure-pay-local"
