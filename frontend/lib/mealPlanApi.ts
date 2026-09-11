import {
  AddEntryRequest,
  CreateMealPlanRequest,
  MealPlan,
  UpdateEntryRequest,
} from "@/types/mealPlan";

// Client-side calls go through the Next proxy path (/api/backend/...), which attaches the
// Authorization header and rewrites to the backend (see proxy.ts + next.config.ts).
const BASE = "/api/backend/api/meal-plans";

async function json<T>(res: Response): Promise<T> {
  if (!res.ok) {
    throw new Error(`Request failed: ${res.status}`);
  }
  return res.json() as Promise<T>;
}

export async function listMealPlans(signal?: AbortSignal): Promise<MealPlan[]> {
  return json<MealPlan[]>(await fetch(BASE, { signal }));
}

export async function getMealPlan(id: string, signal?: AbortSignal): Promise<MealPlan> {
  return json<MealPlan>(await fetch(`${BASE}/${id}`, { signal }));
}

export async function createMealPlan(body: CreateMealPlanRequest): Promise<MealPlan> {
  return json<MealPlan>(
    await fetch(BASE, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    }),
  );
}

export async function deleteMealPlan(id: string): Promise<void> {
  const res = await fetch(`${BASE}/${id}`, { method: "DELETE" });
  if (!res.ok) throw new Error(`Request failed: ${res.status}`);
}

export async function addEntry(id: string, body: AddEntryRequest): Promise<MealPlan> {
  return json<MealPlan>(
    await fetch(`${BASE}/${id}/entries`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    }),
  );
}

export async function updateEntry(
  id: string,
  entryId: string,
  body: UpdateEntryRequest,
): Promise<MealPlan> {
  return json<MealPlan>(
    await fetch(`${BASE}/${id}/entries/${entryId}`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    }),
  );
}

export async function removeEntry(id: string, entryId: string): Promise<MealPlan> {
  return json<MealPlan>(
    await fetch(`${BASE}/${id}/entries/${entryId}`, { method: "DELETE" }),
  );
}
