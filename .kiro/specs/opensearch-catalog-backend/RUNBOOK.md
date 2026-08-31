# Runbook — OpenSearch Catalog Search Backend

Operational guide for migrating catalog search from the in-app backend to Amazon OpenSearch
Serverless (NextGen): provisioning, reindex, cutover, both rollback paths, cost/capacity
settings, and parity verification.

Nothing here runs automatically. OpenSearch is opt-in; the default deployment
(`catalog.search.backend=inapp`, `enable_opensearch=false`) provisions no OpenSearch
infrastructure and incurs no extra cost.

---

## 1. Overview and prerequisites

- Region: **us-east-1** (matches all existing infrastructure).
- DynamoDB is the system of record; OpenSearch is a derived, rebuildable index.
- Two catalog tables: the small in-app table (`recipe-ai-<env>-catalog`) and the opt-in full
  table (`recipe-ai-<env>-catalog-full`) used to build the OpenSearch index.
- Expected cost once the full 2.2M index exists: **~$15/mo recurring** (OpenSearch compute is
  the swing factor) + **~$10 one-time** (embeddings + initial DynamoDB writes). See design §2.1.

Prerequisites:
- AWS credentials with permission to apply the Terraform and run the CLI cap command.
- Bedrock model `amazon.titan-embed-text-v2:0` enabled in the account/region.

---

## 2. Configuration reference

Backend (`application.properties`, env-overridable — ECS injects these):

| Property | Env var | Default | Purpose |
|---|---|---|---|
| `catalog.search.backend` | `CATALOG_SEARCH_BACKEND` | `inapp` | `inapp` \| `opensearch` |
| `catalog.search.semantic-enabled` | `CATALOG_SEMANTIC` | `true` | Embed queries for semantic ranking |
| `catalog.search.mode` | `CATALOG_SEARCH_MODE` | `hybrid` | `keyword` \| `semantic` \| `hybrid` |
| `opensearch.endpoint` | `OPENSEARCH_ENDPOINT` | (blank) | Collection/domain host. **Blank + backend=opensearch ⇒ fail-fast** |
| `opensearch.index` | `OPENSEARCH_INDEX` | `catalog-recipes` | Index name |
| `opensearch.signing-service` | `OPENSEARCH_SIGNING_SERVICE` | `aoss` | `aoss` (serverless) \| `es` (managed) |
| `opensearch.knn.ef-search` | `OPENSEARCH_KNN_EF_SEARCH` | `100` | k-NN recall/latency tuning |
| `opensearch.knn.quantization` | `OPENSEARCH_KNN_QUANTIZATION` | `none` | `none` \| `fp16` \| `byte` |
| `dynamodb.catalog-full-table` | `DYNAMODB_CATALOG_FULL_TABLE` | small table | Full 2.2M table |
| `catalog.reindex.enabled` | `CATALOG_REINDEX_ENABLED` | `false` | Gate for the reindex job |
| `catalog.reindex.batch-size` | `CATALOG_REINDEX_BATCH_SIZE` | `500` | Bulk index batch size |

Terraform (`infrastructure/variables.tf`):

| Variable | Default | Purpose |
|---|---|---|
| `enable_opensearch` | `false` | Provision the serverless collection + policies |
| `enable_catalog_full` | `false` | Create the full 2.2M DynamoDB table |
| `catalog_search_backend` | `inapp` | Backend env var injected into ECS |
| `opensearch_max_search_ocu` | `8` | Max search OCUs (cost ceiling — applied via CLI, §3) |
| `opensearch_max_indexing_ocu` | `8` | Max indexing OCUs (cost ceiling — applied via CLI) |
| `opensearch_budget_limit_amount` | `30` | Monthly budget alarm limit (USD) |
| `opensearch_budget_notification_email` | (blank) | Budget alert email (blank = no budget) |

---

## 3. Migration / cutover sequence

Validate against the current small catalog first (fast, cheap), then scale to the full 2.2M
(§7). Steps:

### 3.1 Provision infrastructure

```
cd infrastructure
terraform apply \
  -var 'enable_opensearch=true' \
  -var 'enable_catalog_full=true' \
  -var 'opensearch_budget_notification_email=you@example.com'
```

This creates the serverless VECTORSEARCH collection (+ encryption/network/data-access
policies), the `catalog-full` table, the task-role IAM grant, and the budget. Note the
collection endpoint from the `opensearch` module output.

### 3.2 Set the OCU cap (cost ceiling — CLI, not Terraform)

