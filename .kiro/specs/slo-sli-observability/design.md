# Design — SLOs & SLIs

## 1. Decision: metric-math SLOs over Application Signals

| Dimension | Metric-math + composite alarms (**chosen**) | Application Signals SLO objects |
| --- | --- | --- |
| Instrumentation needed | None — reads ALB + existing EMF metrics | ADOT / CloudWatch agent + discovered services |
| New IAM | None | Agent/discovery permissions |
| Error budget + burn rate | Built by us (metric-math expressions) | First-class, built-in |
| Fit for this app | All signals already exist; opt-in; cheap | Would require standing up Application Signals first |
| Cost | A handful of extra alarms + composite alarms | SLO objects + Application Signals ingestion |

The app runs **Spring Boot 4 / Java 21 with no Micrometer, no Actuator, and no ADOT/CloudWatch
agent** (verified in the monitoring spec grounding). Application Signals SLOs
(`aws_applicationsignals_service_level_objective`) assume a *discovered service* with
Application-Signals telemetry — infrastructure this app does not have and that the cloudwatch-
monitoring spec deliberately avoided (it chose EMF-over-logs, zero new IAM). Standing that up just
to get SLO objects is disproportionate.

Instead we express each SLI as a **CloudWatch metric-math ratio** (good ÷ valid) directly over
metrics that already exist (ALB `AWS/ApplicationELB` + the `RecipeAiFinder/App` EMF metrics), and
implement error-budget + **multi-window multi-burn-rate** alerting with
`aws_cloudwatch_metric_alarm` (metric-math) + `aws_cloudwatch_composite_alarm`. No new
instrumentation, no new IAM, no new vendor. Everything lives in the existing `monitoring` module
and reuses its SNS topic and dashboard.

**Accepted tradeoff:** we hand-build the error-budget math and burn-rate windows rather than
getting them turnkey. The math is standard SRE (below) and small.

## 2. Current state (grounding — verified against the codebase)

- **Monitoring module** (`infrastructure/modules/monitoring/{main,variables,outputs}.tf`): gated
  `enable_monitoring` with `locals { enabled = var.enable_monitoring }` and
  `local.alarm_actions = [aws_sns_topic.alarms[0].arn]`. Outputs: `sns_topic_arn`, `dashboard_name`.
- **ALB signals already alarmed** (same module): `alb_target_5xx` / `alb_elb_5xx`
  (`HTTPCode_Target_5XX_Count` / `HTTPCode_ELB_5XX_Count`, Sum), `alb_p95_latency`
  (`TargetResponseTime`, `extended_statistic = p95`), all with
  `dimensions = { LoadBalancer = var.alb_arn_suffix, TargetGroup = var.backend_tg_arn_suffix }`.
  `RequestCount` and `HTTPCode_Target_2XX/4XX/5XX_Count` are standard on the same dimensions and are
  **not** emitted by the app — they come free from the ALB.
- **App EMF metrics** (`RecipeAiFinder/App`, opt-in `monitoring.metrics.enabled`): the monitoring
  spec defines `BedrockFailure`/`BedrockRetryExhausted`, `BedrockLatencyMs`, `ImageFinalFailure`,
  `SearchLatencyMs`/`SearchMode`/`SearchEmbedFallback`, SSE counts, and the OpenSearch node-health
  metrics. These are the sources for the **dependency** SLIs.
- **Dashboard**: `aws_cloudwatch_dashboard.main` rendered from `dashboard.tf.json.tftpl` via
  `templatefile(...)`, with `enable_app_metrics` / `enable_opensearch` toggles already threaded in.
  SLO widgets attach here.
- **Module conventions**: `enable_* = bool default false`, `count = enable ? 1 : 0`,
  `terraform_data` preconditions for fail-fast (the SNS-email precondition is the template to copy),
  per-env tfvars, thresholds as variables with defaults.

## 3. Architecture

