# Design — Structured Ingredients

## Context

Third implementation spec of the Meal Planner (see `../meal-planner/ROADMAP.md` spec 3 and
this folder's `requirements.md`). It adds a structured `{quantity, unit, item, raw}`
representation of recipe ingredients **additively**, alongside the existing free-text
`List<String> ingredients`, which is retained unchanged as the authoritative display text.
This is the prerequisite for the Grocery List (spec 4) and servings-aware scaling.

Backend stack: Spring Boot + AWS DynamoDB (Enhanced Client), Bedrock for generation,
self-hosted OpenSearch (basic auth) for catalog search. Frontend: Next.js App Router.

### How ingredients work today (the ground this builds on)

Everything is `List<String>` with the quantity inline (`"2 cups flour"`), end to end:

- **Models** — `Recipe.ingredients` (`model/Recipe.java`) and `CatalogRecipe.ingredients`
  (`model/CatalogRecipe.java`) are both `List<String>`. Neither has a per-field DynamoDB
  annotation (only the PK/GSI keys are annotated), so the enhanced mapper maps the list
  automatically and a **new parallel field is a non-breaking storage addition**.
  `CatalogRecipe` additionally persists `searchText` and a 1024-dim `embedding` computed
  once at ingestion.
- **DTOs** — `RecipeDto`, `CatalogRecipeDto`, `GenerateRecipeResponse` all expose
  `List<String> ingredients` 1:1; `SaveRecipeRequest` validates it
  (`@Size(min=1,max=50)`, each `@NotBlank @Size(max=500)`).
- **Generation** — `service/BedrockService.java#buildPrompt` asks for `"ingredients"
  (array of strings with quantities)`; `#parseResponse` slices the first `[`…`]` out of the
  model text and reads `recipe.path("ingredients").forEach(i -> …asText())` into
  `List<String>`. No schema, no structured parsing.
- **Catalog ingestion** — `ingest/CatalogIngestionRunner` streams `ParsedRecipe`
  (`List<String> ingredients`) from `RecipeNlgCsvSource` (CSV JSON array) and
  `XlsxMealDbSource`. Notably, the xlsx source already has **separate** `ingredients` and
  `quantity` columns and *concatenates* them (`q + " " + n`) into the inline string — the
  structure exists in the source and is currently thrown away. Ingredients feed three
  derived artifacts persisted on `CatalogRecipe`: `dietaryTags` (`DietaryTagger`),
  `searchText`, and the `embedding` input.
- **Search** — `OpenSearchIndexProvisioner` maps `ingredients` as analyzed `text` (indexed,
  searchable) and `embedding` as the **only** `knn_vector`. `OpenSearchCatalogSearchService`
  multi-matches `title^3, description, ingredients` and runs knn on `embedding`. The
  reindex projects the `CatalogRecipe` bean verbatim (minus `searchText`) and **never
  re-embeds**.
- **Frontend** — `types/recipe.ts` uses `ingredients: string[]`. Detail pages render each
  string as an `<li>`; the browse list uses `lib/ingredient.ts#ingredientName` — a
  display-only, lossy heuristic that strips a leading quantity/unit/descriptor to show just
  the name. `DietaryTagger` is the only backend code that inspects ingredient text, and it
  only does keyword matching over a joined blob — it does **not** parse structure.

### The key architectural fact (why this is cheap)

The expensive, breaking thing on this stack is the `knn_vector` mapping / re-embedding. The
embedding is derived from title+description+ingredients **text** and is computed **once** at
ingestion, then copied verbatim on reindex. **This spec does not change the embedding
input**, so it triggers **no re-embed and no vector rebuild**. A structured field is either
kept DynamoDB-only or added to OpenSearch as a plain **non-vector** field populated by an
in-place field update — both additive, both cheap.

### A note on UI iteration

As with Meal Plan Core, exact rendering/wording here is a starting point, not a fixed
contract. The requirements pin behavior (no regression, graceful fallback); the look can be
refined after hands-on use. UI tweaks that don't change API data need no backend change.

---

## 1. Data model

### 1.1 `StructuredIngredient` (new, shared nested type)

A single ingredient broken into parts, carrying the original string so the two
representations never diverge.

| Field | Type | Notes |
|---|---|---|
| `quantity` | `Double` | Nullable. Parsed leading amount (`2`, `0.5`, `1.5` from `1 1/2`). Null = no parseable amount ("salt to taste"). |
| `unit` | `String` | Nullable. Lightly-normalized unit token (`cup`, `tsp`, `g`). Null = no unit (e.g. "3 eggs"). |
| `item` | `String` | Non-blank. The ingredient name with quantity/unit stripped (`flour`). |
| `raw` | `String` | Non-blank. The original string verbatim (`"2 cups flour"`). Authoritative fallback. |

Rationale:
- **`quantity` is `Double`, not a string**, because spec 4 multiplies it (scaling) and sums
  it (grocery merge). A string here would just push parsing downstream again.
- **`unit` is a plain token**, stored roughly as authored (lowercased, de-pluralized where
  trivial). Canonical conversion (tsp↔tbsp↔cup) is deliberately deferred to the grocery
  spec so the conversion table lives with the feature that needs it (ROADMAP: build
  just-in-time).
- **`raw` is always kept**, making parsing lossless at the string level: however rough the
  breakdown, the exact original is recoverable, and display can always use `raw`.

Backend: a nested `@DynamoDbBean` (Lombok `@Data/@Builder/@NoArgsConstructor`) so the
enhanced mapper persists a `List<StructuredIngredient>` inside the recipe item with no
converter. Lives in `model/` next to the recipe models and is reused by DTOs.

### 1.2 Additive fields on the recipe models

- `Recipe` gains `private List<StructuredIngredient> structuredIngredients;` **in addition
  to** `List<String> ingredients` (unchanged, still authoritative display text).
- `CatalogRecipe` gains the same field, additive to its existing `ingredients`,
  `searchText`, `embedding`, `dietaryTags`.

Because neither bean annotates `ingredients` and DynamoDB is schemaless, this is a
non-breaking addition: existing items simply read back with `structuredIngredients == null`
(Req 1.5), treated as "not backfilled yet."

### 1.3 Positional correspondence invariant

When `structuredIngredients` is present it has **the same size and order** as `ingredients`
— one structured entry per raw string, `structuredIngredients[i].raw == ingredients[i]`
(Req 1.3). This keeps the two reconcilable and lets any consumer zip them. A producer that
can't honor this (count mismatch from the model) derives the whole structured list from the
strings instead of partially trusting it (§3).

---

## 2. The parser (`IngredientParser`)

One authoritative, deterministic, side-effect-free server-side component
(`ingest/IngredientParser` or `service/IngredientParser`) — the single source of structured
data for the generation fallback, the save path, and the backfill (Req 5). It is the
server-side promotion of the logic already proven in `frontend/lib/ingredient.ts`, extended
to also return the quantity and unit it strips (the frontend throws those away; we keep
them).

```
StructuredIngredient parse(String raw)
```

Algorithm (mirrors `ingredientName`, then captures what it skips):

1. Trim; keep the original as `raw`. Drop parenthetical notes for parsing only
   (`"flour (sifted)"` → parse `"flour"`, but `raw` stays the original).
2. Tokenize on whitespace. Consume a **leading quantity run**: integers, decimals,
   `a/b` fractions, `a b/c` mixed numbers, and unicode fractions (`¼½¾⅓⅔⅛`). Convert to a
   `Double` (sum the mixed/fraction parts). If none, `quantity = null`.
3. If a quantity was consumed, optionally consume **one unit token** against a maintained
   unit set (cup/cups/c, tsp, tbsp, oz, lb, g, kg, ml, l, clove, can, slice, …) — the same
   vocabulary as `lib/ingredient.ts#UNITS` — and skip trailing size descriptors
   (small/medium/large/…). Normalize the matched unit to a canonical token (lowercase,
   singularize trivial plurals). If none matched, `unit = null`.
4. The remaining tokens are the `item`. If empty (nothing left after stripping), fall back
   to the full trimmed string as `item` so `item` is never blank (Req 5.3).
5. Never throws; worst case returns `{quantity:null, unit:null, item:raw, raw}`.

Unit vocabulary lives in one place server-side (a constant set / small config), mirroring
the frontend set so the two stay consistent (Req 5.2). A tiny shared list is acceptable
duplication across languages; if drift becomes a problem later, generate one from the other.

A `parseAll(List<String>) -> List<StructuredIngredient>` convenience preserves order/count
(the §1.3 invariant).

Because it's pure and allocation-light, it runs fine across the ~2.2M catalog in the
backfill (Req 5.5).

---

## 3. Generation path (`BedrockService`)

### 3.1 Prompt change (ask for structure, keep the string)

Extend `buildPrompt`'s output instruction so each ingredient carries both the display
string and its parts. Target JSON per recipe:

```json
{
  "title": "…",
  "description": "…",
  "ingredients": ["2 cups flour", "1/2 tsp salt", "3 eggs"],
  "structuredIngredients": [
    { "quantity": 2,   "unit": "cup", "item": "flour" },
    { "quantity": 0.5, "unit": "tsp", "item": "salt" },
    { "quantity": 3,   "unit": null,  "item": "eggs" }
  ],
  "steps": ["…"]
}
```

The instruction states: keep `ingredients` as today (array of display strings with
quantities), and add a parallel `structuredIngredients` array of the same length and order,
each `{quantity (number|null), unit (string|null), item (string)}`. The existing
pantry-staple seeding and dietary clause are unchanged.

### 3.2 Parsing + fallback (tolerant, never-fail)

In `parseResponse`, after reading `ingredients` as today:

1. If the recipe node has a `structuredIngredients` array whose length **equals** the
   `ingredients` length, map it into `List<StructuredIngredient>`, setting each entry's
   `raw` to the corresponding `ingredients[i]` string (the display string stays
   authoritative). Coerce `quantity` to `Double` (null-tolerant), `unit` to a normalized
   token, require non-blank `item` (fall back to parsing that one string if blank).
2. **Otherwise** (absent, malformed, or length mismatch — Req 3.3) derive the whole
   structured list via `IngredientParser.parseAll(ingredients)`.

This makes the contract backward-tolerant (Req 3.4): a model that ignores the new
instruction and returns only strings still yields a fully structured recipe via the parser,
and generation never fails on the new field. `GenerateRecipeResponse` gains the additive
`structuredIngredients` field; dietary tagging is untouched (still over the text).

The brittle `indexOf('[')…lastIndexOf(']')` slice is retained as-is (out of scope to
rework); only the per-recipe field handling is extended.

---

## 4. Save path (`RecipeService` / `SaveRecipeRequest`)

- `SaveRecipeRequest` gains an **optional** `List<StructuredIngredientRequest>` (Lombok +
  Jakarta validation: list size bounded to match the string list bound; `item`/`raw`
  non-blank; `quantity` `@PositiveOrZero` when present; `unit` `@Size`-bounded) — Req 4.3.
  The string `ingredients` stays the only **required** ingredient input (Req 4.4).
- On save (`RecipeService.saveRecipe`): if the request carries structured ingredients and
  they satisfy the §1.3 count invariant, persist them (binding each `raw` to the submitted
  string); otherwise derive them with `IngredientParser.parseAll(request.getIngredients())`
  before persisting (Req 4.2). Either way the saved `Recipe` has a populated
  `structuredIngredients`.
- `RecipeDto` / `CatalogRecipeDto` gain the additive `structuredIngredients` field (Req 2);
  it is simply mapped through. When absent on an un-backfilled catalog record, it serializes
  as empty/absent (Req 2.2) and the string `ingredients` is unaffected.

No new endpoint; the data rides existing create/read responses (Req 2.4).

---

## 5. Catalog ingestion + backfill

### 5.1 New ingestion (forward)

`ParsedRecipe` gains an optional `structuredIngredients`. In `CatalogIngestionRunner`, after
a recipe's `ingredients` are known, populate the field (via the parser, or directly for
`XlsxMealDbSource`, which already has the separate quantity+name columns — §Context — and
can emit structure without re-parsing). This is additive; `searchText`, `dietaryTags`, and
the **embedding input stay exactly as they are** (Req 6.4) — the structured field is not fed
into the embedding, so vectors are unchanged.

