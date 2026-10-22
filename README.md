# FinSecure Pay — Cloud-Native Transaction Validation API

DevOps capstone: a Spring Boot transaction-validation API delivered from source code to **Amazon EKS** through a **Jenkins** pipeline with built-in security gates, plus **Prometheus/Grafana** monitoring, **Alertmanager** e-mail alerting and **CloudWatch** centralised logging.

![Architecture](docs/images/architecture.png)

| | |
|---|---|
| Application | Java 21 · Spring Boot 4.1 · Maven · JUnit 5 · Micrometer |
| Container | Docker (non-root, JRE-only Alpine, layered jar) → Docker Hub |
| Platform | Amazon EKS (eksctl), NLB `Service`, `ConfigMap`, `Secret`, HPA, PDB, probes |
| CI/CD | Jenkins (Configuration-as-Code, Docker Compose) · `Jenkinsfile` |
| DevSecOps | SonarQube quality gate · OWASP Dependency-Check (CVSS ≥ 7 blocks) · Trivy image + IaC · Gitleaks · JaCoCo coverage gate |
| Observability | kube-prometheus-stack (Prometheus, Grafana, Alertmanager) · CloudWatch Container Insights / Logs |

## API

| Method | Path | Description |
|---|---|---|
| `GET` | `/health` | Public health: `{"status":"UP","version":"1.0.0-12-ab12cd3",...}` (503 when not ready) |
| `POST` | `/validate` | Validates a transaction. `200` approved · `422` rejected (all reasons listed) · `400` malformed JSON · `401` missing `X-API-Key` |
| `GET` | `:8081/actuator/health/{liveness,readiness}` | Kubernetes probes (internal port) |
| `GET` | `:8081/actuator/prometheus` | Metrics for Prometheus (internal port) |

```bash
curl -s -X POST http://localhost:8080/validate -H 'Content-Type: application/json' -d '{
  "transactionId": "TXN-0001", "sourceAccount": "ACC12345678", "destinationAccount": "ACC87654321",
  "amount": 250.00, "currency": "USD", "timestamp": "2026-10-22T08:00:00Z" }'
# {"transactionId":"TXN-0001","status":"APPROVED","valid":true,"riskLevel":"LOW","reviewRequired":false,"errors":[],...}
```

### Validation rules

| Code | Rule |
|---|---|
| `TXN_ID_REQUIRED` / `TXN_ID_INVALID` | transactionId present, 6–64 chars `[A-Za-z0-9-]` |
| `SOURCE_/DESTINATION_ACCOUNT_REQUIRED` / `_INVALID` | account present and matches `^ACC[0-9]{8}$` (configurable) |
| `SAME_ACCOUNT` | source ≠ destination |
| `ACCOUNT_BLOCKED` | neither account is on the compliance block-list (ConfigMap) |
| `AMOUNT_REQUIRED` / `AMOUNT_NOT_POSITIVE` / `AMOUNT_PRECISION` | amount > 0 with ≤ 2 decimals |
| `AMOUNT_LIMIT_EXCEEDED` | amount ≤ single-transaction limit (default 10 000, ConfigMap) |
| `CURRENCY_REQUIRED` / `CURRENCY_UNSUPPORTED` | ISO code in supported list (USD, EUR, GBP, SGD, THB) |
| `TIMESTAMP_IN_FUTURE` / `TIMESTAMP_EXPIRED` | optional timestamp within 5 min clock skew and not older than 24 h |

Approved transactions get a `riskLevel` (LOW < 1 000 ≤ MEDIUM < 5 000 ≤ HIGH); HIGH sets `reviewRequired=true`.

## Quick start (local)

```bash
mvn clean verify                      # compile + 65+ unit tests + JaCoCo ≥80% gate
mvn spring-boot:run                   # http://localhost:8080/health
scripts/local-run.sh 1.0.0-local      # build image, run container, call /health and /validate
```

## Repository layout

```
src/                     Spring Boot application + JUnit 5 tests
Dockerfile               Runtime image (packages the tested jar)
Jenkinsfile              CI/CD pipeline (build → test → scan → push → deploy → smoke → rollback)
k8s/                     Namespace, ServiceAccount, ConfigMap, Deployment, Services, HPA, PDB (+ optional NetworkPolicy)
infra/eks/               eksctl cluster definition + least-privilege IAM policy for Jenkins
jenkins/                 Jenkins image, plugins, Configuration-as-Code, docker-compose (Jenkins + SonarQube)
monitoring/              kube-prometheus-stack values, ServiceMonitor, PrometheusRules, Grafana dashboard
scripts/                 deploy / rollback / smoke-test / traffic generator / self-healing demo / installers
demo/                    Controlled failure scenarios (failing test, vulnerable dependency, ...)
docs/                    SETUP (step-by-step), DEMO_RUNBOOK (evidence checklist), OPERATIONS (logs, alerts), DESIGN
```

## Documentation

* **[docs/SETUP.md](docs/SETUP.md)** – build everything from zero (GitHub, Docker Hub, EKS, Jenkins, monitoring)
* **[docs/DEMO_RUNBOOK.md](docs/DEMO_RUNBOOK.md)** – 12-minute demo script + screenshot checklist mapped to every test case
* **[docs/OPERATIONS.md](docs/OPERATIONS.md)** – logs, troubleshooting and alert runbooks
* **[docs/DESIGN.md](docs/DESIGN.md)** – design document: architecture, decisions, security, recovery, AI use case

> Security: no credentials are stored in this repository. Jenkins reads them from `jenkins/.env` (git-ignored) into its credential store; the application API key reaches the cluster only as a Kubernetes Secret.
