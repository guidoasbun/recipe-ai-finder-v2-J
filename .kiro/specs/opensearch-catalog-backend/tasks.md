# Implementation Plan — OpenSearch Catalog Search Backend

Migrate catalog search from the in-app backend to OpenSearch behind the existing
`CatalogSearchService` seam. Build the client + index + search impl + reindex job, wire the
config switch, provision opt-in infrastructure, then cut over. The in-app backend stays as a
config-selectable fallback. Nothing outside the catalog search backend changes.

> **Decisions (confirmed):** OpenSearch Serverless NextGen (scale-to-zero); fail-fast on
> misconfig. Recommended cutover order: validate against the current ~1.3K catalog first, then
> run the full 2.2M load. The RecipeNLG dataset is downloaded at
> `backend/data/recipeNGL/RecipeNLG_dataset.csv` (~2.23 GB, ~2.23M rows) and matches the
> existing `RecipeNlgCsvSource` parser.
>
> **Rollback:** full 2.2M lives in a separate table so the small in-app table stays a valid
> fast-rollback target; a ~50K catalog can also be re-ingested from the local dataset (rebuild
> rollback). **Cost:** ~$15/mo recurring + ~$10 one-time (design §2.1); a billing alarm is
> provisioned.

- [x] 1. OpenSearch client and configuration
  - [x] 1.1 Add `OpenSearchConfig` producing a SigV4-signed `opensearch-java` client
        (`AwsSdk2Transport` over `AwsCrtHttpClient`, region from `aws.region`,
        `DefaultCredentialsProvider`), conditional on `catalog.search.backend=opensearch`.
        Signing service from `opensearch.signing-service` (`aoss` serverless | `es` managed).
        Endpoint scheme/trailing-slash stripped to the bare host the transport expects.
  - [x] 1.2 Add properties via `OpenSearchProperties` (`@ConfigurationProperties("opensearch")`):
        `opensearch.endpoint`, `opensearch.index`, `opensearch.signing-service`,
        `opensearch.knn.ef-search`, `opensearch.knn.quantization`; `catalog.search.backend`
        stays default `inapp`.
  - [x] 1.3 Startup behavior: fail fast (`IllegalStateException` with a clear message +
        rollback hint) when `backend=opensearch` and `opensearch.endpoint` is blank.
  - [x] 1.4 Added `org.opensearch.client:opensearch-java:3.9.0` and
        `software.amazon.awssdk:aws-crt-client` (version managed by the existing AWS SDK BOM)
        to `backend/pom.xml`. Verified `./mvnw compile` is clean; the conditional bean means
        the default in-app deployment is unaffected.
  - _Requirements: 1.4, 6.1, 6.2, 6.3_

- [ ] 2. Index mapping and provisioning
  - [ ] 2.1 Define the index mapping (text title/description/ingredients, `knn_vector`
        dim 1024 cosine/HNSW, `dietaryTags` keyword, stored attribution) per design §3.
  - [ ] 2.2 Add create-if-absent provisioning (idempotent) invoked by the reindex job.
  - [ ] 2.3 Wire the quantization knob (`none | fp16 | byte`) into the mapping; default `none`
        for parity verification, documented switch to fp16/byte before the full 2.2M load.
  - [ ] 2.4 (Optional, future-fit) Reserve an `ownerScope` keyword field in the mapping now so
        the future private-recipe feature (design §9a) needs no re-mapping. Unused until then.
  - _Requirements: 3.1, 3.2, 3.3, 3.4, 10.1_

- [ ] 3. OpenSearchCatalogSearchService (the implementation behind the seam)
  - [ ] 3.1 Add `OpenSearchCatalogSearchService implements CatalogSearchService` in
        `io.asbun.backend.search`.
  - [ ] 3.2 `search`: build dietary `terms` filter (AND), keyword `multi_match`
        (title boosted), and knn vector clause per `catalog.search.mode` +
        `semantic-enabled`; blank text => `match_all` browse; pagination `from`/`size`
        clamped to page-size bounds; `totalMatches` from hits total.
  - [ ] 3.3 Hybrid mode: OpenSearch hybrid pipeline if supported, else `bool.should` score
        blend (matches in-app blend intent).
  - [ ] 3.4 Semantic fallback: on `EmbeddingService.embed` failure, drop the vector clause and
        run keyword-only, still returning results.
  - [ ] 3.5 `findById`: get by `catalogRecipeId` → `CatalogRecipeDto` (reuse existing mapper,
        no `embedding`/`searchText`), else empty.
  - [ ] 3.6 Map OpenSearch failures to the existing `GlobalExceptionHandler` (no fake empty
        results on outage).
  - _Requirements: 1.1, 1.2, 1.3, 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7_

- [ ] 4. Backend selection wiring
  - [ ] 4.1 Update `CatalogSearchConfig` to return `OpenSearchCatalogSearchService` when
        `catalog.search.backend=opensearch` (replace the current warn-and-fallback stub),
        in-app otherwise.
  - [ ] 4.2 Confirm the controller, `CatalogRecipeDto`, and frontend require no changes
        (existing `CatalogControllerTest` passes unchanged).
  - _Requirements: 1.4, 1.5_

- [ ] 5. Two catalog tables (rollback preservation)
  - [ ] 5.1 Add `dynamodb.catalog-full-table` property; make ingestion and reindex targets
        table-name-configurable so the full load never overwrites/grows the small in-app table.
  - [ ] 5.2 Keep the small table as the in-app backend's source; both tables share the
        `CatalogRecipe` schema (no code change to operate on either).
  - _Requirements: 4.1, 4.2, 4.3, 4.4_

