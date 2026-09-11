import { MealSlot } from "@/lib/mealSlots";

export type RecipeSource = "SAVED" | "CATALOG";

// A plan entry with its recipe reference resolved for display. `available` is false when the
// referenced recipe is missing/inaccessible; the UI shows a placeholder for that slot.
export interface MealPlanEntry {
  entryId: string;
  date: string; // ISO yyyy-MM-dd
  slot: MealSlot;
  servings: number | null;
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
}

export interface UpdateEntryRequest {
  date?: string;
  slot?: MealSlot;
  servings?: number;
}
