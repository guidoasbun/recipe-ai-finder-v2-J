# Implementation Plan — CloudWatch Monitoring Suite

Build a native CloudWatch observability layer for the app in two independently-shippable layers:
an **infra layer** (pure Terraform: SNS, native-service alarms incl. NAT gateway, log-metric
filters, dashboard, budget) and an **app layer** (opt-in EMF custom metrics **plus a scheduled
health probe for the self-hosted OCI OpenSearch node**). Everything is gated
`enable_monitoring=false` by default with the `count = enable ? 1 : 0` pattern, matching the
OpenSearch module. Changes are confined to the new `monitoring` module, one additive networking
output, additive root wiring, one additive IAM statement (only if `PutMetricData` transport is
chosen), and the opt-in app metrics/probe.

> **Two grounding corrections baked into this plan (see design §2):**
> 1. **The live search backend is the self-hosted OpenSearch node on Oracle Cloud (OCI), NOT AWS
>    OpenSearch Serverless** (Serverless is deleted; `enable_opensearch=false`). Native CloudWatch
>    can't see an OCI host, so node health is observed via an **app-pushed** metric from a
>    scheduled probe (task 6). The Serverless alarms/widgets remain as a legacy path gated on
>    `enable_opensearch`.
> 2. **A single NAT gateway is the whole egress path** — it now gets its own alarms + dashboard row
>    (tasks 2.5, 4.1).
>
> **Decisions (from design):** CloudWatch over Datadog on cost. Request-scoped app metrics via
> **EMF-over-logs** (no new IAM). The OCI-node health probe emits via the same `MetricsService`;
> EMF is the zero-IAM default, `PutMetricData` is a documented opt-in transport (task 5.3) that
> needs the one IAM statement in task 5.2.
>
> **Rollback:** `enable_monitoring=false` + `terraform apply` removes all monitoring resources;
> `monitoring.metrics.enabled=false` disables app emission + the probe. Neither touches request
> handling.

- [ ] 1. Monitoring module scaffold + SNS notification channel
  - [ ] 1.1 Create `infrastructure/modules/monitoring/` with `main.tf`, `variables.tf`,
        `outputs.tf`. Add `enable_monitoring` (bool, default false) and `notification_email`
        (string, default "") vars with cost-rationale descriptions, mirroring
        `modules/opensearch/variables.tf`. `locals { enabled = var.enable_monitoring }`.
  - [ ] 1.2 Add `terraform_data.require_email_when_enabled` precondition (fail fast when enabled
        without an email), mirroring the opensearch budget-email precondition.
  - [ ] 1.3 `aws_sns_topic.alarms` + `aws_sns_topic_subscription.email` (protocol=email), both
        `count = local.enabled ? 1 : 0`. Output `sns_topic_arn` (`""` when disabled, guarded like
        opensearch outputs).
  - _Requirements: 1.1, 1.2, 1.3, 1.4_

- [ ] 2. Infrastructure metric alarms (native AWS services + NAT)
  - [ ] 2.1 ECS alarms: backend service CPU + memory utilization
        (`AWS/ECS`, dims ClusterName/ServiceName). Thresholds via vars (defaults 80%).
  - [ ] 2.2 ALB alarms: 5XX count (target + ELB), unhealthy host count on backend TG, and p95/p99
        `TargetResponseTime` (`AWS/ApplicationELB`, LoadBalancer/TargetGroup dims). Thresholds via
        vars.
  - [ ] 2.3 DynamoDB throttle alarms via `for_each` over the recipes/catalog/users/consent/
        audit-log table names (`AWS/DynamoDB` throttle metrics). catalog-full added only when its
        name is non-empty (`enable_catalog_full`).
  - [ ] 2.4 Bedrock invocation-error alarm (`AWS/Bedrock` InvocationClientErrors/ServerErrors /
        throttles as available).
  - [ ] 2.5 **NAT gateway alarms** (`AWS/NATGateway`, dim `NatGatewayId` from the new networking
        output): `ErrorPortAllocation` > 0, `PacketsDropCount` > 0, and `ActiveConnectionCount`
        above a configurable threshold. This is the single egress chokepoint for the whole app.
  - [ ] 2.6 All alarms: `alarm_actions = [aws_sns_topic.alarms[0].arn]`, tags
        `{ Name, Environment, Project }` — identical shape to the WAF alarm.
  - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 3.1, 3.2, 3.4_

