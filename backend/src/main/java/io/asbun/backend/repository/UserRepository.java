package io.asbun.backend.repository;

import io.asbun.backend.model.User;
import io.asbun.backend.model.enums.AccountStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.ScanEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
public class UserRepository {

    private final DynamoDbTable<User> table;
    private final DynamoDbClient dynamoDbClient;
    private final String tableName;

    public UserRepository(DynamoDbEnhancedClient enhancedClient,
                          DynamoDbClient dynamoDbClient,
                          @Value("${dynamodb.users-table}") String tableName) {
        this.table = enhancedClient.table(tableName, TableSchema.fromBean(User.class));
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tableName;
    }

    public User save(User user) {
        table.putItem(user);
        return user;
    }

    public Optional<User> findById(String userId) {
        Key key = Key.builder().partitionValue(userId).build();
        return Optional.ofNullable(table.getItem(key));
    }
    
    public void delete(String userId) {
        Key key = Key.builder().partitionValue(userId).build();
        table.deleteItem(key);
    }

    public void atomicIncrementGenerateCalls(String userId) {
        dynamoDbClient.updateItem(UpdateItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("userId", AttributeValue.builder().s(userId).build()))
                .updateExpression("ADD generateCallsUsed :inc")
                .expressionAttributeValues(Map.of(":inc", AttributeValue.builder().n("1").build()))
                .build());
    }

    public List<User> findPendingDeletions() {
        Expression filterExpression = Expression.builder()
                .expression("accountStatus = :pending OR accountStatus = :failed")
                .expressionValues(Map.of(
                        ":pending", AttributeValue.builder().s(AccountStatus.PENDING_DELETION.name()).build(),
                        ":failed", AttributeValue.builder().s(AccountStatus.DELETION_FAILED.name()).build()
                ))
                .build();

        ScanEnhancedRequest scanRequest = ScanEnhancedRequest.builder()
                .filterExpression(filterExpression)
                .build();

        return table.scan(scanRequest)
                .stream()
                .flatMap(page -> page.items().stream())
                .collect(Collectors.toList());
    }
}
