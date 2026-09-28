// Catalog ingredients are stored as free-form strings with quantities inline,
// e.g. "2 cups flour", "1/2 tsp salt", "3 large eggs". For compact card displays
// we want just the ingredient name, without the leading amount/unit.

// Common measurement units (and their abbreviations/plurals) that may follow a quantity.
const UNITS = new Set([
  "cup", "cups", "c",
  "teaspoon", "teaspoons", "tsp", "tsps",
  "tablespoon", "tablespoons", "tbsp", "tbsps", "tbs",
  "ounce", "ounces", "oz",
  "pound", "pounds", "lb", "lbs",
  "gram", "grams", "g",
  "kilogram", "kilograms", "kg",
  "milliliter", "milliliters", "ml",
  "liter", "liters", "litre", "litres", "l",
  "pinch", "pinches", "dash", "dashes",
  "clove", "cloves", "can", "cans", "package", "packages", "pkg",
  "slice", "slices", "stick", "sticks", "sprig", "sprigs",
  "quart", "quarts", "qt", "pint", "pints", "pt", "gallon", "gallons", "gal",
  "handful", "handfuls", "piece", "pieces",
]);

// Size/preparation adjectives that often trail the quantity before the actual name.
const DESCRIPTORS = new Set([
  "small", "medium", "large", "extra", "whole", "fresh", "ripe",
]);

// A token that is purely numeric: "2", "1/2", "1.5", "3-4", "½".
function isQuantityToken(token: string): boolean {
  return /^[\d]+([.\-/][\d]+)?$/.test(token) || /^[¼½¾⅓⅔⅛]$/.test(token);
}

/**
 * Extracts the ingredient name from a raw catalog ingredient string by stripping
 * a leading quantity and any following unit/size descriptor.
 *
 * Falls back to the original (trimmed) string if nothing recognizable is stripped,
 * so we never render an empty name.
 */
export function ingredientName(raw: string): string {
  if (!raw) return "";
  const original = raw.trim();
  // Drop any parenthetical notes, e.g. "flour (sifted)" -> "flour".
  const working = original.replace(/\([^)]*\)/g, " ").trim();

  const tokens = working.split(/\s+/);
  let i = 0;

  // Skip a leading run of quantity tokens (e.g. "1 1/2").
  let strippedQuantity = false;
  while (i < tokens.length && isQuantityToken(tokens[i])) {
    i++;
    strippedQuantity = true;
  }

  // If we stripped a quantity, also skip a single following unit and/or descriptor.
  if (strippedQuantity) {
    if (i < tokens.length && UNITS.has(tokens[i].toLowerCase().replace(/\.$/, ""))) {
      i++;
    }
    while (i < tokens.length && DESCRIPTORS.has(tokens[i].toLowerCase())) {
      i++;
    }
  }

  const name = tokens.slice(i).join(" ").trim();
  return name.length > 0 ? name : original;
}
