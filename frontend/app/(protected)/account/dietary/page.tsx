"use client";

import { useState, useEffect, useCallback } from "react";
import { Utensils, Loader2, CheckCircle, XCircle, Check } from "lucide-react";
import { DIETARY_RESTRICTIONS, type DietaryRestriction } from "@/lib/dietary";

const SUCCESS_TOAST_MS = 3000;

export default function DietaryRestrictionsPage() {
  const [selected, setSelected] = useState<Set<DietaryRestriction>>(new Set());
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(false);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [showSuccess, setShowSuccess] = useState(false);

  const loadRestrictions = useCallback(async () => {
    setLoading(true);
    setLoadError(false);
    try {
      const res = await fetch("/api/backend/api/account/dietary-restrictions");
      if (!res.ok) throw new Error("Failed to load");
      const data: string[] = await res.json();
      setSelected(new Set(data as DietaryRestriction[]));
    } catch {
      setLoadError(true);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadRestrictions();
  }, [loadRestrictions]);

  // Auto-dismiss the success toast after the required minimum display time.
  useEffect(() => {
    if (!showSuccess) return;
    const timer = setTimeout(() => setShowSuccess(false), SUCCESS_TOAST_MS);
    return () => clearTimeout(timer);
  }, [showSuccess]);

  function toggle(value: DietaryRestriction) {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(value)) {
        next.delete(value);
      } else {
        next.add(value);
      }
      return next;
    });
  }

  async function handleSave() {
    setSaving(true);
    setSaveError(null);
    setShowSuccess(false);
    try {
      const res = await fetch("/api/backend/api/account/dietary-restrictions", {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ restrictions: Array.from(selected) }),
      });
      if (!res.ok) throw new Error("Failed to save");
      const saved: string[] = await res.json();
      // Reconcile local state with the server's canonical (deduplicated) list.
      setSelected(new Set(saved as DietaryRestriction[]));
      setShowSuccess(true);
    } catch {
      // Retain the user's unsaved selections so they can retry.
      setSaveError("Failed to save your dietary restrictions. Please try again.");
    } finally {
      setSaving(false);
    }
  }

  if (loading) {
    return (
      <div className="flex items-center justify-center py-16">
        <Loader2 className="h-8 w-8 animate-spin text-gray-400" />
      </div>
    );
  }

  if (loadError) {
    return (
      <div className="mx-auto max-w-3xl">
        <div className="rounded-lg border border-red-200 bg-red-50 p-6 text-center">
          <XCircle className="mx-auto h-8 w-8 text-red-500" />
          <p className="mt-3 text-sm text-red-700">
            We couldn&apos;t load your dietary restrictions.
          </p>
          <button
            onClick={loadRestrictions}
            className="mt-4 inline-flex items-center gap-2 rounded-md border border-red-300 px-4 py-2 text-sm font-medium text-red-700 hover:bg-red-100 transition-colors"
          >
            Retry
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-3xl space-y-8">
      <h1 className="text-3xl font-bold text-gray-900">Dietary Restrictions</h1>

      {showSuccess && (
        <div
          role="status"
          className="flex items-center gap-2 rounded-lg border border-green-200 bg-green-50 p-4 text-sm text-green-700"
        >
          <CheckCircle className="h-4 w-4" />
          <span>Your dietary restrictions have been saved.</span>
        </div>
      )}

      {saveError && (
        <div className="rounded-lg border border-red-200 bg-red-50 p-4 text-sm text-red-700">
          {saveError}
        </div>
      )}

      <section className="rounded-lg border border-gray-200 bg-white p-6">
        <div className="mb-4 flex items-center gap-2">
          <Utensils className="h-5 w-5 text-gray-600" />
          <h2 className="text-lg font-semibold text-gray-900">Your Restrictions</h2>
        </div>
        <p className="mb-4 text-sm text-gray-500">
          Select any dietary restrictions you follow. Generated recipes will respect these choices.
        </p>

        <div className="flex flex-wrap gap-2">
          {DIETARY_RESTRICTIONS.map(({ value, label }) => {
            const isSelected = selected.has(value);
            return (
              <button
                key={value}
                type="button"
                role="switch"
                aria-checked={isSelected}
                onClick={() => toggle(value)}
                className={`inline-flex items-center gap-1.5 rounded-full border px-4 py-2 text-sm font-medium transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-offset-2 focus-visible:ring-blue-500 ${
                  isSelected
                    ? "border-blue-600 bg-blue-600 text-white hover:bg-blue-700"
                    : "border-gray-300 bg-white text-gray-700 hover:bg-gray-50"
                }`}
              >
                {isSelected && <Check className="h-4 w-4" />}
                {label}
              </button>
            );
          })}
        </div>

        <div className="mt-6">
          <button
            onClick={handleSave}
            disabled={saving}
            className="inline-flex items-center gap-2 rounded-md bg-blue-600 px-5 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50 transition-colors"
          >
            {saving && <Loader2 className="h-4 w-4 animate-spin" />}
            Save
          </button>
        </div>
      </section>
    </div>
  );
}
