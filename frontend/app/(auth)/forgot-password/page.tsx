"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";

export default function ForgotPasswordPage() {
  const [email, setEmail] = useState("");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const router = useRouter();

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError("");
    setLoading(true);
    try {
      const res = await fetch("/api/auth/email/forgot-password", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email }),
      });
      const data = await res.json();
      if (!res.ok) {
        setError(data.error ?? "Failed to send reset code.");
        return;
      }
      router.push(`/reset-password?email=${encodeURIComponent(email)}`);
    } catch {
      setError("An unexpected error occurred.");
    } finally {
      setLoading(false);
    }
  }

  return (
    <main className="flex min-h-screen items-center justify-center bg-gray-50">
      <div className="w-full max-w-sm rounded-2xl bg-white p-8 shadow-md">
        <h1 className="mb-2 text-center text-2xl font-bold text-gray-900">
          Forgot password
        </h1>
        <p className="mb-6 text-center text-sm text-gray-500">
          Enter your email and we&apos;ll send you a reset code
        </p>

        <form onSubmit={handleSubmit} className="flex flex-col gap-4">
          {error && (
            <p className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-600">
              {error}
            </p>
          )}
          <div className="flex flex-col gap-1">
            <label htmlFor="email" className="text-sm font-medium text-gray-700">
              Email
            </label>
            <input
              id="email"
              type="email"
              autoComplete="email"
              required
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              className="rounded-lg border border-gray-300 text-gray-700 px-3 py-2 text-sm outline-none focus:border-[#003DA5] focus:ring-1 focus:ring-[#003DA5]"
              placeholder="you@example.com"
            />
          </div>
          <button
            type="submit"
            disabled={loading}
            className="rounded-lg px-4 py-3 text-sm font-medium text-white disabled:opacity-50 transition-colors"
            style={{ backgroundColor: "#FF7900" }}
            onMouseEnter={(e) => { if (!loading) e.currentTarget.style.backgroundColor = "#e06a00"; }}
            onMouseLeave={(e) => { e.currentTarget.style.backgroundColor = "#FF7900"; }}
          >
            {loading ? "Sending…" : "Send reset code"}
          </button>
          <p className="text-center text-sm text-gray-500">
            <Link href="/login" className="font-medium hover:underline" style={{ color: "#003DA5" }}>
              Back to sign in
            </Link>
          </p>
        </form>
      </div>
    </main>
  );
}
