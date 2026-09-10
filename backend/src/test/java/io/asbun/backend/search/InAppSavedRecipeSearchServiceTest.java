package io.asbun.backend.search;

import io.asbun.backend.dto.RecipeDto;
import io.asbun.backend.model.Recipe;
import io.asbun.backend.repository.RecipeRepository;
import io.asbun.backend.service.RecipeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for InAppSavedRecipeSearchService: text filtering (title/description/ingredients),
 * newest-first ordering, pagination slicing, and out-of-range handling.
 *
 * Feature: saved-recipe-search
 * Validates: Requirements 1.4, 1.5, 3.2, 3.6, 5.2
 */
class InAppSavedRecipeSearchServiceTest {

    private static final String USER_ID = "user-123";

    private RecipeRepository repository;
    private RecipeService recipeService;
    private InAppSavedRecipeSearchService service;

    @BeforeEach
    void setUp() {
        repository = mock(RecipeRepository.class);
        recipeService = mock(RecipeService.class);
        service = new InAppSavedRecipeSearchService(repository, recipeService);

        // Map each Recipe to a minimal DTO carrying the id/title so assertions can read them back.
        when(recipeService.toDtoFor(any(Recipe.class))).thenAnswer(inv -> {
            Recipe r = inv.getArgument(0);
            return RecipeDto.builder()
                    .recipeId(r.getRecipeId())
                    .title(r.getTitle())
                    .build();
        });
    }

    private Recipe recipe(String id, String title, String description,
                          List<String> ingredients, Instant createdAt) {
        return Recipe.builder()
                .recipeId(id)
                .userId(USER_ID)
                .title(title)
                .description(description)
                .ingredients(ingredients)
                .steps(List.of())
                .createdAt(createdAt)
                .build();
    }

    @Test
    void blankQuery_returnsAllRecipes() {
        when(repository.findByUserId(USER_ID)).thenReturn(List.of(
                recipe("1", "Chicken Curry", null, List.of(), Instant.parse("2026-01-01T00:00:00Z")),
                recipe("2", "Beef Stew", null, List.of(), Instant.parse("2026-01-02T00:00:00Z"))));

        SavedRecipeSearchResults results =
                service.search(new SavedRecipeSearchQuery(USER_ID, "  ", 0, 6));

        assertThat(results.totalMatches()).isEqualTo(2);
        assertThat(results.items()).hasSize(2);
    }

    @Test
    void matchesTitle_caseInsensitive() {
        when(repository.findByUserId(USER_ID)).thenReturn(List.of(
                recipe("1", "Chicken Curry", null, List.of(), Instant.parse("2026-01-01T00:00:00Z")),
                recipe("2", "Beef Stew", null, List.of(), Instant.parse("2026-01-02T00:00:00Z"))));

        SavedRecipeSearchResults results =
                service.search(new SavedRecipeSearchQuery(USER_ID, "CHICKEN", 0, 6));

        assertThat(results.items()).extracting(RecipeDto::getTitle).containsExactly("Chicken Curry");
        assertThat(results.totalMatches()).isEqualTo(1);
    }

    @Test
    void matchesDescription() {
        when(repository.findByUserId(USER_ID)).thenReturn(List.of(
                recipe("1", "Weeknight Dinner", "A quick chicken dish", List.of(),
                        Instant.parse("2026-01-01T00:00:00Z")),
                recipe("2", "Beef Stew", "Slow-cooked beef", List.of(),
                        Instant.parse("2026-01-02T00:00:00Z"))));

        SavedRecipeSearchResults results =
                service.search(new SavedRecipeSearchQuery(USER_ID, "chicken", 0, 6));

        assertThat(results.items()).extracting(RecipeDto::getTitle).containsExactly("Weeknight Dinner");
    }

    @Test
    void matchesIngredient() {
        when(repository.findByUserId(USER_ID)).thenReturn(List.of(
                recipe("1", "Mystery Dish", null, List.of("2 cups flour", "1 lb chicken breast"),
                        Instant.parse("2026-01-01T00:00:00Z")),
                recipe("2", "Salad", null, List.of("lettuce", "tomato"),
                        Instant.parse("2026-01-02T00:00:00Z"))));

        SavedRecipeSearchResults results =
                service.search(new SavedRecipeSearchQuery(USER_ID, "chicken", 0, 6));

        assertThat(results.items()).extracting(RecipeDto::getTitle).containsExactly("Mystery Dish");
    }

