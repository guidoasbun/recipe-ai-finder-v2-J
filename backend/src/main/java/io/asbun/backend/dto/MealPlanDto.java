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
    private List<MealPlanEntryDto> entries;
    private Instant createdAt;
    private Instant updatedAt;
}
