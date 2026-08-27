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

    private final BedrockService service = new BedrockService(mock(BedrockRuntimeClient.class));

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
}
