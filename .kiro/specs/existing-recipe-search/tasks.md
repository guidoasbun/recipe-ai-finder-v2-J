# Implementation Plan — Look for Existing Recipes

Incremental, test-backed steps. Each task references the requirements it satisfies. Build
the data + search core first (behind the interface), then the API, ingestion, and finally
the frontend. Nothing here provisions OpenSearch; that stays a documented future swap.

> **Status (Phase 1 complete):** The in-app search feature is built and verified
> end-to-end. Ingestion loaded **two** datasets — TheMealDB (~300, international) and
> "Better Recipes for a Better Life"/AllRecipes (~1,090, American) — for **1,261 recipes
> embedded** (129 within-source duplicate URLs deduped, 0 failed). Live search verified:
> keyword, semantic (Bedrock Titan V2), dietary filtering, and pagination all working.
> Deferred by scope: automated unit/web-layer tests (verified manually against live data
> instead), the `BatchEmbeddingStrategy` (docs/future only), and all Phase 2 (RecipeNLG)
> work.

- [x] 1. Catalog data model and config
  - [x] 1.1 Add `CatalogRecipe` `@DynamoDbBean` (PK `catalogRecipeId`; fields: title,
        description, ingredients, steps, `dietaryTags`, `searchText`, `embedding`,
        `sourceName`, `sourceUrl`, `sourceLicense`, `ingestedAt`). Also added
        `sourceCountry`.
  - [x] 1.2 Add `dynamodb.catalog-table` property and provision the DynamoDB table
        (dev: `recipe-ai-dev-catalog`). Added to Terraform
        (`infrastructure/modules/dynamodb/`) and created in dev for the ingestion run.
  - [x] 1.3 Add `CatalogRecipeRepository` (save, findById, findAll/scan) using the existing
        `DynamoDbEnhancedClient` bean, mirroring `RecipeRepository` style.
  - _Requirements: 6.4, 7.5, 8.4_

- [x] 2. Embedding service (Bedrock Titan Text Embeddings V2)
  - [x] 2.1 Add `EmbeddingService` using the existing `BedrockRuntimeClient` and model id
        `amazon.titan-embed-text-v2:0`; builds `{"inputText":...,"normalize":true}`, parses
        the `embedding` array, 5-attempt exponential backoff. Used for single-query
        embedding at search time.
  - [x] 2.2 Add `bedrock.embedding.model-id` config property.
  - [x] 2.3 Define `EmbeddingStrategy` interface for bulk ingestion.
  - [x] 2.4 Implement `SynchronousEmbeddingStrategy`: RPM-based pacing, backoff via
        `EmbeddingService`, resumable (runner skips recipes that already have an embedding).
        Serves ≤ ~50K.
  - [ ] 2.5 Scaffold `BatchEmbeddingStrategy` (Bedrock Batch Inference, S3 JSONL, async)
        for ~2.2M. **NOT built** — documented as future in design.md §5.1; Phase 2 item.
  - [ ] 2.6 Unit tests for request/response parsing and resume/skip behavior. **Deferred** —
        verified manually against live Bedrock + data instead of automated tests.
  - _Requirements: 3.1, 3.2, 3.6, 3.7, 3.8_

- [x] 3. Search abstraction (the swap seam)
  - [x] 3.1 Define `CatalogSearchService` interface plus `CatalogSearchQuery` and
        `CatalogSearchResults` records, and `CatalogRecipeDto` (Lombok `@Data @Builder`,
        no `embedding`/`searchText` exposed).
  - [x] 3.2 Add config: `catalog.search.backend` (default `inapp`),
        `catalog.search.semantic-enabled`, `catalog.search.mode`,
        `page-size-default`, `page-size-max`.
  - [x] 3.3 Add a `@Bean` factory (`CatalogSearchConfig`) selecting the implementation from
        `catalog.search.backend` (default in-app; `opensearch` logs a warning + falls back
        until that backend exists).
  - _Requirements: 7.1, 7.3_

- [x] 4. In-app search implementation
  - [x] 4.1 `InAppCatalogSearchService`: loads catalog into an in-memory cache
        (`AtomicReference`, app-lifetime, `refresh()` to reload).
  - [x] 4.2 Dietary filter: keeps only recipes whose `dietaryTags` contain all requested
        tags. (Verified live: VEGAN filter returned only VEGAN-tagged recipes.)
  - [x] 4.3 Keyword ranking over `searchText` (title weighted higher); blank query =>
        browse listing.
  - [x] 4.4 Semantic ranking: embeds query via `EmbeddingService`, cosine similarity vs
        stored vectors; hybrid blend per `catalog.search.mode`. (Verified live: natural-
        language query returned semantically relevant results.)
  - [x] 4.5 Graceful fallback: on embedding failure, returns keyword-ranked results.
  - [x] 4.6 Applies pagination (bounded page size) and populates `totalMatches`.
  - [ ] 4.7 Unit tests. **Deferred** — behavior verified manually against the live catalog
        (keyword, semantic, dietary exclusion, browse, pagination bounds all confirmed).
  - _Requirements: 2.1–2.6, 3.1, 3.3, 3.4, 3.5, 4.2, 7.2, 8.1, 8.2_

