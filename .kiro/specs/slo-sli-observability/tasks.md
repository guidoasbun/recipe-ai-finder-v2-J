# Implementation Plan — SLOs & SLIs

Add an SLO/SLI layer **inside the existing `monitoring` module**, in two independently-shippable
slices:

- **Slice A — infra SLOs (zero app change):** Availability + Latency SLIs/SLOs computed purely from
  ALB metrics, with error-budget + multi-window multi-burn-rate alerts and dashboard widgets.
- **Slice B — dependency SLOs (opt-in app change):** Recipe-generation + Catalog-search SLIs/SLOs,
  which need two tiny EMF counters (`BedrockAttempt`, `SearchError`) via the existing
  `MetricsService`.

Everything is gated `enable_slo=false` by default (`count = enable ? 1 : 0`), requires
`enable_monitoring=true`, reuses the existing SNS topic + single dashboard, and adds **no new IAM**.

> **Decisions (from design):** metric-math + composite alarms over Application Signals (no new
> instrumentation/IAM). SLIs are good÷valid ratios. Latency SLI v1 = windowed-p95 ≤ threshold
> approximation (true-ratio counter is an optional later path). Burn-rate: fast 14.4×/1h (page) +
> slow 3×/6h (ticket), each a composite of a long+short window alarm. Window = 28 days. All
> targets/windows/burn-rates are module variables.
>
> **Rollback:** `enable_slo=false` + `terraform apply` removes all SLO resources; it never touches
> the base monitoring suite or request handling.

- [ ] 1. SLO scaffold + gating inside the monitoring module
  - [ ] 1.1 Add `infrastructure/modules/monitoring/slo.tf` with `locals { slo_enabled =
        var.enable_slo }`.
  - [ ] 1.2 Add a `terraform_data.require_monitoring_when_slo` precondition that fails fast when
        `var.enable_slo && !var.enable_monitoring` with a clear message (SLOs reuse the SNS topic +
        dashboard), mirroring the existing `require_email_when_enabled` precondition.
  - [ ] 1.3 Add SLO variables to `variables.tf` with cost/rationale descriptions mirroring the
        existing threshold vars: `enable_slo` (bool, false), `slo_window_days` (28),
        `slo_availability_target` (0.995), `slo_latency_target` (0.95),
        `slo_latency_threshold_seconds` (1.5), `slo_recipe_gen_target` (0.99),
        `slo_search_target` (0.99), `slo_fast_burn_rate` (14.4), `slo_slow_burn_rate` (3),
        `slo_fast_burn_long_window_seconds` (3600), `slo_fast_burn_short_window_seconds` (300),
        `slo_slow_burn_long_window_seconds` (21600), `slo_slow_burn_short_window_seconds` (1800).
  - _Requirements: 2.3, 5.1, 5.2_

- [ ] 2. Availability SLO (Slice A — no app change)
  - [ ] 2.1 Metric-math bad-rate alarms over ALB (`AWS/ApplicationELB`, dims
        `LoadBalancer=alb_arn_suffix`, `TargetGroup=backend_tg_arn_suffix`):
        `bad_rate = (HTTPCode_Target_5XX_Count + HTTPCode_ELB_5XX_Count) / RequestCount`. Create the
        fast-burn pair (long 1h + short 5m) and slow-burn pair (long 6h + short 30m), comparing each
        window's bad-rate against `(1 − slo_availability_target) × burn_rate`. 4XX excluded (design
        §4.1). All `count = local.slo_enabled ? 1 : 0`.
  - [ ] 2.2 Two `aws_cloudwatch_composite_alarm`s — `availability-fast-burn`
        (`ALARM(long) AND ALARM(short)`) and `availability-slow-burn` — `alarm_actions` /
        `ok_actions = local.alarm_actions` (the existing SNS topic). Names `…-slo-availability-
        <fast|slow>-burn`; tags `{ Name, Environment, Project }`.
  - _Requirements: 1.1, 1.2, 1.3, 2.1, 2.2, 2.4, 3.1, 3.2, 3.3, 3.4, 3.5_

- [ ] 3. Latency SLO (Slice A — no app change)
  - [ ] 3.1 Burn-rate alarms using `TargetResponseTime` at the SLO percentile (p95 for a 0.95
        target) over the fast/slow long+short windows, firing when the windowed percentile exceeds
        `slo_latency_threshold_seconds` (design §4.2 approximation). Composite the long+short per
        severity exactly as in task 2.2; actions → existing SNS topic; `…-slo-latency-<fast|slow>-
        burn` names + standard tags.
  - [ ] 3.2 Add a short code comment / design pointer in `slo.tf` noting this is the windowed-p95
        approximation, and that the exact event-ratio path (optional `RequestLatencyUnderThreshold`
        counter) is deferred to Slice B territory and NOT implemented in v1.
  - _Requirements: 1.2 (latency), 1.3, 2.1, 2.2, 2.4, 3.1, 3.2, 3.3, 3.4, 3.5_

- [ ] 4. SLO dashboard section (Slice A)
  - [ ] 4.1 Extend the existing `templatefile(...)` arg map in `main.tf` (the
        `aws_cloudwatch_dashboard.main` resource) and `dashboard.tf.json.tftpl` with new vars:
        `enable_slo`, `slo_availability_target`, `slo_latency_target`,
        `slo_latency_threshold_seconds`, `slo_window_days`.
  - [ ] 4.2 Add an **SLO section** to `dashboard.tf.json.tftpl` (same single dashboard): per infra
        SLO, a single-value/gauge attainment-vs-target widget, a remaining-error-budget number
        (metric-math `1 − (bad_over_window / valid_over_window)/(1 − target)`), and a burn-rate
        time-series. Widgets rendered only when `enable_slo` is true (template conditional, mirroring
        the existing `enable_app_metrics`/`enable_opensearch` toggles). Keep it ONE dashboard.
  - _Requirements: 4.1, 4.2, 4.3, 2.4_

