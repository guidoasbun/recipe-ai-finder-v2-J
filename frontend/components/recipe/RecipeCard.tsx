"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { Recipe, GeneratedRecipe } from "@/types/recipe";
import { MODELS, IMAGE_MODELS } from "@/lib/constants";

interface Props {
  recipe: Recipe | GeneratedRecipe;
  saved?: boolean;
  model?: string;
  imageModel?: string;
}

export default function RecipeCard({ recipe, saved = false, model, imageModel }: Props) {
  const router = useRouter();
  const [saving, setSaving] = useState(false);
  const [saved_, setSaved_] = useState(saved);
  const [confirming, setConfirming] = useState(false);
  const [deleting, setDeleting] = useState(false);

  const id = "recipeId" in recipe ? recipe.recipeId : null;
  const imageUrl = "imageUrl" in recipe ? recipe.imageUrl : undefined;
  const [savedId, setSavedId] = useState<string | null>(null);
  const effectiveId = savedId ?? id;

  const recipeModel = "model" in recipe ? recipe.model : undefined;
  const recipeImageModel = "imageModel" in recipe ? recipe.imageModel : undefined;
  const effectiveModel = model ?? recipeModel;
  const effectiveImageModel = imageModel ?? recipeImageModel;
  const modelLabel = MODELS.find((m) => m.id === effectiveModel)?.label;
  const imageModelLabel = IMAGE_MODELS.find((m) => m.id === effectiveImageModel)?.label;

  const imageWidth = "imageWidth" in recipe ? recipe.imageWidth : undefined;
  const imageHeight = "imageHeight" in recipe ? recipe.imageHeight : undefined;
  const imageType = "imageType" in recipe ? recipe.imageType : undefined;
  const imageSizeBytes = "imageSizeBytes" in recipe ? recipe.imageSizeBytes : undefined;
  const imageGenerationMs = "imageGenerationMs" in recipe ? recipe.imageGenerationMs : undefined;
  const createdAt = "createdAt" in recipe ? recipe.createdAt : undefined;
  const textGenerationMs = "textGenerationMs" in recipe
    ? recipe.textGenerationMs
    : "generationMs" in recipe
    ? recipe.generationMs
    : undefined;
  
  async function handleDelete() {
    setDeleting(true);
    try {
      await fetch(`/api/backend/api/recipes/${effectiveId}`, { method: "DELETE" });
      router.refresh();
    } finally {
      setDeleting(false);
      setConfirming(false);
    }
  }

  async function handleSave() {
    setSaving(true);
    try {
      const res = await fetch("/api/backend/api/recipes", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          title: recipe.title,
          description: recipe.description,
          ingredients: recipe.ingredients,
          steps: recipe.steps,
          model,
          imageModel,
          textGenerationMs,
        }),
      });
      const data = await res.json();
      setSavedId(data.recipeId);
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
        <p className="mb-4 text-sm text-gray-500 line-clamp-6">{recipe.description}</p>
        <p className="mb-4 text-sm text-gray-500 line-clamp-6">{recipe.ingredients}</p>
        {createdAt && (
          <p className="mb-2 text-xs text-gray-400">
            Saved {new Date(createdAt).toLocaleDateString(undefined, { year: "numeric", month: "long", day: "numeric" })}
          </p>
        )}
        {(modelLabel || imageModelLabel) && (
          <div className="mb-2 flex flex-col gap-1">
            {modelLabel && (
              <span className="text-xs text-gray-400">
                <span className="font-medium text-gray-500">Text:</span> {modelLabel}
              </span>
            )}
            {textGenerationMs && (
              <span className="w-fit rounded-full bg-blue-50 px-2 py-0.5 text-xs text-blue-600">
                AI {textGenerationMs >= 1000 ? `${(textGenerationMs / 1000).toFixed(1)}s` : `${textGenerationMs}ms`}
              </span>
            )}
            {imageModelLabel && (
              <span className="text-xs text-gray-400">
                <span className="font-medium text-gray-500">Image:</span> {imageModelLabel}
              </span>
            )}
          </div>
        )}
      
        {(imageWidth || imageType || imageSizeBytes || imageGenerationMs) && (
          <div className="mb-4 flex flex-wrap gap-1.5">
            {imageWidth && imageHeight && (
              <span className="rounded-full bg-gray-100 px-2 py-0.5 text-xs text-gray-500">
                {imageWidth} × {imageHeight} px
              </span>
            )}
            {imageType && (
              <span className="rounded-full bg-gray-100 px-2 py-0.5 text-xs text-gray-500">
                {imageType}
              </span>
            )}
            {imageSizeBytes && (
              <span className="rounded-full bg-gray-100 px-2 py-0.5 text-xs text-gray-500">
                {imageSizeBytes < 1024 * 1024
                  ? `${(imageSizeBytes / 1024).toFixed(1)} KB`
                  : `${(imageSizeBytes / (1024 * 1024)).toFixed(2)} MB`}
              </span>
            )}
            {imageGenerationMs && (
              <span className="rounded-full bg-gray-100 px-2 py-0.5 text-xs text-gray-500">
                AI {imageGenerationMs >= 1000
                  ? `${(imageGenerationMs / 1000).toFixed(1)}s`
                  : `${imageGenerationMs}ms`}
              </span>
            )}
          </div>
        )}
        <div className="mt-auto flex gap-2">
          {effectiveId && (
            <Link
              href={`/recipes/${effectiveId}`}
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
          {saved_ && !confirming && (
            <button
              onClick={() => setConfirming(true)}
              disabled={deleting}
              className="flex-1 rounded-lg border border-red-300 px-3 py-2 text-xs font-medium text-red-600 hover:bg-red-50 disabled:opacity-50"
            >
              Delete
            </button>
          )}
          {saved_ && confirming && (
            <>
              <button
                onClick={() => setConfirming(false)}
                className="flex-1 rounded-lg border border-gray-300 px-3 py-2 text-xs font-medium text-gray-700 hover:bg-gray-50"
              >
                Cancel
              </button>
              <button
                onClick={handleDelete}
                disabled={deleting}
                className="flex-1 rounded-lg bg-red-600 px-3 py-2 text-xs font-medium text-white hover:bg-red-700 disabled:opacity-50"
              >
                {deleting ? "Deleting…" : "Delete?"}
              </button>
            </>
          )}
        </div>
      </div>
    </div>
  );
}
