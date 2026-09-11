"use client";

import { useState, useEffect, useCallback, useRef } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { Loader2, Plus, Trash2, CalendarDays } from "lucide-react";
import { MealPlan } from "@/types/mealPlan";
import {
  listMealPlans,
  createMealPlan,
  deleteMealPlan,
} from "@/lib/mealPlanApi";

export default function MealPlansPage() {
  const router = useRouter();
  const [plans, setPlans] = useState<MealPlan[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);

  const [showCreate, setShowCreate] = useState(false);
  const [name, setName] = useState("");
  const [servings, setServings] = useState("4");
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);

  const requestSeq = useRef(0);
  const abortRef = useRef<AbortController | null>(null);

  const load = useCallback(async () => {
    const seq = ++requestSeq.current;
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;

    setLoading(true);
    setError(false);
    try {
      const data = await listMealPlans(controller.signal);
      if (seq !== requestSeq.current) return;
      setPlans(data);
    } catch (err) {
      if ((err as Error)?.name === "AbortError") return;
      if (seq !== requestSeq.current) return;
      setError(true);
    } finally {
      if (seq === requestSeq.current) setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  async function onCreate(e: React.FormEvent) {
    e.preventDefault();
    setCreating(true);
    setCreateError(null);
    try {
      const parsedServings = servings.trim() ? Number(servings) : undefined;
      const plan = await createMealPlan({
        name: name.trim(),
        servings: parsedServings,
      });
      router.push(`/meal-plans/${plan.mealPlanId}`);
    } catch {
      setCreateError("Couldn't create the plan. Please try again.");
      setCreating(false);
    }
  }

  async function onDelete(id: string) {
    // Optimistic remove; reload on failure to resync.
    setPlans((prev) => prev?.filter((p) => p.mealPlanId !== id) ?? prev);
    try {
      await deleteMealPlan(id);
    } catch {
      load();
    }
  }

  return (
    <div>
      <div className="mb-6 flex items-center justify-between gap-3">
        <h1 className="text-2xl font-bold text-gray-900">Meal Plans</h1>
        <button
          type="button"
          onClick={() => setShowCreate((v) => !v)}
          className="inline-flex items-center gap-2 rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 transition-colors"
        >
          <Plus className="h-4 w-4" />
          New plan
        </button>
      </div>

      {showCreate && (
        <form
          onSubmit={onCreate}
          className="mb-6 rounded-lg border border-gray-200 bg-white p-4"
        >
          <div className="flex flex-col gap-3 sm:flex-row sm:items-end">
            <label className="flex-1 text-sm">
              <span className="mb-1 block font-medium text-gray-700">Plan name</span>
              <input
                type="text"
                value={name}
                onChange={(e) => setName(e.target.value)}
                required
                maxLength={120}
                placeholder="e.g. This Week"
                className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm text-gray-900 focus:border-blue-500 focus:outline-none"
              />
            </label>
            <label className="text-sm sm:w-32">
              <span className="mb-1 block font-medium text-gray-700">Serves</span>
              <input
                type="number"
                min={1}
                max={50}
                value={servings}
                onChange={(e) => setServings(e.target.value)}
                className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm text-gray-900 focus:border-blue-500 focus:outline-none"
              />
            </label>
            <button
              type="submit"
              disabled={creating || !name.trim()}
              className="inline-flex items-center justify-center gap-2 rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50 transition-colors"
            >
              {creating && <Loader2 className="h-4 w-4 animate-spin" />}
              Create
            </button>
          </div>
          {createError && (
            <p className="mt-2 text-sm text-red-600">{createError}</p>
          )}
        </form>
      )}

      {loading ? (
        <div className="flex items-center justify-center py-16">
          <Loader2 className="h-8 w-8 animate-spin text-gray-400" />
        </div>
      ) : error ? (
        <div className="rounded-lg border border-red-200 bg-red-50 p-6 text-center">
          <p className="text-sm text-red-700">We couldn&apos;t load your meal plans.</p>
          <button
            onClick={load}
            className="mt-4 inline-flex items-center gap-2 rounded-md border border-red-300 px-4 py-2 text-sm font-medium text-red-700 hover:bg-red-100 transition-colors"
          >
            Retry
          </button>
        </div>
      ) : !plans || plans.length === 0 ? (
        <p className="text-gray-500">
          No meal plans yet. Create one to start scheduling recipes.
        </p>
      ) : (
        <ul className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {plans.map((plan) => (
            <li
              key={plan.mealPlanId}
              className="flex items-center justify-between gap-3 rounded-xl border border-gray-200 bg-white p-4 transition-shadow hover:shadow-md"
            >
              <Link
                href={`/meal-plans/${plan.mealPlanId}`}
                className="flex min-w-0 flex-1 items-center gap-3"
              >
                <CalendarDays className="h-5 w-5 flex-shrink-0 text-blue-600" />
                <span className="min-w-0">
                  <span className="block truncate font-semibold text-gray-900">
                    {plan.name}
                  </span>
                  <span className="block text-xs text-gray-500">
                    {plan.entries?.length ?? 0} meal
                    {(plan.entries?.length ?? 0) === 1 ? "" : "s"}
                    {plan.servings ? ` · serves ${plan.servings}` : ""}
                  </span>
                </span>
              </Link>
              <button
                type="button"
                aria-label={`Delete ${plan.name}`}
                onClick={() => onDelete(plan.mealPlanId)}
                className="flex-shrink-0 rounded-md p-2 text-gray-400 hover:bg-red-50 hover:text-red-600 transition-colors"
              >
                <Trash2 className="h-4 w-4" />
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
