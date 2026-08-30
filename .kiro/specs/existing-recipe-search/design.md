# Design — Look for Existing Recipes

## 1. Goals and constraints

- Add a searchable catalog of pre-made recipes with a new "Look for Existing Recipes" tab.
- Keyword search now; semantic search via Bedrock Titan Text Embeddings V2.
- Reuse the existing `DietaryRestriction` enum and per-user `dietaryRestrictions`.
- **In-app search** (runs on existing backend compute) as the default; **OpenSearch as a
  future swap** behind a `CatalogSearchService` interface selected by configuration.
- Do not touch the AI generation flow, the saved-recipe (`Recipe`) tables, or the dietary
  endpoints' behavior.

This design deliberately separates three concerns so the OpenSearch upgrade is a drop-in:
1. **Data** (catalog schema + ingestion) — implementation-neutral; produces tags + vectors.
2. **Search** (`CatalogSearchService`) — one interface, swappable implementation.
3. **API + UI** — depend only on the interface and DTOs, never on the backing store.

---

## 2. High-level architecture

```
                         ┌─────────────────────────────────────────┐
   Offline / one-off     │  Catalog Ingestion (CommandLineRunner or  │
   (not on every start)  │  standalone profile)                      │
                         │   dataset → normalize → dietary-tag →     │
                         │   Bedrock embed → persist CatalogRecipe   │
                         └───────────────────┬───────────────────────┘
                                             │ writes
                                             ▼
                         ┌─────────────────────────────────────────┐
                         │  Catalog store (DynamoDB table            │
                         │  recipe-ai-dev-catalog): text + dietary   │
                         │  Tags + embedding vector + attribution    │
                         └───────────────────┬───────────────────────┘
                                             │ loaded/queried
                                             ▼
  Frontend                Backend
  ┌────────────────┐      ┌──────────────────────────────────────────┐
  │ /browse tab    │─────▶│ CatalogController  (/api/catalog/*)        │
  │ search + diet  │◀─────│   ├─ resolves user dietaryRestrictions     │
  │ filters + list │      │   └─ delegates to CatalogSearchService     │
  └────────────────┘      │                                            │
                          │ CatalogSearchService (interface)           │
                          │   ├─ InAppCatalogSearchService  (default)  │
                          │   │     keyword + cosine(vector) in JVM    │
                          │   └─ OpenSearchCatalogSearchService (later)│
                          │                                            │
                          │ EmbeddingService (Bedrock Titan V2)        │
                          │   embeds queries at runtime                │
                          └──────────────────────────────────────────┘
```

---

## 3. Data model

### 3.1 `CatalogRecipe` (new `@DynamoDbBean`)

A shared, read-only catalog record. Separate table from saved `Recipe` because access
patterns differ (shared vs per-user) and we don't want to pollute the saved-recipe table.

| Field | Type | Notes |
|---|---|---|
| `catalogRecipeId` | `String` (PK) | Deterministic id derived from source (idempotent ingest) |
| `title` | `String` | |
| `description` | `String` | |
| `ingredients` | `List<String>` | |
| `steps` | `List<String>` | |
| `dietaryTags` | `List<String>` | Subset of `DietaryRestriction` names the recipe satisfies |
| `searchText` | `String` | Precomputed lowercase title+desc+ingredients for keyword match |
| `embedding` | `List<Double>` (or `float[]`) | Titan V2 vector (1,024 dims) |
| `sourceName` | `String` | Dataset name (attribution) |
| `sourceUrl` | `String` | Attribution link if applicable |
| `sourceLicense` | `String` | License identifier |
| `ingestedAt` | `Instant` | |

Notes:
- PK is a deterministic hash of the source identifier so re-ingesting the same recipe
  overwrites rather than duplicates (Requirement 6.4).
- `embedding` and `dietaryTags` are persisted so a future OpenSearch implementation can
  reindex from the same table without recomputation (Requirement 7.5).
- DynamoDB item size limit is 400 KB. A 1,024-dim vector as doubles is well under that,
  but if we later expand fields, large vectors may move to S3. For now, in-table is fine.

### 3.2 Config / tables

- New property `dynamodb.catalog-table` (dev: `recipe-ai-dev-catalog`), mirroring the
  existing `dynamodb.recipes-table` convention.
- New property block for search/embedding config (section 8).

---

## 4. `CatalogSearchService` interface (the swap seam)

```java
public interface CatalogSearchService {

    CatalogSearchResults search(CatalogSearchQuery query);

    Optional<CatalogRecipeDto> findById(String catalogRecipeId);
}
```

