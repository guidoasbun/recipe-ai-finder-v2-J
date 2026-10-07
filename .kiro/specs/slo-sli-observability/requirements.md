# Requirements — SLOs & SLIs (Service Level Objectives / Indicators)

## Introduction

The app already has a consolidated **CloudWatch monitoring suite** (see
`.kiro/specs/cloudwatch-monitoring`): an SNS channel, native-service alarms (ECS, ALB, DynamoDB,
Bedrock, NAT gateway), OCI-OpenSearch node-health metrics, log-metric filters, a single dashboard,
and a cost budget. What it does **not** have is any notion of a **Service Level** — a target the
service commits to, measured over a window, with an **error budget** and **burn-rate** alerting.

Today's alarms are **static-threshold** (e.g. "p95 latency > 3s for 3 periods", "5XX count > 10 in
5 min"). They answer "is this metric spiky right now?" but not "are we meeting the reliability we
promised this month, and how fast are we burning the budget to miss it?". This spec adds that layer:

- **SLIs (Service Level Indicators):** the measured ratios — *good events ÷ valid events* — for
  availability and latency, derived from signals that already exist (ALB request/5XX counts, ALB
  `TargetResponseTime`, and the app's EMF metrics).
- **SLOs (Service Level Objectives):** the targets over a rolling window (e.g. "99.5% of requests
  succeed over 28 days", "95% of requests complete under 1.5s over 28 days"), each with an
  **error budget** and **multi-window multi-burn-rate** alerts wired to the existing SNS topic.

This is **additive and opt-in**, mirrors the existing module conventions, and changes no request
handling. It builds on the monitoring module rather than replacing any of it: static alarms stay
(they catch acute spikes); SLOs add the slow-burn / budget view the static alarms miss.

### Grounding: what already exists (verified against the codebase)

- **Monitoring module** (`infrastructure/modules/monitoring/`) is gated `enable_monitoring=false`
  by default (`count = enable ? 1 : 0`), exposes `sns_topic_arn` + `dashboard_name` outputs, and
  already creates an ALB p95 latency alarm (`TargetResponseTime`, `extended_statistic = p95`) and
  ALB 5XX alarms (`HTTPCode_Target_5XX_Count`, `HTTPCode_ELB_5XX_Count`) against
  `AWS/ApplicationELB` with `LoadBalancer = var.alb_arn_suffix` and
  `TargetGroup = var.backend_tg_arn_suffix`. These are the raw signals the availability/latency
  SLIs are computed from.
- **App EMF metrics** publish to the `RecipeAiFinder/App` namespace (opt-in via
  `monitoring.metrics.enabled`): Bedrock/image/search latency and failure counts, SSE counts, and
  the OCI OpenSearch node-health metrics. These feed the **dependency** SLIs (recipe generation,
  catalog search).
- The ALB request-count metric (`RequestCount`) and per-code counts
  (`HTTPCode_Target_2XX/3XX/4XX/5XX_Count`) are standard `AWS/ApplicationELB` metrics available
  without any app change — the natural source for the availability SLI denominator/numerator.
- The dashboard is templated JSON (`dashboard.tf.json.tftpl`) rendered via `templatefile(...)`,
  so SLO widgets slot in the same way existing widgets do.

### Decision to confirm during design (not assumed here)

CloudWatch offers two ways to express an SLO:

1. **Application Signals SLOs** (`aws_applicationsignals_service_level_objective`) — first-class
   SLO objects with error budget + burn-rate built in, but they generally require Application
   Signals instrumentation / discovered services (ADOT or the CloudWatch agent), which this app
   does **not** run today.
2. **Metric-math + composite alarms** — compute the SLI as a CloudWatch **metric-math** expression
   (good ÷ valid), publish it, and implement error-budget + multi-window multi-burn-rate alerting
   with `aws_cloudwatch_metric_alarm` (metric-math) and `aws_cloudwatch_composite_alarm`. No new
   instrumentation; works directly off the ALB + EMF metrics already present.

The design SHALL choose one (the grounding above points at option 2 as the no-new-instrumentation
path) and justify it. Requirements below are written to be transport-neutral.

## Requirements

### Requirement 1 — Define the SLIs (what we measure)
**User Story:** As the operator, I want precisely-defined indicators, so that an SLO target refers
to an unambiguous measured ratio rather than a vague "is it up?".

#### Acceptance Criteria
1. Each SLI SHALL be defined as **good events ÷ valid events** over a period, with the numerator,
   denominator, and the metric source for each stated explicitly.
2. The spec SHALL define, at minimum, these SLIs:
   - **Availability SLI** — valid = ALB `RequestCount` (or sum of per-code counts);
     good = valid minus `HTTPCode_Target_5XX_Count` and `HTTPCode_ELB_5XX_Count`. (4XX is a client
     error and SHALL be excluded from "bad" by default; the design SHALL state this choice.)
   - **Latency SLI** — good = count of requests with `TargetResponseTime` under a latency
     threshold (the SLO's objective threshold, e.g. 1.5s); valid = total request count. The design
     SHALL state how the "under threshold" count is obtained in CloudWatch (percentile-based
     approximation vs a proper count), since ALB does not emit a native below-threshold counter.
   - **Recipe-generation success SLI** — good = successful Bedrock generations;
     bad = `BedrockFailure` / `BedrockRetryExhausted`; valid = total generation attempts, from the
     `RecipeAiFinder/App` EMF metrics.
   - **Catalog-search success SLI** — good = searches that returned without error; valid = total
     searches, from the `RecipeAiFinder/App` search metrics.
3. Each SLI SHALL state its measurement window granularity (the period over which good/valid are
   summed, e.g. per-minute) separate from the SLO compliance window (Requirement 2).
4. Where an SLI needs a metric that is not emitted today (e.g. an explicit per-request
   below-threshold latency counter, or a Bedrock *attempt* count to use as the latency/success
   denominator), the requirement to emit it SHALL be called out so the design can either add a
   small EMF counter or document the approximation used instead. Any such app-side addition SHALL
   be opt-in and non-blocking (same discipline as the existing EMF metrics).

### Requirement 2 — Define the SLOs (targets, windows, error budgets)
**User Story:** As the operator, I want explicit reliability targets with an error budget, so that
I can reason about how much failure is acceptable before I must act.

#### Acceptance Criteria
1. Each SLO SHALL bind one SLI to a **target objective** (a percentage, e.g. 99.5%) over a
   **rolling compliance window** (configurable; default 28 days — the design SHALL justify 28d vs
   30d / 7d).
2. The spec SHALL define, at minimum:
   - **Availability SLO** — e.g. ≥ 99.5% of valid requests succeed over 28 days.
   - **Latency SLO** — e.g. ≥ 95% of requests complete under a configured threshold over 28 days.
   - **Recipe-generation SLO** and **Catalog-search SLO** — target success rates over the window
     (these depend on Bedrock / the OCI node, so the design SHALL note they are partly bounded by
     third-party / self-hosted dependency reliability and MAY use looser targets).
3. Every target, latency threshold, and compliance window SHALL be a **module variable with a
   sensible default**, overridable per environment (same pattern as the existing alarm thresholds).
4. The **error budget** for each SLO SHALL be defined as `1 − target` over the window, and the
   design SHALL show how remaining budget is computed/visualized (e.g. a metric-math expression).
5. SLOs SHALL be documented in a single table (SLI → objective → window → error budget → owner)
   in the design, so the committed targets are reviewable in one place.

### Requirement 3 — Burn-rate & error-budget alerting
**User Story:** As the operator, I want to be paged when I'm burning the error budget too fast,
not just when a raw metric spikes, so that alerts correlate with the reliability I promised.

#### Acceptance Criteria
1. Each SLO SHALL have **multi-window, multi-burn-rate** alerting (the Google SRE pattern): at
   least a **fast-burn** alert (short window, high burn rate — e.g. 14.4× over 1h, page-worthy) and
   a **slow-burn** alert (longer window, lower burn rate — e.g. 3× over 6h, ticket-worthy). The
   design SHALL state the exact windows/rates and SHALL make burn-rate thresholds configurable.
2. Burn-rate alerts SHALL be implemented as CloudWatch alarms (metric-math on the SLI) and, where a
   fast+slow pairing is required to reduce false positives, a `aws_cloudwatch_composite_alarm`
   combining the two windows.
3. All SLO alarm `alarm_actions` (and `ok_actions`) SHALL point at the **existing monitoring SNS
   topic** (`module.monitoring.sns_topic_arn`) — no new notification channel.
4. SLO alarms SHALL be clearly named and tagged (`Name`, `Environment`, `Project`) and
   distinguishable from the existing static alarms (e.g. an `slo-` name prefix), so an operator can
   tell a budget-burn alert from a raw-threshold alert at a glance.
5. The design SHALL explain how burn-rate alerting **coexists with** (does not duplicate) the
   existing static ALB latency/5XX alarms, and why keeping both is intentional (acute spike vs
   budget burn).

### Requirement 4 — SLO visualization
**User Story:** As the operator, I want to see each SLO's attainment and remaining error budget on
the dashboard, so that reliability status is visible alongside raw metrics.

#### Acceptance Criteria
1. The existing consolidated dashboard SHALL gain an **SLO section** (added to
   `dashboard.tf.json.tftpl`) showing, per SLO: current attainment vs target over the window and
   remaining error budget, plus the current burn rate.
2. SLO widgets SHALL render only when the SLO feature is enabled (its own flag), and SHALL degrade
   gracefully (be omitted) when the underlying app EMF metrics are off for the dependency SLOs.
3. The dashboard SHALL stay a **single dashboard** (free-tier discipline): the SLO section is added
   to the existing dashboard, not a new one, unless the design justifies a separate SLO dashboard
   and accounts for the cost.

### Requirement 5 — Opt-in, isolation, cost
**User Story:** As the operator, I want to turn SLOs on safely and know they changed nothing else
and cost almost nothing.

#### Acceptance Criteria
1. The SLO layer SHALL be gated by its own flag (e.g. `enable_slo`, default `false`) with the
   `count = enable ? 1 : 0` pattern; `enable_slo=false` SHALL provision zero SLO resources
   (`terraform plan` shows no SLO changes).
2. `enable_slo=true` SHALL require `enable_monitoring=true` (it reuses the SNS topic + dashboard);
   the module SHALL fail fast with a clear message if `enable_slo=true` while monitoring is off.
3. The SLO layer SHALL add **no change to request handling**. If a new app-side EMF counter is
   needed for an SLI (Requirement 1.4), it SHALL be opt-in, non-blocking, swallow-and-log on error,
   and gated behind the existing `monitoring.metrics.enabled` flag — no new always-on code path.
4. The design SHALL estimate the incremental cost (extra alarms, composite alarms, metric-math,
   any new custom metrics) against the CloudWatch free tier and the monitoring budget, and SHALL
   keep the dashboard count at 1 where practical.
5. `terraform validate` + `terraform fmt -check` SHALL pass; any app change SHALL compile and
   existing tests SHALL remain green.

### Requirement 6 — Documentation & runbook
**User Story:** As the operator, I want to know what the targets are and what to do when a
burn-rate alert fires.

#### Acceptance Criteria
1. The SLO table (Requirement 2.5) SHALL be documented (design + README/runbook).
2. A runbook section SHALL describe: enabling the flag → confirming SLO widgets populate →
   interpreting a fast-burn vs slow-burn alert → what remaining-error-budget means → how to adjust
   a target/window → disable/rollback.
3. The runbook SHALL state the review cadence for targets (e.g. revisit quarterly) and that initial
   targets are **provisional** until real traffic data validates them, so the numbers aren't
   mistaken for hard commitments on day one.
