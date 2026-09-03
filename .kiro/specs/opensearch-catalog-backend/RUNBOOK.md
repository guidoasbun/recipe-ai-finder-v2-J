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
| `catalog_search_backend` | `inapp` | Backend env var injected into ECS (`CATALOG_SEARCH_BACKEND`) |
| `catalog_search_mode` | `hybrid` | Search mode injected into ECS (`CATALOG_SEARCH_MODE`): `keyword` \| `semantic` \| `hybrid` |
| `catalog_semantic_enabled` | `true` | Embed queries for semantic ranking (`CATALOG_SEMANTIC`) |
| `opensearch_knn_ef_search` | `100` | k-NN `ef_search` recall/latency tuning (`OPENSEARCH_KNN_EF_SEARCH`) |
| `opensearch_knn_quantization` | `none` | Vector quantization (`OPENSEARCH_KNN_QUANTIZATION`): `none` \| `fp16` \| `byte`. **Must match the index the reindex built** (`fp16` for the full 2.2M load) |
| `opensearch_max_search_ocu` | `8` | Max search OCUs (cost ceiling — applied via CLI, §3) |
| `opensearch_max_indexing_ocu` | `8` | Max indexing OCUs (cost ceiling — applied via CLI) |
| `opensearch_budget_limit_amount` | `30` | Monthly budget alarm limit (USD) |
| `opensearch_budget_notification_email` | (blank) | Budget alert email (blank = no budget) |

> **ECS now injects the search-tuning knobs.** `catalog_search_mode`, `catalog_semantic_enabled`,
> `opensearch_knn_ef_search`, and `opensearch_knn_quantization` are wired through the ECS module
> (Task 10.5), so tuning them is a `terraform apply` on the running service — no image rebuild.
> `opensearch_knn_quantization` shapes the k-NN query encoder at serve time, so keep it equal to
> the quantization the index was built with (`fp16` at 2.2M); a mismatch degrades recall.

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
reindex runs. Terraform emits the exact command (with your configured
`opensearch_max_*_ocu` values) as the `ocu_cap_cli_command` output — run it:

```
terraform output -raw opensearch_ocu_cap_cli_command | bash
# or copy/run it manually, e.g.:
# aws opensearchserverless update-account-settings \
#   --capacity-limits maxIndexingCapacityInOCU=8,maxSearchCapacityInOCU=8
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

Once parity checks pass, flip the running service to the OpenSearch backend. At full scale
(2.2M) also pin the quantization to match the built index and set the desired `ef_search`:

```
terraform apply -var 'enable_opensearch=true' -var 'enable_catalog_full=true' \
  -var 'catalog_search_backend=opensearch' \
  -var 'opensearch_knn_quantization=fp16' \
  -var 'opensearch_knn_ef_search=100' \
  -var 'opensearch_budget_notification_email=you@example.com'
```

(ECS injects `CATALOG_SEARCH_BACKEND=opensearch`, `OPENSEARCH_ENDPOINT`, `CATALOG_SEARCH_MODE`,
`CATALOG_SEMANTIC`, `OPENSEARCH_KNN_EF_SEARCH`, and `OPENSEARCH_KNN_QUANTIZATION`.) The
controller, DTOs, and frontend are unchanged — only the backend selection differs.

**Tuning `ef_search`:** it trades recall for latency on semantic/hybrid queries. Start at `100`;
raise (e.g. `200`, `500`) if semantic recall looks thin at 2.2M, lower if p95 latency is too
high. It is a serve-time query parameter, so changing it is a `terraform apply` — no reindex.
Confirm the OCU cap (search/indexing `8`/`8`, §3.2) and that observed spend stays under the
budget threshold (§6) after cutover.

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
RecipeNLG dataset (synchronous, paced), then flip to in-app. This selects `RecipeNlgCsvSource`
with a 50K cap and targets the **small** table explicitly:

```
./mvnw spring-boot:run -pl backend \
  -Dspring-boot.run.arguments="\
    --catalog.ingest.enabled=true \
    --catalog.ingest.embedding-strategy=sync \
    --catalog.ingest.recipenlg-file=data/recipeNGL/RecipeNLG_dataset.csv \
    --catalog.ingest.recipenlg-max-records=50000 \
    --dynamodb.catalog-full-table=recipe-ai-<env>-catalog \
    --catalog.ingest.rpm-limit=600"
