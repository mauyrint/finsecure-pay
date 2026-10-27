# FinSecure Pay – Transaction Validation API

This is my DevOps capstone project. It has two parts:

1. **A small Java app** that checks if a payment is valid.
2. **The automation around it.** Every code change is built, tested, scanned for security problems, deployed to Kubernetes on AWS and monitored, without manual steps.

![How FinSecure Pay works](images/architecture.png)

## How it works, in short

1. I push code to **GitHub**.
2. **Jenkins** sees the change and runs the pipeline: build → tests → security scans → Docker image → image scan → push to **Docker Hub** → deploy to **Amazon EKS** → smoke test.
3. If any step fails (for example a test, or a library with a known vulnerability), Jenkins stops and **nothing is deployed**. I get an e-mail.
4. On EKS, **two copies (pods)** of the app run behind a public load balancer. If one pod crashes, Kubernetes starts a new one.
5. **Prometheus** collects numbers from the app, **Grafana** shows them, and **Alertmanager** sends an e-mail if too many payments are rejected. App logs go to **CloudWatch**.

## Tools I used

| Area | Tools |
| --- | --- |
| App and tests | Java 21, Spring Boot, Maven, JUnit 5 (67 tests) |
| Container | Docker, Docker Hub |
| Kubernetes | Amazon EKS (2 pods, load balancer, ConfigMap, Secret, health checks) |
| CI/CD | Jenkins with a `Jenkinsfile` |
| Security | SonarQube (code), Trivy (libraries, image, Kubernetes files), Gitleaks (secrets in Git), 80% test coverage rule |
| Monitoring | Prometheus, Grafana, Alertmanager (e-mail), CloudWatch Logs |

## The API

The app has two endpoints:

| Request | What it does |
| --- | --- |
| `GET /health` | Says if the app is running. Answer: `{"status":"UP","service":"finsecure-pay","version":"..."}` |
| `POST /validate` | Checks one payment (send it as JSON) |

Possible answers from `/validate`:

| Code | Meaning |
| --- | --- |
| `200` | Approved |
| `422` | Rejected. The answer lists **every** rule that failed, so the client can fix everything at once |
| `400` | The JSON is broken |
| `401` | The `X-API-Key` header is missing or wrong |

Example (on your own computer):

```bash
curl -s -X POST http://localhost:8080/validate \
  -H 'Content-Type: application/json' \
  -d '{"transactionId":"TXN-0001","sourceAccount":"ACC12345678","destinationAccount":"ACC87654321","amount":250.00,"currency":"USD"}'
```

### The validation rules

There was no official rule list, so I chose rules that a real payment system needs:

| Rule | Why |
| --- | --- |
| Transaction ID: 6–64 letters, digits or `-` | So a payment can be found later, and strange input is blocked |
| Account format: `ACC` + 8 digits | Catches typos and fake accounts |
| Source and destination must be different | Sending money to the same account is usually a mistake |
| Account is not on the block list | Stops accounts linked to fraud |
| Amount is above 0 with at most 2 decimals | Money can't be negative, and currencies only have cents |
| Amount is at most 10,000 | Limits the damage from fraud or mistakes |
| Currency is USD, EUR, GBP, SGD or THB | Only currencies we can handle |
| Timestamp is not in the future and not older than 24 hours | Blocks wrong or replayed requests |

Approved payments of 5,000 or more get `"riskLevel": "HIGH"` and `"reviewRequired": true`, so a person can check them.
The limit, the currencies and the block list are in `k8s/02-configmap.yaml`, so you can change them without building the app again.

## Try it on your computer

You need Java 21, Maven and Docker Desktop.

```bash
mvn clean verify          # builds the app and runs the 67 tests
scripts/local-run.sh      # builds the Docker image, starts it, and calls /health and /validate
```

When you finish, stop the container with `docker rm -f finsecure-pay-local`.

## Set up everything from zero

This part creates real resources on AWS. **It costs money** (about US$5–6 per day), so delete the cluster when you finish (step 9).

**Install the tools:**
`brew install git openjdk@21 maven awscli eksctl kubectl helm gettext`, plus Docker Desktop.

**Accounts you need:** GitHub, Docker Hub, AWS, and a Gmail account with an app password (for the alert e-mails).

