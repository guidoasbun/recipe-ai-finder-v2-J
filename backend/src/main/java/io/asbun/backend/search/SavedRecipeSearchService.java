package io.asbun.backend.search;

/**
 * The swap seam for saved-recipe search (the Saved Recipes page). The default implementation
 * queries DynamoDB for the user's own recipes and filters/sorts/paginates them in memory — no
 * OpenSearch and no always-on infrastructure. A future OpenSearch-backed implementation can
 * satisfy the same interface and be selected by configuration, requiring no controller or
 * frontend changes (mirrors {@link CatalogSearchService}).
 */
public interface SavedRecipeSearchService {

    SavedRecipeSearchResults search(SavedRecipeSearchQuery query);
}
