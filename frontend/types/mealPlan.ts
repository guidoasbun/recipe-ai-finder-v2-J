import { MealSlot } from "@/lib/mealSlots";

export type RecipeSource = "SAVED" | "CATALOG";

// A plan entry with its recipe reference resolved for display. `available` is false when the
// referenced recipe is missing/inaccessible; the UI shows a placeholder for that slot.
export interface MealPlanEntry {
  entryId: string;
  date: string; // ISO yyyy-MM-dd (start day)
  slot: MealSlot;
  servings: number | null;
  /** Consecutive days covered from `date` (meal-prep). 1 = single day. */
  spanDays: number;
  recipeSource: RecipeSource | null;
  recipeId: string | null;
  available: boolean;
  title: string | null;
  imageUrl: string | null;
}

export interface MealPlan {
  mealPlanId: string;
  ownerUserId: string;
  name: string;
  startDate: string | null;
  endDate: string | null;
  servings: number | null;
  entries: MealPlanEntry[];
  createdAt: string;
  updatedAt: string;
}

// Request payloads (mirror the backend request DTOs).
export interface CreateMealPlanRequest {
  name: string;
  startDate?: string;
  endDate?: string;
  servings?: number;
}

export interface AddEntryRequest {
  date: string;
  slot: MealSlot;
  source: RecipeSource;
  recipeId: string;
  servings?: number;
  /** Meal-prep span; omit or 1 for a single-day meal. */
  spanDays?: number;
}

export interface UpdateEntryRequest {
  date?: string;
  slot?: MealSlot;
  servings?: number;
  spanDays?: number;
}
