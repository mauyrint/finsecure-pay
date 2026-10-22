# FinSecure Pay — Design Document

**Automating a cloud-native DevOps pipeline for a transaction validation API**
Author: HAN · DevOps, Cloud & AI Programme — Capstone · October 2026
Repository: `https://github.com/mauyrint/finsecure-pay` · Image: `docker.io/<your-user>/finsecure-pay`

## 1. Context and goals

FinSecure Pay is a small fintech start-up that must ship a transaction validation API quickly without sacrificing safety. Manual builds and deployments were slow, inconsistent and left no audit trail. The goals of this design are:

| Goal | How it is met |
|---|---|
| Repeatable, low-touch delivery | One Jenkins pipeline from `git push` to a verified EKS rollout; infrastructure, Jenkins and monitoring are all defined as code |
| Run on managed Kubernetes in the cloud | Amazon EKS (ap-southeast-1) with a managed node group in private subnets |
| Security built in, not bolted on | Five automated checks; HIGH/CRITICAL findings, failing tests or low coverage stop the release |
| Operational visibility | Prometheus metrics (business + platform), Grafana dashboard, CloudWatch centralised logs |
| Detect and react to issues | Prometheus alert rules → Alertmanager e-mail; Jenkins success/failure e-mail |
| Recover automatically | Probes + ReplicaSet self-healing, zero-downtime rolling updates, automatic rollback on failed rollout/smoke test |

## 2. Architecture

![End-to-end architecture](images/architecture.png)

**Flow.** A developer pushes to GitHub. Jenkins (running locally in Docker Compose with Configuration-as-Code) polls the repository, builds and tests the Spring Boot service, runs quality and security gates, builds a container image, scans it, and pushes it to Docker Hub with an immutable tag `<pom-version>-<build>-<git-sha>`. The deploy stage authenticates to EKS as a least-privilege IAM user, applies the Kubernetes manifests, waits for the rolling update and runs a smoke test through the public Network Load Balancer; if either fails it rolls back. Prometheus scrapes the pods' internal management port; Grafana visualises; Alertmanager e-mails the on-call engineer. Fluent Bit ships JSON logs to CloudWatch.

**Application.** Java 21 / Spring Boot 4.1. `POST /validate` evaluates 16 business rules (identifier format, account format and block-list, same-account transfers, amount sign, precision and limit, supported currency, timestamp freshness) and returns *all* failures in one `422` response; approved transactions receive a risk level and a `reviewRequired` flag for high-value payments. `GET /health` reports readiness and the running version. The rules engine is a pure class with an injected `Clock`, which makes it fully unit-testable (67 JUnit 5 tests, JaCoCo ≥ 80% line coverage enforced). Limits, currencies and the block-list are externalised so operations can change them through a ConfigMap without a rebuild.

## 3. Key tool choices and design decisions

| Area | Choice | Rationale / trade-off |
|---|---|---|
| Build & test | Maven, JUnit 5, AssertJ, MockMvc, JaCoCo | Programme standard; parameterised tests cover rule boundaries cheaply; coverage gate prevents untested code |
| Container | Docker, JRE-only Alpine, layered jar, non-root UID 10001 | Ships the exact jar that passed the tests; small attack surface; dependency layer is cached between builds. Image built for `linux/amd64` to match EKS nodes even when Jenkins runs on Apple Silicon |
| Registry | Docker Hub with immutable tags (no `latest`) | Recommended tool; every running pod is traceable to a build and a commit. ECR would remove the external dependency (future) |
| Orchestration | Amazon EKS via `eksctl` config file | Managed control plane, native NLB + CloudWatch integration, EKS access entries for fine-grained CI permissions; the cluster is reproducible from `infra/eks/cluster.yaml` |
| Workload design | 2 replicas (HPA 2–6 on CPU), `maxUnavailable: 0`, PDB `minAvailable: 1`, topology spread across nodes/AZs | Survives a pod, node or AZ failure and node upgrades; zero-downtime deploys |
| Probes | startup + readiness + liveness on Actuator groups at port 8081 | Startup probe protects slow JVM starts from liveness kills; readiness gates traffic; management port is not exposed publicly |
| Config & secrets | ConfigMap (rules, profile) + Secret (API key); config checksum annotation | Config changes trigger a rollout automatically; secrets never in Git |
| CI/CD | Jenkins + declarative `Jenkinsfile`, JCasC, Job DSL | Recommended tool; the controller, credentials, job and e-mail settings are rebuilt from code (`docker compose up`). Poll SCM every 2 min because a laptop Jenkins is not reachable by webhooks (ngrok option documented) |
| Quality / SAST | SonarQube (self-hosted in the same compose stack) | Quality gate is polled by the scanner (`sonar.qualitygate.wait`) so no inbound webhook is needed |
| SCA | OWASP Dependency-Check, `failBuildOnCVSS=7` | Blocks any dependency with a HIGH/CRITICAL CVE; documented suppression file with expiry for risk acceptance |
| Image & IaC scan | Trivy (pinned v0.69.3, checksum-verified) | Blocks HIGH/CRITICAL fixable CVEs in OS packages and jars; scans K8s manifests and the Dockerfile for misconfigurations. Pinned because releases 0.69.4–0.69.6 were compromised in March 2026 — a real supply-chain lesson |
| Secret scanning | Gitleaks over full git history | Prevents credentials from ever reaching the registry/cluster |
| Monitoring | kube-prometheus-stack (Prometheus, Grafana, Alertmanager, kube-state-metrics) | De-facto standard; ServiceMonitor/PrometheusRule CRDs keep monitoring as code next to the app |
| Logging | CloudWatch via `amazon-cloudwatch-observability` add-on (Fluent Bit) | Cloud-native, no Elasticsearch cluster to run; ECS-JSON logs are queryable in Logs Insights. Trade-off: vendor lock-in vs. EFK |
| Notification | Jenkins Email Extension + Alertmanager e-mail (Gmail SMTP, app password) | Meets the requirement with no extra services; Slack/PagerDuty receivers are a config change |

