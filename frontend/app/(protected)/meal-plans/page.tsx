"use client";

import { useState, useEffect, useCallback, useRef } from "react";
import Link from "next/link";
import { Loader2, ChevronLeft, ChevronRight, Plus, X, AlertCircle, Pencil } from "lucide-react";
import { MealPlan, MealPlanEntry, RecipeSource, UpdateEntryRequest } from "@/types/mealPlan";
import { MEAL_SLOTS, MealSlot } from "@/lib/mealSlots";
import {
  getDefaultMealPlan,
  addEntry as apiAddEntry,
  removeEntry as apiRemoveEntry,
  updateEntry as apiUpdateEntry,
} from "@/lib/mealPlanApi";
import {
  ISODate,
  todayIso,
  addDays,
  weekDays,
  monthGrid,
  monthLabel,
  dayLabel,
  dayNumber,
  weekdayShort,
  isSameMonth,
  spanCoversDay,
  parseIso,
  startOfWeek,
} from "@/lib/calendar";
import RecipePicker from "@/components/mealplan/RecipePicker";
import EntryEditor from "@/components/mealplan/EntryEditor";

type ViewMode = "week" | "month";

function recipeHref(source: string | null, recipeId: string | null): string | null {
  if (!recipeId || !source) return null;
  return source === "CATALOG" ? `/browse/${recipeId}` : `/recipes/${recipeId}`;
}