    @Test
    void ordersNewestFirst() {
        when(repository.findByUserId(USER_ID)).thenReturn(List.of(
                recipe("old", "Old", null, List.of(), Instant.parse("2026-01-01T00:00:00Z")),
                recipe("new", "New", null, List.of(), Instant.parse("2026-03-01T00:00:00Z")),
                recipe("mid", "Mid", null, List.of(), Instant.parse("2026-02-01T00:00:00Z"))));

        SavedRecipeSearchResults results =
                service.search(new SavedRecipeSearchQuery(USER_ID, null, 0, 6));

        assertThat(results.items()).extracting(RecipeDto::getRecipeId)
                .containsExactly("new", "mid", "old");
    }

    @Test
    void nullCreatedAt_sortsLast() {
        when(repository.findByUserId(USER_ID)).thenReturn(List.of(
                recipe("dated", "Dated", null, List.of(), Instant.parse("2026-01-01T00:00:00Z")),
                recipe("undated", "Undated", null, List.of(), null)));

        SavedRecipeSearchResults results =
                service.search(new SavedRecipeSearchQuery(USER_ID, null, 0, 6));

        assertThat(results.items()).extracting(RecipeDto::getRecipeId)
                .containsExactly("dated", "undated");
    }

    @Test
    void paginatesFirstPage() {
        when(repository.findByUserId(USER_ID)).thenReturn(manyRecipes(15));

        SavedRecipeSearchResults results =
                service.search(new SavedRecipeSearchQuery(USER_ID, null, 0, 6));

        assertThat(results.items()).hasSize(6);
        assertThat(results.totalMatches()).isEqualTo(15);
        assertThat(results.page()).isZero();
        assertThat(results.pageSize()).isEqualTo(6);
        // Newest-first: recipe 14 is the most recent (largest timestamp).
        assertThat(results.items().get(0).getRecipeId()).isEqualTo("r14");
    }

    @Test
    void paginatesLastPartialPage() {
        when(repository.findByUserId(USER_ID)).thenReturn(manyRecipes(15));

        // 15 items, page size 6 => pages 0,1 have 6, page 2 has the remaining 3.
        SavedRecipeSearchResults results =
                service.search(new SavedRecipeSearchQuery(USER_ID, null, 2, 6));

        assertThat(results.items()).hasSize(3);
        assertThat(results.totalMatches()).isEqualTo(15);
        assertThat(results.page()).isEqualTo(2);
    }

    @Test
    void outOfRangePage_returnsEmptyItemsWithTrueTotal() {
        when(repository.findByUserId(USER_ID)).thenReturn(manyRecipes(7));

        SavedRecipeSearchResults results =
                service.search(new SavedRecipeSearchQuery(USER_ID, null, 99, 6));

        assertThat(results.items()).isEmpty();
        assertThat(results.totalMatches()).isEqualTo(7);
        assertThat(results.page()).isEqualTo(99);
    }

    @Test
    void tiedCreatedAt_ordersDeterministicallyByRecipeId() {
        // Three recipes share a createdAt; without a tie-breaker their order is unspecified and
        // could shift between page requests. recipeId ascending makes it stable.
        java.time.Instant t = Instant.parse("2026-01-01T00:00:00Z");
        when(repository.findByUserId(USER_ID)).thenReturn(List.of(
                recipe("c", "C", null, List.of(), t),
                recipe("a", "A", null, List.of(), t),
                recipe("b", "B", null, List.of(), t)));

        SavedRecipeSearchResults results =
                service.search(new SavedRecipeSearchQuery(USER_ID, null, 0, 6));

        assertThat(results.items()).extracting(RecipeDto::getRecipeId)
                .containsExactly("a", "b", "c");
    }

    @Test
    void hugePageNumber_returnsEmptyPageWithoutOverflow() {
        // Regression: (long) page * pageSize must not overflow int and make subList throw.
        when(repository.findByUserId(USER_ID)).thenReturn(manyRecipes(3));

        SavedRecipeSearchResults results =
                service.search(new SavedRecipeSearchQuery(USER_ID, null, Integer.MAX_VALUE, 20));

        assertThat(results.items()).isEmpty();
        assertThat(results.totalMatches()).isEqualTo(3);
    }

    @Test
    void noRecipes_returnsEmpty() {
        when(repository.findByUserId(USER_ID)).thenReturn(List.of());

        SavedRecipeSearchResults results =
                service.search(new SavedRecipeSearchQuery(USER_ID, null, 0, 6));

        assertThat(results.items()).isEmpty();
        assertThat(results.totalMatches()).isZero();
    }

    /** n recipes with strictly increasing timestamps (r0 oldest .. r{n-1} newest). */
    private List<Recipe> manyRecipes(int n) {
        Recipe[] arr = new Recipe[n];
        for (int i = 0; i < n; i++) {
            arr[i] = recipe("r" + i, "Recipe " + i, null, List.of(),
                    Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i * 3600L));
        }
        return List.of(arr);
    }
}
