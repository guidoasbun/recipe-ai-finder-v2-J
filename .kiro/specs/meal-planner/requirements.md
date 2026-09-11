# Requirements — Meal Plan Core

## Overview

The first spec of the Meal Planner feature (see `ROADMAP.md`). It introduces the
`MealPlan` concept — the missing piece everything else depends on — and a basic calendar
where a signed-in user schedules recipes onto days and meal slots. Recipes are pulled from
the app's existing library: the user's saved recipes (`Recipe`) and the shared catalog
(`CatalogRecipe`).

This spec delivers a **working, end-to-end planner** on its own: create a plan, view it as
a calendar, add/move/remove recipe entries, and reopen it later. It reuses the existing
recipe library, search, and auth; it does **not** add grocery lists, structured
ingredients, nutrition, AI planning, or calendar export (those are later specs).

### Scope decisions (agreed)

- **Reuse the recipe library as-is.** Plan entries reference existing recipes by id; this
  spec does **not** add `nutrition`, `prepTime`/`cookTime`, or `cuisine` to any recipe
  model. Those are later, additive specs.
- **Servings ("how many people") is stored, not scaled.** This spec captures, persists,
  edits, and displays a servings count on a plan (and an optional per-entry override), but
  does **not** scale ingredient quantities. Quantity-scaling depends on structured
  ingredients (spec 3) and lands with the grocery-list work. The servings count lives on
  the plan/entry, not on the shared recipe models, so no recipe model changes here.
- **Reuse existing search** (`CatalogSearchService` and the saved-recipe search) to find
  recipes to add. No new search logic.
- **Reuse Cognito/JWT auth** and the per-user scoping pattern (userId from the `sub`
  claim). Plans are private to their owner.
- **Respect existing account-lifecycle and compliance machinery.** Meal plans honor
  account status (pending-deletion blocks writes), are deleted on account hard-deletion,
  and are included in data export — reusing `AccountDeletionService` and
  `DataExportService`. No `AI_DATA_PROCESSING` consent is required (no AI here).
- **Bounded.** A configurable max entries-per-plan and max plans-per-user keep a single
  plan item within DynamoDB's 400 KB limit and prevent resource exhaustion.
- **Persistence is DynamoDB** via the Enhanced Client, following the existing
  `@DynamoDbBean` + hand-written repository pattern.
- **Design forward-compatibly, build just-in-time** (ROADMAP principle). The data model is
  shaped so later specs slot in without breaking changes, but only Meal Plan Core
  structures are built now.
- **Anticipate ownership** in the data shape only: model plan ownership so a future
  shared/household-plans feature does not require a breaking migration. Sharing is **not**
  built in this spec.
- **Frontend is Next.js (App Router).** Note `frontend/AGENTS.md`: this is a modified
  Next.js; consult `node_modules/next/dist/docs/` before writing frontend code. New pages
  live under the existing `(protected)` route group and use the `apiFetch`/proxy pattern.
- **Mobile-first.** Most users will plan on their phones, so the calendar and all planner
  interactions are designed for touch and small screens first, then enhanced for larger
  screens — following the app's existing mobile-first Tailwind convention (base styles for
  mobile, `sm:`/`md:`/`lg:` for wider viewports, as in `browse`/`recipes` and the
  `md:hidden` / `hidden md:flex` splits in the login page).

### Out of scope (later specs)

- Add-to-plan buttons on existing recipe cards (spec 2 — this spec adds recipes from
  within the planner UI).
- Grocery lists, structured ingredients, nutrition, recipe enrichment, AI assistant,
  calendar export.
- Shared/household plans (only the data shape is anticipated).

---

## Requirement 1 — Meal plan entity and persistence

**User story:** As the developer, I want a persisted `MealPlan` model scoped to its owner,
so that plans can be stored, retrieved, and later extended without a breaking change.

### Acceptance criteria

