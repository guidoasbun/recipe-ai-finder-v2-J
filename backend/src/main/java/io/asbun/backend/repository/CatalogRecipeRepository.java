package io.asbun.backend.repository;

import io.asbun.backend.model.CatalogRecipe;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Access to a catalog table. The default Spring-managed bean is bound to the small in-app
 * catalog table ({@code dynamodb.catalog-table}) used by the in-app search backend and the
 * controller.
 *
 * <p>To support two tables (rollback preservation, design §6.0), {@link #forTable} produces a
 * repository bound to a different table (e.g. the full 2.2M {@code dynamodb.catalog-full-table})
 * without disturbing the default bean. Ingestion and reindex use whichever table is configured
 * as their target so loading the full dataset never overwrites the small in-app table.
 *
 * <p>The in-app search backend scans all items into memory, so {@link #findAll()} is provided;
 * that is acceptable up to the in-app ceiling (~50K). The OpenSearch reindex also scans, but
 * bulk-indexes into OpenSearch rather than holding everything in memory.
 */
@Repository
public class CatalogRecipeRepository {

    private final DynamoDbEnhancedClient enhancedClient;
    private final DynamoDbTable<CatalogRecipe> table;

    @Autowired
    public CatalogRecipeRepository(DynamoDbEnhancedClient enhancedClient,
                                   @Value("${dynamodb.catalog-table}") String tableName) {
        this.enhancedClient = enhancedClient;
        this.table = enhancedClient.table(tableName, TableSchema.fromBean(CatalogRecipe.class));
    }

    private CatalogRecipeRepository(DynamoDbEnhancedClient enhancedClient,
                                    DynamoDbTable<CatalogRecipe> table) {
        this.enhancedClient = enhancedClient;
        this.table = table;
    }

    /**
     * Returns a repository bound to {@code tableName}. If it matches the table this instance is
     * already bound to, returns {@code this}; otherwise a new instance sharing the same client.
     * Used to target the full-catalog table for ingestion/reindex.
     */
    public CatalogRecipeRepository forTable(String tableName) {
        if (tableName == null || tableName.isBlank() || tableName.equals(table.tableName())) {
            return this;
        }
        DynamoDbTable<CatalogRecipe> other =
                enhancedClient.table(tableName, TableSchema.fromBean(CatalogRecipe.class));
        return new CatalogRecipeRepository(enhancedClient, other);
    }

    /** The DynamoDB table name this repository is bound to. */
    public String tableName() {
        return table.tableName();
    }

    public CatalogRecipe save(CatalogRecipe recipe) {
        table.putItem(recipe);
        return recipe;
    }

    /**
     * Writes many recipes using DynamoDB {@code BatchWriteItem} (25 items per request) — far
     * faster than per-item {@link #save} for bulk ingestion. Handles "unprocessed items"
     * (DynamoDB may defer some under load) by retrying them with exponential backoff.
     */
    public void saveAll(List<CatalogRecipe> recipes) {
        final int maxPerBatch = 25;
        for (int start = 0; start < recipes.size(); start += maxPerBatch) {
            List<CatalogRecipe> slice = recipes.subList(start, Math.min(start + maxPerBatch, recipes.size()));
            writeBatchWithRetry(slice);
        }
    }

    private void writeBatchWithRetry(List<CatalogRecipe> slice) {
        List<CatalogRecipe> pending = new java.util.ArrayList<>(slice);
        int attempt = 0;
        while (!pending.isEmpty()) {
            software.amazon.awssdk.enhanced.dynamodb.model.WriteBatch.Builder<CatalogRecipe> wb =
                    software.amazon.awssdk.enhanced.dynamodb.model.WriteBatch.builder(CatalogRecipe.class)
                            .mappedTableResource(table);
            for (CatalogRecipe r : pending) {
                wb.addPutItem(r);
            }
            var result = enhancedClient.batchWriteItem(b -> b.addWriteBatch(wb.build()));

            List<CatalogRecipe> unprocessed = result.unprocessedPutItemsForTable(table);
            if (unprocessed == null || unprocessed.isEmpty()) {
                return;
            }
            // Some items were throttled/deferred; back off and retry only those.
            attempt++;
            if (attempt > 8) {
                throw new IllegalStateException(
                        "BatchWriteItem left " + unprocessed.size() + " items unprocessed after retries");
            }
            try {
                Thread.sleep(Math.min(2000L, 100L * (1L << Math.min(attempt, 4))));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted during batch write retry", e);
            }
            pending = unprocessed;
        }
    }

    public Optional<CatalogRecipe> findById(String catalogRecipeId) {
        Key key = Key.builder().partitionValue(catalogRecipeId).build();
        return Optional.ofNullable(table.getItem(key));
    }

    public List<CatalogRecipe> findAll() {
        return table.scan()
                .stream()
                .flatMap(page -> page.items().stream())
                .collect(Collectors.toList());
    }

    /**
     * Scans the table page-by-page, invoking {@code pageConsumer} for each page of items,
     * without materializing the whole table in memory. Used by the reindex job so a 2.2M-row
     * full table streams into OpenSearch rather than loading all at once.
     */
    public void scanInPages(java.util.function.Consumer<List<CatalogRecipe>> pageConsumer) {
        table.scan().stream().forEach(page -> pageConsumer.accept(page.items()));
    }

    /**
     * Scans the table page-by-page but PROJECTS ONLY {@code catalogRecipeId}, so the large
     * embedding attribute is never transferred. On the full catalog this is the difference
     * between moving ~29 GB (whole items) and a few tens of MB (ids only) — essential for the
     * reconciliation that just needs to know which ids exist. Each returned {@link CatalogRecipe}
     * has only its id populated; all other fields are null.
     */
    public void scanIdsInPages(java.util.function.Consumer<List<CatalogRecipe>> pageConsumer) {
        table.scan(software.amazon.awssdk.enhanced.dynamodb.model.ScanEnhancedRequest.builder()
                        .attributesToProject("catalogRecipeId")
                        .build())
                .stream()
                .forEach(page -> pageConsumer.accept(page.items()));
    }

    /**
     * Parallel, id-only table scan: splits the table into {@code segments} and scans them
     * concurrently, projecting only {@code catalogRecipeId}. A single-threaded scan of a 2.2M-row
     * table is bound by DynamoDB returning ~1 MB pages one at a time (many minutes); N parallel
     * segments cut that by roughly N. {@code pageConsumer} is called from multiple threads, so it
     * MUST be thread-safe. Each returned {@link CatalogRecipe} has only its id populated.
     */
    public void scanIdsInPagesParallel(int segments,
                                        java.util.function.Consumer<List<CatalogRecipe>> pageConsumer) {
        int total = Math.max(1, segments);
        if (total == 1) {
            scanIdsInPages(pageConsumer);
            return;
        }
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(total);
        try {
            List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
            for (int seg = 0; seg < total; seg++) {
                final int segment = seg;
                futures.add(pool.submit(() -> {
                    table.scan(software.amazon.awssdk.enhanced.dynamodb.model.ScanEnhancedRequest.builder()
                                    .attributesToProject("catalogRecipeId")
                                    .segment(segment)
                                    .totalSegments(total)
                                    .build())
                            .stream()
                            .forEach(page -> pageConsumer.accept(page.items()));
                }));
            }
            for (java.util.concurrent.Future<?> f : futures) {
                try {
                    f.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted during parallel scan", e);
                } catch (java.util.concurrent.ExecutionException e) {
                    throw new IllegalStateException("Parallel scan segment failed", e.getCause());
                }
            }
        } finally {
            pool.shutdown();
        }
    }
}
