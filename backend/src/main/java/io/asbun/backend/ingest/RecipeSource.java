package io.asbun.backend.ingest;

import java.util.List;
import java.util.function.Consumer;

/**
 * A dataset source that yields normalized recipes for catalog ingestion. Each dataset
 * (TheMealDB xlsx export, AllRecipes CSV, and RecipeNLG) implements this so the ingestion
 * runner is dataset-agnostic.
 */
public interface RecipeSource {

    /** Short name for logging/attribution, e.g. "TheMealDB". */
    String name();

    /** Reads and normalizes all recipes from this source. */
    List<ParsedRecipe> load();

    /**
     * Streams normalized recipes one at a time to {@code consumer}, without materializing the
     * whole source in memory. The default implementation delegates to {@link #load()} (fine for
     * the small Phase 1 sources); large sources like RecipeNLG override this to stream directly
     * so a multi-million-row dataset never builds a full in-memory list.
     */
    default void stream(Consumer<ParsedRecipe> consumer) {
        load().forEach(consumer);
    }
}
