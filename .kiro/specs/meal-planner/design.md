# Design — Meal Plan Core

## Context

First implementation spec of the Meal Planner (see `ROADMAP.md` and `requirements.md`). It
introduces the `MealPlan` domain concept and a mobile-first calendar UI for scheduling
recipes onto days and meal slots. It reuses the existing recipe library (`Recipe`,
`CatalogRecipe`), search, and Cognito/JWT auth, and adds no fields to the recipe models.

Backend stack: Spring Boot + AWS DynamoDB (Enhanced Client). Auth is Cognito-issued JWT;
`userId` is the `sub` claim. Existing patterns this design mirrors:

- **Entity:** `@DynamoDbBean` POJO with `@DynamoDbPartitionKey` + a `userId`
  `@DynamoDbSecondaryPartitionKey` GSI (`Recipe` uses `userId-index`).
- **Repository:** hand-written, table name from a `dynamodb.*` property
  (`RecipeRepository`).
- **Controller:** REST under `/api/...`, `userId` from `JwtAuthenticationToken` `sub`
  claim, owner-scoped reads, non-disclosing not-found (`RecipeController`,
  `CatalogController`).
- **DTO/Request:** Lombok `@Data/@Builder` DTOs (`RecipeDto`, `CatalogRecipeDto`);
  Jakarta-validated request objects (`SaveRecipeRequest`).
- **Frontend:** Next.js App Router pages under `(protected)`, mobile-first Tailwind,
  race-safe fetch (monotonic request id + `AbortController`). Note `frontend/AGENTS.md`:
  modified Next.js — consult `node_modules/next/dist/docs/` before writing frontend code.

### A note on UI iteration

Visual and layout details in this design (calendar arrangement, picker style, spacing,
wording) are **intentional starting points, not fixed contracts**. The requirements pin
down behavior and constraints (works on mobile, touch-operable, no horizontal scroll); the
exact look is expected to be refined after hands-on use. UI tweaks that don't change the
data returned by the API require **no backend changes**.

---

## 1. Data model

### 1.1 `MealPlan` (new `@DynamoDbBean`)

Stored in a new table named by `dynamodb.meal-plans-table`. One item per plan; entries are
embedded in the plan item (see §1.4 for why).

| Field | Type | Notes |
|---|---|---|
| `mealPlanId` | `String` | Partition key. UUID. |
| `ownerUserId` | `String` | GSI partition key (`ownerUserId-index`). The Cognito `sub`. |
| `name` | `String` | User-facing plan name. |
| `startDate` | `String` | ISO-8601 date (`yyyy-MM-dd`). Optional plan range start. |
| `endDate` | `String` | ISO-8601 date. Optional plan range end. |
| `servings` | `Integer` | Default number of people the plan feeds (plan-level). |
| `entries` | `List<MealPlanEntry>` | Embedded entries (§1.2). |
| `createdAt` | `Instant` | Set on create. |
| `updatedAt` | `Instant` | Updated on every mutation. |
| `members` | `List<PlanMember>` | **Reserved, unused this spec** (§1.3). Absent/omitted. |

Dates are stored as ISO strings (not `Instant`) because a plan entry is a *calendar day*,
not an instant in time — storing a timestamp would drag timezone ambiguity into "what's on
Tuesday." Day granularity as a string is unambiguous and sorts lexicographically.

Ownership is `ownerUserId` (a scalar), **not** a `userId` collection, so the single-owner
GSI query stays a simple `keyEqualTo`. Sharing is added later via the reserved `members`
list without changing `ownerUserId` or the index (§1.3).

### 1.2 `MealPlanEntry` (embedded)

| Field | Type | Notes |
|---|---|---|
| `entryId` | `String` | UUID, unique within the plan. Lets move/remove target one entry. |
| `date` | `String` | ISO-8601 date the entry is placed on. |
| `slot` | `String` | A `MealSlot` enum name (§1.5). |
| `recipeRef` | `RecipeRef` | Which recipe this entry points to (§1.3). |
| `servings` | `Integer` | **Optional** per-entry override; null = use plan-level. |

### 1.3 `RecipeRef` (embedded) — forward-compatible reference

Identifies a recipe in one of the two libraries without copying its content.

