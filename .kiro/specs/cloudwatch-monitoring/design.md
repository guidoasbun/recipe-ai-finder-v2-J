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

## 2. Current state (grounding)

From the codebase inventory:
- **CloudWatch today:** `aws_cloudwatch_log_group.backend` / `.frontend`
  (`/ecs/recipe-ai-<env>-backend|frontend`, `retention_in_days = 30`) wired via awslogs; one
  alarm `aws_cloudwatch_metric_alarm.waf_blocked_requests` (`AWS/WAFV2 BlockedRequests`, Sum,
  period 300, threshold var 500 dev / 1000 prod). **No SNS topic is created** — the alarm's
  `alarm_actions` is empty because `waf_alarm_sns_topic_arn = ""` in both tfvars. No dashboards,
  no metric filters, no X-Ray, no sidecar.
- **App instrumentation today:** none. No Actuator, no Micrometer, no `PutMetricData`. Latency
  `textGenerationMs` (set in `RecipeController`) and `imageGenerationMs` (set in
  `ImageGenerationService` → `AsyncImageService`) are stored on `Recipe` in DynamoDB and only
  aggregated in-app by `StatsService`. One JSON logger named `AUDIT` (`AuditService`).
- **Conventions:** modules = `main.tf`/`variables.tf`/`outputs.tf`; `enable_* = bool default false`
  with `count = enable ? 1 : 0` (canonical in `modules/opensearch`, `local.enabled`); budgets gated
  on `budget_notification_email != ""`; Terraform `>= 1.7`; AWS provider `~> 5.0`; per-env tfvars.
- **IAM:** task role `recipe-ai-ecs-task-role` single `task_policy` JSON — the exact place to
  append `cloudwatch:PutMetricData` / X-Ray actions. Task defs are single-container (no sidecar).

## 3. Architecture

Two layers, independently valuable:

```
┌───────────────────────── Infra layer (pure Terraform, no app change) ─────────────────────────┐
│  SNS topic + email sub  ──▶ alarm actions                                                       │
│  Metric alarms: ECS CPU/mem · ALB 5XX/latency/healthyhosts · DynamoDB throttles · Bedrock       │
│                 errors · OpenSearch OCU (if enabled) · (WAF alarm rewired to this SNS)          │
│  Log metric filters: image-gen final-failure, Bedrock retry-exhaustion (from existing logs)     │
│  Dashboard (templated JSON): all of the above + app metrics on one page                         │
│  Budget (COST, scoped CloudWatch [+X-Ray]) when email provided                                  │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
┌──────────────────────── App layer (backend, opt-in flag, non-blocking) ───────────────────────┐
│  RecipeAiFinder/App namespace: bedrock latency/errors, image latency/retries/failures,          │
│  catalog search latency+mode+embed-fallback, SSE emitter counts/broadcast failures              │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
```

The infra layer works with zero app changes (log-filter metrics + native AWS service metrics),
so it can ship first. The app layer adds signals AWS metrics can't see.

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

resource "aws_sns_topic" "alarms"        { count = local.enabled ? 1 : 0 ... }
resource "aws_sns_topic_subscription" "email" {
  count = local.enabled ? 1 : 0
  topic_arn = aws_sns_topic.alarms[0].arn
  protocol  = "email"
  endpoint  = var.notification_email
}

resource "aws_cloudwatch_metric_alarm" "ecs_cpu"          { count = local.enabled ? 1 : 0 ... }
# ... memory, alb_5xx, alb_latency, alb_unhealthy_hosts, ddb_throttle (for_each tables),
#     bedrock_errors, opensearch_ocu (count = enabled && var.enable_opensearch ? 1 : 0)

resource "aws_cloudwatch_log_metric_filter" "image_gen_failures" { count = local.enabled ? 1 : 0 ... }

resource "aws_cloudwatch_dashboard" "main" {
  count          = local.enabled ? 1 : 0
  dashboard_name = "${var.project_name}-${var.environment}"
  dashboard_body = templatefile("${path.module}/dashboard.tf.json.tftpl", { ...names, region, enable_opensearch })
}

