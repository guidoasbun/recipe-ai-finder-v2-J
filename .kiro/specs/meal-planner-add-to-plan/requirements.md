# Requirements — Add-to-Plan Integration

## Overview

The second spec of the Meal Planner feature (see `../meal-planner/ROADMAP.md`, spec 2). Meal
Plan Core (spec 1) built a working planner: a per-user calendar, entry CRUD, and a
touch-first `RecipePicker` reached from *inside* the calendar. This spec closes the loop the
other way: it puts an **"Add to plan"** affordance on the recipe cards users already browse —
the shared catalog (`/browse`) and their saved recipes (`/recipes`) — so a recipe can go onto
the calendar without first navigating to the planner.

This is a **small, high-value, frontend-only** spec. The backend already exposes everything
needed: `GET /api/meal-plans/default` (get-or-create the user's single calendar, from spec 1
Revision R1) and `POST /api/meal-plans/{id}/entries` (add an entry, with recipe-reference
validation). This spec adds **no** backend endpoints, no DynamoDB changes, no Terraform, and
no changes to the `Recipe`/`CatalogRecipe` models.

### Scope decisions (agreed)

- **Frontend only, reuse the existing API.** Adding from a card calls the same
  `getDefaultMealPlan` + `addEntry` helpers (`frontend/lib/mealPlanApi.ts`) the calendar
  already uses. No new API surface.
- **Single implicit calendar (Option A).** Consistent with spec 1 R1, adding always targets
  the user's one default plan (`plan.mealPlanId` resolved via `GET /api/meal-plans/default`).
  There is no "which plan?" chooser — there is one calendar.
- **The card already knows the recipe; the user picks *when*.** This is the inverse of the
  in-calendar `RecipePicker` (which is slot-first, recipe-second). Here the recipe is fixed
  and the user chooses a **date** and **meal slot** (and optionally a meal-prep span),
  then confirms.
- **Two sources, correct `RecipeSource`.** A catalog card adds with
  `source: "CATALOG"`, `recipeId = catalogRecipeId`; a saved-recipe card adds with
  `source: "SAVED"`, `recipeId = recipeId`. This mirrors the normalization already in
  `RecipePicker`.
- **Reuse the shared meal-slot vocabulary.** Slots come from `frontend/lib/mealSlots.ts`
  (`MEAL_SLOTS` / `MealSlot`), the single source of truth mirroring the backend enum — not a
  new list.
- **Mobile-first, touch-operable.** The add affordance and its date/slot picker follow the
  app's mobile-first Tailwind convention and the bottom-sheet pattern established by
  `RecipePicker`/`EntryEditor`. Every action is tap-operable; no drag dependency.
- **Modified Next.js.** Per `frontend/AGENTS.md`, this is a modified Next.js; consult
  `node_modules/next/dist/docs/` before writing frontend code. New UI lives under the
  existing `(protected)` route group and uses the `/api/backend/...` proxy path.
- **No visual/library churn.** There is no shared Button/Modal/Toast primitive in this
  codebase; reuse the existing hand-rolled Tailwind patterns (bottom-sheet modal, optimistic
  inline state, the local success-toast pattern) rather than introducing a UI library.

### Out of scope (later specs / not this spec)

- Any backend change (new endpoints, DTOs, persistence, Terraform) — not needed.
- Grocery lists, structured ingredients, nutrition, recipe enrichment, AI assistant,
  calendar export (specs 3–8).
- Adding from the **saved-recipe detail** page (`/recipes/[id]`) — a possible small follow-on,
  not required here. (The **catalog** detail page `/browse/[id]` **is** in scope — see
  Requirement 1.6.)
- Bulk "add multiple recipes at once" / drag-and-drop onto the calendar.
- Changing the in-calendar `RecipePicker` flow (it stays as-is).

---

## Requirement 1 — "Add to plan" on catalog (browse) cards

