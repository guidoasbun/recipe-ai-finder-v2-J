package io.asbun.backend.ingest;

import io.asbun.backend.model.StructuredIngredient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The single authoritative server-side parser that turns a free-text ingredient string into a
 * {@link StructuredIngredient} ({@code quantity}, {@code unit}, {@code item}, {@code raw}).
 * See the Structured Ingredients spec (§2).
 *
 * <p>It is the server-side promotion of the display-only heuristic in
 * {@code frontend/lib/ingredient.ts} ({@code ingredientName}), extended to also <em>return</em>
 * the quantity and unit it strips (the frontend discards those; here they are the whole point).
 * The {@code item} it produces matches what {@code ingredientName} would return, so the two
 * stay consistent and the frontend can prefer the structured {@code item} and fall back to its
 * heuristic for un-backfilled data.
 *
 * <p>The parser is deterministic and side-effect free (no I/O, no model calls), so it is cheap
 * enough to run across the full ~2.2M catalog during backfill. It never throws: the worst case
 * is {@code {quantity:null, unit:null, item:raw, raw}}.
 */
@Component
public class IngredientParser {

    /**
     * Common measurement units and their abbreviations/plurals that may follow a quantity.
     * Mirrors {@code frontend/lib/ingredient.ts#UNITS} so the two parsers recognize the same
     * vocabulary. Each entry maps to a normalized (singular, lowercase) token stored on the
     * structured ingredient.
     */
    private static final Map<String, String> UNITS = Map.ofEntries(
            Map.entry("cup", "cup"), Map.entry("cups", "cup"), Map.entry("c", "cup"),
            Map.entry("teaspoon", "tsp"), Map.entry("teaspoons", "tsp"),
            Map.entry("tsp", "tsp"), Map.entry("tsps", "tsp"),
            Map.entry("tablespoon", "tbsp"), Map.entry("tablespoons", "tbsp"),
            Map.entry("tbsp", "tbsp"), Map.entry("tbsps", "tbsp"), Map.entry("tbs", "tbsp"),
            Map.entry("ounce", "oz"), Map.entry("ounces", "oz"), Map.entry("oz", "oz"),
            Map.entry("pound", "lb"), Map.entry("pounds", "lb"),
            Map.entry("lb", "lb"), Map.entry("lbs", "lb"),
            Map.entry("gram", "g"), Map.entry("grams", "g"), Map.entry("g", "g"),
            Map.entry("kilogram", "kg"), Map.entry("kilograms", "kg"), Map.entry("kg", "kg"),
            Map.entry("milliliter", "ml"), Map.entry("milliliters", "ml"), Map.entry("ml", "ml"),
            Map.entry("liter", "l"), Map.entry("liters", "l"),
            Map.entry("litre", "l"), Map.entry("litres", "l"), Map.entry("l", "l"),
            Map.entry("pinch", "pinch"), Map.entry("pinches", "pinch"),
            Map.entry("dash", "dash"), Map.entry("dashes", "dash"),
            Map.entry("clove", "clove"), Map.entry("cloves", "clove"),
            Map.entry("can", "can"), Map.entry("cans", "can"),
            Map.entry("package", "package"), Map.entry("packages", "package"),
            Map.entry("pkg", "package"),
            Map.entry("slice", "slice"), Map.entry("slices", "slice"),
            Map.entry("stick", "stick"), Map.entry("sticks", "stick"),
            Map.entry("sprig", "sprig"), Map.entry("sprigs", "sprig"),
            Map.entry("quart", "qt"), Map.entry("quarts", "qt"), Map.entry("qt", "qt"),
            Map.entry("pint", "pt"), Map.entry("pints", "pt"), Map.entry("pt", "pt"),
            Map.entry("gallon", "gal"), Map.entry("gallons", "gal"), Map.entry("gal", "gal"),
            Map.entry("handful", "handful"), Map.entry("handfuls", "handful"),
            Map.entry("piece", "piece"), Map.entry("pieces", "piece")
    );

    /** Size/preparation adjectives that often trail the quantity before the actual name. */
    private static final Set<String> DESCRIPTORS = Set.of(
            "small", "medium", "large", "extra", "whole", "fresh", "ripe"
    );

    /** Unicode vulgar fractions this parser understands, mapped to their decimal value. */
    private static final Map<Character, Double> UNICODE_FRACTIONS = Map.of(
            '\u00BC', 0.25,   // ¼
            '\u00BD', 0.5,    // ½
            '\u00BE', 0.75,   // ¾
            '\u2153', 1.0 / 3, // ⅓
            '\u2154', 2.0 / 3, // ⅔
            '\u215B', 0.125   // ⅛
    );