- [ ] 6. Reindex from DynamoDB (no re-embedding)
  - [ ] 6.1 Add `CatalogReindexRunner` gated by `catalog.reindex.enabled=true` (never on
        normal boot); scans the configured `CatalogRecipe` table (small or full) via
        `CatalogRecipeRepository`.
  - [ ] 6.2 Build the OpenSearch doc from persisted fields incl. `embedding`; bulk index in
        batches of `catalog.reindex.batch-size`, using `catalogRecipeId` as doc id (idempotent
        upsert).
  - [ ] 6.3 No Bedrock calls for recipe vectors (read from DynamoDB); log
        `indexed / skipped / failed` + final summary; safe to re-run.
  - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5_

- [ ] 7. Infrastructure as code (opt-in, cost-bounded)
  - [ ] 7.1 New `infrastructure/modules/opensearch/` gated by `enable_opensearch=false`
        default (provisions nothing by default).
  - [ ] 7.2 Serverless (default flavor): vector-search collection + collection group,
        encryption/network/data-access policies, `maxSearchCapacityInOCU` cap, min OCU 0
        (scale-to-zero).
  - [ ] 7.3 Least-privilege IAM for the backend ECS task role to the collection/domain
        (extend `infrastructure/modules/iam`).
  - [ ] 7.4 Output the endpoint; feed `OPENSEARCH_ENDPOINT` (+ index/signing-service) into the
        ECS task env in `infrastructure/modules/ecs`.
  - [ ] 7.5 Add the `catalog-full` DynamoDB table to `infrastructure/modules/dynamodb`, gated
        by the same opt-in flag.
  - [ ] 7.6 Add a CloudWatch billing alarm / AWS Budget (threshold from design §2.1, e.g.
        alert ~$30/mo) in Terraform.
  - _Requirements: 4.4, 7.1, 7.2, 7.3, 7.4, 7.5, 7.6_

- [ ] 8. Tests
  - [ ] 8.1 Unit — query translation: dietary filter, keyword clause, knn clause, mode
        handling, pagination (mock client).
  - [ ] 8.2 Unit — response mapping + `findById` (present/absent).
  - [ ] 8.3 Unit — semantic fallback on embed failure.
  - [ ] 8.4 Unit — `CatalogSearchConfig` selects OpenSearch vs in-app by property; existing
        `CatalogControllerTest` still passes.
  - [ ] 8.5 Unit — reindex idempotency + no-Bedrock-for-vectors (mock bulk client + repo).
  - _Requirements: 1.5, 2.5, 5.3, 5.4_

- [ ] 9. Migration, verification, and docs
  - [ ] 9.1 Verify backend build compiles; run the catalog test suite green.
  - [ ] 9.2 Documented parity check against OpenSearch: keyword, semantic, dietary filter,
        pagination, detail, 404.
  - [ ] 9.3 Verify both rollback paths: (a) flip to `inapp` reads the small table; (b) re-ingest
        a ~50K subset from the local dataset then flip to `inapp`.
  - [ ] 9.4 Update `RUNBOOK.md`: provision → reindex → verify → cutover → both rollbacks, plus
        cost/capacity settings, billing alarm, and quantization guidance for 2.2M.
  - [ ] 9.5 Confirm isolation: AI generation, saved recipes, dietary endpoints, and the
        ingestion pipeline unchanged; in-app backend still selectable as fallback.
  - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 9.1, 9.2, 9.3_

- [ ] 10. Full 2.2M RecipeNLG load (separate operational step, NOT a gate on tasks 1–9)
  - [ ] 10.1 Register `RecipeNlgCsvSource` in `CatalogIngestionRunner` pointed at
        `backend/data/recipeNGL/RecipeNLG_dataset.csv`, `maxRecords` cap lifted, targeting the
        `catalog-full` table.
  - [ ] 10.2 Finish the `BatchEmbeddingStrategy` scaffold (Bedrock Batch Inference: write S3
        JSONL, submit `CreateModelInvocationJob`, poll, collect vectors from S3 output) and use
        it for the 2.2M run (~$3–6 one-time via batch) instead of the synchronous loop.
  - [ ] 10.3 Run ingestion → `catalog-full` populated with 2.2M recipes + persisted embeddings.
  - [ ] 10.4 Enable vector quantization (fp16 or byte) on the OpenSearch index before/at this
        scale (design §3) to bound the ~9 GB+ vector footprint; run the reindex (task 6) against
        the full table.
  - [ ] 10.5 Re-verify parity + performance at 2.2M; tune `ef-search` and the OCU cap; confirm
        the billing alarm threshold still fits observed cost.
  - _Requirements: 3.4, 4.1, 5.1, 7.2, 7.6_

## Notes
- **Fast rollback:** `catalog.search.backend=inapp` reads the small table (fits in memory), no
  data migration. Kept valid by storing the full 2.2M in a separate table (task 5).
- **Rebuild rollback:** re-ingest a ~50K subset from the local RecipeNLG file via the capped,
  idempotent path, then flip to `inapp`. Minutes, cents, repeatable.
- Tasks 1–9 build and validate the OpenSearch backend against the current small catalog. Task 10
  is the full-scale load and can happen later; the reindex job is identical regardless of size
  because embeddings are read from DynamoDB, never recomputed.
- **Future (not in scope):** private user-recipe search reuses this backend via an `ownerScope`
  filter (design §9a); task 2.4 optionally reserves the field so no re-mapping is needed later.
