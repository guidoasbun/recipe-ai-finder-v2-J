"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";

type Tab = "google" | "email";

export function LoginForm({ googleLoginUrl }: { googleLoginUrl: string }) {
  const [tab, setTab] = useState<Tab>("google");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const router = useRouter();

  async function handleEmailSignIn(e: React.FormEvent) {
    e.preventDefault();
    setError("");
    setLoading(true);
    try {
      const res = await fetch("/api/auth/email/signin", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email, password }),
      });
      const data = await res.json();
      if (!res.ok) {
        setError(data.error ?? "Sign in failed.");
        return;
      }
      router.push("/dashboard");
    } catch {
      setError("An unexpected error occurred.");
    } finally {
      setLoading(false);
    }
  }

  return (
    <main className="flex flex-1">
      {/* Left branding panel */}
      <div
        className="hidden md:flex md:w-1/2 flex-col justify-between p-12 text-white"
        style={{ background: "linear-gradient(135deg, #003DA5 0%, #002880 100%)" }}
      >
        <div>
          <span className="text-3xl">🍳</span>
          <h2 className="mt-4 text-3xl font-bold leading-tight">Recipe AI Finder</h2>
          <p className="mt-3 text-lg text-blue-100">
            Turn your ingredients into restaurant-quality meals.
          </p>
        </div>

        <ul className="space-y-5">
          <li className="flex items-start gap-3">
            <span
              className="mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-full text-xs font-bold"
              style={{ backgroundColor: "#FF7900" }}
            >
              ✓
            </span>
            <div>
              <p className="font-semibold">AI-generated recipes</p>
              <p className="text-sm text-blue-200">Choose from multiple Claude & AWS models</p>
            </div>
          </li>
          <li className="flex items-start gap-3">
            <span
              className="mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-full text-xs font-bold"
              style={{ backgroundColor: "#FF7900" }}
            >
              ✓
            </span>
            <div>
              <p className="font-semibold">Beautiful food photos</p>
              <p className="text-sm text-blue-200">AI-generated images for every dish</p>
            </div>
          </li>
          <li className="flex items-start gap-3">
            <span
              className="mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-full text-xs font-bold"
              style={{ backgroundColor: "#FF7900" }}
            >
              ✓
            </span>
            <div>
              <p className="font-semibold">Save your favorites</p>
              <p className="text-sm text-blue-200">Build your personal recipe collection</p>
            </div>
          </li>
        </ul>

        <p className="text-xs text-blue-300">Built by by Guido Asbun - guido@asbun.io</p>
      </div>

      {/* Right auth panel */}
      <div className="flex w-full flex-col items-center justify-center bg-white px-8 py-12 md:w-1/2">
        <div className="w-full max-w-sm">
          {/* Mobile-only header */}
          <div className="mb-8 text-center md:hidden">
            <span className="text-4xl">🍳</span>
            <h1 className="mt-2 text-2xl font-bold text-gray-900">Recipe AI Finder</h1>
          </div>

          <h1 className="mb-1 text-2xl font-bold text-gray-900">Welcome back</h1>
          <p className="mb-8 text-sm text-gray-500">Sign in to generate and save recipes</p>

          <div className="mb-6 flex rounded-lg border border-gray-200 p-1">
            <button
              onClick={() => setTab("google")}
              className="flex-1 rounded-md py-2 text-sm font-medium transition-colors"
              style={
                tab === "google"
                  ? { backgroundColor: "#00244E", color: "#fff" }
                  : { color: "#6b7280" }
              }
            >
              Google
            </button>
            <button
              onClick={() => setTab("email")}
              className="flex-1 rounded-md py-2 text-sm font-medium transition-colors"
              style={
                tab === "email"
                  ? { backgroundColor: "#00244E", color: "#fff" }
                  : { color: "#6b7280" }
              }
            >
              Email
            </button>
          </div>

          {tab === "google" ? (
            <a
              href={googleLoginUrl}
              className="flex w-full items-center justify-center gap-3 rounded-lg border border-gray-300 bg-white px-4 py-3 text-sm font-medium text-gray-700 shadow-sm hover:bg-gray-50 transition-colors"
            >
              <svg className="h-5 w-5" viewBox="0 0 24 24">
                <path d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z" fill="#4285F4"/>
                <path d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z" fill="#34A853"/>
                <path d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l3.66-2.84z" fill="#FBBC05"/>
                <path d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z" fill="#EA4335"/>
              </svg>
              Sign in with Google
            </a>
          ) : (
            <form onSubmit={handleEmailSignIn} className="flex flex-col gap-4">
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
                  className="rounded-lg border text-gray-700 border-gray-300 px-3 py-2 text-sm outline-none"
                  style={{ "--tw-ring-color": "#003DA5" } as React.CSSProperties}
                  onFocus={(e) => { e.target.style.borderColor = "#003DA5"; e.target.style.boxShadow = "0 0 0 1px #003DA5"; }}
                  onBlur={(e) => { e.target.style.borderColor = "#d1d5db"; e.target.style.boxShadow = "none"; }}
                  placeholder="you@example.com"
                />
              </div>
              <div className="flex flex-col gap-1">
                <div className="flex items-center justify-between">
                  <label htmlFor="password" className="text-sm font-medium text-gray-700">
                    Password
                  </label>
                  <Link
                    href="/forgot-password"
                    className="text-xs text-gray-500 hover:text-gray-900"
                  >
                    Forgot password?
                  </Link>
                </div>
                <input
                  id="password"
                  type="password"
                  autoComplete="current-password"
                  required
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  className="rounded-lg text-gray-700 border border-gray-300 px-3 py-2 text-sm outline-none"
                  onFocus={(e) => { e.target.style.borderColor = "#003DA5"; e.target.style.boxShadow = "0 0 0 1px #003DA5"; }}
                  onBlur={(e) => { e.target.style.borderColor = "#d1d5db"; e.target.style.boxShadow = "none"; }}
                  placeholder="••••••••"
                />
              </div>
              <button
                type="submit"
                disabled={loading}
                className="rounded-lg px-4 py-3 text-sm font-medium text-white disabled:opacity-50 transition-colors"
                style={{ backgroundColor: "#FF7900" }}
                onMouseEnter={(e) => { if (!loading) (e.target as HTMLButtonElement).style.backgroundColor = "#e06a00"; }}
                onMouseLeave={(e) => { (e.target as HTMLButtonElement).style.backgroundColor = "#FF7900"; }}
              >
                {loading ? "Signing in…" : "Sign in"}
              </button>
              <p className="text-center text-sm text-gray-500">
                Don&apos;t have an account?{" "}
                <Link href="/signup" className="font-medium hover:underline" style={{ color: "#003DA5" }}>
                  Sign up
                </Link>
              </p>
            </form>
          )}
        </div>
      </div>
    </main>
  );
}
