# Design — OpenSearch Catalog Search Backend

## 1. Goals and constraints

- Add an `OpenSearchCatalogSearchService` that satisfies the existing `CatalogSearchService`
  interface, selected by `catalog.search.backend=opensearch`.
- Preserve keyword + semantic (k-NN) + dietary-tag search parity with the in-app backend.
- Build the index by reindexing from the `CatalogRecipe` DynamoDB table — **no re-embedding**
  (vectors are already persisted).
- Keep DynamoDB as the system of record; OpenSearch is a derived, rebuildable index.
- Define infrastructure as Terraform, opt-in and cost-bounded; default deployment cost stays
  unchanged.
- Touch nothing outside the catalog search backend (controller, DTOs, frontend, ingestion,
  AI generation, saved recipes all unchanged).

### What already exists (the seam this design plugs into)

```
CatalogController  ──▶  CatalogSearchService (interface)
                           ├─ InAppCatalogSearchService   (default, JVM memory)
                           └─ OpenSearchCatalogSearchService   ← THIS SPEC

CatalogSearchConfig  ── selects impl from catalog.search.backend (opensearch branch stubbed)

CatalogRecipe (DynamoDB)  ── persists dietaryTags + 1024-dim Titan V2 embedding
EmbeddingService          ── embeds a single query at search time (unchanged)
```

`CatalogSearchQuery(text, dietaryTags, page, pageSize)` and
`CatalogSearchResults(items, page, pageSize, totalMatches)` are the fixed contract. The
OpenSearch impl consumes/produces exactly these.

---

## 2. Backend flavor decision

Two viable flavors in us-east-1 (both confirmed available):

| Flavor | Idle cost | Cold start | Fit for this app |
|---|---|---|---|
| **Serverless NextGen** (scale-to-zero) | ~$0 compute when idle (storage only) | 10–30s+ first request after ~10 min idle | Bursty / very low traffic (2 users) |
| **Managed domain** (r-family, 16 GB+) | Always-on (~$130+/mo single node) | None | Steady traffic or always-resident large index |

**Decision (confirmed with owner): OpenSearch Serverless NextGen**, because the app's real
traffic is a handful of actions per day with long idle gaps — scale-to-zero matches that and
keeps idle cost near zero. The cold-start latency on the first query after idle is an
acceptable tradeoff for a non-time-critical "browse recipes" feature.

Cost honesty for this app: at current traffic the **in-app backend ($0) is the cheapest thing
that works** and remains the right choice while the catalog fits under ~50K. OpenSearch is
justified specifically to serve the full ~2.2M RecipeNLG dataset (which cannot fit in JVM
memory). Among OpenSearch options for this bursty/low-traffic profile, NextGen is clearly the
most cost-effective: near-$0 compute while idle vs. ~$130+/mo always-on for a managed
r-family domain. Expect NextGen to still cost single-digit to low-tens of $/month once the
full 2.2M index exists (vector storage + brief warm compute per burst), which is the price of
running semantic search over 2.2M recipes at all.

The implementation must not hard-code serverless-only assumptions: the client is configured
by endpoint + auth, so pointing at a managed domain instead is a config change, not a code
change. The design documents both so the owner can switch if traffic becomes steady.

NextGen operational facts baked into the design (from AWS docs):
- Scale-to-zero is per **collection group**: compute drops to 0 OCU only when every collection
  in the group is idle for 10 minutes (not configurable).
- Vector collections load only the data blocks needed for active requests into memory and
  scale workers on demand, which softens the "whole ANN graph must stay resident" concern that
  applies to managed domains.
- Always set `maxSearchCapacityInOCU` (and indexing cap) to bound spend.

### 2.1 Estimated cost for this app (full 2.2M index)

Rates (us-east-1, confirm live before committing): OCU-hour **$0.24**; NextGen OCU increments
0/2/4/8/16… with min 0 = scale-to-zero; Titan V2 embeddings **$0.00002 / 1K tokens** on-demand
(**$0.00001** batch). Sources: AWS OpenSearch pricing, OpenSearch capacity docs, Bedrock
pricing. Content rephrased for compliance.

| Item | Estimate | Type | Notes |
|---|---|---|---|
| OpenSearch compute (NextGen) | ~$5–15/mo | recurring | Scale-to-zero; driven by how often searches wake it |
| OpenSearch storage | ~$0.50–1/mo | recurring | ~15–25 GB S3-backed vectors+text |
| DynamoDB (2.2M items) | ~$1–3/mo | recurring | Storage + light reads (item sizes estimated, not measured) |
| DynamoDB write (2.2M ingest) | ~$3–5 | one-time | On-demand write units for the initial load |
| Bedrock embeddings (2.2M) | ~$3–6 | one-time | Batch: ~330M tokens × $0.00001 ≈ $3.3; on-demand ~2x |

