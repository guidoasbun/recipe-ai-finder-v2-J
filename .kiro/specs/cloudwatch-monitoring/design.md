# Design — CloudWatch Monitoring Suite

## 1. Decision: CloudWatch over Datadog (cost)

| Dimension | CloudWatch (chosen) | Datadog |
| --- | --- | --- |
| Model | Usage-based, permanent free tier | Per-host + per-product, stacks |
| Est. monthly (this app) | ~$5–15, much free-tier | ~$180–200 (per-task infra + APM) |
| Consolidation | One console; dashboard/service-map assembled by us | Unified out-of-the-box |
| Vendor/setup | Native, already in stack | New vendor, agents, API keys |
| Fit | All-AWS, low-traffic, cost-disciplined project | Wins at multi-cloud / many-service scale |

Datadog's per-task infra ($~15) + APM ($~31) fees are fixed regardless of traffic, which
dominates the bill for a low-traffic app. CloudWatch's usage model + free tier keeps this app near
zero. Rates are 2026 third-party summaries; confirm against a real quote and us-east-1 before spend.

**Accepted tradeoff:** CloudWatch's single pane is *assembled* (this spec) rather than turnkey.

> **One caveat CloudWatch does not solve on its own:** the live search backend runs on **Oracle
> Cloud**, not AWS. Native CloudWatch cannot reach a non-AWS host, so the OCI node is monitored by
> having the app push custom metrics (see §5.3). This is a deliberate seam, not a CloudWatch
> limitation Datadog would have avoided cheaply — Datadog would need an agent on that box too.

## 2. Current state (grounding — verified against the codebase)

- **CloudWatch today:** `aws_cloudwatch_log_group.backend` / `.frontend`
  (`/ecs/recipe-ai-<env>-backend|frontend`, `retention_in_days = 30`) wired via awslogs; one
  alarm `aws_cloudwatch_metric_alarm.waf_blocked_requests` (`AWS/WAFV2 BlockedRequests`, Sum,
  period 300, threshold 500 dev / 1000 prod). **No SNS topic is created** — the alarm's
  `alarm_actions` is empty because `waf_alarm_sns_topic_arn = ""` in both tfvars. No dashboards,
  no metric filters, no X-Ray, no sidecar.
- **The live search backend is the self-hosted OCI node, NOT AWS OpenSearch Serverless.** In
  `dev.tfvars`: `enable_opensearch=false` (Serverless collection deleted for cost),
  `enable_oci_opensearch=true`, `catalog_search_backend="opensearch"`, `opensearch_auth="basic"`,
  `opensearch_tls_verify=false`. The endpoint auto-resolves (root `main.tf`) to
  `module.oci_opensearch.endpoint` = `https://<oci_ip>:9200`. The node is a single-node
  OpenSearch 2.17.1 Docker container on an OCI Ampere A1 VM (Oracle Linux 9, 2 OCPU / 24 GB),
  security-plugin ON (self-signed TLS + basic auth), port 9200 IP-locked to the operator IP and
  the AWS NAT EIP. **Prod uses the in-app fallback** (no OCI node, no collection). **No monitoring
  agent, no node_exporter, no metrics export runs on the OCI box.**
- **Networking:** a **single NAT gateway** (`aws_nat_gateway.main`, single-AZ) with one Elastic IP
  (`aws_eip.nat`) is the entire egress path for the two private subnets. Every outbound call —
  Bedrock, DynamoDB, S3, ECR, Secrets Manager, Cognito, the image provider HTTP APIs, and the OCI
  OpenSearch node — leaves through it. Networking module has **zero** CloudWatch resources today.
  New `nat_gateway_id` output needed (only `nat_gateway_public_ip` exists).
- **DynamoDB (all PAY_PER_REQUEST):** `users`, `recipes` (+`userId-index` GSI), `catalog`,
  `consent`, `audit-log` (+GSI, TTL), and `catalog-full` (only when `enable_catalog_full=true`,
  true in dev). Throttling is the meaningful stress signal on on-demand tables.