resource "aws_budgets_budget" "monitoring" {
  count = local.enabled && var.notification_email != "" ? 1 : 0
  cost_filter { name = "Service" values = ["AmazonCloudWatch"] } # + "AWS X-Ray" when enable_xray
  ...
}
```

Every alarm uses `alarm_actions = [aws_sns_topic.alarms[0].arn]`, `GreaterThanThreshold`, tags
`{ Name, Environment, Project }` — identical shape to the WAF alarm.

### 4.1 Rewiring the existing WAF alarm
Root `main.tf` currently passes `alarm_sns_topic_arn = var.waf_alarm_sns_topic_arn` (`""`). Change
the wiring so that when monitoring is enabled it passes `module.monitoring.sns_topic_arn`,
otherwise the existing var. This gives the already-built WAF alarm a real destination with no WAF
module change.

### 4.2 Root wiring & tfvars
`main.tf` adds `module "monitoring"` receiving: cluster/service names (ecs), ALB ARN suffix + TG
ARN suffix (alb), table names (dynamodb), `enable_opensearch`, collection id (opensearch),
`enable_monitoring`, `notification_email`, thresholds. New tfvars keys (default off):
`enable_monitoring=false`, `monitoring_notification_email=""`, `enable_xray=false`, plus threshold
overrides. Providers/versions unchanged (`>=1.7`, aws `~>5.0`).

## 5. App custom metrics — mechanism choice

Three options considered:

| Option | Pros | Cons |
| --- | --- | --- |
| **A. EMF (Embedded Metric Format) via logs** | No new IAM (logs already flow via awslogs); no SDK client; no sidecar; async by nature | Metrics appear via the awslogs pipeline; slight structure discipline |
| B. Micrometer + CloudWatch registry | Idiomatic Spring; timers/counters | Adds Actuator+Micrometer+registry deps; needs `PutMetricData` IAM; batching config |
| C. Direct `PutMetricData` (AWS SDK v2) | Explicit control | Needs IAM; must hand-roll async/batching; easy to block a request if done naively |

**Chosen: A (EMF via structured logs), with B as a documented future upgrade.** Rationale: the app
already ships logs to CloudWatch via `awslogs`, and already has a JSON logger precedent
(`AuditService`). EMF needs **no new IAM** (Req 6.2) and cannot block a request (it's a log line).
CloudWatch auto-extracts metrics from EMF log entries into the target namespace.

### 5.1 Implementation sketch
- New `MetricsService` (thin) that writes one EMF JSON line per event to a dedicated logger
  (`METRICS`), guarded by `@ConditionalOnProperty("monitoring.metrics.enabled")` (default false).
  When disabled the bean is a no-op, so default/dev/in-app runs are untouched (Req 4.4, 8.2).
- Call sites (log-only additions, no control-flow change):
  - `RecipeController`: emit `BedrockLatencyMs` + `BedrockFailure` (dimension: model) reusing the
    already-measured `generationMs`.
  - `AsyncImageService`: emit `ImageLatencyMs`, `ImageRetryCount`, `ImageFinalFailure` (dimension:
    imageModel) reusing `result.generationMs()` and the retry loop counters.
  - `OpenSearchCatalogSearchService` / `InAppCatalogSearchService`: wrap the search call to emit
    `SearchLatencyMs` + `SearchMode`, and `SearchEmbedFallback` on the `embedQuietly` null path.
  - `StatsSseService` / `ImageSseService`: emit gauge `SseActiveEmitters` on register/remove and
    `SseBroadcastFailure` on emitter send error.
- EMF example line (namespace `RecipeAiFinder/App`, dimension `Model`):
  `{ "_aws": {"CloudWatchMetrics":[{"Namespace":"RecipeAiFinder/App","Dimensions":[["Model"]],
    "Metrics":[{"Name":"BedrockLatencyMs","Unit":"Milliseconds"}]}]}, "Model":"claude-haiku-4-5",
    "BedrockLatencyMs": 812 }`
- Failure-tolerance: the emit method catches everything and logs at debug — a metrics failure never
  propagates (Req 4.3).

### 5.2 If B is ever chosen instead
Add `spring-boot-starter-actuator` + `micrometer-registry-cloudwatch2`, a `MeterRegistry`
CloudWatch bean, and `cloudwatch:PutMetricData` to the task policy scoped by
`StringEquals cloudwatch:namespace = RecipeAiFinder/App`. Documented, not implemented now.

## 6. Log-derived metrics (no app change)
- `image_gen_failures`: pattern on `AsyncImageService` final-failure line
  ("Image generation failed after") → metric `ImageGenFinalFailures`.
- `bedrock_retry_exhausted`: pattern on `BedrockService` throw path / final warn → metric
  `BedrockRetryExhausted`.
These come straight from existing `/ecs/recipe-ai-<env>-backend` logs, so they work even before the
app layer ships. `AUDIT` JSON logger: recommend Logs Insights saved queries over a metric filter
(documented in runbook), since audit volume is low and ad-hoc query fits better.

## 7. Dashboard layout (one page)
Rows: (1) ECS CPU/mem + ALB healthy hosts; (2) ALB request count / 5XX / p95 latency;
(3) DynamoDB consumed capacity + throttles per table; (4) Bedrock invocations/latency/errors +
app `BedrockLatencyMs`/`BedrockFailure`; (5) image `ImageLatencyMs`/`ImageFinalFailure`;
(6) search `SearchLatencyMs` + OpenSearch OCU (conditional); (7) WAF allowed vs blocked + SSE
emitters. Built from `templatefile(...)` so names/region are injected.

## 8. Cost estimate
- Log-filter metrics + native service metrics + ~1 dashboard + ~10 alarms → mostly free tier
  (10 metrics / 10 alarms / 3 dashboards free). Custom app metrics: ~8–12 series; a few beyond the
  free 10 at ~$0.30 each. Logs already billed. **Estimate ~$5–15/mo**, budget default $20.
- X-Ray (if enabled later): ~$0–few /mo at this traffic (free tier covers 100k traces recorded).

## 9. X-Ray (optional, Req 9)
Separate `enable_xray` flag. Approach when pursued: ADOT (AWS Distro for OpenTelemetry) sidecar in
the backend task def + `xray:PutTraceSegments`/`PutTelemetryRecords` on the task role + Spring
instrumentation, feeding ServiceLens for the ALB→ECS→Bedrock/DynamoDB/OpenSearch service map. Kept
out of the base suite so metrics+alarms+dashboard can ship without touching the task definition.

## 10. Testing
- `terraform validate` + `fmt -check`; `enable_monitoring=false` ⇒ empty plan (Req 8.1).
- App: `MetricsService` unit test proving no-op when flag off and correct EMF shape when on;
  existing tests unchanged. `mvn test` green.
- Manual: enable in dev, confirm SNS email subscription, trigger a test alarm, confirm dashboard
  widgets populate, confirm a forced image-gen failure increments the log-filter metric.
