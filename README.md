# FinSecure Pay — Transaction Validation API

This is my DevOps capstone project. It is a small Spring Boot API that checks payment transactions, plus everything needed to build, test, scan, deploy and monitor it automatically on **Amazon EKS**.

![Architecture](docs/images/architecture.png)

## What is in the project

| Part | Tools |
| --- | --- |
| Application | Java 21, Spring Boot 4.1, Maven, JUnit 5 |
| Container | Docker (small Alpine image, non-root user), Docker Hub |
| Kubernetes | Amazon EKS, Deployment (2 pods), Service with an AWS load balancer, ConfigMap, Secret, probes, autoscaling |
| CI/CD | Jenkins (runs in Docker on my laptop, set up as code), `Jenkinsfile` |
| Security | SonarQube, OWASP Dependency-Check, Trivy, Gitleaks, 80% test coverage gate |
| Monitoring | Prometheus, Grafana, Alertmanager (e-mail alerts), CloudWatch Logs |

## The API

| Method | Path | What it does |
| --- | --- | --- |
| `GET` | `/health` | Returns `{"status":"UP","version":"..."}`, or 503 when the app is not ready. Docker and all Kubernetes probes use it too |
| `POST` | `/validate` | Checks one transaction. `200` = approved, `422` = rejected (with every failed rule), `400` = bad JSON, `401` = missing `X-API-Key` |
| `GET` | `:8081/actuator/prometheus` | Metrics for Prometheus (only inside the cluster) |

Example:

```bash
curl -s -X POST http://localhost:8080/validate -H 'Content-Type: application/json' -d '{
  "transactionId": "TXN-0001", "sourceAccount": "ACC12345678", "destinationAccount": "ACC87654321",
  "amount": 250.00, "currency": "USD" }'
# {"transactionId":"TXN-0001","status":"APPROVED","valid":true,"riskLevel":"LOW","reviewRequired":false,"errors":[],...}
```

### Validation rules

There was no official specification, so I chose rules that a real payment system would need:

| Rule | Why |
| --- | --- |
| Transaction ID: 6–64 letters, digits or hyphens | To find a payment later and block strange input |
| Account format `ACC` + 8 digits | Catches typos and fake accounts |
| Source and destination must be different | Paying yourself is usually a mistake |
| Account not on the block list | Fraud and sanctions compliance |
| Amount above 0, at most 2 decimals | Money cannot be negative; currencies only have cents |
| Amount at most 10,000 | Limits damage from fraud or errors |
| Currency: USD, EUR, GBP, SGD or THB | We only settle these currencies |
| Timestamp not in the future and not older than 24 h | Blocks wrongly dated or replayed requests |

Approved payments of 5,000 or more get `riskLevel: HIGH` and `reviewRequired: true`. The limit, currencies and block list are in `k8s/02-configmap.yaml`, so they can change without a new build.

## Run it on your computer

```bash
mvn clean verify                      # builds the app and runs the 67 tests (fails below 80% coverage)
mvn spring-boot:run                   # then open http://localhost:8080/health
scripts/local-run.sh 1.0.0-local      # builds the Docker image, runs it and calls /health and /validate
```

## Folder guide

```
src/          application code and JUnit 5 tests
Dockerfile    container image (uses the jar that passed the tests)
Jenkinsfile   the CI/CD pipeline
k8s/          Kubernetes files: namespace, ConfigMap, Deployment, Service, autoscaler, PDB
infra/eks/    EKS cluster definition and the IAM policy for Jenkins
jenkins/      Jenkins + SonarQube setup (docker-compose, plugins, config as code)
monitoring/   Prometheus alert rules, Grafana dashboard, Alertmanager settings
scripts/      deploy, rollback, smoke test, traffic generator, pod recovery demo
demo/         ways to break the pipeline on purpose (failing test, vulnerable library)
docs/         setup guide, demo script, operations guide, design document
```

## More documents

- [docs/SETUP.md](docs/SETUP.md) – how to set everything up from zero
- [docs/DEMO_RUNBOOK.md](docs/DEMO_RUNBOOK.md) – the demo script and the list of screenshots
- [docs/OPERATIONS.md](docs/OPERATIONS.md) – how to read logs, fix problems and handle each alert
- [docs/DESIGN.md](docs/DESIGN.md) – the design document

> No passwords, keys or tokens are stored in this repository. Jenkins reads them from `jenkins/.env`, which Git ignores, and the app's API key only reaches the cluster as a Kubernetes Secret.
