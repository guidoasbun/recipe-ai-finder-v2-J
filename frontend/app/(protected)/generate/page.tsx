"use client";

import { useEffect, useState } from "react";
import { useSearchParams, useRouter } from "next/navigation";
import { GeneratedRecipe } from "@/types/recipe";
import RecipeCard from "@/components/recipe/RecipeCard";

export default function GeneratePage() {
  const searchParams = useSearchParams();
  const router = useRouter();
  const [recipes, setRecipes] = useState<GeneratedRecipe[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const ingredients = searchParams.get("ingredients") ?? "";
  const model = searchParams.get("model") ?? "CLAUDE_HAIKU";

  useEffect(() => {
    if (!ingredients) {
      router.replace("/dashboard");
      return;
    }

    async function fetchRecipes() {
      try {
        const res = await fetch(`/api/backend/api/recipes/generate`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            ingredients: ingredients.split(",").map((i) => i.trim()),
            model,
          }),
        });

        if (!res.ok) throw new Error("Failed to generate recipes");

        const data: GeneratedRecipe[] = await res.json();
        setRecipes(data);
      } catch (err) {
        setError("Something went wrong. Please try again.");
      } finally {
        setLoading(false);
      }
    }

    fetchRecipes();
  }, []);

  if (loading) {
    return (
      <div className="flex flex-col items-center justify-center py-24 gap-4">
        <div className="h-10 w-10 animate-spin rounded-full border-4 border-blue-600 border-t-transparent" />
        <p className="text-gray-500 text-sm">Generating recipes with AI...</p>
      </div>
    );
  }

  if (error) {
    return (
      <div className="text-center py-24">
        <p className="text-red-500">{error}</p>
        <button onClick={() => router.push("/dashboard")} className="mt-4 text-blue-600 underline text-sm">
          Go back
        </button>
      </div>
    );
  }

  return (
    <div>
      <div className="mb-6 flex items-center justify-between">
        <h1 className="text-2xl font-bold text-gray-900">Generated Recipes</h1>
        <button onClick={() => router.push("/dashboard")} className="text-sm text-blue-600 underline">
          Try again
        </button>
      </div>
      <div className="grid gap-6 sm:grid-cols-2 lg:grid-cols-3">
        {recipes.map((recipe, i) => (
          <RecipeCard key={i} recipe={recipe} />
        ))}
      </div>
    </div>
  );
}