**Recurring ≈ $7–20/month (plan for ~$15).** One-time ≈ $10. The dominant variable is how
often search bursts wake the compute: clustered usage → low end; queries scattered across the
day (each triggering its own ~10-min warm window) → high end. The OCU cap bounds the ceiling.
These are estimates from published rates, not a quote — pair the OCU cap with a billing alarm
(§7).

---

## 3. Data model and index mapping

DynamoDB `CatalogRecipe` is unchanged. The OpenSearch document mirrors it, minus the internal
`searchText` (OpenSearch analyzers replace it) and using a native `knn_vector` for the
embedding.

Index mapping (managed-domain form shown; serverless vector collection uses the equivalent
`knn_vector` mapping):

```json
{
  "settings": { "index.knn": true },
  "mappings": {
    "properties": {
      "catalogRecipeId": { "type": "keyword" },
      "title":       { "type": "text", "fields": { "kw": { "type": "keyword" } } },
      "description": { "type": "text" },
      "ingredients": { "type": "text" },
      "steps":       { "type": "text", "index": false },
      "dietaryTags": { "type": "keyword" },
      "imageUrl":    { "type": "keyword", "index": false },
      "sourceName":  { "type": "keyword" },
      "sourceUrl":   { "type": "keyword", "index": false },
      "sourceLicense": { "type": "keyword", "index": false },
      "sourceCountry": { "type": "keyword" },
      "embedding": {
        "type": "knn_vector",
        "dimension": 1024,
        "method": {
          "name": "hnsw",
          "space_type": "cosinesimil",
          "engine": "faiss",
          "parameters": { "ef_construction": 128, "m": 16 }
        }
      }
    }
  }
}
```

Notes:
- **Cosine** matches the normalized Titan V2 vectors (`EmbeddingService` sends
  `"normalize": true`).
- **Quantization (Requirement 3.4):** at 2.2M vectors the raw footprint is ~9 GB and larger
  with the HNSW graph. FAISS scalar quantization (fp16) roughly halves it; byte/PQ cuts it
  ~4x. The design's recommendation: start unquantized for correctness during parity
  verification at the current ~1.3K–50K scale, and enable fp16/byte quantization before
  loading the full 2.2M set, documenting the small recall tradeoff. This is a config/mapping
  knob, not a code change in the search service.
- `steps` and attribution URLs are stored but not analyzed (retrieved for detail rendering).

---

## 4. `OpenSearchCatalogSearchService`

New class in `io.asbun.backend.search`, implementing `CatalogSearchService`.

### 4.1 Query translation

Given `CatalogSearchQuery(text, dietaryTags, page, pageSize)` and the configured
`catalog.search.mode` + `catalog.search.semantic-enabled`:

- **Dietary filter (always, when tags present):** a `bool.filter` with a `terms`/`term` clause
  per tag so every requested tag must be present (AND semantics, matching in-app).
- **Keyword clause (`keyword`/`hybrid`, non-blank text):** `multi_match` over
  `title^3, description, ingredients` (title boosted, mirroring the in-app title weighting).
- **Vector clause (`semantic`/`hybrid`, non-blank text, semantic enabled):** embed the query
  via `EmbeddingService.embed(text)` and issue a `knn` query on `embedding`.
- **Hybrid:** combine keyword + knn. Preferred approach is OpenSearch hybrid search (search
  pipeline with normalization) where available; a `bool.should` blend is the fallback. The
  design picks hybrid-pipeline if the chosen flavor/version supports it, else score blending,
  matching the in-app "weighted blend" intent.
- **Blank text => browse:** `match_all` (plus dietary filter), sorted deterministically.
- **Pagination:** `from = page * pageSize`, `size = pageSize`, both bounded by
  `page-size-default`/`page-size-max` (clamping done consistently with the in-app path).
- **totalMatches:** from the response `hits.total.value`.

### 4.2 Fallback and errors

- IF `EmbeddingService.embed` throws (Requirement 2.5) THEN drop the vector clause and run
  keyword-only, still returning results. Log at warn.
- IF an OpenSearch request fails THEN surface a clear server error via the existing
  `GlobalExceptionHandler` (do not return a fake empty page that hides an outage).

### 4.3 `findById`

