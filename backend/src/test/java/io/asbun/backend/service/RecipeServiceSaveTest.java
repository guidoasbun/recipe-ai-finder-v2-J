package io.asbun.backend.service;

import io.asbun.backend.dto.RecipeDto;
import io.asbun.backend.dto.SaveRecipeRequest;
import io.asbun.backend.dto.StructuredIngredientRequest;
import io.asbun.backend.ingest.DietaryTagger;
import io.asbun.backend.ingest.IngredientParser;
import io.asbun.backend.model.Recipe;
import io.asbun.backend.model.StructuredIngredient;
import io.asbun.backend.model.enums.BedrockModel;
import io.asbun.backend.repository.RecipeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Tests the save path's structured-ingredient handling (Structured Ingredients spec, task 6):
 * persist the client-submitted structured list when it lines up with the strings, else derive
 * it from the strings; the string list stays the only required ingredient input.
 */
class RecipeServiceSaveTest {

    private RecipeRepository recipeRepository;
    private RecipeService service;

    @BeforeEach
    void setUp() {
        recipeRepository = mock(RecipeRepository.class);
        service = new RecipeService(
                recipeRepository,
                mock(AsyncImageService.class),
                mock(S3Service.class),
                mock(StatsService.class),
                mock(DietaryTagger.class),
                new IngredientParser());
    }

    private SaveRecipeRequest baseRequest() {
        SaveRecipeRequest req = new SaveRecipeRequest();
        req.setTitle("Pancakes");
        req.setDescription("Fluffy pancakes");
        req.setIngredients(List.of("2 cups flour", "1/2 tsp salt"));
        req.setSteps(List.of("Mix", "Cook"));
        req.setModel(BedrockModel.CLAUDE_HAIKU);
        return req;
    }

    private Recipe savedRecipe() {
        ArgumentCaptor<Recipe> captor = ArgumentCaptor.forClass(Recipe.class);
        verify(recipeRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void persistsSubmittedStructuredWhenCountMatches() {
        SaveRecipeRequest req = baseRequest();
        StructuredIngredientRequest a = new StructuredIngredientRequest();
        a.setQuantity(2.0);
        a.setUnit("cup");
        a.setItem("flour");
        a.setRaw("2 cups flour");
        StructuredIngredientRequest b = new StructuredIngredientRequest();
        b.setQuantity(0.5);
        b.setUnit("tsp");
        b.setItem("salt");
        b.setRaw("1/2 tsp salt");
        req.setStructuredIngredients(List.of(a, b));

        RecipeDto dto = service.saveRecipe(req, "u1");

        Recipe saved = savedRecipe();
        assertThat(saved.getStructuredIngredients()).hasSize(2);
        assertThat(saved.getStructuredIngredients().get(0).getItem()).isEqualTo("flour");
        // raw is bound to the string list, keeping the §1.3 invariant.
        assertThat(saved.getStructuredIngredients()).extracting(StructuredIngredient::getRaw)
                .containsExactly("2 cups flour", "1/2 tsp salt");
        assertThat(dto.getStructuredIngredients()).isEqualTo(saved.getStructuredIngredients());
    }

    @Test
    void derivesStructuredWhenOmitted() {
        SaveRecipeRequest req = baseRequest(); // no structuredIngredients set

        service.saveRecipe(req, "u1");

        Recipe saved = savedRecipe();
        assertThat(saved.getStructuredIngredients()).hasSize(2);
        assertThat(saved.getStructuredIngredients().get(0).getQuantity()).isEqualTo(2.0);
        assertThat(saved.getStructuredIngredients().get(0).getUnit()).isEqualTo("cup");
        assertThat(saved.getStructuredIngredients().get(0).getItem()).isEqualTo("flour");
    }

    @Test
    void derivesStructuredOnCountMismatch() {
        SaveRecipeRequest req = baseRequest(); // two string ingredients
        StructuredIngredientRequest only = new StructuredIngredientRequest();
        only.setItem("flour");
        only.setRaw("2 cups flour");
        req.setStructuredIngredients(List.of(only)); // only one → mismatch, ignore it

        service.saveRecipe(req, "u1");

        Recipe saved = savedRecipe();
        assertThat(saved.getStructuredIngredients()).hasSize(2);
        assertThat(saved.getStructuredIngredients()).extracting(StructuredIngredient::getRaw)
                .containsExactly("2 cups flour", "1/2 tsp salt");
    }

    @Test
    void stringIngredientsAlwaysPersistedUnchanged() {
        SaveRecipeRequest req = baseRequest();

        service.saveRecipe(req, "u1");

        assertThat(savedRecipe().getIngredients()).containsExactly("2 cups flour", "1/2 tsp salt");
    }

    // ── Validation (Req 4.3): invalid structured entries produce constraint violations ──

    @Test
    void invalidStructuredEntryFailsValidation() {
        try (jakarta.validation.ValidatorFactory factory =
                     jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            jakarta.validation.Validator validator = factory.getValidator();

            SaveRecipeRequest req = baseRequest();
            StructuredIngredientRequest bad = new StructuredIngredientRequest();
            bad.setQuantity(-1.0);   // @PositiveOrZero violated
            bad.setItem("");          // @NotBlank violated
            bad.setRaw("");           // @NotBlank violated
            req.setStructuredIngredients(List.of(bad));

            var violations = validator.validate(req);

            assertThat(violations).isNotEmpty();
            assertThat(violations).anySatisfy(v ->
                    assertThat(v.getPropertyPath().toString()).contains("structuredIngredients"));
        }
    }

    @Test
    void validStructuredEntryPassesValidation() {
        try (jakarta.validation.ValidatorFactory factory =
                     jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            jakarta.validation.Validator validator = factory.getValidator();

            SaveRecipeRequest req = baseRequest();
            StructuredIngredientRequest ok = new StructuredIngredientRequest();
            ok.setQuantity(2.0);
            ok.setUnit("cup");
            ok.setItem("flour");
            ok.setRaw("2 cups flour");
            StructuredIngredientRequest ok2 = new StructuredIngredientRequest();
            ok2.setItem("salt");       // quantity/unit null is allowed
            ok2.setRaw("1/2 tsp salt");
            req.setStructuredIngredients(List.of(ok, ok2));

            assertThat(validator.validate(req)).isEmpty();
        }
    }
}
