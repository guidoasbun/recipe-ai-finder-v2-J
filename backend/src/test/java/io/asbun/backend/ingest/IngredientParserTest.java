package io.asbun.backend.ingest;

import io.asbun.backend.model.StructuredIngredient;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit + property tests for {@link IngredientParser} (Structured Ingredients spec, task 2).
 *
 * <p>The {@code item} column mirrors the ported {@code frontend/lib/ingredient.test.ts} cases
 * so the server-side parser agrees with the display heuristic; the {@code quantity}/{@code unit}
 * columns assert the parts the frontend throws away but we keep.
 */
class IngredientParserTest {

    private final IngredientParser parser = new IngredientParser();

    // [input, expectedItem, expectedQuantity (nullable), expectedUnit (nullable)]
    static Stream<Arguments> cases() {
        return Stream.of(
                // Plain names (no quantity) pass through untouched; no quantity/unit.
                Arguments.of("flour", "flour", null, null),
                Arguments.of("olive oil", "olive oil", null, null),

                // Leading integer quantity + unit.
                Arguments.of("2 cups flour", "flour", 2.0, "cup"),
                Arguments.of("1 tbsp olive oil", "olive oil", 1.0, "tbsp"),
                Arguments.of("12 oz pasta", "pasta", 12.0, "oz"),

                // Fractions and mixed numbers.
                Arguments.of("1/2 tsp salt", "salt", 0.5, "tsp"),
                Arguments.of("1 1/2 cups sugar", "sugar", 1.5, "cup"),
                Arguments.of("1.5 kg beef", "beef", 1.5, "kg"),
                Arguments.of("3-4 cloves garlic", "garlic", 3.0, "clove"),

                // Unicode fraction glyphs.
                Arguments.of("\u00BD cup milk", "milk", 0.5, "cup"),
                Arguments.of("\u00BC teaspoon nutmeg", "nutmeg", 0.25, "tsp"),

                // Size/prep descriptors after the quantity (no unit present).
                Arguments.of("3 large eggs", "eggs", 3.0, null),
                Arguments.of("2 medium onions", "onions", 2.0, null),
                Arguments.of("1 large ripe avocado", "avocado", 1.0, null),

                // Parenthetical notes are dropped from parsing (raw keeps them).
                Arguments.of("flour (sifted)", "flour", null, null),
                Arguments.of("2 cups flour (sifted)", "flour", 2.0, "cup"),

                // Unit with a trailing period is still recognized.
                Arguments.of("2 oz. cheese", "cheese", 2.0, "oz"),

                // Fallbacks: item never empty; parsed quantity/unit still returned when present.
                Arguments.of("", "", null, null),
                Arguments.of("   ", "", null, null),
                Arguments.of("2 cups", "2 cups", 2.0, "cup"),
                Arguments.of("1/2", "1/2", 0.5, null)
        );
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> item=\"{1}\" qty={2} unit={3}")
    @MethodSource("cases")
    void parsesIninput(String input, String expectedItem, Double expectedQuantity, String expectedUnit) {
        StructuredIngredient result = parser.parse(input);

        assertThat(result.getItem()).isEqualTo(expectedItem);
        if (expectedQuantity == null) {
            assertThat(result.getQuantity()).isNull();
        } else {
            assertThat(result.getQuantity()).isCloseTo(expectedQuantity, within(1e-9));
        }
        assertThat(result.getUnit()).isEqualTo(expectedUnit);
    }

    @Test
    void preservesRawVerbatim() {
        // raw keeps the original (including the parenthetical and surrounding spaces trimmed).
        assertThat(parser.parse("2 cups flour (sifted)").getRaw()).isEqualTo("2 cups flour (sifted)");
        assertThat(parser.parse("  flour  ").getRaw()).isEqualTo("flour");
    }

    @Test
    void doesNotStripDescriptorWithoutLeadingQuantity() {
        // "large" is only a descriptor after a quantity was stripped (matches the frontend).
        StructuredIngredient result = parser.parse("large eggs");
        assertThat(result.getItem()).isEqualTo("large eggs");
        assertThat(result.getQuantity()).isNull();
        assertThat(result.getUnit()).isNull();
    }

    @Test
    void noParseableAmountYieldsNullsAndFullItem() {
        // "salt to taste": no quantity/unit, whole text is the item.
        StructuredIngredient result = parser.parse("salt to taste");
        assertThat(result.getQuantity()).isNull();
        assertThat(result.getUnit()).isNull();
        assertThat(result.getItem()).isEqualTo("salt to taste");
        assertThat(result.getRaw()).isEqualTo("salt to taste");
    }

    @Test
    void parseAllPreservesOrderAndCount() {
        List<String> raws = List.of("2 cups flour", "1/2 tsp salt", "3 eggs");
        List<StructuredIngredient> result = parser.parseAll(raws);

        assertThat(result).hasSize(3);
        assertThat(result).extracting(StructuredIngredient::getRaw)
                .containsExactly("2 cups flour", "1/2 tsp salt", "3 eggs");
        assertThat(result).extracting(StructuredIngredient::getItem)
                .containsExactly("flour", "salt", "eggs");
    }

    @Test
    void parseAllNullListYieldsEmpty() {
        assertThat(parser.parseAll(null)).isEmpty();
    }

    // ── Properties ──────────────────────────────────────────────────────────────

    @Property(tries = 300)
    void rawAlwaysEqualsTrimmedInput(@ForAll String raw) {
        assertThat(parser.parse(raw).getRaw()).isEqualTo(raw.trim());
    }

    @Property(tries = 300)
    void itemIsNeverBlankForNonBlankInput(@ForAll String raw) {
        StructuredIngredient result = parser.parse(raw);
        if (raw.trim().isEmpty()) {
            // Blank input: item mirrors the (empty) trimmed raw — the one allowed empty case.
            assertThat(result.getItem()).isEmpty();
        } else {
            assertThat(result.getItem()).isNotBlank();
        }
    }

    @Property(tries = 300)
    void neverThrows(@ForAll String raw) {
        // Any string parses without exception.
        parser.parse(raw);
    }
}