- [x] 5. Catalog API
  - [x] 5.1 `CatalogController` `GET /api/catalog/search` (`q`, repeated `tags`, `page`,
        `pageSize`); resolves userId from JWT; effective dietary tags = request `tags`
        (validated against `DietaryRestriction`) else user's saved `dietaryRestrictions`.
  - [x] 5.2 `GET /api/catalog/{id}` with `@Pattern` id validation; 404 via existing
        `ResourceNotFoundException`/`GlobalExceptionHandler`.
  - [x] 5.3 Input validation (bounded `q` via `@Size`, capped `pageSize`, valid tags) per
        existing jakarta-validation conventions.
  - [ ] 5.4 Web-layer tests. **Deferred** — auth (401 unauthenticated) confirmed manually;
        automated web-layer tests not written.
  - _Requirements: 1.4, 2.4, 2.6, 4.1, 4.3, 5.1, 5.3_

- [x] 6. Ingestion pipeline
  - [x] 6.1 Dietary-tagging component (`DietaryTagger`): deterministic per-restriction
        disqualifier keyword lists → assign `dietaryTags`, with word-boundary matching.
  - [x] 6.2 `RecipeSource` parser abstraction + `ParsedRecipe` normalizer.
  - [x] 6.3 Phase 1 source: `XlsxMealDbSource` (~300 recipes). **Plus** a second Phase 1
        source added at user request: `CsvBetterRecipesSource` (AllRecipes CSV, ~1,090).
        NOTE: the first xlsx parser had a catastrophic-backtracking regex bug (caught during
        verification); replaced with a linear char-scan parser.
  - [x] 6.4 Ingestion runner (`CatalogIngestionRunner`, `@ConditionalOnProperty
        catalog.ingest.enabled=true`, never runs on normal boot): parse → tag → embed →
        persist with deterministic SHA-256 `catalogRecipeId` (idempotent); records
        attribution; RPM-paced Bedrock calls with progress logging.
  - [x] 6.5 Idempotency: deterministic id + skip-if-already-embedded. Verified live — the
        129 within-AllRecipes duplicate URLs were correctly skipped, no duplicates created.
  - [ ] 6.6 Phase 2 source: RecipeNLG-subset parser (≤ ~50K). **NOT started** — Phase 2,
        out of current scope.
  - _Requirements: 4.5, 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 6.7_

- [x] 7. Frontend — tab and search page
  - [x] 7.1 Consulted the repo's Next.js conventions before writing (client components using
        the existing `/api/backend/*` proxy that injects the bearer token, per `proxy.ts`).
  - [x] 7.2 Added `{ href: "/browse", label: "Look for Existing Recipes" }` to `NAV_LINKS`
        in `components/layout/Header.tsx`.
  - [x] 7.3 `app/(protected)/browse/page.tsx`: search input, results grid,
        loading/empty/no-results/error states, pagination; calls `/api/catalog/search` via
        the backend proxy.
  - [x] 7.4 Dietary filter chips from `lib/dietary.ts`, defaulted to the user's saved
        restrictions (fetched from `/api/account/dietary-restrictions`), toggleable
        per-search without mutating saved account settings.
  - _Requirements: 1.1, 1.2, 1.3, 2.3, 4.3, 4.4_

- [x] 8. Frontend — detail view
  - [x] 8.1 `app/(protected)/browse/[id]/page.tsx`: full recipe (title, description,
        ingredients, steps) + source attribution; 404 → `notFound()`; calls
        `/api/catalog/{id}`.
  - _Requirements: 5.1, 5.2, 5.3_

- [x] 9. Verification and docs
  - [x] 9.1 Backend build compiles clean; frontend type-checks clean (lint has only the
        pre-existing `<img>` warning). Ran live ingestion (1,261 embedded, 0 failed) and
        verified keyword/semantic/dietary/pagination search against real data.
        NOTE: 6 pre-existing property-test failures (AccountDeletion/Audit/DataExport) were
        confirmed to fail on a clean tree — unrelated to this feature.
  - [x] 9.2 Default config uses the in-app backend and provisions no OpenSearch; AI
        generation, saved-recipe tables, and dietary endpoints untouched.
  - [ ] 9.3 Written docs (how to run ingestion / flip `catalog.search.backend` /
        attribution). **Partial** — captured in design.md and this file; a dedicated
        README/runbook section is not yet written.

## Follow-ups / known items
- Add automated tests (tasks 2.6, 4.7, 5.4) — currently verified manually.
- `backend/.../application-local.properties` contains real committed API keys — rotate and
  remove from source control (pre-existing, unrelated to this feature).
- Optional: a `not-found.tsx` for the `/browse/[id]` route.
- Phase 2 (RecipeNLG subset + `BatchEmbeddingStrategy`) when desired.
