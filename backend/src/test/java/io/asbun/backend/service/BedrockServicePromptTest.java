package io.asbun.backend.service;

import io.asbun.backend.model.enums.DietaryRestriction;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for BedrockService prompt construction with dietary restrictions.
 *
 * Feature: dietary-restrictions
 * Validates: Requirements 6.1, 6.2
 */
class BedrockServicePromptTest {

    private static final String DIETARY_CLAUSE_MARKER = "IMPORTANT DIETARY CONSTRAINTS";

    private final BedrockService service =
            new BedrockService(mock(BedrockRuntimeClient.class), new io.asbun.backend.ingest.IngredientParser());

    private String buildPrompt(List<String> ingredients, List<String> restrictions) {
        return (String) ReflectionTestUtils.invokeMethod(
                service, "buildPrompt", ingredients, restrictions);
    }

    @Test
    void prompt_withSpecificRestrictions_containsDisplayNamesAndClause() {
        List<String> ingredients = List.of("chicken", "rice");
        List<String> restrictions = List.of(
                DietaryRestriction.GLUTEN_FREE.name(),
                DietaryRestriction.VEGAN.name());

        String prompt = buildPrompt(ingredients, restrictions);

        assertThat(prompt).contains(DIETARY_CLAUSE_MARKER);
        assertThat(prompt).contains("Gluten-Free");
        assertThat(prompt).contains("Vegan");
        // Ingredients are still present.
        assertThat(prompt).contains("chicken").contains("rice");
    }

    @Test
    void prompt_withAllRestrictions_containsEveryDisplayName() {
        List<String> ingredients = List.of("tofu");
        List<String> restrictions = new ArrayList<>();
        for (DietaryRestriction r : DietaryRestriction.values()) {
            restrictions.add(r.name());
        }

        String prompt = buildPrompt(ingredients, restrictions);

        for (DietaryRestriction r : DietaryRestriction.values()) {
            assertThat(prompt).contains(r.getDisplayName());
        }
    }

    @Test
    void prompt_withEmptyRestrictions_doesNotContainDietaryClause() {
        String prompt = buildPrompt(List.of("eggs", "flour"), new ArrayList<>());

        assertThat(prompt).doesNotContain(DIETARY_CLAUSE_MARKER);
    }

    @Test
    void prompt_withNullRestrictions_doesNotContainDietaryClause() {
        String prompt = buildPrompt(List.of("eggs", "flour"), null);

        assertThat(prompt).doesNotContain(DIETARY_CLAUSE_MARKER);
    }

    // ── Structured ingredients (spec task 5) ──────────────────────────────────────

    @Test
    void prompt_requestsStructuredIngredients() {
        String prompt = buildPrompt(List.of("flour"), null);

        assertThat(prompt).contains("structuredIngredients");
        assertThat(prompt).contains("\"item\"");
        // The existing string-ingredients instruction is retained.
        assertThat(prompt).contains("array of strings with quantities");
    }

    /** Wraps a recipe-array JSON string in a Claude-style response envelope and parses it. */
    @SuppressWarnings("unchecked")
    private List<io.asbun.backend.dto.GenerateRecipeResponse> parseClaude(String recipesJson) {
        String body = "{\"content\":[{\"text\":" + quote(recipesJson) + "}]}";
        return (List<io.asbun.backend.dto.GenerateRecipeResponse>) ReflectionTestUtils.invokeMethod(
                service, "parseResponse",
                io.asbun.backend.model.enums.BedrockModel.CLAUDE_HAIKU, body);
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @Test
    void parse_usesWellFormedStructuredIngredients() {
        String recipes = "[{\"title\":\"T\",\"description\":\"d\","
                + "\"ingredients\":[\"2 cups flour\",\"1/2 tsp salt\"],"
                + "\"structuredIngredients\":["
                + "{\"quantity\":2,\"unit\":\"cup\",\"item\":\"flour\"},"
                + "{\"quantity\":0.5,\"unit\":\"tsp\",\"item\":\"salt\"}],"
                + "\"steps\":[\"mix\"]}]";

        var result = parseClaude(recipes);

        assertThat(result).hasSize(1);
        var si = result.get(0).getStructuredIngredients();
        assertThat(si).hasSize(2);
        assertThat(si.get(0).getQuantity()).isEqualTo(2.0);
        assertThat(si.get(0).getUnit()).isEqualTo("cup");
        assertThat(si.get(0).getItem()).isEqualTo("flour");
        // raw is bound to the display string, not whatever the model might have put.
        assertThat(si.get(0).getRaw()).isEqualTo("2 cups flour");
        // Display strings are untouched.
        assertThat(result.get(0).getIngredients()).containsExactly("2 cups flour", "1/2 tsp salt");
    }

    @Test
    void parse_fallsBackToParserWhenStructuredAbsent() {
        String recipes = "[{\"title\":\"T\",\"description\":\"d\","
                + "\"ingredients\":[\"2 cups flour\"],\"steps\":[\"mix\"]}]";

        var result = parseClaude(recipes);

        var si = result.get(0).getStructuredIngredients();
        assertThat(si).hasSize(1);
        // Derived by the server-side parser from the display string.
        assertThat(si.get(0).getQuantity()).isEqualTo(2.0);
        assertThat(si.get(0).getUnit()).isEqualTo("cup");
        assertThat(si.get(0).getItem()).isEqualTo("flour");
        assertThat(si.get(0).getRaw()).isEqualTo("2 cups flour");
    }

    @Test
    void parse_fallsBackToParserOnLengthMismatch() {
        // Two display strings but only one structured entry → ignore the model's array entirely.
        String recipes = "[{\"title\":\"T\",\"description\":\"d\","
                + "\"ingredients\":[\"2 cups flour\",\"3 eggs\"],"
                + "\"structuredIngredients\":[{\"quantity\":2,\"unit\":\"cup\",\"item\":\"flour\"}],"
                + "\"steps\":[\"mix\"]}]";

        var result = parseClaude(recipes);

        var si = result.get(0).getStructuredIngredients();
        assertThat(si).hasSize(2);
        assertThat(si).extracting(io.asbun.backend.model.StructuredIngredient::getRaw)
                .containsExactly("2 cups flour", "3 eggs");
        assertThat(si.get(1).getQuantity()).isEqualTo(3.0);
        assertThat(si.get(1).getItem()).isEqualTo("eggs");
    }

    @Test
    void parse_perEntryBlankItemIsParsedFromRaw() {
        // Length matches, but one entry has a blank item → that single entry is parsed from raw.
        String recipes = "[{\"title\":\"T\",\"description\":\"d\","
                + "\"ingredients\":[\"2 cups flour\"],"
                + "\"structuredIngredients\":[{\"quantity\":2,\"unit\":\"cup\",\"item\":\"\"}],"
                + "\"steps\":[\"mix\"]}]";

        var result = parseClaude(recipes);

        var si = result.get(0).getStructuredIngredients();
        assertThat(si).hasSize(1);
        assertThat(si.get(0).getItem()).isEqualTo("flour");
        assertThat(si.get(0).getRaw()).isEqualTo("2 cups flour");
    }
}
