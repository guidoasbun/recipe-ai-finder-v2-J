"use client";

import { useState } from "react";
import Link from "next/link";
import { Recipe, GeneratedRecipe } from "@/types/recipe";

interface Props {
  recipe: Recipe | GeneratedRecipe;
  saved?: boolean;
  model?: string;
}

export default function RecipeCard({ recipe, saved = false, model }: Props) {
  const [saving, setSaving] = useState(false);
  const [saved_, setSaved_] = useState(saved);

  const id = "recipeId" in recipe ? recipe.recipeId : null;
  const imageUrl = "imageUrl" in recipe ? recipe.imageUrl : undefined;

  async function handleSave() {
    setSaving(true);
    try {
      await fetch("/api/backend/api/recipes", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          title: recipe.title,
          description: recipe.description,
          ingredients: recipe.ingredients,
          steps: recipe.steps,
          model,
        }),
      });
      setSaved_(true);
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="flex flex-col rounded-2xl border border-gray-200 bg-white shadow-sm overflow-hidden">
      {imageUrl && (
        <img src={imageUrl} alt={recipe.title} className="h-40 w-full object-cover" />
      )}
      <div className="flex flex-1 flex-col p-4">
        <h3 className="mb-1 font-semibold text-gray-900">{recipe.title}</h3>
        <p className="mb-4 text-sm text-gray-500 line-clamp-2">{recipe.description}</p>
        <div className="mt-auto flex gap-2">
          {id && (
            <Link
              href={`/recipes/${id}`}
              className="flex-1 rounded-lg border border-gray-300 px-3 py-2 text-center text-xs font-medium text-gray-700 hover:bg-gray-50"
            >
              View
            </Link>
          )}
          {!saved_ && (
            <button
              onClick={handleSave}
              disabled={saving}
              className="flex-1 rounded-lg bg-blue-600 px-3 py-2 text-xs font-medium text-white hover:bg-blue-700 disabled:opacity-50"
            >
              {saving ? "Saving..." : "Save Recipe"}
            </button>
          )}
          {saved_ && (
            <span className="flex-1 rounded-lg bg-green-50 px-3 py-2 text-center text-xs font-medium text-green-700">
              Saved
            </span>
          )}
        </div>
      </div>
    </div>
  );
}
