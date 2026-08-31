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

- [x] 2. Index mapping and provisioning
  - [x] 2.1 `OpenSearchIndexProvisioner.buildMappingJson()` builds the design §3 mapping:
        `text` title (+ `kw` keyword sub-field)/description/ingredients, non-indexed `steps`
        and attribution URLs, `dietaryTags`/`sourceName`/`sourceCountry` keyword, and a Faiss
        HNSW `knn_vector` (dim 1024, `cosinesimil`, `ef_construction`=128, `m`=16).
  - [x] 2.2 `ensureIndex()` is idempotent create-if-absent (checks `indices().exists`, creates
        only when missing); conditional on `catalog.search.backend=opensearch`. The reindex
        job (task 6) will call it.
  - [x] 2.3 Quantization knob wired: `none` = float (default), `fp16` = Faiss scalar (`sq`)
        encoder, `byte` = `data_type: byte`. Serverless (`aoss`) omits the explicit
        `index.knn` setting (managed `es` sets it), matching NextGen constraints.
  - [x] 2.4 Reserved an `ownerScope` keyword field for the future private-recipe feature
        (design §9a); unused today, avoids a re-mapping later.
  - [x] Tests: `OpenSearchIndexProvisionerTest` (7 tests, all pass) verify field types,
        not-indexed fields, the Faiss HNSW vector definition, `ownerScope`, and all three
        quantization modes.
  - _Requirements: 3.1, 3.2, 3.3, 3.4, 10.1_

- [x] 3. OpenSearchCatalogSearchService (the implementation behind the seam)
  - [x] 3.1 Added `OpenSearchCatalogSearchService implements CatalogSearchService` in
        `io.asbun.backend.search`, `@Component` conditional on
        `catalog.search.backend=opensearch` (coexists with the in-app `@Component`; task 4
        wires the `@Primary` selector).
  - [x] 3.2 `search`: dietary filter is one `term` filter per required tag (AND, mirrors
        `containsAll`); keyword `multi_match` over `title^3, description, ingredients`; knn
        clause on `embedding` per mode + semantic-enabled; blank text => `match_all` browse;
        `from`/`size` clamped (page/pageSize floored, long offset guard); `totalMatches` from
        `hits.total`.
  - [x] 3.3 Hybrid: keyword + knn as `should` clauses with `minimum_should_match=1` (score
        blend, the design's flavor-robust choice over the version-dependent hybrid pipeline).
        `minimum_should_match=1` makes text a filter (not just a sort), mirroring the in-app
        drop-if-no-signal behavior.
  - [x] 3.4 Semantic fallback: `embedQuietly` returns null instead of throwing on embed
        failure, so `search` drops the vector clause and runs keyword-only.
  - [x] 3.5 `findById`: client `get` by id → `CatalogRecipeDto` when found, else
        `Optional.empty()`. DTO excludes `embedding`/`searchText`; the client mapper is
        configured to ignore unknown document fields.
  - [x] 3.6 OpenSearch `IOException`s wrapped in `IllegalStateException` (not swallowed into an
        empty page) → the existing `GlobalExceptionHandler` catch-all returns 500.
  - [x] Also: `OpenSearchConfig` transport now uses a lenient `JacksonJsonpMapper`
        (`FAIL_ON_UNKNOWN_PROPERTIES` off + JSR-310) so hits with `embedding`/`ownerScope`
        deserialize into the DTO cleanly. Compile clean; existing catalog/embedding tests pass
        (query-clause assertion tests are Task 8).
  - _Requirements: 1.1, 1.2, 1.3, 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7_

- [x] 4. Backend selection wiring
  - [x] 4.1 `CatalogSearchConfig` now returns `OpenSearchCatalogSearchService` when
        `catalog.search.backend=opensearch` (replaced the warn-and-fallback stub), in-app
        otherwise. The OpenSearch bean is injected via `ObjectProvider` (it is conditional, so
        absent in `inapp` mode); if `opensearch` is selected but the bean is unavailable, the
        factory throws (fail-fast, consistent with `OpenSearchConfig`) rather than silently
        serving in-app results.
  - [x] 4.2 Controller, `CatalogRecipeDto`, and frontend unchanged; `CatalogControllerTest`
        (8) and `InAppCatalogSearchServiceTest` (8) pass unchanged, confirming the default
        `inapp` selection still resolves with the new provider parameter.
  - _Requirements: 1.4, 1.5_

- [x] 5. Two catalog tables (rollback preservation)
  - [x] 5.1 Added `dynamodb.catalog-full-table` (defaults to `dynamodb.catalog-table`, so
        behavior is unchanged until the full load is set up). `CatalogRecipeRepository` gained
        `forTable(name)` (returns a repo bound to a different table, sharing the client) and
        `tableName()`. `CatalogIngestionRunner` now targets `forTable(catalog-full-table)`, so
        loading the full dataset never overwrites the small in-app table.
  - [x] 5.2 The default `@Repository` bean stays bound to the small table (in-app backend +
        controller unchanged); both tables use the same `CatalogRecipe` schema so no code
        change is needed to operate on either. The reindex job (task 6) will use the same
        `forTable(...)` mechanism.
  - [x] Verified: compile clean; `CatalogControllerTest` (8, nested-default `@Value` resolves
        in context), `InAppCatalogSearchServiceTest` (8, mocked repo signatures preserved),
        `OpenSearchIndexProvisionerTest` (7) all pass.
  - _Requirements: 4.1, 4.2, 4.3, 4.4_

- [x] 6. Reindex from DynamoDB (no re-embedding)
  - [x] 6.1 `CatalogReindexRunner` (`CommandLineRunner`) gated by
        `catalog.reindex.enabled=true` (never on normal boot); reads the configured table via
        `repository.forTable(dynamodb.catalog-full-table)` and streams it with a new
        `scanInPages(...)` (page-by-page, so a 2.2M table isn't materialized in memory). Calls
        `provisioner.ensureIndex()` first.
  - [x] 6.2 Bulk-indexes the persisted `CatalogRecipe` (incl. `embedding`) in batches of
        `catalog.reindex.batch-size` (default 500), using `catalogRecipeId` as the doc id
        (idempotent upsert). Per-item bulk errors counted, first error logged.
  - [x] 6.3 No Bedrock/`EmbeddingService` dependency at all (vectors read from DynamoDB); logs
        `seen / indexed / skipped / failed` progress + final summary; safe to re-run.
  - [x] Tests: `CatalogReindexRunnerTest` (3, all pass) — doc id = `catalogRecipeId`, skips
        id-less recipes, batches flush at batch-size. The runner's constructor takes no
        embedding service, which is itself the proof of no-re-embedding.
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
