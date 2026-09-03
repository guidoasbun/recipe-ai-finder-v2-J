package io.asbun.backend.search;

import io.asbun.backend.config.OpenSearchProperties;
import io.asbun.backend.model.CatalogRecipe;
import io.asbun.backend.repository.CatalogRecipeRepository;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One-off reindex from DynamoDB into OpenSearch. Runs ONLY when
 * {@code catalog.reindex.enabled=true} so it never executes during normal application boot.
 *
 * <p>Reads recipes (text + dietaryTags + persisted embedding + attribution) from the configured
 * catalog table and bulk-indexes them into OpenSearch. It never calls Bedrock — embeddings are
 * read from DynamoDB, so re-embedding never happens here. On serverless the document id is
 * auto-generated ({@code catalogRecipeId} is stored as a field); on a managed domain the
 * deterministic {@code catalogRecipeId} is the {@code _id}, so a rerun upserts (idempotent).
 *
 * <h2>Two modes</h2>
 * <ul>
 *   <li><b>Full reindex</b> (default): streams the whole table into OpenSearch with parallel
 *       bulk writes. Any recipe that could not be indexed after retries has its
 *       {@code catalogRecipeId} written to a failure-ids file so it can be replayed later.</li>
 *   <li><b>Backfill</b> ({@code catalog.reindex.backfill=true}): indexes only the recipes that
 *       are missing from the index — either the ids listed in a failure-ids file
 *       ({@code catalog.reindex.backfill-ids-file}), or, when no file is given, the set derived
 *       by pulling every indexed {@code catalogRecipeId} from OpenSearch and diffing it against
 *       the DynamoDB table. Backfill is single-threaded with long, patient backoff, so a small
 *       number of items does not re-trigger the serverless indexing-OCU throttling that dropped
 *       them in the first place. Backfill never recreates the index.</li>
 * </ul>
 *
 * <p>Requires the OpenSearch backend beans, so run with {@code catalog.search.backend=opensearch}
 * (and a configured {@code opensearch.endpoint}).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "catalog.reindex.enabled", havingValue = "true")
public class CatalogReindexRunner implements CommandLineRunner {

    private final CatalogRecipeRepository repository;
    private final OpenSearchClient client;
    private final OpenSearchIndexProvisioner provisioner;
    private final OpenSearchProperties properties;
    private final int batchSize;
    private final boolean serverless;
    private final boolean recreateIndex;
    private final int concurrency;
    private final boolean backfill;
    private final String backfillIdsFile;
    private final String failedIdsFile;
    private final boolean pitProbe;

    public CatalogReindexRunner(CatalogRecipeRepository repository,
                                OpenSearchClient client,
                                OpenSearchIndexProvisioner provisioner,
                                OpenSearchProperties properties,
                                @Value("${catalog.reindex.batch-size:500}") int batchSize,
                                @Value("${dynamodb.catalog-full-table:${dynamodb.catalog-table}}") String sourceTable,
                                @Value("${catalog.reindex.recreate-index:false}") boolean recreateIndex,
                                @Value("${catalog.reindex.concurrency:8}") int concurrency,
                                @Value("${catalog.reindex.backfill:false}") boolean backfill,
                                @Value("${catalog.reindex.backfill-ids-file:}") String backfillIdsFile,
                                @Value("${catalog.reindex.failed-ids-file:}") String failedIdsFile,
                                @Value("${catalog.reindex.pit-probe:false}") boolean pitProbe) {
        // Read from the configured catalog table (full table when set, small table otherwise).
        this.repository = repository.forTable(sourceTable);
        this.client = client;
        this.provisioner = provisioner;
        this.properties = properties;
        this.batchSize = Math.max(1, batchSize);
        this.serverless = !"es".equalsIgnoreCase(properties.getSigningService());
        this.recreateIndex = recreateIndex;
        this.concurrency = Math.max(1, concurrency);
        this.backfill = backfill;
        this.backfillIdsFile = backfillIdsFile == null ? "" : backfillIdsFile.trim();
        this.failedIdsFile = failedIdsFile == null ? "" : failedIdsFile.trim();
        this.pitProbe = pitProbe;
    }

