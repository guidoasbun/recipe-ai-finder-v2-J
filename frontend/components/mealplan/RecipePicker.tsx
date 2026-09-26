"use client";

import { useState, useEffect, useCallback, useRef } from "react";
import { Loader2, Search, X } from "lucide-react";
import { RecipeSource } from "@/types/mealPlan";
import { mealSlotLabel } from "@/lib/mealSlots";

interface PickResult {
  source: RecipeSource;
  recipeId: string;
  title: string;
  spanDays: number;
}

interface RawRecipe {
  recipeId?: string; // saved
  catalogRecipeId?: string; // catalog
  title: string;
  imageUrl?: string | null;
}

// Normalized item; only items with a usable id survive (no empty keys / no blank recipeId adds).
interface PickerItem {
  id: string;
  title: string;
  imageUrl: string | null;
}

interface Props {
  date: string;
  slot: string;
  onPick: (result: PickResult) => void;
  onClose: () => void;
}

/**
 * Touch-first, full-screen/bottom-sheet recipe picker. Reuses the existing search endpoints
 * (saved recipes + shared catalog). Includes a meal-prep span control so a picked recipe can
 * cover several consecutive days from the chosen date. Picking hands the source + id + span
 * back to the caller, which adds the entry.
 */
export default function RecipePicker({ date, slot, onPick, onClose }: Props) {
  const [source, setSource] = useState<RecipeSource>("CATALOG");
  const [query, setQuery] = useState("");
  const [submitted, setSubmitted] = useState("");
  const [items, setItems] = useState<PickerItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(false);
  const [spanDays, setSpanDays] = useState(1);

  const requestSeq = useRef(0);
  const abortRef = useRef<AbortController | null>(null);

  const run = useCallback(async () => {
    const seq = ++requestSeq.current;
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;

    setLoading(true);
    setError(false);
    try {
      const params = new URLSearchParams();
      if (submitted.trim()) params.set("q", submitted.trim());
      const path =
        source === "CATALOG"
          ? `/api/backend/api/catalog/search?${params.toString()}`
          : `/api/backend/api/recipes?${params.toString()}`;
      const res = await fetch(path, { signal: controller.signal });
      if (!res.ok) throw new Error(String(res.status));
      const data = await res.json();
      if (seq !== requestSeq.current) return;
      const raw: RawRecipe[] = data.items ?? [];
      const normalized: PickerItem[] = raw
        .map((r) => ({
          id: (source === "CATALOG" ? r.catalogRecipeId : r.recipeId) ?? "",
          title: r.title,
          imageUrl: r.imageUrl ?? null,
        }))
        .filter((r) => r.id !== "");
      setItems(normalized);
    } catch (err) {
      if ((err as Error)?.name === "AbortError") return;
      if (seq !== requestSeq.current) return;
      setError(true);
    } finally {
      if (seq === requestSeq.current) setLoading(false);
    }
  }, [source, submitted]);

  useEffect(() => {
    run();
  }, [run]);

  return (
    <div
      className="fixed inset-0 z-50 flex flex-col bg-white sm:items-center sm:justify-center sm:bg-black/40 sm:p-4"
      role="dialog"
      aria-modal="true"
      aria-label="Add a recipe"
    >
      <div className="flex h-full w-full flex-col sm:h-[80vh] sm:max-w-lg sm:rounded-xl sm:bg-white sm:shadow-xl">
        <div className="flex items-center justify-between border-b border-gray-200 p-4">
          <div className="min-w-0">
            <h2 className="truncate text-lg font-semibold text-gray-900">Add a recipe</h2>
            <p className="text-xs text-gray-500">
              {mealSlotLabel(slot)} · {date}
            </p>
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

        {/* Meal-prep span control */}
        <div className="flex items-center gap-3 border-b border-gray-100 px-4 py-3">
          <label htmlFor="span-days" className="text-sm font-medium text-gray-700">
            Meal prep for
          </label>
          <select
            id="span-days"
            value={spanDays}
            onChange={(e) => setSpanDays(Number(e.target.value))}
            className="rounded-md border border-gray-300 px-2 py-1.5 text-sm text-gray-900 focus:border-blue-500 focus:outline-none"
          >
            {Array.from({ length: 14 }, (_, i) => i + 1).map((n) => (
              <option key={n} value={n}>
                {n === 1 ? "1 day" : `${n} days`}
              </option>
            ))}
          </select>
          {spanDays > 1 && (
            <span className="text-xs text-gray-400">cook once, covers {spanDays} days</span>
          )}
        </div>

        <div className="flex gap-2 p-4">
          <button
            type="button"
            aria-pressed={source === "CATALOG"}
            onClick={() => setSource("CATALOG")}
            className={`flex-1 rounded-md border px-3 py-2 text-sm font-medium transition-colors ${
              source === "CATALOG"
                ? "border-blue-600 bg-blue-600 text-white"
                : "border-gray-300 bg-white text-gray-600"
            }`}
          >
            Catalog
          </button>
          <button
            type="button"
            aria-pressed={source === "SAVED"}
            onClick={() => setSource("SAVED")}
            className={`flex-1 rounded-md border px-3 py-2 text-sm font-medium transition-colors ${
              source === "SAVED"
                ? "border-blue-600 bg-blue-600 text-white"
                : "border-gray-300 bg-white text-gray-600"
            }`}
          >
            My recipes
          </button>
        </div>

        <form
          onSubmit={(e) => {
            e.preventDefault();
            setSubmitted(query);
          }}
          className="flex gap-2 px-4 pb-4"
        >
          <div className="relative flex-1">
            <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-gray-400" />
            <input
              type="text"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Search recipes..."
              aria-label="Search recipes"
              maxLength={200}
              className="w-full rounded-md border border-gray-300 py-2 pl-9 pr-3 text-sm text-gray-900 focus:border-blue-500 focus:outline-none"
            />
          </div>
          <button
            type="submit"
            className="rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
          >
            Search
          </button>
        </form>

        <div className="min-h-0 flex-1 overflow-y-auto px-4 pb-4">
          {loading ? (
            <div className="flex items-center justify-center py-12">
              <Loader2 className="h-6 w-6 animate-spin text-gray-400" />
            </div>
          ) : error ? (
            <div className="rounded-lg border border-red-200 bg-red-50 p-4 text-center">
              <p className="text-sm text-red-700">Search failed.</p>
              <button
                onClick={run}
                className="mt-3 rounded-md border border-red-300 px-3 py-1 text-sm text-red-700 hover:bg-red-100"
              >
                Retry
              </button>
            </div>
          ) : items.length === 0 ? (
            <p className="py-8 text-center text-sm text-gray-500">No recipes found.</p>
          ) : (
            <ul className="flex flex-col gap-2">
              {items.map((r) => (
                <li key={r.id}>
                  <button
                    type="button"
                    onClick={() =>
                      onPick({ source, recipeId: r.id, title: r.title, spanDays })
                    }
                    className="flex w-full items-center gap-3 rounded-lg border border-gray-200 p-3 text-left hover:border-blue-400 hover:bg-blue-50 transition-colors"
                  >
                    {r.imageUrl ? (
                      // eslint-disable-next-line @next/next/no-img-element
                      <img
                        src={r.imageUrl}
                        alt={r.title}
                        className="h-12 w-12 flex-shrink-0 rounded-md object-cover"
                      />
                    ) : (
                      <span className="h-12 w-12 flex-shrink-0 rounded-md bg-gray-100" />
                    )}
                    <span className="min-w-0 flex-1 truncate text-sm font-medium text-gray-900">
                      {r.title}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>
    </div>
  );
}
