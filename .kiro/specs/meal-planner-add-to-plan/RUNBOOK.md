# Runbook — Add-to-Plan Integration

Operational guide for the Meal Planner's second spec. See `design.md` for the why and
`requirements.md` for the behavior contract.

## 1. What this spec is (and isn't)

**Frontend only.** It adds an "Add to plan" affordance to the recipe cards on `/browse`
(shared catalog) and `/recipes` (saved recipes) that drops a recipe onto the user's meal-plan
calendar. It reuses the planner API shipped in spec 1 (Meal Plan Core, Revision R1).

- **No backend change.** No new endpoints, controllers, DTOs, or services.
- **No infrastructure.** No DynamoDB table, IAM, or Terraform change. Nothing to `terraform
  apply`, nothing to redeploy on the backend.
- **No new env vars or configuration.**
- **No changes to the `Recipe` / `CatalogRecipe` models.**

If a deploy of this spec seems to need a backend or infra change, something is wrong — it
shouldn't.

## 2. Reused backend endpoints (already live from spec 1)

| Method | Path | Used for |
|---|---|---|
| GET | `/api/meal-plans/default` | Resolve (get-or-create) the user's single calendar |
| POST | `/api/meal-plans/{id}/entries` | Add the chosen recipe as an entry |

Both are authenticated (Cognito JWT; `userId` = `sub`). The add validates the recipe
reference, enforces per-plan limits, and refuses writes on a
`PENDING_DELETION`/`DELETION_FAILED` account (403). This feature surfaces those failures in
the UI; it does not re-implement any of that logic.

## 3. Files added / changed

- **New** `frontend/components/mealplan/AddToPlanButton.tsx` — the affordance plus its
  date/slot/span sheet (`AddToPlanButton` + the internal `AddToPlanSheet`). Self-contained:
  owns its own open/submitting/success/error state.
- **New** `frontend/components/mealplan/AddToPlanButton.test.tsx` — Vitest coverage.
- **Changed** `frontend/components/recipe/RecipeCard.tsx` — mounts `AddToPlanButton`
  (`variant="button"`, `source="SAVED"`) below the action row, only for a saved recipe with an
  id.
- **Changed** `frontend/app/(protected)/browse/page.tsx` — mounts `AddToPlanButton`
  (`variant="icon"`, `source="CATALOG"`) as an overlay control on each catalog card. The card
  is a non-interactive wrapper (`relative`) with the detail `<Link>` and the button as
  **siblings** (not a button nested in an anchor), and the title reserves right padding so the
  overlay never covers it.
- **Changed** `frontend/app/(protected)/browse/[id]/page.tsx` — mounts `AddToPlanButton`
  (`variant="button"`, `source="CATALOG"`) next to the title so a recipe can be scheduled from
  the catalog detail page too.
- **New** `frontend/lib/ingredient.test.ts` — table-driven tests for the ingredient-label
  parser used on browse cards.

No new API helper was added — it calls the existing `getDefaultMealPlan` and `addEntry` in
`frontend/lib/mealPlanApi.ts`.

## 4. How it behaves

- **Catalog card (`/browse`):** a compact calendar-plus icon overlays the top-right of each
  card. Tapping it opens the sheet and does **not** navigate to the recipe detail page (the
  click is stopped from bubbling into the card's link).
- **Saved card (`/recipes`):** an "Add to plan" button appears below View/Delete, only when
  the card is a persisted saved recipe (`effectiveId` present). It never shows for an unsaved
  generated recipe.
- **Catalog detail (`/browse/[id]`):** an "Add to plan" button sits next to the recipe title
  and opens the same sheet.
- **Accessibility:** the sheet moves focus to the date field on open, traps Tab within the
  sheet, closes on Escape, and restores focus to the trigger on close. The date field is
  `required`, so an empty date is blocked in-browser (and guarded in code) rather than failing
  a backend round-trip.
- **The sheet:** full-screen on phones, centered card on `sm+` (mirrors `EntryEditor`/
  `RecipePicker`). Collects a **date** (native date input, defaults to today), a **meal slot**
  (`MEAL_SLOTS`, defaults to Dinner), and an optional **meal-prep span** (1–14 days, default
  1). Confirm resolves the default plan then posts the entry.
- **Feedback:** the button shows a spinner while adding and a brief "Added ✓" afterward
  (auto-clears). On failure the sheet stays open with a retryable error (`role="alert"`); the
  confirm button is disabled while in flight so there's no double-submit.
- **Source mapping:** catalog → `source: "CATALOG"`, `recipeId = catalogRecipeId`; saved →
  `source: "SAVED"`, `recipeId = recipeId`. `servings` is not set here (edit it later in the
  calendar via `EntryEditor`).

## 5. How to exercise it

In the app (through the Next proxy, which attaches auth):

1. Go to `/browse`, search, and tap the calendar icon on any result card. Pick a date/slot
   (optionally a meal-prep span) and confirm.
2. Go to `/recipes` and use "Add to plan" on a saved recipe card.
3. Open `/meal-plans` — the added entries appear on the calendar on the chosen day/slot;
   multi-day picks render as meal-prep with leftovers on continuation days.

Direct backend calls (no UI) need a Cognito JWT in `Authorization: Bearer <token>`; the two
endpoints in §2 are all this feature touches.

## 6. Tests

- Frontend: `npm run test` in `frontend/`.
  - New: `components/mealplan/AddToPlanButton.test.tsx` — blank-id guard (renders nothing),
    sheet opens with the recipe title + today/Dinner defaults, add posts the correct body for
    both SAVED and CATALOG, failure keeps the sheet open with a retryable error, and the icon
    click doesn't bubble to a surrounding clickable ancestor.
  - Full suite passes (48 tests at time of writing).
- Backend: unchanged; nothing to run for this spec.

Note: `tsc --noEmit` reports pre-existing tuple-typing errors in
`app/(protected)/meal-plans/page.test.tsx` (spec 1's test, `fetchMock.mock.calls` access).
Those predate this spec and are unrelated to the files changed here.

## 7. A note on the UI

Per the planner's convention, the exact look of the button and sheet (icon choice, placement,
wording, span max) are **starting points, not fixed contracts**. Pure look-and-feel tweaks
need no logic or backend change — the component just composes the existing API.

## 8. Not in this spec (see ROADMAP)

Add-to-plan is wired on the browse cards, the saved-recipe cards, and the **catalog detail
page** (`/browse/[id]`). The **saved-recipe detail page** (`/recipes/[id]`) is the same
trivial mount and is a follow-on (no API change). Also still later: grocery lists / structured
ingredients (spec 3–4), recipe enrichment (spec 5), nutrition (spec 6), the AI assistant
(spec 7), and calendar export (spec 8).