### 5.2 Backfill of existing data (the main operational work)

A one-off, operator-gated runner in the style of the existing `catalog.reindex.*` /
`catalog.ingest.*` flags (Req 6.1). Proposed config (defaults off, env-overridable like the
others):

```
catalog.structured.backfill.enabled=false   # master switch for the catalog backfill
catalog.structured.backfill.batch-size=500
catalog.structured.backfill.skip-records=0   # windowing for the 2.2M set (like recipenlg-skip-records)
catalog.structured.backfill.max-records=0    # 0 = no cap
catalog.structured.backfill.force=false      # re-parse even if structuredIngredients already set
```

Behavior:
- Scan/stream `CatalogRecipe` items; for each with `structuredIngredients == null` (or when
  `force`), compute `parseAll(ingredients)` and write **only that attribute** back
  (DynamoDB `UpdateItem` on the one field, not a full rewrite) — idempotent and resumable
  (Req 6.3). Windowing via `skip-records`/`max-records` lets the 2.2M set be done in passes,
  mirroring `recipenlg-skip-records`.
- **No embeddings touched, no index recreate** (Req 6.4). Progress + final
  seen/updated/skipped/failed summary; a per-record failure is logged and skipped, never
  aborting the run (Req 6.6).
- **Saved `Recipe` backfill** (Req 6.2): a parallel path (separate flag, e.g.
  `recipes.structured.backfill.enabled`) that scans the `Recipe` table and does the same
  single-attribute update. Smaller and simpler (no catalog/OpenSearch concerns).

