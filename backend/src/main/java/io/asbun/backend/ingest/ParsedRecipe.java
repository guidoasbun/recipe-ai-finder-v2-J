package io.asbun.backend.ingest;

import io.asbun.backend.model.StructuredIngredient;

import java.util.List;

/**
 * A normalized recipe emitted by a {@link RecipeSource}, before dietary tagging and
 * embedding. {@code sourceId} is a stable identifier within a source (used to build the
 * deterministic catalog id for idempotent ingestion).
 *
 * <p>{@code structuredIngredients} is optional (Structured Ingredients spec §5.1): a source
 * that already has the ingredient amounts separated (e.g. TheMealDB's xlsx columns) may emit
 * them directly; sources that only have inline strings leave it null and the ingestion runner
 * derives the structure from {@code ingredients} via the parser. When present it corresponds
 * positionally and in count to {@code ingredients}.
 */
public record ParsedRecipe(
        String sourceId,
        String title,
        String description,
        List<String> ingredients,
        List<String> steps,
        String imageUrl,
        String sourceName,
        String sourceUrl,
        String sourceLicense,
        String sourceCountry,
        List<StructuredIngredient> structuredIngredients
) {

    /**
     * Convenience constructor for sources with no pre-separated structure: leaves
     * {@code structuredIngredients} null so the ingestion runner derives it from the strings.
     * Keeps existing 10-arg call sites unchanged.
     */
    public ParsedRecipe(
            String sourceId,
            String title,
            String description,
            List<String> ingredients,
            List<String> steps,
            String imageUrl,
            String sourceName,
            String sourceUrl,
            String sourceLicense,
            String sourceCountry) {
        this(sourceId, title, description, ingredients, steps, imageUrl,
                sourceName, sourceUrl, sourceLicense, sourceCountry, null);
    }
}
