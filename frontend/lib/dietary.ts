export type DietaryRestriction =
  | "GLUTEN_FREE"
  | "KETO"
  | "VEGAN"
  | "VEGETARIAN"
  | "DAIRY_FREE"
  | "NUT_FREE"
  | "PALEO"
  | "LOW_CARB"
  | "HALAL"
  | "KOSHER";

export interface DietaryRestrictionOption {
  value: DietaryRestriction;
  label: string;
}

export const DIETARY_RESTRICTIONS: DietaryRestrictionOption[] = [
  { value: "GLUTEN_FREE", label: "Gluten-Free" },
  { value: "KETO", label: "Keto" },
  { value: "VEGAN", label: "Vegan" },
  { value: "VEGETARIAN", label: "Vegetarian" },
  { value: "DAIRY_FREE", label: "Dairy-Free" },
  { value: "NUT_FREE", label: "Nut-Free" },
  { value: "PALEO", label: "Paleo" },
  { value: "LOW_CARB", label: "Low-Carb" },
  { value: "HALAL", label: "Halal" },
  { value: "KOSHER", label: "Kosher" },
];

const LABEL_BY_VALUE = new Map(
  DIETARY_RESTRICTIONS.map((o) => [o.value, o.label])
);

export function dietaryLabel(value: string): string {
  return LABEL_BY_VALUE.get(value as DietaryRestriction) ?? value;
}
