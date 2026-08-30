package io.asbun.backend.search;

import io.asbun.backend.dto.CatalogRecipeDto;

import java.util.List;

/**
 * Implementation-neutral search response with pagination metadata.
 */
public record CatalogSearchResults(
        List<CatalogRecipeDto> items,
        int page,
        int pageSize,
        long totalMatches
) {}
