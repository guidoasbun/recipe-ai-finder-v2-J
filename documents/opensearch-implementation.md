# OpenSearch Catalog Search — Implementation & Post‑Mortem

A comprehensive record of how catalog search was migrated from an in‑app backend to
**Amazon OpenSearch Serverless**, how the full ~2.23M‑recipe dataset was loaded, and — most
importantly — **every mistake, wrong assumption, and hard‑won fix** along the way.

This document is written to be read later, without the surrounding chat context. It is both a
design reference and a learning log.

---

## 1. Purpose & goals

The app lets users search a large public recipe catalog (the RecipeNLG dataset, ~2.23M recipes)
by **keyword**, by **meaning** (semantic / vector search over embeddings), and with **dietary
filters** (vegan, gluten‑free, etc.), with pagination.

The original catalog search ran **in‑app**: the whole catalog was scanned into memory and
searched in Java. That is fine for a small (~1.3K–50K) catalog but cannot scale to millions of
recipes or do efficient vector similarity. The goal of this work:

- Move catalog search to **OpenSearch** behind an existing `CatalogSearchService` seam, so the
  rest of the app is untouched and the in‑app backend stays as a config‑selectable fallback.
- Support **hybrid search**: keyword (`multi_match`) + semantic (k‑NN over 1024‑dim Titan V2
  embeddings), plus dietary `term` filters.
- Load the **entire 2.23M dataset** with embeddings, at bounded cost, without OOM.
- Keep a **fast rollback** (flip a config flag back to the in‑app backend).

Key constraint that shaped everything: we chose **OpenSearch Serverless** (`aoss`,
`VECTORSEARCH` collection type) for scale‑to‑zero economics. Serverless removes a lot of ops
burden but imposes a **subset** of the OpenSearch API — and that subset is exactly where most of
our pain came from.

---

## 2. Architecture

```
                        ┌─────────────────────────────────────────────┐
                        │                Backend (Spring)              │
                        │                                              │
   HTTP  ──▶ CatalogController ──▶ CatalogSearchService (seam)         │
                        │            ├── InAppCatalogSearchService     │  (fallback, default)
                        │            └── OpenSearchCatalogSearchService │  (backend=opensearch)
                        │                        │                     │
                        └────────────────────────┼─────────────────────┘
                                                 │ SigV4 (opensearch-java + AwsSdk2Transport)
                                                 ▼
                              ┌──────────────────────────────────┐
                              │   OpenSearch Serverless (aoss)     │
                              │   collection: VECTORSEARCH         │
                              │   index: catalog-recipes           │
                              │   - text: title/description/ingr.  │
                              │   - keyword: catalogRecipeId, tags │
                              │   - knn_vector embedding (1024,fp16)│
                              └──────────────────────────────────┘
                                                 ▲
                                                 │ bulk index (no re-embedding)
                                                 │
   ┌────────────────────────┐   embeddings   ┌───────────────────────────┐
   │  RecipeNLG CSV (2.23M)  │ ─────────────▶ │  DynamoDB recipe-ai-dev-   │
   │  local file, streamed   │  Bedrock Batch │  catalog-full (source of   │
   └────────────────────────┘  (Titan V2)    │  truth: text + embedding)  │
                                              └───────────────────────────┘
```

### Data flow (three stages)

1. **Ingest** (`CatalogIngestionRunner` + `BatchEmbeddingStrategy`): stream the RecipeNLG CSV →
   Bedrock **Batch Inference** to embed (Titan Text V2, 1024‑dim) → persist each recipe (text +
   attribution + embedding) into the DynamoDB `catalog-full` table. DynamoDB is the durable
   **source of truth**.
2. **Reindex** (`CatalogReindexRunner`): read recipes (incl. persisted embeddings) from DynamoDB
   and **bulk‑index** them into OpenSearch. **No Bedrock here** — vectors are read from DynamoDB,
   never recomputed, so reindexing is cheap and repeatable.
3. **Serve** (`OpenSearchCatalogSearchService`): translate a search request into an OpenSearch
   query (keyword `multi_match`, k‑NN on `embedding`, dietary `term` filters, `from`/`size`
   pagination) and map hits back to DTOs.

### Two DynamoDB tables (rollback preservation)

- `catalog-table` — the small in‑app catalog (fits in memory), keeps the **fast rollback** valid.
- `catalog-full` — the full 2.23M dataset. Loading it never touches the small table.
  `CatalogRecipeRepository.forTable(name)` returns a repo bound to a chosen table.

### Index mapping (design highlights)

- `title` (`text`, with a `kw` keyword sub‑field), `description`/`ingredients` (`text`).
- `steps` and attribution URLs: stored but **not indexed**.
- `catalogRecipeId`, `dietaryTags`, `sourceName`, `sourceCountry`, `ownerScope`: **keyword**.
- `embedding`: `knn_vector`, dimension **1024**, Faiss HNSW, cosine, with **fp16** scalar
  quantization for the full‑scale load (roughly half the vector memory vs. float).

---

## 3. Key components (with code)

### 3.1 The search seam

`CatalogSearchConfig` picks the backend by config; the OpenSearch bean is conditional so the
default (in‑app) deployment neither needs an endpoint nor loads the client:

