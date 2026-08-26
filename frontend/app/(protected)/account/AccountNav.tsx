"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { Settings, Utensils } from "lucide-react";

const NAV_ITEMS = [
  { href: "/account/settings", label: "Settings", Icon: Settings },
  { href: "/account/dietary", label: "Dietary Restrictions", Icon: Utensils },
];

export default function AccountNav() {
  const pathname = usePathname();

  return (
    <nav aria-label="Account navigation" className="sm:w-56 sm:flex-shrink-0">
      <ul className="flex gap-2 sm:flex-col sm:gap-1">
        {NAV_ITEMS.map(({ href, label, Icon }) => {
          const active = pathname === href || pathname.startsWith(href + "/");
          return (
            <li key={href}>
              <Link
                href={href}
                aria-current={active ? "page" : undefined}
                className={`flex items-center gap-2 rounded-md px-3 py-2 text-sm transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-offset-2 focus-visible:ring-blue-500 ${
                  active
                    ? "bg-blue-50 font-semibold text-blue-800"
                    : "font-medium text-gray-600 hover:bg-gray-100 hover:text-gray-900"
                }`}
              >
                <Icon className="h-4 w-4" />
                {label}
              </Link>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}
