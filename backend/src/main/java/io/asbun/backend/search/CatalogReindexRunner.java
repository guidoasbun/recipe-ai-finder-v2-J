package io.asbun.backend.search;

import io.asbun.backend.config.OpenSearchProperties;
import io.asbun.backend.model.CatalogRecipe;
import io.asbun.backend.repository.CatalogRecipeRepository;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * One-off reindex from DynamoDB into OpenSearch. Runs ONLY when
 * {@code catalog.reindex.enabled=true} so it never executes during normal application boot.
 *
 * <p>Reads recipes (text + dietaryTags + persisted embedding + attribution) from the configured
 * catalog table and bulk-indexes them into OpenSearch. It never calls Bedrock — embeddings are
 * read from DynamoDB, so re-embedding never happens here. The OpenSearch document id is the
 * deterministic {@code catalogRecipeId}, so re-running upserts rather than duplicating
 * (idempotent, resumable).
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

    public CatalogReindexRunner(CatalogRecipeRepository repository,
                                OpenSearchClient client,
                                OpenSearchIndexProvisioner provisioner,
                                OpenSearchProperties properties,
                                @Value("${catalog.reindex.batch-size:500}") int batchSize,
                                @Value("${dynamodb.catalog-full-table:${dynamodb.catalog-table}}") String sourceTable,
                                @Value("${catalog.reindex.recreate-index:false}") boolean recreateIndex,
                                @Value("${catalog.reindex.concurrency:8}") int concurrency) {
        // Read from the configured catalog table (full table when set, small table otherwise).
        this.repository = repository.forTable(sourceTable);
        this.client = client;
        this.provisioner = provisioner;
        this.properties = properties;
        this.batchSize = Math.max(1, batchSize);
        this.serverless = !"es".equalsIgnoreCase(properties.getSigningService());
        this.recreateIndex = recreateIndex;
        this.concurrency = Math.max(1, concurrency);
    }

    @Override
    public void run(String... args) {
        String index = properties.getIndex();
        log.info("Reindex starting: source table={}, index={}, batchSize={}, recreateIndex={}",
                repository.tableName(), index, batchSize, recreateIndex);

        // Recreate when the mapping must change (e.g. enabling fp16 quantization on a fresh
        // full-scale index). Drops the existing index and its data first.
        if (recreateIndex) {
            provisioner.deleteIndexIfExists();
        }
        provisioner.ensureIndex();

        Counters counters = new Counters();
        List<BulkOperation> buffer = new ArrayList<>(batchSize);

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
                    buffer.add(indexOp(index, recipe));
                    if (buffer.size() >= batchSize) {
                        submitFlush(pool, inFlight, futures, buffer, counters);
                        buffer.clear();
                    }
                }
            });
            // Submit the remainder, then wait for all in-flight bulk requests.
            if (!buffer.isEmpty()) {
                submitFlush(pool, inFlight, futures, new ArrayList<>(buffer), counters);
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
        }

        log.info("Reindex complete: {} seen, {} indexed, {} skipped, {} failed",
                counters.seen, counters.indexed.get(), counters.skipped, counters.failed.get());

        // Fail the command on any failures so automation does not treat a partial index as a
        // successful reindex and proceed to cutover. The run is idempotent, so a rerun is safe.
        if (counters.failed.get() > 0) {
            throw new IllegalStateException("Reindex had " + counters.failed.get()
                    + " failed item(s); index may be partial. Fix the cause and re-run (idempotent).");
        }
    }

    /** Submits a copy of the buffer as a bulk request on the pool, bounded by the semaphore. */
    private void submitFlush(java.util.concurrent.ExecutorService pool,
                             java.util.concurrent.Semaphore inFlight,
                             List<java.util.concurrent.Future<?>> futures,
                             List<BulkOperation> buffer,
                             Counters counters) {
        List<BulkOperation> batch = new ArrayList<>(buffer);
        try {
            inFlight.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted acquiring reindex permit", e);
        }
        futures.add(pool.submit(() -> {
            try {
                flushBatch(batch, counters);
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
     * Runs one bulk request (called concurrently from the pool); counters are thread-safe.
     * Retries items the server rejected (e.g. OpenSearch Serverless "[throttled]" while it
     * auto-scales indexing capacity) with exponential backoff, so transient throttling does not
     * leave gaps in the index. Only genuinely stuck items after all retries count as failed.
     */
    private void flushBatch(List<BulkOperation> batch, Counters counters) {
        if (batch.isEmpty()) {
            return;
        }
        int attempted = batch.size();
        List<BulkOperation> pending = batch;
        int maxAttempts = 6;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            List<BulkOperation> retry = new ArrayList<>();
            try {
                BulkResponse response = client.bulk(new BulkRequest.Builder().operations(pending).build());
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
                    counters.addFailed(retry.size());
                    log.warn("{} items still failing after {} attempts (first: {})", retry.size(),
                            maxAttempts, items.stream().filter(x -> x.error() != null).findFirst()
                                    .map(x -> x.error().reason()).orElse("unknown"));
                    break;
                }
                log.info("Retrying {} throttled/failed items (attempt {}/{})", retry.size(), attempt + 1, maxAttempts);
            } catch (IOException e) {
                if (attempt == maxAttempts) {
                    counters.addFailed(pending.size());
                    log.warn("Bulk request of {} items failed after {} attempts: {}",
                            pending.size(), maxAttempts, e.getMessage());
                    break;
                }
                retry = pending; // whole request failed; retry all
            }
            // Backoff before the next attempt (100ms, 200, 400, 800, 1600, capped 3s).
            try {
                Thread.sleep(Math.min(3000L, 100L * (1L << (attempt - 1))));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                counters.addFailed(retry.size());
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

    private static final class Counters {
        long seen; // only touched on the scan (main) thread
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
