package io.asbun.backend.search;

import java.util.List;

/**
 * Implementation-neutral search request. Dietary tags are resolved by the controller
 * (from the user's saved restrictions or per-search overrides) before reaching the service.
 *
 * @param text        search text; null/blank means browse (no keyword filter)
 * @param dietaryTags DietaryRestriction names that results must all satisfy; may be empty
 * @param page        0-based page index
 * @param pageSize    bounded page size
 */
public record CatalogSearchQuery(
        String text,
        List<String> dietaryTags,
        int page,
        int pageSize
) {}
