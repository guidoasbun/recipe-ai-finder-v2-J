# Requirements — CloudWatch Monitoring Suite

## Introduction

Provide a single consolidated observability layer for the Recipe AI Finder app using
**Amazon CloudWatch** (native, no third-party vendor). The goal is one place to see the health
of the whole system — ECS, ALB, DynamoDB, S3, Bedrock, the **NAT gateway** (the single egress
chokepoint), the **self-hosted OpenSearch node on Oracle Cloud**, WAF, and app-specific signals —
plus alerting when things go wrong, all defined in Terraform and matching the existing module
conventions.

CloudWatch was chosen over Datadog on cost: for this app's scale the estimated difference is
roughly **$5–15/mo (CloudWatch, largely free-tier)** vs **~$180–200/mo (Datadog per-task
infra + APM)**. See `design.md §1` for the comparison. The tradeoff accepted is that the
consolidated view is assembled deliberately (this spec) rather than out-of-the-box.

### Grounding: what actually runs today (from the codebase inventory)
Two facts reshape this spec versus a generic "monitor an AWS app" plan:

1. **The live search backend is NOT AWS OpenSearch Serverless.** The Serverless collection was
   deleted for cost (`enable_opensearch=false`; it held ~6.5 OCU warm ≈ $240/mo). Search now runs
   on a **self-hosted single-node OpenSearch 2.17.1 container on an Oracle Cloud (OCI) Ampere A1
   VM** (`modules/oci-opensearch`), reached over HTTPS `:9200` with basic auth. **Native CloudWatch
   cannot see an OCI host** — so monitoring it requires the app (or a probe) to actively push
   custom metrics. This is the single biggest gap in the previous version of this spec.
2. **A single NAT gateway is the entire egress path** for the private-subnet ECS tasks — every
   Bedrock / DynamoDB / S3 / Secrets Manager / Cognito / image-provider / OpenSearch call leaves
   through one NAT + one Elastic IP. It's both a cost driver (per-GB processing) and a single point
   of failure, and nothing monitors it today.

Other grounding facts the design relies on: backend is **Spring Boot 4.0.5 / Java 21 (Maven)** with
**no Actuator or Micrometer**; the only structured-JSON log path is the `AUDIT` logger in
`AuditService` (there is no root JSON logging config); latency is measured ad-hoc with
`System.currentTimeMillis()` in `RecipeController` (`generationMs`) and `ImageGenerationService`
(`imageGenerationMs`); the health endpoint is `GET /api/health` returning a static `UP` (it does not
probe OpenSearch/DynamoDB); the two ECS log groups (`/ecs/recipe-ai-<env>-backend|frontend`,
30-day retention) and the single WAF `BlockedRequests` alarm are the only CloudWatch resources, and
**no SNS topic exists** (`waf_alarm_sns_topic_arn=""`, so the WAF alarm fires no actions).

### Scope guardrails (mirrors the OpenSearch spec's discipline)
- Everything is **opt-in and cost-bounded**: a new `monitoring` Terraform module gated
  `enable_monitoring = false` by default (`count = enable ? 1 : 0`), so the standard deployment
  provisions nothing new until explicitly turned on.
- **No behavior change** to request handling. App-side changes only *emit* telemetry (metrics,
  and a lightweight OpenSearch health probe); they must not alter responses, latency-critical
  paths, or existing functionality.
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

### Requirement 2 — Infrastructure metric alarms (native AWS services)
**User Story:** As the operator, I want alarms on the AWS services this app depends on, so that
degradation is caught early.

#### Acceptance Criteria
1. ECS: alarms on backend service CPU and memory utilization (per-service dimensions).
2. ALB: alarms on 5XX count (target + ELB) and unhealthy host count on the backend target group.
3. ALB: alarm on p95/p99 target response time above a configurable threshold.
4. DynamoDB: alarms on `ThrottledRequests` / `ReadThrottleEvents` / `WriteThrottleEvents` for the
   `users`, `recipes`, `catalog`, `consent`, `audit-log`, and (when `enable_catalog_full=true`)
   `catalog-full` tables. All tables are PAY_PER_REQUEST, so throttling is the key stress signal.
5. Bedrock: alarm on model invocation errors and (if surfaced) throttles.
6. Every alarm's threshold SHALL be a module variable with sensible defaults; alarm actions
   SHALL point at the Requirement 1 SNS topic.
7. Alarms SHALL be tagged consistently (`Name`, `Environment`, `Project`) like the WAF alarm.

### Requirement 3 — NAT gateway monitoring (single egress chokepoint)
**User Story:** As the operator, I want the one NAT gateway watched, because every outbound call
the app makes (Bedrock, DynamoDB, S3, Secrets Manager, Cognito, image providers, and the OCI
OpenSearch node) leaves through it — if it degrades, the whole app degrades.

