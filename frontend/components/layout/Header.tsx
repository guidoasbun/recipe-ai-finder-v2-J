"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

const NAV_LINKS = [
  { href: "/dashboard", label: "Generate" },
  { href: "/browse", label: "Look for Existing Recipes" },
  { href: "/recipes", label: "Saved Recipes" },
  { href: "/model-stats", label: "Model Stats" },
  { href: "/account", label: "Account Settings" },
];

export default function Header() {
  const pathname = usePathname();

  function handleLogout() {
    window.location.href = "/api/auth/logout";
  }

  return (
    <header className="border-b border-gray-200 bg-white">
      <div className="mx-auto flex max-w-5xl items-center justify-between px-4 py-4">
        <Link href="/dashboard" className="flex items-center gap-2 text-lg font-bold text-gray-900">
          <span>🍳</span>
          <span>Recipe AI Finder</span>
        </Link>
        <nav className="flex items-center gap-6">
          {NAV_LINKS.map(({ href, label }) => {
            const active = pathname === href || pathname.startsWith(href + "/");
            return (
              <Link
                key={href}
                href={href}
                className="relative text-sm font-medium transition-colors"
                style={{ color: active ? "#00244E" : "#4b5563" }}
              >
                {label}
                {active && (
                  <span
                    className="absolute -bottom-[18px] left-0 right-0 h-0.5"
                    style={{ backgroundColor: "#00244E" }}
                  />
                )}
              </Link>
            );
          })}
          <button
            onClick={handleLogout}
            className="text-sm text-red-500 hover:text-red-700 transition-colors"
          >
            Logout
          </button>
        </nav>
      </div>
    </header>
  );
}