1. The system SHALL define a `MealPlan` persisted in its own DynamoDB table (named by a
   `dynamodb.*` config property), following the existing `@DynamoDbBean` + repository
   pattern.
2. A `MealPlan` SHALL have a unique plan identifier as its partition key and SHALL be
   retrievable by its owner via a `userId` secondary index (mirroring the `Recipe`
   `userId-index` GSI).
3. A `MealPlan` SHALL record ownership in a shape that can grow to shared/household plans
   later WITHOUT a breaking migration (e.g. an explicit owner field plus room for a future
   members concept), while enforcing single-owner access in this spec.
4. A `MealPlan` SHALL contain a collection of entries, where each entry associates a
   **date**, a **meal slot** (e.g. breakfast, lunch, dinner, snack), and a **recipe
   reference**.
5. A recipe reference SHALL identify both which library the recipe comes from (saved
   `Recipe` vs shared `CatalogRecipe`) and the recipe id, so an entry can resolve to a
   real recipe at read time.
6. A `MealPlan` SHALL record a **servings** count (the default number of people the plan
   feeds), and each entry MAY carry an optional servings override; where an entry has no
   override, the plan-level servings applies.
7. The servings value(s) SHALL be stored and returned for display/edit only in this spec;
   the system SHALL NOT scale recipe ingredient quantities from servings here (that
   depends on structured ingredients — spec 3 — and lands with the grocery-list work).
8. The system SHALL record plan `createdAt` and `updatedAt` timestamps.
9. The meal-slot vocabulary SHALL be defined once (a single enum/constant) and reused by
   backend and frontend, not duplicated.

## Requirement 2 — Create and manage meal plans

**User story:** As a signed-in user, I want to create meal plans and see the ones I've
made, so that I can organize meals over time.

### Acceptance criteria

1. WHEN a signed-in user creates a meal plan THEN the system SHALL persist it owned by
   that user and return its identifier.
2. WHEN a user lists their meal plans THEN the system SHALL return only plans they own.
3. WHEN a user opens a plan they own THEN the system SHALL return the plan with its
   entries.
4. IF a user requests a plan they do not own THEN the system SHALL respond as not-found
   (the same non-disclosing pattern used elsewhere: not-owned is indistinguishable from
   not-existing).
5. WHEN a user deletes a plan they own THEN the system SHALL remove it.
6. WHEN a user renames or edits plan-level attributes (e.g. plan name, date range,
   servings) THEN the system SHALL persist the change and update `updatedAt`.
7. WHEN a user changes the plan's servings count THEN the system SHALL persist the new
   count; this affects display only and SHALL NOT alter stored recipe ingredients.
8. WHEN plan input is submitted (name, dates, servings) THEN the system SHALL validate and
   bound it consistent with existing request-validation conventions (servings SHALL be a
   positive integer within a sane upper bound).

## Requirement 3 — Add, move, and remove recipe entries

**User story:** As a user, I want to place recipes on specific days and meal slots and
adjust them, so that I can build and revise a plan.

### Acceptance criteria

1. WHEN a user adds a recipe to a plan at a given date and meal slot THEN the system SHALL
   persist an entry referencing that recipe and return the updated plan (or the created
   entry).
1a. WHEN a user sets a servings override on an entry THEN the system SHALL persist it on
   that entry; WHEN no override is set THEN the plan-level servings applies to that entry.
2. WHEN a user adds a recipe that comes from the shared catalog THEN the entry SHALL
   reference the `CatalogRecipe`; WHEN it comes from the user's saved recipes THEN the
   entry SHALL reference the `Recipe`.
3. WHEN a user moves an entry to a different date or meal slot THEN the system SHALL update
   that entry's date/slot and persist it.
