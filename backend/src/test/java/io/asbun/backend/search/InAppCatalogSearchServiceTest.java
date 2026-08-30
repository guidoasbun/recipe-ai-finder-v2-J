package io.asbun.backend.search;

import io.asbun.backend.dto.CatalogRecipeDto;
import io.asbun.backend.model.CatalogRecipe;
import io.asbun.backend.repository.CatalogRecipeRepository;
import io.asbun.backend.service.EmbeddingService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for InAppCatalogSearchService: keyword ranking, dietary filtering, browse,
 * pagination, and semantic-failure fallback.
 *
 * Feature: existing-recipe-search
 * Validates: Requirements 2.1-2.6, 3.3, 3.4, 4.2, 7.2
 */
class InAppCatalogSearchServiceTest {

    private CatalogRecipe recipe(String id, String title, List<String> tags) {
        return CatalogRecipe.builder()
                .catalogRecipeId(id)
                .title(title)
                .searchText(title.toLowerCase())
                .dietaryTags(tags)
                .ingredients(List.of())
                .steps(List.of())
                .build();
    }

    private CatalogRecipeRepository repoWith(CatalogRecipe... recipes) {
        CatalogRecipeRepository repo = mock(CatalogRecipeRepository.class);
        when(repo.findAll()).thenReturn(List.of(recipes));
        return repo;
    }

    private InAppCatalogSearchService keywordService(CatalogRecipeRepository repo) {
        // semantic disabled, keyword mode: no Bedrock calls
        return new InAppCatalogSearchService(repo, mock(EmbeddingService.class), false, "keyword");
    }

    @Test
    void keywordSearch_ranksTitleMatchesFirst() {
        CatalogRecipeRepository repo = repoWith(
                recipe("1", "Chicken Curry", List.of()),
                recipe("2", "Beef Stew", List.of()),
                recipe("3", "Grilled Chicken Salad", List.of()));

        InAppCatalogSearchService svc = keywordService(repo);
        CatalogSearchResults results = svc.search(new CatalogSearchQuery("chicken", List.of(), 0, 10));

        assertThat(results.items()).extracting(CatalogRecipeDto::getTitle)
                .allMatch(t -> t.toLowerCase().contains("chicken"));
        assertThat(results.totalMatches()).isEqualTo(2);
    }

    @Test
    void dietaryFilter_excludesRecipesMissingAllTags() {
        CatalogRecipeRepository repo = repoWith(
                recipe("1", "Vegan Bowl", List.of("VEGAN", "DAIRY_FREE")),
                recipe("2", "Cheese Pizza", List.of("VEGETARIAN")),
                recipe("3", "Fruit Salad", List.of("VEGAN", "GLUTEN_FREE")));

        InAppCatalogSearchService svc = keywordService(repo);
        CatalogSearchResults results =
                svc.search(new CatalogSearchQuery(null, List.of("VEGAN"), 0, 10));

        assertThat(results.items()).extracting(CatalogRecipeDto::getTitle)
                .containsExactlyInAnyOrder("Vegan Bowl", "Fruit Salad");
    }

    @Test
    void blankQuery_returnsBrowseListing() {
        CatalogRecipeRepository repo = repoWith(
                recipe("1", "A", List.of()),
                recipe("2", "B", List.of()));

        InAppCatalogSearchService svc = keywordService(repo);
        CatalogSearchResults results = svc.search(new CatalogSearchQuery("  ", List.of(), 0, 10));

        assertThat(results.totalMatches()).isEqualTo(2);
        assertThat(results.items()).hasSize(2);
    }

    @Test
    void pagination_boundsPageSizeAndReportsTotal() {
        CatalogRecipe[] many = new CatalogRecipe[25];
        for (int i = 0; i < 25; i++) {
            many[i] = recipe("id" + i, "Recipe " + i, List.of());
        }
        CatalogRecipeRepository repo = repoWith(many);

        InAppCatalogSearchService svc = keywordService(repo);
        CatalogSearchResults page0 = svc.search(new CatalogSearchQuery(null, List.of(), 0, 10));

        assertThat(page0.items()).hasSize(10);
        assertThat(page0.totalMatches()).isEqualTo(25);
        assertThat(page0.page()).isZero();
        assertThat(page0.pageSize()).isEqualTo(10);
    }

    @Test
    void hugePageNumber_returnsEmptyPageWithoutError() {
        // Regression: page*pageSize must be computed as long, or Stream.skip gets a negative
        // argument and throws (500) instead of returning an empty page.
        CatalogRecipeRepository repo = repoWith(
                recipe("1", "Chicken Curry", List.of()),
                recipe("2", "Chicken Soup", List.of()));

        InAppCatalogSearchService svc = keywordService(repo);
        CatalogSearchResults results =
                svc.search(new CatalogSearchQuery("chicken", List.of(), Integer.MAX_VALUE, 20));

        assertThat(results.items()).isEmpty();
        assertThat(results.totalMatches()).isEqualTo(2);
    }

    @Test
    void noMatches_returnsEmptyResults() {
        CatalogRecipeRepository repo = repoWith(recipe("1", "Chicken Curry", List.of()));

        InAppCatalogSearchService svc = keywordService(repo);
        CatalogSearchResults results = svc.search(new CatalogSearchQuery("zucchini", List.of(), 0, 10));

        assertThat(results.items()).isEmpty();
        assertThat(results.totalMatches()).isZero();
    }

    @Test
    void semanticFailure_fallsBackToKeyword() {
        CatalogRecipeRepository repo = repoWith(
                recipe("1", "Chicken Curry", List.of()),
                recipe("2", "Beef Stew", List.of()));

        EmbeddingService failing = mock(EmbeddingService.class);
        when(failing.embed(anyString())).thenThrow(new RuntimeException("bedrock down"));

        // semantic enabled + hybrid, but embedding throws => must still return keyword results
        // (no crash), and keyword filtering means only the actual match is returned.
        InAppCatalogSearchService svc =
                new InAppCatalogSearchService(repo, failing, true, "hybrid");
        CatalogSearchResults results = svc.search(new CatalogSearchQuery("chicken", List.of(), 0, 10));

        assertThat(results.items()).extracting(CatalogRecipeDto::getTitle)
                .containsExactly("Chicken Curry");
        assertThat(results.totalMatches()).isEqualTo(1);
    }

    @Test
    void findById_returnsDtoWithoutInternalFields() {
        CatalogRecipe r = CatalogRecipe.builder()
                .catalogRecipeId("abc")
                .title("Test")
                .searchText("test internal")
                .embedding(List.of(0.1, 0.2))
                .dietaryTags(List.of("VEGAN"))
                .ingredients(List.of("x"))
                .steps(List.of("y"))
                .build();
        CatalogRecipeRepository repo = mock(CatalogRecipeRepository.class);
        when(repo.findById("abc")).thenReturn(Optional.of(r));

        InAppCatalogSearchService svc = keywordService(repo);
        Optional<CatalogRecipeDto> dto = svc.findById("abc");

        assertThat(dto).isPresent();
        assertThat(dto.get().getTitle()).isEqualTo("Test");
        assertThat(dto.get().getDietaryTags()).containsExactly("VEGAN");
        // DTO has no embedding/searchText fields at all — confirms they are not exposed.
    }
}