`GET`/`get` by document id (`catalogRecipeId`) → map `_source` to `CatalogRecipeDto` (same
mapper the in-app path uses), or `Optional.empty()` → controller returns 404.

### 4.4 DTO mapping

Reuse the existing `CatalogRecipeDto` and its mapping. `embedding` and any internal fields are
not projected into the DTO. This keeps Requirement 1.5 (no DTO/contract change) true.

---

## 5. Client and configuration

### 5.1 Client bean

New `OpenSearchConfig` (in `io.asbun.backend.config`) producing an OpenSearch client
(`opensearch-java` with the AWS SDK v2 transport, SigV4-signed via the existing
`DefaultCredentialsProvider`, region from `aws.region`). Mirrors how `AwsConfig`/`DynamoDbConfig`
build clients.

- Serverless uses service name `aoss` for SigV4; managed domain uses `es`. The design selects
  the signing service from a property so both flavors work.
- The client bean is created only when `catalog.search.backend=opensearch` (conditional), so
  the default deployment does not require an endpoint.

### 5.2 Properties (added to `application.properties`, env-overridable)

```properties
# existing (unchanged defaults)
catalog.search.backend=${CATALOG_SEARCH_BACKEND:inapp}      # inapp | opensearch
catalog.search.semantic-enabled=${CATALOG_SEMANTIC:true}
catalog.search.mode=${CATALOG_SEARCH_MODE:hybrid}

# new — OpenSearch connection
opensearch.endpoint=${OPENSEARCH_ENDPOINT:}                 # https://... (collection or domain)
opensearch.index=${OPENSEARCH_INDEX:catalog-recipes}
opensearch.signing-service=${OPENSEARCH_SIGNING_SERVICE:aoss}  # aoss (serverless) | es (managed)
opensearch.knn.ef-search=${OPENSEARCH_KNN_EF_SEARCH:100}
opensearch.knn.quantization=${OPENSEARCH_KNN_QUANTIZATION:none} # none | fp16 | byte

# new — reindex job gate (mirrors catalog.ingest.enabled)
catalog.reindex.enabled=${CATALOG_REINDEX_ENABLED:false}
catalog.reindex.batch-size=${CATALOG_REINDEX_BATCH_SIZE:500}
```

### 5.3 Startup behavior (Requirement 5.3)

Chosen behavior: **fail fast**. When `catalog.search.backend=opensearch` and
`opensearch.endpoint` is blank, the OpenSearch bean's initializer throws a clear
configuration error at startup rather than serving empty results. Reachability is validated
lazily on first query with a descriptive error. Rationale: a silent fallback to in-app after
someone deliberately set `opensearch` could mask a misconfigured production cutover. (RUNBOOK
documents the one-line rollback to `inapp` if fail-fast fires unexpectedly.)

---

## 6. Reindex from DynamoDB

New `CatalogReindexRunner` (in `io.asbun.backend.ingest` or a new `search`-adjacent package),
`@ConditionalOnProperty(catalog.reindex.enabled=true)` — never runs on normal boot, mirroring
`CatalogIngestionRunner`.

Flow:
1. Ensure the index exists (create with the section 3 mapping if absent — idempotent).
2. Scan `CatalogRecipe` via the existing `CatalogRecipeRepository` (findAll/scan).
3. For each recipe, build the OpenSearch document (copy fields + persisted `embedding`); use
   `catalogRecipeId` as the document id (upsert → idempotent, Requirement 4.3).
4. Bulk index in batches of `catalog.reindex.batch-size`.
5. Log progress (`indexed / skipped / failed`) and a final summary; safe to re-run.

Explicitly: **no Bedrock calls for recipe vectors** — embeddings come from DynamoDB
(Requirement 4.4). The only Bedrock use in the OpenSearch path is single-query embedding at
search time, unchanged.

For the full 2.2M dataset, the upstream ingestion (populating DynamoDB with vectors) uses the
existing `BatchEmbeddingStrategy` path (separate operational step); reindex to OpenSearch is
the same runner regardless of catalog size.

### 6.0 Two catalog tables (rollback preservation)

To keep in-app rollback viable after the full load, the full dataset goes in a **separate**
DynamoDB table, not the one the in-app backend reads:

| Table (property) | Contents | Read by |
|---|---|---|
| `dynamodb.catalog-table` (existing) | small catalog (~1.3K, up to ≤50K) | in-app backend + reindex (small validation) |
| `dynamodb.catalog-full-table` (new, opt-in) | full ~2.2M + embeddings | reindex → OpenSearch only |

