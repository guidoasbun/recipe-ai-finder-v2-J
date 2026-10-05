# Requirements — Structured Ingredients

## Overview

The third spec of the Meal Planner feature (see `../meal-planner/ROADMAP.md`, spec 3). It
adds a **structured** representation of a recipe's ingredients — a `{quantity, unit, item}`
breakdown — **additively alongside** the existing free-text `List<String>` ingredients.
Nothing that reads the current string list changes; the structured form is a new, parallel
field that richer features can consume.

This spec exists because the next big planner payoff — the **Grocery List** (spec 4) —
needs to aggregate ingredients across a plan's recipes (merge "2 cups flour" + "1 cup
flour" → "3 cups flour"), which is impossible against opaque strings. Servings-aware
quantity scaling (deferred from Meal Plan Core) also depends on it. Structured ingredients
are the prerequisite that unblocks both.

Today, an ingredient is a free-text phrase with the quantity baked inline (e.g.
`"2 cups flour"`), stored as `List<String>` end-to-end: `Recipe.ingredients` and
`CatalogRecipe.ingredients` (`model/Recipe.java`, `model/CatalogRecipe.java`), every DTO
(`RecipeDto`, `CatalogRecipeDto`, `GenerateRecipeResponse`, `SaveRecipeRequest`), the
Bedrock generation prompt and parser (`service/BedrockService.java`), catalog ingestion
(`ingest/*`), and the frontend (`types/recipe.ts`, `string[]`). The only structured parsing
that exists anywhere is `frontend/lib/ingredient.ts` (`ingredientName`), a display-only,
lossy heuristic that strips a leading amount to show just the name on browse cards.

### Scope decisions (agreed)

- **Additive, never breaking.** The structured representation is a **new field**; the
  existing `List<String> ingredients` stays exactly as it is and remains the authoritative
  display text. No existing reader (search, dietary tagging, display, export) is forced to
  change. DynamoDB is schemaless and the two recipe models carry no per-field annotations
  on `ingredients`, so adding a parallel field is a non-breaking storage change.
- **Structured form is derived from, and reconcilable with, the string form.** Each
  structured ingredient preserves the original raw string it came from, so the two
  representations never silently diverge and a consumer can always fall back to the raw
  text.
- **Generation emits structured directly.** The Bedrock prompt is extended so new recipes
  come back with structured ingredients from the model (the most accurate source), with a
  **server-side parser as the fallback** when the model omits or malforms the structure.
- **Existing data is backfilled by parsing, not re-generating.** Saved recipes and the
  ~2.2M-row catalog gain structured ingredients via a parser over their existing strings —
  a one-off, idempotent, resumable backfill in the style of the existing catalog
  ingestion/reindex runners. No LLM calls, no re-embedding.
- **Search and embeddings are untouched.** The catalog's `knn_vector` embedding is computed
  once at ingestion from title+description+ingredients text and never recomputed on
  reindex. This spec does **not** change the embedding input, so **no re-embed and no
  vector rebuild**. If a structured field is projected into OpenSearch at all, it is an
  additive, non-vector mapping change populated by an in-place backfill.
- **No new recipe search semantics.** Keyword search already matches the `ingredients` text
  field; this spec does not add structured-ingredient filtering/search (that can come with
  enrichment/grocery specs if wanted).
- **No quantity scaling here.** This spec produces the structured data; it does **not**
  scale quantities by servings and does **not** build the grocery list. Those are spec 4,
  which consumes this.
- **Reuse existing conventions.** Backend: `@DynamoDbBean` nested types, Lombok
  `@Data/@Builder` DTOs, Jakarta validation, the `CommandLineRunner`-style gated backfill
  (`catalog.*` flags). Frontend: Next.js App Router under `(protected)`, mobile-first
  Tailwind. Note `frontend/AGENTS.md`: this is a modified Next.js — consult
  `node_modules/next/dist/docs/` before writing frontend code.

### Out of scope (later specs)

- The grocery list itself (unit normalization across a plan, item merging, pantry
  subtraction) — spec 4.
