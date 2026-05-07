"use client";

import { useState, useEffect } from "react";
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
  const id = "recipeId" in recipe ? recipe.recipeId : null;
  const initialImageUrl = "imageUrl" in recipe ? recipe.imageUrl : undefined;
  const recipeImageModel = "imageModel" in recipe ? recipe.imageModel : undefined;
  const needsImage = id != null && !initialImageUrl && !!(imageModel ?? recipeImageModel);

  const [saving, setSaving] = useState(false);
  const [saved_, setSaved_] = useState(saved);
  const [confirming, setConfirming] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [loadingImage, setLoadingImage] = useState(needsImage);
  const [liveImageUrl, setLiveImageUrl] = useState<string | undefined>(initialImageUrl);
  const [savedId, setSavedId] = useState<string | null>(needsImage ? id : null);
  const effectiveId = savedId ?? id;

  const recipeModel = "model" in recipe ? recipe.model : undefined;
  const effectiveModel = model ?? recipeModel;
  const effectiveImageModel = imageModel ?? recipeImageModel;
  const modelLabel = MODELS.find((m) => m.id === effectiveModel)?.label;
  const imageModelLabel = IMAGE_MODELS.find((m) => m.id === effectiveImageModel)?.label;

  const imageWidth = "imageWidth" in recipe ? recipe.imageWidth : undefined;
  const imageHeight = "imageHeight" in recipe ? recipe.imageHeight : undefined;
  const imageType = "imageType" in recipe ? recipe.imageType : undefined;
  const imageSizeBytes = "imageSizeBytes" in recipe ? recipe.imageSizeBytes : undefined;
  const imageGenerationMs = "imageGenerationMs" in recipe ? recipe.imageGenerationMs : undefined;

  const [liveImageWidth, setLiveImageWidth] = useState(imageWidth);
  const [liveImageHeight, setLiveImageHeight] = useState(imageHeight);
  const [liveImageType, setLiveImageType] = useState(imageType);
  const [liveImageSizeBytes, setLiveImageSizeBytes] = useState(imageSizeBytes);
  const [liveImageGenerationMs, setLiveImageGenerationMs] = useState(imageGenerationMs);
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

  useEffect(() => {
    if (!loadingImage || !savedId) return;

    const eventSource = new EventSource(`/api/backend/api/recipes/${savedId}/image-stream`);

    const timeoutId = setTimeout(() => {
      eventSource.close();
      setLoadingImage(false);
    }, 90000);

    eventSource.addEventListener('image-ready', async () => {
      clearTimeout(timeoutId);
      eventSource.close();
      try {
        const r = await fetch(`/api/backend/api/recipes/${savedId}`);
        if (r.ok) {
          const data: Recipe = await r.json();
          if (data.imageUrl) setLiveImageUrl(data.imageUrl);
          setLiveImageWidth(data.imageWidth);
          setLiveImageHeight(data.imageHeight);
          setLiveImageType(data.imageType);
          setLiveImageSizeBytes(data.imageSizeBytes);
          setLiveImageGenerationMs(data.imageGenerationMs);
        }
      } finally {
        setLoadingImage(false);
      }
    });

    eventSource.onerror = () => {
      clearTimeout(timeoutId);
      eventSource.close();
      setLoadingImage(false);
    };

    return () => {
      clearTimeout(timeoutId);
      eventSource.close();
    };
  }, [loadingImage, savedId]);

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
      setLoadingImage(true);
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="flex flex-col rounded-2xl border border-gray-200 bg-white shadow-sm overflow-hidden">
      {liveImageUrl ? (
        <img src={liveImageUrl} alt={recipe.title} className="h-40 w-full object-cover" />
      ) : loadingImage ? (
        <div className="h-40 w-full bg-gray-100 animate-pulse flex items-center justify-center">
          <span className="text-xs text-gray-400">Generating image…</span>
        </div>
      ) : null}
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
      
        {(liveImageWidth || liveImageType || liveImageSizeBytes || liveImageGenerationMs) && (
          <div className="mb-4 flex flex-wrap gap-1.5">
            {liveImageGenerationMs && (
              <span className="w-fit rounded-full bg-orange-50 px-2 py-0.5 text-xs text-orange-600">
                AI {liveImageGenerationMs >= 1000
                  ? `${(liveImageGenerationMs / 1000).toFixed(1)}s`
                  : `${liveImageGenerationMs}ms`}
              </span>
            )}
            {liveImageWidth && liveImageHeight && (
              <span className="rounded-full bg-gray-100 px-2 py-0.5 text-xs text-gray-500">
                {liveImageWidth} × {liveImageHeight} px
              </span>
            )}
            {liveImageType && (
              <span className="rounded-full bg-gray-100 px-2 py-0.5 text-xs text-gray-500">
                {liveImageType}
              </span>
            )}
            {liveImageSizeBytes && (
              <span className="rounded-full bg-gray-100 px-2 py-0.5 text-xs text-gray-500">
                {liveImageSizeBytes < 1024 * 1024
                  ? `${(liveImageSizeBytes / 1024).toFixed(1)} KB`
                  : `${(liveImageSizeBytes / (1024 * 1024)).toFixed(2)} MB`}
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
