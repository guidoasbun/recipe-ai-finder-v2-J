"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { MODELS, ModelId } from "@/lib/constants";

export default function DashboardPage() {
  const router = useRouter();
  const [ingredients, setIngredients] = useState("");
  const [model, setModel] = useState<ModelId>("CLAUDE_HAIKU");
  const [loading, setLoading] = useState(false);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!ingredients.trim()) return;

    setLoading(true);
    const params = new URLSearchParams({
      ingredients: ingredients.trim(),
      model,
    });
    router.push(`/generate?${params.toString()}`);
  }

  return (
    <div className="mx-auto max-w-2xl">
      <h1 className="mb-2 text-3xl font-bold text-gray-900">What's in your fridge?</h1>
      <p className="mb-8 text-gray-500">Enter your ingredients and we'll generate recipes for you.</p>

      <form onSubmit={handleSubmit} className="space-y-4">
        <textarea
          value={ingredients}
          onChange={(e) => setIngredients(e.target.value)}
          placeholder="e.g. chicken, garlic, lemon, olive oil"
          rows={4}
          className="w-full rounded-lg border border-gray-300 px-4 py-3 text-sm shadow-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
        />

        <div className="flex items-center gap-3">
          <label className="text-sm font-medium text-gray-700">Model:</label>
          <select
            value={model}
            onChange={(e) => setModel(e.target.value as ModelId)}
            className="rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none"
          >
            {MODELS.map((m) => (
              <option key={m.id} value={m.id}>{m.label}</option>
            ))}
          </select>
        </div>

        <button
          type="submit"
          disabled={loading || !ingredients.trim()}
          className="w-full rounded-lg bg-blue-600 px-4 py-3 text-sm font-semibold text-white hover:bg-blue-700 disabled:opacity-50 transition-colors"
        >
          {loading ? "Loading..." : "Generate Recipes"}
        </button>
      </form>
    </div>
  );
}
