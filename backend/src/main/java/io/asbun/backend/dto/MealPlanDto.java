package io.asbun.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Public view of a {@link io.asbun.backend.model.MealPlan}. Entries are resolved to display
 * views ({@link MealPlanEntryDto}). The reserved {@code members} field is intentionally not
 * exposed (unused in Meal Plan Core).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MealPlanDto {

    private String mealPlanId;
    private String ownerUserId;
    private String name;
    private String startDate;
    private String endDate;
    private Integer servings;

    /**
     * Resolved entries. Present on single-plan responses (get / default / mutations). The list
     * endpoint returns summaries with {@code entries == null} and {@code entryCount} set, to
     * avoid resolving every recipe of every plan (an O(plans × entries) fan-out of
     * DynamoDB/OpenSearch lookups) just to render a list.
     */
    private List<MealPlanEntryDto> entries;

    /** Number of entries in the plan. Always set; lets the list view show size without entries. */
    private Integer entryCount;

    private Instant createdAt;
    private Instant updatedAt;
}
