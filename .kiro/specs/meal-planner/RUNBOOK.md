# Runbook — Meal Plan Core

Operational guide for the meal planner's first spec: infrastructure, configuration,
exercising the API, and how the compliance hooks behave. See `design.md` for the why.

## 1. Infrastructure (Terraform — you apply it)

The meal planner needs one new DynamoDB table. All infra is Terraform in
`infrastructure/`; nothing is created by the app at runtime. The code changes are written
and `terraform validate` passes, but **`terraform apply` is run by the operator**.

Changes made (for review before apply):

- `infrastructure/modules/dynamodb/main.tf` — new `aws_dynamodb_table.meal_plans`:
  - Partition key `mealPlanId` (S), `PAY_PER_REQUEST`.
  - GSI `ownerUserId-index` on `ownerUserId` (S), projection `ALL` — a user's plans are one
    query (mirrors the recipes table's `userId-index`).
  - Only the PK and GSI-key attributes are declared; every other field
    (entries, servings, dates, …) is schemaless, so later specs that add fields need **no**
    infra change.
- `infrastructure/modules/dynamodb/outputs.tf` — `meal_plans_table_name`,
  `meal_plans_table_arn`.
- `infrastructure/modules/ecs/` — new `dynamodb_meal_plans_table` variable, injected as the
  `DYNAMODB_MEAL_PLANS_TABLE` container env var.
- `infrastructure/main.tf` — wires the table-name output into the ECS module and adds the
  table to the monitoring module's throttling-alarm list.
- **IAM: no change.** The ECS task policy already grants DynamoDB actions on
  `Resource = "*"`, so the new table + its GSI are covered.

Apply:

```
cd infrastructure
terraform plan    # expect: 1 new DynamoDB table + an ECS task-definition update; no destructive changes
terraform apply
```

After apply, redeploy the backend so ECS picks up the new task definition (the backend
reads the table name from `DYNAMODB_MEAL_PLANS_TABLE` at startup — no code change beyond
`application.properties`).

## 2. Configuration

Added to `backend/src/main/resources/application.properties` (env-override style):

| Property | Env var | Default | Purpose |
|---|---|---|---|
| `dynamodb.meal-plans-table` | `DYNAMODB_MEAL_PLANS_TABLE` | `recipe-ai-dev-meal-plans` | Table name |
| `mealplans.max-per-user` | `MEALPLANS_MAX_PER_USER` | `100` | Max plans a user may create |
| `mealplans.max-entries-per-plan` | `MEALPLANS_MAX_ENTRIES_PER_PLAN` | `500` | Max entries per plan (keeps the single plan item well under DynamoDB's 400 KB limit) |

Local dev (`application-local.properties`) points at `recipe-ai-dev-meal-plans`.

## 3. API

All endpoints are under `/api/meal-plans`, authenticated (Cognito JWT; `userId` is the
`sub` claim). Not-owned/absent plans return **404** (non-disclosing). Validation errors and
limit breaches return **400**; writes on a `PENDING_DELETION`/`DELETION_FAILED` account
return **403**.

| Method | Path | Body | Notes |
|---|---|---|---|
| POST | `/api/meal-plans` | `{name, startDate?, endDate?, servings?}` | Create; 201 |
| GET | `/api/meal-plans` | — | List the caller's plans (newest-updated first) |
| GET | `/api/meal-plans/{id}` | — | One plan, entries resolved to display views |
| PUT | `/api/meal-plans/{id}` | `{name, startDate?, endDate?, servings?}` | Edit plan-level attrs |
| DELETE | `/api/meal-plans/{id}` | — | 204 |
| POST | `/api/meal-plans/{id}/entries` | `{date, slot, source, recipeId, servings?}` | Add entry; 201 |
| PUT | `/api/meal-plans/{id}/entries/{entryId}` | `{date?, slot?, servings?}` | Move/edit entry |
| DELETE | `/api/meal-plans/{id}/entries/{entryId}` | — | Remove entry |

- `slot` ∈ `BREAKFAST | LUNCH | DINNER | SNACK`; `source` ∈ `SAVED | CATALOG`. Unknown enum
  values are rejected (400).
- Dates are ISO-8601 day strings (`yyyy-MM-dd`).
- Adding an entry validates the recipe reference: `SAVED` must be a recipe the caller owns;
  `CATALOG` must exist. A dangling reference is rejected rather than stored.
- On read, each entry resolves to `{title, imageUrl, available}`. If the referenced recipe
  was deleted, `available=false` and the rest of the plan still returns.

### Quick manual check (through the frontend proxy)

The browser calls go through `/api/backend/api/meal-plans` (proxy adds the auth header;
`next.config.ts` rewrites to the backend). With a valid session cookie you can exercise the
UI at `/meal-plans`. For a direct backend call you need a Cognito JWT in
`Authorization: Bearer <token>`.

## 4. Compliance behavior

- **Account deletion:** `AccountDeletionService.executeHardDeletion` deletes the user's meal
  plans (step before the user-record removal), with the same fail-and-mark
  (`markDeletionFailed(user, "meal_plan_deletion", …)`) semantics as the recipe step. Plans
  own no S3 objects, so there is no image cleanup.
- **Data export:** both the JSON export (`DataExportService`) and the async ZIP export
  (`DataExportAsyncWorker`) include a `mealPlans` list (plan attributes + entries by recipe
  id — recipe content is exported separately under `recipes`). The
  `DATA_EXPORT_COMPLETED` audit event carries a `mealPlanCount`.
- **Consent:** the planner does **not** require `AI_DATA_PROCESSING` consent (no AI here). A
  future AI-assistant spec will gate its own AI calls.

## 5. Tests

- Backend: `./mvnw test` in `backend/` — `MealPlanServiceTest`, `MealPlanControllerTest`,
  plus the meal-plan deletion property in `AccountDeletionServicePropertyTest`. Full suite
  passes.
- Frontend: `npm run test` in `frontend/` — `meal-plans/page.test.tsx` and
  `meal-plans/[id]/page.test.tsx`.

## 6. A note on the UI

The calendar's visual and layout details (single-day mobile view, bottom-sheet picker,
spacing, wording) are **intentional starting points, not fixed contracts**. Pure
look-and-feel and layout changes need **no backend change** — the API returns data; the UI
decides how to show it. Expect to refine the UI after using it on a phone.

## 7. Not in this spec (see ROADMAP)

Add-to-plan buttons on recipe cards (spec 2), grocery lists / structured ingredients
(spec 3–4), recipe enrichment (spec 5), nutrition (spec 6), the AI assistant (spec 7),
calendar export (spec 8), and shared/household plans (data shape reserved via the unused
`members` field; behavior not built).