    /**
     * Parses a single raw ingredient string into its structured parts. Never throws; the
     * original string is always preserved as {@code raw} and {@code item} is never blank.
     */
    public StructuredIngredient parse(String raw) {
        String original = raw == null ? "" : raw.trim();

        // Drop parenthetical notes for parsing only, e.g. "flour (sifted)" -> "flour".
        // (raw keeps the original.)
        String working = original.replaceAll("\\([^)]*\\)", " ").trim();

        if (working.isEmpty()) {
            // Nothing to parse (empty or whitespace/parenthetical-only). item falls back to raw.
            return StructuredIngredient.builder()
                    .quantity(null)
                    .unit(null)
                    .item(original)
                    .raw(original)
                    .build();
        }

        String[] tokens = working.split("\\s+");
        int i = 0;

        // 1. Consume a leading run of quantity tokens (e.g. "1 1/2", "3-4", "½"), summing them.
        Double quantity = null;
        boolean strippedQuantity = false;
        while (i < tokens.length) {
            Double value = quantityValue(tokens[i]);
            if (value == null) {
                break;
            }
            quantity = (quantity == null ? 0.0 : quantity) + value;
            strippedQuantity = true;
            i++;
        }

        // 2. If a quantity was stripped, optionally consume one unit then any size descriptors.
        String unit = null;
        if (strippedQuantity) {
            if (i < tokens.length) {
                String candidate = tokens[i].toLowerCase(Locale.ROOT).replaceAll("\\.$", "");
                String normalized = UNITS.get(candidate);
                if (normalized != null) {
                    unit = normalized;
                    i++;
                }
            }
            while (i < tokens.length && DESCRIPTORS.contains(tokens[i].toLowerCase(Locale.ROOT))) {
                i++;
            }
        }

        // 3. Remaining tokens are the item name.
        String item = String.join(" ", java.util.Arrays.asList(tokens).subList(i, tokens.length)).trim();

        // 4. Never produce a blank item: fall back to the original string (matching the
        //    frontend heuristic's "falls back to original" behavior). The parsed quantity/unit
        //    are still returned when they were recognized.
        if (item.isEmpty()) {
            item = original;
        }

        return StructuredIngredient.builder()
                .quantity(quantity)
                .unit(unit)
                .item(item)
                .raw(original)
                .build();
    }

    /**
     * Parses a list of raw ingredient strings, preserving order and count (one structured
     * entry per raw string — the positional-correspondence invariant, spec §1.3). A null list
     * yields an empty list.
     */
    public List<StructuredIngredient> parseAll(List<String> raws) {
        List<StructuredIngredient> out = new ArrayList<>();
        if (raws == null) {
            return out;
        }
        for (String raw : raws) {
            out.add(parse(raw));
        }
        return out;
    }

    /**
     * The numeric value of a single quantity token, or null if the token is not a quantity.
     * Handles integers ("2"), decimals ("1.5"), simple fractions ("1/2"), hyphen ranges
     * ("3-4", taking the lower bound), and single unicode vulgar fractions ("½").
     */
    private Double quantityValue(String token) {
        if (token.isEmpty()) {
            return null;
        }

        // Single unicode vulgar fraction glyph (e.g. "½").
        if (token.length() == 1 && UNICODE_FRACTIONS.containsKey(token.charAt(0))) {
            return UNICODE_FRACTIONS.get(token.charAt(0));
        }

        // Simple fraction "a/b".
        int slash = token.indexOf('/');
        if (slash > 0 && slash < token.length() - 1) {
            Double num = parseDecimal(token.substring(0, slash));
            Double den = parseDecimal(token.substring(slash + 1));
            if (num != null && den != null && den != 0.0) {
                return num / den;
            }
            return null;
        }

        // Hyphen range "a-b": take the lower bound as the representative quantity.
        int hyphen = token.indexOf('-');
        if (hyphen > 0 && hyphen < token.length() - 1) {
            Double low = parseDecimal(token.substring(0, hyphen));
            Double high = parseDecimal(token.substring(hyphen + 1));
            if (low != null && high != null) {
                return low;
            }
            return null;
        }

        // Plain integer or decimal.
        return parseDecimal(token);
    }

    /** Parses a plain integer/decimal token to a Double, or null if it is not numeric. */
    private Double parseDecimal(String s) {
        if (s.isEmpty()) {
            return null;
        }
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (!Character.isDigit(c) && c != '.') {
                return null;
            }
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
