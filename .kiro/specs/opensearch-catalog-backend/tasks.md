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

- [x] 7. Infrastructure as code (opt-in, cost-bounded)
  - [x] 7.1 New `infrastructure/modules/opensearch/` gated by `enable_opensearch=false` default
        (every resource `count = enable ? 1 : 0`, so the standard deployment provisions nothing).
  - [x] 7.2 Serverless (NextGen flavor): `VECTORSEARCH` collection + encryption/network/data
        access policies. Scale-to-zero is the serverless default (idle → 0 OCU). NOTE: the
        account-level OCU cap is not settable via the AWS Terraform provider yet
        (hashicorp/terraform-provider-aws #41245); documented as a one-time CLI step using
        `max_search_ocu`/`max_indexing_ocu`, with the billing budget as the TF-managed guardrail.
  - [x] 7.3 Least-privilege: the data-access policy scopes the ECS task role to the collection's
        indexes/collection; added `aoss:APIAccessAll` (data-plane grant) to the task role in
        `modules/iam`; exposed `task_role_name`.
  - [x] 7.4 `collection_endpoint` output feeds `OPENSEARCH_ENDPOINT`; ECS task def now injects
        `CATALOG_SEARCH_BACKEND`, `OPENSEARCH_ENDPOINT`, `OPENSEARCH_INDEX`, and
        `DYNAMODB_CATALOG_FULL_TABLE` (env-var names verified to match `application.properties`).
  - [x] 7.5 Added the opt-in `catalog-full` DynamoDB table (`enable_catalog_full=false` default)
        with a `catalog_full_table_name` output.
  - [x] 7.6 AWS Budget (COST, scoped to OpenSearch + Bedrock) with actual-80%/forecast-100%
        email alerts; created only when enabled and an email is provided. Default limit $30
        (design §2.1, ~$15 expected).
  - [x] Verified: `terraform validate` succeeds, `terraform fmt -check` clean; defaults create
        no new infra (opt-in gating).
  - _Requirements: 4.4, 7.1, 7.2, 7.3, 7.4, 7.5, 7.6_

- [x] 8. Tests
  - [x] 8.1 `OpenSearchCatalogSearchServiceTest` (11) — captures the built `SearchRequest` and
        asserts the typed query: dietary tags → one `term` filter each; keyword mode →
        `multi_match` (no knn, no embed call); semantic mode → knn (no multi_match); hybrid →
        both + `minimum_should_match=1`; blank text → `match_all` browse with dietary filter;
        pagination (from=page*size, negative/zero clamped).
  - [x] 8.2 Same class — response mapping (hits → DTOs, `totalMatches` from hits total) and
        `findById` present/absent.
  - [x] 8.3 Same class — embed throws → vector clause dropped, keyword clause retained.
  - [x] 8.4 `CatalogSearchConfigTest` (3) — defaults to in-app; selects OpenSearch when
        configured + available; fail-fast when `opensearch` selected but unavailable. Existing
        `CatalogControllerTest` (8) still passes unchanged.
  - [x] 8.5 `CatalogReindexRunnerTest` (3, from task 6) — idempotent doc id, skips id-less,
        batches flush; constructor takes no embedding service (no-Bedrock proof).
  - _Requirements: 1.5, 2.5, 5.3, 5.4_

- [x] 9. Migration, verification, and docs
  - [x] 9.1 Full `./mvnw test` run: all catalog/OpenSearch/embedding tests green
        (`OpenSearchCatalogSearchServiceTest` 11, `CatalogSearchConfigTest` 3,
        `CatalogReindexRunnerTest` 3, `OpenSearchIndexProvisionerTest` 7, `CatalogControllerTest`
        8, `InAppCatalogSearchServiceTest` 8, etc.). The only failures are the 4 pre-existing
        compliance/audit/export property tests documented in `existing-recipe-search` — none in
        this feature's code.
  - [x] 9.2 Parity check documented in `RUNBOOK.md` §5: automated (catalog test glob) + manual
        (keyword, semantic, dietary filter, pagination, detail, 404, fallback).
  - [x] 9.3 Both rollbacks documented in `RUNBOOK.md` §4: (a) fast — flip
        `catalog_search_backend=inapp` reads the untouched small table; (b) rebuild — re-ingest
        ~50K from the local dataset into the small table, then flip.
  - [x] 9.4 Wrote `.kiro/specs/opensearch-catalog-backend/RUNBOOK.md`: provision → OCU-cap CLI
        step → deploy → reindex → verify → cutover → both rollbacks, plus config reference,
        cost/capacity, budget, and quantization guidance for 2.2M.
  - [x] 9.5 Isolation confirmed via `git diff --stat origin/main..HEAD`: the feature touched
        only new OpenSearch classes/tests, the `opensearch` TF module, and additive/config edits
        (`CatalogSearchConfig` selector, `CatalogRecipeRepository` new methods,
        `CatalogIngestionRunner` +9 lines for the target table, properties, TF wiring). No change
        to `BedrockService`, `Recipe`/`RecipeService`, dietary endpoints, or `DietaryRestriction`;
        in-app backend remains selectable.
  - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 9.1, 9.2, 9.3_

- [x] 10. Full 2.2M RecipeNLG load — CODE complete; the AWS run (10.3–10.5) is operator-invoked
  - [x] 10.1 `CatalogIngestionRunner` now loads `RecipeNlgCsvSource` when
        `catalog.ingest.recipenlg-file` is set, with `catalog.ingest.recipenlg-max-records=0`
        meaning no cap (full set), targeting the `catalog-full` table (Task 5 mechanism).
  - [x] 10.2 Finished `BatchEmbeddingStrategy` (real Bedrock Batch Inference): writes JSONL to
        S3, submits `CreateModelInvocationJob`, polls to completion, then STREAMS the S3 output
        back via a per-record callback (`embedAll(inputs, BiConsumer)`) so the ~4.4 GB output per
        100K chunk is never held in memory. Splits input into sub-jobs respecting the Bedrock
        limits (100K records / 1 GB input file). Added the `bedrock` control-plane SDK dep +
        `BedrockClient` bean + batch config. `BatchEmbeddingStrategyTest` (5) covers
        upload/submit/poll/stream-parse, job-failed, missing-config, unsupported single embed,
        and job-splitting >100K.
        Real-world hardening discovered running the full load: (a) `RecipeNlgCsvSource.stream()`
        made truly streaming — `runBatch` consumes it and flushes bounded chunks (fixed an OOM
        where `load()` materialized all 2.23M `ParsedRecipe`); verified streaming 2.23M in a
        256 MB heap. (b) DynamoDB persist switched to `saveAll` (`BatchWriteItem`, 25/call) —
        per-item `putItem` was ~141 writes/sec. (c) per-record dedup made optional/off by default
        (`catalog.ingest.batch-dedup`) — it was a DynamoDB read per recipe. (d) `recipenlg-skip-records`
        offset added for cross-run resume. (e) `scripts/run-full-catalog-ingest.sh` — self-
        caffeinating, always-rebuild, prereq checks, logged.
  - [x] 10.3 (DONE — full load run) Ran `run-full-catalog-ingest.sh` (batch mode) → `catalog-full`
        populated with the entire dataset: **2,231,142 seen, 0 skipped, 2,231,142 persisted,
        0 missing-vector** across 23 sequential Bedrock jobs (~17.5h wall clock; per-job time is
        Bedrock-queue-dependent, 7–30 min observed). Every recipe embedded (Titan V2, 1024-dim)
        and stored. Cost ~$8–15 one-time as estimated.
  - [x] 10.4 (DONE — VERIFIED COMPLETE) Reindexed `catalog-full` → OpenSearch with
        `opensearch.knn.quantization=fp16`. **Final state: OpenSearch `_count` = 2,231,142 =
        DynamoDB item count (exact match, 0 missing, 0 duplicates), confirmed by the
        `catalog.reindex.verify-count` check.** This task was a multi-day ordeal against OpenSearch
        Serverless quirks; the full post-mortem is in `documents/opensearch-implementation.md`.
        Summary of what it took:
        - **Throttling (HTTP 429 / "[throttled]" 503):** serverless auto-scales indexing OCUs; at
          concurrency 8 it throttled at ~1.06M docs. Root fix was `flushBatch` catching
          `Exception` (not just `IOException`) so whole-request 429s are retried + recorded; also
          lowered concurrency 8→4.
        - **Silent drops:** because 429s escaped as `OpenSearchException`, a run reported
          "7,000 failed" while 68,000 were actually missing (61,000 unrecorded). Fixed by the
          catch-widening above; discovered via `seen − indexed` arithmetic.
        - **No upsert on serverless:** aoss auto-generates `_id` and rejects `_update/_id` /
          `PUT _doc/_id` on VECTORSEARCH collections, so re-indexing duplicates. Recovery must only
          index truly-absent docs → reconciliation.
        - **Reconciliation:** scroll (404) and a large `terms` existence query (500) both fail on
          this collection; **PIT + `search_after`** (endpoint `POST /{index}/_search/point_in_time`)
          is the working deep-pagination path. Added `_id` sort tiebreaker + PIT-expiry recovery.
        - **Slow/hung scans:** the DynamoDB reconcile scan pulled full ~13 KB items (incl. the
          1024-dim embedding) → ~29 GB serial; fixed with an id-only projected + parallel (8-segment)
          scan. Then it HUNG for ~50 min on a stuck socket because **neither AWS client had any
          timeouts** — the real root cause. Added apiCall/attempt timeouts + retries to the
          DynamoDB client and connection/idle timeouts to the OpenSearch client.
        - **Completion path:** a self-healing reindex script (recreate → auto-backfill failed ids)
          and a standalone reconciliation backfill (`run-catalog-backfill.sh`) that closed the final
          68,000-doc gap in one clean pass (68,000 indexed, 0 failed), then verify-count confirmed
          2,231,142.
  - [ ] 10.5 Finish the cutover (index is complete + verified):
        - [ ] Re-verify search parity + performance at 2.2M (keyword/semantic/dietary/pagination).
        - [ ] Tune `ef-search` / confirm OCU cap (8/8) and the budget threshold.
        - [ ] Cut over `catalog.search.backend=opensearch` in ECS/Terraform.
        - [ ] Remove the temporary CLI data-access principal
          (`arn:aws:iam::412381751532:user/rodrigo-cli`) from the `recipe-ai-dev-catalog-data`
          access policy.
        - [ ] Final `RUNBOOK.md` update.
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
