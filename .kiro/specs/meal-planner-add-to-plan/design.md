# Design — Add-to-Plan Integration

## Context

Second implementation spec of the Meal Planner (see `../meal-planner/ROADMAP.md` spec 2 and
this folder's `requirements.md`). Meal Plan Core (spec 1) delivered the planner and an
in-calendar `RecipePicker`. This spec adds an **"Add to plan"** affordance to the recipe
cards on `/browse` (shared catalog) and `/recipes` (saved recipes), reusing the planner's
existing default-plan + add-entry API.

**This is a frontend-only spec.** The backend contract from spec 1 (R1) is already sufficient:

- `GET /api/meal-plans/default` → `getDefaultMealPlan(signal?)` — returns (or lazily creates)
  the user's single calendar. Not gated on account status (it's a read that may materialize
  an empty calendar).
- `POST /api/meal-plans/{id}/entries` → `addEntry(mealPlanId, AddEntryRequest)` — validates
  the recipe reference, enforces per-plan limits and account-status gating, returns the
  updated `MealPlan`.

No new endpoints, DTOs, DynamoDB tables, IAM, or Terraform. No changes to `Recipe` /
`CatalogRecipe`.

### A note on UI iteration (same as spec 1)

The exact look of the button and the picker sheet (wording, placement, spacing) are
**starting points, not fixed contracts**. Requirements pin the behavior (works on mobile,
touch-operable, adds to the default plan with the correct source); the visuals are expected
to be refined after hands-on use. Pure look-and-feel tweaks need no logic change.

Frontend conventions this design follows (from `frontend/AGENTS.md` and the existing code):
modified Next.js — consult `node_modules/next/dist/docs/` before writing; mobile-first
Tailwind; race-safe fetch (monotonic request id + `AbortController`) where async state is
kept; all client calls via the `/api/backend/...` proxy path.

---

## 1. Component design

The core is one reusable, self-contained component plus its two mount points.

### 1.1 `AddToPlanButton` (new) — the affordance on a card

`frontend/components/mealplan/AddToPlanButton.tsx`

A small `"use client"` button that, given a fixed recipe, opens the date/slot sheet and
performs the add. It owns its own transient UI state (idle → open → submitting → success /
error) so a card doesn't have to.

Props:

```ts
interface AddToPlanButtonProps {
  source: RecipeSource;     // "CATALOG" | "SAVED"  (from types/mealPlan)
  recipeId: string;         // catalogRecipeId (CATALOG) or recipeId (SAVED)
  title?: string;           // for the sheet header + success message
  className?: string;       // let the host card style/size it
  variant?: "button" | "icon"; // full button (saved card action row) vs compact icon (catalog card)
}
```

Behavior:

- Renders a touch-sized control labeled "Add to plan" (or a `Plus`/calendar `lucide-react`
  icon in `variant="icon"` for the dense catalog grid).
- `onClick`: `stopPropagation()` + `preventDefault()` (critical on the catalog card, whose
  whole surface is a `<Link>` — Req 1.2) and opens `AddToPlanSheet`.
- Guards a blank `recipeId`: if empty, the control is not rendered / disabled (Req 1.5, 2.2).
- Shows an in-flight state (disabled + `Loader2` spinner) while adding (Req 5.3).
- On success, shows a brief confirmed state and/or triggers a toast (see §3); on error,
  surfaces a retryable message (Req 5.1, 5.2).

### 1.2 `AddToPlanSheet` (new) — the date/slot picker

`frontend/components/mealplan/AddToPlanSheet.tsx` (may live in the same file as the button if
small).

The **inverse** of `RecipePicker`: the recipe is already known, so this sheet only collects
**when**. It reuses the exact bottom-sheet skeleton from `RecipePicker`/`EntryEditor`:

- Overlay: `fixed inset-0 z-50 flex flex-col bg-white sm:items-center sm:justify-center
  sm:bg-black/40 sm:p-4`, `role="dialog" aria-modal="true"`.
- Panel: `flex h-full w-full flex-col sm:h-auto sm:max-w-md sm:rounded-xl sm:bg-white
  sm:shadow-xl`, header with title (the recipe title) + `X` close.

Controls (all touch-first, Req 3):

