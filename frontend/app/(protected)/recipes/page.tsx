"use client";

import { useState, useEffect, useCallback } from "react";
import { Loader2 } from "lucide-react";
import { Recipe } from "@/types/recipe";
import RecipeCard from "@/components/recipe/RecipeCard";

export default function RecipesPage() {
  const [recipes, setRecipes] = useState<Recipe[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);

  const loadRecipes = useCallback(async () => {
    setLoading(true);
    setError(false);
    try {
      const res = await fetch("/api/backend/api/recipes");
      if (!res.ok) throw new Error(`Failed to load recipes: ${res.status}`);
      const data: Recipe[] = await res.json();
      setRecipes(data);
    } catch {
      setError(true);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadRecipes();
  }, [loadRecipes]);

  const sortedRecipes = [...recipes].sort(
    (a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime(),
  );

  return (
    <div>
      <h1 className="mb-6 text-2xl font-bold text-gray-900">Saved Recipes</h1>

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
      ) : recipes.length === 0 ? (
        <p className="text-gray-500">
          No saved recipes yet. Generate some first!
        </p>
      ) : (
        <div className="grid gap-6 sm:grid-cols-2 lg:grid-cols-3">
          {sortedRecipes.map((recipe) => (
            <RecipeCard key={recipe.recipeId} recipe={recipe} saved />
          ))}
        </div>
      )}
    </div>
  );
}
