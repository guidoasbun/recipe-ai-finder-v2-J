package io.asbun.backend.repository;

import io.asbun.backend.model.MealPlan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * DynamoDB access for {@link MealPlan}, mirroring {@code RecipeRepository}: get/put/delete by
 * plan id, and list-by-owner via the {@code ownerUserId-index} GSI. Entries are embedded in
 * the plan item, so a plan is always read and written as a whole.
 *
 * <p>Writes go through the enhanced client's version-aware {@code putItem}: {@link MealPlan}
 * carries a {@code @DynamoDbVersionAttribute}, so every save is a conditional write that fails
 * (throwing {@code ConditionalCheckFailedException}) if another writer changed the item since
 * it was loaded. Callers retry against fresh state — this is how concurrent entry mutations
 * avoid last-writer-wins clobbering.
 */
@Repository
public class MealPlanRepository {

    private final DynamoDbTable<MealPlan> table;
    private final DynamoDbIndex<MealPlan> ownerIndex;

    private final DynamoDbClient dynamoDbClient;
    private final String tableName;

    /**
     * Partition-key prefix for the per-user plan-count item. Prefixed so it can never collide
     * with a real {@code mealPlanId} (a UUID) and is naturally excluded from the owner GSI
     * (the counter item carries no {@code ownerUserId}), so {@code findByOwner} never sees it.
     */
    private static final String COUNTER_PK_PREFIX = "COUNT#";

    public MealPlanRepository(DynamoDbEnhancedClient enhancedClient,
                              DynamoDbClient dynamoDbClient,
                              @Value("${dynamodb.meal-plans-table}") String tableName) {
        this.table = enhancedClient.table(tableName, TableSchema.fromBean(MealPlan.class));
        this.ownerIndex = table.index("ownerUserId-index");
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tableName;
    }

    /**
     * Version-guarded put. The enhanced client derives the conditional from the item's
     * {@code @DynamoDbVersionAttribute}: it requires the stored version to equal the loaded one
     * (or the item to be absent for a first write) and then increments it. A stale write throws
     * {@code ConditionalCheckFailedException}.
     */
    public MealPlan save(MealPlan mealPlan) {
        table.putItem(mealPlan);
        return mealPlan;
    }

    /**
     * Creates the item only if no item with the same partition key exists yet. Used for the
     * deterministic per-user default calendar: two concurrent first-loads race on the same
     * {@code mealPlanId}; exactly one put succeeds and the loser gets
     * {@code ConditionalCheckFailedException} and reloads the winner.
     */
    public MealPlan createIfAbsent(MealPlan mealPlan) {
        Expression notExists = Expression.builder()
                .expression("attribute_not_exists(mealPlanId)")
                .build();
        table.putItem(PutItemEnhancedRequest.builder(MealPlan.class)
                .item(mealPlan)
                .conditionExpression(notExists)
                .build());
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

    // ── Atomic per-user plan counter ────────────────────────────────────────────

    /**
     * Atomically reserves a plan slot for the user: increments the per-user counter only if it
     * is still below {@code maxPerUser}, in a single conditional {@code UpdateItem}. Parallel
     * create requests can no longer all read a count below the cap and then each write — the
     * increment itself is the check, so at most {@code maxPerUser} reservations ever succeed.
     *
     * <p>Mirrors {@code UserRepository.atomicIncrementGenerateCalls}, adding a condition that
     * the current value (default 0 when absent) is under the cap. A rejected reservation throws
     * {@code ConditionalCheckFailedException}; the caller maps that to a limit error.
     */
    public void reservePlanSlot(String ownerUserId, int maxPerUser) {
        dynamoDbClient.updateItem(UpdateItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("mealPlanId",
                        AttributeValue.builder().s(COUNTER_PK_PREFIX + ownerUserId).build()))
                .updateExpression("ADD planCount :one")
                .conditionExpression("attribute_not_exists(planCount) OR planCount < :max")
                .expressionAttributeValues(Map.of(
                        ":one", AttributeValue.builder().n("1").build(),
                        ":max", AttributeValue.builder().n(Integer.toString(maxPerUser)).build()))
                .build());
    }

    /**
     * Releases a previously reserved plan slot (decrement), clamped at zero so a spurious extra
     * delete can't drive the counter negative and permanently under-count the user. Called on
     * plan delete and to roll back a reservation whose subsequent create failed.
     */
    public void releasePlanSlot(String ownerUserId) {
        dynamoDbClient.updateItem(UpdateItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("mealPlanId",
                        AttributeValue.builder().s(COUNTER_PK_PREFIX + ownerUserId).build()))
                .updateExpression("ADD planCount :negOne")
                .conditionExpression("attribute_exists(planCount) AND planCount > :zero")
                .expressionAttributeValues(Map.of(
                        ":negOne", AttributeValue.builder().n("-1").build(),
                        ":zero", AttributeValue.builder().n("0").build()))
                .build());
    }
}
