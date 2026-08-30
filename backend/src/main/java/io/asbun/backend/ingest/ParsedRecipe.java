package io.asbun.backend.ingest;

import java.util.List;

/**
 * A normalized recipe emitted by a {@link RecipeSource}, before dietary tagging and
 * embedding. {@code sourceId} is a stable identifier within a source (used to build the
 * deterministic catalog id for idempotent ingestion).
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
        String sourceCountry
) {}
