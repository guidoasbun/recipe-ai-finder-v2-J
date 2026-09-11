package io.asbun.backend.model;

import io.asbun.backend.model.enums.MealSlot;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;

/**
 * A single placement of a recipe on a date and meal slot within a {@link MealPlan}. Entries
 * are embedded in the plan item (§1.4 of the design): a plan is small and always read/written
 * as a whole, so one read renders the calendar and updates are atomic.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@DynamoDbBean
public class MealPlanEntry {

    /** UUID, unique within the plan. Lets move/remove target one entry precisely. */
    private String entryId;

    /** ISO-8601 date (yyyy-MM-dd) the entry is placed on. A calendar day, not an instant. */
    private String date;

    /** The meal slot. Stored as the {@link MealSlot} enum name. */
    private MealSlot slot;

    /** Which recipe this entry points to. */
    private RecipeRef recipeRef;

    /**
     * Optional per-entry servings override. When null, the plan-level {@code servings}
     * applies. Stored/displayed only in this spec — no quantity scaling.
     */
    private Integer servings;
}
