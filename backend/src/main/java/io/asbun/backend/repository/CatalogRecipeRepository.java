package io.asbun.backend.repository;

import io.asbun.backend.model.CatalogRecipe;
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
}
