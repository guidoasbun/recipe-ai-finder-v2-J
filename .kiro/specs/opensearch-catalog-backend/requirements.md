# Requirements — OpenSearch Catalog Search Backend

## Overview

Migrate the catalog search feature (`existing-recipe-search`) from the in-app, JVM-memory
search backend to an Amazon OpenSearch backend, so the catalog can grow past the ~50K in-app
ceiling toward the full ~2.2M RecipeNLG dataset while preserving keyword, semantic (k-NN
vector), and dietary-tag search.

This is a **backend swap behind an existing seam**, not a rewrite. The prior feature was
built specifically to make this migration a configuration change plus one new implementation
class:

- Search already sits behind the `CatalogSearchService` interface (`search(...)` +
  `findById(...)`), selected by the `catalog.search.backend` property in
  `CatalogSearchConfig` (the `opensearch` branch is already stubbed to warn + fall back).
- Every `CatalogRecipe` already persists its `dietaryTags` and 1,024-dim Titan V2
  `embedding` in DynamoDB, so OpenSearch can be indexed from that table with **no
  re-embedding**.
- The controller, DTOs (`CatalogRecipeDto`), and frontend depend only on the interface and
  must not change.

### Scope decisions (to confirm during design)

- **Target region is us-east-1** (matches all existing infrastructure). OpenSearch Serverless
  NextGen and managed OpenSearch domains are both available there.
- **DynamoDB remains the system of record** for catalog recipes and their embeddings.
  OpenSearch is a derived search index, rebuildable by reindexing from DynamoDB.
- **The migration is reversible**: flipping `catalog.search.backend` back to `inapp` restores
  the previous behavior with no data loss, as long as the catalog stays within the in-app
  ceiling.
- **Two backend flavors are in scope to evaluate**: OpenSearch Serverless NextGen
  (scale-to-zero, better for this app's bursty/low traffic) and a managed domain (no cold
  starts, better for steady traffic or a large always-resident index). The design must pick a
  default and justify it; the implementation must not hard-code assumptions that block the
  other.
- **Cost control is a first-class requirement.** The default configuration must not silently
  provision an always-on, high-cost cluster. Any OpenSearch resource must be opt-in and
  capacity-bounded.
- The **full 2.2M ingestion** (batch embedding via `BatchEmbeddingStrategy`, currently a
  scaffold) may be enabled by this migration but its execution is a separate operational step,
  not a gate on shipping the OpenSearch backend.
- **Two catalog tables to preserve rollback:** the existing small catalog table stays intact
  for the in-app backend (fast rollback), and the full 2.2M dataset is loaded into a separate
  table used only to build the OpenSearch index. Rollback to in-app never depends on the full
  table.
- **Rebuild-from-local is an additional safety net:** because the RecipeNLG dataset is on the
  local machine and the ingestion pipeline is idempotent with a record cap, a ~50K in-app
  catalog can be re-ingested on demand if the small table is ever lost or stale.
- **Future (out of scope, but must not be blocked):** a later feature will let users create
  and save private recipes and search across both the public catalog and their own private
  recipes. The OpenSearch backend design must leave room for this (per-owner scoping) without
  building it now.

---

## Requirement 1 — OpenSearch search implementation behind the existing interface

**User story:** As the developer, I want an `OpenSearchCatalogSearchService` that satisfies
the existing `CatalogSearchService` interface, so that switching backends requires no changes
to the controller, DTOs, or frontend.

### Acceptance criteria

1. The system SHALL provide an `OpenSearchCatalogSearchService` that implements
   `CatalogSearchService` (`search(CatalogSearchQuery)` and `findById(String)`).
2. `search` SHALL translate a `CatalogSearchQuery` into an OpenSearch query that applies
   keyword matching, semantic (k-NN vector) matching, and a dietary-tag filter consistent
   with the in-app behavior, and SHALL return `CatalogSearchResults` with correct pagination
   metadata (`page`, `pageSize`, `totalMatches`).
3. `findById` SHALL return the matching `CatalogRecipeDto` or empty, mapping the OpenSearch
   document to the same DTO shape the in-app backend returns (no `embedding`/`searchText`
   exposed).
4. WHEN `catalog.search.backend=opensearch` THEN `CatalogSearchConfig` SHALL select the
   OpenSearch implementation; WHEN it is `inapp` (default) THEN it SHALL select the in-app
   implementation. No caller code SHALL change to switch.
5. The controller, `CatalogRecipeDto`, request/response contracts, and frontend SHALL require
   no changes to switch backends (verified by the existing web-layer tests continuing to pass).

---

## Requirement 2 — Search parity with the in-app backend

**User story:** As a user, I want OpenSearch results to behave like the current search, so
that the migration is invisible to me except for scale.

### Acceptance criteria

1. WHEN a keyword query is submitted THEN the OpenSearch backend SHALL match against title,
   description, and ingredients, weighting title higher, consistent with the in-app ranking
   intent.
