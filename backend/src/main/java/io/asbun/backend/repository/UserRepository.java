package io.asbun.backend.repository;

import io.asbun.backend.model.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

import java.util.Optional;

@Repository
public class UserRepository {
    
    private final DynamoDbTable<User> table;

    public UserRepository(DynamoDbEnhancedClient enhancedClient,
                          @Value("${dynamodb.users-table}") String tableName) {
        this.table = enhancedClient.table(tableName, TableSchema.fromBean(User.class));
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
}