- Servings-aware ingredient scaling — spec 4 (uses this spec's quantities).
- Nutrition — spec 6 (needs structured quantities + a nutrition source).
- Cuisine / prep-time / servings enrichment on recipes — spec 5.
- Structured-ingredient search/filtering (e.g. "recipes using < 1 cup sugar").
- Changing the authoritative display text or removing the `List<String>` field.

---

## Requirement 1 — Structured ingredient representation (additive data model)

**User story:** As the developer, I want each ingredient available as a `{quantity, unit,
item}` structure in addition to its original text, so that later features can do math on
quantities without re-parsing strings, and nothing that reads the existing text breaks.

### Acceptance criteria

1. The system SHALL define a structured ingredient shape capturing at least: a numeric
   **quantity** (nullable), a **unit** (nullable string, e.g. `cup`, `tsp`, `g`), the
   **item** name (string), and the **raw** original ingredient string it was derived from.
2. `Recipe` and `CatalogRecipe` SHALL each gain a new field holding the list of structured
   ingredients, **in addition to** the existing `List<String> ingredients`, which SHALL be
   retained unchanged as the authoritative display text.
3. The structured list, when present, SHALL correspond positionally and in count to the
   existing `ingredients` string list (one structured entry per raw string), so the two
   representations are reconcilable and never silently diverge.
4. A structured entry SHALL always carry a non-empty `item` and `raw`; `quantity` and
   `unit` MAY be null when the source text has no parseable amount (e.g. "salt to taste").
5. Adding the structured field SHALL be a non-breaking storage change: existing persisted
   recipes without the field SHALL remain readable, with the structured list treated as
   absent (not an error) until backfilled.
6. The structured shape SHALL be defined once and reused across models and DTOs (not
   redefined ad hoc per layer), consistent with how the app shares vocabulary today.

## Requirement 2 — Expose structured ingredients through the API

**User story:** As a frontend/consumer, I want structured ingredients returned alongside the
text ones in the existing recipe responses, so that I can use whichever representation fits
without a new endpoint.

### Acceptance criteria

1. `RecipeDto` and `CatalogRecipeDto` SHALL include the structured ingredient list as an
   additive field, alongside the existing `ingredients` string list.
2. WHEN a recipe has no structured ingredients yet (not backfilled) THEN the field SHALL be
   returned as absent/empty rather than causing an error, and the string `ingredients`
   SHALL still be present and complete.
3. The structured field SHALL NOT replace or reorder the existing `ingredients` field in
   any response; existing clients that ignore the new field SHALL be unaffected.
4. No new endpoint is required; the structured data rides on the existing recipe/catalog
   read responses.

## Requirement 3 — Generate structured ingredients for new recipes

**User story:** As a user generating recipes, I want the AI to produce ingredients with
their quantity, unit, and item already separated, so that newly generated recipes are
grocery-list-ready without lossy re-parsing.

### Acceptance criteria

1. The recipe-generation prompt SHALL instruct the model to return, for each ingredient,
   both the human-readable display string AND its structured `{quantity, unit, item}`
   parts.
2. WHEN the model returns well-formed structured ingredients THEN the system SHALL use them
   to populate the structured field, and SHALL continue to populate the existing
   `ingredients` string list (from the display strings) exactly as today.
3. IF the model's response omits the structured parts, returns them malformed, or returns a
   count that does not match the display strings THEN the system SHALL fall back to deriving
   the structured field from the display strings via the server-side parser (Requirement 5),
   rather than failing the generation.
4. The generation response contract change SHALL be backward tolerant: a response that still
   contains only the string ingredients SHALL be handled (parsed into structured) without
   error, so a model that ignores the new instruction never breaks generation.
5. Structured generation SHALL NOT change the existing dietary-tagging behavior, which
   continues to operate over the ingredient text.

## Requirement 4 — Save path preserves and validates structured ingredients

**User story:** As a user saving a recipe, I want its structured ingredients persisted with
it, so that saved recipes carry the same grocery-list-ready data as when they were
generated.

### Acceptance criteria

1. WHEN a generated recipe is saved THEN the system SHALL persist its structured
   ingredients alongside the string ingredients.
2. IF a save request provides no structured ingredients (e.g. an older client, or a recipe
   whose structure was lost) THEN the system SHALL derive them from the submitted string
   ingredients via the parser before persisting, so saved recipes are not left without
   structure.
3. Structured ingredient input SHALL be validated consistent with existing request
   validation (bounded list size matching the string-ingredient bounds; `item`/`raw`
   non-blank; `quantity` non-negative when present; `unit` length-bounded), surfacing
   violations through the existing `GlobalExceptionHandler`.
4. The save path SHALL NOT require structured ingredients from the client; the string
   ingredients remain the only required ingredient input (Requirement 1's additive rule).

## Requirement 5 — Server-side ingredient parser

**User story:** As the developer, I want one authoritative server-side parser that turns an
ingredient string into `{quantity, unit, item}`, so that generation fallback, the save
path, and the backfill all produce consistent structured data from the same logic.

### Acceptance criteria

1. The system SHALL provide a single server-side parser that converts a raw ingredient
   string into a structured ingredient, extracting a leading quantity (including integers,
   decimals, simple fractions like `1/2`, mixed numbers like `1 1/2`, and common unicode
   fractions), an optional following unit, and the remaining item text.
2. The parser SHALL recognize a configurable/maintained set of common units and their
   abbreviations/plurals (cup/cups/c, tsp, tbsp, oz, lb, g, kg, ml, l, clove, can, …),
   consistent with the vocabulary already encoded in `frontend/lib/ingredient.ts`.
3. WHEN no quantity or unit is recognizable THEN the parser SHALL set `quantity`/`unit`
   null and use the full (trimmed) text as `item`, never producing an empty `item`.
4. The parser SHALL always preserve the original string as `raw`, so parsing is lossless at
   the string level even when the structured breakdown is approximate.
5. The parser SHALL be deterministic and side-effect free (no I/O, no model calls), so it
   is cheap enough to run across the full ~2.2M catalog during backfill.
6. The frontend's display-only `ingredientName` heuristic SHALL remain valid; where
   practical the frontend SHALL be able to prefer the structured `item` when present and
   fall back to the existing heuristic for un-backfilled data.

## Requirement 6 — Backfill existing recipes and catalog

**User story:** As the operator, I want existing saved recipes and the catalog to gain
structured ingredients without regenerating or re-embedding anything, so that current data
becomes grocery-list-ready at low cost and risk.

### Acceptance criteria

1. The system SHALL provide a one-off, operator-gated backfill (in the style of the
   existing `catalog.*`-flagged runners) that parses existing string ingredients into the
   structured field for stored recipes.
2. The backfill SHALL cover the shared `CatalogRecipe` table and SHALL support the saved
   `Recipe` data as well (the two may be separate runs/flags).
3. The backfill SHALL be **idempotent and resumable**: re-running SHALL not corrupt or
   duplicate data, SHALL skip records already backfilled (unless explicitly forced), and
   SHALL support windowing/paging for the ~2.2M catalog like the existing ingestion runner.
4. The backfill SHALL NOT recompute embeddings and SHALL NOT alter the text used to produce
   embeddings, so no vector is regenerated and no index rebuild is triggered.
5. IF the catalog's structured field is projected into OpenSearch THEN it SHALL be added as
   an **additive, non-vector** mapping field and populated by an in-place update/upsert of
   only that field (reusing the existing reindex/backfill mechanics), never by recreating
   the index or re-embedding.
6. The backfill SHALL log progress and a final summary (seen / updated / skipped / failed)
   consistent with the existing ingestion/reindex runners, and a failure on one record
   SHALL NOT abort the whole run.

## Requirement 7 — Frontend consumes structured ingredients with graceful fallback

**User story:** As a user, I want recipe ingredient displays to keep working everywhere,
using the richer structured data when it exists and the existing text when it doesn't, so
nothing regresses while the data is being backfilled.

### Acceptance criteria

1. The frontend recipe types SHALL add the structured ingredient shape as an optional field
   mirroring the backend DTOs, alongside the existing `ingredients: string[]`.
2. WHEN structured ingredients are present THEN components MAY use them (e.g. show the
   item name without re-running the string heuristic); WHEN they are absent THEN the UI
   SHALL fall back to the existing string rendering and the `ingredientName` heuristic with
   no visible regression.
3. The existing ingredient displays (recipe detail lists, browse card summaries, saved
   recipe card) SHALL continue to render correctly for both backfilled and un-backfilled
   recipes.
4. This spec SHALL NOT add new ingredient-editing UI; it only reads/renders structured data
   where it improves display. (Editing/scaling arrives with grocery-list/servings work.)

## Requirement 8 — Compliance, limits, and consistency

**User story:** As the operator, I want structured ingredients to respect the same data
rules as the rest of a recipe, so this change introduces no gap.

### Acceptance criteria

1. Structured ingredients SHALL be included wherever recipe content already flows for a
   user's own data — specifically, the data export SHALL carry the structured field as part
   of each exported recipe (additive to the existing export shape), referencing no new
   external data.
2. Account hard-deletion SHALL require no new step: structured ingredients live inside the
   existing `Recipe`/`CatalogRecipe` items and are removed when those items are
   (catalog is shared/not user-owned, as today).
3. The structured list SHALL be bounded consistently with the existing ingredient list
   bounds so a single recipe/plan item stays well within DynamoDB's 400 KB item limit.
4. Generation SHALL continue to honor existing consent, demo-quota, and rate-limit
   machinery unchanged; this spec adds no new AI calls beyond the existing generate request.

---

## Design-forward notes (shape now, build later)

Recorded so later specs slot in without breaking changes; **not** implemented here.

- **Grocery list (spec 4).** The `{quantity, unit, item}` shape is the aggregation input:
  spec 4 normalizes units and merges items across a plan's recipes. Keep `quantity` numeric
  and `unit` a plain token so normalization is a pure function over this data.
- **Servings scaling (spec 4).** Numeric `quantity` is what gets multiplied by a servings
  ratio; this spec stores it, spec 4 scales it.
- **Nutrition (spec 6).** Structured quantities are the derivation input for a future
  nutrition source; no nutrition fields are added now.
- **Unit canonicalization.** This spec stores the unit as authored (lightly normalized);
  canonical unit conversion (tsp↔tbsp↔cup, g↔oz) is deliberately left to the grocery-list
  spec so the conversion table lives with the feature that needs it.
