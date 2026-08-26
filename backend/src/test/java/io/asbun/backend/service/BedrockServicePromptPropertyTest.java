package io.asbun.backend.service;

import io.asbun.backend.model.enums.DietaryRestriction;
import net.jqwik.api.*;
import net.jqwik.api.Tag;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Property-based tests for BedrockService prompt construction.
 *
 * Feature: dietary-restrictions
 * Validates: Requirements 6.1, 6.2
 */
@Tag("Feature: dietary-restrictions")
class BedrockServicePromptPropertyTest {

    private static final String[] VALID_VALUES = Arrays.stream(DietaryRestriction.values())
            .map(Enum::name)
            .toArray(String[]::new);

    private static final String DIETARY_CLAUSE_MARKER = "IMPORTANT DIETARY CONSTRAINTS";

    // ========================================================================
    // Property 5: Prompt includes all restrictions when present
    // ========================================================================

    /**
     * Property 5: For any non-empty valid restrictions list and any non-empty ingredients
     * list, the prompt contains the display name of every restriction.
     *
     * Validates: Requirements 6.1
     */
    @Property(tries = 100)
    @Tag("Property 5: Prompt includes all restrictions when present")
    void prompt_containsEveryRestrictionDisplayName(
            @ForAll("nonEmptyIngredients") List<String> ingredients,
            @ForAll("nonEmptyValidRestrictions") List<String> restrictions
    ) {
        String prompt = buildPrompt(ingredients, restrictions);

        for (String restriction : restrictions) {
            String displayName = DietaryRestriction.valueOf(restriction).getDisplayName();
            assertThat(prompt).contains(displayName);
        }
        assertThat(prompt).contains(DIETARY_CLAUSE_MARKER);
    }

    // ========================================================================
    // Property 6: Prompt excludes dietary text when restrictions are absent
    // ========================================================================

    /**
     * Property 6: For any empty/null restrictions list and any non-empty ingredients list,
     * the prompt does not contain the dietary constraint clause text.
     *
     * Validates: Requirements 6.2
     */
    @Property(tries = 100)
    @Tag("Property 6: Prompt excludes dietary text when restrictions are absent")
    void prompt_excludesDietaryClauseWhenAbsent(
            @ForAll("nonEmptyIngredients") List<String> ingredients,
            @ForAll("emptyOrNullRestrictions") List<String> restrictions
    ) {
        String prompt = buildPrompt(ingredients, restrictions);

        assertThat(prompt).doesNotContain(DIETARY_CLAUSE_MARKER);
    }

    // ========================================================================
    // Providers
    // ========================================================================

    @Provide
    Arbitrary<List<String>> nonEmptyIngredients() {
        return Arbitraries.strings().alpha().ofMinLength(2).ofMaxLength(30)
                .list().ofMinSize(1).ofMaxSize(10);
    }

    @Provide
    Arbitrary<List<String>> nonEmptyValidRestrictions() {
        return Arbitraries.of(VALID_VALUES)
                .set().ofMinSize(1).ofMaxSize(VALID_VALUES.length)
                .map(ArrayList::new);
    }

    @Provide
    Arbitrary<List<String>> emptyOrNullRestrictions() {
        return Arbitraries.of(new ArrayList<String>(), (List<String>) null);
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private String buildPrompt(List<String> ingredients, List<String> restrictions) {
        BedrockService service = new BedrockService(mock(BedrockRuntimeClient.class));
        return (String) ReflectionTestUtils.invokeMethod(
                service, "buildPrompt", ingredients, restrictions);
    }
}