**User story:** As a signed-in user browsing the shared catalog, I want an "Add to plan"
action on each recipe card, so that I can schedule a catalog recipe onto my calendar without
leaving the browse page.

### Acceptance criteria

1. WHEN a user views the `/browse` results grid THEN each catalog recipe card SHALL present a
   clearly labeled, touch-sized "Add to plan" affordance in addition to its existing
   navigation to the detail page.
2. WHEN a user activates "Add to plan" on a catalog card THEN the system SHALL open a
   date/slot picker for that recipe WITHOUT navigating away from `/browse` (the card is
   currently a full-card link, so the action MUST NOT trigger that navigation).
3. WHEN the user confirms a date and meal slot THEN the system SHALL add an entry to the
   user's default plan with `source = CATALOG` and `recipeId = catalogRecipeId`, reusing the
   existing `getDefaultMealPlan` + `addEntry` helpers.
4. WHEN the add succeeds THEN the UI SHALL confirm it (e.g. a transient success message or an
   inline confirmed state) and return the user to browsing, without a full-page reload.
5. IF the recipe card has no usable `catalogRecipeId` THEN the "Add to plan" affordance SHALL
   NOT attempt an add with a blank id (consistent with the picker's blank-id guard).
6. WHEN a user views the catalog recipe **detail** page (`/browse/[id]`) THEN it SHALL present
   an "Add to plan" action (using the same component and add path as the card), so a recipe
   can be scheduled from the detail view as well as the grid.

## Requirement 2 — "Add to plan" on saved-recipe cards

**User story:** As a signed-in user viewing my saved recipes, I want an "Add to plan" action
on each recipe card, so that I can schedule one of my own recipes onto my calendar.

### Acceptance criteria

1. WHEN a user views `/recipes` THEN each saved-recipe card (`RecipeCard` with `saved`) SHALL
   present an "Add to plan" action alongside the existing View/Delete actions.
2. The "Add to plan" action SHALL appear only for cards that represent a persisted saved
   recipe (i.e. an `effectiveId`/`recipeId` exists); it SHALL NOT appear for an unsaved
   generated recipe that has no id yet.
3. WHEN the user confirms a date and meal slot THEN the system SHALL add an entry to the
   user's default plan with `source = SAVED` and `recipeId = recipeId`.
4. WHEN the add succeeds THEN the card SHALL confirm it (transient/inline), without a
   full-page reload, and without disturbing the card's existing Save/Delete/image behavior.

## Requirement 3 — Date and meal-slot selection

**User story:** As a user adding a known recipe, I want to choose which day and meal it goes
on, so that it lands in the right place on my calendar.

### Acceptance criteria

1. WHEN the add flow opens THEN it SHALL let the user choose a **date** (defaulting to today)
   and a **meal slot** from the shared `MEAL_SLOTS` vocabulary (Breakfast, Lunch, Dinner,
   Snack), with a sensible default slot.
2. The flow MAY offer a meal-prep **span** (consecutive days ≥ 1) consistent with spec 1 R1;
   WHEN a span is chosen THEN the add SHALL pass `spanDays` accordingly, and WHEN it is not
   THEN the entry SHALL default to a single day (span 1).
3. The date/slot selection SHALL NOT require the user to type an ISO date by hand; it SHALL
   use a touch-friendly control (native date input or an equivalent picker).
4. All controls in the flow SHALL be fully operable by tap on a phone-sized screen, with
   tap targets sized for fingers and no horizontal scroll.
5. WHEN the user dismisses the flow without confirming THEN the system SHALL make no change
   to the plan.

## Requirement 4 — Reuse the default plan and add-entry path

**User story:** As the developer, I want add-to-plan to reuse the planner's existing default
plan and add-entry API, so that we don't duplicate logic or introduce a second code path.

### Acceptance criteria

1. The add flow SHALL resolve the target plan via the existing `getDefaultMealPlan()` helper
   (get-or-create the user's single calendar), NOT by asking the user to pick a plan.
2. The add flow SHALL persist the entry via the existing
   `addEntry(mealPlanId, AddEntryRequest)` helper, building the `AddEntryRequest`
   (`date`, `slot`, `source`, `recipeId`, optional `spanDays`) from the card's data and the
   user's selection.
3. The system SHALL NOT introduce a new backend endpoint, DTO, or meal-plan persistence path
   for this feature; the backend contract from spec 1 is sufficient.
4. WHERE the flow fetches the default plan, it SHALL follow the app's race-safe fetch
   convention (monotonic request id + `AbortController`) if it maintains its own async state,
   consistent with `browse`/`recipes`/`meal-plans`.

## Requirement 5 — Error handling and feedback

**User story:** As a user, I want clear feedback when adding to my plan succeeds or fails, so
that I know whether the recipe was scheduled.

### Acceptance criteria

1. WHEN an add succeeds THEN the UI SHALL show a brief, non-blocking success indication
   ("Added to plan"), using the app's existing transient-toast or inline-confirmed pattern
   (no new toast library).
2. IF the add request fails (network/validation/backend error) THEN the UI SHALL surface a
   clear, non-technical error and SHALL leave the user able to retry, without corrupting the
   card's other state.
3. WHILE an add request is in flight THEN the affordance SHALL indicate progress (disabled /
   spinner) so the user does not double-submit.
4. IF the referenced recipe cannot be added because the backend rejects the reference (e.g. a
   saved recipe the user no longer owns, surfaced as a 4xx) THEN the UI SHALL report the
   failure gracefully rather than silently doing nothing.

## Requirement 6 — Access control and consistency with the existing app

**User story:** As a user, I want add-to-plan to obey the same auth and account rules as the
rest of the planner, so that it introduces no gap.

### Acceptance criteria

1. All add-to-plan network calls SHALL go through the existing `/api/backend/...` proxy path,
   which attaches auth; the client SHALL NOT set auth headers itself.
2. The feature SHALL rely on the backend's existing per-user scoping (the plan resolves to
   the caller's own calendar via the JWT `sub` claim) and its existing account-status gating
   on writes (a `PENDING_DELETION`/`DELETION_FAILED` account's add is refused by the backend
   with 403); the UI SHALL surface such a refusal per Requirement 5.2.
3. The feature SHALL NOT require `AI_DATA_PROCESSING` consent (no AI is involved), consistent
   with the planner.

## Requirement 7 — Mobile-first, touch-first UI

**User story:** As a user adding recipes to my plan from my phone, I want the add affordance
to work well on a small touch screen.

### Acceptance criteria

1. The "Add to plan" affordances on both card types SHALL be designed mobile-first (base
   styles for phones, `sm:`/`md:`/`lg:` layered on), consistent with `browse`/`recipes`.
2. The date/slot picker SHALL use the established mobile bottom-sheet / full-screen pattern
   (as in `RecipePicker`/`EntryEditor`) on small screens rather than a wide layout.
3. Every add-to-plan interaction SHALL be achievable by tap; any pointer/drag enhancement (if
   added later) SHALL never be the only way to perform the action.
4. The added affordance SHALL NOT cause content overflow or overlap on common phone widths,
   and on catalog cards SHALL NOT interfere with the card's existing tap-to-open-detail
   behavior beyond the intended action.

---

## Design-forward notes (shape now, build later)

Recorded so this spec doesn't paint later specs into a corner. Not implemented here.

- **Add from the detail pages.** The same self-contained component (parameterized by
  `{source, recipeId, title}`) is mounted on the catalog detail page `/browse/[id]` in this
  spec (Requirement 1.6). The saved-recipe detail page `/recipes/[id]` is the same trivial
  mount and can follow with no new API.
- **Add-to-plan for AI assistant (spec 7).** The assistant will add entries through the same
  `addEntry` path; keeping this feature purely client-side over the existing API keeps that
  surface clean.
