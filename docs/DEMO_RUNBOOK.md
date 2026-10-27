# Demo runbook & evidence checklist

Use this twice: (1) while building, to capture screenshots for the slides; (2) as the script for the 10–15 minute live demo.
Tip: before the demo, open tabs for GitHub, Jenkins, Docker Hub, Grafana, Prometheus Alerts, Alertmanager, Gmail and CloudWatch Logs Insights, plus a terminal with `export URL=http://<nlb-dns>` and `FINSECURE_API_KEY`.

## Evidence checklist (screenshot file names map to slide placeholders)

Save every screenshot in `docs/images/screenshots/` with the exact file name below.

| # | Task | Evidence | How | Screenshot |
|---|---|---|---|---|
| 1 | T1 | GitHub repo + commit history | GitHub → *Commits* | `01-github-commits.png` |
| 2 | T1 | Unit tests pass | `mvn clean verify` (Tests run: 67, Failures: 0) or Jenkins *Tests* tab | `02-unit-tests.png` |
| 3 | T1 | Coverage | Jenkins *JaCoCo Coverage* / `target/site/jacoco/index.html` | `03-coverage.png` |
| 4 | T2 | Image built + versioned | `docker images finsecure-pay` | `04-docker-images.png` |
| 5 | T2 | Container runs, `/health` UP | `scripts/local-run.sh` output | `05-container-health.png` |
| 6 | T2 | Image in registry | Docker Hub → *Tags* | `06-dockerhub-tags.png` |
| 7a | T3 | EKS cluster + 2 nodes Ready | `eksctl get cluster` and `kubectl get nodes` | `07a-eks-nodes.png` |
| 7 | T3 | ≥2 pods Ready + objects | `kubectl -n finsecure get deploy,rs,pods,svc,hpa,pdb -o wide` | `07-k8s-resources.png` |
| 8 | T3 | Probes + ConfigMap | `kubectl -n finsecure describe deploy finsecure-pay` (Liveness/Readiness lines, envFrom) | `08-probes-config.png` |
| 9 | T3 | External access | browser/curl `http://<nlb-dns>/health` | `09-external-access.png` |
| 10 | T3 | Self-healing | `scripts/demo-self-healing.sh` | `10-self-healing.png` |
| 11 | T4 | Pipeline green, all stages | Jenkins *Stage View* / *Pipeline Graph* | `11-pipeline-success.png` |
| 12 | T4 | Failed test blocks deploy | build with `demo/ControlledFailureTest` – Unit Test red, later stages skipped | `12-pipeline-test-failure.png` |
| 13 | T4 | Rollout of new version | `kubectl rollout status`, `/health` shows new version | `13-rollout.png` |
| 14 | T4 | Rollback | Build with Parameters → `ACTION=rollback`; `kubectl rollout history` | `14-rollback.png` |
| 15 | T5 | Scan reports | Sonar dashboard, Dependency-Check report, Trivy HTML report | `15-sonar.png`, `15-dependency-check.png`, `15-trivy.png` |
| 16 | T5 | Security gate blocks release | build #4: Image Scan red (Tomcat/Jackson CVEs) | `16-security-gate-blocked.png` |
| 17 | T5 | Remediation → green | next build after removing the dependency | `17-remediated.png` |
| 18 | T5 | Secrets handling | Jenkins *Credentials* page (masked), `kubectl -n finsecure get secret` (no values), `.gitignore` | `18-secrets.png` |
| 19 | T6 | Dashboard with live metrics | Grafana dashboard during traffic | `19-grafana.png` |
| 20 | T6 | Alert rules + firing | Prometheus *Alerts* (FinSecureHighRejectionRate *Firing*) | `20-alert-firing.png` |
| 21 | T6 | Notification received | Gmail: Alertmanager e-mail + Jenkins success/failure e-mails | `21-email-alert.png`, `21-email-jenkins.png` |
| 22 | T6 | Logs for troubleshooting | CloudWatch Logs Insights query / `kubectl logs` | `22-logs.png` |

## Scenario scripts