- [ ] 3. OpenSearch alarms (OCI node primary; Serverless legacy)
  - [ ] 3.1 **OCI node health alarms** fed by the app-pushed metrics (task 6): alarm on
        `OpenSearchNodeUp < 1` with `treat_missing_data = "breaching"` (missing == not reporting ==
        down), and `OpenSearchClusterStatus < 1` (red). `count = local.enabled &&
        var.enable_opensearch_node_alarms ? 1 : 0` (defaults true when app metrics are on).
  - [ ] 3.2 **Legacy Serverless** OCU + search-error alarm, `count = local.enabled &&
        var.enable_opensearch ? 1 : 0` — no-op unless the AWS Serverless collection is ever
        re-enabled. Not the live path.
  - _Requirements: 4.1, 4.5, 4.6_

- [ ] 4. Log-derived metrics + new networking output
  - [ ] 4.1 Add `nat_gateway_id = aws_nat_gateway.main.id` to `modules/networking/outputs.tf`
        (additive, alongside the existing `nat_gateway_public_ip`).
  - [ ] 4.2 `aws_cloudwatch_log_metric_filter.image_gen_failures` on the backend log group,
        pattern matching the `AsyncImageService` final-failure line (literal prefix
        `Image generation failed after`) → `ImageGenFinalFailures` in `RecipeAiFinder/App`. Add an
        alarm (action = SNS topic).
  - [ ] 4.3 Bedrock retry-exhaustion: add a single `log.error("Bedrock generation failed after {}
        attempts", ...)` at the throw in `BedrockService` (log-only, no control-flow change), then
        a `bedrock_retry_exhausted` filter → `BedrockRetryExhausted` + alarm. (The prior spec
        assumed this log line existed; it does not.)
  - [ ] 4.4 Preserve existing 30-day retention on the log groups (no change).
  - _Requirements: 5.x, 7.1, 7.2, 7.4, 10.2_

- [ ] 5. Consolidated dashboard, root wiring, WAF rewire, IAM, tfvars, budget
  - [ ] 5.1 `dashboard.tf.json.tftpl` + `aws_cloudwatch_dashboard.main` (`count = local.enabled ? 1
        : 0`) via `templatefile(...)`: rows for ECS, ALB, DynamoDB (per table), **NAT gateway**,
        Bedrock, image, search, **OCI OpenSearch node health**, WAF, SSE — per design §7. Node-
        health/app widgets render only when `enable_app_metrics=true`; Serverless OCU widgets only
        when `enable_opensearch=true`.
  - [ ] 5.2 Instantiate `module "monitoring"` in `infrastructure/main.tf`, passing ECS
        cluster/service names, ALB + TG ARN suffixes, the DynamoDB table names, **`nat_gateway_id`
        (new output)**, `enable_opensearch`, `enable_app_metrics`, `enable_monitoring`,
        `notification_email`, thresholds. Rewire the WAF alarm: `alarm_sns_topic_arn =
        var.enable_monitoring ? module.monitoring.sns_topic_arn : var.waf_alarm_sns_topic_arn`.
  - [ ] 5.3 **IAM (only if `PutMetricData` transport is selected):** append ONE additive statement
        to `aws_iam_policy.task_policy` (`modules/iam`) — `cloudwatch:PutMetricData`, `Resource="*"`,
        `Condition StringEquals cloudwatch:namespace = RecipeAiFinder/App`. Leave the existing
        statements untouched. Skip entirely for the EMF default.
  - [ ] 5.4 `aws_budgets_budget.monitoring` (COST, service filter AmazonCloudWatch [+AWS X-Ray when
        enable_xray]) gated on email, matching WAF/OpenSearch budget pattern. Default $20.
  - [ ] 5.5 Add tfvars keys to dev/prod (all off): `enable_monitoring=false`,
        `monitoring_notification_email=""`, `enable_xray=false`, threshold overrides. Root
        `variables.tf` declarations to match.
  - _Requirements: 5.1, 5.2, 5.3, 8.1, 8.4, 9.2, 10.2_