- **App instrumentation today:** none. **Spring Boot 4.0.5 / Java 21 (Maven); no Actuator, no
  Micrometer, no `PutMetricData`, no logback JSON config.** The one structured-JSON path is the
  `AUDIT` logger in `AuditService` (Jackson → `AUDIT_LOG.info(json)`). Latency is measured ad-hoc:
  `generationMs` in `RecipeController.generateRecipes` (reused as `textGenerationMs`), and
  `generationMs` inside `ImageGenerationService.generateAndUploadImage` (reused as
  `imageGenerationMs`), both aggregated in-app by `StatsService`. `GET /api/health` returns a
  static `{"status":"UP"}` and probes nothing.
- **Retry/failure log lines (verified):**
  - `AsyncImageService` final failure (after the 3-attempt loop) logs a WARN with the literal
    prefix **`Image generation failed after`** — a clean metric-filter target.
  - `BedrockService` logs only a per-attempt WARN (`Recipe generation attempt {}/{} failed`) and
    then `throw`s with **no dedicated retry-exhaustion log line**. (The prior spec assumed one
    existed; it does not — see §6.)
- **Conventions:** modules = `main.tf`/`variables.tf`/`outputs.tf`; `enable_* = bool default false`
  with `count = enable ? 1 : 0` (canonical in `modules/opensearch`, `local.enabled`); budgets gated
  on `budget_notification_email != ""`; Terraform `>= 1.7`; AWS provider `~> 5.0`; per-env tfvars.
- **IAM:** task role `recipe-ai-ecs-task-role`, single `aws_iam_policy.task_policy` JSON
  (`modules/iam`) with statements for bedrock / dynamodb / s3 / ssm / `aoss:APIAccessAll` /
  cognito — the exact place to append `cloudwatch:PutMetricData` (see §5.3, §8). Task defs are
  single-container (no sidecar).

## 3. Architecture

Three layers, in ship order. The first is pure Terraform and works with zero app changes.

```
┌───────────────────────── Layer 1 · Infra (pure Terraform, no app change) ──────────────────────┐
│  SNS topic + email sub ──▶ alarm actions                                                        │
│  Native-metric alarms: ECS CPU/mem · ALB 5XX/latency/healthyhosts · DynamoDB throttles (6 tbls) │
│                         · Bedrock errors · NAT gateway drops/ports/connections                  │
│                         · (WAF alarm rewired to this SNS)                                       │
│  Log metric filters: image-gen final-failure (from existing logs)                               │
│  Dashboard (templated JSON): all of the above on one page                                       │
│  Budget (COST, scoped CloudWatch [+X-Ray]) when email provided                                  │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
┌────────────────── Layer 2 · App metrics + OCI-node health (backend, opt-in, non-blocking) ─────┐
│  RecipeAiFinder/App namespace: bedrock latency/errors, image latency/retries/failures,          │
│  catalog search latency+mode+embed-fallback, SSE emitter counts/broadcast failures              │
│  OCI OpenSearch node: scheduled health probe → OpenSearchNodeUp / ClusterStatus / ProbeLatency  │
│  (the ONLY way to see the Oracle-hosted node from CloudWatch)                                    │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
┌────────────────── Layer 3 · (optional, later) X-Ray tracing ──────────────────────────────────┐
│  enable_xray flag + ADOT sidecar + task-role xray perms + Spring instrumentation                │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
```

Layer 1 works with zero app changes (log-filter metrics + native AWS service metrics), so it ships
first. Layer 2 adds signals AWS metrics can't see — including the **only** view of the OCI node.

## 4. Terraform module: `infrastructure/modules/monitoring/`

Files: `main.tf`, `variables.tf`, `outputs.tf`, `dashboard.tf.json.tftpl` (templated body).

