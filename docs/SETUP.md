# Setup guide — from zero to a running pipeline

Estimated time: ~2–3 hours (most of it waiting for EKS). Commands assume macOS with Docker Desktop.

> **Cost warning:** EKS control plane (~US$0.10/h) + 2× t3.medium + NAT gateway + NLB ≈ **US$0.35–0.45/hour**.
> Create the cluster when you are ready to capture evidence and **delete it afterwards** (step 9).

## 0. Prerequisites

```bash
brew install git openjdk@21 maven awscli eksctl kubectl helm gettext jq
brew install --cask docker          # Docker Desktop (enable "Use Rosetta" on Apple Silicon for amd64 builds)
```

Accounts: GitHub, Docker Hub, AWS (with an admin IAM user/role for cluster creation), a Gmail account with 2FA (for an **App Password**), and a free **NVD API key** (https://nvd.nist.gov/developers/request-an-api-key).

## 1. Source control (Task 1)

```bash
cd finsecure-pay
git init -b main && git add -A && git commit -m "feat: FinSecure Pay validation API with tests"
gh repo create finsecure-pay --public --source . --push      # or create on github.com and `git remote add origin ...`
mvn clean verify                                              # screenshot: Tests run: 67, Failures: 0, BUILD SUCCESS
```
Make small, purposeful commits as you go (`feat:`, `test:`, `ci:`, `fix:`, `docs:`) – the rubric rewards a clear history.
Replace `CHANGE_ME` in the `Dockerfile` OCI source label with your GitHub path.

## 2. Container (Task 2)

```bash
scripts/local-run.sh 1.0.0-local                # builds jar + image, runs container, calls /health and /validate
docker images finsecure-pay
docker login -u <dockerhub-user>
# EKS nodes are amd64 and a Mac is arm64, so build the pushed image for amd64
docker buildx build --platform linux/amd64 -t docker.io/<dockerhub-user>/finsecure-pay:1.0.0 --push .   # first manual push; Jenkins pushes afterwards
```
Create a Docker Hub **access token** (Account settings → Personal access tokens, Read & Write) for Jenkins.

## 3. AWS identities

1. Create IAM user **`jenkins-deployer`** (programmatic access only). Attach an inline policy from `infra/eks/jenkins-deployer-iam-policy.json` (replace `<AWS_ACCOUNT_ID>`). Create an access key → goes into `jenkins/.env` only.
2. Edit `infra/eks/cluster.yaml` and replace `<AWS_ACCOUNT_ID>`. The access entry gives this user **edit rights in namespace `finsecure` only**.

## 4. Create the EKS cluster (Task 3)

```bash
aws configure                                   # your ADMIN credentials (not jenkins-deployer)
eksctl create cluster -f infra/eks/cluster.yaml # ~15-20 min
scripts/bootstrap-cluster.sh                    # kubeconfig + namespace (PSS restricted) + checks
```

First manual deployment (validates manifests before CI exists):
```bash
export IMAGE=docker.io/<dockerhub-user>/finsecure-pay:1.0.0 APP_VERSION=1.0.0 FINSECURE_API_KEY=$(openssl rand -hex 24)
scripts/deploy.sh
kubectl -n finsecure get deploy,rs,pods,svc,hpa,pdb -o wide
FINSECURE_API_KEY=$FINSECURE_API_KEY scripts/smoke-test.sh 1.0.0
scripts/demo-self-healing.sh                    # delete a pod -> recreated automatically
```
Keep the same `FINSECURE_API_KEY` value for `jenkins/.env`.

## 5. Monitoring, alerting, logging (Task 6)

```bash
export ALERT_EMAIL_TO=<you@gmail.com> SMTP_USERNAME=<you@gmail.com> SMTP_PASSWORD='<gmail-app-password>' \
       GRAFANA_ADMIN_PASSWORD='<strong-password>'
scripts/install-monitoring.sh
kubectl -n monitoring port-forward svc/kps-grafana 3000:80          # http://localhost:3000 -> "FinSecure Pay - Service Overview"
kubectl -n monitoring port-forward svc/kps-kube-prometheus-stack-prometheus 9090   # Status -> Targets: finsecure-pay UP
```
Logs are shipped automatically by the `amazon-cloudwatch-observability` add-on to the log group
`/aws/containerinsights/finsecure-eks/application` — see [OPERATIONS.md](OPERATIONS.md).

## 6. Jenkins + SonarQube (Tasks 4 & 5)

```bash
cd jenkins
cp .env.example .env && chmod 600 .env          # fill in every value (never commit .env)
docker compose up -d --build                    # first build ~5 min
```
1. Open **SonarQube** http://localhost:9000 (admin/admin → change password). *My Account → Security → Generate token* (type *Global analysis*). Put it in `SONAR_TOKEN` in `.env`, then `docker compose up -d` to reload Jenkins.
2. Open **Jenkins** http://localhost:8080 and log in with `JENKINS_ADMIN_ID/PASSWORD`. The job **finsecure-pay**, all credentials and the e-mail settings already exist (Configuration-as-Code). Check *Manage Jenkins → Credentials* (secrets are masked).
3. Click **Build Now** once. The first OWASP Dependency-Check run downloads the NVD database (~10–20 min with an API key); later runs use the cache in the `jenkins_home` volume.
4. Afterwards every push to `main` is picked up within 2 minutes (Poll SCM). *Optional:* expose Jenkins with `ngrok http 8080` and add a GitHub webhook to `https://<ngrok>/github-webhook/` for instant triggers.

Test the e-mail configuration: *Manage Jenkins → System → Extended E-mail Notification → Test configuration*.

## 7. Prove the pipeline (Task 4/5)

Follow [DEMO_RUNBOOK.md](DEMO_RUNBOOK.md): successful build, failed test, blocked vulnerability, remediation, new version rollout, rollback.

## 8. Troubleshooting setup

| Symptom | Fix |
|---|---|
| Jenkins fails at startup with a CasC error | `docker logs finsecure-jenkins`; a plugin renamed a field – comment out that block in `casc/jenkins.yaml` and configure it in the UI |
| `permission denied /var/run/docker.sock` | the compose file runs Jenkins as root for Docker Desktop; on Linux add the docker group instead |
| `exec format error` on EKS | image built for arm64; the pipeline builds `TARGET_PLATFORM=linux/amd64` – keep it, or use Graviton (t4g) nodes and `linux/arm64` |
| Pods never created, event `violates PodSecurity` for container `opentelemetry-auto-instrumentation-java` | CloudWatch add-on injects a tracing init container | Pod annotation `instrumentation.opentelemetry.io/inject-java: "false"` (already in `k8s/10-deployment.yaml`) |
| Deploy stage `Forbidden` | access entry ARN/namespace mismatch in `infra/eks/cluster.yaml` (`eksctl utils describe-access-entry`...) or namespace not created by bootstrap |
| Sonar stage times out | SonarQube not ready (needs ~2 GB RAM in Docker Desktop) or wrong token |
| Pods `CreateContainerConfigError` | Secret `finsecure-pay-secrets` missing – deploy via Jenkins or pass `FINSECURE_API_KEY` to `scripts/deploy.sh` |

## 9. Tear down (stop costs)

```bash
helm -n monitoring uninstall kps
kubectl delete ns finsecure monitoring          # releases the NLB
eksctl delete cluster -f infra/eks/cluster.yaml --disable-nodegroup-eviction
cd jenkins && docker compose down               # add -v to also delete Jenkins/SonarQube data
```