```java
// active only when catalog.search.backend=opensearch
@ConditionalOnProperty(name = "catalog.search.backend", havingValue = "opensearch")
public class OpenSearchConfig { /* builds the SigV4 opensearch-java client */ }
```

### 3.2 Index op — the serverless `_id` rule (see Mistake #3)

```java
private BulkOperation indexOp(String index, CatalogRecipe recipe) {
    recipe.setSearchText(null); // don't index the pre-concatenated duplicate field
    if (serverless) {
        // aoss REJECTS a custom _id; it auto-generates one. Store catalogRecipeId as a field.
        return BulkOperation.of(op -> op.index(idx -> idx.index(index).document(recipe)));
    }
    // managed domains (es) CAN use catalogRecipeId as _id (enables upsert)
    return BulkOperation.of(op -> op.index(idx -> idx
            .index(index).id(recipe.getCatalogRecipeId()).document(recipe)));
}
```

### 3.3 Bulk flush with retry — the throttle fix (see Mistake #1 & #2)

```java
// maxAttempts + backoffCapMillis are tuned per phase (reindex: 6/3s; backfill: 12/30s)
for (int attempt = 1; attempt <= maxAttempts; attempt++) {
    try {
        BulkResponse response = client.bulk(...);
        if (!response.errors()) { counters.addIndexed(pending.size()); break; }
        // re-collect per-item failures by position for retry
        ...
    } catch (Exception e) {                       // <-- MUST be Exception, not IOException
        // 429/503 throttling arrives as OpenSearchException (a RuntimeException).
        // If we only caught IOException, a whole-request 429 escaped: not retried,
        // not recorded -> silently dropped docs. This single line was the biggest bug.
        if (attempt == maxAttempts) { recordFailed(pending, counters, failedIds); break; }
        retry = pending;
    }
    Thread.sleep(Math.min(backoffCapMillis, 200L * (1L << Math.min(attempt - 1, 20))));
    pending = retry;
}
```

### 3.4 Reconciliation — find what's missing without a reliable failure log (see Mistake #5–#8)

Because serverless cannot upsert, recovery may only index **truly absent** docs. We derive the
missing set from ground truth: pull every indexed id, diff against DynamoDB.

```java
// Pull all indexed catalogRecipeIds via PIT + search_after (the serverless deep-paging path).
private Set<String> fetchIndexedIds() {
    String pitId = createPit();                    // POST /{index}/_search/point_in_time?keep_alive=5m
    List<FieldValue> searchAfter = null;
    while (true) {
        var resp = client.search(b -> b.size(2000)
            .pit(Pit.of(p -> p.id(pitId).keepAlive("5m")))   // NOTE: with a PIT, do NOT set .index()
            .source(s -> s.filter(f -> f.includes("catalogRecipeId")))  // ids only, no vectors
            .query(q -> q.matchAll(m -> m))
            .sort(catalogRecipeId ASC)             // primary
            .sort(_id ASC), CatalogRecipe.class);  // tiebreaker -> strict total order (Mistake #7)
        // collect ids; advance searchAfter to last hit's sort(); recreate PIT on failure
    }
}
```

```java
// Diff via a PROJECTED, PARALLEL DynamoDB scan (Mistake #6), then load full recipes for the gap.
repository.scanIdsInPagesParallel(8, page -> { /* keep ids not in `indexed` */ });
// point-read the full recipe (with embedding) ONLY for the ~missing ids, in parallel (16 threads)
```

### 3.5 The AWS client timeouts — the real root cause of the hangs (see Mistake #9)

```java
// DynamoDbConfig — WITHOUT these, a stuck TCP read hangs the scan forever.
DynamoDbClient.builder()
    .httpClientBuilder(AwsCrtHttpClient.builder()
        .connectionTimeout(Duration.ofSeconds(10)).maxConcurrency(64))
    .overrideConfiguration(ClientOverrideConfiguration.builder()
        .apiCallAttemptTimeout(Duration.ofSeconds(30))  // per-attempt: abandon a wedged read
        .apiCallTimeout(Duration.ofSeconds(120))        // whole-operation ceiling
        .retryPolicy(RetryPolicy.builder().numRetries(8).build())
        .build())
    .build();
```

### 3.6 Operator scripts

- `scripts/run-full-catalog-ingest.sh` — full dataset ingest (Bedrock batch → DynamoDB).
- `scripts/run-full-catalog-reindex.sh` — **self‑healing**: recreate index → bulk load all 2.23M →
  auto‑backfill any failed ids → **verify count**. Fails loudly if the index ends up short.
- `scripts/run-catalog-backfill.sh` — index only missing recipes; reconciles via PIT if no
  failed‑ids file is given.
- `scripts/probe-pit-support.sh` — 5‑second yes/no that PIT create + a page read work (run before
  a long reconcile).

All scripts self‑caffeinate (`caffeinate -ims`) so a laptop won't sleep mid‑run.

---

## 4. The hard part: mistakes, assumptions, and fixes

This is the important section. The ingest and the code scaffolding went smoothly. **Reindexing
2.23M docs into OpenSearch Serverless is where nearly every problem lived.** Each item below is a
real thing that went wrong, why, and how it was fixed. The git history
(`3e51096`→`431a9b5`) is the blow‑by‑blow timeline.