#### Acceptance Criteria
1. Alarms SHALL be created on the NAT gateway (`AWS/NATGateway`, dimension `NatGatewayId`) for:
   - `ErrorPortAllocation` and/or `PacketsDropCount` > 0 (port-allocation exhaustion / drops).
   - `ActiveConnectionCount` above a configurable threshold (connection-count saturation).
2. The dashboard SHALL include NAT throughput (`BytesInFromDestination` / `BytesOutToDestination`)
   and connection-count widgets, since NAT data processing is a real per-GB cost driver.
3. The NAT gateway id SHALL be passed into the monitoring module from the networking module's
   output (a new `nat_gateway_id` output alongside the existing `nat_gateway_public_ip`).
4. Alarm actions SHALL point at the Requirement 1 SNS topic; thresholds SHALL be module variables.

### Requirement 4 — Self-hosted OpenSearch node monitoring (OCI, non-AWS)
**User Story:** As the operator, I want to know when the Oracle-hosted OpenSearch node is
unhealthy or unreachable, since it is the live catalog-search backend and native CloudWatch
cannot see an Oracle Cloud host.

#### Acceptance Criteria
1. The design SHALL acknowledge that native CloudWatch CANNOT reach the OCI VM (non-AWS host,
   self-signed TLS on an IP-locked `:9200`), so node health MUST be observed by actively pushing
   custom metrics into CloudWatch rather than reading AWS-native metrics.
2. The backend SHALL run a lightweight, scheduled OpenSearch health probe (e.g. `_cluster/health`
   or a client ping) and publish custom metrics to `RecipeAiFinder/App` (or a
   `RecipeAiFinder/OpenSearch` namespace):
   - `OpenSearchNodeUp` (1/0 reachability).
   - `OpenSearchClusterStatus` (green=2 / yellow=1 / red=0) when the health API returns it.
   - `OpenSearchHealthProbeLatencyMs`.
3. The probe SHALL be feature-flagged, non-blocking, and failure-tolerant: it MUST NOT affect
   request handling, and a probe/publish failure MUST be swallowed-and-logged (a failed probe
   reports `OpenSearchNodeUp=0`, it does not throw).
4. The probe SHALL only run when the OpenSearch backend is actually the OCI node
   (`catalog.search.backend=opensearch` AND `opensearch.auth=basic`); it SHALL be a no-op for the
   in-app fallback and for a (future) SigV4/AWS backend.
5. An alarm SHALL fire on `OpenSearchNodeUp` breaching (node unreachable) and on
   `OpenSearchClusterStatus` red, with actions pointing at the Requirement 1 SNS topic.
6. WHEN the AWS OpenSearch Serverless collection is ever re-enabled (`enable_opensearch=true`)
   THEN the module SHALL additionally create the native Serverless alarms/widgets (OCU + search
   errors), conditionally — this remains a legacy/optional path, not the live one.

### Requirement 5 — Consolidated dashboard (the single pane)
**User Story:** As the operator, I want one dashboard showing everything, so that I can assess
system health at a glance.

#### Acceptance Criteria
1. An `aws_cloudwatch_dashboard` SHALL present, on one page: ECS CPU/mem, ALB
   request count / 5XX / latency / healthy-host count, DynamoDB capacity + throttles per table,
   NAT gateway throughput + connections, Bedrock invocation count/latency/errors, the OCI
   OpenSearch node health + search latency, WAF allowed vs blocked, and the custom app metrics
   from Requirement 6.
2. The dashboard body SHALL be generated from Terraform (templated JSON) so it stays in sync with
   the region/account/resource names.
3. OpenSearch Serverless widgets SHALL be conditionally included only when `enable_opensearch =
   true` (legacy path); the OCI-node health widgets SHALL be included whenever app metrics are on.

### Requirement 6 — Application custom metrics
**User Story:** As the operator, I want app-level signals (not just infra), so that I can see
AI/search behavior that AWS service metrics don't expose.

#### Acceptance Criteria
1. The backend SHALL publish these to a custom namespace (e.g. `RecipeAiFinder/App`):
   - Bedrock text-generation latency and failure count (per model dimension).
   - Image-generation latency, retry count, and final-failure count (per image model).
   - Catalog search latency and query mode (keyword/semantic/hybrid), plus embed-fallback count.
   - SSE active-emitter counts and broadcast failures (stats + image streams).
   - The OpenSearch node health metrics from Requirement 4.
2. Latency already captured (`textGenerationMs`/`imageGenerationMs`, i.e. `generationMs`) SHALL be
   reused as the metric source where possible rather than re-measured.
3. Metric publishing SHALL be non-blocking and failure-tolerant: a metrics error MUST NOT fail or
   slow the user request (fire-and-forget / async, swallow-and-log on error).
4. Metric publishing SHALL be feature-flagged (`monitoring.metrics.enabled`, default `false`) so
   the default local/dev run and the in-app deployment are unaffected.
