# Implementation Plan — CloudWatch Monitoring Suite

Build a native CloudWatch observability layer for the app in two independently-shippable layers:
an **infra layer** (pure Terraform: SNS, alarms, log-metric-filters, dashboard, budget) and an
**app layer** (opt-in EMF custom metrics). Everything is gated `enable_monitoring=false` by default
with the `count = enable ? 1 : 0` pattern, matching the OpenSearch module. Nothing outside the new
`monitoring` module + additive wiring changes.

> **Decisions (from design):** CloudWatch (not Datadog) on cost (~$5–15 vs ~$180–200/mo). App
> metrics via **EMF-over-logs** (no new IAM, non-blocking) rather than Micrometer/PutMetricData.
> X-Ray is a separate opt-in flag, out of the base suite. The infra layer ships first and works
> with zero app changes.
>
> **Sequencing:** Do this AFTER the OpenSearch cutover is complete. The OpenSearch alarms/widgets
> are gated on `enable_opensearch`, so they no-op cleanly if OpenSearch isn't live yet.
>
> **Rollback:** `enable_monitoring=false` + `terraform apply` removes all monitoring resources;
> `monitoring.metrics.enabled=false` disables app emission. Neither touches request handling.

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
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 8.1_

- [ ] 2. Infrastructure metric alarms
  - [ ] 2.1 ECS alarms: backend service CPU + memory utilization
        (`AWS/ECS`, dims ClusterName/ServiceName). Thresholds via vars (defaults 80%).
  - [ ] 2.2 ALB alarms: 5XX count (target + ELB), unhealthy host count on backend TG, and p95/p99
        `TargetResponseTime` (`AWS/ApplicationELB`, LoadBalancer/TargetGroup dims). Thresholds via
        vars.
  - [ ] 2.3 DynamoDB throttle alarms via `for_each` over the recipes/catalog/catalog-full table
        names (`AWS/DynamoDB` throttle metrics). catalog-full only when its name is non-empty.
  - [ ] 2.4 Bedrock invocation-error alarm (`AWS/Bedrock` InvocationClientErrors/ServerErrors /
        throttles as available).
  - [ ] 2.5 OpenSearch OCU + search-error alarm, `count = local.enabled && var.enable_opensearch
        ? 1 : 0` (no-op until OpenSearch is live).
  - [ ] 2.6 All alarms: `alarm_actions = [aws_sns_topic.alarms[0].arn]`, `GreaterThanThreshold`,
        tags `{ Name, Environment, Project }` — identical shape to the WAF alarm.
  - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.8_

- [ ] 3. Log-derived metrics (no app change required)
  - [ ] 3.1 `aws_cloudwatch_log_metric_filter.image_gen_failures` on the backend log group,
        pattern matching the `AsyncImageService` final-failure line → `ImageGenFinalFailures`
        metric in `RecipeAiFinder/App`.
  - [ ] 3.2 `bedrock_retry_exhausted` filter on the `BedrockService` retry-exhaustion line →
        `BedrockRetryExhausted`. Add alarms on both (action = SNS topic).
  - [ ] 3.3 Preserve existing 30-day retention on the log groups (no change).
  - _Requirements: 5.1, 5.3, 2.7_

- [ ] 4. Consolidated dashboard
  - [ ] 4.1 `dashboard.tf.json.tftpl` templated body: rows for ECS, ALB, DynamoDB (per table),
        Bedrock, image, search + OpenSearch (conditional), WAF, SSE — per design §7.
  - [ ] 4.2 `aws_cloudwatch_dashboard.main` rendering the template via `templatefile(...)` with
        region/account/resource-name/enable_opensearch inputs. `count = local.enabled ? 1 : 0`.
  - [ ] 4.3 Conditionally include OpenSearch + app-metric widgets based on the enable flags.
  - _Requirements: 3.1, 3.2, 3.3_

- [ ] 5. Root wiring, WAF rewire, tfvars, budget
  - [ ] 5.1 Instantiate `module "monitoring"` in `infrastructure/main.tf`, passing ECS
        cluster/service names, ALB + TG ARN suffixes, DynamoDB table names, `enable_opensearch`,
        OpenSearch collection id, `enable_monitoring`, `notification_email`, thresholds.
  - [ ] 5.2 Rewire the WAF alarm: pass `alarm_sns_topic_arn = var.enable_monitoring ?
        module.monitoring.sns_topic_arn : var.waf_alarm_sns_topic_arn` (gives the existing WAF
        alarm a live destination without changing the WAF module).
  - [ ] 5.3 Add `aws_budgets_budget.monitoring` (COST, service filter AmazonCloudWatch [+AWS X-Ray
        when enable_xray]) gated on email, matching WAF/OpenSearch budget pattern. Default $20.
  - [ ] 5.4 Add tfvars keys to dev/prod (all off): `enable_monitoring=false`,
        `monitoring_notification_email=""`, `enable_xray=false`, threshold overrides. Root
        `variables.tf` declarations to match.
  - _Requirements: 1.3, 7.1, 7.2, 7.3, 8.1, 8.2_