Account-level OCU limits are not settable via the Terraform AWS provider yet
(hashicorp/terraform-provider-aws #41245), so set them once with the CLI **before** any
reindex runs, using the same values as the Terraform variables:

```
aws opensearchserverless update-account-settings \
  --capacity-limits maxIndexingCapacityInOCU=8,maxSearchCapacityInOCU=8
```

This bounds the maximum spend; scale-to-zero handles the idle minimum (0 OCU). The budget
alarm from §3.1 is the secondary guardrail.

### 3.3 Deploy the backend (still on in-app)

Deploy the current backend image. Keep `catalog_search_backend=inapp` for now — the app runs
unchanged; the OpenSearch client bean is not even created yet.

### 3.4 Reindex from DynamoDB into OpenSearch

Run the one-off reindex (reads the configured table, creates the index if absent via
`ensureIndex()`, bulk-indexes — no re-embedding). It requires the OpenSearch beans, so run
with the OpenSearch backend selected and the endpoint set:

```
./mvnw spring-boot:run -pl backend \
  -Dspring-boot.run.arguments="\
    --catalog.search.backend=opensearch \
    --opensearch.endpoint=<collection-endpoint> \
    --catalog.reindex.enabled=true"
```

For the small-catalog validation, leave `dynamodb.catalog-full-table` at its default so the
reindex reads the existing ~1.3K catalog. Watch the logs for `indexed / skipped / failed`.
Safe to re-run (idempotent upsert by `catalogRecipeId`).

### 3.5 Verify parity (§5), then cut over

Once parity checks pass, flip the running service to the OpenSearch backend:

```
terraform apply -var 'enable_opensearch=true' -var 'enable_catalog_full=true' \
  -var 'catalog_search_backend=opensearch' \
  -var 'opensearch_budget_notification_email=you@example.com'
```

(ECS injects `CATALOG_SEARCH_BACKEND=opensearch` + `OPENSEARCH_ENDPOINT`.) The controller,
DTOs, and frontend are unchanged — only the backend selection differs.

---

## 4. Rollback

### 4.1 Fast rollback (seconds)

Set the backend back to in-app:

```
terraform apply -var 'catalog_search_backend=inapp' ...
```

The in-app backend reads the small `catalog` table (untouched by the full load), so it fits
in memory and works immediately. No data migration. Valid as long as the small table is within
the in-app ceiling (~50K).

### 4.2 Rebuild rollback (minutes)

If the small table is ever lost or stale, re-ingest a bounded ~50K catalog from the local
RecipeNLG dataset, then flip to in-app. See §7 for registering `RecipeNlgCsvSource`; run with a
50K cap targeting the **small** table (leave `dynamodb.catalog-full-table` at default):

```
./mvnw spring-boot:run -pl backend \
  -Dspring-boot.run.arguments="--catalog.ingest.enabled=true --catalog.ingest.rpm-limit=600"
```

Idempotent, so re-running never duplicates. A working subset — not necessarily identical to a
prior catalog.

> Note: once the full 2.2M lives only in OpenSearch/`catalog-full`, in-app rollback serves the
> small table's subset, not 2.2M. That is the intended one-way scaling point.

---

## 5. Parity verification

After reindex (and before/after cutover), verify the OpenSearch backend behaves like in-app:

Automated:
- `./mvnw -pl backend test -Dtest='*Catalog*,OpenSearch*,CatalogSearchConfigTest'` — all green.
  (Query translation, mapping, findById, semantic fallback, backend selection, reindex.)

Manual (against the running app with `backend=opensearch`):
1. **Keyword:** search a title term (e.g. "pancake") → relevant recipes, title matches ranked high.
2. **Semantic:** a natural-language query (e.g. "something warm for a cold night") → semantically
   relevant results even without exact keywords.
3. **Dietary filter:** apply a restriction (e.g. VEGAN) → only VEGAN-tagged recipes returned.
4. **Pagination:** page 0 vs page 1 return different, non-overlapping items; `totalMatches` stable.
5. **Detail:** open a recipe → full fields + attribution; unknown id → 404.
6. **Fallback:** (optional) temporarily deny Bedrock → semantic query still returns keyword results.

---

## 6. Cost and capacity

- **OCU cap:** set via CLI (§3.2); the hard ceiling on compute spend.
- **Scale-to-zero:** idle → 0 OCU after ~10 min (per collection group); first query after idle
  has a 10–30s cold start. Acceptable for this app's low, bursty traffic.
- **Budget alarm:** AWS Budget scoped to OpenSearch + Bedrock, alerts at 80% actual / 100%
  forecast of the limit (default $30). Set the email to enable it.
- **Quantization:** keep `none` for the small-catalog validation. Before the full 2.2M load,
  switch `opensearch.knn.quantization=fp16` (≈½ memory) or `byte` (≈¼) to bound the ~9 GB+
  vector footprint — this changes the index mapping, so it must be set before the index is
  created (or reindex into a fresh index).

---

## 7. Full 2.2M load (Task 10 — separate, later)

1. Register `RecipeNlgCsvSource` in `CatalogIngestionRunner` pointed at
   `backend/data/recipeNGL/RecipeNLG_dataset.csv`, cap lifted, targeting `catalog-full`
   (`DYNAMODB_CATALOG_FULL_TABLE=recipe-ai-<env>-catalog-full`).
2. Produce embeddings via the batch strategy (`BatchEmbeddingStrategy`, Bedrock Batch
   Inference — S3 JSONL, ~$3–6 one-time). Finish that scaffold first.
3. Run ingestion → `catalog-full` populated with 2.2M recipes + embeddings.
4. Set `opensearch.knn.quantization=fp16` (or `byte`), then run the reindex (§3.4) against the
   full table.
5. Re-verify parity + latency at 2.2M; tune `ef-search` and the OCU cap; confirm the budget
   threshold still fits observed cost.

---

## 8. Isolation guarantees

This migration does not change:
- AI generation (`BedrockService`, recipe generate/save, consent checks).
- Saved-recipe `Recipe` model/table and endpoints.
- Dietary-restriction endpoints and the `DietaryRestriction` enum.
- The `existing-recipe-search` ingestion pipeline behavior (it only gained a configurable
  target table, defaulting to the small table).
- The in-app backend, which remains fully functional and config-selectable as the fallback.

The `CatalogSearchService` interface, `CatalogRecipeDto`, `CatalogController`, and the frontend
are unchanged — switching backends is a configuration change only.
