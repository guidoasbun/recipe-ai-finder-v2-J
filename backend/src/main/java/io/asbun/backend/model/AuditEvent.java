package io.asbun.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@DynamoDbBean
public class AuditEvent {

    private String auditId;
    private String userId;
    private String eventType;
    private Map<String, String> details;
    private String timestamp;
    private String ipAddress;
    private String userAgent;
    private Long ttl;

    @DynamoDbPartitionKey
    public String getAuditId() {
        return auditId;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = "userId-timestamp-index")
    public String getUserId() {
        return userId;
    }

    @DynamoDbSecondarySortKey(indexNames = "userId-timestamp-index")
    public String getTimestamp() {
        return timestamp;
    }

}