Supporting types (implementation-neutral):

```java
public record CatalogSearchQuery(
        String text,                 // may be null/blank => browse
        List<String> dietaryTags,    // effective restrictions to enforce (from user, overridable)
        int page,                    // 0-based
        int pageSize                 // bounded
) {}

public record CatalogSearchResults(
        List<CatalogRecipeDto> items,
        int page,
        int pageSize,
        long totalMatches
) {}
```

Why this shape:
- `dietaryTags` are passed *in* by the controller (already resolved from the user +
  any per-search UI overrides), so the search implementation stays free of auth/user
  concerns and is trivial to reimplement on OpenSearch.
- Pagination is in the contract so neither the API nor the UI changes when the backing
  store changes.

### 4.1 `InAppCatalogSearchService` (default implementation)

- On startup (or lazily/cached), loads the catalog from the `catalog` DynamoDB table into
  an in-memory list. At current scale (thousands–tens of thousands of records) this is a
  few MB and searches in microseconds–milliseconds.
- **Dietary filter:** keep only recipes whose `dietaryTags` contain every tag in
  `query.dietaryTags()`.
- **Keyword ranking:** tokenize the query; score by term matches in `searchText`
  (title weighted higher than ingredients/description). Simple TF-style scoring is
  sufficient at this scale.
- **Semantic ranking:** if enabled and the query is non-blank, embed the query via
  `EmbeddingService`, compute cosine similarity against each candidate's `embedding`,
  and rank by similarity. If embedding fails, fall back to keyword ranking
  (Requirement 3.3).
- **Combined mode (configurable):** blend keyword score and cosine similarity
  (weighted sum) so exact-term hits and semantic hits both surface (Requirement 3.5).
- Applies pagination after ranking.
- Cache invalidation: since the catalog is essentially static and rebuilt via ingestion,
  a simple app-lifetime cache with a manual/refresh endpoint (or app restart) is enough.
  A TTL refresh can be added later if needed.

### 4.2 `OpenSearchCatalogSearchService` (future, not built now)

Documented so the interface is proven swappable:
- Same interface. Query becomes an OpenSearch bool query: `must` = keyword `multi_match`
  and/or `knn` on the embedding vector; `filter` = `terms` on `dietaryTags`; pagination
  via `from`/`size`.
- Reindexes from the same `CatalogRecipe` fields (tags + embedding already persisted).
- Selected by config; no controller/DTO/frontend changes required (Requirement 7.4).

### 4.3 Selecting the implementation

```java
@Bean
public CatalogSearchService catalogSearchService(
        @Value("${catalog.search.backend:inapp}") String backend,
        ObjectProvider<InAppCatalogSearchService> inApp,
        ObjectProvider<OpenSearchCatalogSearchService> openSearch) {
    return "opensearch".equalsIgnoreCase(backend)
            ? openSearch.getObject()
            : inApp.getObject();
}
```

Default `catalog.search.backend=inapp`. Setting it to `opensearch` (once that bean exists)
flips the backend with zero code changes elsewhere (Requirement 7.3).

---

## 5. Embeddings (Bedrock Titan Text Embeddings V2)

New `EmbeddingService`, sibling to the existing `BedrockService`, reusing the already-wired
`BedrockRuntimeClient`.

```java
@Service
@RequiredArgsConstructor
public class EmbeddingService {
    private final BedrockRuntimeClient bedrockRuntimeClient;
    // modelId: amazon.titan-embed-text-v2:0

    public float[] embed(String text) { /* invokeModel, parse "embedding" array */ }
}
```

- Request body: `{"inputText": "..."}` (optionally `dimensions`, `normalize`), matching the
  Titan V2 invoke schema; response contains an `embedding` array.
- Follows the existing `BedrockService` conventions: `InvokeModelRequest` with
  `contentType/accept = application/json`, Jackson parsing, a small retry loop.
- **Ingestion time:** embed each recipe's combined text once, persist to `CatalogRecipe`.
- **Query time:** embed the user query (only when semantic search enabled). On failure,
  log and fall back to keyword search (Requirement 3.3).
- Cost: ~$0.00002 / 1K tokens → ~$1 one-time for a large catalog, pennies/month for
  queries.

`BedrockModel` enum is for text-generation models and is left untouched; the embedding
model id lives as a constant/config in `EmbeddingService` to avoid conflating the two.

### 5.1 Embedding strategy (synchronous now, batch later)

