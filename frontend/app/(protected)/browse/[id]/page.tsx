"use client";

import { useState, useEffect, useCallback } from "react";
import { useParams, notFound } from "next/navigation";
import Link from "next/link";
import { Loader2, ArrowLeft } from "lucide-react";
import { dietaryLabel } from "@/lib/dietary";

interface CatalogRecipe {
  catalogRecipeId: string;
  title: string;
  description: string | null;
  ingredients: string[];
  steps: string[];
  imageUrl: string | null;
  dietaryTags: string[];
  sourceName: string | null;
  sourceUrl: string | null;
  sourceLicense: string | null;
  sourceCountry: string | null;
}

export default function CatalogRecipeDetailPage() {
  const params = useParams<{ id: string }>();
  const id = params?.id;

  const [recipe, setRecipe] = useState<CatalogRecipe | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);
  const [missing, setMissing] = useState(false);

  const loadRecipe = useCallback(async () => {
    if (!id) return;
    setLoading(true);
    setError(false);
    setMissing(false);
    try {
      const res = await fetch(`/api/backend/api/catalog/${id}`);
      if (res.status === 404) {
        setMissing(true);
        return;
      }
      if (!res.ok) throw new Error(`Failed to load recipe: ${res.status}`);
      const data: CatalogRecipe = await res.json();
      setRecipe(data);
    } catch {
      setError(true);
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => {
    loadRecipe();
  }, [loadRecipe]);

  if (missing) {
    notFound();
  }

  if (loading) {
    return (
      <div className="flex items-center justify-center py-16">
        <Loader2 className="h-8 w-8 animate-spin text-gray-400" />
      </div>
    );
  }

  if (error || !recipe) {
    return (
      <div className="mx-auto max-w-2xl rounded-lg border border-red-200 bg-red-50 p-6 text-center">
        <p className="text-sm text-red-700">We couldn&apos;t load this recipe.</p>
        <button
          onClick={loadRecipe}
          className="mt-4 inline-flex items-center gap-2 rounded-md border border-red-300 px-4 py-2 text-sm font-medium text-red-700 hover:bg-red-100 transition-colors"
        >
          Retry
        </button>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-2xl">
      <Link
        href="/browse"
        className="mb-4 inline-flex items-center gap-1 text-sm text-gray-500 hover:text-gray-700 transition-colors"
      >
        <ArrowLeft className="h-4 w-4" />
        Back to search
      </Link>

      {recipe.imageUrl && (
        <img
          src={recipe.imageUrl}
          alt={recipe.title}
          className="mb-6 h-64 w-full rounded-2xl object-cover"
        />
      )}

      <h1 className="mb-2 text-3xl font-bold text-gray-900">{recipe.title}</h1>

      {recipe.dietaryTags?.length > 0 && (
        <div className="mb-3 flex flex-wrap gap-1">
          {recipe.dietaryTags.map((t) => (
            <span
              key={t}
              className="rounded-full bg-blue-50 px-2.5 py-1 text-xs text-blue-600"
            >
              {dietaryLabel(t)}
            </span>
          ))}
        </div>
      )}

      {recipe.description && (
        <p className="mb-6 text-gray-500">{recipe.description}</p>
      )}

      <section className="mb-6">
        <h2 className="mb-3 text-lg font-semibold text-gray-800">Ingredients</h2>
        <ul className="space-y-1">
          {recipe.ingredients.map((ing, i) => (
            <li key={i} className="flex items-start gap-2 text-sm text-gray-700">
              <span className="mt-1 h-1.5 w-1.5 flex-shrink-0 rounded-full bg-blue-500" />
              {ing}
            </li>
          ))}
        </ul>
      </section>

      <section>
        <h2 className="mb-3 text-lg font-semibold text-gray-800">Steps</h2>
        <ol className="space-y-3">
          {recipe.steps.map((step, i) => (
            <li key={i} className="flex gap-3 text-sm text-gray-700">
              <span className="flex h-6 w-6 flex-shrink-0 items-center justify-center rounded-full bg-blue-600 text-xs font-bold text-white">
                {i + 1}
              </span>
              {step}
            </li>
          ))}
        </ol>
      </section>

      {(recipe.sourceName || recipe.sourceUrl) && (
        <div className="mt-8 border-t border-gray-200 pt-4 text-xs text-gray-400">
          Source:{" "}
          {recipe.sourceUrl ? (
            <a
              href={recipe.sourceUrl}
              target="_blank"
              rel="noopener noreferrer"
              className="underline hover:text-gray-600"
            >
              {recipe.sourceName ?? recipe.sourceUrl}
            </a>
          ) : (
            recipe.sourceName
          )}
          {recipe.sourceCountry ? ` · ${recipe.sourceCountry}` : ""}
        </div>
      )}
    </div>
  );
}