- [ ] 6. App custom metrics via EMF (opt-in, non-blocking)
  - [ ] 6.1 Add `MetricsService` writing one EMF JSON line per event to a `METRICS` logger, guarded
        by `@ConditionalOnProperty("monitoring.metrics.enabled", default false)`; no-op bean when
        disabled. Catch-and-log-at-debug so emission never propagates an error.
  - [ ] 6.2 Instrument call sites (log-only additions, no control-flow change):
        `RecipeController` (BedrockLatencyMs/BedrockFailure by model, reusing `generationMs`);
        `AsyncImageService` (ImageLatencyMs/ImageRetryCount/ImageFinalFailure by imageModel).
  - [ ] 6.3 Instrument search: `OpenSearchCatalogSearchService` + `InAppCatalogSearchService`
        (SearchLatencyMs, SearchMode, SearchEmbedFallback on the `embedQuietly` null path).
  - [ ] 6.4 Instrument SSE: `StatsSseService` + `ImageSseService` (SseActiveEmitters gauge,
        SseBroadcastFailure).
  - [ ] 6.5 Add `monitoring.metrics.enabled=false` to `application.properties`; set `true` only in
        the deployed task via a new ECS env var wired from `enable_monitoring`.
  - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 6.2, 8.2_

- [ ] 7. Tests
  - [ ] 7.1 `MetricsServiceTest`: no-op when flag off; correct EMF JSON shape (namespace,
        dimensions, metric name/unit/value) when on; emit swallows exceptions.
  - [ ] 7.2 Confirm existing tests unchanged (`CatalogControllerTest`, search/ingest tests) — the
        metrics additions are behind the flag and must not alter behavior.
  - [ ] 7.3 `terraform validate` + `terraform fmt -check`; assert `enable_monitoring=false` yields
        an empty plan (Req 8.1).
  - _Requirements: 4.3, 8.1, 8.3_

- [ ] 8. Verification, docs, runbook
  - [ ] 8.1 Full `./mvnw test` green; `terraform validate`/`fmt` clean; defaults create no infra.
  - [ ] 8.2 Write `RUNBOOK.md`: enable in dev → confirm the SNS email subscription (manual click)
        → deploy → verify dashboard widgets populate → trigger a test alarm → verify a forced
        image-gen failure increments `ImageGenFinalFailures` → disable/rollback. Include the
        cost/free-tier note and Logs Insights saved queries for the `AUDIT` logger.
  - [ ] 8.3 Isolation check via `git diff --stat`: only the new `monitoring` module, additive root
        wiring, additive IAM (only if option B/X-Ray later), the opt-in `MetricsService` + guarded
        call-site edits, and properties/tfvars. No change to Bedrock/search request logic.
  - [ ] 8.4 Update README's infrastructure/monitoring section to mention the CloudWatch suite +
        how to enable it.
  - _Requirements: 8.2, 8.3, 8.4_

- [ ] 9. (Optional, later) X-Ray distributed tracing
  - [ ] 9.1 Add `enable_xray` flag + `xray:PutTraceSegments`/`PutTelemetryRecords` on the task
        policy; add an ADOT sidecar to the backend task def; instrument the backend.
  - [ ] 9.2 Confirm ServiceLens shows the ALB→ECS→Bedrock/DynamoDB/OpenSearch map; add X-Ray to the
        budget cost filter. Base suite must remain fully usable with `enable_xray=false`.
  - _Requirements: 9.1, 9.2, 6.3_

## Notes
- **Ship order:** Tasks 1–5 (infra layer) deliver a working single-pane dashboard + alarms with
  ZERO app changes. Task 6 (app EMF metrics) enriches it. Task 9 (X-Ray) is optional/later.
- **Free-tier discipline:** keep custom metrics near ~10 and dashboards at 1 to stay mostly free.
- **Depends on:** OpenSearch cutover complete (so `enable_opensearch=true` widgets/alarms are real);
  otherwise those pieces no-op via their gate.
