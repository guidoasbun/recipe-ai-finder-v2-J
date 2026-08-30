# Runbook — Look for Existing Recipes

Operational guide for the catalog search feature: running ingestion, switching the search
backend, configuration, and dataset attribution.

## 1. Datasets

Raw datasets live under `backend/data/` (gitignored — download locally, never commit).

| Source | Location | Count | Style | Format |
|---|---|---|---|---|
| TheMealDB (Kaggle export) | `backend/data/archive/*.xlsx` | ~300 | International | 34 xlsx, one per country |
| Better Recipes / AllRecipes | `backend/data/archive-1/recipes.csv` | ~1,090 | American home cooking | CSV |
| RecipeNLG (Phase 2, optional) | not downloaded | up to cap | Mixed | CSV |

Parsers (each implements `RecipeSource`):
- `XlsxMealDbSource` → TheMealDB xlsx files.
- `CsvBetterRecipesSource` → AllRecipes CSV.
- `RecipeNlgCsvSource` → RecipeNLG CSV (Phase 2; enforces a hard record cap for the in-app
  backend's ~50K ceiling).

## 2. Running catalog ingestion

Ingestion is a one-off job guarded by `catalog.ingest.enabled` — it never runs on normal
boot. It parses each source, assigns dietary tags, embeds via Bedrock Titan V2, and writes
to the catalog DynamoDB table. It is idempotent (deterministic SHA-256 id + skip if already
embedded), so re-running is safe and resumable.

Prerequisites:
- AWS credentials with DynamoDB + Bedrock access.
- The `recipe-ai-dev-catalog` DynamoDB table exists (provisioned via Terraform
  `infrastructure/modules/dynamodb`, or created ad hoc for dev).
- Bedrock model `amazon.titan-embed-text-v2:0` enabled in the account/region.

Run (local profile, from `backend/`):

```
./mvnw spring-boot:run \
  -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.arguments="--catalog.ingest.enabled=true --catalog.ingest.rpm-limit=600"
```

The runner logs progress (`embedded / skipped / failed`) and a final summary, then the app
continues running (stop it once ingestion completes). Timing: ~1,390 recipes at 600 RPM ≈
~5–6 minutes.

To re-run after adding recipes: just run again — already-embedded recipes are skipped.

### Phase 2 (RecipeNLG)
Drop the RecipeNLG CSV under `backend/data/`, register `RecipeNlgCsvSource` in
`CatalogIngestionRunner` with a record cap (≤ ~50K for the in-app backend), and run as above.
For the full ~2.2M dataset, use the OpenSearch backend + a batch embedding approach
(`BatchEmbeddingStrategy`); see `design.md` §5.1 and §12.

## 3. Configuration reference

`application.properties` (env-overridable):

| Property | Default | Purpose |
|---|---|---|
| `dynamodb.catalog-table` | `recipe-ai-dev-catalog` | Catalog table name |
| `catalog.search.backend` | `inapp` | `inapp` \| `opensearch` (future) |
| `catalog.search.semantic-enabled` | `true` | Embed queries for semantic ranking |
| `catalog.search.mode` | `hybrid` | `keyword` \| `semantic` \| `hybrid` |
| `catalog.search.page-size-default` | `20` | Default page size |
| `catalog.search.page-size-max` | `50` | Max page size (cap) |
| `bedrock.embedding.model-id` | `amazon.titan-embed-text-v2:0` | Titan embeddings model |
| `catalog.ingest.enabled` | `false` | Gate for the ingestion runner |
| `catalog.ingest.source-dir` | `data/archive` | Base dir; runner resolves `archive/` + `archive-1/` |
| `catalog.ingest.rpm-limit` | `300` | Requests/min pacing for embedding |

## 4. Switching the search backend (future OpenSearch)

Search is behind the `CatalogSearchService` interface, selected in `CatalogSearchConfig`.
Today only the in-app backend exists; `catalog.search.backend=opensearch` logs a warning and
falls back to in-app. To adopt OpenSearch later:

1. Add an `OpenSearchCatalogSearchService implements CatalogSearchService`.
2. Wire it in `CatalogSearchConfig` when `backend=opensearch`.
3. Reindex from the `CatalogRecipe` table (dietary tags + embeddings are already persisted,
   so no re-embedding needed).
4. Set `catalog.search.backend=opensearch`.

No controller/DTO/frontend changes are required — that is the point of the seam.

## 5. Frontend

- Tab: "Look for Existing Recipes" (`/browse`) in `components/layout/Header.tsx`.
- Pages: `app/(protected)/browse/page.tsx` (search + dietary chips + pagination),
  `app/(protected)/browse/[id]/page.tsx` (detail + attribution), and `[id]/not-found.tsx`.
- Calls the backend via the `/api/backend/*` proxy (injects the bearer token).
- Dietary chips default to the user's saved restrictions and are overridable per-search
  without changing account settings.

## 6. Attribution

Each `CatalogRecipe` stores `sourceName`, `sourceUrl`, and `sourceLicense`; the detail page
renders the source link. Respect each dataset's license:
- TheMealDB: free for education/development; attribution requested.
- Better Recipes / AllRecipes (Kaggle): non-commercial use.
- RecipeNLG: research/non-commercial use.

This app is non-commercial, which keeps these datasets in scope. Re-check licensing before
any commercial use.
