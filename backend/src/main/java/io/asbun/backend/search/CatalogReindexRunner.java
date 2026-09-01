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

    public CatalogReindexRunner(CatalogRecipeRepository repository,
                                OpenSearchClient client,
                                OpenSearchIndexProvisioner provisioner,
                                OpenSearchProperties properties,
                                @Value("${catalog.reindex.batch-size:500}") int batchSize,
                                @Value("${dynamodb.catalog-full-table:${dynamodb.catalog-table}}") String sourceTable,
                                @Value("${catalog.reindex.recreate-index:false}") boolean recreateIndex) {
        // Read from the configured catalog table (full table when set, small table otherwise).
        this.repository = repository.forTable(sourceTable);
        this.client = client;
        this.provisioner = provisioner;
        this.properties = properties;
        this.batchSize = Math.max(1, batchSize);
        this.serverless = !"es".equalsIgnoreCase(properties.getSigningService());
        this.recreateIndex = recreateIndex;
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

        repository.scanInPages(page -> {
            for (CatalogRecipe recipe : page) {
                counters.seen++;
                if (recipe.getCatalogRecipeId() == null || recipe.getCatalogRecipeId().isBlank()) {
                    counters.skipped++;
                    continue;
                }
                buffer.add(indexOp(index, recipe));
                if (buffer.size() >= batchSize) {
                    flush(buffer, counters);
                }
            }
        });
        // Flush any remainder.
        flush(buffer, counters);

        log.info("Reindex complete: {} seen, {} indexed, {} skipped, {} failed",
                counters.seen, counters.indexed, counters.skipped, counters.failed);

        // Fail the command on any failures so automation does not treat a partial index as a
        // successful reindex and proceed to cutover. The run is idempotent, so a rerun is safe.
        if (counters.failed > 0) {
            throw new IllegalStateException("Reindex had " + counters.failed
                    + " failed item(s); index may be partial. Fix the cause and re-run (idempotent).");
        }
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

    private void flush(List<BulkOperation> buffer, Counters counters) {
        if (buffer.isEmpty()) {
            return;
        }
        int attempted = buffer.size();
        try {
            BulkRequest request = new BulkRequest.Builder().operations(new ArrayList<>(buffer)).build();
            BulkResponse response = client.bulk(request);
            if (response.errors()) {
                long errors = response.items().stream()
                        .filter(i -> i.error() != null)
                        .count();
                counters.indexed += attempted - errors;
                counters.failed += errors;
                response.items().stream()
                        .filter(i -> i.error() != null)
                        .findFirst()
                        .ifPresent(i -> log.warn("Bulk item error (first of {}): {}",
                                errors, i.error() != null ? i.error().reason() : "unknown"));
            } else {
                counters.indexed += attempted;
            }
            if (counters.indexed % (batchSize * 10) < batchSize) {
                log.info("Reindex progress: {} indexed, {} failed (of {} seen)",
                        counters.indexed, counters.failed, counters.seen);
            }
        } catch (IOException e) {
            counters.failed += attempted;
            log.warn("Bulk request of {} items failed: {}", attempted, e.getMessage());
        } finally {
            buffer.clear();
        }
    }

    private static final class Counters {
        long seen;
        long indexed;
        long skipped;
        long failed;
    }
}