### Mistake #1 — Assuming bulk indexing would "just work" at scale

**Assumption:** a bulk reindex at concurrency 8 would stream 2.23M docs in fine.

**Reality:** OpenSearch Serverless **auto‑scales indexing capacity (OCUs)** on demand. During a
scale‑up it **throttles** writes, returning `rejected execution of primary operation
[throttled]`. At concurrency 8 this hit around the ~1.06M mark and dropped thousands of docs.

**Why it bit us:** the first version of `flushBatch` had **no retry** at all. Throttled items were
just counted as failed. The completeness guard (`if failed > 0 throw`) then refused to proceed —
which was correct, but it meant every run "failed."

**Fix:** retry server‑rejected items with exponential backoff, and lower write concurrency 8 → 4
to stay under the OCU ceiling. Commit `3e51096`.

**Lesson:** serverless throttling is normal backpressure, not an error — you must retry it, and
you must not hammer it with high concurrency during scale‑up.

### Mistake #2 — Catching the wrong exception type (the single most damaging bug)

**Assumption:** transient failures come as `IOException`, so `catch (IOException e)` in
`flushBatch` covers throttling.

**Reality:** whole‑request throttling arrives as **HTTP 429**, surfaced by the opensearch‑java
client as an `OpenSearchException` — a **`RuntimeException`, not an `IOException`.** So a 429 on an
entire bulk request **escaped `flushBatch` entirely**: it was neither retried nor recorded to the
failed‑ids file. It only got logged by the outer future as "Bulk task failed," and the docs
vanished silently.

**How we caught it:** the arithmetic didn't add up. A run reported "**7,000 failed**," but
`2,231,142 seen − 2,163,142 indexed = 68,000` were actually missing. **61,000 docs were dropped
without ever being recorded.** The failed‑ids file we were about to trust for a backfill was
missing 90% of the real gap.

**Fix:** widen the catch to `catch (Exception e)` so 429/503 on the whole request is retried and,
if it ultimately fails, its ids are recorded. Commit `5d4d322`.

**Lesson:** know exactly how your client library surfaces throttling. Assuming the exception
hierarchy cost us the most. Always cross‑check "reported failures" against "seen − indexed."

### Mistake #3 — Assuming we could upsert by our own id

**Assumption:** re‑running the reindex is idempotent because `catalogRecipeId` is the document id
(upsert semantics), so a rerun just overwrites.

**Reality:** OpenSearch **Serverless auto‑generates `_id`** and **rejects a custom `_id`** at index
time. On a `VECTORSEARCH` collection, `POST _update/<id>` and `PUT _doc/<id>` are **not
supported** (they're "search collection only" per the AWS supported‑ops list). So there is **no
upsert**. Re‑indexing a recipe that's already present creates a **duplicate** document.

**Consequences:** we could not "just rerun to fill gaps." Two safe paths only:
- **Recreate** the index (drop + rebuild from scratch) — clean but ~6 hours.
- **Backfill only truly‑absent docs** — requires knowing exactly what's missing.

**Lesson:** on serverless VECTORSEARCH, design for **append‑only, no upsert**. Any recovery must
avoid touching docs that already exist.

### Mistake #4 — Retrying harder against a saturated resource

**Assumption:** if the first retry logic still dropped items, add more retries / more concurrency.

**Reality:** when the indexing OCUs are saturated, all writer threads throttle **in lockstep** and
just compete for the same exhausted capacity. Retrying *harder* doesn't move the wall; you need to
retry *gentler and longer* (fewer writers, longer backoff), or do the small recovery pass
single‑threaded after the pressure is off.

**Fix:** backfill runs **single‑threaded** with **patient** backoff (12 attempts, up to 30s),
which comfortably outlasts a scale‑up window. Commit `40c9c9b`.

**Lesson:** backpressure is a signal to slow down, not speed up.

### Mistake #5 — Assuming the failure log would always tell us what to backfill

**Assumption:** the reindex writes failed ids to a file, so the backfill just replays that file.

**Reality:** true only if the logging is complete — and Mistake #2 meant it wasn't (7,000 logged
of 68,000 missing). Worse, the run that produced the very first gap used an **older build with no
failure logging at all.**

**Fix:** build a **reconciliation** path that doesn't depend on the log — it compares what's
actually in the index against DynamoDB (ground truth). The failed‑ids file became a fast‑path
optimization; reconciliation is the reliable recovery. Commits `40c9c9b`, `a052ee5`, `3c698d3`.

**Lesson:** for a recovery mechanism, prefer **ground‑truth reconciliation** over trusting your
own (possibly buggy) bookkeeping.

### Mistake #6 — Reconciling with API calls the serverless collection doesn't support

To pull "all indexed ids," we tried three approaches in order, and **two failed on serverless**:

1. **Scroll API** → **HTTP 404.** Serverless does not support scroll. (Assumed it would; it's the
   classic OpenSearch deep‑paging API.)