- [ ] 5. Root wiring + tfvars (Slice A)
  - [ ] 5.1 Pass `enable_slo` + all SLO vars from task 1.3 into `module "monitoring"` in
        `infrastructure/main.tf`.
  - [ ] 5.2 Declare the matching variables in root `infrastructure/variables.tf` (defaults off /
        the design defaults).
  - [ ] 5.3 Add `enable_slo=false` + target/threshold/window overrides to dev + prod tfvars (all
        defaulting off so the standard deployment provisions nothing).
  - _Requirements: 5.1, 5.2, 5.4_

- [ ] 6. Dependency SLO counters (Slice B — opt-in app change, non-blocking)
  - [ ] 6.1 In `RecipeController` (where `BedrockLatencyMs`/`BedrockFailure` are already emitted per
        the monitoring spec), emit a `BedrockAttempt` (Count, dim `model`) once per generation
        attempt via the existing `MetricsService`. Guarded by `monitoring.metrics.enabled`,
        swallow-and-log, no control-flow change.
  - [ ] 6.2 In the catalog search services (`OpenSearchCatalogSearchService` /
        `InAppCatalogSearchService`), emit `SearchError` (Count, dim `mode`) on the hard-error path
        only — distinct from the existing `SearchEmbedFallback` (which is a served-but-degraded
        path and is NOT counted as bad). Same guarding/discipline.
  - _Requirements: 1.4, 5.3_

- [ ] 7. Dependency SLOs (Slice B — recipe-gen + catalog-search)
  - [ ] 7.1 Recipe-generation SLO: metric-math over `RecipeAiFinder/App`
        `bad_rate = (BedrockFailure + BedrockRetryExhausted) / BedrockAttempt`; fast/slow burn-rate
        pairs + composites against `slo_recipe_gen_target`. `count = local.slo_enabled &&
        var.enable_app_metrics ? 1 : 0` (needs the EMF counters). Actions → existing SNS topic;
        `…-slo-recipe-gen-<fast|slow>-burn`.
  - [ ] 7.2 Catalog-search SLO: `bad_rate = SearchError / <search total>` (denominator = existing
        `SearchLatencyMs` SampleCount or an added `SearchAttempt`; use SampleCount if viable to
        avoid a new metric). Same gating/shape; `…-slo-search-<fast|slow>-burn`.
  - [ ] 7.3 Add the two dependency SLOs to the dashboard SLO section, gated on `enable_app_metrics`
        (omitted when app metrics are off), matching how the OCI-node widgets already behave.
  - _Requirements: 1.2 (recipe/search), 2.1, 2.2, 2.4, 3.x, 4.2_

- [ ] 8. Tests
  - [ ] 8.1 If the §6 counters are added: extend `MetricsServiceTest`-style coverage — `BedrockAttempt`
        / `SearchError` are no-ops when `monitoring.metrics.enabled=false`, produce the correct EMF
        shape (namespace, dimension, name/unit/value) when on, and swallow exceptions on emit.
  - [ ] 8.2 Confirm existing backend tests stay green (the counters are behind the flag and must not
        alter behavior).
  - [ ] 8.3 `terraform validate` + `terraform fmt -check`; assert `enable_slo=false` yields no SLO
        resources in the plan (Req 5.1) and `enable_slo=true && enable_monitoring=false` fails the
        precondition (Req 5.2).
  - _Requirements: 5.1, 5.2, 5.3, 5.5_

- [ ] 9. Verification, docs, runbook
  - [ ] 9.1 Full `./mvnw test` green; `terraform validate`/`fmt` clean; defaults create no SLO infra.
  - [ ] 9.2 Isolation check via `git diff --stat`: only `modules/monitoring/slo.tf`, additive edits
        to `modules/monitoring/{variables.tf,main.tf,dashboard.tf.json.tftpl}`, additive root
        wiring + tfvars, and (Slice B only) the two guarded EMF counter emits. No change to the base
        monitoring suite's behavior or request handling.
  - [ ] 9.3 Add the **SLO table** (design §8) and a runbook section: enable `enable_slo` → confirm
        SLO widgets populate → interpret fast-burn (page) vs slow-burn (ticket) → read remaining
        error budget → adjust a target/window/burn-rate → disable/rollback. State the targets are
        **provisional** until real traffic validates them, and set a review cadence (e.g.
        quarterly). Document why static alarms AND burn-rate alarms both exist (acute spike vs
        budget burn).
  - [ ] 9.4 Update the README monitoring section to mention the SLO/SLI layer, the four SLOs, and
        how to enable it (`enable_slo` requires `enable_monitoring`; dependency SLOs also need
        `enable_app_metrics`).
  - _Requirements: 5.5, 6.1, 6.2, 6.3_

## Notes
- **Ship order:** Tasks 1–5 (Slice A) deliver working **availability + latency** SLOs with
  error-budget burn-rate alerts and dashboard widgets, **with zero app changes**. Tasks 6–7
  (Slice B) add the recipe-generation + catalog-search SLOs once the two tiny EMF counters land.
- **No new SNS topic, no new dashboard, no new IAM** — the SLO layer reuses
  `module.monitoring.sns_topic_arn` and the existing single dashboard (free-tier discipline).
- **Targets are provisional.** The defaults (99.5% availability, 95% latency < 1.5s, 99% dependency
  success over 28d) are starting points; the runbook requires validating them against real traffic
  before treating them as commitments.
- **Coexistence, not replacement.** The existing static ALB latency/5XX alarms stay; SLO burn-rate
  alarms are additive and catch slow budget erosion the static thresholds miss.
