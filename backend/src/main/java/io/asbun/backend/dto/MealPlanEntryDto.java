package io.asbun.backend.dto;

import io.asbun.backend.model.enums.MealSlot;
import io.asbun.backend.model.enums.RecipeSource;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A plan entry with its recipe reference resolved to a slim display view. The resolved fields
 * (title/imageUrl) are best-effort: when the referenced recipe is missing/inaccessible,
 * {@code available} is false and the display fields are null, so the UI shows a placeholder
 * rather than breaking (design §3.3).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MealPlanEntryDto {

    private String entryId;
    private String date;
    private MealSlot slot;
    private Integer servings;

    // Recipe reference + resolved display view
    private RecipeSource recipeSource;
    private String recipeId;
    private boolean available;
    private String title;
    private String imageUrl;
}
