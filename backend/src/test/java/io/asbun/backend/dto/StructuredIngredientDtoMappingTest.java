package io.asbun.backend.dto;

import io.asbun.backend.ingest.DietaryTagger;
import io.asbun.backend.model.CatalogRecipe;
import io.asbun.backend.model.Recipe;
import io.asbun.backend.model.StructuredIngredient;
import io.asbun.backend.repository.CatalogRecipeRepository;
import io.asbun.backend.repository.RecipeRepository;
import io.asbun.backend.search.InAppCatalogSearchService;
import io.asbun.backend.service.AsyncImageService;
import io.asbun.backend.service.RecipeService;
import io.asbun.backend.service.S3Service;
import io.asbun.backend.service.StatsService;
import io.asbun.backend.metrics.MetricsService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the additive {@code structuredIngredients} field is carried through the DTO mappers
 * (Structured Ingredients spec, task 4): present when the model has it, absent (null) when it
 * doesn't, and the existing {@code ingredients} string list is never altered or reordered.
 *
 * <p>Covers both DynamoDB-backed mapping paths — the saved-recipe mapper
 * ({@link RecipeService#toDtoFor}) and the in-app catalog mapper
 * ({@link InAppCatalogSearchService#findById}). Dependencies are mocked (no S3, no DynamoDB).
 */
class StructuredIngredientDtoMappingTest {

    private static final List<String> RAW = List.of("2 cups flour", "1/2 tsp salt");
    private static final List<StructuredIngredient> STRUCTURED = List.of(
            StructuredIngredient.builder().quantity(2.0).unit("cup").item("flour").raw("2 cups flour").build(),
            StructuredIngredient.builder().quantity(0.5).unit("tsp").item("salt").raw("1/2 tsp salt").build());

    // ── Saved recipe (RecipeService.toDtoFor) ────────────────────────────────────

    private RecipeService recipeService() {
        S3Service s3 = mock(S3Service.class);
        // No image => the mapper skips all S3 work and returns imageUrl null.
        return new RecipeService(
                mock(RecipeRepository.class),
                mock(AsyncImageService.class),
                s3,
                mock(StatsService.class),
                mock(DietaryTagger.class),
                new io.asbun.backend.ingest.IngredientParser());
    }

    @Test
    void recipeDtoCarriesStructuredWhenPresent() {
        Recipe recipe = Recipe.builder()
                .recipeId("r1").userId("u1").title("Pancakes")
                .ingredients(RAW)
                .structuredIngredients(STRUCTURED)
                .build();

        RecipeDto dto = recipeService().toDtoFor(recipe);

        assertThat(dto.getStructuredIngredients()).isEqualTo(STRUCTURED);
        assertThat(dto.getIngredients()).isEqualTo(RAW);
    }

    @Test
    void recipeDtoStructuredNullWhenAbsent() {
        Recipe recipe = Recipe.builder()
                .recipeId("r1").userId("u1").title("Pancakes")
                .ingredients(RAW)
                .build();

        RecipeDto dto = recipeService().toDtoFor(recipe);

        assertThat(dto.getStructuredIngredients()).isNull();
        assertThat(dto.getIngredients()).isEqualTo(RAW);
    }

    // ── Catalog recipe (InAppCatalogSearchService.findById) ───────────────────────

    private InAppCatalogSearchService catalogService(CatalogRecipeRepository repo) {
        return new InAppCatalogSearchService(
                repo, mock(io.asbun.backend.service.EmbeddingService.class),
                mock(MetricsService.class), false, "keyword");
    }

    @Test
    void catalogDtoCarriesStructuredWhenPresent() {
        CatalogRecipe recipe = CatalogRecipe.builder()
                .catalogRecipeId("c1").title("Omelette")
                .ingredients(RAW)
                .structuredIngredients(STRUCTURED)
                .build();
        CatalogRecipeRepository repo = mock(CatalogRecipeRepository.class);
        when(repo.findById("c1")).thenReturn(Optional.of(recipe));

        Optional<CatalogRecipeDto> dto = catalogService(repo).findById("c1");

        assertThat(dto).isPresent();
        assertThat(dto.get().getStructuredIngredients()).isEqualTo(STRUCTURED);
        assertThat(dto.get().getIngredients()).isEqualTo(RAW);
    }

    @Test
    void catalogDtoStructuredNullWhenAbsent() {
        CatalogRecipe recipe = CatalogRecipe.builder()
                .catalogRecipeId("c2").title("Toast")
                .ingredients(RAW)
                .build();
        CatalogRecipeRepository repo = mock(CatalogRecipeRepository.class);
        when(repo.findById("c2")).thenReturn(Optional.of(recipe));

        Optional<CatalogRecipeDto> dto = catalogService(repo).findById("c2");

        assertThat(dto).isPresent();
        assertThat(dto.get().getStructuredIngredients()).isNull();
        assertThat(dto.get().getIngredients()).isEqualTo(RAW);
    }
}
