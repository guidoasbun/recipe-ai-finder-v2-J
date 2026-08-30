package io.asbun.backend.ingest;

import io.asbun.backend.model.enums.DietaryRestriction;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Assigns {@link DietaryRestriction} tags to a recipe from its ingredient list, using
 * deterministic disqualifier keyword matching. A recipe earns a tag when NONE of that
 * restriction's disqualifiers appear in its ingredients.
 *
 * <p>This is intentionally conservative (better to omit a tag than to wrongly claim a
 * recipe is safe). Tagging happens once at ingestion; querying is then a pure tag match.
 *
 * <p>Two deliberate rules:
 * <ul>
 *   <li>If ingredients are missing/empty, NO tags are assigned. Absence of disqualifiers in
 *       an empty list is not evidence of safety, so we do not label unknown recipes.</li>
 *   <li>HALAL and KOSHER are NOT inferred here. They require certification/provenance that
 *       ingredient text cannot establish (e.g. halal slaughter, kosher meat/dairy
 *       separation), so absence of pork/shellfish alone is insufficient.</li>
 * </ul>
 * The remaining approximated restrictions (PALEO, LOW_CARB, KETO) are best-effort from
 * ingredient text.
 */
@Component
public class DietaryTagger {

    // Animal flesh — disqualifies VEGETARIAN and VEGAN (and, being non-plant, PALEO keeps meat).
    private static final Set<String> MEAT = Set.of(
            "beef", "pork", "chicken", "turkey", "lamb", "veal", "bacon", "ham", "sausage",
            "prosciutto", "pancetta", "chorizo", "duck", "goat", "venison", "meat",
            "fish", "salmon", "tuna", "cod", "haddock", "trout", "anchovy", "anchovies",
            "shrimp", "prawn", "crab", "lobster", "clam", "mussel", "oyster", "scallop",
            "squid", "octopus", "gelatin", "lard", "bone broth", "chicken broth", "beef broth"
    );

    // Additional animal products beyond flesh — disqualifies VEGAN.
    private static final Set<String> ANIMAL_PRODUCTS = Set.of(
            "egg", "eggs", "honey", "milk", "butter", "cheese", "cream", "yogurt", "yoghurt",
            "ghee", "whey", "casein", "custard", "mayonnaise"
    );

    // Dairy — disqualifies DAIRY_FREE.
    private static final Set<String> DAIRY = Set.of(
            "milk", "butter", "cheese", "cream", "yogurt", "yoghurt", "ghee", "whey",
            "casein", "custard", "buttermilk", "sour cream", "cream cheese", "parmesan",
            "mozzarella", "cheddar", "ricotta"
    );

    // Gluten-bearing — disqualifies GLUTEN_FREE.
    private static final Set<String> GLUTEN = Set.of(
            "flour", "wheat", "bread", "breadcrumb", "breadcrumbs", "pasta", "noodle",
            "noodles", "barley", "rye", "couscous", "cracker", "crackers", "beer",
            "soy sauce", "malt", "bulgur", "semolina", "spaghetti", "penne", "macaroni",
            "puff pastry", "pastry", "tortilla", "pie crust", "pie pastry", "cake",
            "farro", "seitan"
    );

    // Tree nuts / peanuts — disqualifies NUT_FREE.
    private static final Set<String> NUTS = Set.of(
            "almond", "almonds", "walnut", "walnuts", "pecan", "pecans", "cashew", "cashews",
            "pistachio", "pistachios", "hazelnut", "hazelnuts", "macadamia", "peanut",
            "peanuts", "peanut butter", "pine nut", "pine nuts", "nut", "nuts", "nutella"
    );

    // High-carb staples — used to approximate LOW_CARB / KETO / PALEO disqualifiers.
    private static final Set<String> HIGH_CARB = Set.of(
            "sugar", "flour", "bread", "pasta", "noodle", "noodles", "rice", "potato",
            "potatoes", "corn", "cornstarch", "oats", "oat", "honey", "syrup", "maple syrup",
            "brown sugar", "white sugar", "cane sugar", "molasses", "beans", "lentil",
            "lentils", "chickpea", "chickpeas", "tortilla", "cracker", "crackers", "cake",
            "cookie", "cookies", "pastry", "banana", "candy"
    );

