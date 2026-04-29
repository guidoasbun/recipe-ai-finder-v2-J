"use client";

import { useState, useEffect, Suspense } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import Link from "next/link";

function ConfirmForm() {
  const [code, setCode] = useState("");
  const [error, setError] = useState("");
  const [resendStatus, setResendStatus] = useState<"idle" | "sent" | "error">("idle");
  const [loading, setLoading] = useState(false);
  const router = useRouter();
  const searchParams = useSearchParams();
  const email = searchParams.get("email") ?? "";

  useEffect(() => {
    if (!email) router.replace("/signup");
  }, [email, router]);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError("");
    setLoading(true);
    try {
      const res = await fetch("/api/auth/email/confirm", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email, code }),
      });
      const data = await res.json();
      if (!res.ok) {
        setError(data.error ?? "Confirmation failed.");
        return;
      }
      router.push("/login?confirmed=1");
    } catch {
      setError("An unexpected error occurred.");
    } finally {
      setLoading(false);
    }
  }

  async function handleResend() {
    setResendStatus("idle");
    const res = await fetch("/api/auth/email/resend", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email }),
    });
    setResendStatus(res.ok ? "sent" : "error");
  }

  return (
    <main className="flex min-h-screen items-center justify-center bg-gray-50">
      <div className="w-full max-w-sm rounded-2xl bg-white p-8 shadow-md">
        <h1 className="mb-2 text-center text-2xl font-bold text-gray-900">
          Verify your email
        </h1>
        <p className="mb-6 text-center text-sm text-gray-500">
          We sent a 6-digit code to <span className="font-medium text-gray-700">{email}</span>
        </p>

        <form onSubmit={handleSubmit} className="flex flex-col gap-4">
          {error && (
            <p className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-600">
              {error}
            </p>
          )}
          <div className="flex flex-col gap-1">
            <label htmlFor="code" className="text-sm font-medium text-gray-700">
              Verification code
            </label>
            <input
              id="code"
              type="text"
              inputMode="numeric"
              autoComplete="one-time-code"
              maxLength={6}
              required
              value={code}
              onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))}
              className="rounded-lg text-gray-700 border border-gray-300 px-3 py-2 text-center text-lg tracking-widest outline-none focus:border-gray-900 focus:ring-1 focus:ring-gray-900"
              placeholder="000000"
            />
          </div>
          <button
            type="submit"
            disabled={loading}
            className="rounded-lg bg-gray-900 px-4 py-3 text-sm font-medium text-white hover:bg-gray-700 disabled:opacity-50 transition-colors"
          >
            {loading ? "Verifying…" : "Verify email"}
          </button>
        </form>

        <div className="mt-4 text-center text-sm text-gray-500">
          Didn&apos;t receive the code?{" "}
          <button
            onClick={handleResend}
            className="font-medium text-gray-900 hover:underline"
          >
            Resend
          </button>
          {resendStatus === "sent" && (
            <p className="mt-1 text-xs text-green-600">Code resent!</p>
          )}
          {resendStatus === "error" && (
            <p className="mt-1 text-xs text-red-600">Failed to resend. Try again.</p>
          )}
        </div>

        <p className="mt-4 text-center text-sm text-gray-500">
          <Link href="/login" className="font-medium text-gray-900 hover:underline">
            Back to sign in
          </Link>
        </p>
      </div>
    </main>
  );
}

export default function ConfirmPage() {
  return (
    <Suspense>
      <ConfirmForm />
    </Suspense>
  );
}
