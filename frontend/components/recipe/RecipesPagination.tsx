"use client";

interface RecipesPaginationProps {
  /** 0-based current page. */
  page: number;
  /** Total number of pages (>= 1). */
  totalPages: number;
  /** Called with the 0-based page index to navigate to. */
  onPageChange: (page: number) => void;
  /** Optional label to distinguish the two instances for assistive tech (e.g. "top"/"bottom"). */
  position?: string;
}

// Sentinel used in the condensed page list to render a non-clickable gap.
const ELLIPSIS = "ellipsis" as const;
type PageItem = number | typeof ELLIPSIS;

/**
 * Builds a bounded, condensed list of page numbers for large page counts:
 * always the first and last page, plus a small window around the current page,
 * with ellipses filling the gaps. Page indexes returned are 0-based.
 */
function buildPageItems(page: number, totalPages: number): PageItem[] {
  // Small counts: show every page, no ellipses.
  if (totalPages <= 7) {
    return Array.from({ length: totalPages }, (_, i) => i);
  }

  const items: PageItem[] = [];
  const first = 0;
  const last = totalPages - 1;
  const windowStart = Math.max(first + 1, page - 1);
  const windowEnd = Math.min(last - 1, page + 1);

  items.push(first);
  if (windowStart > first + 1) items.push(ELLIPSIS);
  for (let p = windowStart; p <= windowEnd; p++) items.push(p);
  if (windowEnd < last - 1) items.push(ELLIPSIS);
  items.push(last);

  return items;
}

/**
 * Pagination control for the Saved Recipes page: Previous / numbered pages / Next.
 * The current page is driven by the parent (single source of truth), so the top and
 * bottom instances stay in sync. Rendered only when there is more than one page.
 */
export default function RecipesPagination({
  page,
  totalPages,
  onPageChange,
  position,
}: RecipesPaginationProps) {
  if (totalPages <= 1) return null;

  const isFirst = page === 0;
  const isLast = page + 1 >= totalPages;
  const items = buildPageItems(page, totalPages);
  const labelSuffix = position ? ` (${position})` : "";

  return (
    <nav
      className="flex items-center justify-center gap-1"
      aria-label={`Saved recipes pagination${labelSuffix}`}
    >
      <button
        type="button"
        disabled={isFirst}
        onClick={() => onPageChange(page - 1)}
        aria-label="Go to previous page"
        className="rounded-md border border-gray-300 px-3 py-1 text-sm text-gray-700 disabled:opacity-40 disabled:cursor-not-allowed hover:bg-gray-50 transition-colors"
      >
        Previous
      </button>

      {items.map((item, idx) =>
        item === ELLIPSIS ? (
          <span
            key={`ellipsis-${idx}`}
            aria-hidden="true"
            className="px-2 text-sm text-gray-400 select-none"
          >
            …
          </span>
        ) : (
          <button
            key={item}
            type="button"
            onClick={() => onPageChange(item)}
            aria-label={`Go to page ${item + 1}`}
            aria-current={item === page ? "page" : undefined}
            className={`min-w-[2rem] rounded-md border px-2 py-1 text-sm transition-colors ${
              item === page
                ? "border-blue-600 bg-blue-600 text-white"
                : "border-gray-300 bg-white text-gray-700 hover:bg-gray-50"
            }`}
          >
            {item + 1}
          </button>
        ),
      )}

      <button
        type="button"
        disabled={isLast}
        onClick={() => onPageChange(page + 1)}
        aria-label="Go to next page"
        className="rounded-md border border-gray-300 px-3 py-1 text-sm text-gray-700 disabled:opacity-40 disabled:cursor-not-allowed hover:bg-gray-50 transition-colors"
      >
        Next
      </button>
    </nav>
  );
}
