# Tasks — Add-to-Plan Integration

> Status: all tasks complete. Frontend tests pass (`npm run test` — 48 tests / 7 files,
> incl. the 6 new `AddToPlanButton` tests) and lint is clean on the changed files. Purely
> frontend: no backend, DTO, DynamoDB, IAM, or Terraform diff. Two small deviations from the
> plan, both intentional: task 3's optional `addRecipeToDefaultPlan` wrapper was NOT added
> (the two helpers are composed inline in `performAdd` instead — no second network path);
> task 4 uses an inline "Added ✓" confirmed state on the button rather than the page-level
> 3000ms toast (the requirement — brief non-blocking success — is met either way).

Implementation plan. Each task is incremental and test-backed. Requirement references map to
`requirements.md`; design references map to `design.md`. This spec is **frontend-only** — no
backend, DTO, DynamoDB, IAM, or Terraform changes, and no changes to the `Recipe` /
`CatalogRecipe` models or the meal-plan API (spec 1's contract is already sufficient).

> Reminder (`frontend/AGENTS.md`): this is a modified Next.js. Consult
> `node_modules/next/dist/docs/` before writing frontend code and heed deprecation notices.

- [x] 1. Build the `AddToPlanButton` component
  - New `frontend/components/mealplan/AddToPlanButton.tsx` (`"use client"`), props
    `{ source, recipeId, title?, className?, variant? }`.
  - Renders a touch-sized control ("Add to plan" for `variant="button"`; a `lucide-react`
    icon with an `aria-label` for `variant="icon"`). Guards a blank `recipeId` (render
    nothing / disabled). `onClick` does `stopPropagation()` + `preventDefault()` and opens
    the sheet. Owns transient state (idle/open/submitting/success/error).
  - _Requirements: 1.1, 1.5, 2.1, 2.2, 5.3, 7.1, 7.3 | Design: §1.1_

- [x] 2. Build the `AddToPlanSheet` date/slot picker
  - New `AddToPlanSheet` (in `AddToPlanButton.tsx` or its own file). Reuse the bottom-sheet
    skeleton from `RecipePicker`/`EntryEditor` (`fixed inset-0 z-50 …`, `role="dialog"`,
    `aria-modal`, `X` close).
  - Controls: native `<input type="date">` defaulting to `todayIso()` (`lib/calendar.ts`);
    slot from `MEAL_SLOTS` (`lib/mealSlots.ts`), default `DINNER`; optional 1–14 day meal-prep
    span `<select>` (mirror `RecipePicker`), default 1; a "Add to plan" confirm button.
  - On confirm, hand `{ date, slot, spanDays }` to the caller; dismiss makes no change.
  - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 7.2 | Design: §1.2_

- [x] 3. Wire the add via the existing API helpers
  - In `AddToPlanButton.performAdd`: `getDefaultMealPlan()` → `addEntry(plan.mealPlanId, { date,
    slot, source, recipeId, spanDays })` from `lib/mealPlanApi.ts`. Do NOT set `servings`
    here. No new endpoint or network path.
  - Optional: a thin `addRecipeToDefaultPlan(source, recipeId, {date, slot, spanDays})` wrapper
    in `lib/mealPlanApi.ts` composing the two helpers — only if it reads cleaner; must not add
    a second network path.
  - _Requirements: 4.1, 4.2, 4.3, 6.1 | Design: §2, §4_

- [x] 4. Success / error feedback
  - Success: brief inline "Added ✓" state and/or a transient toast mirroring
    `account/dietary/page.tsx` (`SUCCESS_TOAST_MS = 3000`, auto-dismiss via `useEffect`
    timeout). No new toast library.
  - Error: map a thrown `Error("Request failed: <status>")` to a friendly, retryable message;
    keep the sheet open on failure so the user can retry. Disable confirm while in flight.
  - _Requirements: 5.1, 5.2, 5.3, 5.4, 6.2 | Design: §3_

- [x] 5. Mount on the saved-recipes card
  - In `frontend/components/recipe/RecipeCard.tsx`, add `AddToPlanButton`
    (`variant="button"`, `source="SAVED"`, `recipeId={effectiveId}`, `title={recipe.title}`)
    to the action row `<div className="mt-auto flex gap-2">`, only when `saved` and
    `effectiveId != null`. Don't disturb the existing View/Save/Delete/image behavior.
  - _Requirements: 2.1, 2.2, 2.3, 2.4, 7.4 | Design: §1.3_

- [x] 6. Mount on the browse (catalog) card
  - In `frontend/app/(protected)/browse/page.tsx`, add `AddToPlanButton` (`variant="icon"`,
    `source="CATALOG"`, `recipeId={r.catalogRecipeId}`, `title={r.title}`) to each result
    card. Because the card is a full-card `<Link>`, ensure the button's click is stopped from
    bubbling into navigation. Place it so it doesn't overflow/overlap on phone widths.
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 7.1, 7.4 | Design: §1.3_

- [x] 7. Frontend tests
  - Vitest + Testing Library (mock the `mealPlanApi` helpers / `fetch`):
    - Tapping the button opens the sheet with the recipe title in the header.
    - Confirm calls `getDefaultMealPlan` then `addEntry` with the exact expected body
      (`source`, `recipeId`, chosen `date`/`slot`, `spanDays`) for both a CATALOG and a SAVED
      case.
    - Catalog icon click does not navigate (default prevented / propagation stopped).
    - Blank `recipeId` → no add attempted.
    - Success → confirmed state/toast; failure → retryable error, sheet stays open; in-flight
      disables confirm.
    - `RecipeCard` shows the affordance only when `saved` + id present; a browse card shows it
      per result.
  - Run `npm run test` and lint/typecheck in `frontend/`; fix failures.
  - _Requirements: 1–7 (verification) | Design: §6_

- [x] 8. RUNBOOK
  - Add `.kiro/specs/meal-planner-add-to-plan/RUNBOOK.md`: note it's frontend-only (no infra,
    no backend, no new env vars), which existing endpoints it reuses, the two mount points,
    the feedback pattern, and how to exercise it in the UI.
  - _Requirements: (docs) | Design: §2_

---

## Verification checklist (definition of done)

- [x] Adding from a catalog card posts a `CATALOG` entry to the default plan (asserted in
      tests: `getDefaultMealPlan` then `addEntry` with `source: "CATALOG"`). End-to-end "the
      calendar shows it" is best confirmed with a hands-on pass in the running app.
- [x] Adding from a saved-recipe card posts a `SAVED` entry to the default plan (asserted in
      tests).
- [x] Catalog "Add to plan" never navigates to the detail page (test: click does not bubble
      to the surrounding clickable card; component calls `preventDefault`/`stopPropagation`).
- [x] The date/slot sheet defaults to today + Dinner and uses the shared full-screen/bottom-
      sheet skeleton (`fixed inset-0`, native date input, tap-only). Actual phone
      layout/no-horizontal-scroll is a visual check to do on a device (per the UI-iteration
      note).
- [x] Success and failure both give feedback; failure keeps the sheet open (retryable);
      confirm is disabled while in flight (no double-submit) — all asserted in tests.
- [x] `npm run test` (frontend) passes incl. the new tests (48/48). Lint clean on the changed
      files (only pre-existing `<img>` warnings remain). Note: `tsc --noEmit` surfaces
      pre-existing tuple-typing errors in spec 1's `meal-plans/page.test.tsx`, unrelated to
      this change.
- [x] No backend/infra/Terraform diff — the change set is only frontend files.
