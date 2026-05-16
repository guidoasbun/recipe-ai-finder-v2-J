"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";

export default function SignupPage() {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const router = useRouter();

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError("");
    if (password !== confirm) {
      setError("Passwords do not match.");
      return;
    }
    setLoading(true);
    try {
      const res = await fetch("/api/auth/email/signup", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email, password }),
      });
      const data = await res.json();
      if (!res.ok) {
        setError(data.error ?? "Sign up failed.");
        return;
      }
      router.push(`/confirm?email=${encodeURIComponent(email)}`);
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
          Create account
        </h1>
        <p className="mb-6 text-center text-sm text-gray-500">
          Sign up to start generating recipes
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
              className="rounded-lg border text-gray-700 border-gray-300 px-3 py-2 text-sm outline-none focus:border-[#003DA5] focus:ring-1 focus:ring-[#003DA5]"
              placeholder="you@example.com"
            />
          </div>
          <div className="flex flex-col gap-1">
            <label htmlFor="password" className="text-sm font-medium text-gray-700">
              Password
            </label>
            <input
              id="password"
              type="password"
              autoComplete="new-password"
              required
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className="rounded-lg border text-gray-700 border-gray-300 px-3 py-2 text-sm outline-none focus:border-[#003DA5] focus:ring-1 focus:ring-[#003DA5]"
              placeholder="••••••••"
            />
            <p className="text-xs text-gray-400">
              Min 8 characters with uppercase, lowercase, and numbers
            </p>
          </div>
          <div className="flex flex-col gap-1">
            <label htmlFor="confirm" className="text-sm font-medium text-gray-700">
              Confirm password
            </label>
            <input
              id="confirm"
              type="password"
              autoComplete="new-password"
              required
              value={confirm}
              onChange={(e) => setConfirm(e.target.value)}
              className="rounded-lg border text-gray-700 border-gray-300 px-3 py-2 text-sm outline-none focus:border-[#003DA5] focus:ring-1 focus:ring-[#003DA5]"
              placeholder="••••••••"
            />
          </div>
          <button
            type="submit"
            disabled={loading}
            className="rounded-lg px-4 py-3 text-sm font-medium text-white disabled:opacity-50 transition-colors"
            style={{ backgroundColor: "#FF7900" }}
            onMouseEnter={(e) => { if (!loading) (e.currentTarget).style.backgroundColor = "#e06a00"; }}
            onMouseLeave={(e) => { e.currentTarget.style.backgroundColor = "#FF7900"; }}
          >
            {loading ? "Creating account…" : "Create account"}
          </button>
          <p className="text-center text-sm text-gray-500">
            Already have an account?{" "}
            <Link href="/login" className="font-medium hover:underline" style={{ color: "#003DA5" }}>
              Sign in
            </Link>
          </p>
        </form>
      </div>
    </main>
  );
}
