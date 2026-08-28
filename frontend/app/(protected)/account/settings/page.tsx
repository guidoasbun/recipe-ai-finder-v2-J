"use client";

import { useState, useEffect, useCallback } from "react";
import {
  User,
  Shield,
  Download,
  Trash2,
  AlertTriangle,
  CheckCircle,
  XCircle,
  Loader2,
  X,
} from "lucide-react";
import type { Consent, ExportStatusResponse } from "@/lib/compliance-api";

interface UserProfile {
  userId: string;
  email: string;
  username: string;
  createdAt: string;
}

export default function AccountSettingsPage() {
  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [consents, setConsents] = useState<Consent[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Export state
  const [jsonExporting, setJsonExporting] = useState(false);
  const [zipExporting, setZipExporting] = useState(false);
  const [zipStatus, setZipStatus] = useState<ExportStatusResponse | null>(null);

  // Deletion state
  const [softDeleting, setSoftDeleting] = useState(false);
  const [showHardDeleteDialog, setShowHardDeleteDialog] = useState(false);
  const [hardDeleteConfirmation, setHardDeleteConfirmation] = useState("");
  const [hardDeleting, setHardDeleting] = useState(false);

  // Consent revocation state
  const [showRevokeWarning, setShowRevokeWarning] = useState(false);
  const [revoking, setRevoking] = useState(false);

  const loadProfile = useCallback(async () => {
    try {
      const res = await fetch("/api/backend/api/account/profile");
      if (!res.ok) throw new Error("Failed to load profile");
      const data = await res.json();
      setProfile(data);
    } catch {
      setError("Failed to load profile information");
    }
  }, []);

  const loadConsents = useCallback(async () => {
    try {
      const res = await fetch("/api/backend/api/consent");
      if (!res.ok) throw new Error("Failed to load consents");
      const data = await res.json();
      setConsents(data);
    } catch {
      // Non-critical, don't block the page
    }
  }, []);

  const loadZipStatus = useCallback(async () => {
    try {
      const res = await fetch("/api/backend/api/account/export/status");
      if (res.status === 204) {
        setZipStatus(null);
        return;
      }
      if (!res.ok) return;
      const data = await res.json();
      setZipStatus(data);
    } catch {
      // Silently fail
    }
  }, []);

  useEffect(() => {
    async function init() {
      setLoading(true);
      await Promise.all([loadProfile(), loadConsents(), loadZipStatus()]);
      setLoading(false);
    }
    init();
  }, [loadProfile, loadConsents, loadZipStatus]);

  // Poll ZIP export status while in progress
  useEffect(() => {
    if (zipStatus?.status !== "IN_PROGRESS") return;
    const interval = setInterval(loadZipStatus, 3000);
    return () => clearInterval(interval);
  }, [zipStatus?.status, loadZipStatus]);

  async function handleJsonExport() {
    setJsonExporting(true);
    try {
      const res = await fetch("/api/backend/api/account/export?format=json");
      if (!res.ok) throw new Error("Export failed");
      const data = await res.json();
      const blob = new Blob([JSON.stringify(data, null, 2)], {
        type: "application/json",
      });
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = "my-data-export.json";
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
    } catch {
      setError("Failed to export data as JSON");
    } finally {
      setJsonExporting(false);
    }
  }

  async function handleZipExport() {
    setZipExporting(true);
    try {
      const res = await fetch("/api/backend/api/account/export?format=zip", {
        method: "POST",
      });
      if (!res.ok) throw new Error("Failed to start ZIP export");
      const data = await res.json();
      setZipStatus(data);
    } catch {
      setError("Failed to start ZIP export");
    } finally {
      setZipExporting(false);
    }
  }

  async function handleSoftDelete() {
    setSoftDeleting(true);
    try {
      const res = await fetch("/api/backend/api/account/delete", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ type: "soft" }),
      });
      if (!res.ok) throw new Error("Failed to schedule deletion");
      window.location.reload();
    } catch {
      setError("Failed to schedule account deletion");
    } finally {
      setSoftDeleting(false);
    }
  }

  async function handleHardDelete() {
    setHardDeleting(true);
    try {
      const res = await fetch("/api/backend/api/account/delete", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ type: "immediate" }),
      });
      if (!res.ok) throw new Error("Failed to delete account");
      window.location.href = "/api/auth/logout";
    } catch {
      setError("Failed to permanently delete account");
    } finally {
      setHardDeleting(false);
      setShowHardDeleteDialog(false);
      setHardDeleteConfirmation("");
    }
  }

  async function handleRevokeAiConsent() {
    setRevoking(true);
    try {
      const res = await fetch("/api/backend/api/consent/AI_DATA_PROCESSING", {
        method: "DELETE",
      });
      if (!res.ok) throw new Error("Failed to revoke consent");
      await loadConsents();
      setShowRevokeWarning(false);
    } catch {
      setError("Failed to revoke AI data processing consent");
    } finally {
      setRevoking(false);
    }
  }

  function getConsentLabel(type: string): string {
    switch (type) {
      case "TERMS_OF_SERVICE":
        return "Terms of Service";
      case "PRIVACY_POLICY":
        return "Privacy Policy";
      case "AI_DATA_PROCESSING":
        return "AI Data Processing";
      default:
        return type;
    }
  }

  if (loading) {
    return (
      <div className="flex items-center justify-center py-16">
        <Loader2 className="h-8 w-8 animate-spin text-gray-400" />
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-3xl space-y-8">
      <h1 className="text-3xl font-bold text-gray-900">Account Settings</h1>

      {error && (
        <div className="rounded-lg border border-red-200 bg-red-50 p-4 text-sm text-red-700">
          <div className="flex items-center justify-between">
            <span>{error}</span>
            <button onClick={() => setError(null)} aria-label="Dismiss error">
              <X className="h-4 w-4" />
            </button>
          </div>
        </div>
      )}

      {/* Profile Section */}
      <section className="rounded-lg border border-gray-200 bg-white p-6">
        <div className="mb-4 flex items-center gap-2">
          <User className="h-5 w-5 text-gray-600" />
          <h2 className="text-lg font-semibold text-gray-900">Profile</h2>
        </div>
        {profile ? (
          <dl className="grid grid-cols-1 gap-4 sm:grid-cols-3">
            <div>
              <dt className="text-sm font-medium text-gray-500">Email</dt>
              <dd className="mt-1 text-sm text-gray-900">{profile.email}</dd>
            </div>
            <div>
              <dt className="text-sm font-medium text-gray-500">Username</dt>
              <dd className="mt-1 text-sm text-gray-900">{profile.username}</dd>
            </div>
            <div>
              <dt className="text-sm font-medium text-gray-500">Member since</dt>
              <dd className="mt-1 text-sm text-gray-900">
                {new Date(profile.createdAt).toLocaleDateString()}
              </dd>
            </div>
          </dl>
        ) : (
          <p className="text-sm text-gray-500">Unable to load profile information.</p>
        )}
      </section>

      {/* Consent Management Section */}
      <section className="rounded-lg border border-gray-200 bg-white p-6">
        <div className="mb-4 flex items-center gap-2">
          <Shield className="h-5 w-5 text-gray-600" />
          <h2 className="text-lg font-semibold text-gray-900">Consent Management</h2>
        </div>
        <div className="space-y-3">
          {consents.length === 0 ? (
            <p className="text-sm text-gray-500">No consent records found.</p>
          ) : (
            consents.map((consent) => (
              <div
                key={consent.consentType}
                className="flex items-center justify-between rounded-md border border-gray-100 bg-gray-50 px-4 py-3"
              >
                <div className="flex items-center gap-3">
                  {consent.granted ? (
                    <CheckCircle className="h-5 w-5 text-green-500" />
                  ) : (
                    <XCircle className="h-5 w-5 text-red-500" />
                  )}
                  <div>
                    <p className="text-sm font-medium text-gray-900">
                      {getConsentLabel(consent.consentType)}
                    </p>
                    <p className="text-xs text-gray-500">
                      {consent.granted
                        ? `Granted ${consent.grantedAt ? new Date(consent.grantedAt).toLocaleDateString() : ""}`
                        : `Revoked ${consent.revokedAt ? new Date(consent.revokedAt).toLocaleDateString() : ""}`}
                    </p>
                  </div>
                </div>
                {consent.consentType === "AI_DATA_PROCESSING" && consent.granted && (
                  <button
                    onClick={() => setShowRevokeWarning(true)}
                    className="rounded-md border border-gray-300 px-3 py-1.5 text-xs font-medium text-gray-700 hover:bg-gray-100 transition-colors"
                  >
                    Revoke
                  </button>
                )}
              </div>
            ))
          )}
        </div>
      </section>

      {/* Data Export Section */}
      <section className="rounded-lg border border-gray-200 bg-white p-6">
        <div className="mb-4 flex items-center gap-2">
          <Download className="h-5 w-5 text-gray-600" />
          <h2 className="text-lg font-semibold text-gray-900">Data Export</h2>
        </div>
        <p className="mb-4 text-sm text-gray-500">
          Download a copy of all your personal data stored in this application.
        </p>
        <div className="flex flex-wrap gap-3">
          <button
            onClick={handleJsonExport}
            disabled={jsonExporting}
            className="inline-flex items-center gap-2 rounded-md border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50 transition-colors"
          >
            {jsonExporting ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : (
              <Download className="h-4 w-4" />
            )}
            Export as JSON
          </button>
          <button
            onClick={handleZipExport}
            disabled={zipExporting || zipStatus?.status === "IN_PROGRESS"}
            className="inline-flex items-center gap-2 rounded-md border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50 transition-colors"
          >
            {zipExporting ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : (
              <Download className="h-4 w-4" />
            )}
            Export as ZIP (with images)
          </button>
        </div>
        {zipStatus && (
          <div className="mt-4 rounded-md border border-gray-100 bg-gray-50 p-3">
            {zipStatus.status === "IN_PROGRESS" && (
              <div className="flex items-center gap-2 text-sm text-gray-600">
                <Loader2 className="h-4 w-4 animate-spin" />
                <span>ZIP export in progress...</span>
              </div>
            )}
            {zipStatus.status === "COMPLETED" && zipStatus.downloadUrl && (
              <div className="flex items-center gap-2 text-sm text-green-700">
                <CheckCircle className="h-4 w-4" />
                <span>Export ready.</span>
                <a
                  href={zipStatus.downloadUrl}
                  className="font-medium underline hover:text-green-900"
                  target="_blank"
                  rel="noopener noreferrer"
                >
                  Download ZIP
                </a>
              </div>
            )}
            {zipStatus.status === "FAILED" && (
              <div className="flex items-center gap-2 text-sm text-red-700">
                <XCircle className="h-4 w-4" />
                <span>Export failed{zipStatus.error ? `: ${zipStatus.error}` : ""}.</span>
              </div>
            )}
          </div>
        )}
      </section>

      {/* Account Deletion Section */}
      <section className="rounded-lg border border-red-200 bg-white p-6">
        <div className="mb-4 flex items-center gap-2">
          <Trash2 className="h-5 w-5 text-red-500" />
          <h2 className="text-lg font-semibold text-gray-900">Delete Account</h2>
        </div>

        <div className="space-y-4">
          {/* Soft Delete */}
          <div className="rounded-md border border-gray-200 p-4">
            <h3 className="text-sm font-semibold text-gray-900">Schedule Deletion</h3>
            <p className="mt-1 text-sm text-gray-500">
              Your account will be permanently deleted after 30 days. During this period, you can
              cancel the deletion and reactivate your account. You will not be able to generate new
              recipes while deletion is pending.
            </p>
            <button
              onClick={handleSoftDelete}
              disabled={softDeleting}
              className="mt-3 inline-flex items-center gap-2 rounded-md border border-red-300 px-4 py-2 text-sm font-medium text-red-700 hover:bg-red-50 disabled:opacity-50 transition-colors"
            >
              {softDeleting && <Loader2 className="h-4 w-4 animate-spin" />}
              Schedule Account Deletion
            </button>
          </div>

          {/* Hard Delete */}
          <div className="rounded-md border border-red-300 bg-red-50 p-4">
            <h3 className="text-sm font-semibold text-red-900">Immediate Permanent Deletion</h3>
            <p className="mt-1 text-sm text-red-700">
              This will immediately and permanently delete your account and all associated data.
              This action cannot be undone.
            </p>
            <button
              onClick={() => setShowHardDeleteDialog(true)}
              className="mt-3 inline-flex items-center gap-2 rounded-md bg-red-600 px-4 py-2 text-sm font-medium text-white hover:bg-red-700 transition-colors"
            >
              <Trash2 className="h-4 w-4" />
              Delete Permanently
            </button>
          </div>
        </div>
      </section>

      {/* Hard Delete Confirmation Dialog */}
      {showHardDeleteDialog && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
          role="dialog"
          aria-modal="true"
          aria-labelledby="delete-dialog-title"
        >
          <div className="mx-4 w-full max-w-md rounded-lg bg-white p-6 shadow-xl">
            <div className="mb-4 flex items-center gap-2">
              <AlertTriangle className="h-5 w-5 text-red-500" />
              <h3 id="delete-dialog-title" className="text-lg font-semibold text-gray-900">
                Confirm Permanent Deletion
              </h3>
            </div>
            <p className="mb-4 text-sm text-gray-600">
              This action is irreversible. All your data including recipes, images, and account
              information will be permanently deleted.
            </p>
            <p className="mb-3 text-sm font-medium text-gray-900">
              Type <span className="font-mono text-red-600">DELETE</span> to confirm:
            </p>
            <input
              type="text"
              value={hardDeleteConfirmation}
              onChange={(e) => setHardDeleteConfirmation(e.target.value)}
              placeholder="Type DELETE here"
              className="w-full text-gray-900 rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-red-500 focus:outline-none focus:ring-1 focus:ring-red-500"
              autoFocus
            />
            <div className="mt-4 flex justify-end gap-3">
              <button
                onClick={() => {
                  setShowHardDeleteDialog(false);
                  setHardDeleteConfirmation("");
                }}
                className="rounded-md border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 transition-colors"
              >
                Cancel
              </button>
              <button
                onClick={handleHardDelete}
                disabled={hardDeleteConfirmation !== "DELETE" || hardDeleting}
                className="inline-flex items-center gap-2 rounded-md bg-red-600 px-4 py-2 text-sm font-medium text-white hover:bg-red-700 disabled:opacity-50 transition-colors"
              >
                {hardDeleting && <Loader2 className="h-4 w-4 animate-spin" />}
                Delete Forever
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Revoke AI Consent Warning Dialog */}
      {showRevokeWarning && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
          role="dialog"
          aria-modal="true"
          aria-labelledby="revoke-dialog-title"
        >
          <div className="mx-4 w-full max-w-md rounded-lg bg-white p-6 shadow-xl">
            <div className="mb-4 flex items-center gap-2">
              <AlertTriangle className="h-5 w-5 text-yellow-500" />
              <h3 id="revoke-dialog-title" className="text-lg font-semibold text-gray-900">
                Revoke AI Data Processing Consent
              </h3>
            </div>
            <p className="mb-4 text-sm text-gray-600">
              If you revoke your AI data processing consent, recipe generation will be unavailable
              until you re-grant this consent. Your existing recipes will not be affected.
            </p>
            <div className="flex justify-end gap-3">
              <button
                onClick={() => setShowRevokeWarning(false)}
                className="rounded-md border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 transition-colors"
              >
                Cancel
              </button>
              <button
                onClick={handleRevokeAiConsent}
                disabled={revoking}
                className="inline-flex items-center gap-2 rounded-md bg-yellow-600 px-4 py-2 text-sm font-medium text-white hover:bg-yellow-700 disabled:opacity-50 transition-colors"
              >
                {revoking && <Loader2 className="h-4 w-4 animate-spin" />}
                Confirm Revocation
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
