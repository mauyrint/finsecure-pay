# Controlled failure demos

| Demo | How to trigger | Expected result | How to revert |
|---|---|---|---|
| Failing unit test | Copy `ControlledFailureTest.java.txt` into `src/test/java/com/finsecure/pay/ControlledFailureTest.java`, commit, push | **Unit Test** stage red; Package → Deploy skipped; failure e-mail | Delete the file, commit, push |
| Vulnerable dependency (CRITICAL) | Uncomment the `commons-text` **1.9** block in `pom.xml` (CVE-2022-42889, CVSS 9.8), commit, push | **SCA: Dependencies** fails (OWASP Dependency-Check `failBuildOnCVSS=7`, or Trivy when no NVD key); nothing pushed or deployed | Re-comment / delete the dependency (or upgrade to ≥ 1.10.0), commit, push → green build |
| Vulnerable base image (HIGH) | In `Dockerfile` set `ARG JAVA_IMAGE=eclipse-temurin:21.0.1_12-jre-alpine` (old, unpatched) and remove `apk upgrade` | **Image Scan: Trivy** fails on HIGH/CRITICAL OS CVEs | Restore the Dockerfile |
| Failed rollout → auto rollback | `kubectl -n finsecure set image deploy/finsecure-pay finsecure-pay=docker.io/<user>/finsecure-pay:does-not-exist` (or deploy a build whose probes fail) | Rollout stalls; `scripts/deploy.sh` / Smoke Test runs `rollout undo` | automatic |
| Pod self-healing | `scripts/demo-self-healing.sh` | Deleted pod is recreated and becomes Ready | automatic |
| Alert | `scripts/generate-traffic.sh http://<elb> 1500 0.8` for ~3 min | `FinSecureHighRejectionRate` → Firing; e-mail from Alertmanager | stop the script; alert resolves (resolved e-mail) |
