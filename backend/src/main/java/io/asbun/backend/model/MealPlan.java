package io.asbun.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.extensions.annotations.DynamoDbVersionAttribute;

import java.time.Instant;
import java.util.List;

/**
 * A user's meal plan: a calendar of recipe placements ({@link MealPlanEntry}) across days and
 * meal slots. Stored in its own table ({@code dynamodb.meal-plans-table}). One item per plan;
 * entries are embedded (design §1.4).
 *
 * <p>Ownership is the scalar {@code ownerUserId} (also the GSI key), so a user's plans are a
 * single {@code keyEqualTo} query — mirroring {@code Recipe}'s {@code userId-index}. Sharing
 * lands later via the reserved {@code members} list without changing {@code ownerUserId} or
 * the index.
 *
 * <p>Dates ({@code startDate}, {@code endDate}, and each entry's {@code date}) are ISO-8601
 * day strings ({@code yyyy-MM-dd}), not {@code Instant}: an entry is a calendar day, so a
 * timestamp would drag timezone ambiguity into "what's on Tuesday".
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@DynamoDbBean
public class MealPlan {

    private String mealPlanId;
    private String ownerUserId;
    private String name;

    /** Optional plan range (ISO-8601 date). */
    private String startDate;
    private String endDate;

    /** Default number of people the plan feeds. Stored/displayed only — no quantity scaling. */
    private Integer servings;

    /** Embedded entries. */
    private List<MealPlanEntry> entries;

    private Instant createdAt;
    private Instant updatedAt;

    /**
     * Optimistic-locking version. Managed by the enhanced client's {@code VersionedRecordExtension}:
     * every {@code putItem} writes a conditional update requiring the stored version to match the
     * one loaded, then increments it. Concurrent read-modify-write of the embedded {@code entries}
     * list from two tabs no longer silently clobbers each other — the losing write fails its
     * condition and is retried against fresh state. Null on legacy items → treated as a first
     * write. The annotation lives on the getter (a method-target annotation, like the keys below).
     */
    private Long version;

    /**
     * RESERVED, UNUSED this spec: future shared/household-plan members. Absent on existing
     * plans (schemaless); adding it later is non-breaking. Not written or read here.
     */
    private List<PlanMember> members;

    @DynamoDbPartitionKey
    public String getMealPlanId() {
        return mealPlanId;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = "ownerUserId-index")
    public String getOwnerUserId() {
        return ownerUserId;
    }

    @DynamoDbVersionAttribute
    public Long getVersion() {
        return version;
    }
}
