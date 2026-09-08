# Requirements — CloudWatch Monitoring Suite

## Introduction

Provide a single consolidated observability layer for the Recipe AI Finder app using
**Amazon CloudWatch** (native, no third-party vendor). The goal is one place to see the health
of the whole system — ECS, ALB, DynamoDB, S3, Bedrock, OpenSearch Serverless, WAF, and
app-specific signals — plus alerting when things go wrong, all defined in Terraform and matching
the existing module conventions.

CloudWatch was chosen over Datadog on cost: for this app's scale the estimated difference is
roughly **$5–15/mo (CloudWatch, largely free-tier)** vs **~$180–200/mo (Datadog per-task
infra + APM)**. See `design.md §1` for the comparison. The tradeoff accepted is that the
consolidated view is assembled deliberately (this spec) rather than out-of-the-box.

### Scope guardrails (mirrors the OpenSearch spec's discipline)
- Everything is **opt-in and cost-bounded**: a new `monitoring` Terraform module gated
  `enable_monitoring = false` by default (`count = enable ? 1 : 0`), so the standard deployment
  provisions nothing new until explicitly turned on.
- **No behavior change** to request handling. App-side changes only *emit* telemetry; they must
  not alter responses, latency-critical paths, or existing functionality.
- Reuse existing patterns: the WAF alarm (`modules/waf/monitoring.tf`), the log groups
  (`modules/ecs/main.tf`), the budget + `enable_*` gating (`modules/opensearch`).

## Requirements

### Requirement 1 — Alarm notification channel (SNS)
**User Story:** As the operator, I want alarms to actually notify me, so that I learn about
incidents without watching a dashboard.

#### Acceptance Criteria
1. WHEN monitoring is enabled AND a notification email is provided THEN an `aws_sns_topic` and
   an email `aws_sns_topic_subscription` SHALL be created.
2. WHEN monitoring is enabled but no email is provided THEN the module SHALL fail fast with a
   clear message (mirroring the opensearch budget-email precondition).
3. The SNS topic ARN SHALL be exposed as an output so the existing WAF alarm can be wired to it
   (today `waf_alarm_sns_topic_arn` is `""`, so the WAF alarm fires no actions).
4. Email subscription confirmation is a manual one-time click; the runbook SHALL document it.

### Requirement 2 — Infrastructure metric alarms
**User Story:** As the operator, I want alarms on the AWS services this app depends on, so that
degradation is caught early.

#### Acceptance Criteria
1. ECS: alarms on backend service CPU and memory utilization (per-service dimensions).
2. ALB: alarms on 5XX count (target + ELB) and unhealthy host count on the backend target group.
3. ALB: alarm on p95/p99 target response time above a configurable threshold.
4. DynamoDB: alarms on `ThrottledRequests` / `ReadThrottleEvents` / `WriteThrottleEvents` for the
   recipes, catalog, and (when enabled) catalog-full tables.
5. Bedrock: alarm on model invocation errors and (if surfaced) throttles.
6. OpenSearch Serverless: alarm on OCU consumption approaching the configured cap and on search
   error rate — created only when `enable_opensearch = true`.
7. Every alarm's threshold SHALL be a module variable with sensible defaults; alarm actions
   SHALL point at the Requirement 1 SNS topic.
8. Alarms SHALL be tagged consistently (`Name`, `Environment`, `Project`) like the WAF alarm.

### Requirement 3 — Consolidated dashboard (the single pane)
**User Story:** As the operator, I want one dashboard showing everything, so that I can assess
system health at a glance.

#### Acceptance Criteria
1. An `aws_cloudwatch_dashboard` SHALL present, on one page: ECS CPU/mem, ALB
   request count / 5XX / latency / healthy-host count, DynamoDB capacity + throttles per table,
   Bedrock invocation count/latency/errors, OpenSearch OCU + search latency (when enabled), WAF
   allowed vs blocked, and the custom app metrics from Requirement 4.
2. The dashboard body SHALL be generated from Terraform (templated JSON) so it stays in sync with
   the region/account/resource names.
3. OpenSearch widgets SHALL be conditionally included only when `enable_opensearch = true`.

### Requirement 4 — Application custom metrics
**User Story:** As the operator, I want app-level signals (not just infra), so that I can see
AI/search behavior that AWS service metrics don't expose.