2. **Large `terms` existence query** (ask "which of these 500/1000 ids exist?") → **HTTP 500
   `internal_server_exception`, consistently.** A big `terms` + `_source` filter is rejected by
   this collection. It *ran* for ~2.5 min then died, which initially looked transient; it wasn't.
3. **PIT (Point‑In‑Time) + `search_after`** → **works.** This is the pagination method AWS
   actually recommends for serverless. Endpoint: `POST /{index}/_search/point_in_time?keep_alive=5m`.

**Extra wrinkle:** my first PIT attempt used the wrong endpoint path (`/{index}/...` vs
`/_search/...`) and got a 404 — which made me wrongly conclude PIT was unsupported too. The typed
opensearch‑java client in our version doesn't expose PIT create/delete, so we call the **raw
generic client** and parse `pit_id`. We now try **both endpoint forms** and use whichever returns
2xx.

**Lesson:** don't assume feature parity with open‑source OpenSearch. Check the **AWS serverless
supported‑operations list** first. Verify an unfamiliar API with a **tiny probe**
(`probe-pit-support.sh`) before committing to a 20‑minute run that depends on it.

### Mistake #7 — `search_after` without a unique tiebreaker

**Assumption:** sorting by `catalogRecipeId` alone is a fine cursor for `search_after`.