**Pipeline stages.** Checkout → Build → Unit Test → Package (coverage gate) → Security Checks in parallel (SonarQube, Dependency-Check, Gitleaks, Trivy IaC) → Container Build → Image Scan (Trivy gate) → Push → Deploy to EKS → Smoke Test. A parameter `ACTION=rollback` skips the build and restores the previous revision or a named tag. Because every stage after a failure is skipped, a failing test or a blocked finding can never reach Docker Hub or the cluster.

## 4. Security considerations

- **Shift-left gates:** tests, coverage, SAST quality gate, SCA (CVSS ≥ 7), secret scan, IaC scan and image scan all fail the build. Demonstrated by introducing `commons-text 1.9` (CVE-2022-42889, CVSS 9.8): Dependency-Check blocked the release; removing it produced a green build.
- **Secrets management:** no secrets in the repository (`.gitignore`, Gitleaks). Jenkins credentials are loaded from a git-ignored `.env` into the encrypted Jenkins credential store and masked in logs; the app API key reaches Kubernetes only as a Secret created at deploy time (written via a 0600 temp file, never echoed). EKS envelope-encrypts Secrets at rest with KMS. SMTP and Grafana passwords live in Secrets referenced by file, not in Helm values. *Production next step:* AWS Secrets Manager + External Secrets Operator, and Jenkins secrets from Vault.
- **Least privilege:** the `jenkins-deployer` IAM user can only `eks:DescribeCluster` and holds `AmazonEKSEditPolicy` scoped to the `finsecure` namespace via EKS access entries; namespace creation and monitoring installation are admin-only bootstrap steps.
- **Workload hardening:** Pod Security Standard *restricted* enforced on the namespace; non-root, read-only root filesystem, all capabilities dropped, seccomp `RuntimeDefault`, no service-account token; worker nodes in private subnets; actuator/metrics only on an internal port; optional default-deny NetworkPolicy.
- **Application:** constant-time API-key comparison, strict input validation, no stack traces to clients, masked account numbers in logs, request correlation IDs, EKS control-plane audit logs.
- **Accepted risk (lab only):** the local Jenkins container runs as root with the Docker socket mounted. In production Jenkins would use ephemeral Kubernetes agents with rootless BuildKit/Kaniko.

## 5. Recovery and resilience considerations