2. WHEN semantic search is enabled AND a query is non-blank THEN the backend SHALL embed the
   query via the existing `EmbeddingService` and rank by k-NN vector similarity against the
   stored recipe embeddings.
3. WHEN `catalog.search.mode` is `keyword`, `semantic`, or `hybrid` THEN the backend SHALL
   honor that mode, matching the in-app semantics (hybrid blends keyword and vector scores).
4. WHEN dietary tags are supplied on the query THEN results SHALL include only recipes whose
   `dietaryTags` satisfy every requested tag (filter, not rank).
5. IF the query embedding call fails THEN the backend SHALL degrade to keyword-only search and
   still return results (no user-facing hard failure), matching the existing fallback.
6. WHEN a query is blank/whitespace THEN the backend SHALL return a browse listing rather than
   an error, and SHALL respect pagination bounds (default and max page size).
7. WHEN no recipes match THEN the backend SHALL return an empty result set (not an error).

---

## Requirement 3 — Index provisioning and mapping

**User story:** As the operator, I want the OpenSearch index defined as code with the correct
mapping for vectors and tags, so that the index is reproducible and tuned for the dataset.

### Acceptance criteria

1. The system SHALL define an index (or collection) whose mapping includes: text fields for
   title/description/ingredients (analyzed for keyword search), a `knn_vector` field of
   dimension 1,024 for the embedding, and a keyword field for `dietaryTags` (exact-match
   filtering), plus stored attribution fields for detail rendering.
2. The k-NN field SHALL be configured with a similarity/space consistent with the normalized
   Titan V2 vectors (cosine), and an ANN method appropriate for the target scale.
3. The index mapping and settings SHALL be created idempotently (create-if-absent) so the
   provisioning step is safe to re-run.
4. The design SHALL address memory footprint at 2.2M vectors (~9 GB raw, more with the ANN
   graph) and SHALL evaluate vector quantization (e.g. byte/scalar) to reduce it, documenting
   the recall/cost tradeoff.

---

## Requirement 4 — Separate full-catalog table (preserve in-app rollback)

**User story:** As the operator, I want the full 2.2M dataset stored separately from the small
in-app catalog table, so that rolling back to in-app search never depends on a table too large
to fit in memory.

### Acceptance criteria

1. The system SHALL support two DynamoDB catalog tables: the existing small table
   (`dynamodb.catalog-table`, ~1.3K–≤50K, read by the in-app backend) and a separate full
   table (`dynamodb.catalog-full-table`, up to 2.2M, used to build the OpenSearch index).
2. The ingestion and reindex targets SHALL be configurable (a table-name property) so loading
   the full dataset SHALL NOT overwrite or grow the small in-app table.
3. Both tables SHALL use the same `CatalogRecipe` schema so the reindex, in-app backend, and
   ingestion pipeline operate on either without code changes.
4. The full table SHALL be provisioned only when the full load is enabled (opt-in), consistent
   with the cost-bounding requirement.

---

## Requirement 5 — Reindex from DynamoDB (no re-embedding)

**User story:** As the operator, I want to build the OpenSearch index from the existing
`CatalogRecipe` table, so that I never recompute embeddings that are already persisted.

### Acceptance criteria

1. The system SHALL provide a reindex process that scans the configured `CatalogRecipe`
   DynamoDB table (small or full) and writes each recipe (text + `dietaryTags` + persisted
   `embedding` + attribution) into OpenSearch using bulk indexing.
2. The reindex process SHALL be a one-off/operator-invoked job (like ingestion), gated behind
   configuration, and SHALL NOT run on normal application boot.
3. The reindex SHALL use the deterministic `catalogRecipeId` as the OpenSearch document id so
   re-running is idempotent (upsert, no duplicates).
4. The reindex SHALL NOT call Bedrock for recipe embeddings (they are read from DynamoDB); it
   SHALL only use the persisted vectors.
5. The reindex process SHALL log progress and a final summary (indexed / skipped / failed) and
   SHALL be resumable/re-runnable safely.

---

## Requirement 6 — Configuration and backend selection

**User story:** As the operator, I want to control the backend and OpenSearch connection via
configuration, so that enabling OpenSearch is an explicit, environment-scoped change.

### Acceptance criteria

1. The system SHALL add configuration for the OpenSearch endpoint, index/collection name,
   auth mode (IAM/SigV4), and any k-NN/quantization tuning knobs, following the existing
   `application.properties` env-override style.
2. The default configuration SHALL keep `catalog.search.backend=inapp`; OpenSearch SHALL be
   opt-in via `catalog.search.backend=opensearch`.
3. WHEN `catalog.search.backend=opensearch` but required OpenSearch configuration is missing
   or the endpoint is unreachable at startup THEN the system SHALL fail fast with a clear
   error (not silently serve empty results), OR fall back to in-app with a prominent warning —
   the design SHALL choose and document one behavior.
