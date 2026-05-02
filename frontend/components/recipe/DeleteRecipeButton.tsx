"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";

interface Props {
  recipeId: string;
}

export default function DeleteRecipeButton({ recipeId }: Props) {
  const router = useRouter();
  const [confirming, setConfirming] = useState(false);
  const [deleting, setDeleting] = useState(false);

  async function handleDelete() {
    setDeleting(true);
    try {
      await fetch(`/api/backend/api/recipes/${recipeId}`, { method: "DELETE" });
      router.push("/recipes");
    } finally {
      setDeleting(false);
    }
  }

  if (deleting) {
    return (
      <button disabled className="rounded-lg border border-red-300 px-4 py-2 text-sm font-medium text-red-400 opacity-50">
        Deleting…
      </button>
    );
  }

  if (confirming) {
    return (
      <div className="flex gap-2">
        <button
          onClick={() => setConfirming(false)}
          className="rounded-lg border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50"
        >
          Cancel
        </button>
        <button
          onClick={handleDelete}
          className="rounded-lg bg-red-600 px-4 py-2 text-sm font-medium text-white hover:bg-red-700"
        >
          Delete?
        </button>
      </div>
    );
  }

  return (
    <button
      onClick={() => setConfirming(true)}
      className="rounded-lg border border-red-300 px-4 py-2 text-sm font-medium text-red-600 hover:bg-red-50"
    >
      Delete
    </button>
  );
}
