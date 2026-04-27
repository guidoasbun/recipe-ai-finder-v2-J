package io.asbun.backend.repository;

import io.asbun.backend.model.Recipe;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
public class RecipeRepository {
    
    private final DynamoDbTable<Recipe> table;
    private final DynamoDbIndex<Recipe> userIndex;

    public RecipeRepository(DynamoDbEnhancedClient enhancedClient,
                            @Value("${dynamodb.recipes-table}") String tableName) {
        this.table = enhancedClient.table(tableName, TableSchema.fromBean(Recipe.class));
        this.userIndex = table.index("userId-index");
    }

    public Recipe save(Recipe recipe) {
        table.putItem(recipe);
        return recipe;
    }

    public Optional<Recipe> findById(String recipeId) {
        Key key = Key.builder().partitionValue(recipeId).build();
        return Optional.ofNullable(table.getItem(key));
    }

    public List<Recipe> findByUserId(String userId) {
        Key key = Key.builder().partitionValue(userId).build();
        QueryConditional query = QueryConditional.keyEqualTo(key);
        return userIndex.query(query)
                .stream()
                .flatMap(page -> page.items().stream())
                .collect(Collectors.toList());
    }

    public void delete(String recipeId) {
        Key key = Key.builder().partitionValue(recipeId).build();
        table.deleteItem(key);
    }
}