1. **Build and test:** `mvn clean verify`. All 67 tests must pass.
2. **Run it in Docker:** `scripts/local-run.sh`.
3. **Push the image to Docker Hub:**
   `docker buildx build --platform linux/amd64 -t docker.io/<your-user>/finsecure-pay:<version> --push .`
   The `linux/amd64` part is needed because the AWS servers use Intel chips, and a new Mac uses ARM.
4. **AWS users:** run `aws configure` with an admin key. Create an IAM user called `jenkins-deployer` and give it the policy in `infra/eks/jenkins-deployer-iam-policy.json`. This user can only deploy the app, nothing else.
5. **Create the cluster:** `eksctl create cluster -f infra/eks/cluster.yaml` (takes about 20 minutes), then `scripts/bootstrap-cluster.sh`.
6. **First deploy:** set `IMAGE`, `APP_VERSION` and `FINSECURE_API_KEY`, then run `scripts/deploy.sh` and `scripts/smoke-test.sh <version>`.
7. **Monitoring:** set `ALERT_EMAIL_TO`, `SMTP_USERNAME`, `SMTP_PASSWORD` and `GRAFANA_ADMIN_PASSWORD`, then run `scripts/install-monitoring.sh`.
8. **Jenkins and SonarQube:** create the file `jenkins/.env` with the values below, then run `cd jenkins && docker compose up -d --build`. Open http://localhost:8080. The `finsecure-pay` job checks GitHub every 2 minutes and runs by itself.

   Values for `jenkins/.env`:
   `JENKINS_ADMIN_ID`, `JENKINS_ADMIN_PASSWORD`, `GITHUB_REPO_URL`, `GITHUB_USER`, `GITHUB_TOKEN`, `DOCKERHUB_USER`, `DOCKERHUB_TOKEN`, `DOCKERHUB_REPO`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` (of `jenkins-deployer`), `AWS_REGION`, `EKS_CLUSTER`, `SONAR_HOST_URL`, `SONAR_TOKEN`, `NVD_API_KEY` (optional), `FINSECURE_API_KEY`, `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `NOTIFY_EMAIL`
9. **Clean up:** `kubectl delete namespace finsecure monitoring`, then
   `eksctl delete cluster -f infra/eks/cluster.yaml --disable-nodegroup-eviction`.

## Logs and troubleshooting

| I want to… | Command or place |
| --- | --- |
| See live logs of both pods | `kubectl -n finsecure logs -l app.kubernetes.io/name=finsecure-pay --prefix --tail=100` |
| See why a pod crashed | `kubectl -n finsecure logs <pod-name> --previous` |
| See why a pod is not ready | `kubectl -n finsecure describe pod <pod-name>` (read "Events" at the bottom) |
| Search old logs from all pods | AWS Console → CloudWatch → Logs Insights → log group `/aws/containerinsights/finsecure-eks/application` |
| See why a pipeline failed | Jenkins → the build → Console Output (it is also attached to the failure e-mail) |

Account numbers are always hidden in the logs, like `ACC****5678`.

Example CloudWatch query that shows rejected payments:

```
fields @timestamp, kubernetes.pod_name, log_processed.message
| filter kubernetes.namespace_name = "finsecure" and log_processed.message like /transaction rejected/
| sort @timestamp desc | limit 50
```

**If the "high rejection rate" alert fires:** open Grafana → "Validation failures by rule" to see which rule jumped. If it started right after a deploy, check the last change to the ConfigMap and roll back with Jenkins (Build with Parameters → `ACTION = rollback`).

## Folders

```
src/          Java code and the JUnit tests
Dockerfile    how the Docker image is built
Jenkinsfile   the CI/CD pipeline
k8s/          Kubernetes files (Deployment, Service, ConfigMap, autoscaling)
infra/eks/    EKS cluster settings and the IAM policy for Jenkins
jenkins/      Jenkins and SonarQube setup (Docker Compose)
monitoring/   alert rules, Grafana dashboard, e-mail settings
scripts/      helper scripts: deploy, rollback, smoke test, traffic test, pod recovery test
images/       architecture diagram
```

## About passwords and keys

There are **no passwords, keys or tokens in this repository**. Jenkins reads them from `jenkins/.env`, which Git ignores. Inside Jenkins they are stored as hidden credentials, and the app's API key reaches Kubernetes only as a Secret. Gitleaks checks the Git history for leaked secrets on every build.