```

(Setting `dynamodb.catalog-full-table` to the small table name makes the ingestion runner
target it. Leaving it at the default also targets the small table.)

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

Manual: run the built-in verify runner (logs the checks below) against the running app:
```
java -jar backend/target/backend-0.0.1-SNAPSHOT.jar \
  --catalog.search.backend=opensearch --opensearch.endpoint=<endpoint> \
  --catalog.verify.enabled=true
```
It exercises: keyword, semantic (natural-language), dietary VEGAN filter, browse total,
pagination (no page overlap), and findById (present + missing). Verified on the ~1.3K catalog:
browse total=1261, keyword 'chicken'=109, semantic top hit relevant, VEGAN filter all-tagged,
pages non-overlapping, findById round-trips.

### Serverless (aoss) constraints discovered during validation
The implementation accounts for these OpenSearch Serverless behaviors (they differ from a
managed domain):
- `index.knn=true` **is** required on the index for a `knn_vector` mapping with a method
  (contrary to some docs suggesting serverless manages it implicitly).
- Custom document `_id` is **rejected** at index time — serverless auto-generates ids. The
  reindex omits `_id` on serverless and stores `catalogRecipeId` as a field; `findById` queries
  that field rather than getting by `_id`. Managed domains still use `catalogRecipeId` as `_id`.
- The `knn` query accepts exactly one of `k` / `distance` / `score`; `min_score`/`max_distance`
  are **rejected**. So the in-app 0.35 cosine threshold is not applied as a knn radial filter on
  serverless — semantic uses k-bounded nearest neighbors, and in hybrid mode the keyword clause
  is the precision signal.

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

## 7. Full 2.2M load

Prereqs: two S3 buckets (batch input + output) and an IAM role Bedrock can assume to read the
input / write the output (Bedrock Batch Inference service role). Then run ingestion in **batch**
mode against the full table:

```
./mvnw spring-boot:run -pl backend \
  -Dspring-boot.run.arguments="\
    --catalog.ingest.enabled=true \
    --catalog.ingest.embedding-strategy=batch \
    --catalog.ingest.recipenlg-file=data/recipeNGL/RecipeNLG_dataset.csv \
    --catalog.ingest.recipenlg-max-records=0 \
    --dynamodb.catalog-full-table=recipe-ai-<env>-catalog-full \
    --bedrock.batch.input-bucket=<batch-input-bucket> \
    --bedrock.batch.output-bucket=<batch-output-bucket> \
    --bedrock.batch.role-arn=<bedrock-batch-service-role-arn>"