Embeddings are throttled by **requests-per-minute (RPM)**, not tokens, so bulk ingestion is
about pacing request count. The pipeline treats "produce embeddings" as a strategy behind a
small interface so the ingestion flow is identical regardless of scale:

```java
public interface EmbeddingStrategy {
    // returns vectors aligned to the input order; used by ingestion
    List<float[]> embedBatch(List<String> texts);
}
```

- **`SynchronousEmbeddingStrategy` (BUILD NOW — serves ≤ ~50K):** paced `invokeModel`
  loop (optionally a small concurrency pool) with:
  - an RPM rate limiter set below the account quota,
  - exponential backoff on `ThrottlingException`/429 (reusing the `BedrockService` retry
    style, tuned for batch: more attempts, longer backoff),
  - **per-recipe persist + skip-if-already-embedded** so a long run is resumable and
    re-runs never re-embed (works with the deterministic `catalogRecipeId`).
  - Time: ~50 recipes = seconds; 15K ≈ ~1 hr; 50K ≈ ~3 hr at conservative RPM. Cost: cents.
- **`BatchEmbeddingStrategy` (FUTURE — for ~2.2M):** Amazon Bedrock **Batch Inference** —
  write inputs as JSONL to S3, submit an async job, read vectors from the S3 output.
  ~50% cheaper than on-demand (~$11 one-time for 2.2M), no per-request RPM babysitting,
  built for bulk. Not built now; scaffolded as a future strategy.

The query-time single-query embedding always uses the synchronous path (one fast call) and
is unaffected by which bulk strategy ingestion used. Both strategies produce identical
1,024-dim vectors stored the same way, so switching is a config/selection change, not a
pipeline change.

---

## 6. Ingestion pipeline

A standalone process, not part of normal request handling and not run on every boot
(Requirement 6.2). Options, in order of preference:

- A Spring `CommandLineRunner` guarded by a dedicated profile (e.g. `--spring.profiles.active=ingest`)
  or a CLI arg, so it runs only when explicitly invoked.
- Reads the dataset from a local file (or S3), which keeps the ingest environment simple.

Pipeline stages per recipe:
1. **Parse** the raw dataset row/record.
2. **Normalize** into `CatalogRecipe` fields (title, description, ingredients, steps,
   `searchText`).
3. **Dietary-tag** using a deterministic rule set (section 6.1).
4. **Embed** the combined text via `EmbeddingService`.
5. **Persist** to the catalog table using a deterministic `catalogRecipeId`
   (idempotent — Requirement 6.4).
6. **Record attribution** (`sourceName`, `sourceUrl`, `sourceLicense`).

Data source path (phased — see section 6.2 for the scaling rationale):
- **Phase 1 — Prototype (~300 recipes):** TheMealDB (tiny, clean, permissive) to validate
  the whole pipeline end-to-end (parse → tag → embed → persist → search → UI).
- **Phase 2 — In-app volume (10K–50K recipes):** a **subset** of RecipeNLG
  (non-commercial OK for this app) once the pipeline is proven. This is the intended
  steady state for the in-app backend.
- Batch/throttle Bedrock calls during ingestion and log progress; ingestion is offline so
  latency is not user-facing.
- The parser is structured to accept both sources (a `RecipeSource` abstraction) so moving
  from TheMealDB to a RecipeNLG subset is a new parser, not a pipeline rewrite.

### 6.2 Dataset size, in-app memory ceiling, and OpenSearch trigger

The in-app backend holds the catalog (text + embedding vectors) in JVM memory, so dataset
size has a hard practical ceiling. Sizing (1,024-dim float vectors ≈ 4 KB each, plus text):

| Catalog size | Vector footprint | In-app fit | Embedding approach | Notes |
|---|---|---|---|---|
| ~300 (TheMealDB) | ~1 MB | Comfortable | Synchronous | Phase 1 prototype |
| 10K | ~40 MB | Comfortable | Synchronous | Good in-app target |
| 50K | ~200 MB | Workable | Synchronous (paced, resumable) | Upper bound for in-app |
| ~2.2M (full RecipeNLG) | ~9 GB raw (13–15 GB+ with ANN graph) | **Does not fit** | **Batch Inference** (~$11) | Requires OpenSearch |

**Decision rule baked into the design:**
- Keep the in-app catalog at **≤ ~50K recipes**. Ingest a RecipeNLG subset, do not load the
  full 2.2M into the in-app backend.