5. The chosen mechanism SHALL be justified in the design. Because the OCI-node health metrics
   (Req 4) must be actively pushed, the design SHALL state clearly which mechanism carries which
   metric (EMF-over-logs vs `PutMetricData`) and the IAM consequence of each (see Requirement 8).
   Whichever is chosen MUST NOT require a sidecar unless the design explicitly adds one.

### Requirement 7 — Log-derived metrics & queryability
**User Story:** As the operator, I want to turn existing logs into signals and query them, so that
I get value from logs already being written.

#### Acceptance Criteria
1. `aws_cloudwatch_log_metric_filter`(s) SHALL derive metrics from existing log lines without app
   changes — specifically the `AsyncImageService` final-failure line (literal prefix
   "Image generation failed after") → `ImageGenFinalFailures`.
2. Because `BedrockService` currently logs only per-attempt WARN and then throws with NO dedicated
   retry-exhaustion log line, the design SHALL either (a) add a single ERROR log line at the throw
   and filter on it, or (b) rely on the app `BedrockFailure` metric instead. The chosen approach
   SHALL be stated (this corrects an assumption in the prior version that such a log line exists).
3. The design SHALL note whether the `AUDIT` JSON logger warrants a metric filter or Logs Insights
   saved queries.
4. Existing log-group retention (30 days) SHALL be preserved; any change is explicit and justified.

### Requirement 8 — IAM (least privilege)
**User Story:** As a security-conscious operator, I want monitoring to add only the minimum
permissions, so that the task role stays least-privilege.

#### Acceptance Criteria
1. Because the OCI-node health metrics (Req 4) cannot be delivered via EMF-over-logs alone if a
   metric must exist even when no request flows, the design SHALL specify whether
   `cloudwatch:PutMetricData` is required. IF `PutMetricData` is used THEN it SHALL be added to the
   ECS task policy (`aws_iam_policy.task_policy` in `modules/iam`) as a single additive statement,
   scoped by a `cloudwatch:namespace` condition where feasible.
2. IF EMF-via-logs is chosen for a given metric THEN NO new IAM SHALL be required for that metric
   (logs already flow via awslogs).
3. IF X-Ray is included THEN `xray:PutTraceSegments`/`xray:PutTelemetryRecords` SHALL be added and
   the tracing approach documented; X-Ray is OPTIONAL and behind its own flag.
4. Any IAM change SHALL be additive (a new statement object) and SHALL NOT alter the existing
   bedrock/dynamodb/s3/ssm/aoss/cognito statements.

### Requirement 9 — Cost guardrail
**User Story:** As a cost-conscious operator, I want monitoring spend bounded and visible.

#### Acceptance Criteria
1. Custom metrics, alarms, and dashboards SHALL be counted against the CloudWatch free tier in the
   design; the design SHALL estimate monthly cost including the added NAT and OCI-node metrics.
2. An AWS Budget scoped to CloudWatch (and X-Ray if enabled) SHALL be provisioned when an email is
   provided, matching the WAF/OpenSearch budget pattern.
3. The number of custom metrics and dashboards SHALL be kept within/near the free tier where
   practical (10 free custom metrics, 10 free alarms, 3 free dashboards). The design SHALL flag
   that adding NAT alarms + OCI-node metrics pushes the alarm/metric counts up and account for it.

### Requirement 10 — Opt-in, isolation, verification
**User Story:** As the operator, I want to enable monitoring safely and confirm it changed nothing
else.

#### Acceptance Criteria
1. `enable_monitoring = false` (default) SHALL provision zero new resources
   (`terraform plan` shows no changes).
2. The feature SHALL be isolated: only the new `monitoring` module, the additive
   `nat_gateway_id` networking output, the additive IAM statement (only if `PutMetricData` is
   used), the opt-in app metrics flag + health probe, and root wiring change. No change to request
   handling, Bedrock/search logic, or existing tests' behavior.
3. `terraform validate` + `terraform fmt -check` SHALL pass; app changes SHALL compile and existing
   tests SHALL remain green.
4. A runbook SHALL document enable → confirm SNS subscription → deploy → verify dashboard/alarms
   (including a forced OCI-node-down test showing `OpenSearchNodeUp=0`) → disable/rollback.

### Requirement 11 — Optional distributed tracing (X-Ray) — future/opt-in
**User Story:** As the operator, I may later want end-to-end traces across ALB → ECS →
Bedrock/DynamoDB/OpenSearch.

#### Acceptance Criteria
1. X-Ray SHALL be a separate opt-in flag (`enable_xray`, default `false`); the base suite
   (metrics + logs + dashboard + alarms) SHALL be fully usable without it.
2. The design SHALL document the instrumentation approach (SDK/agent/ADOT sidecar) and its cost,
   so it can be a later task rather than a blocker for the base suite. Note that the OCI node,
   being non-AWS, will not appear as an X-Ray segment unless separately instrumented.
