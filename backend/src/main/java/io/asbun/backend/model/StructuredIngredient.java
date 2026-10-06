package io.asbun.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;

/**
 * A single ingredient broken into its parts, stored <em>additively</em> alongside a recipe's
 * free-text {@code List<String> ingredients} (which stays the authoritative display text).
 * See the Structured Ingredients spec (§1.1).
 *
 * <p>The structured form is derived from, and reconcilable with, the string form: {@code raw}
 * always holds the original ingredient string verbatim, so parsing is lossless at the string
 * level and any consumer can fall back to {@code raw} even when the breakdown is approximate.
 * When a recipe's {@code structuredIngredients} list is present it corresponds positionally
 * and in count to its {@code ingredients} list — one structured entry per raw string.
 *
 * <p>Design choices (spec §1.1):
 * <ul>
 *   <li>{@code quantity} is a {@link Double}, not a string, because later specs multiply it
 *       (servings scaling) and sum it (grocery-list merging). Null = no parseable amount
 *       (e.g. "salt to taste").</li>
 *   <li>{@code unit} is a plain, lightly-normalized token (e.g. {@code cup}, {@code tsp},
 *       {@code g}), stored roughly as authored. Canonical unit conversion is deferred to the
 *       grocery-list spec, where the conversion table belongs. Null = no unit (e.g. "3 eggs").</li>
 *   <li>{@code item} and {@code raw} are always non-blank.</li>
 * </ul>
 *
 * <p>Embedded (a nested {@link DynamoDbBean}) in {@code Recipe} and {@code CatalogRecipe} as a
 * {@code List<StructuredIngredient>}, and reused by the recipe DTOs.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@DynamoDbBean
public class StructuredIngredient {

    /**
     * The parsed leading amount (e.g. {@code 2}, {@code 0.5}, or {@code 1.5} from "1 1/2").
     * Null when the source text has no parseable quantity.
     */
    private Double quantity;

    /**
     * The lightly-normalized unit token (e.g. {@code cup}, {@code tsp}, {@code g}). Null when
     * the source text has no recognizable unit.
     */
    private String unit;

    /** The ingredient name with any leading quantity/unit stripped (e.g. {@code flour}). Never blank. */
    private String item;

    /** The original ingredient string verbatim (e.g. {@code "2 cups flour"}). Never blank; the authoritative fallback. */
    private String raw;
}