- The **full 2.2M dataset is the trigger to switch to the OpenSearch backend** — and at
  that size a memory-optimized managed domain (r-family, 16 GB+ RAM) or serverless with
  sufficient warm OCUs is required, because k-NN vector search wants the ANN graph resident
  in memory. A smallest t3.small managed node (~$25–30/mo, ~2 GB RAM) can NOT hold a 2.2M
  vector index and is not a valid target for the full dataset.

Because ingestion persists `dietaryTags` and `embedding` in DynamoDB, switching backends
later reindexes from the same data with no re-embedding and no schema change
(Requirement 7.5).

Cost/time note by size (embedding ~$0.00002/1K tokens on-demand; see section 5.1 for the
strategy interface): ~300 recipes ≈ pennies and minutes (synchronous); 10K–50K ≈ cents and
a paced, resumable synchronous run (~1–3 hr); full 2.2M ≈ ~$11 one-time via Bedrock **Batch
Inference** (async, ~50% cheaper than the ~$22 on-demand, no RPM babysitting). The 2.2M
batch path is another reason the full set is a deliberate, separate decision, not the
default.

### 6.3 Dietary tagging rules (baseline)

Deterministic ingredient-keyword matching mapped to `DietaryRestriction`:
- Maintain per-restriction disqualifying keyword lists (e.g. dairy: milk, butter, cheese,
  cream, yogurt → not `DAIRY_FREE`; meat/fish → not `VEGETARIAN`/`VEGAN`; also eggs/honey
  for `VEGAN`; gluten-bearing grains → not `GLUTEN_FREE`; etc.).
- A recipe gets a tag if none of that restriction's disqualifiers appear in its ingredients.
- This is intentionally conservative and documented; ambiguous cases can later be refined
  (optionally by an LLM classification pass) without changing the schema (Requirement 6.3).
- Tagging happens once at ingestion; querying is a pure tag match (Requirement 4.5).

---

## 7. API design

New `CatalogController` at `/api/catalog`, following existing controller conventions
(JWT `sub` for userId, jakarta validation, `@Pattern` on path ids, `@Validated`).

### `GET /api/catalog/search`
Query params:
- `q` (optional string, bounded length) — search text; blank => browse.
- `tags` (optional, repeated) — dietary tag overrides for this search. If omitted, the
  controller uses the user's stored `dietaryRestrictions`.
- `page` (default 0), `pageSize` (default e.g. 20, capped).

Behavior:
1. Resolve userId from JWT.
2. Determine effective dietary tags: request `tags` if provided (validated against
   `DietaryRestriction`), else the user's saved `dietaryRestrictions`
   (Requirements 4.1, 4.3).
3. Build `CatalogSearchQuery` and call `catalogSearchService.search(...)`.
4. Return `CatalogSearchResults` (items + pagination metadata).

Response item shape: `CatalogRecipeDto` (Lombok `@Data @Builder`, mirroring `RecipeDto`
style) with `catalogRecipeId`, `title`, `description`, `ingredients`, `steps`,
`dietaryTags`, and attribution fields. The `embedding` and internal `searchText` are
**not** exposed.

### `GET /api/catalog/{id}`
- Validates id with a `@Pattern`, returns the `CatalogRecipeDto` or 404 (Requirement 5.3).

### Notes
- No new consent gate is required for browsing a static catalog; the AI generation consent
  logic is untouched. (If product later wants a consent check, it slots into the controller
  the same way generation does.)
- Reuse existing exception handling (`ResourceNotFoundException`,
  `GlobalExceptionHandler`).

---

## 8. Configuration

New properties (env-overridable, matching existing `application.properties` style):

```properties
dynamodb.catalog-table=${CATALOG_TABLE:recipe-ai-dev-catalog}

catalog.search.backend=${CATALOG_SEARCH_BACKEND:inapp}   # inapp | opensearch
catalog.search.semantic-enabled=${CATALOG_SEMANTIC:true} # embeds queries at runtime
catalog.search.mode=${CATALOG_SEARCH_MODE:hybrid}        # keyword | semantic | hybrid
catalog.search.page-size-default=20
catalog.search.page-size-max=50

bedrock.embedding.model-id=amazon.titan-embed-text-v2:0
```

Turning `semantic-enabled=false` makes search keyword-only and avoids all runtime Bedrock
calls (Requirement 3.4). `backend=opensearch` is inert until that implementation exists.

---

## 9. Frontend

Framework note: this repo pins a Next.js version with breaking changes vs. common
knowledge (`frontend/AGENTS.md`). Before writing frontend code, consult
`node_modules/next/dist/docs/` for the current App Router / data-fetching conventions.

