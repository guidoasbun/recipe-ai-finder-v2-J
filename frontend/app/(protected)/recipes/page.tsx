"use client";

import { useState, useEffect, useCallback, useRef } from "react";
import { Loader2, Search } from "lucide-react";
import { Recipe } from "@/types/recipe";
import RecipeCard from "@/components/recipe/RecipeCard";
import RecipesPagination from "@/components/recipe/RecipesPagination";

interface SavedRecipeResults {
  items: Recipe[];
  page: number;
  pageSize: number;
  totalMatches: number;
}

export default function RecipesPage() {
  const [query, setQuery] = useState("");
  const [submittedQuery, setSubmittedQuery] = useState("");
  const [page, setPage] = useState(0);

  const [results, setResults] = useState<SavedRecipeResults | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);

  // Monotonic request id: only the newest request may commit its result (fixes races when
  // the user paginates or searches faster than responses arrive).
  const requestSeq = useRef(0);
  const abortRef = useRef<AbortController | null>(null);

  const loadRecipes = useCallback(async () => {
    const seq = ++requestSeq.current;
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;

    setLoading(true);
    setError(false);
    try {
      const params = new URLSearchParams();
      if (submittedQuery.trim()) params.set("q", submittedQuery.trim());
      params.set("page", String(page));

      const res = await fetch(`/api/backend/api/recipes?${params.toString()}`, {
        signal: controller.signal,
      });
      if (!res.ok) throw new Error(`Failed to load recipes: ${res.status}`);
      const data: SavedRecipeResults = await res.json();
      // Ignore stale responses that resolved after a newer request started.
      if (seq !== requestSeq.current) return;

      // If the collection shrank (e.g. deletions) the requested page can now be out of range:
      // the server returns the true totalMatches but empty items. Clamp to the last valid page
      // and let the resulting state change refetch, so we never render an empty grid with an
      // impossible "Page 100 of 2" label (Requirement 1.5).
      const lastPage = Math.max(
        0,
        Math.ceil(data.totalMatches / data.pageSize) - 1,
      );
      if (data.totalMatches > 0 && data.page > lastPage) {
        setPage(lastPage);
        return;
      }

      setResults(data);
    } catch (err) {
      if ((err as Error)?.name === "AbortError") return; // superseded, not a failure
      if (seq !== requestSeq.current) return;
      setError(true);
    } finally {
      if (seq === requestSeq.current) setLoading(false);
    }
  }, [submittedQuery, page]);

  useEffect(() => {
    loadRecipes();
  }, [loadRecipes]);

  function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setPage(0);
    setSubmittedQuery(query);
  }

  const totalPages = results
    ? Math.max(1, Math.ceil(results.totalMatches / results.pageSize))
    : 1;
  const hasQuery = submittedQuery.trim().length > 0;
  const isEmpty = !loading && !error && (results?.totalMatches ?? 0) === 0;

  return (
    <div>
      <h1 className="mb-6 text-2xl font-bold text-gray-900">Saved Recipes</h1>

      <form onSubmit={onSubmit} className="mb-6 flex gap-2">
        <div className="relative flex-1">
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-gray-400" />
          <input
            type="text"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search your saved recipes..."
            aria-label="Search your saved recipes"
            maxLength={200}
            className="w-full text-gray-900 rounded-md border border-gray-300 py-2 pl-9 pr-3 text-sm focus:border-blue-500 focus:outline-none"
          />
        </div>
        <button
          type="submit"
          className="rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 transition-colors"
        >
          Search
        </button>
      </form>

      {loading ? (
        <div className="flex items-center justify-center py-16">
          <Loader2 className="h-8 w-8 animate-spin text-gray-400" />
        </div>
      ) : error ? (
        <div className="rounded-lg border border-red-200 bg-red-50 p-6 text-center">
          <p className="text-sm text-red-700">
            We couldn&apos;t load your saved recipes.
          </p>
          <button
            onClick={loadRecipes}
            className="mt-4 inline-flex items-center gap-2 rounded-md border border-red-300 px-4 py-2 text-sm font-medium text-red-700 hover:bg-red-100 transition-colors"
          >
            Retry
          </button>
        </div>
      ) : isEmpty ? (
        hasQuery ? (
          <p className="text-gray-500">No recipes match your search.</p>
        ) : (
          <p className="text-gray-500">
            No saved recipes yet. Generate some first!
          </p>
        )
      ) : (
        <>
          <div className="mb-4 flex items-center justify-between gap-3 text-sm text-gray-600">
            <span>
              {results?.totalMatches} recipe
              {results?.totalMatches === 1 ? "" : "s"}
            </span>
            <span>
              Page {page + 1} of {totalPages}
            </span>
          </div>

          {totalPages > 1 && (
            <div className="mb-6">
              <RecipesPagination
                page={page}
                totalPages={totalPages}
                onPageChange={setPage}
                position="top"
              />
            </div>
          )}

          <div className="grid gap-6 sm:grid-cols-2 lg:grid-cols-3">
            {results?.items.map((recipe) => (
              <RecipeCard key={recipe.recipeId} recipe={recipe} saved />
            ))}
          </div>

          {totalPages > 1 && (
            <div className="mt-6">
              <RecipesPagination
                page={page}
                totalPages={totalPages}
                onPageChange={setPage}
                position="bottom"
              />
            </div>
          )}
        </>
      )}
    </div>
  );
}