4. WHEN a user removes an entry THEN the system SHALL delete it from the plan.
5. WHEN a user adds an entry referencing a recipe that does not exist or that they cannot
   access (a saved recipe they don't own) THEN the system SHALL reject the operation rather
   than storing a dangling reference.
6. The system SHALL allow the same recipe to appear in multiple entries (e.g. leftovers
   across days) without restriction.
7. WHEN a referenced recipe is later deleted from its source library THEN reading the plan
   SHALL degrade gracefully (the entry is shown as unavailable/missing rather than causing
   the whole plan read to fail).

## Requirement 4 — Calendar view

**User story:** As a user, I want to see my plan as a calendar of days and meal slots, so
that I can understand my week at a glance and edit it in place.

### Acceptance criteria

1. WHEN a user opens a plan THEN the system SHALL display a calendar organized by date and
   meal slot, showing the recipe placed in each slot.
2. WHEN a slot is empty THEN the UI SHALL present a clear way to add a recipe to that
   date/slot.
3. WHEN a user adds a recipe from within the calendar THEN the UI SHALL let them find a
   recipe using the existing search (saved recipes and/or the shared catalog) and place it
   in the chosen date/slot.
4. WHEN the plan loads THEN the UI SHALL resolve each entry to its recipe details (at least
   title and image where available) for display, reusing existing recipe DTOs.
5. WHEN a recipe referenced by an entry is unavailable THEN the UI SHALL show a graceful
   placeholder for that slot (not a broken card or a page error).
6. The calendar UI SHALL live under the existing `(protected)` route group and follow the
   existing race-safe fetch conventions (monotonic request id + `AbortController`) where it
   fetches data.
7. On small screens the calendar SHALL present a mobile-appropriate layout (e.g. a
   single-day or vertical day-by-day view) rather than a wide multi-column week grid, and
   SHALL provide simple navigation between days; on larger screens it MAY present a wider
   week/grid layout.
8. All add/move/remove interactions SHALL be operable by touch (tap-based add-to-slot and
   entry actions); drag-and-drop, if offered, SHALL be an enhancement on top of a
   touch-friendly baseline, never the only way to perform an action.

## Requirement 5 — Access control and per-user isolation

**User story:** As a user, I want my meal plans private to me, so that no one else can read
or change them.

### Acceptance criteria

1. WHEN any meal-plan endpoint is called THEN the system SHALL require authentication using
   the same security configuration as other protected endpoints.
2. WHEN a meal-plan operation is performed THEN the system SHALL derive the acting user
   from the JWT `sub` claim and SHALL scope the operation to plans that user owns.
3. IF a user attempts to read or modify a plan they do not own THEN the system SHALL deny
   the operation using the non-disclosing not-found pattern.
4. The meal-plan endpoints SHALL respect the existing rate-limiting and request-size
   filters already applied to the API.

## Requirement 6 — Mobile-first, responsive UI

**User story:** As a user planning meals on my phone, I want the planner to work well on a
small touch screen, so that I can build and adjust plans on the go.

### Acceptance criteria

1. The planner UI SHALL be designed mobile-first: base styles target small screens and
   larger-screen layouts are layered on with the app's existing Tailwind breakpoints
   (`sm:`/`md:`/`lg:`), consistent with `browse`/`recipes`.
2. WHEN the calendar is viewed on a phone-sized screen THEN it SHALL use a layout that fits
   without horizontal scrolling (e.g. one day at a time or a vertical day list), with clear
   navigation between days/weeks.
3. All primary actions (create plan, add recipe to a slot, move an entry, remove an entry,
   change servings) SHALL be fully operable by touch, with tap targets sized for fingers.
4. WHEN a recipe is added from within the planner on mobile THEN the search-and-pick flow
   SHALL be usable on a small screen (e.g. a full-screen or sheet-style picker) rather than
   depending on a wide layout.
5. Any drag-and-drop affordance SHALL be an optional enhancement; every action it provides
   SHALL also be achievable via tap, so touch users are never blocked.
6. The UI SHALL remain usable across common phone widths without content overflow or
   overlapping controls.

## Requirement 7 — Account lifecycle, consent, and compliance

**User story:** As a user (and as the operator), I want meal plans to respect the same
account-status, consent, and data-lifecycle rules as the rest of my data, so that the
planner doesn't create a gap in access control or GDPR handling.

### Acceptance criteria

1. WHEN an account is `PENDING_DELETION` or `DELETION_FAILED` THEN meal-plan **write**
   operations (create plan, add/move/remove entry, edit) SHALL be refused with the same
   forbidden response `RecipeController.generate` uses for that state.
2. Meal-plan endpoints SHALL NOT require `AI_DATA_PROCESSING` consent, because this spec
   performs no AI processing. (When the AI assistant spec adds AI planning, that feature —
   not this one — will gate on the appropriate consent.)
3. WHEN a user's account is hard-deleted THEN the system SHALL delete all meal plans owned
   by that user as a step in the existing `AccountDeletionService` deletion sequence, with
   the same fail-and-mark behavior (`markDeletionFailed`) as the other deletion steps, and
   before the user record itself is removed.
4. WHEN a user exports their data THEN the export SHALL include their meal plans (plan
   attributes and entries, referencing recipe ids — not copied recipe content), added to
   the existing `DataExportService` JSON/ZIP output alongside recipes.
5. Meal-plan mutations SHALL be recorded consistently with the app's existing audit
   approach where account-data changes are audited (at minimum, deletion of plans during
   account deletion is covered by the existing deletion audit events).

## Requirement 8 — Per-user and per-plan limits

**User story:** As the operator, I want sane bounds on plans and entries, so that a single
plan item stays well within storage limits and no user can exhaust resources.

### Acceptance criteria

1. The system SHALL enforce a maximum number of **entries per plan** (a configurable
   bound) so a plan item stays comfortably within the DynamoDB 400 KB item limit; adding
   an entry beyond the bound SHALL be rejected with a clear validation error.
2. The system SHALL enforce a maximum number of **plans per user** (a configurable bound);
   creating a plan beyond the bound SHALL be rejected with a clear validation error.
3. These bounds SHALL be defined as configuration (with sensible defaults), consistent with
   how existing limits (page sizes, request sizes, demo quotas) are configured.
4. The limit checks SHALL reuse the existing validation/exception conventions so violations
   surface as the standard error responses via `GlobalExceptionHandler`.

## Requirement 9 — Reuse existing recipe library and search

**User story:** As the developer, I want the planner to reuse the existing recipe library
and search, so that we don't duplicate recipe data or search logic.

### Acceptance criteria

1. The planner SHALL reference existing recipes by id rather than copying recipe content
   into the plan.
2. The planner SHALL resolve saved-recipe references through the existing recipe
   service/repository and catalog references through the existing
   `CatalogSearchService`/catalog repository.
3. The "find a recipe to add" experience SHALL reuse the existing catalog search and/or
   saved-recipe search, not a new search implementation.
4. The planner SHALL NOT modify the `Recipe` or `CatalogRecipe` models in this spec.

---

## Design-forward notes (build later, shape now)

These are recorded so the Meal Plan Core data model does not need breaking changes when
later specs land. They are **not** implemented in this spec.

- **Ownership → sharing (spec: household plans).** Model ownership so a plan can gain
  additional members/roles later without changing the partition strategy or breaking
  single-owner reads.
- **Servings scaling (spec 3/4).** This spec stores and displays the servings count
  (plan-level default + optional per-entry override). The *scaling of ingredient
  quantities* from that count is built later, once structured ingredients exist; the count
  captured here is its input.
- **Grocery list (spec 4).** Nothing built, but the plan's date-range + entries are the
  input a future grocery-list feature will aggregate over.
- **AI assistant (spec 7).** The meal-plan CRUD defined here is intended to be the tool
  surface the assistant will later call; keep operations clean and id-based.
