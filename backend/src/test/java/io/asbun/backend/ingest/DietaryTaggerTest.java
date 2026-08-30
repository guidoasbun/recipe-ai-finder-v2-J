package io.asbun.backend.ingest;

import io.asbun.backend.model.enums.DietaryRestriction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for DietaryTagger deterministic keyword tagging.
 *
 * Feature: existing-recipe-search
 * Validates: Requirements 4.5, 6.1
 */
class DietaryTaggerTest {

    private final DietaryTagger tagger = new DietaryTagger();

    @Test
    void meatDish_isNotVegetarianOrVegan() {
        List<String> tags = tagger.tag(List.of("2 lbs beef", "1 onion", "salt"));

        assertThat(tags).doesNotContain(
                DietaryRestriction.VEGETARIAN.name(),
                DietaryRestriction.VEGAN.name());
    }

    @Test
    void dairyDessert_isVegetarianButNotVeganNorDairyFree() {
        List<String> tags = tagger.tag(List.of("2 cups milk", "1 cup sugar", "butter"));

        assertThat(tags).contains(DietaryRestriction.VEGETARIAN.name());
        assertThat(tags).doesNotContain(
                DietaryRestriction.VEGAN.name(),
                DietaryRestriction.DAIRY_FREE.name());
    }

    @Test
    void plantOnlyDish_isVeganAndVegetarianAndDairyFree() {
        List<String> tags = tagger.tag(List.of("spinach", "strawberries", "olive oil", "lemon"));

        assertThat(tags).contains(
                DietaryRestriction.VEGETARIAN.name(),
                DietaryRestriction.VEGAN.name(),
                DietaryRestriction.DAIRY_FREE.name());
    }

    @Test
    void flourContaining_isNotGlutenFree() {
        List<String> tags = tagger.tag(List.of("2 cups flour", "water"));

        assertThat(tags).doesNotContain(DietaryRestriction.GLUTEN_FREE.name());
    }

    @Test
    void nutContaining_isNotNutFree() {
        List<String> tags = tagger.tag(List.of("1 cup almonds", "honey"));

        assertThat(tags).doesNotContain(DietaryRestriction.NUT_FREE.name());
    }

    @Test
    void porkContaining_isNotHalal() {
        List<String> tags = tagger.tag(List.of("bacon", "eggs"));

        assertThat(tags).doesNotContain(DietaryRestriction.HALAL.name());
    }

    @Test
    void shellfishContaining_isNotKosher() {
        List<String> tags = tagger.tag(List.of("shrimp", "garlic", "butter"));

        assertThat(tags).doesNotContain(DietaryRestriction.KOSHER.name());
    }

    @Test
    void wordBoundary_grahamDoesNotTriggerHam() {
        // "graham" contains "ham" as a substring; word-boundary matching must not flag it.
        List<String> tags = tagger.tag(List.of("graham crackers", "sugar"));

        // graham crackers are not pork; HALAL should remain (crackers do disqualify GLUTEN_FREE).
        assertThat(tags).contains(DietaryRestriction.HALAL.name());
        assertThat(tags).doesNotContain(DietaryRestriction.GLUTEN_FREE.name());
    }

    @Test
    void emptyIngredients_returnsAllTags() {
        // With no disqualifiers present, the conservative tagger grants every restriction.
        List<String> tags = tagger.tag(List.of());

        assertThat(tags).contains(
                DietaryRestriction.VEGAN.name(),
                DietaryRestriction.GLUTEN_FREE.name(),
                DietaryRestriction.NUT_FREE.name());
    }
}