- [ ] 6. App layer — custom metrics (EMF) + OCI-node health probe (opt-in, non-blocking)
  - [ ] 6.1 Add `MetricsService` writing one EMF JSON line per event to a `METRICS` logger, guarded
        by `@ConditionalOnProperty("monitoring.metrics.enabled", default false)`; no-op bean when
        disabled. Catch-and-log-at-debug so emission never propagates an error. (Optional
        `monitoring.metrics.transport=emf|putmetricdata`, default `emf`.)
  - [ ] 6.2 **`OpenSearchHealthProbe`**: `@Scheduled(fixedRate=60_000)`, guarded on
        `monitoring.metrics.enabled=true` AND `catalog.search.backend=opensearch` AND
        `opensearch.auth=basic` (no-op for in-app fallback / SigV4). Calls the existing
        `OpenSearchClient` (`_cluster/health`/ping, short timeout); emits `OpenSearchNodeUp` (1/0),
        `OpenSearchClusterStatus` (green=2/yellow=1/red=0), `OpenSearchHealthProbeLatencyMs` via
        `MetricsService`. Failure/timeout → emit `OpenSearchNodeUp=0` and swallow (never throws).
  - [ ] 6.3 Instrument request-scoped call sites (log-only, no control-flow change):
        `RecipeController` (BedrockLatencyMs/BedrockFailure by model, reusing `generationMs`);
        `AsyncImageService` (ImageLatencyMs/ImageRetryCount/ImageFinalFailure by imageModel).
  - [ ] 6.4 Instrument search: `OpenSearchCatalogSearchService` + `InAppCatalogSearchService`
        (add a latency bracket around the search/rank call → SearchLatencyMs, SearchMode,
        SearchEmbedFallback on the `embedQuietly` null path).
  - [ ] 6.5 Instrument SSE: `StatsSseService` + `ImageSseService` (SseActiveEmitters gauge =
        `emitters.size()`, SseBroadcastFailure on send error).
  - [ ] 6.6 Add `monitoring.metrics.enabled=false` (+ `monitoring.metrics.transport=emf`) to
        `application.properties`; set `true` only in the deployed task via a new ECS env var wired
        from `enable_monitoring`.
  - _Requirements: 4.2, 4.3, 4.4, 6.1, 6.2, 6.3, 6.4, 6.5, 10.2_

- [ ] 7. Tests
  - [ ] 7.1 `MetricsServiceTest`: no-op when flag off; correct EMF JSON shape (namespace,
        dimensions, metric name/unit/value) when on; emit swallows exceptions.
  - [ ] 7.2 `OpenSearchHealthProbeTest`: emits `OpenSearchNodeUp=0` (does NOT throw) on a probe
        failure; is a no-op when the backend isn't the basic-auth OCI node; emits status mapping
        on a healthy response.
  - [ ] 7.3 Confirm existing tests unchanged (`CatalogControllerTest`, search/ingest tests) — the
        metrics additions + probe are behind the flag and must not alter behavior.
  - [ ] 7.4 `terraform validate` + `terraform fmt -check`; assert `enable_monitoring=false` yields
        an empty plan (Req 10.1).
  - _Requirements: 6.3, 4.3, 10.1, 10.3_

- [ ] 8. Verification, docs, runbook
  - [ ] 8.1 Full `./mvnw test` green; `terraform validate`/`fmt` clean; defaults create no infra.
  - [ ] 8.2 Write `RUNBOOK.md`: enable in dev → confirm the SNS email subscription (manual click)
        → deploy → verify dashboard widgets populate → trigger a test alarm → verify a forced
        image-gen failure increments `ImageGenFinalFailures` → **stop the OCI node briefly and
        confirm `OpenSearchNodeUp` flips to 0 and the node-down alarm fires** → disable/rollback.
        Include the cost/free-tier note, the NAT data-processing cost note, and Logs Insights saved
        queries for the `AUDIT` logger.
  - [ ] 8.3 Isolation check via `git diff --stat`: only the new `monitoring` module, the additive
        `nat_gateway_id` output, additive root wiring, the additive IAM statement (only if
        `PutMetricData` chosen), the opt-in `MetricsService` + `OpenSearchHealthProbe` + guarded
        call-site edits, and properties/tfvars. No change to Bedrock/search request logic.
  - [ ] 8.4 Update README's infrastructure/monitoring section to mention the CloudWatch suite, the
        NAT + OCI-node coverage, and how to enable it.
  - _Requirements: 10.2, 10.3, 10.4, 9.1_

- [ ] 9. (Optional, later) X-Ray distributed tracing
  - [ ] 9.1 Add `enable_xray` flag + `xray:PutTraceSegments`/`PutTelemetryRecords` on the task
        policy; add an ADOT sidecar to the backend task def; instrument the backend.
  - [ ] 9.2 Confirm ServiceLens shows the ALB→ECS→Bedrock/DynamoDB map; add X-Ray to the budget
        cost filter. Note the OCI node won't appear as a segment unless the OpenSearch client calls
        are separately instrumented (best-effort). Base suite must remain usable with
        `enable_xray=false`.
  - _Requirements: 11.1, 11.2, 8.3_

## Notes
- **Ship order:** Tasks 1–5 (infra layer) deliver a working single-pane dashboard + alarms
  (including NAT) with ZERO app changes. Task 6 (app EMF metrics + OCI-node health probe) enriches
  it and provides the only view of the Oracle-hosted node. Task 9 (X-Ray) is optional/later.
- **Free-tier discipline:** this revision pushes to ~17–18 alarms and ~10–14 custom series (design
  §9), a few dollars beyond free tier. Keep the dashboard at 1.
- **No OpenSearch-cutover dependency anymore:** the cutover to the OCI node is already done in dev;
  the Serverless alarms/widgets simply no-op via `enable_opensearch=false`.
