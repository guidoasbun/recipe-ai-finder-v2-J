package io.asbun.backend.repository;

import io.asbun.backend.model.AuditEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

import java.util.List;
import java.util.stream.Collectors;

@Repository
public class AuditRepository {

    private final DynamoDbTable<AuditEvent> table;

    public AuditRepository(DynamoDbEnhancedClient enhancedClient,
                           @Value("${dynamodb.audit-table}") String tableName) {
        this.table = enhancedClient.table(tableName, TableSchema.fromBean(AuditEvent.class));
    }

    public AuditEvent save(AuditEvent auditEvent) {
        table.putItem(auditEvent);
        return auditEvent;
    }

    public List<AuditEvent> findByUserId(String userId) {
        DynamoDbIndex<AuditEvent> index = table.index("userId-timestamp-index");
        Key key = Key.builder().partitionValue(userId).build();
        QueryConditional queryConditional = QueryConditional.keyEqualTo(key);
        QueryEnhancedRequest request = QueryEnhancedRequest.builder()
                .queryConditional(queryConditional)
                .scanIndexForward(false)
                .build();
        return index.query(request)
                .stream()
                .flatMap(page -> page.items().stream())
                .collect(Collectors.toList());
    }
}