export default function MealCalendarPage() {
  const [plan, setPlan] = useState<MealPlan | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);

  const [view, setView] = useState<ViewMode>("month");
  const [anchor, setAnchor] = useState<ISODate>(todayIso());
  const [picker, setPicker] = useState<{ date: ISODate; slot: MealSlot } | null>(null);
  const [editing, setEditing] = useState<MealPlanEntry | null>(null);

  const requestSeq = useRef(0);
  const abortRef = useRef<AbortController | null>(null);

  // Default the view by screen size: month on desktop, week on phones. Runs once on mount;
  // the user can toggle afterwards.
  useEffect(() => {
    if (typeof window !== "undefined" && window.matchMedia("(max-width: 767px)").matches) {
      setView("week");
    }
  }, []);

  const load = useCallback(async () => {
    const seq = ++requestSeq.current;
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    setLoading(true);
    setError(false);
    try {
      const data = await getDefaultMealPlan(controller.signal);
      if (seq !== requestSeq.current) return;
      setPlan(data);
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

  const entries = plan?.entries ?? [];

  function entriesCovering(day: ISODate, slot: MealSlot): MealPlanEntry[] {
    return entries.filter(
      (e) => e.slot === slot && spanCoversDay(e.date, e.spanDays ?? 1, day),
    );
  }

  function entriesCoveringDay(day: ISODate): MealPlanEntry[] {
    return entries.filter((e) => spanCoversDay(e.date, e.spanDays ?? 1, day));
  }

  async function onPick(result: {
    source: RecipeSource;
    recipeId: string;
    spanDays: number;
  }) {
    if (!plan || !picker) return;
    const target = picker;
    setPicker(null);
    try {
      const updated = await apiAddEntry(plan.mealPlanId, {
        date: target.date,
        slot: target.slot,
        source: result.source,
        recipeId: result.recipeId,
        spanDays: result.spanDays,
      });
      setPlan(updated);
    } catch {
      load();
    }
  }

  async function onRemove(entryId: string) {
    if (!plan) return;
    setPlan((prev) =>
      prev ? { ...prev, entries: prev.entries.filter((e) => e.entryId !== entryId) } : prev,
    );
    try {
      const updated = await apiRemoveEntry(plan.mealPlanId, entryId);
      setPlan(updated);
    } catch {
      load();
    }
  }

  async function onUpdate(entryId: string, changes: UpdateEntryRequest) {
    if (!plan) return;
    setEditing(null);
    // Nothing changed — skip the round trip.
    if (Object.keys(changes).length === 0) return;
    try {
      const updated = await apiUpdateEntry(plan.mealPlanId, entryId, changes);
      setPlan(updated);
    } catch {
      // Re-sync from the server on failure (e.g. an invalid date rejected by the backend).
      load();
    }
  }

  function shift(delta: number) {
    setAnchor((a) => addDays(a, view === "week" ? delta * 7 : shiftMonth(a, delta)));
  }

  // For month navigation, jump ~1 month by moving to the 1st of the next/prev month.
  function shiftMonth(a: ISODate, delta: number): number {
    const d = parseIso(a);
    const target = new Date(d.getFullYear(), d.getMonth() + delta, 1);
    return Math.round((target.getTime() - d.getTime()) / 86_400_000);
  }

  const headerLabel =
    view === "week"
      ? `Week of ${dayLabel(startOfWeek(anchor))}`
      : monthLabel(anchor);

  return (
    <div>
      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">Meal Plan</h1>
          {plan?.servings != null && (
            <p className="text-sm text-gray-500">
              Serves {plan.servings} {plan.servings === 1 ? "person" : "people"} by default
            </p>
          )}
        </div>
        <div className="flex items-center gap-2">
          <div className="inline-flex overflow-hidden rounded-md border border-gray-300">
            <button
              type="button"
              aria-pressed={view === "week"}
              onClick={() => setView("week")}
              className={`px-3 py-1.5 text-sm font-medium transition-colors ${
                view === "week" ? "bg-blue-600 text-white" : "bg-white text-gray-600"
              }`}
            >
              Week
            </button>
            <button
              type="button"
              aria-pressed={view === "month"}
              onClick={() => setView("month")}
              className={`px-3 py-1.5 text-sm font-medium transition-colors ${
                view === "month" ? "bg-blue-600 text-white" : "bg-white text-gray-600"
              }`}
            >
              Month
            </button>
          </div>
        </div>
      </div>

      {/* Period navigation */}
      <div className="mb-6 flex items-center justify-between gap-2">
        <button
          type="button"
          aria-label={view === "week" ? "Previous week" : "Previous month"}
          onClick={() => shift(-1)}
          className="rounded-md border border-gray-300 p-2 text-gray-600 hover:bg-gray-50"
        >
          <ChevronLeft className="h-5 w-5" />
        </button>
        <div className="min-w-0 flex-1 text-center">
          <span className="block truncate text-sm font-semibold text-gray-900">
            {headerLabel}
          </span>
          <button
            type="button"
            onClick={() => setAnchor(todayIso())}
            className="text-xs text-blue-600 hover:underline"
          >
            Today
          </button>
        </div>
        <button
          type="button"
          aria-label={view === "week" ? "Next week" : "Next month"}
          onClick={() => shift(1)}
          className="rounded-md border border-gray-300 p-2 text-gray-600 hover:bg-gray-50"
        >
          <ChevronRight className="h-5 w-5" />
        </button>
      </div>

      {loading ? (
        <div className="flex items-center justify-center py-16">
          <Loader2 className="h-8 w-8 animate-spin text-gray-400" />
        </div>
      ) : error ? (
        <div className="rounded-lg border border-red-200 bg-red-50 p-6 text-center">
          <p className="text-sm text-red-700">We couldn&apos;t load your meal plan.</p>
          <button
            onClick={load}
            className="mt-4 inline-flex items-center gap-2 rounded-md border border-red-300 px-4 py-2 text-sm font-medium text-red-700 hover:bg-red-100 transition-colors"
          >
            Retry
          </button>
        </div>
      ) : view === "week" ? (
        <WeekView
          anchor={anchor}
          planServings={plan?.servings ?? null}
          entriesCovering={entriesCovering}
          onAdd={(date, slot) => setPicker({ date, slot })}
          onRemove={onRemove}
          onEdit={(entry) => setEditing(entry)}
        />
      ) : (
        <MonthView
          anchor={anchor}
          entriesCoveringDay={entriesCoveringDay}
          onAddDay={(date) => setPicker({ date, slot: "DINNER" })}
        />
      )}

      {picker && (
        <RecipePicker
          date={picker.date}
          slot={picker.slot}
          onPick={onPick}
          onClose={() => setPicker(null)}
        />
      )}

      {editing && (
        <EntryEditor
          entry={editing}
          planServings={plan?.servings ?? null}
          onSave={(changes) => onUpdate(editing.entryId, changes)}
          onClose={() => setEditing(null)}
        />
      )}
    </div>
  );
}

// ── Week view ────────────────────────────────────────────────────────────────

function WeekView({
  anchor,
  planServings,
  entriesCovering,
  onAdd,
  onRemove,
  onEdit,
}: {
  anchor: ISODate;
  planServings: number | null;
  entriesCovering: (day: ISODate, slot: MealSlot) => MealPlanEntry[];
  onAdd: (day: ISODate, slot: MealSlot) => void;
  onRemove: (entryId: string) => void;
  onEdit: (entry: MealPlanEntry) => void;
}) {
  const days = weekDays(anchor);
  const today = todayIso();

  return (
    <div className="flex flex-col gap-4">
      {days.map((day) => (
        <section
          key={day}
          className={`rounded-xl border bg-white p-4 ${
            day === today ? "border-blue-400" : "border-gray-200"
          }`}
        >
          <h2 className="mb-3 text-sm font-semibold text-gray-900">
            {weekdayShort(day)} · {dayNumber(day)}
          </h2>
          <div className="flex flex-col gap-3">
            {MEAL_SLOTS.map(({ value, label }) => {
              const items = entriesCovering(day, value);
              return (
                <div key={value}>
                  <div className="mb-1 flex items-center justify-between">
                    <span className="text-xs font-medium uppercase tracking-wide text-gray-400">
                      {label}
                    </span>
                    <button
                      type="button"
                      aria-label={`Add a recipe to ${label} on ${day}`}
                      onClick={() => onAdd(day, value)}
                      className="rounded p-1 text-gray-400 hover:bg-blue-50 hover:text-blue-600"
                    >
                      <Plus className="h-4 w-4" />
                    </button>
                  </div>
                  {items.length === 0 ? (
                    <p className="text-xs text-gray-300">—</p>
                  ) : (
                    <ul className="flex flex-col gap-1">
                      {items.map((e) => (
                        <EntryRow
                          key={e.entryId}
                          entry={e}
                          day={day}
                          planServings={planServings}
                          onRemove={onRemove}
                          onEdit={onEdit}
                        />
                      ))}
                    </ul>
                  )}
                </div>
              );
            })}
          </div>
        </section>
      ))}
    </div>
  );
}

// ── Month view ───────────────────────────────────────────────────────────────

function MonthView({
  anchor,
  entriesCoveringDay,
  onAddDay,
}: {
  anchor: ISODate;
  entriesCoveringDay: (day: ISODate) => MealPlanEntry[];
  onAddDay: (day: ISODate) => void;
}) {
  const weeks = monthGrid(anchor);
  const today = todayIso();

  return (
    <div className="overflow-hidden rounded-xl border border-gray-200">
      {/* Weekday header */}
      <div className="grid grid-cols-7 border-b border-gray-200 bg-gray-50 text-center text-xs font-medium text-gray-500">
        {weeks[0].map((d) => (
          <div key={d} className="py-2">
            {weekdayShort(d)}
          </div>
        ))}
      </div>
      <div>
        {weeks.map((week) => (
          <div key={week[0]} className="grid grid-cols-7">
            {week.map((day) => {
              const inMonth = isSameMonth(day, anchor);
              const dayEntries = entriesCoveringDay(day);
              return (
                <div
                  key={day}
                  className={`min-h-24 border-b border-r border-gray-100 p-1 last:border-r-0 ${
                    inMonth ? "bg-white" : "bg-gray-50"
                  }`}
                >
                  <div className="mb-1 flex items-center justify-between">
                    <span
                      className={`text-xs ${
                        day === today
                          ? "flex h-5 w-5 items-center justify-center rounded-full bg-blue-600 font-semibold text-white"
                          : inMonth
                            ? "text-gray-700"
                            : "text-gray-400"
                      }`}
                    >
                      {dayNumber(day)}
                    </span>
                    <button
                      type="button"
                      aria-label={`Add a recipe on ${day}`}
                      onClick={() => onAddDay(day)}
                      className="rounded p-0.5 text-gray-300 hover:bg-blue-50 hover:text-blue-600"
                    >
                      <Plus className="h-3.5 w-3.5" />
                    </button>
                  </div>
                  <ul className="flex flex-col gap-0.5">
                    {dayEntries.slice(0, 3).map((e) => {
                      // A multi-day meal shows as a continuous bar: rounded/labeled only on its
                      // start day, plain on continuation days.
                      const isStart = e.date === day;
                      return (
                        <li
                          key={e.entryId}
                          title={e.available ? e.title ?? "" : "Recipe unavailable"}
                          className={`truncate px-1 py-0.5 text-[10px] leading-tight ${
                            e.available
                              ? "bg-blue-100 text-blue-800"
                              : "bg-gray-100 text-gray-400"
                          } ${(e.spanDays ?? 1) > 1 ? "" : "rounded"} ${
                            isStart ? "rounded-l" : ""
                          }`}
                        >
                          {isStart ? (e.available ? e.title : "Unavailable") : "·"}
                        </li>
                      );
                    })}
                    {dayEntries.length > 3 && (
                      <li className="px-1 text-[10px] text-gray-400">
                        +{dayEntries.length - 3} more
                      </li>
                    )}
                  </ul>
                </div>
              );
            })}
          </div>
        ))}
      </div>
    </div>
  );
}

// ── Shared entry row (week view) ───────────────────────────────────────────────

function EntryRow({
  entry,
  day,
  planServings,
  onRemove,
  onEdit,
}: {
  entry: MealPlanEntry;
  day: ISODate;
  planServings: number | null;
  onRemove: (entryId: string) => void;
  onEdit: (entry: MealPlanEntry) => void;
}) {
  const href = entry.available ? recipeHref(entry.recipeSource, entry.recipeId) : null;
  const isStart = entry.date === day;
  const span = entry.spanDays ?? 1;

  // Per-entry override wins; otherwise fall back to the plan default for display.
  const effectiveServings = entry.servings ?? planServings;

  // On the start day show the recipe title (+ a meal-prep badge for spans); on continuation
  // days show "Leftovers" so a multi-day meal reads as one cooked dish carried forward.
  const primary = !entry.available
    ? "Recipe unavailable"
    : isStart
      ? entry.title
      : "Leftovers";

  const label = (
    <span className="min-w-0 flex-1 truncate">
      <span className="block truncate text-sm text-gray-900">{primary}</span>
      <span className="flex items-center gap-1.5 truncate text-[10px] text-gray-400">
        {span > 1 && <span>{isStart ? `meal prep · ${span} days` : "from meal prep"}</span>}
        {isStart && effectiveServings != null && (
          <span>
            {span > 1 ? "· " : ""}
            serves {effectiveServings}
            {entry.servings == null ? "" : "*"}
          </span>
        )}
      </span>
    </span>
  );

  const inner = (
    <div className="flex min-w-0 flex-1 items-center gap-2">
      {!entry.available && <AlertCircle className="h-4 w-4 flex-shrink-0 text-gray-400" />}
      {label}
    </div>
  );

  return (
    <li
      className={`flex items-center gap-2 rounded-md border px-2 py-1 ${
        span > 1 ? "border-blue-200 bg-blue-50" : "border-gray-200"
      }`}
    >
      {href ? (
        <Link href={href} className="flex min-w-0 flex-1 items-center gap-2">
          {inner}
        </Link>
      ) : (
        inner
      )}
      {/* Edit + remove are offered on the start day so a span is acted on once, as a unit. */}
      {isStart && (
        <>
          <button
            type="button"
            aria-label="Edit entry"
            onClick={() => onEdit(entry)}
            className="flex-shrink-0 rounded p-1 text-gray-400 hover:bg-blue-50 hover:text-blue-600"
          >
            <Pencil className="h-3.5 w-3.5" />
          </button>
          <button
            type="button"
            aria-label="Remove from plan"
            onClick={() => onRemove(entry.entryId)}
            className="flex-shrink-0 rounded p-1 text-gray-400 hover:bg-red-50 hover:text-red-600"
          >
            <X className="h-3.5 w-3.5" />
          </button>
        </>
      )}
    </li>
  );
}
