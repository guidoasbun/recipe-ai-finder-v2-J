"use client";

import { useState, useEffect, useCallback } from "react";
import Link from "next/link";

const REQUIRED_CONSENTS = [
  {
    type: "TERMS_OF_SERVICE" as const,
    label: "I agree to the",
    linkText: "Terms of Service",
    href: "/terms",
  },
  {
    type: "PRIVACY_POLICY" as const,
    label: "I acknowledge the",
    linkText: "Privacy Policy",
    href: "/privacy",
  },
  {
    type: "AI_DATA_PROCESSING" as const,
    label: "I consent to AI data processing for recipe generation",
    linkText: null,
    href: null,
  },
];

interface ConsentModalProps {
  onConsentsGranted: () => void;
}

export default function ConsentModal({ onConsentsGranted }: ConsentModalProps) {
  const [checked, setChecked] = useState<Record<string, boolean>>({
    TERMS_OF_SERVICE: false,
    PRIVACY_POLICY: false,
    AI_DATA_PROCESSING: false,
  });
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const allChecked = Object.values(checked).every(Boolean);

  // Prevent Escape key from dismissing the modal
  useEffect(() => {
    function handleKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape") {
        e.preventDefault();
        e.stopPropagation();
      }
    }
    document.addEventListener("keydown", handleKeyDown, true);
    return () => document.removeEventListener("keydown", handleKeyDown, true);
  }, []);

  // Prevent browser back navigation from dismissing the modal
  useEffect(() => {
    window.history.pushState(null, "", window.location.href);
    function handlePopState() {
      window.history.pushState(null, "", window.location.href);
    }
    window.addEventListener("popstate", handlePopState);
    return () => window.removeEventListener("popstate", handlePopState);
  }, []);

  const handleCheckboxChange = useCallback((type: string) => {
    setChecked((prev) => ({ ...prev, [type]: !prev[type] }));
  }, []);

  async function handleSubmit() {
    if (!allChecked || submitting) return;

    setSubmitting(true);
    setError(null);

    try {
      for (const consent of REQUIRED_CONSENTS) {
        const res = await fetch("/api/backend/api/consent", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            consentType: consent.type,
            version: "1.0",
          }),
        });

        if (!res.ok) {
          const body = await res.json().catch(() => ({}));
          throw new Error(
            body.message ?? `Failed to record consent for ${consent.type}`
          );
        }
      }

      onConsentsGranted();
    } catch (err) {
      setError(
        err instanceof Error
          ? err.message
          : "An error occurred while submitting your consent. Please try again."
      );
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/60"
      role="dialog"
      aria-modal="true"
      aria-labelledby="consent-modal-title"
      onClick={(e) => e.stopPropagation()}
    >
      <div className="w-full max-w-md rounded-xl bg-white p-8 shadow-2xl">
        <h2
          id="consent-modal-title"
          className="mb-2 text-xl font-bold text-gray-900"
        >
          Welcome to Recipe AI Finder
        </h2>
        <p className="mb-6 text-sm text-gray-600">
          Before you continue, please review and accept the following to use
          this application.
        </p>

        <div className="space-y-4">
          {REQUIRED_CONSENTS.map((consent) => (
            <label
              key={consent.type}
              className="flex items-start gap-3 cursor-pointer"
            >
              <input
                type="checkbox"
                checked={checked[consent.type]}
                onChange={() => handleCheckboxChange(consent.type)}
                className="mt-0.5 h-4 w-4 rounded border-gray-300 text-[#003DA5] focus:ring-[#003DA5]"
              />
              <span className="text-sm text-gray-700">
                {consent.label}
                {consent.linkText && consent.href && (
                  <>
                    {" "}
                    <Link
                      href={consent.href}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="font-medium text-[#003DA5] underline hover:text-[#002d7a]"
                    >
                      {consent.linkText}
                    </Link>
                  </>
                )}
              </span>
            </label>
          ))}
        </div>

        {error && (
          <div className="mt-4 rounded-lg bg-red-50 p-3 text-sm text-red-700">
            {error}
          </div>
        )}

        <button
          onClick={handleSubmit}
          disabled={!allChecked || submitting}
          className="mt-6 w-full rounded-lg px-4 py-3 text-sm font-semibold text-white transition-colors disabled:cursor-not-allowed disabled:opacity-50"
          style={{ backgroundColor: allChecked ? "#FF7900" : "#9ca3af" }}
          onMouseEnter={(e) => {
            if (allChecked && !submitting)
              e.currentTarget.style.backgroundColor = "#e06a00";
          }}
          onMouseLeave={(e) => {
            e.currentTarget.style.backgroundColor = allChecked
              ? "#FF7900"
              : "#9ca3af";
          }}
        >
          {submitting ? "Submitting..." : "Accept & Continue"}
        </button>
      </div>
    </div>
  );
}
