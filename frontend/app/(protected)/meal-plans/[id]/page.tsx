"use client";

import { useState, useEffect, useCallback, useRef } from "react";
import { useParams, notFound } from "next/navigation";
import Link from "next/link";
import {
  Loader2,
  ArrowLeft,
  ChevronLeft,
  ChevronRight,
  Plus,
  X,
  AlertCircle,
} from "lucide-react";
import { MealPlan, MealPlanEntry, RecipeSource } from "@/types/mealPlan";
import { MEAL_SLOTS, MealSlot } from "@/lib/mealSlots";
import { getMealPlan, addEntry, removeEntry } from "@/lib/mealPlanApi";
import RecipePicker from "./RecipePicker";

function todayIso(): string {
  // Local calendar day (not UTC), so "today" matches the user's timezone.
  const d = new Date();
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${y}-${m}-${day}`;
}

function addDaysIso(iso: string, delta: number): string {
  const [y, m, d] = iso.split("-").map(Number);
  const date = new Date(y, m - 1, d);
  date.setDate(date.getDate() + delta);
  const yy = date.getFullYear();
  const mm = String(date.getMonth() + 1).padStart(2, "0");
  const dd = String(date.getDate()).padStart(2, "0");
  return `${yy}-${mm}-${dd}`;
}

function recipeHref(source: string | null, recipeId: string | null): string | null {
  if (!recipeId || !source) return null;
  // Reuse the existing recipe detail pages: catalog → /browse/[id], saved → /recipes/[id].
  return source === "CATALOG" ? `/browse/${recipeId}` : `/recipes/${recipeId}`;
}

function formatDayLabel(iso: string): string {
  const [y, m, d] = iso.split("-").map(Number);
  const date = new Date(y, m - 1, d);
  return date.toLocaleDateString(undefined, {
    weekday: "long",
    month: "short",
    day: "numeric",
  });
}

export default function MealPlanCalendarPage() {
  const params = useParams<{ id: string }>();
  const id = params?.id;

  const [plan, setPlan] = useState<MealPlan | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);
  const [missing, setMissing] = useState(false);

  const [day, setDay] = useState(todayIso());
  const [picker, setPicker] = useState<{ date: string; slot: MealSlot } | null>(null);

  const requestSeq = useRef(0);
  const abortRef = useRef<AbortController | null>(null);

  const load = useCallback(async () => {
    if (!id) return;
    const seq = ++requestSeq.current;
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;

    setLoading(true);
    setError(false);
    try {
      const data = await getMealPlan(id, controller.signal);
      if (seq !== requestSeq.current) return;
      setPlan(data);
    } catch (err) {
      if ((err as Error)?.name === "AbortError") return;
      if (seq !== requestSeq.current) return;
      if ((err as Error)?.message?.includes("404")) {
        setMissing(true);
      } else {
        setError(true);
      }
    } finally {
      if (seq === requestSeq.current) setLoading(false);
    }
  }, [id]);

  useEffect(() => {
    load();
  }, [load]);

  if (missing) notFound();

  const entriesForDay = (slot: MealSlot): MealPlanEntry[] =>
    (plan?.entries ?? []).filter((e) => e.date === day && e.slot === slot);

  async function onPick(result: { source: RecipeSource; recipeId: string }) {
    if (!id || !picker) return;
    const target = picker;
    setPicker(null);
    try {
      const updated = await addEntry(id, {
        date: target.date,
        slot: target.slot,
        source: result.source,
        recipeId: result.recipeId,
      });
      setPlan(updated);
    } catch {
      // Resync on failure.
      load();
    }
  }

  async function onRemove(entryId: string) {
    if (!id) return;
    // Optimistic removal.
    setPlan((prev) =>
      prev
        ? { ...prev, entries: prev.entries.filter((e) => e.entryId !== entryId) }
        : prev,
    );
    try {
      const updated = await removeEntry(id, entryId);
      setPlan(updated);
    } catch {
      load();
    }
  }

  return (
    <div>
      <Link
        href="/meal-plans"
        className="mb-4 inline-flex items-center gap-1 text-sm text-gray-500 hover:text-gray-700"
      >
        <ArrowLeft className="h-4 w-4" />
        All plans
      </Link>

      {loading ? (
        <div className="flex items-center justify-center py-16">
          <Loader2 className="h-8 w-8 animate-spin text-gray-400" />
        </div>
      ) : error ? (
        <div className="rounded-lg border border-red-200 bg-red-50 p-6 text-center">
          <p className="text-sm text-red-700">We couldn&apos;t load this plan.</p>
          <button
            onClick={load}
            className="mt-4 inline-flex items-center gap-2 rounded-md border border-red-300 px-4 py-2 text-sm font-medium text-red-700 hover:bg-red-100 transition-colors"
          >
            Retry
          </button>
        </div>
      ) : plan ? (
        <>
          <div className="mb-4">
            <h1 className="text-2xl font-bold text-gray-900">{plan.name}</h1>
            {plan.servings ? (
              <p className="text-sm text-gray-500">Serves {plan.servings}</p>
            ) : null}
          </div>

          {/* Day navigation — touch-sized, single-day view for mobile-first. */}
          <div className="mb-6 flex items-center justify-between gap-2">
            <button
              type="button"
              aria-label="Previous day"
              onClick={() => setDay((d) => addDaysIso(d, -1))}
              className="rounded-md border border-gray-300 p-2 text-gray-600 hover:bg-gray-50"
            >
              <ChevronLeft className="h-5 w-5" />
            </button>
            <div className="min-w-0 flex-1 text-center">
              <span className="block truncate text-sm font-semibold text-gray-900">
                {formatDayLabel(day)}
              </span>
              <button
                type="button"
                onClick={() => setDay(todayIso())}
                className="text-xs text-blue-600 hover:underline"
              >
                Today
              </button>
            </div>
            <button
              type="button"
              aria-label="Next day"
              onClick={() => setDay((d) => addDaysIso(d, 1))}
              className="rounded-md border border-gray-300 p-2 text-gray-600 hover:bg-gray-50"
            >
              <ChevronRight className="h-5 w-5" />
            </button>
          </div>

          {/* Slots stacked (mobile-first). Wider screens get more breathing room but the same
              single-day layout; a multi-day grid can be layered on later without API changes. */}
          <div className="flex flex-col gap-4">
            {MEAL_SLOTS.map(({ value, label }) => {
              const entries = entriesForDay(value);
              return (
                <section
                  key={value}
                  className="rounded-xl border border-gray-200 bg-white p-4"
                >
                  <div className="mb-3 flex items-center justify-between">
                    <h2 className="text-sm font-semibold uppercase tracking-wide text-gray-500">
                      {label}
                    </h2>
                    <button
                      type="button"
                      aria-label={`Add a recipe to ${label}`}
                      onClick={() => setPicker({ date: day, slot: value })}
                      className="inline-flex items-center gap-1 rounded-md border border-gray-300 px-3 py-1.5 text-sm text-gray-700 hover:border-blue-400 hover:bg-blue-50 transition-colors"
                    >
                      <Plus className="h-4 w-4" />
                      Add
                    </button>
                  </div>

                  {entries.length === 0 ? (
                    <p className="text-sm text-gray-400">Nothing planned.</p>
                  ) : (
                    <ul className="flex flex-col gap-2">
                      {entries.map((e) => {
                        const href = e.available
                          ? recipeHref(e.recipeSource, e.recipeId)
                          : null;

                        // The image + title is the tappable area that opens the recipe.
                        // Available entries link to the existing detail page; unavailable
                        // ones render as a plain (non-clickable) block.
                        const content = (
                          <>
                            {e.available && e.imageUrl ? (
                              // eslint-disable-next-line @next/next/no-img-element
                              <img
                                src={e.imageUrl}
                                alt={e.title ?? ""}
                                className="h-12 w-12 flex-shrink-0 rounded-md object-cover"
                              />
                            ) : (
                              <span className="flex h-12 w-12 flex-shrink-0 items-center justify-center rounded-md bg-gray-100">
                                {!e.available && (
                                  <AlertCircle className="h-5 w-5 text-gray-400" />
                                )}
                              </span>
                            )}
                            <span className="min-w-0 flex-1">
                              <span className="block truncate text-sm font-medium text-gray-900">
                                {e.available ? e.title : "Recipe unavailable"}
                              </span>
                              {!e.available && (
                                <span className="block text-xs text-gray-400">
                                  It may have been deleted.
                                </span>
                              )}
                            </span>
                          </>
                        );

                        return (
                          <li
                            key={e.entryId}
                            className="flex items-center gap-3 rounded-lg border border-gray-200 p-2"
                          >
                            {href ? (
                              <Link
                                href={href}
                                className="flex min-w-0 flex-1 items-center gap-3 rounded-md hover:bg-gray-50 transition-colors"
                              >
                                {content}
                              </Link>
                            ) : (
                              <div className="flex min-w-0 flex-1 items-center gap-3">
                                {content}
                              </div>
                            )}
                            <button
                              type="button"
                              aria-label="Remove from plan"
                              onClick={() => onRemove(e.entryId)}
                              className="flex-shrink-0 rounded-md p-2 text-gray-400 hover:bg-red-50 hover:text-red-600 transition-colors"
                            >
                              <X className="h-4 w-4" />
                            </button>
                          </li>
                        );
                      })}
                    </ul>
                  )}
                </section>
              );
            })}
          </div>
        </>
      ) : null}

      {picker && (
        <RecipePicker
          date={picker.date}
          slot={picker.slot}
          onPick={onPick}
          onClose={() => setPicker(null)}
        />
      )}
    </div>
  );
}
