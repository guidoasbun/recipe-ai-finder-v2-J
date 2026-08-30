package io.asbun.backend.ingest;

import java.util.List;

/**
 * A dataset source that yields normalized recipes for catalog ingestion. Each dataset
 * (TheMealDB xlsx export, AllRecipes CSV, and future RecipeNLG subset) implements this so
 * the ingestion runner is dataset-agnostic.
 */
public interface RecipeSource {

    /** Short name for logging/attribution, e.g. "TheMealDB". */
    String name();

    /** Reads and normalizes all recipes from this source. */
    List<ParsedRecipe> load();
}