```hcl
locals { enabled = var.enable_monitoring }

# fail-fast: notifications need an email (mirror opensearch precondition)
resource "terraform_data" "require_email_when_enabled" {
  count = local.enabled ? 1 : 0
  lifecycle {
    precondition {
      condition     = var.notification_email != ""
      error_message = "enable_monitoring=true requires monitoring_notification_email (alarms must be able to notify)."
    }
  }
}

resource "aws_sns_topic" "alarms"             { count = local.enabled ? 1 : 0 ... }
resource "aws_sns_topic_subscription" "email" {
  count = local.enabled ? 1 : 0
  topic_arn = aws_sns_topic.alarms[0].arn
  protocol  = "email"
  endpoint  = var.notification_email
}

# ── Native-service alarms (Layer 1) ───────────────────────────────────────────
resource "aws_cloudwatch_metric_alarm" "ecs_cpu"    { count = local.enabled ? 1 : 0 ... }
# ... ecs_memory, alb_5xx, alb_latency, alb_unhealthy_hosts,
#     ddb_throttle (for_each over the 5–6 table names),
#     bedrock_errors

# NAT gateway — the single egress chokepoint (Req 3)
resource "aws_cloudwatch_metric_alarm" "nat_port_alloc_errors" {
  count       = local.enabled ? 1 : 0
  namespace   = "AWS/NATGateway"
  metric_name = "ErrorPortAllocation"
  dimensions  = { NatGatewayId = var.nat_gateway_id }
  ...
}
# ... nat_packets_dropped, nat_active_connections (thresholds via vars)

# OpenSearch node health alarms — fed by app-pushed custom metrics (Req 4)
resource "aws_cloudwatch_metric_alarm" "opensearch_node_down" {
  count               = local.enabled && var.enable_opensearch_node_alarms ? 1 : 0
  namespace           = "RecipeAiFinder/App"
  metric_name         = "OpenSearchNodeUp"
  comparison_operator = "LessThanThreshold"
  threshold           = 1
  treat_missing_data  = "breaching"   # missing data == node not reporting == down
  ...
}
# ... opensearch_cluster_red (OpenSearchClusterStatus < 1)

# Legacy/optional: AWS OpenSearch Serverless (only if it is ever re-enabled)
resource "aws_cloudwatch_metric_alarm" "opensearch_serverless_ocu" {
  count = local.enabled && var.enable_opensearch ? 1 : 0 ...
}

resource "aws_cloudwatch_log_metric_filter" "image_gen_failures" { count = local.enabled ? 1 : 0 ... }

resource "aws_cloudwatch_dashboard" "main" {
  count          = local.enabled ? 1 : 0
  dashboard_name = "${var.project_name}-${var.environment}"
  dashboard_body = templatefile("${path.module}/dashboard.tf.json.tftpl", {
    ...names, region, nat_gateway_id, enable_opensearch, enable_app_metrics
  })
}

resource "aws_budgets_budget" "monitoring" {
  count = local.enabled && var.notification_email != "" ? 1 : 0
  cost_filter { name = "Service" values = ["AmazonCloudWatch"] } # + "AWS X-Ray" when enable_xray
  ...
}
```

Every alarm uses `alarm_actions = [aws_sns_topic.alarms[0].arn]`, mostly `GreaterThanThreshold`
(the node-health alarms invert to `LessThanThreshold`), tags `{ Name, Environment, Project }` —
same shape as the WAF alarm.

### 4.1 Rewiring the existing WAF alarm
Root `main.tf` currently passes `alarm_sns_topic_arn = var.waf_alarm_sns_topic_arn` (`""`). Change
the wiring so that when monitoring is enabled it passes `module.monitoring.sns_topic_arn`,
otherwise the existing var. This gives the already-built WAF alarm a real destination with no WAF
module change.