### 5.3 OpenSearch (only if we project the field)

Default: **keep `structuredIngredients` DynamoDB-only.** Search already matches the
`ingredients` text field; this spec adds no structured search (Req: out of scope), so there
is no functional need to index the structured field. Keeping it out of OpenSearch is the
smallest change and avoids any index work.

If a later need (or convenience) argues for projecting it:
- Add it to `OpenSearchIndexProvisioner` as an **additive, non-vector** field — a
  `nested`/`object` of `{quantity: float, unit: keyword, item: text}` or a flattened
  `keyword` list. This does **not** touch the `embedding` `knn_vector` mapping (Req 6.5).
- Populate it via the existing reindex/backfill mechanics in **backfill mode** (in-place
  bulk update of the new field only), never a recreate-index and never a re-embed. OpenSearch
  allows adding a brand-new field to an existing mapping; it does not allow changing an
  existing field's type, so this stays strictly additive.

The design keeps this optional and clearly fenced so the default path requires zero
OpenSearch changes.

---

## 6. Frontend

### 6.1 Types

`types/recipe.ts` gains:

```ts
export interface StructuredIngredient {
  quantity: number | null;
  unit: string | null;
  item: string;
  raw: string;
}
```

and an **optional** `structuredIngredients?: StructuredIngredient[]` on `Recipe` /
`GeneratedRecipe` (and the browse pages' local catalog type), alongside the unchanged
`ingredients: string[]` (Req 7.1). Optional because un-backfilled data won't have it.

### 6.2 Rendering with graceful fallback

A small helper (e.g. `lib/ingredient.ts#displayItem(entry | rawString)`) centralizes the
"prefer structured, fall back to heuristic" rule:
- If a `StructuredIngredient` is available, use its `item` (and show `quantity`/`unit` where
  useful) directly — no heuristic needed.
- Else fall back to the existing `ingredientName(rawString)` heuristic over the string
  (Req 7.2), so un-backfilled recipes render exactly as today.

Apply it where ingredients already render — the browse list summary (currently
`r.ingredients.map(ingredientName)`), the detail `<li>` lists, and the saved-recipe card —
each gaining structured awareness with a string fallback, no visible regression for either
data state (Req 7.3). No new editing UI (Req 7.4).

### 6.3 Data fetching

Unchanged — structured data arrives on the existing recipe/catalog responses; no new
request or state wiring.

---

## 7. Compliance, limits, consistency

- **Data export** (Req 8.1): add `structuredIngredients` to the exported recipe shape in
  `DataExportService` / `DataExportJson` (and thus the ZIP path), additive to the existing
  recipe fields — references no new external data.
- **Hard-deletion** (Req 8.2): no new step; the field lives inside the existing `Recipe`
  item and is deleted with it. Catalog is shared/not user-owned, as today.
- **Limits** (Req 8.3): the structured list is bounded to the same count as the string
  ingredient bound (≤ 50), each `item`/`unit`/`raw` length-bounded, keeping the item well
  under DynamoDB's 400 KB limit.
- **Generation machinery** (Req 8.4): consent, demo quota, and rate limiting are unchanged;
  no new AI calls — structured output rides the same single generate request.

---

## 8. Testing

- **Parser unit tests** (backend): integers/decimals/fractions/mixed/unicode quantities;
  unit + descriptor stripping; no-quantity ("salt to taste") → null/null/item; empty-item
  fallback to raw; `raw` always preserved. Port the `lib/ingredient.test.ts` cases plus new
  quantity/unit assertions. jqwik property: `parse(raw).raw == raw` and `item` non-blank for
  all inputs.
- **Generation** (`BedrockServicePromptTest` / parse tests): prompt includes the structured
  instruction; `parseResponse` uses well-formed `structuredIngredients`; falls back to the
  parser on absent / malformed / length-mismatch; length/order invariant holds; string
  `ingredients` unchanged; dietary tagging unaffected.
- **Save path**: structured persisted when valid; derived when omitted; validation
  rejections → 400 via `GlobalExceptionHandler`; string `ingredients` still required.
- **Backfill**: idempotent (second run is a no-op without `force`); resumable via
  skip/max windowing; single-attribute update (doesn't touch embedding/searchText);
  per-record failure skipped not fatal; summary counts. Catalog and saved-recipe paths.
- **DTO/export**: structured field present in recipe/catalog read DTOs when set,
  absent/empty when not; export carries it.
- **Frontend** (Vitest): `displayItem` prefers structured, falls back to `ingredientName`;
  detail/browse/card render correctly for both backfilled and un-backfilled recipes;
  `ingredients: string[]` consumers unaffected.

Follows existing conventions (JUnit + Spring slices, jqwik where valuable; Vitest). No new
framework. Backend `./mvnw test` and frontend `npm run test` green.

---

## 9. What this design deliberately defers (per ROADMAP)

- **Grocery list** (unit normalization across a plan, item merging, pantry subtraction) →
  spec 4. This spec only produces the per-recipe structured data it will aggregate.
- **Servings scaling** of quantities → spec 4 (multiplies this spec's numeric `quantity`).
- **Canonical unit conversion** (tsp↔tbsp↔cup, g↔oz) → grocery spec, where the conversion
  table belongs.
- **Structured-ingredient search/filtering** → not built; keyword search over the text
  field is unchanged, and the structured field stays DynamoDB-only by default (§5.3).
- **Nutrition** → spec 6 (uses structured quantities + a nutrition source).
- **Re-embedding / vector changes** → explicitly none; the embedding input is untouched.
