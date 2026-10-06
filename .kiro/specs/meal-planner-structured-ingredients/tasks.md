# Tasks — Structured Ingredients

> Status: in progress. Tasks 1–7 complete (generate + save path end-to-end, plus forward
> catalog ingestion). Backend tests pass (`./mvnw test`, 262). Remaining: backfills (8–9),
> optional OpenSearch projection (10), export (11), backend verify (12), frontend (13–15),
> RUNBOOK (16).
>
> All work is **additive and non-breaking**: the existing `List<String> ingredients` stays as
> the authoritative display text everywhere; a parallel structured field is added alongside it.
> No embeddings are recomputed and no OpenSearch index is recreated.

Implementation plan. Each task is incremental and test-backed. Requirement references map to
`requirements.md`; design references map to `design.md`. Backend precedes frontend so the
contract is real before the UI consumes it. Nothing here changes the `knn_vector` mapping or
the embedding input.

- [x] 1. Add the shared `StructuredIngredient` type
  - Add `model/StructuredIngredient` as a nested `@DynamoDbBean` (Lombok
    `@Data/@Builder/@NoArgsConstructor`) with `Double quantity`, `String unit`, `String
    item`, `String raw`. Reused by models and DTOs (defined once).
  - _Requirements: 1.1, 1.6 | Design: §1.1_

- [x] 2. Implement the server-side `IngredientParser`
  - One deterministic, side-effect-free component: `parse(String raw) ->
    StructuredIngredient` and `parseAll(List<String>) -> List<StructuredIngredient>`
    (preserving order/count). Extract leading quantity (int/decimal/`a/b`/`a b/c`/unicode
    fractions) → `Double`; optional following unit from a maintained unit set mirroring
    `frontend/lib/ingredient.ts#UNITS` (normalized token); skip size descriptors; remaining
    tokens → `item`; empty `item` falls back to the full trimmed string; `raw` always the
    original. Never throws.
  - Unit tests: port `lib/ingredient.test.ts` cases + quantity/unit assertions; "salt to
    taste" → null/null/item; jqwik property `parse(raw).raw == raw` and `item` non-blank for
    all inputs.
  - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5 | Design: §2_

- [x] 3. Add the structured field to the recipe models
  - Add `List<StructuredIngredient> structuredIngredients` to `Recipe` and `CatalogRecipe`,
    additive to the existing `ingredients` (unchanged). Confirm the enhanced mapper
    round-trips the nested list and that items without the field read back as null.
  - Repository round-trip test: save with/without the field; null-safe read.
  - _Requirements: 1.2, 1.5 | Design: §1.2_

- [x] 4. Thread the field through the DTOs (read + response)
  - Add `structuredIngredients` to `RecipeDto`, `CatalogRecipeDto`, and
    `GenerateRecipeResponse`, mapped straight through. Absent/empty when not set; the
    string `ingredients` field is never replaced or reordered.
  - Mapping tests: present when set, absent/empty when not; existing `ingredients`
    unaffected.
  - _Requirements: 2.1, 2.2, 2.3, 2.4 | Design: §4_

- [x] 5. Generation: prompt + tolerant parse with fallback
  - Extend `BedrockService#buildPrompt` output instruction to request a parallel
    `structuredIngredients` array (same length/order as `ingredients`), each `{quantity
    (number|null), unit (string|null), item (string)}`; keep the string `ingredients` and
    the pantry/dietary clauses unchanged.
  - In `parseResponse`: use the model's `structuredIngredients` only when its length equals
    `ingredients` (binding each `raw` to the display string); otherwise derive via
    `IngredientParser.parseAll(ingredients)`. Enforce the size/order invariant; never fail
    generation on the new field; dietary tagging untouched.
  - Tests: prompt contains the instruction; well-formed structured used; fallback on
    absent/malformed/length-mismatch; string ingredients unchanged.
  - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 1.3 | Design: §3_

- [x] 6. Save path: validate, persist, derive-on-omit
  - Add an optional `List<StructuredIngredientRequest>` to `SaveRecipeRequest` with Jakarta
    validation (list size matching the string-list bound; `item`/`raw` `@NotBlank`;
    `quantity` `@PositiveOrZero`; `unit` `@Size`). In `RecipeService.saveRecipe`, persist
    the submitted structured ingredients when valid and count-matching, else derive from the
    submitted strings via the parser. String `ingredients` stays the only required input.
  - Tests: persisted when valid; derived when omitted; validation → 400 via
    `GlobalExceptionHandler`.
  - _Requirements: 4.1, 4.2, 4.3, 4.4, 8.3 | Design: §4_

- [x] 7. Forward ingestion populates the structured field
  - `ParsedRecipe` gains optional `structuredIngredients`. In `CatalogIngestionRunner`,
    populate it per recipe (via the parser; `XlsxMealDbSource` MAY emit structure directly
    from its already-separate quantity/name columns). Additive only — `searchText`,
    `dietaryTags`, and the **embedding input are unchanged** (no re-embed).
  - Tests: ingested catalog recipe carries structured ingredients; embedding input text
    unchanged (regression guard).
  - _Requirements: 6.4, 1.3 | Design: §5.1_