Both use the identical `CatalogRecipe` schema, so ingestion, reindex, and the in-app backend
operate on either by pointing at a table-name property. Loading 2.2M never touches the small
table, so:

- **Fast rollback:** `catalog.search.backend=inapp` reads the small table (fits in memory).
  No data migration.
- **Rebuild rollback:** if the small table is ever lost/stale, re-ingest a ~50K subset from the
  local RecipeNLG file using the existing capped, idempotent path (`RecipeNlgCsvSource` with
  `maxRecords=50000`), targeting the small table, then flip to `inapp`. Minutes, cents,
  repeatable, no duplicates. A working subset — not necessarily byte-for-byte identical to the
  prior catalog.

### 6.1 Full 2.2M ingestion prerequisite (dataset now present)

The RecipeNLG CSV is downloaded (`backend/data/recipeNGL/RecipeNLG_dataset.csv`). Loading the
full set into DynamoDB with embeddings, before the OpenSearch reindex can index it, requires:
- Registering `RecipeNlgCsvSource` in `CatalogIngestionRunner` with the `maxRecords` cap
  **lifted** (the cap exists only to protect the in-app JVM ceiling; the OpenSearch target has
  no such limit).
- Producing embeddings via the **`BatchEmbeddingStrategy`** (Bedrock Batch Inference, S3 JSONL,
  async, ~$11 one-time, ~50% cheaper than on-demand) rather than the synchronous per-request
  loop — this strategy is currently a scaffold and must be finished as part of enabling the
  full load. This is upstream of, and separate from, the OpenSearch search backend itself.

Because embeddings persist in DynamoDB, the OpenSearch reindex (§6) never re-embeds; it bulk-
indexes whatever vectors exist. So the OpenSearch backend can be built and validated against
the current ~1.3K catalog first, then the 2.2M load run when ready.

---

## 7. Infrastructure (Terraform)

New `infrastructure/modules/opensearch/` (or a serverless-collection module), consistent with
the `dynamodb`/`waf` module style. Gated so it is not provisioned by default (Requirement 6.2):

- A feature flag / `count` variable (e.g. `enable_opensearch = false` by default) so the
  current deployment provisions nothing new.
- **Serverless (default):** a vector-search collection (+ collection group), encryption /
  network / data-access policies, `maxSearchCapacityInOCU` cap, min OCU 0 (scale-to-zero).
- **Managed (alternative):** a domain with a justified r-family instance type and node count
  for the target index size (documented as the cost driver).
- **IAM (Requirement 7.5):** least-privilege access for the backend ECS task role to the
  collection/domain (data-access policy for serverless, or resource policy for managed) added
  to `infrastructure/modules/iam`.
- **Billing alarm (Requirement 7.6):** a CloudWatch billing alarm or AWS Budget with a
  threshold set from the §2.1 estimate (e.g. alert at ~$30/mo to catch a runaway well above
  the ~$15 expected), defined in Terraform alongside the OpenSearch module.
- **Full-catalog table (Requirement 4):** the new `catalog-full` DynamoDB table (§6.0) is
  added to `infrastructure/modules/dynamodb`, gated behind the same opt-in flag so it isn't
  provisioned by default.

Outputs the endpoint, which feeds `OPENSEARCH_ENDPOINT` for the ECS task (via the ECS module
env vars, alongside the existing `DYNAMODB_*` vars).

---

## 8. Migration / cutover / rollback

Sequence (documented in RUNBOOK):
1. `terraform apply` with `enable_opensearch=true` → collection/domain + policies + IAM.
2. Deploy the backend with the new config (still `catalog.search.backend=inapp`).
3. Run reindex: `catalog.reindex.enabled=true` (one-off) → index built from DynamoDB.
4. Verify parity (section 9) against the OpenSearch backend in a non-default env or with the
   flag flipped in a controlled window.
5. Cutover: set `catalog.search.backend=opensearch`.
6. **Rollback:** set `catalog.search.backend=inapp` — single config change, no data migration
   (Requirement 7.2), valid while the catalog is within the in-app ceiling.

---

## 9. Testing strategy

- **Unit — query translation:** given `CatalogSearchQuery` + mode/semantic flags, assert the
  built OpenSearch request has the expected keyword clause, knn clause, dietary `terms` filter,
  and pagination (`from`/`size`). Mock the OpenSearch client.
- **Unit — response mapping:** OpenSearch hits → `CatalogRecipeDto` list + `totalMatches`;
  `findById` present/absent → DTO / empty.
