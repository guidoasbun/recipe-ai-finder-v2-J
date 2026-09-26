// Single source of truth for the meal-slot vocabulary on the frontend. Mirrors the backend
// MealSlot enum (io.asbun.backend.model.enums.MealSlot), like lib/dietary.ts mirrors
// DietaryRestriction. Order here is the display order (breakfast → snack).

export type MealSlot = "BREAKFAST" | "LUNCH" | "DINNER" | "SNACK";

export interface MealSlotOption {
  value: MealSlot;
  label: string;
}

export const MEAL_SLOTS: MealSlotOption[] = [
  { value: "BREAKFAST", label: "Breakfast" },
  { value: "LUNCH", label: "Lunch" },
  { value: "DINNER", label: "Dinner" },
  { value: "SNACK", label: "Snack" },
];

const LABEL_BY_VALUE = new Map(MEAL_SLOTS.map((o) => [o.value, o.label]));

export function mealSlotLabel(value: string): string {
  return LABEL_BY_VALUE.get(value as MealSlot) ?? value;
}
