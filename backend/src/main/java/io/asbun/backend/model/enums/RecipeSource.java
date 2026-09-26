package io.asbun.backend.model.enums;

/**
 * Which recipe library a meal-plan entry references. Kept as an enum (rather than two
 * nullable id fields) so a future third library adds an enum value, not a schema change.
 */
public enum RecipeSource {
    /** A user's own saved {@code Recipe} (per-user, mutable). */
    SAVED,
    /** A shared read-only {@code CatalogRecipe}. */
    CATALOG
}