### A. Happy path – new version rollout (T4)
1. Change something visible, e.g. in `k8s/02-configmap.yaml` set `FINSECURE_VALIDATION_MAX_AMOUNT: "15000.00"` **or** bump `<version>` in `pom.xml` to `1.1.0`.
2. `git commit -am "feat: raise single-transaction limit to 15k" && git push`
3. Jenkins starts within 2 min. Show stage view: Checkout → … → Deploy → Smoke Test all green.
4. Terminal: `kubectl -n finsecure rollout history deploy/finsecure-pay` and `curl -s $URL/health` → new `version`.
5. Show the success e-mail.

### B. Failed test blocks deployment (T4, test case 2)
```bash
cp demo/ControlledFailureTest.java.txt src/test/java/com/finsecure/pay/ControlledFailureTest.java
git add -A && git commit -m "demo: controlled failing test" && git push
```
Show: *Unit Test* red, test report lists `amountAboveLimitMustBeApproved_intentionallyWrong`, Package…Deploy **skipped**, running pods still on the old version (`curl $URL/health`), failure e-mail with log attached.
Revert: `git rm src/test/java/com/finsecure/pay/ControlledFailureTest.java && git commit -m "demo: remove failing test" && git push`.

### C. Security gate (T5, test case 7)
1. In `pom.xml` add the `commons-text` **1.9** dependency (see demo/README.md) → commit "demo: add vulnerable dependency" → push.
2. Show *SCA: OWASP Dependency-Check* red: `CVE-2022-42889` (CVSS 9.8) — "One or more dependencies were identified with vulnerabilities that have a CVSS score greater than or equal to '7.0'". Push/Deploy skipped.
3. Remediate: delete it again (or upgrade to the latest 1.x release, ≥ 1.10.0), commit "fix: remove vulnerable commons-text (CVE-2022-42889)", push → green build. Show the Dependency-Check trend graph.

### D. Rollback to known-good (T4)
* Automatic: a failing rollout or smoke test triggers `kubectl rollout undo` (see `scripts/deploy.sh`, Smoke Test stage).
* Manual: Jenkins → *Build with Parameters* → `ACTION=rollback`, `ROLLBACK_TAG=<previous tag>` (or blank for previous revision).
  Show the console (`Rolling back to …`), `kubectl rollout history`, and `/health` version.

### E. Self-healing (T3, test case 6)
`scripts/demo-self-healing.sh` – a pod is deleted, the ReplicaSet immediately creates a replacement, it becomes Ready; service stays available (`while true; do curl -s -o /dev/null -w '%{http_code} ' $URL/health; sleep 0.5; done` in another terminal shows only 200s).

### F. Alert + notification (T6, test case 8)
```bash
scripts/generate-traffic.sh $URL 1500 0.8 4      # ~80% invalid transactions for ~3-4 minutes
```
Grafana: *Rejection rate* stat turns red, *Validation failures by rule* shows SAME_ACCOUNT / AMOUNT_LIMIT_EXCEEDED / CURRENCY_UNSUPPORTED.
Prometheus *Alerts*: `FinSecureHighRejectionRate` Pending → **Firing** after 1 min. Alertmanager sends e-mail "[FIRING] FinSecureHighRejectionRate (warning)". Stop traffic → "[RESOLVED]" e-mail.

## Suggested 12-minute demo flow

| Time | Segment |
|---|---|
| 0:00–1:00 | Business problem + architecture diagram |
| 1:00–2:30 | Code tour: validation rules, tests, `mvn verify`, `/validate` approved vs rejected |
| 2:30–3:30 | Dockerfile decisions, Docker Hub tags |
| 3:30–5:00 | EKS: pods/probes/ConfigMap/Secret, external URL, delete a pod (E) |
| 5:00–7:30 | Push a change → pipeline runs (A). While it runs, explain stages and security gates; show previous blocked builds (B, C) |
| 7:30–8:30 | Rollout complete → new version; rollback (D) |
| 8:30–10:30 | Grafana dashboard, start traffic → alert fires → e-mail (F); CloudWatch log query for a rejected transaction |
| 10:30–12:00 | AI incident assistant proposal, lessons learned, future improvements |
