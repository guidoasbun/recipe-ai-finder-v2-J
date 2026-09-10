package io.asbun.backend.search;

import io.asbun.backend.dto.RecipeDto;

import java.util.List;

/**
 * Implementation-neutral, paginated response for a saved-recipe search. Same shape as
 * {@link CatalogSearchResults} so the frontend consumes both consistently.
 *
 * @param items        the recipes on the requested page (already mapped to DTOs)
 * @param page         the 0-based page index that was returned
 * @param pageSize     the page size that was applied
 * @param totalMatches total number of the user's recipes matching the query (across all pages)
 */
public record SavedRecipeSearchResults(
        List<RecipeDto> items,
        int page,
        int pageSize,
        long totalMatches
) {}
