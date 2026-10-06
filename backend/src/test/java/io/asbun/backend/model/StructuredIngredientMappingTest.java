package io.asbun.backend.model;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the enhanced-client bean mapping for the additive {@code structuredIngredients}
 * field on {@link Recipe} and {@link CatalogRecipe} (Structured Ingredients spec, task 3).
 *
 * <p>Uses the enhanced client's {@link TableSchema} item mapper in-memory (no DynamoDB, no
 * network, no new test infrastructure) to assert that a recipe carrying a nested
 * {@link StructuredIngredient} list serializes to a DynamoDB item and reads back equal, and
 * that a recipe WITHOUT the field (older/un-backfilled data) round-trips with the list null —
 * the non-breaking read required by the spec.
 */
class StructuredIngredientMappingTest {

    private final TableSchema<Recipe> recipeSchema = TableSchema.fromBean(Recipe.class);
    private final TableSchema<CatalogRecipe> catalogSchema = TableSchema.fromBean(CatalogRecipe.class);

    @Test
    void recipeRoundTripsStructuredIngredients() {
        Recipe recipe = Recipe.builder()
                .recipeId("r-1")
                .userId("u-1")
                .title("Pancakes")
                .ingredients(List.of("2 cups flour", "1/2 tsp salt", "salt to taste"))
                .structuredIngredients(List.of(
                        StructuredIngredient.builder().quantity(2.0).unit("cup").item("flour").raw("2 cups flour").build(),
                        StructuredIngredient.builder().quantity(0.5).unit("tsp").item("salt").raw("1/2 tsp salt").build(),
                        StructuredIngredient.builder().item("salt to taste").raw("salt to taste").build()))
                .build();

        Map<String, AttributeValue> item = recipeSchema.itemToMap(recipe, true);
        // The nested list is actually persisted as its own attribute.
        assertThat(item).containsKey("structuredIngredients");

        Recipe back = recipeSchema.mapToItem(item);
        assertThat(back.getStructuredIngredients()).isEqualTo(recipe.getStructuredIngredients());
        // The authoritative string list is untouched.
        assertThat(back.getIngredients()).isEqualTo(recipe.getIngredients());
        // A null-quantity/unit entry round-trips with nulls preserved.
        StructuredIngredient toTaste = back.getStructuredIngredients().get(2);
        assertThat(toTaste.getQuantity()).isNull();
        assertThat(toTaste.getUnit()).isNull();
        assertThat(toTaste.getItem()).isEqualTo("salt to taste");
    }

    @Test
    void recipeWithoutStructuredIngredientsReadsBackNull() {
        Recipe recipe = Recipe.builder()
                .recipeId("r-2")
                .userId("u-1")
                .ingredients(List.of("2 cups flour"))
                .build();

        Map<String, AttributeValue> item = recipeSchema.itemToMap(recipe, true);
        Recipe back = recipeSchema.mapToItem(item);

        assertThat(back.getStructuredIngredients()).isNull();
        assertThat(back.getIngredients()).containsExactly("2 cups flour");
    }

    @Test
    void catalogRecipeRoundTripsStructuredIngredients() {
        CatalogRecipe recipe = CatalogRecipe.builder()
                .catalogRecipeId("c-1")
                .title("Omelette")
                .ingredients(List.of("3 eggs"))
                .structuredIngredients(List.of(
                        StructuredIngredient.builder().quantity(3.0).item("eggs").raw("3 eggs").build()))
                .build();

        Map<String, AttributeValue> item = catalogSchema.itemToMap(recipe, true);
        assertThat(item).containsKey("structuredIngredients");

        CatalogRecipe back = catalogSchema.mapToItem(item);
        assertThat(back.getStructuredIngredients()).isEqualTo(recipe.getStructuredIngredients());
        assertThat(back.getIngredients()).containsExactly("3 eggs");
    }

    @Test
    void catalogRecipeWithoutStructuredIngredientsReadsBackNull() {
        CatalogRecipe recipe = CatalogRecipe.builder()
                .catalogRecipeId("c-2")
                .title("Toast")
                .ingredients(List.of("1 slice bread"))
                .build();

        Map<String, AttributeValue> item = catalogSchema.itemToMap(recipe, true);
        CatalogRecipe back = catalogSchema.mapToItem(item);

        assertThat(back.getStructuredIngredients()).isNull();
        assertThat(back.getIngredients()).containsExactly("1 slice bread");
    }
}
