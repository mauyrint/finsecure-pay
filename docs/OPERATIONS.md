# Operations guide — logs, troubleshooting and alert runbooks

## 1. Where the logs are

| Layer | Access | Notes |
|---|---|---|
| Live pod logs | `kubectl -n finsecure logs -l app.kubernetes.io/name=finsecure-pay --prefix --tail=100 -f` | All replicas, prefixed with the pod name |
| Crashed container | `kubectl -n finsecure logs <pod> --previous` | Output of the container instance before a restart |
| Kubernetes events | `kubectl -n finsecure get events --sort-by=.lastTimestamp` / `kubectl describe pod <pod>` | Probe failures, OOMKilled, image pull errors, scheduling |
| Centralised (CloudWatch) | Log group `/aws/containerinsights/finsecure-eks/application` | Shipped by Fluent Bit (amazon-cloudwatch-observability add-on); survives pod deletion |
| Control plane audit | Log group `/aws/eks/finsecure-eks/cluster` | Who changed what (api, audit, authenticator) |
| Pipeline | Jenkins console + archived `reports/` | Failure e-mail attaches the compressed console log |

The `k8s` Spring profile writes **one JSON object per line** in Elastic Common Schema (ECS): `@timestamp`, `log.level`, `message`, `log.logger`, `process.thread.name`, and `service.*`. Account numbers are always **masked** (`ACC****5678`) – no PII in logs.

Each log line has the transaction ID (`txnId=`), so you can search for one payment.

### CloudWatch Logs Insights queries

```sql
-- Rejected transactions and their reasons (last 1h)
fields @timestamp, kubernetes.pod_name, log_processed.message
| filter kubernetes.namespace_name = "finsecure" and log_processed.message like /transaction rejected/
| sort @timestamp desc | limit 50

-- Top rejection reasons
fields log_processed.message
| filter kubernetes.namespace_name = "finsecure" and log_processed.message like /reasons=/
| parse log_processed.message "reasons=*" as reasons
| stats count(*) as n by reasons | sort n desc

-- Find one transaction
fields @timestamp, kubernetes.pod_name, log_processed.message
| filter log_processed.message like /txnId=<transaction id>/

-- Errors / stack traces
fields @timestamp, kubernetes.pod_name, log_processed.message, log_processed.error.message
| filter kubernetes.namespace_name = "finsecure" and log_processed.`log.level` in ["ERROR","WARN"]
| sort @timestamp desc
```
(Fluent Bit puts the parsed JSON under `log_processed`; if your add-on version differs, run `fields @message | limit 5` first to see the structure.)

## 2. Troubleshooting flow

1. **Is the service reachable?** `curl -i http://<nlb-dns>/health` → 200 `UP`? 503 means pods are not ready.
2. **Are pods healthy?** `kubectl -n finsecure get pods -o wide` – look at READY, STATUS, RESTARTS.
3. **Why not?** `kubectl describe pod` (events at the bottom) → probe failures / OOMKilled / ImagePullBackOff / CreateContainerConfigError (missing Secret or ConfigMap key).
4. **What did the app say?** `kubectl logs` (or `--previous`), then CloudWatch for history across pods.
5. **Is it a recent change?** `kubectl -n finsecure rollout history deploy/finsecure-pay`, Jenkins build history, `git log`. Roll back with Jenkins `ACTION=rollback` or `kubectl -n finsecure rollout undo deploy/finsecure-pay`.
6. **Capacity?** Grafana CPU/memory/heap panels, `kubectl -n finsecure get hpa`, `kubectl top pods -n finsecure`.

## 3. Alert runbooks

### FinSecureHighRejectionRate
*>30% of transactions rejected for 1 min (warning).*
- Grafana → *Validation failures by rule*: which rule spikes?
- `AMOUNT_LIMIT_EXCEEDED` / `CURRENCY_UNSUPPORTED` right after a deploy → check the last ConfigMap change (`git log -p k8s/02-configmap.yaml`) and roll back.
- One rule from one client (e.g. `TXN_ID_INVALID`) → client integration bug; contact the partner, logs show masked accounts + txnId.
- Sudden flood of `ACCOUNT_BLOCKED` → possible fraud attempt; escalate to compliance.

### FinSecureReplicasUnavailable
*Available replicas < desired for 2 min (critical).*
- `kubectl -n finsecure get pods`, `describe pod` → ImagePullBackOff (bad tag → roll back), CrashLoopBackOff (logs `--previous`), Pending (node capacity: `kubectl get nodes`, node group scaling).
- If caused by the latest rollout, `scripts/rollback.sh`.

### FinSecureTargetDown
*Prometheus cannot scrape any pod (critical).* Check pods first; then the `finsecure-pay-management` Service endpoints (`kubectl -n finsecure get endpoints`), the ServiceMonitor, and the NetworkPolicy (if enabled) allowing port 8081 from `monitoring`.

### FinSecurePodRestarting
*>2 restarts in 10 min.* `kubectl describe pod` → `Last State: OOMKilled` (raise memory limit / check heap panel) or liveness failures (startup too slow → `startupProbe.failureThreshold`).

### FinSecureHighLatencyP95
*p95 `/validate` > 500 ms for 5 min.* Check CPU throttling and HPA (`kubectl get hpa`), JVM heap/GC, node pressure. Scale manually if HPA is maxed: `kubectl -n finsecure scale deploy/finsecure-pay --replicas=4` (HPA will take over again).

### FinSecureHigh5xxRate
*>2% 5xx for 3 min (critical).* Search logs for `log.level = ERROR` (stack traces are logged server-side only). Correlate with deployments; roll back if new.

### FinSecureJvmHeapHigh
*Heap > 85% for 5 min.* Look for a memory leak trend (heap panel), consider raising the container limit (JVM uses `MaxRAMPercentage=75`).

## 4. Useful commands

```bash
kubectl -n finsecure get deploy,rs,pods,svc,hpa,pdb -o wide
kubectl -n finsecure rollout status|history|undo deploy/finsecure-pay
kubectl -n finsecure top pods
kubectl -n finsecure port-forward deploy/finsecure-pay 8081:8081   # then curl localhost:8081/actuator/prometheus
kubectl -n monitoring port-forward svc/kps-grafana 3000:80
kubectl -n monitoring port-forward svc/kps-kube-prometheus-stack-alertmanager 9093
```
