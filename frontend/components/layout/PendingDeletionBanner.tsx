"use client";

import { useState } from "react";

interface PendingDeletionBannerProps {
  scheduledDeletionDate: string;
}

export default function PendingDeletionBanner({ scheduledDeletionDate }: PendingDeletionBannerProps) {
  const [cancelling, setCancelling] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [dismissed, setDismissed] = useState(false);

  const formattedDate = new Date(scheduledDeletionDate).toLocaleDateString(undefined, {
    year: "numeric",
    month: "long",
    day: "numeric",
  });

  async function handleCancel() {
    setCancelling(true);
    setError(null);

    try {
      const res = await fetch("/api/account/cancel-deletion", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
      });

      if (!res.ok) {
        const body = await res.json().catch(() => ({}));
        throw new Error(body.message ?? "Failed to cancel deletion.");
      }

      setDismissed(true);
      window.location.reload();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Something went wrong.");
    } finally {
      setCancelling(false);
    }
  }

  if (dismissed) return null;

  return (
    <div
      role="alert"
      className="border-l-4 border-red-600 bg-red-50 px-4 py-3"
    >
      <div className="mx-auto flex max-w-5xl flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2 text-sm text-red-800">
          <svg
            className="h-5 w-5 flex-shrink-0 text-red-600"
            fill="currentColor"
            viewBox="0 0 20 20"
            aria-hidden="true"
          >
            <path
              fillRule="evenodd"
              d="M8.485 2.495c.673-1.167 2.357-1.167 3.03 0l6.28 10.875c.673 1.167-.17 2.625-1.516 2.625H3.72c-1.347 0-2.189-1.458-1.515-2.625L8.485 2.495zM10 6a.75.75 0 01.75.75v3.5a.75.75 0 01-1.5 0v-3.5A.75.75 0 0110 6zm0 9a1 1 0 100-2 1 1 0 000 2z"
              clipRule="evenodd"
            />
          </svg>
          <span>
            Your account is scheduled for permanent deletion on{" "}
            <strong>{formattedDate}</strong>. All data will be removed after this date.
          </span>
        </div>
        <div className="flex items-center gap-3">
          {error && <span className="text-xs text-red-600">{error}</span>}
          <button
            onClick={handleCancel}
            disabled={cancelling}
            className="rounded-md bg-red-600 px-3 py-1.5 text-sm font-medium text-white transition-colors hover:bg-red-700 disabled:opacity-50"
          >
            {cancelling ? "Cancelling..." : "Cancel Deletion"}
          </button>
        </div>
      </div>
    </div>
  );
}
