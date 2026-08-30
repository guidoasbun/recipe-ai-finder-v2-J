"use client";

import { useState, useEffect, useCallback } from "react";
import Link from "next/link";
import { Loader2, Search } from "lucide-react";
import { DIETARY_RESTRICTIONS, dietaryLabel } from "@/lib/dietary";

interface CatalogRecipe {
  catalogRecipeId: string;
  title: string;
  description: string | null;
  ingredients: string[];
  steps: string[];
  imageUrl: string | null;
  dietaryTags: string[];
  sourceName: string | null;
  sourceCountry: string | null;
}

interface SearchResults {
  items: CatalogRecipe[];
  page: number;
  pageSize: number;
  totalMatches: number;
}

export default function BrowsePage() {
  const [query, setQuery] = useState("");
  const [submittedQuery, setSubmittedQuery] = useState("");
  const [activeTags, setActiveTags] = useState<string[]>([]);
  const [tagsInitialized, setTagsInitialized] = useState(false);
  const [page, setPage] = useState(0);

  const [results, setResults] = useState<SearchResults | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);

  // Default the dietary filters to the user's saved restrictions on first load.
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const res = await fetch("/api/backend/api/account/dietary-restrictions");
        if (res.ok) {
          const saved: string[] = await res.json();
          if (!cancelled) setActiveTags(saved);
        }
      } catch {
        // Non-fatal: fall back to no filters.
      } finally {
        if (!cancelled) setTagsInitialized(true);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  const runSearch = useCallback(async () => {
    setLoading(true);
    setError(false);
    try {
      const params = new URLSearchParams();
      if (submittedQuery.trim()) params.set("q", submittedQuery.trim());
      activeTags.forEach((t) => params.append("tags", t));
      params.set("page", String(page));
      const res = await fetch(`/api/backend/api/catalog/search?${params.toString()}`);
      if (!res.ok) throw new Error(`Search failed: ${res.status}`);
      const data: SearchResults = await res.json();
      setResults(data);
    } catch {
      setError(true);
    } finally {
      setLoading(false);
    }
  }, [submittedQuery, activeTags, page]);

  // Run search once dietary defaults are loaded, and whenever query/tags/page change.
  useEffect(() => {
    if (!tagsInitialized) return;
    runSearch();
  }, [tagsInitialized, runSearch]);

  function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setPage(0);
    setSubmittedQuery(query);
  }

  function toggleTag(tag: string) {
    setPage(0);
    setActiveTags((prev) =>
      prev.includes(tag) ? prev.filter((t) => t !== tag) : [...prev, tag],
    );
  }

  const totalPages = results
    ? Math.max(1, Math.ceil(results.totalMatches / results.pageSize))
    : 1;

  return (
    <div>
      <h1 className="mb-2 text-2xl font-bold text-gray-900">
        Look for Existing Recipes
      </h1>
      <p className="mb-6 text-sm text-gray-500">
        Search a catalog of ready-made recipes. Filters default to your saved dietary
        restrictions.
      </p>

      <form onSubmit={onSubmit} className="mb-4 flex gap-2">
        <div className="relative flex-1">
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-gray-400" />
          <input
            type="text"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search by name or ingredient..."
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

      <div className="mb-6 flex flex-wrap gap-2">
        {DIETARY_RESTRICTIONS.map(({ value, label }) => {
          const active = activeTags.includes(value);
          return (
            <button
              key={value}
              type="button"
              onClick={() => toggleTag(value)}
              className={`rounded-full border px-3 py-1 text-xs font-medium transition-colors ${
                active
                  ? "border-blue-600 bg-blue-600 text-white"
                  : "border-gray-300 bg-white text-gray-600 hover:border-gray-400"
              }`}
            >
              {label}
            </button>
          );
        })}
      </div>

      {loading ? (
        <div className="flex items-center justify-center py-16">
          <Loader2 className="h-8 w-8 animate-spin text-gray-400" />
        </div>
      ) : error ? (
        <div className="rounded-lg border border-red-200 bg-red-50 p-6 text-center">
          <p className="text-sm text-red-700">We couldn&apos;t run that search.</p>
          <button
            onClick={runSearch}
            className="mt-4 inline-flex items-center gap-2 rounded-md border border-red-300 px-4 py-2 text-sm font-medium text-red-700 hover:bg-red-100 transition-colors"
          >
            Retry
          </button>
        </div>
      ) : !results || results.items.length === 0 ? (
        <p className="text-gray-500">
          No recipes match your search. Try different terms or fewer filters.
        </p>
      ) : (
        <>
          <div className="grid gap-6 sm:grid-cols-2 lg:grid-cols-3">
            {results.items.map((r) => (
              <Link
                key={r.catalogRecipeId}
                href={`/browse/${r.catalogRecipeId}`}
                className="flex flex-col overflow-hidden rounded-xl border border-gray-200 bg-white transition-shadow hover:shadow-md"
              >
                {r.imageUrl && (
                  <img
                    src={r.imageUrl}
                    alt={r.title}
                    className="h-40 w-full object-cover"
                  />
                )}
                <div className="flex flex-1 flex-col p-4">
                  <h2 className="mb-1 font-semibold text-gray-900">{r.title}</h2>
                  {r.description && (
                    <p className="mb-2 line-clamp-2 text-xs text-gray-500">
                      {r.description}
                    </p>
                  )}
                  <div className="mt-auto flex flex-wrap gap-1 pt-2">
                    {r.dietaryTags?.slice(0, 3).map((t) => (
                      <span
                        key={t}
                        className="rounded-full bg-gray-100 px-2 py-0.5 text-[10px] text-gray-600"
                      >
                        {dietaryLabel(t)}
                      </span>
                    ))}
                  </div>
                </div>
              </Link>
            ))}
          </div>

          <div className="mt-8 flex items-center justify-between text-sm text-gray-600">
            <span>
              {results.totalMatches} result{results.totalMatches === 1 ? "" : "s"}
            </span>
            <div className="flex items-center gap-3">
              <button
                type="button"
                disabled={page === 0}
                onClick={() => setPage((p) => Math.max(0, p - 1))}
                className="rounded-md border border-gray-300 px-3 py-1 disabled:opacity-40 hover:bg-gray-50 transition-colors"
              >
                Previous
              </button>
              <span>
                Page {page + 1} of {totalPages}
              </span>
              <button
                type="button"
                disabled={page + 1 >= totalPages}
                onClick={() => setPage((p) => p + 1)}
                className="rounded-md border border-gray-300 px-3 py-1 disabled:opacity-40 hover:bg-gray-50 transition-colors"
              >
                Next
              </button>
            </div>
          </div>
        </>
      )}
    </div>
  );
}