| Control | Detail |
|---|---|
| Date | Native `<input type="date">` defaulting to today's local ISO (`todayIso()` from `lib/calendar.ts`). No hand-typed ISO. |
| Slot | Segmented buttons or a `<select>` from `MEAL_SLOTS` (`lib/mealSlots.ts`); default `DINNER` (matches the calendar month-view default). |
| Span (optional) | The same 1–14 day meal-prep `<select>` used in `RecipePicker`; default 1. |
| Confirm | "Add to plan" primary button; disabled while submitting. |

On confirm the sheet hands `{ date, slot, spanDays }` back to `AddToPlanButton`, which
performs the add (§2). Dismiss (X / overlay / cancel) closes with no change (Req 3.5).

Reuse note: date/ISO helpers come from the existing pure `lib/calendar.ts` (`todayIso`,
`ISODate`) so we keep local-day semantics (no UTC drift), consistent with the calendar.

### 1.3 Mount points

- **Saved recipes — `RecipeCard`** (`frontend/components/recipe/RecipeCard.tsx`): add
  `AddToPlanButton` (`variant="button"`, `source="SAVED"`, `recipeId={effectiveId}`) into the
  existing action row `<div className="mt-auto flex gap-2">`, alongside View/Delete. Gate on
  `effectiveId != null && saved` (Req 2.1, 2.2). The button is **not** inside the outer link
  here, so no click-bubbling issue — but `AddToPlanButton` guards anyway.
- **Catalog — `/browse` inline card** (`frontend/app/(protected)/browse/page.tsx`): the whole
  card is a `<Link href={/browse/${r.catalogRecipeId}}>`. Add `AddToPlanButton`
  (`variant="icon"`, `source="CATALOG"`, `recipeId={r.catalogRecipeId}`) as a small overlay
  control (e.g. top-right of the image or in the card footer) whose click is stopped from
  bubbling into the link (Req 1.2). Do not restructure the card beyond inserting the control.

- **Catalog detail — `/browse/[id]`** (`frontend/app/(protected)/browse/[id]/page.tsx`):
  mount `AddToPlanButton` (`variant="button"`, `source="CATALOG"`,
  `recipeId={recipe.catalogRecipeId}`) in the header row next to the title, styled as a
  primary button via `className`. Not inside a link here, so no bubbling concern.

- **Saved-recipe detail — `/recipes/[id]`** (`frontend/app/(protected)/recipes/[id]/page.tsx`):
  same mount as the catalog detail page but `source="SAVED"`, `recipeId={recipe.recipeId}`,
  next to the title.

Because `AddToPlanButton` is self-contained and parameterized by `{source, recipeId, title}`,
both detail-page mounts are one-liners over the existing add path — no API change.

---

## 2. Data flow (add)

```
user taps "Add to plan" (card, recipe known)
      │
      ▼
AddToPlanSheet opens → user picks date + slot (+ optional span) → Confirm
      │
      ▼
AddToPlanButton.performAdd():
      plan = await getDefaultMealPlan()          // resolve the single calendar (Req 4.1)
      updated = await addEntry(plan.mealPlanId, {  // reuse existing helper (Req 4.2)
        date, slot, source, recipeId, spanDays,   // AddEntryRequest
      })
      → success toast / confirmed state           // Req 5.1
   catch → retryable error message                // Req 5.2, 5.4
```

Notes:

- `getDefaultMealPlan()` is called at confirm time (lazy), so a user who never opens the
  planner still gets a calendar created on first add. This avoids fetching the plan for every
  card on the page. (If a future perf concern arises, the resolved `mealPlanId` can be cached
  in a small module-level ref for the session — not needed now.)
- `AddEntryRequest` fields used: `date`, `slot`, `source`, `recipeId`, and `spanDays` (omit or
  1 = single day). `servings` is left unset here (same as the in-calendar add path); it's
  edited later via the calendar's `EntryEditor`.
- The component keeps only transient local state; it does not hold the whole plan. It doesn't
  need the `MealPlan` after the add (the calendar page reloads its own copy when opened). If
  it maintains async state across renders it uses the race-safe pattern (Req 4.4), though the
  add is a one-shot on confirm so a simple in-flight boolean suffices.

---

## 3. Feedback pattern (no new library)

There is no shared Toast component in the app. Two established options, used together:

1. **Inline confirmed state** on the button (like `RecipeCard`'s `saved_`/`saving` flags):
   the button shows a spinner while adding and a brief "Added ✓" afterward, then resets.
2. **Transient success toast** mirroring `account/dietary/page.tsx`: a `showSuccess` boolean
   with `SUCCESS_TOAST_MS` (3000ms) auto-dismiss via `setTimeout` in a `useEffect`. If a
   page-level toast reads better than an inline state for the dense catalog grid, lift the
   toast to the page hosting the cards.

Errors reuse the same transient surface with an error style and a retry affordance (the sheet
can stay open on failure so the user retries without re-picking). Backend refusals (e.g. a
403 on a pending-deletion account, or a 4xx on an unresolvable reference) arrive as a thrown
`Error("Request failed: <status>")` from `mealPlanApi`; the component maps that to a friendly
message (Req 5.2, 5.4, 6.2).

---

## 4. Types & reuse (no new types needed)

- `RecipeSource`, `AddEntryRequest` — `frontend/types/mealPlan.ts` (already defined).
- `MEAL_SLOTS`, `MealSlot`, `mealSlotLabel` — `frontend/lib/mealSlots.ts` (single source of
  truth; do not re-declare slots).
- `getDefaultMealPlan`, `addEntry` — `frontend/lib/mealPlanApi.ts` (already defined).
- `todayIso`, `ISODate` — `frontend/lib/calendar.ts`.

No new API helper is required. If a tiny convenience wrapper reads well (e.g.
`addRecipeToDefaultPlan(source, recipeId, {date, slot, spanDays})` that composes
`getDefaultMealPlan` + `addEntry`), it belongs in `lib/mealPlanApi.ts` next to the existing
helpers — optional, and it must not introduce a second network path.

---

## 5. Accessibility & mobile

- The sheet is a labeled dialog (`role="dialog"`, `aria-modal`, `aria-label` = recipe title),
  matching `RecipePicker`. Close button has `aria-label="Close"`.
- The button has an accessible label ("Add to plan"); the `variant="icon"` catalog version
  keeps a visually-hidden or `aria-label` text so it isn't icon-only to assistive tech.
- Tap targets sized for fingers; sheet is full-screen on phones, centered card on `sm:+`
  (Req 7.2). No horizontal scroll; nothing overlaps the catalog card's existing content
  (Req 7.4).

---

## 6. Testing

Vitest + Testing Library, matching the existing frontend tests (`meal-plans/page.test.tsx`,
`recipes/page.test.tsx`). Network is mocked at the `fetch`/helper boundary as the current
tests do.

- **`AddToPlanButton` / `AddToPlanSheet`:**
  - Tapping the button opens the sheet; the recipe title shows in the header.
  - Confirm calls `getDefaultMealPlan` then `addEntry` with the correct body
    (`source`, `recipeId`, chosen `date`/`slot`, `spanDays`); assert the exact payload.
  - Catalog `variant="icon"` click does not navigate (the surrounding link's default is
    prevented / propagation stopped).
  - Blank `recipeId` → the control does not add (guarded).
  - Success shows the confirmed state / toast; failure shows a retryable error and leaves the
    sheet open.
  - In-flight state disables the confirm button (no double submit).
- **Mount-point smoke:** `RecipeCard` renders "Add to plan" only when `saved` and an id
  exists; a browse card renders the affordance for each result; the catalog detail page
  renders it next to the title.
- **`lib/ingredient.ts`:** table-driven unit tests for `ingredientName` (plain names, mixed
  fractions, unit/descriptor stripping, parenthetical notes, unicode fractions, and the
  empty/lone-quantity fallbacks) — it drives the label shown on every browse card.

The sheet also implements modal keyboard/focus behavior (initial focus, Escape to close,
Tab/Shift+Tab containment, focus restoration to the trigger) and marks the date input
`required` so an empty date can't be submitted.

Run `npm run test` (and lint/typecheck) in `frontend/`. No backend tests change.

---

## 7. What this design deliberately excludes

- Any backend/infra change — the spec 1 API is complete for this.
- Bulk add and drag-and-drop — out of scope. (Adding from **both detail pages** —
  `/browse/[id]` and `/recipes/[id]` — is included, §1.3.)
- Changes to the in-calendar `RecipePicker` — untouched.
- A shared UI component library — we reuse the existing hand-rolled Tailwind patterns.
