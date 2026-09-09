# Runbook: CloudWatch Monitoring Suite

Operator steps to enable, verify, and roll back the CloudWatch monitoring suite for the app. The
suite is a single consolidated observability layer: an SNS notification channel, native-service
alarms (ECS, ALB, DynamoDB, Bedrock, **NAT gateway**), health monitoring for the **self-hosted
OpenSearch node on Oracle Cloud**, log-derived metrics, one dashboard, and a cost budget.

Design and rationale: `.kiro/specs/cloudwatch-monitoring/{requirements,design,tasks}.md`.
Terraform module: `infrastructure/modules/monitoring/`. App code: `backend/.../metrics/`.

---

## 0. Decisions baked in

- **Two layers, both opt-in.** The **infra layer** is pure Terraform (SNS + alarms + dashboard +
  budget), gated by `enable_monitoring` (default `false`, `count = enable ? 1 : 0`). The **app
  layer** is the backend EMF metrics + the OpenSearch health probe, gated by
  `monitoring.metrics.enabled` (default `false`), which ECS sets from the same `enable_monitoring`
  flag via the `MONITORING_METRICS_ENABLED` env var.
- **App metrics via EMF-over-logs** (namespace `RecipeAiFinder/App`). No new IAM — the backend
  already ships logs via `awslogs`, and CloudWatch extracts metrics from the EMF log lines. There
  is no `PutMetricData` call in the default path.
- **The OCI node is monitored by an app-pushed probe.** It runs on Oracle Cloud (not AWS), so
  native CloudWatch cannot reach it. A `@Scheduled(60s)` probe in the backend calls the node's
  cluster-health API and emits `OpenSearchNodeUp` / `OpenSearchClusterStatus` /
  `OpenSearchHealthProbeLatencyMs`. This is the only CloudWatch view of that node.
- **Terraform is applied by the operator, not CI.** CI (`deploy.yml`) only builds/pushes images
  and force-redeploys ECS. All monitoring infra changes go through a manual `terraform apply`.

---

## 1. Enable in dev

1. Edit `infrastructure/environments/dev.tfvars`:
   ```hcl
   enable_monitoring              = true
   monitoring_notification_email  = "you@example.com"   # a real inbox you can click
   monitoring_budget_limit_amount = "20"
   enable_xray                    = false
   ```
   > The module **fails fast** at plan time if `enable_monitoring=true` and the email is empty
   > (`terraform_data.require_email_when_enabled` precondition) — alarms must be able to notify.

2. From `infrastructure/`:
   ```
   terraform plan -var-file=environments/dev.tfvars -out=monitoring.plan
   ```
   Review. Expect ~25 resources to add on the first enable (SNS topic + subscription, ~17 alarms,
   image-gen + bedrock log filters, dashboard, budget, and the email precondition), plus the WAF
   alarm flipping its `alarm_actions` to the new SNS topic, plus the ECS task-def picking up
   `MONITORING_METRICS_ENABLED=true` (a new task-def revision — this is normal, ECS task defs are
   immutable and always "replace" on any change; it does not delete running tasks).

   > **Unrelated drift:** the dev state currently also shows Cognito `token_url` and WAF managed-rule
   > churn (incl. Bot Control ML) as pending changes. Those are pre-existing and not part of
   > monitoring. To keep an apply strictly to monitoring, scope with `-target=module.monitoring`
   > and `-target=module.waf.aws_cloudwatch_metric_alarm.waf_blocked_requests`.

3. Apply the reviewed plan:
   ```
   terraform apply monitoring.plan
   ```

## 2. Confirm the SNS subscription (one-time, manual)

AWS emails an **"AWS Notification - Subscription Confirmation"** to `monitoring_notification_email`.
Click **Confirm subscription**. Until confirmed, every alarm fires into a void. Verify:
```
aws sns list-subscriptions-by-topic \
  --topic-arn arn:aws:sns:us-east-1:<acct>:recipe-ai-dev-alarms \
  --query 'Subscriptions[].SubscriptionArn'
```
A confirmed subscription shows a real ARN (not `PendingConfirmation`).

## 3. Deploy the app layer

The infra apply sets `MONITORING_METRICS_ENABLED=true` on the task def, but the **running image
must also contain the metrics code**. Ship it via the normal path:

- Merge the backend to `main`, or run the `deploy.yml` workflow (`workflow_dispatch`, env `dev`).
- CI builds/pushes the image and force-redeploys the ECS backend service.

Confirm the new task is live and the beans activated:
```
aws logs filter-log-events --log-group-name /ecs/recipe-ai-dev-backend \
  --start-time $(( ($(date +%s) - 600) * 1000 )) \
  --filter-pattern '?"EMF metrics enabled" ?"OpenSearch health probe"'
```
Expect:
- `EMF metrics enabled — emitting to the METRICS logger under namespace RecipeAiFinder/App`
- `OpenSearch health probe active (self-hosted basic-auth node).`

