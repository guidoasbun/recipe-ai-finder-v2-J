package io.asbun.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;

/**
 * RESERVED, UNUSED in Meal Plan Core. Present so the future shared/household-plans feature
 * (see ROADMAP) can add members to a plan without a breaking migration: {@code ownerUserId}
 * stays the authoritative owner and GSI key, and access checks extend to include members
 * then. Not written or read in this spec.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@DynamoDbBean
public class PlanMember {

    private String userId;

    /** e.g. VIEWER / EDITOR when sharing lands. Free-form for now (unused). */
    private String role;
}
