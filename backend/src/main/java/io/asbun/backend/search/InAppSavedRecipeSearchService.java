package io.asbun.backend.search;

import io.asbun.backend.dto.RecipeDto;
import io.asbun.backend.model.Recipe;
import io.asbun.backend.repository.RecipeRepository;
import io.asbun.backend.service.RecipeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Default, in-app implementation of {@link SavedRecipeSearchService}.
 *
 * <p>Saved recipes are per-user (tens to low-hundreds per user), so search is a cheap in-memory
 * operation: query the user's own items via the {@code userId-index} GSI, filter by the search
 * text (title / description / ingredients, case-insensitive), sort newest-first by
 * {@code createdAt}, then slice out the requested page. Only the page's items are mapped to DTOs
 * (via {@link RecipeService#toDtoFor}) so the S3 presign / lazy image-regeneration work runs for
 * ~{@code pageSize} recipes rather than the whole collection.
 *
 * <p>This deliberately does not use OpenSearch: at per-user scale an index adds latency, cost,
 * and sync complexity for no benefit. The {@link SavedRecipeSearchService} interface remains the
 * seam for swapping in an OpenSearch implementation later if a collection ever grows large.
 */
@Service
@RequiredArgsConstructor
public class InAppSavedRecipeSearchService implements SavedRecipeSearchService {

    private final RecipeRepository recipeRepository;
    private final RecipeService recipeService;

    @Override
    public SavedRecipeSearchResults search(SavedRecipeSearchQuery query) {
        int pageSize = Math.max(1, query.pageSize());
        int page = Math.max(0, query.page());

        List<Recipe> matches = recipeRepository.findByUserId(query.userId())
                .stream()
                .filter(recipe -> matchesText(recipe, query.text()))
                // Newest-first, preserving the page's prior client-side sort. Nulls last so a
                // recipe missing createdAt never jumps ahead of dated ones.
                .sorted(Comparator.comparing(Recipe::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());

        long totalMatches = matches.size();

        int from = page * pageSize;
        if (from >= matches.size()) {
            // Page is beyond the available range: empty items, but still report the true total
            // and echo the requested page/size so the UI can correct itself.
            return new SavedRecipeSearchResults(List.of(), page, pageSize, totalMatches);
        }
        int to = Math.min(from + pageSize, matches.size());

        List<RecipeDto> items = matches.subList(from, to).stream()
                .map(recipeService::toDtoFor)
                .collect(Collectors.toList());

        return new SavedRecipeSearchResults(items, page, pageSize, totalMatches);
    }

    /**
     * Case-insensitive substring match over the recipe's title, description, and ingredients.
     * A null/blank query matches everything (browse the full listing).
     */
    private boolean matchesText(Recipe recipe, String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        String needle = text.toLowerCase(Locale.ROOT).trim();

        if (containsIgnoreCase(recipe.getTitle(), needle)
                || containsIgnoreCase(recipe.getDescription(), needle)) {
            return true;
        }
        List<String> ingredients = recipe.getIngredients();
        if (ingredients != null) {
            for (String ingredient : ingredients) {
                if (containsIgnoreCase(ingredient, needle)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean containsIgnoreCase(String haystack, String lowerNeedle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(lowerNeedle);
    }
}