#### Acceptance Criteria
1. The backend SHALL publish these to a custom namespace (e.g. `RecipeAiFinder/App`):
   - Bedrock text-generation latency and failure count (per model dimension).
   - Image-generation latency, retry count, and final-failure count (per image model).
   - Catalog search latency and query mode (keyword/semantic/hybrid), plus embed-fallback count.
   - SSE active-emitter counts and broadcast failures (stats + image streams).
2. Latency already captured (`textGenerationMs`/`imageGenerationMs`) SHALL be reused as the metric
   source where possible rather than re-measured.
3. Metric publishing SHALL be non-blocking and failure-tolerant: a metrics error MUST NOT fail or
   slow the user request (fire-and-forget / async, swallow-and-log on error).
4. Metric publishing SHALL be feature-flagged (`monitoring.metrics.enabled`, default `false`) so
   the default local/dev run and the in-app deployment are unaffected.
5. The chosen mechanism (EMF via structured logs, or Micrometer CloudWatch registry, or direct
   `PutMetricData`) SHALL be justified in the design; whichever is chosen must not require a
   sidecar unless the design explicitly adds one.

### Requirement 5 — Log-derived metrics & queryability
**User Story:** As the operator, I want to turn existing logs into signals and query them, so that
I get value from logs already being written.

#### Acceptance Criteria
1. `aws_cloudwatch_log_metric_filter`(s) SHALL derive metrics from existing log lines (e.g.
   image-generation final-failure `log.warn`, Bedrock retry-exhaustion) without app changes.
2. The design SHALL note whether the `AUDIT` JSON logger warrants a metric filter or Logs Insights
   saved queries.
3. Existing log-group retention (30 days) SHALL be preserved; any change is explicit and justified.

### Requirement 6 — IAM (least privilege)
**User Story:** As a security-conscious operator, I want monitoring to add only the minimum
permissions, so that the task role stays least-privilege.

#### Acceptance Criteria
1. IF app metric publishing uses `PutMetricData` THEN `cloudwatch:PutMetricData` SHALL be added to
   the ECS task policy, scoped by a `cloudwatch:namespace` condition where feasible.
2. IF EMF-via-logs is chosen THEN NO new IAM SHALL be required (logs already flow via awslogs).
3. IF X-Ray is included THEN `xray:PutTraceSegments`/`xray:PutTelemetryRecords` SHALL be added and
   the tracing approach documented; X-Ray is OPTIONAL and behind its own flag.

### Requirement 7 — Cost guardrail
**User Story:** As a cost-conscious operator, I want monitoring spend bounded and visible.

#### Acceptance Criteria
1. Custom metrics, alarms, and dashboards SHALL be counted against the CloudWatch free tier in the
   design; the design SHALL estimate monthly cost.
2. An AWS Budget scoped to CloudWatch (and X-Ray if enabled) SHALL be provisioned when an email is
   provided, matching the WAF/OpenSearch budget pattern.
3. The number of custom metrics and dashboards SHALL be kept within/near the free tier where
   practical (10 free custom metrics, 10 free alarms, 3 free dashboards).

### Requirement 8 — Opt-in, isolation, verification
**User Story:** As the operator, I want to enable monitoring safely and confirm it changed nothing
else.

#### Acceptance Criteria
1. `enable_monitoring = false` (default) SHALL provision zero new resources
   (`terraform plan` shows no changes).
2. The feature SHALL be isolated: only the new `monitoring` module, additive IAM statements, the
   opt-in app metrics flag, and root wiring change. No change to request handling, Bedrock/search
   logic, or existing tests' behavior.
3. `terraform validate` + `terraform fmt -check` SHALL pass; app changes SHALL compile and existing
   tests SHALL remain green.
4. A runbook SHALL document enable → confirm SNS subscription → deploy → verify dashboard/alarms →
   disable/rollback.

### Requirement 9 — Optional distributed tracing (X-Ray) — future/opt-in
**User Story:** As the operator, I may later want end-to-end traces across ALB → ECS →
Bedrock/DynamoDB/OpenSearch.

#### Acceptance Criteria
1. X-Ray SHALL be a separate opt-in flag (`enable_xray`, default `false`); the base suite
   (metrics + logs + dashboard + alarms) SHALL be fully usable without it.
2. The design SHALL document the instrumentation approach (SDK/agent/ADOT sidecar) and its cost,
   so it can be a later task rather than a blocker for the base suite.