    // Grains/legumes/dairy — additional PALEO disqualifiers (beyond HIGH_CARB grains).
    private static final Set<String> NON_PALEO = Set.of(
            "flour", "bread", "pasta", "rice", "oats", "oat", "beans", "lentil", "lentils",
            "chickpea", "chickpeas", "peanut", "peanuts", "soy", "tofu", "milk", "cheese",
            "butter", "cream", "yogurt", "sugar", "corn", "barley", "rye", "wheat"
    );

    /**
     * @param ingredients recipe ingredient phrases (quantities allowed)
     * @return the DietaryRestriction enum names (e.g. "VEGAN") the recipe satisfies. Empty
     *         when ingredients are missing/empty (unknown recipes get no safety tags), and
     *         never includes HALAL/KOSHER (not inferable from ingredient text).
     */
    public List<String> tag(List<String> ingredients) {
        // Unknown ingredients => no safety claims. Absence of disqualifiers in an empty
        // list is not evidence a recipe is vegan/nut-free/etc.
        if (ingredients == null || ingredients.isEmpty()) {
            return new ArrayList<>();
        }

        String blob = normalize(ingredients);
        List<String> tags = new ArrayList<>();

        boolean hasMeat = containsAny(blob, MEAT);
        boolean hasAnimal = hasMeat || containsAny(blob, ANIMAL_PRODUCTS);
        boolean hasDairy = containsAny(blob, DAIRY);
        boolean hasGluten = containsAny(blob, GLUTEN);
        boolean hasNuts = containsAny(blob, NUTS);
        boolean hasHighCarb = containsAny(blob, HIGH_CARB);
        boolean hasNonPaleo = containsAny(blob, NON_PALEO);

        if (!hasMeat) {
            tags.add(DietaryRestriction.VEGETARIAN.name());
        }
        if (!hasAnimal) {
            tags.add(DietaryRestriction.VEGAN.name());
        }
        if (!hasDairy) {
            tags.add(DietaryRestriction.DAIRY_FREE.name());
        }
        if (!hasGluten) {
            tags.add(DietaryRestriction.GLUTEN_FREE.name());
        }
        if (!hasNuts) {
            tags.add(DietaryRestriction.NUT_FREE.name());
        }
        if (!hasHighCarb) {
            tags.add(DietaryRestriction.LOW_CARB.name());
            tags.add(DietaryRestriction.KETO.name());
        }
        // PALEO allows meat but not grains/legumes/dairy/sugar.
        if (!hasNonPaleo) {
            tags.add(DietaryRestriction.PALEO.name());
        }

        // HALAL and KOSHER are intentionally NOT inferred: certification/provenance cannot
        // be derived from ingredient text (halal slaughter, kosher meat/dairy separation).

        return tags;
    }

    private String normalize(List<String> ingredients) {
        return String.join(" | ", ingredients).toLowerCase(Locale.ROOT);
    }

    private boolean containsAny(String blob, Set<String> keywords) {
        for (String kw : keywords) {
            if (containsWord(blob, kw)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Word-boundary-ish match to avoid false hits (e.g. "cornstarch" contains "corn"
     * intentionally, but "buttermilk" should still match "butter"). We match on substring
     * bounded by non-letter characters to reduce accidental partial matches like matching
     * "ham" inside "graham".
     */
    private boolean containsWord(String blob, String keyword) {
        if (keyword.contains(" ")) {
            // Multi-word phrase: plain substring is fine.
            return blob.contains(keyword);
        }
        int idx = 0;
        while ((idx = blob.indexOf(keyword, idx)) != -1) {
            boolean leftOk = idx == 0 || !Character.isLetter(blob.charAt(idx - 1));
            int end = idx + keyword.length();
            boolean rightOk = end >= blob.length() || !Character.isLetter(blob.charAt(end));
            if (leftOk && rightOk) {
                return true;
            }
            idx = end;
        }
        return false;
    }
}