```
┌──────────────────────────── SLO layer (additive, gated enable_slo) ─────────────────────────────┐
│                                                                                                  │
│  SLIs (metric-math ratios, good ÷ valid)                                                         │
│   • Availability  = (RequestCount − Target_5XX − ELB_5XX) / RequestCount        ← ALB (no app)   │
│   • Latency       = under_threshold_count / RequestCount                        ← ALB (no app)   │
│   • RecipeGen     = (attempts − BedrockFailure) / attempts                      ← RecipeAiFinder/App│
│   • CatalogSearch = (searches − search_errors) / searches                       ← RecipeAiFinder/App│
│                                                                                                  │
│  Error budget   = 1 − target   (per rolling window, default 28d)                                 │
│  Burn rate      = observed_bad_rate / (1 − target)                                               │
│                                                                                                  │
│  Alerting (per SLO): multi-window multi-burn-rate                                                │
│   • fast-burn  (1h window, 14.4× burn)  → page-worthy   ─┐                                        │
│   • slow-burn  (6h window, 3× burn)     → ticket-worthy ─┴─▶ composite alarm ─▶ SNS (existing)   │
│                                                                                                  │
│  Visualization: SLO section appended to the existing single dashboard                            │
└──────────────────────────────────────────────────────────────────────────────────────────────┘
             reuses:  module.monitoring.sns_topic_arn   ·   dashboard.tf.json.tftpl
```

The SLO layer adds **no new SNS topic, no new dashboard, no new IAM**. It is a set of metric-math
alarms + composite alarms + dashboard widgets inside the existing `monitoring` module, plus (only
if needed, §5) one small opt-in EMF counter.

## 4. SLI definitions (good ÷ valid)

All SLIs are **request/event-based** (ratio of good events over valid events), computed with
CloudWatch metric math. Each is evaluated at a 1-minute period and then aggregated over the SLO
window by the burn-rate alarms (§6) and the attainment widget (§7).

### 4.1 Availability SLI
- **Source:** `AWS/ApplicationELB`, dims `LoadBalancer=alb_arn_suffix`,
  `TargetGroup=backend_tg_arn_suffix`.
- **valid** = `RequestCount` (Sum).
- **bad** = `HTTPCode_Target_5XX_Count` + `HTTPCode_ELB_5XX_Count` (Sum). ELB 5XX included so
  "backend unreachable / no healthy host" counts against availability.
- **good** = `valid − bad`; **SLI** = `good / valid`.
- **4XX excluded** from "bad": a 4XX is a client error (bad input, auth), not a service failure.
  Documented as an explicit choice; revisit if 4XX turns out to signal server-side bugs.
- Metric-math (alarm expression), with `m_req`, `m_t5`, `m_e5` as the three metrics:
  `bad_rate = (m_t5 + m_e5) / m_req` (the SLI is `1 − bad_rate`).

### 4.2 Latency SLI
- **Goal:** fraction of requests faster than the SLO's latency threshold `L` (default 1.5s).
- **Problem:** ALB does not emit a native "count of requests under L" metric; `TargetResponseTime`
  is a distribution, queried by percentile/statistic.
- **Chosen approach (approximation, no app change):** evaluate `TargetResponseTime` at the
  **target percentile** matching the SLO. For a 95% latency SLO we alarm when the **p95 of
  `TargetResponseTime` exceeds `L`** over the window. This is the standard CloudWatch
  approximation: "95% of requests under L over the window" ⇔ "windowed p95 ≤ L". It is not a true
  event ratio, so the design records the limitation.