Changes:
- **Nav tab:** add `{ href: "/browse", label: "Look for Existing Recipes" }` to
  `NAV_LINKS` in `components/layout/Header.tsx`.
- **Page:** new `app/(protected)/browse/page.tsx` with:
  - a search input (debounced submit),
  - dietary-restriction filter chips built from `lib/dietary.ts`, **defaulted to the
    user's saved restrictions**, toggleable per-search (Requirement 4.3),
  - a paginated results list, and empty/no-results/loading states.
- **Detail:** `app/(protected)/browse/[id]/page.tsx` showing full recipe + attribution
  (Requirement 5).
- **API calls:** use existing `lib/api.ts` `apiFetch` (injects `Authorization` bearer) to
  hit `/api/catalog/search` and `/api/catalog/{id}`.
- **Reuse:** `lib/dietary.ts` for labels/options; no new restriction vocabulary
  (Requirement 4.4).

The AI generation tab/pages are untouched.

---

## 10. Testing strategy

- **Unit — dietary tagging:** given ingredient lists, assert correct `dietaryTags`
  (positive and disqualifier cases per restriction).
- **Unit — InAppCatalogSearchService:** keyword ranking order; dietary filtering excludes
  non-matching recipes; pagination bounds; blank query => browse; empty results path.
- **Unit — semantic fallback:** when `EmbeddingService.embed` throws, search still returns
  keyword-ranked results (Requirement 3.3).
- **Unit — EmbeddingService:** request body shape and response parsing (mock
  `BedrockRuntimeClient`).
- **Controller/web-layer tests:** effective-tags resolution (request override vs user
  saved), validation of `q`/`tags`/pagination, 404 on unknown id.
- **Ingestion:** idempotency (re-ingest same source => no duplicates); attribution fields
  populated.
- Follow existing backend test conventions (the project already uses jqwik/Spring test per
  `.jqwik-database` and pom). Frontend: Vitest per existing setup.

---

## 11. What is explicitly NOT changing

- AI generation (`BedrockService`, `RecipeController` generate/save, consent checks).
- Saved-recipe `Recipe` model/table and `RecipeService`.
- Dietary-restriction endpoints in `AccountController` and the `User` model.
- `BedrockModel` enum.

---

## 12. Future OpenSearch upgrade (reference, not in scope now)

Steps to switch backends (no API/UI/DTO changes required):
1. Add the AWS OpenSearch module + an `OpenSearchConfig` client bean.
2. Add `OpenSearchCatalogSearchService implements CatalogSearchService` (bool query +
   `terms` dietary filter + `knn` on the persisted embedding).
3. Reindex from the existing `CatalogRecipe` table (tags + vectors already present — no
   re-embedding needed).
4. Set `catalog.search.backend=opensearch`.

### 12.1 Choosing the OpenSearch flavor (with researched numbers, us-east-1, ~Aug 2026)

Rates are published examples; confirm live on the AWS OpenSearch pricing page before
committing. Sources: AWS OpenSearch pricing, scale-to-zero, and instance-sizing docs.

| Backend | Handles full 2.2M + semantic? | ~Cost/month | Cold start | Fit |
|---|---|---|---|---|
| In-app (default) | No (~9 GB won't fit in JVM) | $0 extra | none | ≤ ~50K recipes |
| Managed t3.small (~2 GB RAM) | No (RAM too small for vector index) | ~$25–30 | none | small catalog demo / keyword |
| Managed r-family (16 GB+ RAM) | Yes | ~$130+ | none | full dataset, steady traffic |
| Serverless NextGen (min OCU 0) | Yes | tens $ storage + warm compute | 10–30s+ from idle | bursty; large idle gaps |

Key operational facts for NextGen serverless scale-to-zero:
- Scales to 0 OCU after **10 minutes** of no requests (not configurable); compute billing
  stops while idle.
- First request after idle incurs **10–30 s** cold-start latency (higher for a large vector
  index); search and indexing wake independently.
- Always set a max-OCU cap (`maxSearchCapacityInOCU`) to bound spend.

**Guidance:**
- Bursty traffic with long idle gaps → NextGen (cheap idle, accept cold starts).
- Steady traffic or a large always-resident vector index → managed r-family (no cold
  starts; a large k-NN graph wants to stay warm anyway, which erodes NextGen's benefit).
- A t3.small managed node is only viable for a small subset / keyword-first demo, NOT the
  full 2.2M dataset.
- The full 2.2M RecipeNLG dataset is the point at which one of the OpenSearch backends
  becomes necessary; the in-app backend is intentionally capped at a ≤50K subset.