| Field | Type | Notes |
|---|---|---|
| `source` | `String` | `RecipeSource` enum name: `SAVED` or `CATALOG` (§1.5). |
| `recipeId` | `String` | `Recipe.recipeId` when `SAVED`, `CatalogRecipe.catalogRecipeId` when `CATALOG`. |

Keeping `source` + `recipeId` (rather than two nullable id fields) means a future third
library adds an enum value, not a new column. Recipe *content* is never copied into the
plan — it is resolved at read time (§3.3), so edits/deletes to the source recipe are
reflected (and handled gracefully when the recipe is gone).

### 1.4 Why entries are embedded (single-item plan), not a separate table

A plan is small and always read/written as a whole (the calendar shows the entire plan).
Embedding entries in the plan item means: one read to render the calendar, atomic updates
to the plan, and no cross-item consistency to manage. DynamoDB's 400 KB item limit is
ample — an entry is a few small strings, so thousands of entries fit. If a future spec
ever needs plans far larger than that, entries can move to their own table behind the same
repository interface; nothing in the API contract assumes embedding.

### 1.5 Enums (defined once, shared vocabulary)

- `MealSlot`: `BREAKFAST, LUNCH, DINNER, SNACK`. Backend enum; the frontend mirrors the
  same names in one constant (like `lib/dietary.ts` mirrors `DietaryRestriction`), so the
  vocabulary is not duplicated ad hoc.
- `RecipeSource`: `SAVED, CATALOG`.

### 1.6 Ownership → sharing (design-forward, NOT built here)

- `ownerUserId` stays the authoritative owner and the GSI key.
- The reserved `members` list (`PlanMember { userId, role }`) is where shared access lands
  later. It is not written or read in this spec.
- Because DynamoDB is schemaless, adding `members` later is non-breaking; existing plans
  simply have no members. Access checks in this spec use `ownerUserId` only; a future spec
  extends the check to include members without touching the table or index.

---

## 2. Persistence

### 2.1 Table + index

- Table: `dynamodb.meal-plans-table` (env `DYNAMODB_MEAL_PLANS_TABLE`, dev default e.g.
  `recipe-ai-dev-meal-plans`), added to `application.properties` alongside the others.
- GSI: `ownerUserId-index` (partition key `ownerUserId`) so a user's plans are one query,
  mirroring `Recipe`'s `userId-index`.
- **Infrastructure (Terraform, in-repo).** The table + GSI are provisioned in
  `infrastructure/modules/dynamodb` — a near-copy of `aws_dynamodb_table.recipes` (which
  already has the analogous `userId-index` GSI). This mirrors how the compliance spec added
  the Consent/AuditLog tables. Beyond the table itself: add module outputs, grant the ECS
  task role read/write on the table + `ownerUserId-index` in the IAM module, and inject
  `DYNAMODB_MEAL_PLANS_TABLE` into the ECS task definition (the backend reads table names
  from env vars at startup — no code change beyond `application.properties`). Only the PK
  and GSI-key attributes are declared; all other fields are schemaless, so later specs that
  add fields need no infra change. See §2.1 tasks and the RUNBOOK.

### 2.2 `MealPlanRepository` (hand-written, mirrors `RecipeRepository`)

```
save(MealPlan)                       // putItem (full-item write; entries embedded)
findById(String mealPlanId)          // getItem by PK
findByOwner(String ownerUserId)      // query ownerUserId-index
delete(String mealPlanId)            // deleteItem
```

Entry add/move/remove are **read-modify-write** on the plan item in the service layer
(load plan → mutate `entries` → `save`). Simple and correct for single-owner plans at this
scale. (Concurrency note: last-write-wins is acceptable for a single owner on their own
plans; if a future multi-writer/shared spec needs it, add a DynamoDB version attribute for
optimistic locking then — not now.)

---

## 3. Service layer

`MealPlanService` owns validation, ownership enforcement, entry mutations, and recipe
resolution.

### 3.1 Ownership enforcement

Every operation takes the acting `userId` (from the controller). Reads/writes load the
plan, compare `ownerUserId` to `userId`, and throw the existing `ResourceNotFoundException`
when they differ — the same non-disclosing pattern `RecipeService` uses (not-owned looks
identical to not-existing).