## 4. Verify

1. **Custom metrics registered:**
   ```
   aws cloudwatch list-metrics --namespace RecipeAiFinder/App \
     --query 'Metrics[].MetricName' --output text | tr '\t' '\n' | sort -u
   ```
   The probe emits `OpenSearchNodeUp`, `OpenSearchClusterStatus`, `OpenSearchHealthProbeLatencyMs`
   within ~1 min. Log-filter metrics `ImageGenFinalFailures` / `BedrockRetryExhausted` register
   once their patterns match (or show as available filters). Request-scoped metrics
   (`BedrockLatencyMs`, `ImageLatencyMs`, `SearchLatencyMs`, `SseActiveEmitters`, …) appear once
   you exercise those features.

2. **Dashboard:** CloudWatch → Dashboards → `recipe-ai-dev`. Infra rows (ECS/ALB/DynamoDB/NAT/
   Bedrock/WAF) populate from live traffic; the **OpenSearch node (Oracle Cloud)** row shows
   `OpenSearchNodeUp=1`, cluster status, and probe latency.

3. **Alarms:** CloudWatch → Alarms. `opensearch-node-down` should be **OK** once `NodeUp=1`
   datapoints arrive (it uses `treat_missing_data=breaching`, so it may show ALARM in the window
   between infra-apply and app-deploy — that is expected and self-resolves).

4. **Trigger a forced image-gen failure** (optional end-to-end proof of a log-derived metric): force
   an image provider error and confirm `ImageGenFinalFailures` increments and the alarm fires an
   email.

## 5. Steady-state notes (so healthy signals aren't misread)

- **Single-node OpenSearch is YELLOW, not green.** `OpenSearchClusterStatus` reports `1` (yellow)
  in steady state because a single node cannot allocate replica shards. This is healthy. The
  `opensearch-cluster-red` alarm only fires on `< 1` (red), so yellow does not page.
- **Probe latency ~2–3s is normal.** The node is in OCI us-sanjose-1, reached from us-east-1 over
  the NAT with a TLS handshake per probe.
- **A fresh alarm may sit in INSUFFICIENT_DATA** until its first datapoint — not a fault.

## 6. Rollback

- **Disable app emission only** (stops the probe + EMF metrics, keeps infra alarms):
  set `enable_monitoring=false`? — no, that also tears down infra. To disable ONLY the app layer
  without touching infra, override the env var on a deploy (`MONITORING_METRICS_ENABLED=false`) or
  set `monitoring.metrics.enabled=false`. Neither touches request handling.
- **Full teardown of the suite:** set `enable_monitoring=false` in `dev.tfvars` and
  `terraform apply`. This removes the SNS topic, all alarms, the dashboard, the budget, and reverts
  the WAF alarm to its prior (empty) SNS wiring. The plan should show the ~25 resources destroyed
  and nothing else monitoring-related.
- Rollback is safe at any time: none of it is in the request path.

## 7. Logs Insights: querying the AUDIT logger

Audit volume is low, so ad-hoc Logs Insights queries fit better than a metric filter. Run against
`/ecs/recipe-ai-dev-backend`:

```
# All audit events, newest first
fields @timestamp, @message
| filter @logStream like /ecs/
| filter @message like /"eventType"/
| sort @timestamp desc
| limit 100
```
```
# Count audit events by type over the range
fields @message
| filter @message like /"eventType"/
| parse @message '"eventType":"*"' as eventType
| stats count(*) as n by eventType
| sort n desc
```
```
# The METRICS EMF lines (sanity-check emission)
fields @timestamp, @message
| filter @message like /"_aws"/
| sort @timestamp desc
| limit 50
```

## 8. Cost

Design estimate **~$5–15/month**, budget alert at **$20**. Drivers: ~17–18 alarms (first 10 free,
rest ~$0.10 each), ~10–14 custom metric series (first 10 free, rest ~$0.30 each), 1 dashboard
(first 3 free), and logs already billed (EMF rides that pipeline). The NAT widgets make existing
NAT data-processing cost visible but add none. The budget is scoped to the `AmazonCloudWatch`
service filter and only created when an email is set.

## 9. Prod

Same steps against `environments/prod.tfvars`. Note prod currently runs the **in-app** search
fallback (no OCI node), so the OpenSearch health probe is a no-op there and the node-health row/
alarms stay idle until/unless prod cuts over to a self-hosted node. Everything else (ECS, ALB,
DynamoDB, NAT, Bedrock, WAF) applies identically.