- [ ] 8. Catalog backfill runner (idempotent, resumable, no re-embed)
  - Operator-gated runner with `catalog.structured.backfill.*` config (enabled, batch-size,
    skip-records, max-records, force; defaults off, env-overridable like the existing
    `catalog.*` flags). Stream `CatalogRecipe`; for each with null `structuredIngredients`
    (or `force`), compute `parseAll(ingredients)` and write **only that attribute** via
    DynamoDB `UpdateItem`. Windowing via skip/max for the 2.2M set. Never recreates the
    index, never re-embeds. Progress + seen/updated/skipped/failed summary; per-record
    failure logged and skipped, not fatal.
  - Tests: second run is a no-op without `force`; windowing; single-attribute update does
    not touch embedding/searchText; failure isolation; summary counts.
  - _Requirements: 6.1, 6.3, 6.4, 6.6 | Design: §5.2_

- [ ] 9. Saved-recipe backfill
  - Parallel operator-gated path (`recipes.structured.backfill.*`) scanning the `Recipe`
    table, same single-attribute update from `parseAll(ingredients)`. Idempotent/resumable;
    smaller and simpler (no catalog/OpenSearch concerns).
  - Tests: idempotency, failure isolation, summary.
  - _Requirements: 6.2, 6.3, 6.6 | Design: §5.2_

- [ ] 10. (Optional, deferred by default) OpenSearch projection of the structured field
  - ONLY if a concrete need arises. Add `structuredIngredients` to
    `OpenSearchIndexProvisioner` as an **additive, non-vector** field (nested/object or
    flattened keyword) — does NOT touch the `embedding` `knn_vector` mapping — and populate
    via the existing reindex **backfill mode** (in-place field update only; no recreate, no
    re-embed). Default path leaves this undone and keeps the field DynamoDB-only.
  - _Requirements: 6.5 | Design: §5.3_

- [ ] 11. Compliance: data export carries the structured field
  - Add `structuredIngredients` to the exported recipe shape in `DataExportService` /
    `DataExportJson` (JSON + ZIP paths), additive to existing recipe fields. No new
    hard-deletion step (field lives inside the `Recipe` item). Confirm the structured list
    stays within the existing ingredient bounds.
  - Tests: export includes the field; bounds enforced.
  - _Requirements: 8.1, 8.2, 8.3 | Design: §7_

- [ ] 12. Backend verification
  - Run `./mvnw test` in `backend/`; fix failures. Confirm: no change to embedding
    input/vectors; generation never fails on the new field; backfills idempotent.
  - _Requirements: 1–8 (verification) | Design: §8_

- [ ] 13. Frontend types + display helper
  - Add `StructuredIngredient` to `types/recipe.ts` and an optional
    `structuredIngredients?: StructuredIngredient[]` on `Recipe`/`GeneratedRecipe` (and the
    browse pages' local catalog type), alongside the unchanged `ingredients: string[]`.
  - Add `lib/ingredient.ts#displayItem(...)`: prefer a structured entry's `item` (with
    `quantity`/`unit` where useful), else fall back to `ingredientName(rawString)`.
  - Unit tests for `displayItem` (structured vs fallback).
  - _Requirements: 7.1, 7.2 | Design: §6.1, §6.2_

- [ ] 14. Frontend rendering with graceful fallback
  - Use `displayItem` where ingredients render today — browse list summary (currently
    `r.ingredients.map(ingredientName)`), recipe/catalog detail `<li>` lists, and the
    saved-recipe card — so backfilled recipes use structured data and un-backfilled ones
    render exactly as today. No new editing UI.
  - Tests: detail/browse/card render correctly for both backfilled and un-backfilled
    recipes; string-only consumers unaffected.
  - _Requirements: 7.2, 7.3, 7.4 | Design: §6.2_

- [ ] 15. Frontend verification
  - Run `npm run test` (and lint/typecheck) in `frontend/`; fix failures.
  - _Requirements: 7.* (verification) | Design: §8_

- [ ] 16. RUNBOOK
  - Add `RUNBOOK.md`: the backfill flags and how to run catalog vs saved-recipe backfills
    (including windowing for the 2.2M set), the explicit "no re-embed / no index recreate"
    guarantee, how to verify structured data on a sample recipe, and a note that pure UI
    display tweaks need no backend change.
  - _Requirements: 6.1, 6.3 | Design: §5.2_

---

## Sequencing notes

- Tasks 1–6 deliver structured data on the **generate + save** path end-to-end (new recipes
  are structured immediately), independently shippable before any backfill.
- Tasks 7–10 bring **existing/catalog** data up to parity; task 10 is explicitly optional
  and off by default.
- Tasks 13–14 are the **frontend consumption**, safe to land once DTOs (task 4) expose the
  field; they regress nothing because of the string fallback.
- The whole spec touches **no** `knn_vector` mapping and **no** embedding input, so there is
  no vector rebuild anywhere in this plan (the one expensive/breaking operation on this
  stack is deliberately avoided).
