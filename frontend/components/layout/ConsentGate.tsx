"use client";

import { useState, useEffect } from "react";
import ConsentModal from "./ConsentModal";

const REQUIRED_CONSENT_TYPES = [
  "TERMS_OF_SERVICE",
  "PRIVACY_POLICY",
  "AI_DATA_PROCESSING",
];

interface Consent {
  consentType: string;
  granted: boolean;
}

export default function ConsentGate({ children }: { children: React.ReactNode }) {
  const [loading, setLoading] = useState(true);
  const [needsConsent, setNeedsConsent] = useState(false);

  useEffect(() => {
    checkConsents();
  }, []);

  async function checkConsents() {
    try {
      const res = await fetch("/api/backend/api/consent", {
        method: "GET",
        headers: { "Content-Type": "application/json" },
      });

      if (!res.ok) {
        // If we can't check consents, assume they need to be granted
        setNeedsConsent(true);
        setLoading(false);
        return;
      }

      const consents: Consent[] = await res.json();

      const hasAll = REQUIRED_CONSENT_TYPES.every((type) =>
        consents.some((c) => c.consentType === type && c.granted)
      );

      setNeedsConsent(!hasAll);
    } catch {
      // On error, show modal to be safe
      setNeedsConsent(true);
    } finally {
      setLoading(false);
    }
  }

  function handleConsentsGranted() {
    setNeedsConsent(false);
  }

  if (loading) {
    return (
      <div className="flex min-h-screen items-center justify-center">
        <div className="h-8 w-8 animate-spin rounded-full border-4 border-gray-300 border-t-[#003DA5]" />
      </div>
    );
  }

  return (
    <>
      {needsConsent && <ConsentModal onConsentsGranted={handleConsentsGranted} />}
      {children}
    </>
  );
}