### 4.2 Root wiring & tfvars
`main.tf` adds `module "monitoring"` receiving: cluster/service names (ecs), ALB ARN suffix + TG
ARN suffix (alb), the 5–6 DynamoDB table names, **`nat_gateway_id`** (new networking output),
`enable_opensearch`, `enable_app_metrics` (mirrors `monitoring.metrics.enabled`),
`enable_monitoring`, `notification_email`, thresholds. New tfvars keys (default off):
`enable_monitoring=false`, `monitoring_notification_email=""`, `enable_xray=false`, plus threshold
overrides. Providers/versions unchanged (`>=1.7`, aws `~>5.0`).

### 4.3 New networking output
`modules/networking/outputs.tf` gains `nat_gateway_id = aws_nat_gateway.main.id` (additive,
alongside the existing `nat_gateway_public_ip`). This is the only change outside the new module +
root wiring + IAM.

## 5. App layer — mechanism choice

### 5.1 Options for request-scoped metrics
| Option | Pros | Cons |
| --- | --- | --- |
| **A. EMF (Embedded Metric Format) via logs** | No new IAM (logs already flow via awslogs); no SDK client; no sidecar; async by nature | Only emits when a log line is written (fine for per-request events) |
| B. Micrometer + CloudWatch registry | Idiomatic Spring; timers/counters | Adds Actuator+Micrometer+registry deps; needs `PutMetricData` IAM; batching config |
| C. Direct `PutMetricData` (AWS SDK v2) | Explicit control; can emit on a timer with no request traffic | Needs IAM; must hand-roll async/batching |