| Failure | Mechanism | Evidence |
|---|---|---|
| Pod crash / deletion | ReplicaSet recreates the pod; readiness keeps traffic on healthy pods | `scripts/demo-self-healing.sh` – replacement Ready in ~30–40 s, no failed requests |
| Hung JVM | Liveness probe restarts the container; `FinSecurePodRestarting` alert | Probe config in Deployment |
| Bad release (won't start) | `maxUnavailable: 0` keeps old pods serving; `rollout status` times out → automatic `rollout undo` | `scripts/deploy.sh` |
| Bad release (starts but misbehaves) | Post-deploy smoke test (health, version, valid/invalid validations) → automatic rollback | Smoke Test stage |
| Human-initiated rollback | Jenkins `ACTION=rollback` (previous revision or named tag); 10 revisions retained | Rollback build + `rollout history` |
| Node / AZ loss, upgrades | ≥2 replicas spread across nodes/AZs, PDB, managed node group replacement | Manifests |
| Load spikes | HPA 2–6 replicas on CPU; latency and heap alerts | HPA + PrometheusRules |
| Cluster loss | Everything is code: `eksctl create` + `bootstrap` + `install-monitoring` + one pipeline run rebuilds the service (RTO ≈ 30 min); the service is stateless so RPO = 0 | `docs/SETUP.md` |

## 6. AI use case — AI-assisted incident triage ("AI on-call assistant")

**Problem.** When an alert fires (e.g. `FinSecureHighRejectionRate`), the on-call engineer must jump between Grafana, Prometheus, CloudWatch, Jenkins and Git to work out *what changed and why*. For a small team, this context-gathering dominates mean time to resolution (MTTR), especially out of hours. The signals are heterogeneous (metrics, unstructured logs, diffs, runbooks) and the reasoning is "correlate and explain" rather than a fixed threshold — a task large language models are good at, while deterministic rules are brittle and anomaly-only ML gives no explanation.

**How it works.**
1. Alertmanager sends the firing alert to a webhook receiver (a small Lambda / Kubernetes job).
2. A *context collector* gathers a bounded evidence pack: the alert and its labels; 30 minutes of the relevant PromQL series (rejection rate by rule, latency, replicas, restarts); the top 200 recent WARN/ERROR log lines from CloudWatch Logs Insights (already PII-masked); `kubectl get events`; the last Jenkins builds with their git diff (especially `k8s/02-configmap.yaml`); and the matching runbook section from `docs/OPERATIONS.md`.
3. A prompt asks an LLM (Amazon Bedrock, so data stays in the AWS account/region) to return structured JSON: *summary, most-likely cause with cited evidence, confidence, suggested runbook steps, and whether a rollback is recommended*.
4. The result is appended to the alert e-mail / incident ticket. Proposed actions (e.g. "run Jenkins `ACTION=rollback` to tag X") are **suggestions requiring human approval**.

*Example:* rejection rate jumps to 65% at 14:02; failures are 95% `AMOUNT_LIMIT_EXCEEDED`; Jenkins build #42 at 13:58 changed `FINSECURE_VALIDATION_MAX_AMOUNT` from 10000 to 1000 → "Likely cause: ConfigMap change in build #42 (confidence high). Recommend rollback to 1.0.0-41-ab12cd3."

**Expected benefits.** Faster triage (target: 30–50% lower MTTR for change-related incidents), consistent first response out of hours, less alert fatigue through summarisation, an auto-generated incident timeline that seeds post-mortems, and knowledge capture for a small team.

**Limitations, risks and governance.**
- *Hallucination / wrong root cause:* require cited evidence (query + values, log lines, commit id) for every claim, show confidence, never auto-execute; the engineer remains accountable.
- *Data protection:* financial logs are sensitive. Only masked logs are sent; Bedrock with no data retention for training, VPC endpoints, IAM-scoped access, and an audit log of every prompt/response. Payload size is capped.
- *Prompt injection:* log content is untrusted input; it is passed as quoted data, the assistant has read-only credentials, and any action path is human-gated.
- *Cost and latency:* only invoked for firing alerts, with token budgets; falls back to the plain alert if the model is unavailable.
- *Evaluation:* replay past incidents (and the demo scenarios) to measure accuracy before trusting it; review monthly.

**Integration with the current solution.** It needs no change to the application: add a `webhook_configs` receiver to Alertmanager next to the existing e-mail receiver; give the collector read-only Prometheus, CloudWatch Logs and Jenkins API access; reuse `docs/OPERATIONS.md` as its runbook knowledge base. A second, low-risk extension is AI summarisation of failed Jenkins builds (test failures or scan reports) appended to the failure e-mail.

## 7. Results summary

| Core test case | Expected result |
|---|---|
| Unit tests | 67 tests pass; coverage gate ≥ 80% |
| Controlled failing test | Unit Test stage fails; Package → Deploy skipped; failure e-mail |
| Container `/health` | `{"status":"UP",...}` locally and via the NLB |
| ≥2 pods Ready | 2/2 Ready (HPA min 2) |
| New version | Rolling update completes; `/health` shows new version |
| Pod deletion | Pod recreated automatically, no downtime |
| Critical/high finding | Dependency-Check (CVE-2022-42889) blocks release; remediated build green |
| Monitoring condition | `FinSecureHighRejectionRate` fires; Alertmanager e-mail received; resolves |

## 8. Lessons learned and future improvements

**Lessons learned.** (1) Making the jar the single tested artifact — and the image a thin wrapper around it — removed "works in CI but not in the container" issues. (2) Separate management and traffic ports simplify both security and probes. (3) Security gates need a documented exception process (suppression file with expiry), otherwise teams disable them. (4) Pin and verify your security tools too — the Trivy compromise showed that the scanner itself is part of the supply chain. (5) Business metrics (rejections by rule) are far more useful for alerting than CPU alone. (6) Cost discipline: script the teardown.

**Future improvements.** GitOps with Argo CD (pull-based deploys, drift detection) and progressive delivery (Argo Rollouts canary with automated analysis on the rejection-rate metric); ephemeral Jenkins agents on Kubernetes or GitHub Actions with OIDC (no long-lived AWS keys); Amazon ECR with image signing (cosign) and admission policy (Kyverno) to run only signed, scanned images; Terraform for VPC/EKS/IAM; External Secrets + AWS Secrets Manager; distributed tracing with OpenTelemetry; SLOs with error-budget alerts; multi-environment promotion (dev → staging → prod) with manual approval for production; and the AI incident assistant described above.
