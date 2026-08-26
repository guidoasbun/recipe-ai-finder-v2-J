package io.asbun.backend.model;

import io.asbun.backend.model.enums.AccountStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@DynamoDbBean
public class User {

    private String userId;
    private String email;
    private String username;
    private Instant createdAt;
    private Integer generateCallsUsed;
    @Builder.Default
    private AccountStatus accountStatus = AccountStatus.ACTIVE;
    private Instant deletionRequestedAt;
    private Instant scheduledDeletionDate;
    @Builder.Default
    private List<String> dietaryRestrictions = new ArrayList<>();

    @DynamoDbPartitionKey
    public String getUserId() {
        return userId;
    }

}