**Chosen for request-scoped metrics: A (EMF via structured logs).** The app already ships logs to
CloudWatch via `awslogs` and already has a JSON-logger precedent (`AuditService`). EMF needs **no
new IAM** and cannot block a request (it's a log line). CloudWatch auto-extracts metrics from EMF
log entries into the target namespace. This covers Bedrock latency/failure, image
latency/retries/failure, search latency/mode/embed-fallback, and SSE counts — all of which happen
*during* request/async handling, so a log line is naturally present.

### 5.2 Why one metric needs `PutMetricData` (option C) too
The **OCI node health probe** (§5.3) runs on a **timer**, not on request traffic — and its whole
point is to report `OpenSearchNodeUp=0` precisely when the app is *not* serving search. Relying on
EMF-over-logs alone is workable (the probe writes an EMF log line each tick), and that is the
zero-IAM default. But EMF has a subtlety: if the log line fails to ship, the metric silently
goes missing rather than reporting "down", and alarming on missing-data is coarser than alarming on
an explicit `0`. So the design supports **either**:
- **Default (no new IAM):** the probe emits its metrics as EMF log lines like everything else, and
  the `OpenSearchNodeUp` alarm uses `treat_missing_data = "breaching"`.
- **Opt-in (`monitoring.metrics.transport=putmetricdata`):** the probe (and optionally all app
  metrics) call `PutMetricData` directly, which requires the IAM statement in §8. This gives a
  crisp `0` datapoint even under log-pipeline trouble.

The design ships with the **EMF default** to honor Req 8.2 (no IAM unless needed) and documents the
`PutMetricData` upgrade as a one-statement, namespace-scoped opt-in.

### 5.3 OpenSearch node health probe (the OCI-node seam)
A new `OpenSearchHealthProbe` component, guarded by `@ConditionalOnProperty` on both
`monitoring.metrics.enabled=true` AND the OpenSearch backend actually being the basic-auth node
(`catalog.search.backend=opensearch` + `opensearch.auth=basic`), so it is a no-op for the in-app
fallback and any SigV4 path:
- `@Scheduled(fixedRate = 60_000)` calls the existing `OpenSearchClient` (`_cluster/health` or a
  ping) with a short timeout (the client is already configured with bounded timeouts).
- On success: emit `OpenSearchNodeUp=1`, `OpenSearchClusterStatus` (green=2/yellow=1/red=0),
  `OpenSearchHealthProbeLatencyMs`.
- On any failure/timeout: emit `OpenSearchNodeUp=0` and swallow the exception (log at debug). The
  probe never throws and never touches a user request.
- Emission goes through the same `MetricsService` used by the request-scoped metrics (EMF by
  default, `PutMetricData` if that transport is selected).

This is the only mechanism by which CloudWatch can observe the Oracle-hosted node. It reuses the
already-wired `OpenSearchClient` bean, so it adds no new connection/credentials.

### 5.4 Request-scoped emission points (log-only additions, no control-flow change)
- `RecipeController.generateRecipes`: emit `BedrockLatencyMs` + `BedrockFailure` (dim `Model`)
  reusing the already-measured `generationMs`.
- `AsyncImageService.generateAndUpdateRecipe`: emit `ImageLatencyMs`, `ImageRetryCount`,
  `ImageFinalFailure` (dim `ImageModel`) reusing `result.generationMs()` and the retry counters.
- `OpenSearchCatalogSearchService` / `InAppCatalogSearchService`: wrap the search call to emit
  `SearchLatencyMs` + `SearchMode`, and `SearchEmbedFallback` on the `embedQuietly` null path.
  (Search latency is currently unmeasured; add a `System.currentTimeMillis()` bracket around the
  `client.search(...)` / ranking call.)
- `StatsSseService` / `ImageSseService`: emit gauge `SseActiveEmitters` (= `emitters.size()`) on
  register/remove and `SseBroadcastFailure` on emitter send error.
- EMF example line (namespace `RecipeAiFinder/App`, dimension `Model`):
  `{ "_aws": {"CloudWatchMetrics":[{"Namespace":"RecipeAiFinder/App","Dimensions":[["Model"]],
    "Metrics":[{"Name":"BedrockLatencyMs","Unit":"Milliseconds"}]}]}, "Model":"claude-haiku-4-5",
    "BedrockLatencyMs": 812 }`
- `MetricsService` is guarded by `@ConditionalOnProperty("monitoring.metrics.enabled")`
  (default false → no-op bean); the emit method catches everything and logs at debug so a metrics
  failure never propagates (Req 6.3).

### 5.5 If Micrometer (option B) is ever chosen instead
Add `spring-boot-starter-actuator` + `micrometer-registry-cloudwatch2`, a `MeterRegistry`
CloudWatch bean, and `cloudwatch:PutMetricData` scoped by
`StringEquals cloudwatch:namespace = RecipeAiFinder/App`. Documented, not implemented now.

## 6. Log-derived metrics (no app change)
- `image_gen_failures`: metric filter on the `AsyncImageService` final-failure line (literal prefix
  **`Image generation failed after`**) → metric `ImageGenFinalFailures` in `RecipeAiFinder/App`.
- **Bedrock retry-exhaustion:** there is currently **no dedicated log line** — `BedrockService`
  only logs per-attempt WARN then throws. Two options:
  - (a) Add a single `log.error("Bedrock generation failed after {} attempts", maxAttempts, ...)`
    at the throw and filter on it → metric `BedrockRetryExhausted`. This is a one-line, log-only
    addition (no control-flow change).
  - (b) Skip the filter and rely on the app `BedrockFailure` metric (§5.4) instead.
  **Chosen: (a)** — it's a trivial, honest log improvement and gives a signal even when app metrics
  are disabled. The prior spec assumed this line already existed; it does not.
- `AUDIT` JSON logger: recommend Logs Insights saved queries over a metric filter (documented in
  runbook), since audit volume is low and ad-hoc query fits better.

## 7. Dashboard layout (one page)
Rows:
1. ECS CPU/mem + ALB healthy hosts.
2. ALB request count / 5XX / p95 latency.
3. DynamoDB consumed capacity + throttles per table (users/recipes/catalog/consent/audit-log
   [+catalog-full]).
4. **NAT gateway**: `BytesOut/InToDestination` throughput, `ActiveConnectionCount`,
   `ErrorPortAllocation`/`PacketsDropCount`.
5. Bedrock invocations/latency/errors + app `BedrockLatencyMs`/`BedrockFailure`.
6. Image `ImageLatencyMs`/`ImageFinalFailure`; search `SearchLatencyMs`/`SearchMode`.
7. **OpenSearch node** (OCI): `OpenSearchNodeUp`, `OpenSearchClusterStatus`,
   `OpenSearchHealthProbeLatencyMs` — plus Serverless OCU widgets *only if* `enable_opensearch`.
8. WAF allowed vs blocked + SSE `SseActiveEmitters`/`SseBroadcastFailure`.

Built from `templatefile(...)` so names/region/`nat_gateway_id` are injected. Node-health and
app-metric widgets render only when `enable_app_metrics=true`.

## 8. IAM
- **Default (EMF transport):** no IAM change — logs already flow via awslogs (Req 8.2).
- **If `PutMetricData` transport is selected:** append ONE statement to `aws_iam_policy.task_policy`
  (`modules/iam`), additive, leaving the existing bedrock/dynamodb/s3/ssm/aoss/cognito statements
  untouched:
  ```hcl
  {
    Effect   = "Allow"
    Action   = ["cloudwatch:PutMetricData"]
    Resource = "*"
    Condition = { StringEquals = { "cloudwatch:namespace" = "RecipeAiFinder/App" } }
  }
  ```
  (`PutMetricData` does not support resource-level ARNs, so the namespace condition is the scoping
  mechanism.)
- **X-Ray (opt-in):** `xray:PutTraceSegments` / `xray:PutTelemetryRecords` added only with
  `enable_xray`.

## 9. Cost estimate
- Layer 1 native-service metrics + ~1 dashboard + alarms → mostly free tier (10 metrics / 10
  alarms / 3 dashboards free). **Alarm count grows** with this revision: ECS(2) + ALB(3) +
  DynamoDB(~6) + Bedrock(1) + NAT(2–3) + OpenSearch-node(2) + WAF(1, existing) ≈ **17–18 alarms**,
  so ~7–8 alarms are billable at ~$0.10/alarm/mo → ~$0.70–0.80/mo.
- Custom app metrics + node-health metrics: ~10–14 series; a handful beyond the free 10 at ~$0.30
  each → a couple dollars. Logs already billed (EMF rides that pipeline).
- **NAT data-processing cost is unchanged by monitoring** — but the NAT widgets make that existing
  cost visible for the first time.
- **Revised estimate ~$5–15/mo**, budget default **$20**. X-Ray (if enabled later): ~$0–few/mo
  (free tier covers 100k traces).

## 10. X-Ray (optional, Req 11)
Separate `enable_xray` flag. Approach when pursued: ADOT (AWS Distro for OpenTelemetry) sidecar in
the backend task def + `xray:PutTraceSegments`/`PutTelemetryRecords` on the task role + Spring
instrumentation, feeding ServiceLens for the ALB→ECS→Bedrock/DynamoDB map. **The OCI OpenSearch
node, being non-AWS, will not appear as an X-Ray segment** unless the OpenSearch client calls are
separately instrumented as a downstream subsegment (best-effort; documented, not required). Kept
out of the base suite so metrics+alarms+dashboard can ship without touching the task definition.

## 11. Testing
- `terraform validate` + `fmt -check`; `enable_monitoring=false` ⇒ empty plan (Req 10.1).
- App: `MetricsService` unit test proving no-op when flag off and correct EMF shape when on;
  `OpenSearchHealthProbe` test proving it emits `OpenSearchNodeUp=0` (not throw) on a probe
  failure and is a no-op when the backend isn't the basic-auth node; existing tests unchanged.
  `./mvnw test` green.
- Manual: enable in dev, confirm SNS email subscription, trigger a test alarm, confirm dashboard
  widgets populate, confirm a forced image-gen failure increments the log-filter metric, and
  **stop the OCI node briefly to confirm `OpenSearchNodeUp` flips to 0 and the alarm fires**.
