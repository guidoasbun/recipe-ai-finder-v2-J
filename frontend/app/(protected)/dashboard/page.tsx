"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { MODELS, ModelId, IMAGE_MODELS, ImageModelId } from "@/lib/constants";

export default function DashboardPage() {
  const router = useRouter();
  const [ingredients, setIngredients] = useState("");
  const [model, setModel] = useState<ModelId>("CLAUDE_HAIKU");
  const [imageModel, setImageModel] = useState<ImageModelId>("STABILITY_CORE");
  const [loading, setLoading] = useState(false);

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
      <h1 className="mb-2 text-3xl font-bold text-gray-900">What's in your fridge or pantry?</h1>
      <p className="mb-8 text-gray-500">Enter your ingredients and we'll generate recipes for you.</p>

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
