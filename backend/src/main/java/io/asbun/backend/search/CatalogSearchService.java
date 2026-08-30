package io.asbun.backend.search;

import io.asbun.backend.dto.CatalogRecipeDto;

import java.util.Optional;

/**
 * The swap seam for catalog search. Phase 1 provides an in-app implementation; a future
 * OpenSearch implementation satisfies the same interface and is selected by the
 * {@code catalog.search.backend} property, requiring no controller/DTO/frontend changes.
 */
public interface CatalogSearchService {

    CatalogSearchResults search(CatalogSearchQuery query);

    Optional<CatalogRecipeDto> findById(String catalogRecipeId);
}
