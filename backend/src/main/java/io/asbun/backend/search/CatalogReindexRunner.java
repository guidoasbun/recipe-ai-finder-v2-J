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
                                @Value("${catalog.reindex.failed-ids-file:}") String failedIdsFile) {
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
    }

    @Override
    public void run(String... args) {
        if (backfill) {
            runBackfill();
        } else {
            runFullReindex();
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
            } catch (IOException e) {
                if (attempt == maxAttempts) {
                    recordFailed(pending, counters, failedIds);
                    log.warn("Bulk request of {} items failed after {} attempts: {}",
                            pending.size(), maxAttempts, e.getMessage());
                    break;
                }
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
     * Derives the missing set with a reverse existence check that uses only the plain
     * {@code search} API (the pagination-free primitive this serverless collection supports —
     * scroll and point-in-time both 404 here). It scans DynamoDB in chunks of {@code probeSize}
     * ids and, for each chunk, runs a single {@code terms} query asking which of those ids are
     * present in the index. Any id in the chunk that does not come back is missing.
     *
     * <p>Cost: ~{@code ceil(total / probeSize)} cheap queries (each returns only the
     * {@code catalogRecipeId} field, no vectors), e.g. ~2.2k queries for 2.2M recipes.
     */
    private List<CatalogRecipe> reconcileMissing() {
        final int probeSize = 1_000;
        List<CatalogRecipe> missing = new ArrayList<>();
        List<CatalogRecipe> probe = new ArrayList<>(probeSize);
        long[] scanned = {0};
        long[] checked = {0};

        repository.scanInPages(page -> {
            for (CatalogRecipe r : page) {
                scanned[0]++;
                if (r.getCatalogRecipeId() == null || r.getCatalogRecipeId().isBlank()) {
                    continue;
                }
                probe.add(r);
                if (probe.size() >= probeSize) {
                    collectMissing(probe, missing);
                    checked[0] += probe.size();
                    probe.clear();
                    if (checked[0] % 100_000 < probeSize) {
                        log.info("Backfill reconcile: checked {} recipe(s), {} missing so far...",
                                checked[0], missing.size());
                    }
                }
            }
        });
        if (!probe.isEmpty()) {
            collectMissing(probe, missing);
        }
        log.info("Backfill reconcile: scanned {} recipe(s) in DynamoDB, {} missing from the index.",
                scanned[0], missing.size());
        return missing;
    }

    /**
     * Runs one {@code terms} query over the {@code catalogRecipeId}s in {@code probe} and adds any
     * recipe whose id is NOT returned (i.e. not in the index) to {@code missing}.
     */
    private void collectMissing(List<CatalogRecipe> probe, List<CatalogRecipe> missing) {
        String index = properties.getIndex();
        List<FieldValue> terms = new ArrayList<>(probe.size());
        for (CatalogRecipe r : probe) {
            terms.add(FieldValue.of(r.getCatalogRecipeId()));
        }
        Set<String> present = new HashSet<>();
        try {
            SearchResponse<CatalogRecipe> resp = client.search(b -> b
                    .index(index)
                    .size(probe.size())
                    .trackTotalHits(t -> t.enabled(false))
                    .source(src -> src.filter(f -> f.includes("catalogRecipeId")))
                    .query(q -> q.terms(t -> t
                            .field("catalogRecipeId")
                            .terms(tt -> tt.value(terms)))),
                    CatalogRecipe.class);
            for (var hit : resp.hits().hits()) {
                if (hit.source() != null && hit.source().getCatalogRecipeId() != null) {
                    present.add(hit.source().getCatalogRecipeId());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Reconcile existence-check query failed", e);
        }
        for (CatalogRecipe r : probe) {
            if (!present.contains(r.getCatalogRecipeId())) {
                missing.add(r);
            }
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