### 3.2 Reference validation on add/move

When an entry is added or its `recipeRef` changes, the service verifies the reference
resolves to a recipe the user may use, **before** persisting:

- `SAVED`: `RecipeService.getRecipeById(recipeId, userId)` (already owner-checks; throws
  if not owned/missing).
- `CATALOG`: `CatalogSearchService.findById(catalogRecipeId)` present.

If it doesn't resolve, reject (400/404) rather than storing a dangling reference
(Req 3.5).

### 3.3 Recipe resolution on read (graceful degradation)

Rendering a plan resolves each `recipeRef` to display data (title, image, etc.):

- `SAVED` → `RecipeService` (reuse `toDtoFor` so image side effects match the saved path).
- `CATALOG` → `CatalogSearchService.findById`.

Resolution is **best-effort per entry**: if a referenced recipe no longer exists, that
entry resolves to an "unavailable" marker (id + null details) and the rest of the plan
still returns (Req 3.7 / 4.5). One missing recipe never fails the whole plan read.

To avoid N calls per render, resolution batches by source and de-duplicates repeated
recipe ids (the same recipe can appear in many entries — leftovers).

### 3.4 Servings

`servings` is stored/edited/returned; the service does **no** quantity math. Validation: a
positive integer within a sane bound (e.g. 1–50). Per-entry override falls back to
plan-level when null. Scaling ingredient quantities is deferred to spec 3/4.

---

## 4. API