- **Unit — semantic fallback:** `EmbeddingService.embed` throws → keyword-only request still
  issued, results returned (Requirement 2.5).
- **Config selection:** `CatalogSearchConfig` returns the OpenSearch bean when
  `backend=opensearch` and in-app otherwise; existing `CatalogControllerTest` continues to
  pass unchanged (proves Requirement 1.5).
- **Reindex:** idempotent upsert by `catalogRecipeId`; no Bedrock calls for recipe vectors;
  progress/summary logged. (Mock the OpenSearch bulk client and the repository.)
- **Integration (optional, gated):** against a local OpenSearch container or a real
  collection, index a few recipes and assert keyword/semantic/dietary/pagination behavior.
- Follow existing backend test conventions (jqwik/Spring test). Frontend needs no new tests
  (unchanged).

---

## 9a. Future extension: private user recipes (design-fit only, NOT built here)

A later feature will let users create private recipes and search them alongside the public
catalog. OpenSearch handles this "shared + per-owner-private" pattern natively; the backend
built here is deliberately compatible with it. Captured so nothing done now blocks it:

- **Single index + owner scope (recommended):** add an `ownerScope` field per document —
  `"public"` for catalog recipes, or a `userId` for a private recipe. A user's search adds a
  `filter`: `ownerScope IN ("public", <requesting userId>)`. Results from the public catalog
  and the user's own recipes are blended and ranked in one query.
- **Security:** the owner filter is applied server-side (from the JWT `sub`), never from the
  client, so a user can never retrieve another user's private recipes.
- **Indexing on save:** when a user saves a private recipe, embed it with the existing
  `EmbeddingService.embed` (one synchronous call — no batch needed for a single recipe), tag
  it, and index it with `ownerScope=<userId>`.
- **No seam change:** this extends the query (owner filter) and ingestion (index-on-save), not
  the `CatalogSearchService` interface. `CatalogSearchQuery` would gain an optional owner/scope
  parameter; existing callers are unaffected.
- **Alternative (two indexes):** a separate private index queried in parallel and merged — more
  moving parts, only warranted if mappings/lifecycles diverge. Single-index is the default.

Implication for this spec: the index mapping (§3) may reserve an `ownerScope` keyword field now
(cheap, unused until the feature ships) so the future index needs no re-mapping. This is
optional and flagged as such.

## 10. What is explicitly NOT changing

- `CatalogSearchService` interface, `CatalogSearchQuery`, `CatalogSearchResults`,
  `CatalogRecipeDto`, `CatalogController`.
- The frontend (`/browse` pages, proxy, dietary chips).
- `InAppCatalogSearchService` (remains a valid, config-selectable fallback).
- The ingestion pipeline (`CatalogIngestionRunner`, parsers, `DietaryTagger`,
  `EmbeddingService`, `SynchronousEmbeddingStrategy`), except that its output now also feeds
  the reindex.
- AI generation (`BedrockService`), saved-recipe `Recipe` model/table, dietary endpoints.
- The `CatalogRecipe` DynamoDB schema (system of record).

---

## 11. Decisions and remaining open questions

Confirmed with owner:
1. **Flavor:** OpenSearch Serverless NextGen (scale-to-zero), per §2.
2. **Startup behavior:** fail-fast when `catalog.search.backend=opensearch` but the endpoint
   is missing/unreachable, per §5.3. Rationale: a silent fall-back to in-app could serve a
   stale/partial catalog after a botched production cutover without anyone noticing; fail-fast
   surfaces the misconfig immediately. Rollback stays a one-line config change
   (`catalog.search.backend=inapp`).

Dataset status: the full **RecipeNLG dataset is downloaded** at
`backend/data/recipeNGL/RecipeNLG_dataset.csv` (~2.23 GB, ~2,231,149 rows). Columns match the
existing `RecipeNlgCsvSource` parser (title / ingredients / directions / link / source, JSON
array fields). The parser streams row-by-row with a `maxRecords` cap; for the full 2.2M
OpenSearch target that cap is lifted and embeddings are produced via the batch path (§6), not
the synchronous per-request loop.

Remaining open questions:
3. **Scale target for first cutover:** validate the migration at the current ~1.3K catalog
   first (fast, cheap, proves parity), then run the full 2.2M ingestion + reindex? Recommended:
   yes — cut over small, then scale up.
4. **Hybrid implementation:** OpenSearch hybrid search pipeline vs. `bool.should` score blend —
   confirmed during implementation against the NextGen-supported engine version.