    @Override
    public void run(String... args) {
        if (pitProbe) {
            runPitProbe();
        } else if (backfill) {
            runBackfill();
        } else {
            runFullReindex();
        }
    }

    /**
     * Cheap yes/no check that a point-in-time can be created + a first page read on this
     * collection, without scanning DynamoDB. Run with {@code catalog.reindex.pit-probe=true}
     * before committing to a full reconciliation. Throws with the recreate-fallback hint if PIT
     * is unavailable.
     */
    private void runPitProbe() {
        log.info("PIT probe: attempting to create a point-in-time on index '{}'...", properties.getIndex());
        String pitId = createPit();
        try {
            org.opensearch.client.opensearch.core.search.Pit pit =
                    new org.opensearch.client.opensearch.core.search.Pit.Builder()
                            .id(pitId).keepAlive("1m").build();
            SearchResponse<CatalogRecipe> resp = client.search(b -> b
                    .size(1)
                    .pit(pit)
                    .trackTotalHits(t -> t.enabled(false))
                    .source(src -> src.filter(f -> f.includes("catalogRecipeId")))
                    .query(q -> q.matchAll(m -> m))
                    .sort(so -> so.field(f -> f.field("catalogRecipeId").order(
                            org.opensearch.client.opensearch._types.SortOrder.Asc))),
                    CatalogRecipe.class);
            int got = resp.hits().hits().size();
            log.info("PIT probe SUCCEEDED: PIT create + search_after page returned {} hit(s). "
                    + "Reconciliation backfill is safe to run.", got);
        } catch (IOException e) {
            throw new IllegalStateException("PIT probe: create succeeded but paged search failed: "
                    + e.getMessage(), e);
        } finally {
            deletePitQuietly(pitId);
        }
    }

    // ── Full reindex ─────────────────────────────────────────────────────────

    private void runFullReindex() {
        String index = properties.getIndex();
        log.info("Reindex starting: source table={}, index={}, batchSize={}, recreateIndex={}, concurrency={}",
                repository.tableName(), index, batchSize, recreateIndex, concurrency);

        // Recreate when the mapping must change (e.g. enabling fp16 quantization on a fresh
        // full-scale index). Drops the existing index and its data first.
        if (recreateIndex) {
            provisioner.deleteIndexIfExists();
        }
        provisioner.ensureIndex();

        Counters counters = new Counters();
        FailedIdWriter failedIds = new FailedIdWriter(failedIdsFile);
        List<CatalogRecipe> buffer = new ArrayList<>(batchSize);

        // Parallelize bulk writes: a single-threaded flush waits on each ~1-2s round-trip
        // (~50 docs/sec). Submitting up to `concurrency` bulk requests in flight overlaps that
        // latency and multiplies throughput. A semaphore bounds in-flight work so the scan does
        // not race ahead and buffer the whole table in memory.
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(concurrency);
        java.util.concurrent.Semaphore inFlight = new java.util.concurrent.Semaphore(concurrency);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();

        try {
            repository.scanInPages(page -> {
                for (CatalogRecipe recipe : page) {
                    counters.seen++;
                    if (recipe.getCatalogRecipeId() == null || recipe.getCatalogRecipeId().isBlank()) {
                        counters.skipped++;
                        continue;
                    }
                    buffer.add(recipe);
                    if (buffer.size() >= batchSize) {
                        submitFlush(pool, inFlight, futures, buffer, counters, failedIds);
                        buffer.clear();
                    }
                }
            });
            // Submit the remainder, then wait for all in-flight bulk requests.
            if (!buffer.isEmpty()) {
                submitFlush(pool, inFlight, futures, new ArrayList<>(buffer), counters, failedIds);
                buffer.clear();
            }
            for (java.util.concurrent.Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception e) {
                    log.warn("Bulk task failed: {}", e.getMessage());
                }
            }
        } finally {
            pool.shutdown();
            failedIds.close();
        }