All under `/api/meal-plans`, authenticated (existing `SecurityConfig` — everything but
health/privacy/terms is authenticated), rate/size filters already applied. `userId` from
the JWT `sub` claim, as in `RecipeController`.

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/meal-plans` | Create a plan (name, optional dates, servings). Returns the plan. |
| `GET` | `/api/meal-plans` | List the caller's plans (summaries). |
| `GET` | `/api/meal-plans/{id}` | Get one plan with resolved entries. |
| `PUT` | `/api/meal-plans/{id}` | Update plan-level attributes (name, dates, servings). |
| `DELETE` | `/api/meal-plans/{id}` | Delete a plan. |
| `POST` | `/api/meal-plans/{id}/entries` | Add an entry (date, slot, recipeRef, optional servings). |
| `PUT` | `/api/meal-plans/{id}/entries/{entryId}` | Move/edit an entry (date, slot, servings). |
| `DELETE` | `/api/meal-plans/{id}/entries/{entryId}` | Remove an entry. |

`{id}` and `{entryId}` are validated with a UUID-style `@Pattern` like existing
controllers. Not-owned/absent → 404. Validation errors → 400 via the existing
`GlobalExceptionHandler`.

### 4.1 DTOs / requests (Lombok, Jakarta validation — existing style)

- `MealPlanDto` — `mealPlanId, ownerUserId, name, startDate, endDate, servings,
  List<MealPlanEntryDto>, createdAt, updatedAt`.
- `MealPlanEntryDto` — `entryId, date, slot, servings, resolved recipe view`. The resolved
  recipe reuses existing DTO shapes: a `RecipeDto`/`CatalogRecipeDto` (or a slim
  `{source, recipeId, title, imageUrl, available}` projection) so the UI needs no new
  recipe type.
- `CreateMealPlanRequest` — `@NotBlank @Size name`, optional ISO dates, `@Positive @Max`
  servings.
- `UpdateMealPlanRequest` — same fields, all for plan-level edit.
- `AddEntryRequest` — `@NotNull date`, `@NotNull slot` (valid `MealSlot`), `@NotNull`
  recipeRef (`source` ∈ `RecipeSource`, non-blank `recipeId`), optional `@Positive`
  servings.
- `UpdateEntryRequest` — optional `date`, `slot`, `servings` for move/edit.

Slot and source strings are validated against their enums (reject unknown values, like
`CatalogController` validates dietary tags), so a typo is a 400, not silently stored.

---

## 5. Frontend (mobile-first)

New route group pages under `frontend/app/(protected)/meal-plans/`:

- `meal-plans/page.tsx` — list of the user's plans + "New plan". Reuses the card/list
  patterns from `recipes`/`browse`.
- `meal-plans/[id]/page.tsx` — the calendar for one plan.

### 5.1 Responsive calendar

- **Mobile (base styles):** one day at a time (or a vertical day list), slots stacked
  (Breakfast → Snack), each slot showing its recipe card or an empty "＋ Add" affordance.
  Day navigation (prev/next day, jump to date) sized for touch. No horizontal scroll.
- **Larger screens (`md:`/`lg:`):** may expand to a multi-day/week column layout. This is
  purely presentational — same data, same endpoints.
- Follows the app's mobile-first Tailwind convention (base = mobile, breakpoints layer up),
  as in `browse`'s `grid ... sm:grid-cols-2 lg:grid-cols-3` and the login page's
  `md:hidden` / `hidden md:flex` splits.

### 5.2 Add-a-recipe flow (touch-first)

Tapping "＋ Add" on a slot opens a **full-screen / bottom-sheet picker** (mobile-friendly)
that reuses existing search:

- Search saved recipes (`GET /api/recipes?q=`) and/or the catalog
  (`GET /api/catalog/search?q=`) — a simple toggle/tab between the two sources.
- Picking a result calls `POST /entries` with the right `recipeRef` and the slot/date
  already chosen.

Drag-and-drop is **not** required. If a desktop enhancement later adds drag-to-move, every
such action still has a tap equivalent (Req 6.5) — e.g. an entry's "⋯" menu offers
Move/Remove.

### 5.3 Data fetching

Plan reads use the existing race-safe pattern (monotonic request id + `AbortController`)
already used in `recipes/page.tsx`. Mutations (add/move/remove/edit) call the API then
refresh or optimistically update the local plan state (local React state; no new state
library, consistent with the app today).

### 5.4 Types

`frontend/types/mealPlan.ts` — `MealPlan`, `MealPlanEntry`, `RecipeRef`, `MealSlot`
constant — mirroring the backend enums/DTOs, alongside the existing `types/recipe.ts`.

---

## 6. Testing

- **Backend unit:** `MealPlanService` — ownership enforcement (not-owned → not-found),
  reference validation (dangling rejected), graceful resolution (missing recipe →
  unavailable, rest returns), servings validation + per-entry override fallback, entry
  move/remove targeting by `entryId`.
- **Backend web:** `MealPlanController` slice — auth required; validation (bad slot/source
  → 400); 404 for other users' plans.
- **Repository:** save/findById/findByOwner/delete round-trips (existing repo test style).
- **Frontend:** calendar renders entries into slots; empty slot shows add; mobile layout
  (no horizontal scroll) and touch add flow; unavailable entry shows placeholder;
  race-safe fetch on plan open.

Follows existing conventions (JUnit + Spring test slices, jqwik where property-style adds
value; Vitest on the frontend). No new test framework.

---

## 7. Account lifecycle, consent, and limits

### 7.1 Account-status gating

Meal-plan **write** operations check account status the same way `RecipeController.generate`
does: if the user is `PENDING_DELETION` or `DELETION_FAILED`, return the existing 403
forbidden response. Reads are unaffected. No `AI_DATA_PROCESSING` consent check — this spec
does no AI processing (a later AI-assistant spec gates its own AI calls).

### 7.2 Hard-deletion integration

`AccountDeletionService.executeHardDeletion` is a stepwise sequence
(recipes+S3 → consents → export zip → Cognito → user record), each step wrapped so a
failure calls `markDeletionFailed(user, step, resourceId, e)` and aborts. Add a **meal-plans
deletion step before the user-record step**: load `mealPlanRepository.findByOwner(userId)`,
delete each plan, and on failure call `markDeletionFailed(user, "meal_plan_deletion", ...)`
and rethrow — identical shape to the recipe step. Placing it before the user-record removal
keeps the existing "external data gone before the record" ordering and retry safety. (Meal
plans reference recipes by id and own no S3 objects, so there's no image cleanup here.)

### 7.3 Data-export integration

`DataExportService.buildDataExportJson` assembles a `DataExportJson` with a `recipes` list.
Add a parallel `mealPlans` list: fetch `findByOwner(userId)` and map each plan (attributes +
entries, entries referencing recipe ids — **not** copied recipe content) into a new
`DataExportJson.MealPlanExportData` shape. Include it in both the JSON export and the ZIP
(the async worker builds from the same JSON), and add the plan count to the existing
`DATA_EXPORT_COMPLETED` audit metadata.

### 7.4 Limits (configuration)

- `mealplans.max-per-user` (e.g. 100) and `mealplans.max-entries-per-plan` (e.g. 500),
  added to `application.properties` with env overrides, in the style of the existing
  `recipes.search.page-size-*` / demo-quota settings.
- `MealPlanService` enforces them: reject plan creation past the per-user cap and entry
  add past the per-plan cap, via the existing validation/exception path
  (`GlobalExceptionHandler`). The per-plan cap keeps the single (entries-embedded) plan
  item comfortably under DynamoDB's 400 KB limit (§1.4).

## 8. What this design deliberately defers (per ROADMAP)

- Add-to-plan buttons on existing recipe cards → spec 2 (here, adding is from the picker).
- Ingredient quantity scaling from servings → spec 3/4 (here, servings is stored/shown).
- Grocery list, nutrition, cuisine/prep-time enrichment → later specs.
- Shared/household plans → data shape reserved (`members`), behavior not built.
- AI assistant → the clean, id-based CRUD here is the intended future tool surface.

---

## Revision R1 — Unified calendar + meal-prep spans

Implements requirements R1.1–R1.3. Backend change is additive (one field + one endpoint);
the bulk is a frontend rework.

### R1.1 Data model — `spanDays` on the entry
`MealPlanEntry` gains `Integer spanDays`: the number of consecutive days the entry covers
from its `date` (meal prep). Null/1 = single day. Non-breaking (DynamoDB schemaless; older
entries read back as null and are treated as 1 in the DTO). Carried through `MealPlanEntryDto`,
`AddEntryRequest`/`UpdateEntryRequest` (`@Positive @Max(31)`), the resolver mapping, and the
data-export shape. No quantity scaling.

### R1.2 Default (implicit) calendar
`MealPlanService.getOrCreateDefaultPlan(userId)` + `GET /api/meal-plans/default`: returns the
user's most-recently-updated plan or creates one ("My Meal Plan"). This lets the UI expose a
single calendar (Option A) while the `MealPlan` entity and multi-plan endpoints stay intact.
Not gated on account status (a lazy read that materializes an empty calendar shouldn't 403).
Existing plans from the old UI fold in automatically — no migration.

### R1.3 Frontend — unified calendar
- `/meal-plans/page.tsx` is now the calendar (the old `/meal-plans/[id]` detail route and the
  plans-list page were removed). It fetches the default plan and renders Week or Month.
- View toggle: Month default on `md+`, Week default on phones (via `matchMedia`), user can switch.
- Date math lives in a pure, tested `lib/calendar.ts` (`weekDays`, `monthGrid`, `spanCoversDay`,
  local-day ISO strings only — no UTC). This keeps the calendar logic unit-testable.
- Week view: each day shows its four slots; a per-day/slot "+" opens the picker.
- Month view: a weekday-headed grid of numbered cells; each cell lists its meals (compact),
  with "+N more" overflow; an in-cell "+" adds to that day.
- Multi-day meals: placed into every covered day via `spanCoversDay`. Start day shows the
  title + meal-prep badge; continuation days show "Leftovers"/"from meal prep". Remove is
  offered only on the start day so a span is deleted as a unit. (Rendered per-day rather than as
  an absolutely-positioned bar, which keeps week-row wrapping on the month grid trivial and
  avoids brittle layout — the visual "span" is the repeated, connected styling.)
- `components/mealplan/RecipePicker.tsx` adds a meal-prep span selector (1–N days) to the
  existing touch-first picker; it returns `{source, recipeId, title, spanDays}`.
- Entry cards link to the existing recipe detail pages (`/browse/[id]`, `/recipes/[id]`).

### R1.4 Tests
`lib/calendar.test.ts` (date/grid/span math) and the rewritten `meal-plans/page.test.tsx`
(view default + toggle, default-plan fetch, picker open with span control, multi-day rendering,
error/retry). Backend: span + default-plan cases in `MealPlanServiceTest`.
