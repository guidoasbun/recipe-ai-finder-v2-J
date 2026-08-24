package io.asbun.backend.repository;

import io.asbun.backend.model.Consent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
public class ConsentRepository {

    private final DynamoDbTable<Consent> table;

    public ConsentRepository(DynamoDbEnhancedClient enhancedClient,
                             @Value("${dynamodb.consent-table}") String tableName) {
        this.table = enhancedClient.table(tableName, TableSchema.fromBean(Consent.class));
    }

    public Consent save(Consent consent) {
        table.putItem(consent);
        return consent;
    }

    public Optional<Consent> findByUserIdAndType(String userId, String consentType) {
        Key key = Key.builder()
                .partitionValue(userId)
                .sortValue(consentType)
                .build();
        return Optional.ofNullable(table.getItem(key));
    }

    public List<Consent> findAllByUserId(String userId) {
        Key key = Key.builder().partitionValue(userId).build();
        QueryConditional query = QueryConditional.keyEqualTo(key);
        return table.query(query)
                .stream()
                .flatMap(page -> page.items().stream())
                .collect(Collectors.toList());
    }

    public void delete(String userId, String consentType) {
        Key key = Key.builder()
                .partitionValue(userId)
                .sortValue(consentType)
                .build();
        table.deleteItem(key);
    }
}