4. Semantic search enable/disable (`catalog.search.semantic-enabled`) and mode
   (`catalog.search.mode`) SHALL apply to the OpenSearch backend the same way they apply to
   the in-app backend.

---

## Requirement 7 — Infrastructure as code and cost bounding

**User story:** As the owner, I want any OpenSearch infrastructure defined in Terraform and
cost-bounded, so that enabling it is auditable and can't run away on cost.

### Acceptance criteria

1. Any OpenSearch resource (managed domain or serverless collection + policies) SHALL be
   defined in the existing Terraform structure (`infrastructure/modules/...`), consistent with
   how DynamoDB, ECS, and WAF are modeled.
2. The OpenSearch infrastructure SHALL be gated so it is NOT provisioned by default (e.g. a
   count/feature flag), keeping the current default deployment cost unchanged.
3. IF OpenSearch Serverless is chosen THEN the configuration SHALL set a maximum OCU cap and
   SHALL use scale-to-zero (min OCU 0) to bound idle cost; the design SHALL note the
   ~10-minute idle window and cold-start latency tradeoff.
4. IF a managed domain is chosen THEN the instance type and node count SHALL be justified
   against the target index size and documented as the cost driver.
5. The IAM permissions granting the backend service access to OpenSearch SHALL be least-
   privilege and defined in the existing IAM module.
6. The system SHALL provision a billing/cost alarm (CloudWatch billing alarm or AWS Budget)
   so unexpected OpenSearch/Bedrock spend is surfaced. The design SHALL document the expected
   monthly cost range (≈$7–20/mo recurring, ≈$5–10 one-time embedding) so the alarm threshold
   is meaningful.

---

## Requirement 8 — Migration, cutover, and rollback

**User story:** As the operator, I want a documented, low-risk cutover with a rollback path,
so that adopting OpenSearch is safe.

### Acceptance criteria

1. The system SHALL support a migration sequence: provision index → reindex from DynamoDB →
   verify parity → flip `catalog.search.backend` to `opensearch`.
2. **Fast rollback** SHALL be a single configuration change (`catalog.search.backend=inapp`)
   with no data migration required. To keep this valid after the full 2.2M load, the small
   in-app catalog table SHALL be preserved separately from the full table (Requirement 4a),
   so the in-app backend always has a table it can hold in memory.
3. **Rebuild rollback** SHALL be supported: the operator SHALL be able to re-ingest a bounded
   (~50K) in-app catalog from the local RecipeNLG dataset using the existing capped,
   idempotent ingestion path, then flip to `inapp`. This is the safety net if the small table
   is lost or stale. It need not be instant (minutes acceptable) and need not be byte-for-byte
   identical to the prior catalog (a working subset is sufficient).
4. The RUNBOOK SHALL document provisioning, reindex, both rollback paths, and the
   cost/capacity settings for the chosen flavor.
5. Parity verification SHALL include running the existing catalog test suite plus a documented
   manual check (keyword, semantic, dietary filter, pagination, detail, 404) against the
   OpenSearch backend.

---

## Requirement 9 — Isolation from unchanged features

**User story:** As the owner, I want the migration to leave everything else untouched, so it's
safe to ship.

### Acceptance criteria

1. The migration SHALL NOT modify the AI generation flow, the saved-recipe (`Recipe`) model or
   table, the dietary-restriction endpoints, or the `existing-recipe-search` ingestion pipeline
   behavior.
2. The migration SHALL NOT change the `CatalogRecipe` DynamoDB schema in a way that breaks the
   in-app backend; DynamoDB remains the system of record and the in-app backend remains a valid
   fallback.
3. The in-app backend and its tests SHALL remain functional after the migration (the two
   backends coexist, selected by configuration).

---

## Requirement 10 — Future extension: search private user recipes (NOT in scope now)

**User story (future):** As a user, I want to search across both the public catalog and my own
saved/private recipes in one search, so that all my recipes are discoverable together, while my
private recipes stay visible only to me.

This requirement is documented so the OpenSearch backend built now does not block it. It is
**not implemented in this spec.**

### Acceptance criteria (for the future feature; captured for design-fit only)

1. The design SHALL leave room for a per-document owner/visibility scope (e.g. an
   `ownerScope` field: `"public"` for catalog recipes or a `userId` for a private recipe) so
   public and private recipes can coexist in one index.
2. A future user search SHALL be able to filter to `ownerScope = "public" OR ownerScope =
   <requesting userId>`, enforced server-side so a user can never retrieve another user's
   private recipes.
3. Indexing a single private recipe on save SHALL be able to reuse the existing per-item
   `EmbeddingService.embed` (synchronous, one call) — no batch job needed for one recipe.
4. Adding this feature SHALL NOT require changing the `CatalogSearchService` interface; it
   extends the query (owner filter) and the ingestion (index on save), not the search seam.
