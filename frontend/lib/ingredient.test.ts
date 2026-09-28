import { describe, it, expect } from "vitest";
import { ingredientName } from "./ingredient";

describe("ingredientName", () => {
  // [input, expected, note]
  const cases: [string, string, string][] = [
    // Plain names (no quantity) pass through untouched.
    ["flour", "flour", "bare name"],
    ["olive oil", "olive oil", "multi-word bare name"],

    // Leading integer quantity + unit.
    ["2 cups flour", "flour", "integer + unit"],
    ["1 tbsp olive oil", "olive oil", "abbreviated unit + multi-word name"],
    ["12 oz pasta", "pasta", "two-digit quantity"],

    // Fractions and mixed numbers.
    ["1/2 tsp salt", "salt", "simple fraction + unit"],
    ["1 1/2 cups sugar", "sugar", "mixed number (two quantity tokens)"],
    ["1.5 kg beef", "beef", "decimal quantity"],
    ["3-4 cloves garlic", "garlic", "range quantity"],

    // Unicode fraction glyphs.
    ["½ cup milk", "milk", "unicode half + unit"],
    ["¼ teaspoon nutmeg", "nutmeg", "unicode quarter + spelled-out unit"],

    // Size/prep descriptors after the quantity (no unit present).
    ["3 large eggs", "eggs", "quantity + descriptor, no unit"],
    ["2 medium onions", "onions", "quantity + descriptor"],
    ["1 large ripe avocado", "avocado", "quantity + two descriptors"],

    // Parenthetical notes are dropped.
    ["flour (sifted)", "flour", "parenthetical on a bare name"],
    ["2 cups flour (sifted)", "flour", "parenthetical after unit"],

    // Unit with a trailing period is still recognized.
    ["2 oz. cheese", "cheese", "unit with trailing period"],

    // Fallbacks: never return empty.
    ["", "", "empty input"],
    ["   ", "", "whitespace-only input trims to empty"],
    ["2 cups", "2 cups", "quantity + unit with no name falls back to original"],
    ["1/2", "1/2", "lone quantity falls back to original"],
  ];

  it.each(cases)("%s → %s (%s)", (input, expected) => {
    expect(ingredientName(input)).toBe(expected);
  });

  it("does not strip a descriptor when there was no leading quantity", () => {
    // "large" is only treated as a descriptor after a quantity was stripped.
    expect(ingredientName("large eggs")).toBe("large eggs");
  });
});
