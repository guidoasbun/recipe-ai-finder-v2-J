package io.asbun.backend.model;

import io.asbun.backend.model.enums.RecipeSource;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;

/**
 * A reference to a recipe in one of the two libraries, without copying its content. Recipe
 * content is resolved at read time (so edits/deletes to the source are reflected, and a
 * missing recipe degrades gracefully). Embedded in {@link MealPlanEntry}.
 *
 * <p>{@code source} + {@code recipeId} (rather than two nullable id fields) means a future
 * third library adds a {@link RecipeSource} value, not a new field.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@DynamoDbBean
public class RecipeRef {

    /** Which library the recipe comes from. Stored as the enum name. */
    private RecipeSource source;

    /** {@code Recipe.recipeId} when {@code SAVED}, {@code CatalogRecipe.catalogRecipeId} when {@code CATALOG}. */
    private String recipeId;
}
