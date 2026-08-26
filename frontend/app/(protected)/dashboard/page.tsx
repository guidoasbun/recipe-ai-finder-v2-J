"use client";

import { useState, useEffect, useCallback } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { Pencil } from "lucide-react";
import { MODELS, ModelId, IMAGE_MODELS, ImageModelId } from "@/lib/constants";
import { dietaryLabel } from "@/lib/dietary";

export default function DashboardPage() {
  const router = useRouter();
  const [ingredients, setIngredients] = useState("");
  const [model, setModel] = useState<ModelId>("CLAUDE_HAIKU");
  const [imageModel, setImageModel] = useState<ImageModelId>("STABILITY_CORE");
  const [loading, setLoading] = useState(false);

  const [restrictions, setRestrictions] = useState<string[]>([]);
  const [profileLoaded, setProfileLoaded] = useState(false);

  const loadProfile = useCallback(async () => {
    try {
      const res = await fetch("/api/account/profile");
      if (!res.ok) throw new Error("Failed to load profile");
      const data = await res.json();
      setRestrictions(
        Array.isArray(data.dietaryRestrictions) ? data.dietaryRestrictions : []
      );
    } catch {
      setRestrictions([]);
    } finally {
      setProfileLoaded(true);
    }
  }, []);

  useEffect(() => {
    loadProfile();
  }, [loadProfile]);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!ingredients.trim()) return;

    setLoading(true);
    const params = new URLSearchParams({
      ingredients: ingredients.trim(),
      model,
      imageModel,
    });
    router.push(`/generate?${params.toString()}`);
  }

  return (
    <div className="mx-auto max-w-2xl">
      <h1 className="mb-2 text-3xl font-bold text-gray-900">What&apos;s in your fridge or pantry?</h1>
      <p className="mb-8 text-gray-500">Enter your ingredients and we&apos;ll generate recipes for you.</p>

      {profileLoaded && (
        <div className="mb-6 rounded-lg border border-gray-200 bg-white p-4">
          <div className="flex items-center justify-between gap-4">
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-sm font-medium text-gray-700">Dietary restrictions:</span>
              {restrictions.length > 0 ? (
                restrictions.map((r) => (
                  <span
                    key={r}
                    className="inline-flex items-center rounded-full bg-blue-50 px-3 py-1 text-xs font-medium text-blue-800"
                  >
                    {dietaryLabel(r)}
                  </span>
                ))
              ) : (
                <span className="text-sm text-gray-500">No dietary restrictions</span>
              )}
            </div>
            <Link
              href="/account/dietary"
              className="inline-flex flex-shrink-0 items-center gap-1 text-sm font-medium text-blue-700 hover:text-blue-900 transition-colors"
            >
              <Pencil className="h-3.5 w-3.5" />
              Edit
            </Link>
          </div>
        </div>
      )}

      <form onSubmit={handleSubmit} className="space-y-4 text-gray-800">
        <textarea
          value={ingredients}
          onChange={(e) => setIngredients(e.target.value)}
          placeholder="e.g. chicken, garlic, lemon, olive oil"
          rows={4}
          maxLength={2000}
          className="w-full rounded-lg border border-gray-300 px-4 py-3 text-sm shadow-sm focus:border-[#003DA5] focus:outline-none focus:ring-1 focus:ring-[#003DA5]"
        />

        <div>
          <p className="mb-2 text-sm font-medium text-gray-700">Model Settings</p>
          <p className="mb-3 text-xs text-gray-500">
            Choose a <span className="font-medium">text model</span> to generate the recipe and a{" "}
            <span className="font-medium">image model</span> to create a photo of the dish.
            Faster models respond quicker but may be less detailed.
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-4">
          <div className="flex items-center gap-3">
            <label className="text-sm font-medium text-gray-700">Text Model:</label>
            <select
              value={model}
              onChange={(e) => setModel(e.target.value as ModelId)}
              className="rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-[#003DA5] focus:outline-none"
            >
              {MODELS.map((m) => (
                <option key={m.id} value={m.id}>{m.label}</option>
              ))}
            </select>
          </div>
          <div className="flex items-center gap-3">
            <label className="text-sm font-medium text-gray-700">Image Model:</label>
            <select
              value={imageModel}
              onChange={(e) => setImageModel(e.target.value as ImageModelId)}
              className="rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-[#003DA5] focus:outline-none"
            >
              {IMAGE_MODELS.map((m) => (
                <option key={m.id} value={m.id}>{m.label}</option>
              ))}
            </select>
          </div>
        </div>

        <button
          type="submit"
          disabled={loading || !ingredients.trim()}
          className="w-full rounded-lg px-4 py-3 text-sm font-semibold text-white disabled:opacity-50 transition-colors"
          style={{ backgroundColor: "#FF7900" }}
          onMouseEnter={(e) => { const btn = e.currentTarget; if (!btn.disabled) btn.style.backgroundColor = "#e06a00"; }}
          onMouseLeave={(e) => { e.currentTarget.style.backgroundColor = "#FF7900"; }}
        >
          {loading ? "Loading..." : "Generate Recipes"}
        </button>
      </form>
    </div>
  );
}
