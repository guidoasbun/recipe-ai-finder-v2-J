package io.asbun.backend.repository;

import io.asbun.backend.model.MealPlan;
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

/**
 * DynamoDB access for {@link MealPlan}, mirroring {@code RecipeRepository}: get/put/delete by
 * plan id, and list-by-owner via the {@code ownerUserId-index} GSI. Entries are embedded in
 * the plan item, so a plan is always read and written as a whole.
 */
@Repository
public class MealPlanRepository {

    private final DynamoDbTable<MealPlan> table;
    private final DynamoDbIndex<MealPlan> ownerIndex;

    public MealPlanRepository(DynamoDbEnhancedClient enhancedClient,
                              @Value("${dynamodb.meal-plans-table}") String tableName) {
        this.table = enhancedClient.table(tableName, TableSchema.fromBean(MealPlan.class));
        this.ownerIndex = table.index("ownerUserId-index");
    }

    public MealPlan save(MealPlan mealPlan) {
        table.putItem(mealPlan);
        return mealPlan;
    }

    public Optional<MealPlan> findById(String mealPlanId) {
        Key key = Key.builder().partitionValue(mealPlanId).build();
        return Optional.ofNullable(table.getItem(key));
    }

    public List<MealPlan> findByOwner(String ownerUserId) {
        Key key = Key.builder().partitionValue(ownerUserId).build();
        QueryConditional query = QueryConditional.keyEqualTo(key);
        return ownerIndex.query(query)
                .stream()
                .flatMap(page -> page.items().stream())
                .collect(Collectors.toList());
    }

    public void delete(String mealPlanId) {
        Key key = Key.builder().partitionValue(mealPlanId).build();
        table.deleteItem(key);
    }
}