- **Optional exact path (if we later want a true ratio):** emit an app-side EMF counter
  `RequestLatencyUnderThreshold` (1 when a request's measured latency < L, else 0) alongside a
  `RequestCountApp` denominator, then `SLI = under / total`. This needs the opt-in app change in
  §5 and is **not** required for v1 — v1 uses the p95-threshold approximation.
- The design flags that the two approaches answer slightly different questions (percentile-of-
  distribution vs fraction-of-requests) and v1 intentionally ships the no-app-change one.

### 4.3 Recipe-generation success SLI
- **Source:** `RecipeAiFinder/App` EMF metrics (requires `monitoring.metrics.enabled=true`).
- **bad** = `BedrockFailure` + `BedrockRetryExhausted` (Sum).
- **valid** = total generation **attempts**. The monitoring spec emits failure counts but not an
  explicit attempt counter; the design adds a tiny `BedrockAttempt` EMF counter (§5) so the
  denominator exists. Until that counter is present, this SLO is **disabled** (its widgets/alarms
  are gated on `enable_app_metrics` AND the counter being emitted).
- **good** = `valid − bad`; **SLI** = `good / valid`.
- **Note:** partly bounded by Bedrock (third-party) reliability → looser target (§8).

### 4.4 Catalog-search success SLI
- **Source:** `RecipeAiFinder/App` search metrics.
- **valid** = total searches (a `SearchCount`/`SearchLatencyMs` sample count, or an added
  `SearchAttempt` counter — the design reuses the existing `SearchLatencyMs` **SampleCount** as the
  denominator to avoid a new metric where possible).
- **bad** = search errors. The monitoring spec emits `SearchEmbedFallback` (a degraded-but-served
  path, **not** a hard failure) — the design states that embed-fallback is **excluded** from "bad"
  (the search still returned results) and that a hard-error counter `SearchError` is the real "bad"
  signal, added as the one small counter in §5 if not already present.
- **SLI** = `good / valid`; partly bounded by the self-hosted OCI node → looser target (§8).

## 5. App-side additions (minimal, opt-in, non-blocking)

Only if the dependency SLIs need a denominator/error counter that the monitoring spec didn't emit:

- `BedrockAttempt` (Count, per `model` dimension) — incremented once per generation attempt in
  `RecipeController` right where `BedrockLatencyMs`/`BedrockFailure` are already emitted.
- `SearchError` (Count, per `mode`) — incremented on the hard-error path of the catalog search
  services, distinct from the existing `SearchEmbedFallback`.

Both go through the **existing `MetricsService`** from the monitoring spec: EMF-over-logs, guarded
by `@ConditionalOnProperty("monitoring.metrics.enabled")`, swallow-and-log on error, no control-
flow change, no new IAM. If the operator never enables app metrics, the dependency SLIs simply
stay off (their alarms/widgets are gated), and the two infra SLIs (availability + latency) still
work with zero app involvement. **v1 can ship availability + latency SLOs with no app change at
all;** the two dependency SLOs are a follow-on task once these counters land.

## 6. Error budget & burn-rate alerting

### 6.1 Definitions
For an SLO with target `T` (e.g. 0.995) over window `W` (e.g. 28d):
- **Error budget** = `1 − T` (e.g. 0.005 = 0.5% of events may be bad over `W`).
- **Burn rate** = `observed_bad_rate / (1 − T)`. A burn rate of `1` exactly exhausts the budget at
  the end of `W`; `14.4` exhausts it in `W/14.4` (≈ 2 days of budget in 1h for a 28d/0.5% SLO).

### 6.2 Multi-window multi-burn-rate (Google SRE pattern)
Two paired alarms per SLO, combined with a composite alarm to cut false positives (both the long
and short window must breach):

| Severity | Burn rate (default) | Long window | Short window | Budget consumed before firing |
| --- | --- | --- | --- | --- |
| **Fast burn** (page) | 14.4× | 1h | 5m | ~2% of 28-day budget |
| **Slow burn** (ticket) | 3× | 6h | 30m | ~5% of 28-day budget |

- Implemented as `aws_cloudwatch_metric_alarm` with a **metric-math** expression computing the
  bad-event rate over each window, comparing against `(1 − T) × burn_rate`.
- Each severity uses a `aws_cloudwatch_composite_alarm` requiring **both** its long- and short-
  window child alarms to be in ALARM (`ALARM(long) AND ALARM(short)`), so a brief blip on the
  short window alone doesn't page.
- Burn-rate multipliers and windows are **module variables** with the defaults above.
- `alarm_actions` / `ok_actions` = `[module.monitoring sns topic]` (the existing topic; passed in
  as it already is for the other alarms via `local.alarm_actions`).

### 6.3 Naming & coexistence with static alarms
- SLO alarms are named `…-slo-<sli>-<fast|slow>-burn` and the composites `…-slo-<sli>-burn`, tagged
  `{ Name, Environment, Project }` — the `slo-` segment distinguishes them from the existing
  `alb-latency-p95-high` / `alb-target-5xx-high` static alarms.
- **Both are kept on purpose:** the static alarms catch an *acute* spike (a sudden 5XX storm or a
  latency cliff *right now*), the SLO burn-rate alarms catch *budget erosion* (a slow, sustained
  drift that never trips a static threshold but will miss the monthly target). They answer
  different questions; the design explicitly documents that this is not duplication.

## 7. Visualization

Append an **SLO section** to `dashboard.tf.json.tftpl` (still one dashboard):
- Per SLO: a **single-value / gauge** widget showing current attainment over `W` vs target (green
  above target, red below), a **remaining error-budget** number (metric-math:
  `1 − (bad_over_window / valid_over_window) / (1 − T)` → fraction of budget left), and a
  **burn-rate** time-series.
- Dependency-SLO widgets (recipe-gen, catalog-search) render only when `enable_app_metrics=true`
  (reusing the existing template toggle); if app metrics are off they're omitted, matching how the
  OCI-node widgets already behave.
- New template vars added to the `templatefile(...)` call: `enable_slo`, the SLO targets, and the
  latency threshold, so the widgets label themselves with the committed numbers.

## 8. The SLO table (committed targets — provisional until traffic validates)

| SLI | Objective (target) | Window | Error budget | Depends on | Notes |
| --- | --- | --- | --- | --- | --- |
| Availability (non-5XX) | **99.5%** | 28 d | 0.5% | ALB / ECS | No app change; 4XX excluded |
| Latency (< `L`, default 1.5s) | **95%** | 28 d | 5% | ALB / ECS | v1 = windowed-p95 ≤ L approximation |
| Recipe generation success | **99.0%** | 28 d | 1.0% | Bedrock (3rd-party) | Needs `BedrockAttempt` counter; looser target |
| Catalog search success | **99.0%** | 28 d | 1.0% | OCI OpenSearch node | Embed-fallback excluded from "bad"; looser target |

- **Window = 28 days** (not 30): a fixed 4-week window keeps the day-of-week mix constant week to
  week, which is the common SRE choice; it's a module variable so 7d/30d are a one-line override.
- All targets/threshold/window are variables with these defaults; **they are provisional** — the
  runbook states they must be revisited against real traffic (Requirement 6.3) before being treated
  as commitments.

## 9. Terraform shape

New file `infrastructure/modules/monitoring/slo.tf` (keeps the SLO layer self-contained inside the
existing module):
- `locals { slo_enabled = var.enable_slo }` with a `terraform_data` precondition failing fast when
  `enable_slo && !enable_monitoring` (SLOs reuse the SNS topic + dashboard).
- Metric-math child alarms (`aws_cloudwatch_metric_alarm`) for each SLO × {fast,slow} × {long,short
  window} and the composite alarms (`aws_cloudwatch_composite_alarm`), all `count`/`for_each` gated
  on `local.slo_enabled` (and additionally on `enable_app_metrics` for the dependency SLOs).
- New variables in `variables.tf`: `enable_slo` (bool, false), `slo_window_days` (28),
  `slo_availability_target` (0.995), `slo_latency_target` (0.95),
  `slo_latency_threshold_seconds` (1.5), `slo_recipe_gen_target` (0.99),
  `slo_search_target` (0.99), `slo_fast_burn_rate` (14.4), `slo_slow_burn_rate` (3) — all with
  cost/rationale descriptions mirroring the existing threshold vars.
- Dashboard: extend the existing `templatefile(...)` arg map + `dashboard.tf.json.tftpl` with the
  SLO section (no new dashboard resource).
- Root `infrastructure/main.tf`: pass the new `enable_slo` + SLO vars into `module "monitoring"`;
  declare them in root `variables.tf`; add `enable_slo=false` + target overrides to dev/prod tfvars
  (all defaulting off).

## 10. Cost

- SLO adds roughly **2 child alarms + 1 composite per SLO**. For 2 infra SLOs (v1): ~4 metric
  alarms + 2 composites. With all 4 SLOs: ~8 metric alarms + 4 composites. Composite alarms are
  billed like standard alarms; metric-math alarms count their math-referenced metrics.
- No new **custom metrics** for the two infra SLOs (pure ALB metric-math). The two dependency SLOs
  reuse existing EMF metrics plus at most the two small counters in §5.
- This stays within/near the free alarm tier the monitoring spec already budgeted and well under
  the existing monitoring budget; §5.4 of requirements asks the implementation to re-estimate and
  keep the dashboard at 1. No separate SLO budget is introduced.

## 11. Testing / verification

- `terraform validate` + `terraform fmt -check`; assert `enable_slo=false` yields no SLO resources
  in the plan, and `enable_slo=true && enable_monitoring=false` fails the precondition.
- If the §5 counters are added: unit-test them the same way `MetricsServiceTest` does (no-op when
  the flag is off; correct EMF shape when on; emission swallows exceptions) and confirm existing
  tests stay green.
- Manual: enable in dev → confirm SLO widgets populate → force sustained 5XX (or lower the
  availability target) to drive a fast-burn alarm → confirm the composite fires to the existing SNS
  topic → restore → confirm `ok_actions` clear.