```

What it does:
1. Parses RecipeNLG (`RecipeNlgCsvSource`, `max-records=0` = full ~2.23M set), skipping any
   already-embedded recipes (idempotent — safe to re-run).
2. Writes all inputs as JSONL to S3, submits one Bedrock **Batch Inference** job
   (`CreateModelInvocationJob`), polls to completion, reads vectors back from the output
   (~$3–6 one-time, ~50% cheaper than on-demand).
3. Persists each recipe + vector to `catalog-full`.

Then:
4. Set `opensearch.knn.quantization=fp16` (or `byte`) BEFORE the index is created (it changes
   the mapping), then run the reindex (§3.4) against `catalog-full`.
5. Re-verify parity + latency at 2.2M; tune `ef-search` and the OCU cap; confirm the budget
   threshold still fits observed cost.

> Note: for very large batches Bedrock may split output across multiple files and enforce
> per-job record limits/quotas; the collector reads all `*.jsonl(.out)` files under the output
> prefix. If the job hits a max-records-per-job quota, split the run (the skip-if-embedded logic
> makes multiple runs safe).

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

---

## 9. Full-scale cutover state (Task 10.5)

The full 2.2M index is **built and verified**: OpenSearch `_count` = DynamoDB item count =
**2,231,142** (exact, 0 missing / 0 duplicates), confirmed by `catalog.reindex.verify-count`.
See the post-mortem in `documents/opensearch-implementation.md` for how the index was completed
(serverless throttling, no-upsert reconciliation via PIT + `search_after`, client timeouts).

Environment (dev): AWS account `412381751532`, `us-east-1`; collection index `catalog-recipes`,
signing service `aoss`; source of truth `recipe-ai-dev-catalog-full`; OCU caps 8/8;
embeddings Titan Text V2 (1024-dim, fp16 in the index).

### 9.1 Status: DEV cut over to OpenSearch (done)

- **Dev is live on OpenSearch.** `terraform apply` (scoped saved plan) flipped the dev ECS backend
  to `CATALOG_SEARCH_BACKEND=opensearch` with the tuning env vars, and removed the temporary
  `rodrigo-cli` data-access principal. Parity re-verified at 2.2M (browse total 2,231,142, keyword,
  semantic, VEGAN filter, pagination, findById all good).
- **Prod is intentionally NOT cut over** — the index + data live in dev only. `prod.tfvars` leaves
  OpenSearch disabled (with a guard note); a prod cutover requires a full prod load + reindex +
  verify first.
- The search-tuning knobs (`catalog_search_mode`, `catalog_semantic_enabled`,
  `opensearch_knn_ef_search`, `opensearch_knn_quantization`) are injected by the ECS module, so
  tuning them is a `terraform apply` — no image rebuild (§2 table, §3.5).
- CLI data access is codified via `opensearch_admin_principals` (empty = least-privilege).

### 9.2 The cutover steps (performed for dev; the reference for a future prod cutover)

Run these against the live account in order (require credentials + the running service):

1. **Re-verify parity + performance at 2.2M.** Run the built-in verifier against the full index:
   ```
   java -jar backend/target/backend-0.0.1-SNAPSHOT.jar \
     --catalog.search.backend=opensearch \
     --opensearch.endpoint=<collection-endpoint> \
     --opensearch.knn.quantization=fp16 \
     --dynamodb.catalog-full-table=recipe-ai-dev-catalog-full \
     --catalog.verify.enabled=true
   ```
   Confirm keyword, semantic (natural-language), dietary VEGAN filter, browse total ≈ 2,231,142,
   pagination (no page overlap), and findById (present + missing). Note p95 latency for the first
   (cold-start) query vs. warm.

2. **Tune `ef_search` / confirm the OCU cap.** If semantic recall looks thin, raise
   `opensearch_knn_ef_search` (200/500) via `terraform apply` and re-check; watch latency.
   Confirm the account OCU cap is 8/8:
   ```
   aws opensearchserverless get-account-settings
   ```
   (re-apply the §3.2 command if it drifted).

3. **Cut over.** `terraform apply` with `catalog_search_backend=opensearch` and the tuning vars
   (§3.5). Smoke-test `/browse` in the app.

4. **CLI data-access principal — now codified (DONE).** During the reindex/backfill a personal
   CLI user (`rodrigo-cli`) was granted data-plane access out-of-band. The cutover apply removed
   it, and the data-access policy is now `concat([var.task_role_arn], var.admin_principals)` with
   `admin_principals=[]` — least-privilege, only the ECS task role has access (Requirement 7.5).

   **To re-grant ad-hoc CLI/local access later** (reindex, backfill, debugging), do NOT edit the
   policy by hand. Set the principal in a tfvars file and apply, so the grant is version-controlled:
   ```hcl
   # environments/dev.tfvars (example is commented there)
   opensearch_admin_principals = ["arn:aws:iam::412381751532:user/rodrigo-cli"]
   ```
   ```
   terraform apply -var-file=environments/dev.tfvars
   ```
   The principal also needs `aoss:APIAccessAll` on its IAM side. Remove it again by clearing the
   list and re-applying. (aoss caps a data-access policy at 20 principals.)

5. **Final verification after cutover.** Re-run the verifier (step 1) pointed at the deployed
   service path, or exercise `/browse` manually; confirm the budget alarm email is active and
   observed spend tracks the ~$15/mo estimate.
