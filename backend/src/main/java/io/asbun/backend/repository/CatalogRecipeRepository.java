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
 * Access to the shared catalog table. The in-app search backend scans all items into memory,
 * so a {@link #findAll()} is provided; that is acceptable at Phase 1 scale (~300 recipes) and
 * up to the in-app ceiling (~50K). A future OpenSearch backend would not scan here.
 */
@Repository
public class CatalogRecipeRepository {

    private final DynamoDbTable<CatalogRecipe> table;

    public CatalogRecipeRepository(DynamoDbEnhancedClient enhancedClient,
                                   @Value("${dynamodb.catalog-table}") String tableName) {
        this.table = enhancedClient.table(tableName, TableSchema.fromBean(CatalogRecipe.class));
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
}
