"use client";

import { useState } from "react";
import { X } from "lucide-react";
import { MealPlanEntry, UpdateEntryRequest } from "@/types/mealPlan";
import { MEAL_SLOTS, MealSlot } from "@/lib/mealSlots";

interface Props {
  entry: MealPlanEntry;
  /** Plan-level default servings, shown as the fallback when no per-entry override is set. */
  planServings: number | null;
  onSave: (changes: UpdateEntryRequest) => void;
  onClose: () => void;
}

/**
 * Touch-first editor for an existing entry: move it to a different date/slot, adjust the
 * meal-prep span, and set (or clear) a per-entry servings override. Covers the move and
 * servings actions (Requirements 2.6/3.3/6.3) that were previously unavailable in the UI.
 *
 * <p>Only changed fields are sent. Servings uses an empty value to mean "use the plan default"
 * (clears the override); a positive number sets an override.
 */
export default function EntryEditor({ entry, planServings, onSave, onClose }: Props) {
  const [date, setDate] = useState(entry.date);
  const [slot, setSlot] = useState<MealSlot>(entry.slot);
  const [spanDays, setSpanDays] = useState(entry.spanDays ?? 1);
  const [servings, setServings] = useState<string>(
    entry.servings != null ? String(entry.servings) : "",
  );

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    const changes: UpdateEntryRequest = {};
    if (date !== entry.date) changes.date = date;
    if (slot !== entry.slot) changes.slot = slot;
    if (spanDays !== (entry.spanDays ?? 1)) changes.spanDays = spanDays;

    const trimmed = servings.trim();
    const nextServings = trimmed === "" ? null : Number(trimmed);
    const currentServings = entry.servings ?? null;
    if (nextServings !== currentServings) {
      // null clears the override (falls back to the plan default); a number sets it.
      changes.servings = nextServings ?? undefined;
    }

    onSave(changes);
  }

  const title = entry.available ? entry.title ?? "Recipe" : "Recipe unavailable";

  return (
    <div
      className="fixed inset-0 z-50 flex flex-col bg-white sm:items-center sm:justify-center sm:bg-black/40 sm:p-4"
      role="dialog"
      aria-modal="true"
      aria-label="Edit entry"
    >
      <div className="flex w-full flex-col sm:h-auto sm:max-w-md sm:rounded-xl sm:bg-white sm:shadow-xl">
        <div className="flex items-center justify-between border-b border-gray-200 p-4">
          <div className="min-w-0">
            <h2 className="truncate text-lg font-semibold text-gray-900">Edit entry</h2>
            <p className="truncate text-xs text-gray-500">{title}</p>
          </div>
          <button
            type="button"
            aria-label="Close"
            onClick={onClose}
            className="rounded-md p-2 text-gray-500 hover:bg-gray-100"
          >
            <X className="h-5 w-5" />
          </button>
        </div>

        <form onSubmit={handleSubmit} className="flex flex-col gap-4 p-4">
          <div className="flex flex-col gap-1">
            <label htmlFor="entry-date" className="text-sm font-medium text-gray-700">
              Date
            </label>
            <input
              id="entry-date"
              type="date"
              value={date}
              onChange={(e) => setDate(e.target.value)}
              className="rounded-md border border-gray-300 px-3 py-2 text-sm text-gray-900 focus:border-blue-500 focus:outline-none"
            />
          </div>

          <div className="flex flex-col gap-1">
            <label htmlFor="entry-slot" className="text-sm font-medium text-gray-700">
              Meal slot
            </label>
            <select
              id="entry-slot"
              value={slot}
              onChange={(e) => setSlot(e.target.value as MealSlot)}
              className="rounded-md border border-gray-300 px-3 py-2 text-sm text-gray-900 focus:border-blue-500 focus:outline-none"
            >
              {MEAL_SLOTS.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </div>

          <div className="flex flex-col gap-1">
            <label htmlFor="entry-span" className="text-sm font-medium text-gray-700">
              Meal prep for
            </label>
            <select
              id="entry-span"
              value={spanDays}
              onChange={(e) => setSpanDays(Number(e.target.value))}
              className="rounded-md border border-gray-300 px-3 py-2 text-sm text-gray-900 focus:border-blue-500 focus:outline-none"
            >
              {Array.from({ length: 14 }, (_, i) => i + 1).map((n) => (
                <option key={n} value={n}>
                  {n === 1 ? "1 day" : `${n} days`}
                </option>
              ))}
            </select>
          </div>

          <div className="flex flex-col gap-1">
            <label htmlFor="entry-servings" className="text-sm font-medium text-gray-700">
              Servings
            </label>
            <input
              id="entry-servings"
              type="number"
              min={1}
              max={50}
              value={servings}
              onChange={(e) => setServings(e.target.value)}
              placeholder={
                planServings != null ? `Plan default (${planServings})` : "Plan default"
              }
              className="rounded-md border border-gray-300 px-3 py-2 text-sm text-gray-900 focus:border-blue-500 focus:outline-none"
            />
            <span className="text-xs text-gray-400">
              Leave blank to use the plan default
              {planServings != null ? ` (${planServings})` : ""}.
            </span>
          </div>

          <div className="flex justify-end gap-2 pt-2">
            <button
              type="button"
              onClick={onClose}
              className="rounded-md border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50"
            >
              Cancel
            </button>
            <button
              type="submit"
              className="rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
            >
              Save
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