        log.info("Reindex complete: {} seen, {} indexed, {} skipped, {} failed",
                counters.seen, counters.indexed.get(), counters.skipped, counters.failed.get());

        // Fail the command on any failures so automation does not treat a partial index as a
        // successful reindex and proceed to cutover. The failed ids were written to
        // failedIdsFile (when configured) so a backfill run can replay exactly those.
        if (counters.failed.get() > 0) {
            String hint = failedIds.path() != null
                    ? " Failed ids written to " + failedIds.path()
                        + "; re-run with --catalog.reindex.backfill=true --catalog.reindex.backfill-ids-file="
                        + failedIds.path()
                    : " (set catalog.reindex.failed-ids-file to capture the failed ids for a targeted backfill).";
            throw new IllegalStateException("Reindex had " + counters.failed.get()
                    + " failed item(s); index may be partial." + hint);
        }
    }

    /** Submits a copy of the buffer as a bulk request on the pool, bounded by the semaphore. */
    private void submitFlush(java.util.concurrent.ExecutorService pool,
                             java.util.concurrent.Semaphore inFlight,
                             List<java.util.concurrent.Future<?>> futures,
                             List<CatalogRecipe> buffer,
                             Counters counters,
                             FailedIdWriter failedIds) {
        List<CatalogRecipe> batch = new ArrayList<>(buffer);
        try {
            inFlight.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted acquiring reindex permit", e);
        }
        futures.add(pool.submit(() -> {
            try {
                flushBatch(batch, counters, failedIds, 6, 3_000L);
            } finally {
                inFlight.release();
            }
        }));
    }

    private BulkOperation indexOp(String index, CatalogRecipe recipe) {
        // Index only the mapped fields. Clear searchText (a pre-concatenated duplicate of
        // title/description/ingredients that the OpenSearch document intentionally excludes —
        // design §3) so dynamic mapping does not index it across the whole catalog.
        recipe.setSearchText(null);
        // OpenSearch Serverless (aoss) rejects a custom document _id ("Document ID is not
        // supported"); it auto-generates ids. catalogRecipeId is stored as a document field for
        // lookup/dedup instead. Managed domains (es) can use catalogRecipeId as _id for upsert.
        if (serverless) {
            return BulkOperation.of(op -> op.index(idx -> idx.index(index).document(recipe)));
        }
        return BulkOperation.of(op -> op.index(idx -> idx
                .index(index)
                .id(recipe.getCatalogRecipeId())
                .document(recipe)));
    }

    /**
     * Runs one bulk request (called concurrently from the pool in full reindex, serially in
     * backfill); counters are thread-safe. Retries items the server rejected (e.g. OpenSearch
     * Serverless "[throttled]" while it auto-scales indexing capacity) with exponential backoff.
     * Items still failing after {@code maxAttempts} are counted as failed and their
     * {@code catalogRecipeId} is recorded so a later backfill can replay exactly those.
     *
     * @param maxAttempts total attempts per batch (higher for backfill to outlast a scale-up)
     * @param backoffCapMillis upper bound on the per-attempt sleep (larger for backfill)
     */
    private void flushBatch(List<CatalogRecipe> batch, Counters counters, FailedIdWriter failedIds,
                            int maxAttempts, long backoffCapMillis) {
        if (batch.isEmpty()) {
            return;
        }
        String index = properties.getIndex();
        List<CatalogRecipe> pending = batch;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            List<BulkOperation> ops = new ArrayList<>(pending.size());
            for (CatalogRecipe r : pending) {
                ops.add(indexOp(index, r));
            }
            List<CatalogRecipe> retry = new ArrayList<>();
            try {
                BulkResponse response = client.bulk(new BulkRequest.Builder().operations(ops).build());
                if (!response.errors()) {
                    counters.addIndexed(pending.size());
                    break;
                }
                // Re-collect the failed sub-operations (by position) for retry.
                var items = response.items();
                long okThisPass = 0;
                for (int i = 0; i < items.size(); i++) {
                    if (items.get(i).error() == null) {
                        okThisPass++;
                    } else {
                        retry.add(pending.get(i));
                    }
                }
                counters.addIndexed(okThisPass);
                if (retry.isEmpty()) {
                    break;
                }
                if (attempt == maxAttempts) {
                    recordFailed(retry, counters, failedIds);
                    log.warn("{} items still failing after {} attempts (first: {})", retry.size(),
                            maxAttempts, items.stream().filter(x -> x.error() != null).findFirst()
                                    .map(x -> x.error().reason()).orElse("unknown"));
                    break;
                }
                log.info("Retrying {} throttled/failed items (attempt {}/{})", retry.size(), attempt + 1, maxAttempts);
            } catch (Exception e) {
                // Catches BOTH IOException AND OpenSearchException — crucially the latter carries
                // whole-request throttling as HTTP 429 (and the serverless "[throttled]" 503).
                // Without this, a 429 on the entire bulk request would escape flushBatch entirely:
                // it would neither be retried NOR have its ids recorded to the failed-ids file,
                // so the backfill could never recover those documents.
                if (attempt == maxAttempts) {
                    recordFailed(pending, counters, failedIds);
                    log.warn("Bulk request of {} items failed after {} attempts: {}",
                            pending.size(), maxAttempts, e.getMessage());
                    break;
                }
                log.warn("Bulk request of {} items failed (attempt {}/{}): {} — retrying",
                        pending.size(), attempt, maxAttempts, e.getMessage());
                retry = pending; // whole request failed; retry all
            }
            // Backoff before the next attempt: exponential, capped. A larger cap (backfill) lets
            // a batch wait out a multi-minute serverless indexing-OCU scale-up window.
            try {
                Thread.sleep(Math.min(backoffCapMillis, 200L * (1L << Math.min(attempt - 1, 20))));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                recordFailed(retry, counters, failedIds);
                return;
            }
            pending = retry;
        }

        long done = counters.indexed.get();
        if (done % (batchSize * 10L) < batchSize) {
            log.info("Reindex progress: {} indexed, {} failed (of {} seen)",
                    done, counters.failed.get(), counters.seen);
        }
    }

    private void recordFailed(List<CatalogRecipe> failed, Counters counters, FailedIdWriter failedIds) {
        counters.addFailed(failed.size());
        for (CatalogRecipe r : failed) {
            failedIds.write(r.getCatalogRecipeId());
        }
    }

    // ── Backfill ─────────────────────────────────────────────────────────────

    /**
     * Indexes only the recipes missing from the index. The missing set comes from a failure-ids
     * file when configured, otherwise it is reconciled by pulling every indexed
     * {@code catalogRecipeId} from OpenSearch and diffing against the DynamoDB table. Indexing is
     * single-threaded with long, patient backoff so a small replay does not re-throttle.
     */
    private void runBackfill() {
        String index = properties.getIndex();
        log.info("Backfill starting: source table={}, index={}, batchSize={} (single-threaded, patient backoff)",
                repository.tableName(), index, batchSize);
        provisioner.ensureIndex();

        List<CatalogRecipe> missing = resolveMissing();
        log.info("Backfill: {} recipe(s) to index.", missing.size());
        if (missing.isEmpty()) {
            log.info("Backfill complete: nothing missing, index is already complete.");
            return;
        }

        Counters counters = new Counters();
        counters.seen = missing.size();
        FailedIdWriter failedIds = new FailedIdWriter(failedIdsFile);
        try {
            List<CatalogRecipe> buffer = new ArrayList<>(batchSize);
            for (CatalogRecipe r : missing) {
                buffer.add(r);
                if (buffer.size() >= batchSize) {
                    // Patient: up to 12 attempts, backoff capped at 30s, so a batch can ride out
                    // a multi-minute indexing scale-up rather than giving up in ~10s.
                    flushBatch(new ArrayList<>(buffer), counters, failedIds, 12, 30_000L);
                    buffer.clear();
                    log.info("Backfill progress: {} indexed, {} failed (of {} to do)",
                            counters.indexed.get(), counters.failed.get(), counters.seen);
                }
            }
            if (!buffer.isEmpty()) {
                flushBatch(new ArrayList<>(buffer), counters, failedIds, 12, 30_000L);
            }
        } finally {
            failedIds.close();
        }

        log.info("Backfill complete: {} to do, {} indexed, {} failed",
                counters.seen, counters.indexed.get(), counters.failed.get());
        if (counters.failed.get() > 0) {
            throw new IllegalStateException("Backfill still had " + counters.failed.get()
                    + " failed item(s)." + (failedIds.path() != null
                        ? " Failed ids: " + failedIds.path() : ""));
        }
    }

    /** Resolves which recipes are missing from the index: from an ids file, or by reconciliation. */
    private List<CatalogRecipe> resolveMissing() {
        if (!backfillIdsFile.isEmpty()) {
            return loadFromIdsFile(backfillIdsFile);
        }
        return reconcileMissing();
    }

    /** Loads the recipes named in a failure-ids file (one catalogRecipeId per line) from DynamoDB. */
    private List<CatalogRecipe> loadFromIdsFile(String file) {
        List<String> ids;
        try {
            ids = Files.readAllLines(Path.of(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read backfill ids file: " + file, e);
        }
        List<CatalogRecipe> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String raw : ids) {
            String id = raw == null ? "" : raw.trim();
            if (id.isEmpty() || !seen.add(id)) {
                continue;
            }
            repository.findById(id).ifPresentOrElse(
                    out::add,
                    () -> log.warn("Backfill ids file references unknown recipe {} (not in {})",
                            id, repository.tableName()));
        }
        log.info("Backfill: loaded {} recipe(s) from ids file {}.", out.size(), file);
        return out;
    }

    /**
     * Derives the missing set by pulling every {@code catalogRecipeId} already in the index into
     * a set (via a point-in-time + {@code search_after}, the deep-pagination primitive AWS lists
     * as supported for serverless), then scanning DynamoDB and keeping any recipe whose id is not
     * in that set. Only the {@code catalogRecipeId} field is fetched (no vectors), so each page
     * is cheap.
     *
     * <p>If PIT is unavailable on this collection, this throws with a clear message pointing at
     * the recreate-reindex fallback ({@code scripts/run-full-catalog-reindex.sh}), rather than
     * silently degrading.
     */
    private List<CatalogRecipe> reconcileMissing() {
        Set<String> indexed = fetchIndexedIds();
        log.info("Backfill reconcile: {} catalogRecipeId(s) currently in the index.", indexed.size());

        // Cheap, PARALLEL id-only scan (projects catalogRecipeId, NOT the ~13 KB embedding) to
        // find which ids are missing. A single-threaded scan of 2.2M rows is bound by DynamoDB
        // returning ~1 MB pages serially (many minutes); parallel segments cut that down. The
        // page consumer runs on multiple threads, so accumulation is synchronized and progress is
        // logged periodically (so the phase is never a silent black box).
        List<String> missingIds = java.util.Collections.synchronizedList(new ArrayList<>());
        java.util.concurrent.atomic.AtomicLong scanned = new java.util.concurrent.atomic.AtomicLong();
        int segments = 8;
        repository.scanIdsInPagesParallel(segments, page -> {
            for (CatalogRecipe r : page) {
                long n = scanned.incrementAndGet();
                String id = r.getCatalogRecipeId();
                if (id != null && !id.isBlank() && !indexed.contains(id)) {
                    missingIds.add(id);
                }
                if (n % 200_000 == 0) {
                    log.info("Backfill reconcile: scanned {} recipe(s) so far, {} missing...",
                            n, missingIds.size());
                }
            }
        });
        log.info("Backfill reconcile: scanned {} recipe(s) in DynamoDB, {} missing from the index.",
                scanned.get(), missingIds.size());

        // Now fetch the FULL recipe (with embedding) only for the missing ids — a point read
        // each, so we pay the big-item cost only for what we actually need to index.
        List<CatalogRecipe> missing = new ArrayList<>(missingIds.size());
        long loaded = 0;
        for (String id : missingIds) {
            repository.findById(id).ifPresent(missing::add);
            if (++loaded % 10_000 == 0) {
                log.info("Backfill reconcile: loaded {}/{} missing recipes from DynamoDB...",
                        loaded, missingIds.size());
            }
        }
        return missing;
    }

    /**
     * Pulls every {@code catalogRecipeId} in the index into a set, paginating with a
     * point-in-time (PIT) + {@code search_after}. Sorted by {@code catalogRecipeId} (unique
     * keyword) so {@code search_after} advances deterministically. Retries transient page errors.
     */
    private Set<String> fetchIndexedIds() {
        int pageSize = 2_000;
        Set<String> ids = new HashSet<>();
        String pitId = createPit();
        try {
            List<FieldValue> searchAfter = null;
            int emptyOrErr = 0;
            while (true) {
                final List<FieldValue> after = searchAfter;
                org.opensearch.client.opensearch.core.search.Pit pit =
                        new org.opensearch.client.opensearch.core.search.Pit.Builder()
                                .id(pitId).keepAlive("5m").build();
                SearchResponse<CatalogRecipe> resp;
                try {
                    resp = client.search(b -> {
                        b.size(pageSize)
                                // With a PIT the index is implied by the PIT; do NOT set .index().
                                .pit(pit)
                                .trackTotalHits(t -> t.enabled(false))
                                .source(src -> src.filter(f -> f.includes("catalogRecipeId")))
                                .query(q -> q.matchAll(m -> m))
                                .sort(so -> so.field(f -> f.field("catalogRecipeId").order(
                                        org.opensearch.client.opensearch._types.SortOrder.Asc)));
                        if (after != null) {
                            b.searchAfter(after);
                        }
                        return b;
                    }, CatalogRecipe.class);
                    emptyOrErr = 0;
                } catch (Exception e) {
                    if (++emptyOrErr >= 5) {
                        throw new IllegalStateException(
                                "PIT paging failed repeatedly while pulling indexed ids: " + e.getMessage(), e);
                    }
                    log.warn("PIT page failed (attempt {}/5): {} — retrying", emptyOrErr, e.getMessage());
                    sleepQuietly(Math.min(10_000L, 500L * (1L << (emptyOrErr - 1))));
                    continue;
                }

                var hits = resp.hits().hits();
                if (hits.isEmpty()) {
                    break;
                }
                for (var hit : hits) {
                    if (hit.source() != null && hit.source().getCatalogRecipeId() != null) {
                        ids.add(hit.source().getCatalogRecipeId());
                    }
                }
                searchAfter = hits.get(hits.size() - 1).sort();
                if (searchAfter == null || searchAfter.isEmpty()) {
                    break;
                }
                if (ids.size() % 100_000 < pageSize) {
                    log.info("Backfill reconcile: pulled {} indexed ids so far...", ids.size());
                }
            }
        } finally {
            deletePitQuietly(pitId);
        }
        return ids;
    }

    /**
     * Creates a point-in-time via the generic (raw) client, trying both endpoint forms because
     * the exact path differs by deployment: open-source OpenSearch uses
     * {@code POST /{index}/_search/point_in_time}; AWS serverless lists {@code POST
     * /_search/point_in_time}. Returns the {@code pit_id}. Throws (pointing at the recreate
     * fallback) if neither form works, so we do not silently proceed on a broken reconciliation.
     */
    private String createPit() {
        String index = properties.getIndex();
        String[] endpoints = {
                "/" + index + "/_search/point_in_time",
                "/_search/point_in_time"
        };
        RuntimeException last = null;
        for (String endpoint : endpoints) {
            try (var resp = client.generic().execute(
                    org.opensearch.client.opensearch.generic.Requests.builder()
                            .method("POST")
                            .endpoint(endpoint)
                            .query(java.util.Map.of("keep_alive", "5m"))
                            .build())) {
                if (resp.getStatus() / 100 != 2) {
                    last = new IllegalStateException("PIT create " + endpoint + " -> HTTP "
                            + resp.getStatus() + " " + resp.getReason());
                    continue;
                }
                String body = resp.getBody().map(b -> b.bodyAsString()).orElse("");
                java.util.regex.Matcher m =
                        java.util.regex.Pattern.compile("\"pit_id\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
                if (m.find()) {
                    log.info("PIT created via {}.", endpoint);
                    return m.group(1);
                }
                last = new IllegalStateException("PIT create " + endpoint + " returned no pit_id: " + body);
            } catch (IOException e) {
                last = new IllegalStateException("PIT create " + endpoint + " request failed", e);
            }
        }
        throw new IllegalStateException(
                "Could not create a point-in-time on this collection (tried both endpoint forms). "
                + "Reconciliation cannot proceed. Use the clean recreate reindex instead: "
                + "scripts/run-full-catalog-reindex.sh (it captures failed ids for a targeted backfill). "
                + "Last error: " + (last == null ? "unknown" : last.getMessage()), last);
    }

    /** Best-effort PIT delete; the PIT also expires on its keep-alive if this fails. */
    private void deletePitQuietly(String pitId) {
        try (var resp = client.generic().execute(
                org.opensearch.client.opensearch.generic.Requests.builder()
                        .method("DELETE")
                        .endpoint("/_search/point_in_time")
                        .json("{\"pit_id\":[\"" + pitId + "\"]}")
                        .build())) {
            resp.getStatus(); // ignore; best-effort
        } catch (IOException ignored) {
            // PIT expires on keep-alive
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Appends failed catalogRecipeIds to a file (one per line), created lazily on first write. */
    private static final class FailedIdWriter {
        private final String configuredPath;
        private Path path;
        private BufferedWriter writer;
        private boolean broken;

        FailedIdWriter(String configuredPath) {
            this.configuredPath = configuredPath == null ? "" : configuredPath.trim();
        }

        synchronized void write(String id) {
            if (configuredPath.isEmpty() || broken || id == null || id.isBlank()) {
                return;
            }
            try {
                if (writer == null) {
                    path = Path.of(configuredPath);
                    if (path.getParent() != null) {
                        Files.createDirectories(path.getParent());
                    }
                    writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
                }
                writer.write(id);
                writer.newLine();
            } catch (IOException e) {
                broken = true;
                log.warn("Could not write failed-id file {}: {}", configuredPath, e.getMessage());
            }
        }

        synchronized Path path() {
            return path;
        }

        synchronized void close() {
            if (writer != null) {
                try {
                    writer.flush();
                    writer.close();
                } catch (IOException e) {
                    log.warn("Could not close failed-id file {}: {}", configuredPath, e.getMessage());
                }
            }
        }
    }

    private static final class Counters {
        volatile long seen; // set on the scan (main) thread before flushes run
        final java.util.concurrent.atomic.AtomicLong indexed = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong failed = new java.util.concurrent.atomic.AtomicLong();
        long skipped;

        void addIndexed(long n) {
            indexed.addAndGet(n);
        }

        void addFailed(long n) {
            failed.addAndGet(n);
        }
    }
}