**Reality:** `search_after` needs a **strict total order**. If the sort key has any ties or missing
values, paging can **skip or repeat** a boundary — and a skipped id would look "missing" and get
re‑indexed → **duplicate** (Mistake #3 makes this unrecoverable in place). `catalogRecipeId` is a
unique keyword in practice, but there's no reason to gamble.

**Fix:** add `_id` as a secondary sort so the order is always total. Commit `431a9b5`.

**Lesson:** always give `search_after` a unique tiebreaker (usually `_id` or `_shard_doc`).

### Mistake #8 — A completion path that could declare success while short

**Assumption:** if the script finishes without error, the index is complete.

**Reality:** the self‑healing loop trusted the failed‑ids file; with silent drops (Mistake #2) or a
truncated file, it could print "✅ fully indexed" while the index was short. The only real check
was a human eyeballing a count.

**Fix:** add a `verify-count` mode that compares the OpenSearch `_count` to the DynamoDB item
count and **exits non‑zero if short**, wired into the end of the reindex script. Commit `431a9b5`.
This is what ultimately confirmed **2,231,142 == 2,231,142**.

**Lesson:** never let automation declare success without an **independent completeness check**
against ground truth.

### Mistake #9 — No client timeouts → an unkillable hang (the real root cause of the "stuck" runs)

**Assumption:** a DynamoDB scan either progresses or errors.

**Reality:** with **no `apiCallAttemptTimeout`, no socket read timeout, and no retry policy** on the
DynamoDB client, a single **stuck / half‑open TCP connection** during the scan blocked the paginator
**forever**. We observed a scan thread parked in `sun.nio.ch.Net.poll` for **~50 minutes** with
essentially flat CPU. On‑demand billing ruled out capacity throttling — it was a dead socket that
nothing was configured to abandon.

This also compounded two earlier missteps:
- The reconcile scan initially pulled **whole items** (~13 KB each, including the 1024‑dim
  embedding) just to read the id → **~29 GB** serially. Fixed with an **id‑only projected scan**
  (`attributesToProject("catalogRecipeId")`) — commit `afa4b31` — then a **parallel 8‑segment**
  scan with progress logging — commit `b2cf247`. (Important nuance: projection reduces network
  transfer but DynamoDB still reads the full items server‑side, so a serial scan is still slow;
  parallelism is what actually helps.)
- I twice mis‑diagnosed "slow" vs "hung" and told the user to "let it finish" when it was actually
  wedged. The fix was to stop guessing and take a **thread dump** (`jstack`), which showed the
  blocked `Net.poll` and pointed straight at the missing timeout.

**Fix:** add `apiCallAttemptTimeout` (30s), `apiCallTimeout` (120s), and 8 retries to the DynamoDB
client, plus connection/idle timeouts to the OpenSearch client. A wedged attempt now fails fast and
retries on a fresh connection. Commit `431a9b5`.

**Lesson:** **always configure timeouts + bounded retries on network clients**, especially for
long batch jobs that make thousands of calls. One dead connection out of thousands should cost you
seconds, not the whole job. When something is "stuck," take a thread dump before deciding it's
merely slow.

### Mistake #10 — Under‑instrumented long phases looked like hangs

Several phases (the DynamoDB scan, the id reload) had **no progress logging**, so a slow‑but‑working
phase was indistinguishable from a hang, causing premature stops. Fix: log progress every
100k/200k items so every long phase is observable. Commit `b2cf247`.

**Lesson:** a long batch phase with no heartbeat log is operationally the same as a hang. Always
emit periodic progress.

---

## 5. Final resolution — how the gap was actually closed

After all fixes landed, the recovery ran cleanly:

1. **`probe-pit-support.sh`** → `PIT probe SUCCEEDED` (PIT create + a page read work).
2. **`run-catalog-backfill.sh`** (reconciliation mode):
   - PIT + `search_after` pulled all **2,163,142** indexed ids (~15–24 min).
   - Parallel projected DynamoDB scan diffed against 2,231,142 → **68,000 missing**.
   - Parallel point‑reads loaded those 68,000 full recipes.
   - Single‑threaded patient backfill indexed them: **`68000 to do, 68000 indexed, 0 failed`.**
3. **`verify-count`** → **`OpenSearch index has 2231142 doc(s); DynamoDB has 2231142 recipe(s).
   Verify PASSED.`**

Exact match. 0 missing, 0 duplicates.

---

## 6. Serverless "gotchas" cheat‑sheet (for next time)

| Thing | Managed OpenSearch (`es`) | Serverless (`aoss`, VECTORSEARCH) |
|---|---|---|
| Custom document `_id` | Allowed (enables upsert) | **Rejected** — auto‑generated |
| `_update/<id>`, `PUT _doc/<id>` | Supported | **Not supported** on VECTORSEARCH |
| `_delete_by_query` | Supported | **Not supported** |
| Scroll API | Supported | **Not supported (404)** |
| Deep pagination | scroll / PIT / search_after | **PIT + `search_after`** (the supported path) |
| Large `terms` existence query | Fine | **Rejected (500) on this collection** |
| `min_score` on k‑NN | Allowed | Rejected |
| `index.knn` setting | Set explicitly | Omit (managed) |
| Indexing capacity | Fixed cluster | **Auto‑scales OCUs → throttles (429/503) during scale‑up** |
| Throttling exception type | — | `OpenSearchException` (RuntimeException), **not** IOException |

Other durable lessons:
- **Always set AWS client timeouts + retries** (Mistake #9). This is non‑negotiable for batch jobs.
- **Reconcile against ground truth**; don't trust your own failure bookkeeping (Mistake #5).
- **Verify completeness independently** before declaring success (Mistake #8).
- **Probe unfamiliar serverless APIs** with a 5‑second check before a long run (Mistake #6).
- **Give `search_after` a unique tiebreaker** (Mistake #7).
- **Cross‑check `seen − indexed` vs reported failures** to catch silent drops (Mistake #2).
- **Thread‑dump before concluding "hung"** (Mistake #9).
- **Emit progress on every long phase** (Mistake #10).

---

## 7. Config reference (reindex / backfill)

| Property | Default | Purpose |
|---|---|---|
| `catalog.search.backend` | `inapp` | `opensearch` to use this backend; `inapp` for fast rollback |
| `catalog.reindex.enabled` | `false` | Gates the one‑off runner (never runs on normal boot) |
| `catalog.reindex.recreate-index` | `false` | Drop + recreate before reindex (needed for mapping changes / clean rebuild) |
| `catalog.reindex.concurrency` | `8` (script uses `4`) | In‑flight bulk requests; 4 avoids serverless throttle storms |
| `catalog.reindex.batch-size` | `500` (reindex script `1000`) | Docs per bulk request |
| `catalog.reindex.failed-ids-file` | (blank) | Where permanently‑failed `catalogRecipeId`s are written |
| `catalog.reindex.backfill` | `false` | Index only missing docs (never recreates) |
| `catalog.reindex.backfill-ids-file` | (blank) | Ids to backfill; blank → reconcile against the index |
| `catalog.reindex.pit-probe` | `false` | Diagnostic: verify PIT works, then exit |
| `catalog.reindex.verify-count` | `false` | Compare OpenSearch `_count` to DynamoDB; fail if short |
| `opensearch.knn.quantization` | `none` | `fp16` for the full‑scale index (≈ half vector memory) |

## 8. Environment facts (dev)

- AWS account `412381751532`, region `us-east-1`.
- OpenSearch Serverless endpoint: `https://o2dmi7wacuk8u8y9pbm6.us-east-1.aoss.amazonaws.com`,
  index `catalog-recipes`, signing service `aoss`.
- DynamoDB source of truth: `recipe-ai-dev-catalog-full` (PAY_PER_REQUEST, ~28.9 GB, 2,231,142
  items, ~13 KB/item dominated by the 1024‑dim embedding).
- OCU caps set via CLI: indexing 8 / search 8 (`aws opensearchserverless update-account-settings`).
- Embeddings: Amazon Titan Text Embeddings V2, 1024 dimensions, via Bedrock Batch Inference.

> **Outstanding (Task 10.5):** search parity/perf verification at 2.2M, `ef-search`/OCU tuning,
> the production cutover (`catalog.search.backend=opensearch`), removing the temporary CLI
> data‑access principal, and the final RUNBOOK update. The index itself is complete and verified.

---

## 9. The cutover session — completing Task 10.5

This section records the full dev cutover session (2026-09-03): finishing task 10.5, every issue
encountered, and how each was resolved. The index was already built and verified (§5); this was
about making the live service actually use it.

### 9.1 IaC gap: search-tuning knobs not injectable via Terraform

**Problem:** the four search-tuning knobs — `catalog.search.mode`, `catalog.search.semantic-enabled`,
`opensearch.knn.ef-search`, and `opensearch.knn.quantization` — had env-var overrides in
`application.properties` but were **not plumbed through the ECS task definition**. Only
`CATALOG_SEARCH_BACKEND`, `OPENSEARCH_ENDPOINT`, and `OPENSEARCH_INDEX` were injected. That meant
a production cutover couldn't pin `fp16` quantization (which must match the index) or tune
`ef-search` — the values would be stuck at their `application.properties` defaults.

**Fix:** wired all four through: `modules/ecs/variables.tf` (new variables),
`modules/ecs/main.tf` (new env vars `CATALOG_SEARCH_MODE`, `CATALOG_SEMANTIC`,
`OPENSEARCH_KNN_EF_SEARCH`, `OPENSEARCH_KNN_QUANTIZATION`), `infrastructure/variables.tf`
(root vars), `infrastructure/main.tf` (module wiring). `terraform validate` / `fmt` clean,
backend `./mvnw compile` clean.

### 9.2 Parity re-verification at 2.2M (the verifier run)

Ran `CatalogSearchVerifyRunner` against the live 2.2M OpenSearch index to confirm parity before
the cutover:

```
java -jar target/backend-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=local --server.port=0 \
  --catalog.search.backend=opensearch \
  --opensearch.endpoint=https://o2dmi7wacuk8u8y9pbm6.us-east-1.aoss.amazonaws.com \
  --opensearch.knn.quantization=fp16 \
  --dynamodb.catalog-full-table=recipe-ai-dev-catalog-full \
  --catalog.verify.enabled=true
```

**Issue — app fails to start: `Could not resolve placeholder 'COGNITO_ISSUER_URI'`.** The
full Spring context (including the OAuth2 security layer) boots even for the verifier. Locally,
`COGNITO_ISSUER_URI` isn't in the environment (it's set by ECS or the `local` profile).

**Fix:** add `--spring.profiles.active=local` to the command — the `application-local.properties`
provides `COGNITO_ISSUER_URI` and dev-table config.

**Issue — `./mvnw -pl backend package` fails.** The user ran the command from inside `backend/`
and passed `-pl backend`, but `-pl` selects a reactor module — when you're already inside the
module directory, there's no `backend` submodule in the reactor.

**Fix:** from inside `backend/`, drop the `-pl` flag: `./mvnw clean package -DskipTests`. Or
run from the repo root with `-pl backend`.

**Verifier results (all passed):**
- browse total = **2,231,142** (exact match to DynamoDB)
- keyword 'chicken' = 305,634
- semantic "warm and comforting for a cold night" → top hits: "Beef Stew For A Cold, Cold Night",
  "Pumpkin Laksa for a Cold Night", etc.
- VEGAN filter = 370,699, `allTaggedVegan=true`
- pagination: pages non-overlapping
- findById: present returns recipe, missing returns empty
- latency: sub-second (collection was warm)

### 9.3 Terraform cutover — issues and resolution

#### 9.3.1 `dev.tfvars` vs `prod.tfvars` discovery

**Problem:** the project uses per-environment tfvars files (`infrastructure/environments/dev.tfvars`
and `prod.tfvars`), not ad-hoc `-var` flags. The RUNBOOK's cutover commands used `-var` flags,
which would be overridden by a subsequent `apply -var-file=` that didn't include them — silently
reverting the cutover. Neither tfvars file had any of the OpenSearch cutover variables set.

**Fix:** added the cutover block to `dev.tfvars`:
```hcl
enable_opensearch       = true
enable_catalog_full     = true
enable_batch_embedding  = true
catalog_search_backend  = "opensearch"
opensearch_knn_quantization  = "fp16"
opensearch_knn_ef_search     = 100
opensearch_budget_notification_email = "guido@asbun.io"
```

#### 9.3.2 `prod.tfvars` had the cutover block too — dangerous

**Problem:** someone (a prior session or copy-paste) had added the full OpenSearch cutover block
to `prod.tfvars`. Since there is **no prod OpenSearch index** (everything was built in dev), a
`terraform apply -var-file=prod.tfvars` would provision an empty prod collection and flip the prod
backend to `opensearch` against that empty index — broken prod search, silent.

**Fix:** removed the cutover block from `prod.tfvars` and added a guard comment:
```
# NOTE: OpenSearch catalog search is NOT enabled for prod. Enabling it here would provision
# an EMPTY prod collection — do NOT set enable_opensearch/catalog_search_backend for prod
# until a full prod load + reindex + parity verification has been run.
```

#### 9.3.3 Terraform state lock

**Problem:** `terraform plan` errored with `Error acquiring the state lock`. A prior `terraform
plan` process had been **suspended** (Ctrl-Z, state `T` in `ps`) and still held the DynamoDB lock.

**Fix:**
1. `kill 43085` (SIGTERM) — did not work; a suspended process can't receive SIGTERM.
2. `kill -9 43085` (SIGKILL) — killed the process, but SIGKILL skips Terraform's cleanup handler
   so the DynamoDB lock row remained.
3. `terraform force-unlock 495efa1c-a8bd-6b95-d912-237db1437c99` — released the stale lock.

**Lesson:** always `kill -9` a stopped (state `T`) terraform process, then follow with
`force-unlock` because the cleanup handler never ran.

#### 9.3.4 Unscoped plan brings unwanted drift

**Problem:** a plain `terraform plan -var-file=dev.tfvars` included:
- The intended cutover (ECS env vars, data-access policy).
- **7 destroys** of the Bedrock batch embedding infra (S3 buckets, IAM role) because
  `enable_batch_embedding` wasn't set in `dev.tfvars`.
- A `CHANGE_ME@example.com` placeholder clobbering the live budget email (`guido@asbun.io`).
- Unrelated **Cognito** drift (Google `token_url` changed out of band) and **WAF** drift
  (bot-control `enable_machine_learning` false→true, rules recreated).

**Fix (multi-part):**
1. Set `enable_batch_embedding = true` in `dev.tfvars` (keeps the near-zero-cost batch infra).
2. Set `opensearch_budget_notification_email = "guido@asbun.io"` (matches live).
3. Used a **scoped saved plan** (`-target=...ecs_task_definition.backend -target=...ecs_service.backend
   -target=...access_policy.data -target=...budgets_budget.opensearch -out=cutover.tfplan`)
   to exclude the WAF/Cognito drift.

#### 9.3.5 `-target` couldn't separate the policy change from the ECS cutover

**Problem:** tried to exclude the data-access-policy target (to keep `rodrigo-cli`'s collection
access until after the smoke test) but `-target` includes **dependencies** — the data policy was
in the dependency closure of the ECS targets, so it appeared in the plan regardless.

**Resolution:** accepted the policy change in the same apply. `rodrigo-cli` removal was the
correct end state anyway (least-privilege). Mitigated the "no CLI access for post-cutover debug"
concern by building a **codified re-grant path**: `admin_principals` variable (see §9.4).

### 9.4 Codified `admin_principals` for ad-hoc CLI access

**Context:** removing `rodrigo-cli` from the data-access policy was correct (least-privilege,
Requirement 7.5), but it also removed the ability to run local scripts or the verifier against
the collection. The prior approach (manual CLI policy edits) was undocumented drift — the exact
thing that caused confusion during the reindex (§4 Mistake #5).

**Solution:** added an `admin_principals` variable to the `opensearch` module:
- `modules/opensearch/variables.tf`: `variable "admin_principals" { type = list(string), default = [] }`
- `modules/opensearch/main.tf`: data policy `Principal = concat([var.task_role_arn], var.admin_principals)`
- Root `variables.tf` + `main.tf`: `opensearch_admin_principals` wired through.
- `dev.tfvars`: commented-out example showing how to re-grant:
  ```hcl
  # opensearch_admin_principals = ["arn:aws:iam::412381751532:user/rodrigo-cli"]
  ```

With `admin_principals = []` (default), only the ECS task role has access — the secure state.
Re-granting is a one-line, version-controlled config change + apply, not manual policy drift.

### 9.5 The apply

Applied the scoped saved plan:
```
terraform apply "cutover.tfplan"
```
Result: `Apply complete! Resources: 1 added, 2 changed, 1 destroyed.`
- ECS task def `:8` created with `CATALOG_SEARCH_BACKEND=opensearch` + tuning env vars.
- ECS service updated to `:8`.
- Data-access policy updated: `rodrigo-cli` removed.
- Rollout: `COMPLETED`, `runningCount=1`, `desiredCount=1`, `failedTasks=0`.

### 9.6 The stale-image discovery — why the first smoke test returned 64 results

**Problem:** after the terraform cutover, the `/browse` "chicken" search returned **64 results**
(same as the small in-app catalog), not the expected 305,634+. The cutover appeared broken.

**Investigation:** the ECS task log revealed the root cause:
```
WARN CatalogSearchConfig : catalog.search.backend=opensearch requested but no OpenSearch
backend is implemented yet; falling back to in-app search.
INFO InAppCatalogSearchService : Loaded 1261 catalog recipes into in-app search cache
```

That `"no OpenSearch backend is implemented yet; falling back to in-app"` message was the
**old stubbed `CatalogSearchConfig`** — the warn-and-fallback stub from before the OpenSearch
feature was built (replaced by Task 4.1 on the `OpenSearch` branch). The env var was correctly
set to `opensearch`, but the **running Docker image** was built from `main` before the branch
was merged — it didn't contain `OpenSearchCatalogSearchService` or the real `CatalogSearchConfig`.
It could only fall back to in-app.

**Resolution:** this is not an infra or config problem — the image was stale. **Merging the
`OpenSearch` branch to `main`** triggered the deploy workflow, which built a fresh image from the
merged code containing the real OpenSearch implementation. After the deploy rolled:
```
INFO OpenSearchConfig    : Configuring OpenSearch client: host=o2dmi7wacuk8u8y9pbm6...
INFO CatalogSearchConfig : Using OpenSearch catalog search backend.
```

No more fallback stub. The "chicken" search then returned **1,185,230 results** (hybrid mode:
keyword + semantic, which returns more than keyword-only's 305,634).

**Lesson:** the Terraform cutover (env vars) and the code deploy (Docker image) are **two
separate steps** in this architecture. The cutover sets `CATALOG_SEARCH_BACKEND=opensearch` on
the task def, but the running image must contain the code that honors it. When the code is on a
feature branch and the running image was built from `main`, the env var has no effect until the
branch is merged and deployed. The correct sequence is: cutover the config → merge the code →
confirm the deployed image logs the real backend selection.

### 9.7 `.gitignore` inconsistency

**Problem:** `infrastructure/environments/*.tfvars` was in `.gitignore`, but both `dev.tfvars`
and `prod.tfvars` were **already tracked** (committed earlier in the branch). `.gitignore` only
prevents tracking of *new* files — once a file is committed, the ignore rule has no effect.
This created a false sense that tfvars were protected; someone could drop a raw secret into a
tracked tfvars file and it would be committed normally.

**Fix:** removed the misleading `infrastructure/environments/*.tfvars` line from `.gitignore`
and replaced it with a comment explaining the files are intentionally tracked and must contain
only non-secret values (Secrets Manager ARNs, SSM paths). Also added `*.tfplan` to `.gitignore`
(the saved plan file was untracked but would have been committed on a broad `git add`).

### 9.8 Secret hygiene audit before merge

Ran a full check before pushing:
- `.env*`, `*.pem`, `*credentials*`, `**/*secret*`, `.aws/`, `application-local.properties` — all
  gitignored.
- `application-local.properties` (contains real Cognito issuer) — confirmed **not tracked**.
- The pending diff scanned for raw key patterns (AKIA..., sk-..., AIza..., PEM blocks, inline
  passwords) — `NO_RAW_SECRETS_FOUND`.
- tfvars files contain only ARNs, SSM paths, and non-secret config.
- Pre-commit secret-scanning hook exists in `.githooks/`.

### 9.9 The merge and deploy

- PR #41 ("OpenSearch catalog search backend + dev cutover") — 36 commits, base `main`.
- Updated title and description via `gh pr edit` with the full feature summary.
- Merged to `main` → GitHub Actions deploy workflow built/pushed fresh backend + frontend images
  and ran `force-new-deployment` on ECS.
- Deploy workflow does **not** run `terraform apply` — it ships code/images only. The Terraform
  cutover (env vars, policies) was already applied manually and persists in the task def.
- Confirmed: new task `16fc2c95...` started at 18:48 UTC, logs
  `Using OpenSearch catalog search backend` — the real code, not the fallback stub.
- Rollout completed: single `PRIMARY` deployment, `runningCount=1`, 0 failed.

### 9.10 End-to-end smoke test — confirmed

Logged into `https://recipe-ai-finder.com`, opened `/browse`, searched "chicken" →
**1,185,230 results** returned through the full path:
```
browser → Next.js proxy (attaches JWT) → rewrite (BACKEND_URL=https://recipe-ai-finder.com)
  → ALB → ECS backend :8 (CATALOG_SEARCH_BACKEND=opensearch)
  → CatalogController → OpenSearchCatalogSearchService → OpenSearch (2.2M index)
```

The 1,185,230 vs the verifier's 305,634 is expected: the deployed app runs in **hybrid** mode
(keyword + semantic k-NN, `minimum_should_match=1`), which returns any recipe matching either
clause. The verifier ran each mode separately. Both numbers are correct for their mode.

DNS confirmed: `recipe-ai-finder.com` resolves to the same IPs as the dev ALB
(`recipe-ai-dev-alb-2025002285...`).

### 9.11 Why OpenSearch doesn't work in local (post-cutover)

Two reasons, both by design:

1. **Config:** `catalog.search.backend` defaults to `inapp`; the `local` profile doesn't override
   it. So a plain local run uses the in-app backend (small catalog).
2. **Auth:** even if forced to `opensearch`, the local machine authenticates as `rodrigo-cli`,
   which was **removed from the data-access policy** at cutover (least-privilege). The collection
   rejects it with 403. Re-granting is a one-line `opensearch_admin_principals` change + apply
   (§9.4), but this is intentional — local doesn't need standing collection access.

The correct way to test the OpenSearch cutover is through the **deployed app** (which
authenticates as the ECS task role, still in the policy), not locally.

---

## 10. Final state (post-cutover, 2026-09-03)

| Component | State |
|---|---|
| OpenSearch index | `catalog-recipes`, 2,231,142 docs (== DynamoDB, verified) |
| Quantization | `fp16` (Faiss scalar, ~half vector memory vs float) |
| ECS task def | `:8`, `CATALOG_SEARCH_BACKEND=opensearch`, all tuning env vars injected |
| ECS deployment | `COMPLETED`, 1/1 running, 0 failed |
| Data-access policy | `[task_role_arn]` only (least-privilege); `admin_principals=[]` |
| Budget alarm | `guido@asbun.io`, $30 limit, 80% actual / 100% forecast alerts |
| OCU caps | indexing 8 / search 8 (CLI-set) |
| `dev.tfvars` | OpenSearch enabled, `fp16`, batch infra kept, budget email set |
| `prod.tfvars` | OpenSearch **not** enabled (guard note) |
| Branch | `OpenSearch` merged to `main` (PR #41, 36 commits) |
| Rollback | `catalog_search_backend=inapp` in `dev.tfvars` + apply (reads small table) |
| CLI access | Removed; re-grantable via `opensearch_admin_principals` in tfvars |

The OpenSearch catalog search migration is complete. 2.2M recipes are live and searchable via
keyword, semantic, and dietary-filter search through the deployed app.
