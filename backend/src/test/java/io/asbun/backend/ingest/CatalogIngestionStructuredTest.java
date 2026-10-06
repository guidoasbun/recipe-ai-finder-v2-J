package io.asbun.backend.ingest;

import io.asbun.backend.model.StructuredIngredient;
import io.asbun.backend.repository.CatalogRecipeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies forward-ingestion structured-ingredient resolution (Structured Ingredients spec,
 * task 7): the runner prefers a source's own structured list when it lines up with the string
 * ingredients, otherwise derives it from the strings via the parser — and the embedding input
 * text is unchanged (no structured data folded in, so vectors are unaffected).
 */
class CatalogIngestionStructuredTest {

    private CatalogIngestionRunner runner() {
        CatalogRecipeRepository repo = mock(CatalogRecipeRepository.class);
        // The constructor calls repository.forTable(...); return the same mock.
        when(repo.forTable(anyString())).thenReturn(repo);
        return new CatalogIngestionRunner(
                repo,
                mock(DietaryTagger.class),
                new IngredientParser(),
                mock(SynchronousEmbeddingStrategy.class),
                mock(BatchEmbeddingStrategy.class),
                "data", "table", "sync", "", 0, 0, 100000, false);
    }

    @SuppressWarnings("unchecked")
    private List<StructuredIngredient> resolveStructured(CatalogIngestionRunner r, ParsedRecipe p) {
        return (List<StructuredIngredient>) ReflectionTestUtils.invokeMethod(r, "resolveStructured", p);
    }

    private String embeddingInput(CatalogIngestionRunner r, ParsedRecipe p) {
        return (String) ReflectionTestUtils.invokeMethod(r, "embeddingInput", p);
    }

    private ParsedRecipe parsed(List<String> ingredients, List<StructuredIngredient> structured) {
        return new ParsedRecipe("src:1", "Chili", "Spicy", ingredients,
                List.of("cook"), null, "TheMealDB", null, null, "US", structured);
    }

    @Test
    void prefersSourceProvidedStructureWhenCountMatches() {
        List<StructuredIngredient> fromSource = List.of(
                StructuredIngredient.builder().quantity(2.0).unit("cup").item("beans").raw("2 cups beans").build(),
                StructuredIngredient.builder().quantity(1.0).unit(null).item("onion").raw("1 onion").build());
        ParsedRecipe p = parsed(List.of("2 cups beans", "1 onion"), fromSource);

        assertThat(resolveStructured(runner(), p)).isSameAs(fromSource);
    }

    @Test
    void derivesFromStringsWhenSourceStructureAbsent() {
        ParsedRecipe p = parsed(List.of("2 cups beans", "1 onion"), null);

        List<StructuredIngredient> result = resolveStructured(runner(), p);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getQuantity()).isEqualTo(2.0);
        assertThat(result.get(0).getUnit()).isEqualTo("cup");
        assertThat(result.get(0).getItem()).isEqualTo("beans");
        assertThat(result.get(1).getItem()).isEqualTo("onion");
    }

    @Test
    void derivesFromStringsOnCountMismatch() {
        List<StructuredIngredient> tooFew = List.of(
                StructuredIngredient.builder().item("beans").raw("2 cups beans").build());
        ParsedRecipe p = parsed(List.of("2 cups beans", "1 onion"), tooFew);

        List<StructuredIngredient> result = resolveStructured(runner(), p);

        // Mismatch → ignore source list, parse both strings.
        assertThat(result).hasSize(2);
        assertThat(result).extracting(StructuredIngredient::getRaw)
                .containsExactly("2 cups beans", "1 onion");
    }

    @Test
    void embeddingInputDoesNotIncludeStructuredData() {
        // Structured data present, but the embedding text is title + description + raw strings
        // only — unchanged by this spec, so no vector is regenerated.
        List<StructuredIngredient> fromSource = List.of(
                StructuredIngredient.builder().quantity(2.0).unit("cup").item("beans").raw("2 cups beans").build());
        ParsedRecipe p = parsed(List.of("2 cups beans"), fromSource);

        String input = embeddingInput(runner(), p);

        assertThat(input).isEqualTo("Chili. Spicy. Ingredients: 2 cups beans");
    }
}
