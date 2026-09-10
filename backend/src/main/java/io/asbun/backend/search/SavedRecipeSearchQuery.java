package io.asbun.backend.search;

/**
 * Implementation-neutral request for searching a single user's saved recipes.
 *
 * <p>Scoped to one user: the {@code userId} bounds the search to that caller's own recipes.
 * The controller resolves the {@code userId} from the JWT and clamps {@code page}/{@code pageSize}
 * before this record reaches the service.
 *
 * @param userId   the owner whose saved recipes are searched (from the JWT {@code sub})
 * @param text     search text; null/blank means "no filter" (return the full listing)
 * @param page     0-based page index
 * @param pageSize bounded page size
 */
public record SavedRecipeSearchQuery(
        String userId,
        String text,
        int page,
        int pageSize
) {}
