# Tasks — Meal Plan Core

> Status: all tasks complete. Backend tests pass (`./mvnw test`), frontend tests pass
> (`npm run test`), and the Terraform change was applied to the live (dev) environment —
> `recipe-ai-dev-meal-plans` table is ACTIVE and the backend task definition carries
> `DYNAMODB_MEAL_PLANS_TABLE`.

Implementation plan. Each task is incremental and test-backed. Requirement references map
to `requirements.md`; design references map to `design.md`. Backend precedes frontend so
the API contract is real before the UI consumes it. Nothing here modifies the `Recipe` or
`CatalogRecipe` models.

- [x] 1. Add meal-plan configuration
  - Add `dynamodb.meal-plans-table=${DYNAMODB_MEAL_PLANS_TABLE:recipe-ai-dev-meal-plans}`
    to `backend/src/main/resources/application.properties` (and the local override file),
    mirroring the existing `dynamodb.*` entries.
  - Add limits config `mealplans.max-per-user` (default 100) and
    `mealplans.max-entries-per-plan` (default 500), env-override style like the existing
    `recipes.search.page-size-*` entries.
  - _Requirements: 1.1, 1.2, 8.3 | Design: §2.1, §7.4_

- [x] 1a. Terraform infrastructure changes (mirror the compliance spec's task 4.5)
  - Add an `aws_dynamodb_table.meal_plans` resource to
    `infrastructure/modules/dynamodb/main.tf`: `hash_key = mealPlanId` (S), attribute
    `ownerUserId` (S), GSI `ownerUserId-index` (hash `ownerUserId`, projection ALL),
    `PAY_PER_REQUEST`, Name/Environment tags — a near-copy of `aws_dynamodb_table.recipes`.
  - Add outputs `meal_plans_table_name` / `meal_plans_table_arn` to
    `infrastructure/modules/dynamodb/outputs.tf`.
  - Update the IAM module so the ECS task role has read/write on the meal-plans table AND
    its `ownerUserId-index` (grant the table arn + `/index/*`), matching how the other
    tables are granted.
  - Inject `DYNAMODB_MEAL_PLANS_TABLE` into the ECS task definition (env var), matching the
    other `DYNAMODB_*` env vars; wire the new output through the root `main.tf`.
  - (Optional) Add the table to the monitoring module's `dynamodb_table_names` list for
    throttling alarms, consistent with the existing tables.
  - Run `terraform plan` and confirm ONLY expected changes: 1 new DynamoDB table, IAM
    policy update, ECS task-definition update. No destructive changes to existing
    resources.
  - _Requirements: 1.1, 1.2, 5.4 | Design: §2.1_

- [x] 2. Define shared enums
  - Add `MealSlot` (`BREAKFAST, LUNCH, DINNER, SNACK`) and `RecipeSource` (`SAVED,
    CATALOG`) enums under `model/enums`.
  - _Requirements: 1.8, 3.2 | Design: §1.5_

- [x] 3. Define the `MealPlan` domain model
  - Add `@DynamoDbBean` `MealPlan` (`mealPlanId` PK, `ownerUserId`
    `@DynamoDbSecondaryPartitionKey("ownerUserId-index")`, `name`, `startDate`, `endDate`,
    `servings`, `entries`, `createdAt`, `updatedAt`), embedded `MealPlanEntry`
    (`entryId, date, slot, recipeRef, servings`) and `RecipeRef` (`source, recipeId`) as
    nested `@DynamoDbBean`s.
  - Reserve (declare but do not populate/read) the `members` shape for future sharing.
  - _Requirements: 1.1–1.9 | Design: §1_

- [x] 4. Implement `MealPlanRepository`
  - Hand-written repository mirroring `RecipeRepository`: `save`, `findById`,
    `findByOwner` (query `ownerUserId-index`), `delete`. Table name via `@Value`.
  - _Requirements: 1.1, 1.2, 2.2 | Design: §2.2_

- [x] 5. Define DTOs and validated request objects
  - `MealPlanDto`, `MealPlanEntryDto` (Lombok `@Data/@Builder`, resolved recipe view
    reusing `RecipeDto`/`CatalogRecipeDto` or a slim projection).
  - `CreateMealPlanRequest`, `UpdateMealPlanRequest`, `AddEntryRequest`,
    `UpdateEntryRequest` with Jakarta validation (`@NotBlank/@Size` name, ISO dates,
    `@Positive @Max` servings, slot/source validated against enums).
  - _Requirements: 2.7, 3.1, 3.2, 1.6, 1.7 | Design: §4.1_

- [x] 6. Implement `MealPlanService` — plan CRUD + ownership + limits + account status
  - Create/list/get/update/delete scoped to the acting `userId`; not-owned/absent throws
    the existing `ResourceNotFoundException` (non-disclosing). Set `createdAt`/`updatedAt`;
    validate + bound servings (no scaling).
  - Enforce `mealplans.max-per-user` on create and `mealplans.max-entries-per-plan` on
    entry add, rejecting past the cap via the standard validation/exception path.
  - Gate write operations on account status: refuse when `PENDING_DELETION` /
    `DELETION_FAILED`, mirroring `RecipeController.generate` (reads unaffected; no
    `AI_DATA_PROCESSING` consent check).
  - _Requirements: 2.1–2.8, 5.2, 5.3, 7.1, 7.2, 8.1, 8.2, 8.4 | Design: §3.1, §3.4, §7.1, §7.4_

- [x] 7. Implement entry mutations + reference validation
  - Add/move/remove entries via read-modify-write on the plan item, targeting by
    `entryId`. On add/move, validate the `recipeRef` resolves and is accessible (`SAVED` →
    `RecipeService.getRecipeById(userId)`; `CATALOG` → `CatalogSearchService.findById`);
    reject dangling references. Per-entry servings override falls back to plan-level.
    Allow the same recipe in multiple entries.
  - _Requirements: 3.1, 3.1a, 3.2, 3.3, 3.4, 3.5, 3.6 | Design: §3.2_

- [x] 8. Implement recipe resolution on read (graceful degradation)
  - Resolve each entry's `recipeRef` to a display view, batched by source and de-duped by
    id. Missing/inaccessible recipe → "unavailable" marker; the rest of the plan still
    returns. Reuse `RecipeService.toDtoFor` for saved recipes so image side effects match.
  - _Requirements: 3.7, 4.4, 4.5, 9.1, 9.2 | Design: §3.3_

- [x] 9. Wire `MealPlanController`
  - REST under `/api/meal-plans` with the endpoints in Design §4; `userId` from the JWT
    `sub` claim; `{id}`/`{entryId}` `@Pattern`-validated; errors via
    `GlobalExceptionHandler`. No changes to existing controllers or `SecurityConfig`
    (already authenticates everything but health/privacy/terms).
  - _Requirements: 2.*, 3.*, 5.1, 5.4, 9.3 | Design: §4_

- [x] 9a. Integrate meal plans into account hard-deletion
  - Add a meal-plans deletion step to `AccountDeletionService.executeHardDeletion` BEFORE
    the user-record step: `mealPlanRepository.findByOwner(userId)` → delete each; on
    failure call `markDeletionFailed(user, "meal_plan_deletion", ...)` and rethrow, matching
    the existing recipe-step shape. (No S3 cleanup — plans own no images.)
  - _Requirements: 7.3, 7.5 | Design: §7.2_

- [x] 9b. Include meal plans in data export
  - Add a `MealPlanExportData` shape to `DataExportJson` and a `mealPlans` list to
    `DataExportService.buildDataExportJson` (via `findByOwner(userId)`), mapping plan
    attributes + entries by recipe id (no recipe content copied). Ensure the ZIP path
    (async worker) carries it too; add plan count to the `DATA_EXPORT_COMPLETED` audit
    metadata.
  - _Requirements: 7.4, 7.5 | Design: §7.3_

- [x] 10. Backend tests
  - Service unit tests: ownership (not-owned → not-found), reference validation (dangling
    rejected), graceful resolution (missing recipe → unavailable, rest returns), servings
    validation + override fallback, entry move/remove by `entryId`, same recipe in
    multiple entries.
  - Controller slice tests: auth required; bad slot/source → 400; other user's plan → 404;
    response shapes.
  - Limits: create past `max-per-user` → rejected; add entry past
    `max-entries-per-plan` → rejected. Account status: write refused when
    PENDING_DELETION/DELETION_FAILED.
  - Compliance: hard-deletion removes the user's plans (and marks failure on error);
    data export includes the user's plans (by recipe id, no content copied).
  - Repository round-trip tests (save/findById/findByOwner/delete).
  - Run `./mvnw test` in `backend/` and fix failures.
  - _Requirements: 1–9 (verification) | Design: §6, §7_

- [x] 11. Frontend types + shared slot constant
  - Add `frontend/types/mealPlan.ts` (`MealPlan`, `MealPlanEntry`, `RecipeRef`) and a
    `MealSlot` constant mirroring the backend enum (one source of truth, like
    `lib/dietary.ts`).
  - _Requirements: 1.8 | Design: §5.4_

- [x] 12. Plans list page
  - `frontend/app/(protected)/meal-plans/page.tsx`: list the user's plans + "New plan"
    (create → navigate to the plan). Mobile-first Tailwind; reuse card/list patterns from
    `recipes`/`browse`; race-safe fetch.
  - _Requirements: 2.1, 2.2, 6.1 | Design: §5_

- [x] 13. Responsive calendar view
  - `meal-plans/[id]/page.tsx`: render the plan by date + slot. Mobile base layout =
    single-day / vertical day list, stacked slots, touch day-navigation, no horizontal
    scroll; `md:`/`lg:` may widen to multi-day. Empty slots show a touch "＋ Add".
    Unavailable entries show a graceful placeholder. Race-safe fetch on open.
  - Post-verification refinement: each available entry links to its existing recipe detail
    page (catalog → `/browse/[id]`, saved → `/recipes/[id]`) so users can open the full
    recipe from the calendar; the remove button stays a separate control; unavailable
    entries are non-clickable.
  - _Requirements: 4.1, 4.2, 4.4, 4.5, 4.7, 6.1, 6.2, 6.6 | Design: §5.1_

- [x] 14. Touch-first add-a-recipe picker
  - Full-screen / bottom-sheet picker reusing existing search (saved `GET /api/recipes?q=`
    and catalog `GET /api/catalog/search?q=`, with a source toggle). Picking a result adds
    an entry to the chosen date/slot. Fully tap-operable; drag-and-drop not required.
  - Post-verification fix: normalize search results to `{id, title, imageUrl}` per source
    and drop items with a blank id, so the picker never renders a duplicate/empty React key
    or adds an entry with an empty `recipeId`.
  - _Requirements: 4.3, 6.3, 6.4, 6.5, 9.3 | Design: §5.2_

- [x] 15. Entry actions + servings editing (touch)
  - Per-entry move/remove via a tap menu (no drag dependency); edit plan-level servings and
    optional per-entry override in the UI (display only — no quantity scaling). Optimistic
    or refetch update of local plan state.
  - _Requirements: 2.6, 2.7, 3.1a, 3.3, 3.4, 6.3, 6.5 | Design: §5.2, §5.3_

- [x] 16. Frontend tests
  - Vitest: calendar renders entries into slots; empty slot shows add; mobile layout has no
    horizontal scroll; touch add flow places a recipe; unavailable entry shows placeholder;
    race-safe fetch on plan open.
  - _Requirements: 4.*, 6.* (verification) | Design: §6_

- [x] 17. RUNBOOK
  - Add `.kiro/specs/meal-planner/RUNBOOK.md`: table + GSI creation, config/env vars, how
    to exercise the endpoints, and a note that visual/layout details are expected to be
    refined after hands-on use (no backend change needed for pure UI tweaks).
  - _Requirements: 1.1, 1.2 | Design: §2.1, "UI iteration" note_

---

## Revision R1 — Unified calendar + meal-prep spans (complete)

Post-hands-on rework (roadmap "Option A") + meal prep. Backend tests pass (202) and frontend
tests pass (40). No new infrastructure.

- [x] R1.1 Backend: add `spanDays` to `MealPlanEntry` + `MealPlanEntryDto` +
  `Add/UpdateEntryRequest` (`@Positive @Max(31)`), default 1, carried through the resolver,
  `MealPlanExportMapper`, and `DataExportJson`. Additive/non-breaking.
- [x] R1.2 Backend: `getOrCreateDefaultPlan(userId)` + `GET /api/meal-plans/default`
  (reuse most-recently-updated plan, else create; folds in old multi-plan data).
- [x] R1.3 Backend tests: span defaulting/persist/update, null-span → 1 on read,
  default-plan create vs reuse. `./mvnw test` green.
- [x] R1.4 Frontend: `lib/calendar.ts` pure date/grid/span helpers (+ `calendar.test.ts`);
  `spanDays` + `getDefaultMealPlan` in types/api.
- [x] R1.5 Frontend: unified `/meal-plans` calendar with Week/Month toggle (month default on
  desktop, week on phones); removed the old `[id]` detail route and plans-list page.
- [x] R1.6 Frontend: meal-prep span selector in the (relocated) `RecipePicker`; add flow
  passes `spanDays`.
- [x] R1.7 Frontend: multi-day rendering (title + meal-prep badge on start day, leftovers on
  continuation days; remove as a unit).
- [x] R1.8 Frontend tests + lint/typecheck green.
- [x] R1.9 Spec updated (requirements/design/tasks/RUNBOOK).
