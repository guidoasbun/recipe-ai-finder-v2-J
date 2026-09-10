import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import RecipesPage from "./page";
import type { Recipe } from "@/types/recipe";

// RecipeCard uses next/navigation's useRouter, which needs the App Router context. Mock the
// navigation module so cards render standalone in the test environment.
vi.mock("next/navigation", () => ({
  useRouter: () => ({ refresh: vi.fn(), push: vi.fn() }),
}));

interface SavedRecipeResults {
  items: Recipe[];
  page: number;
  pageSize: number;
  totalMatches: number;
}

const PAGE_SIZE = 6;

function makeRecipe(i: number): Recipe {
  return {
    recipeId: `r${i}`,
    title: `Recipe ${i}`,
    description: `Description ${i}`,
    ingredients: [`ingredient ${i}`],
    steps: [`step ${i}`],
    userId: "user-1",
    createdAt: new Date(2026, 0, 1, 0, i).toISOString(),
  };
}

/** Builds a page slice from a full list, echoing the backend's page-response shape. */
function pageResults(all: Recipe[], page: number): SavedRecipeResults {
  const from = page * PAGE_SIZE;
  return {
    items: all.slice(from, from + PAGE_SIZE),
    page,
    pageSize: PAGE_SIZE,
    totalMatches: all.length,
  };
}

function jsonResponse(body: unknown, ok = true, status = 200) {
  return Promise.resolve({
    ok,
    status,
    json: () => Promise.resolve(body),
  } as Response);
}

/**
 * Fetch stub that parses the requested URL's q/page params and returns the matching page.
 * Filters `all` by the q term (title/description/ingredients) like the backend does.
 */
function stubFetchFor(all: Recipe[]) {
  return vi.fn((input: RequestInfo | URL) => {
    const url = new URL(String(input), "http://localhost");
    const q = (url.searchParams.get("q") ?? "").toLowerCase();
    const page = Number(url.searchParams.get("page") ?? "0");

    const filtered = q
      ? all.filter(
          (r) =>
            r.title.toLowerCase().includes(q) ||
            r.description.toLowerCase().includes(q) ||
            r.ingredients.some((ing) => ing.toLowerCase().includes(q)),
        )
      : all;

    return jsonResponse(pageResults(filtered, page));
  });
}

describe("RecipesPage", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("shows a loading indicator before data arrives", () => {
    vi.stubGlobal("fetch", vi.fn(() => new Promise(() => {})));
    const { container } = render(<RecipesPage />);
    expect(container.querySelector(".animate-spin")).toBeInTheDocument();
  });

  it("renders at most one page (6) of recipe cards", async () => {
    const all = Array.from({ length: 15 }, (_, i) => makeRecipe(i));
    vi.stubGlobal("fetch", stubFetchFor(all));

    render(<RecipesPage />);

    // 6 "View" links => 6 cards on the first page.
    await waitFor(() =>
      expect(screen.getAllByRole("link", { name: "View" })).toHaveLength(PAGE_SIZE),
    );
    expect(screen.getByText("Page 1 of 3")).toBeInTheDocument();
  });

  it("renders pagination controls both above and below the grid", async () => {
    const all = Array.from({ length: 15 }, (_, i) => makeRecipe(i));
    vi.stubGlobal("fetch", stubFetchFor(all));

    render(<RecipesPage />);

    await waitFor(() =>
      expect(
        screen.getByRole("navigation", { name: /pagination \(top\)/i }),
      ).toBeInTheDocument(),
    );
    expect(
      screen.getByRole("navigation", { name: /pagination \(bottom\)/i }),
    ).toBeInTheDocument();
  });

  it("navigates to a page when a page number is clicked", async () => {
    const all = Array.from({ length: 15 }, (_, i) => makeRecipe(i));
    vi.stubGlobal("fetch", stubFetchFor(all));

    render(<RecipesPage />);
    const topNav = await screen.findByRole("navigation", { name: /pagination \(top\)/i });

    await userEvent.click(within(topNav).getByRole("button", { name: "Go to page 2" }));

    await waitFor(() => expect(screen.getByText("Page 2 of 3")).toBeInTheDocument());
    // Page 2 is now current in the top nav (scoped to avoid matching the bottom nav too).
    const topNavAfter = screen.getByRole("navigation", { name: /pagination \(top\)/i });
    expect(
      within(topNavAfter).getByRole("button", { name: "Go to page 2" }),
    ).toHaveAttribute("aria-current", "page");
  });

  it("disables Previous on the first page and Next on the last page", async () => {
    const all = Array.from({ length: 8 }, (_, i) => makeRecipe(i));
    vi.stubGlobal("fetch", stubFetchFor(all));

    render(<RecipesPage />);
    const topNav = await screen.findByRole("navigation", { name: /pagination \(top\)/i });

    expect(within(topNav).getByRole("button", { name: "Go to previous page" })).toBeDisabled();
    expect(within(topNav).getByRole("button", { name: "Go to next page" })).toBeEnabled();

    await userEvent.click(within(topNav).getByRole("button", { name: "Go to next page" }));
    await waitFor(() => expect(screen.getByText("Page 2 of 2")).toBeInTheDocument());

    const topNav2 = screen.getByRole("navigation", { name: /pagination \(top\)/i });
    expect(within(topNav2).getByRole("button", { name: "Go to next page" })).toBeDisabled();
  });

  it("resets to page 1 and filters when a search is submitted", async () => {
    const all = Array.from({ length: 15 }, (_, i) => makeRecipe(i));
    vi.stubGlobal("fetch", stubFetchFor(all));

    render(<RecipesPage />);
    // Go to page 2 first.
    const topNav = await screen.findByRole("navigation", { name: /pagination \(top\)/i });
    await userEvent.click(within(topNav).getByRole("button", { name: "Go to page 2" }));
    await waitFor(() => expect(screen.getByText("Page 2 of 3")).toBeInTheDocument());

    // Search for a single recipe.
    await userEvent.type(
      screen.getByRole("textbox", { name: /search your saved recipes/i }),
      "Recipe 9",
    );
    await userEvent.click(screen.getByRole("button", { name: "Search" }));

    await waitFor(() =>
      expect(screen.getByText("1 recipe")).toBeInTheDocument(),
    );
    // Single match => one page, no pagination nav.
    expect(
      screen.queryByRole("navigation", { name: /pagination/i }),
    ).not.toBeInTheDocument();
  });

  it("renders dietary restriction chips on recipe cards", async () => {
    const tagged: Recipe = { ...makeRecipe(0), dietaryTags: ["VEGAN", "GLUTEN_FREE"] };
    vi.stubGlobal("fetch", stubFetchFor([tagged]));

    render(<RecipesPage />);

    // Labels come from lib/dietary's dietaryLabel mapping.
    expect(await screen.findByText("Vegan")).toBeInTheDocument();
    expect(screen.getByText("Gluten-Free")).toBeInTheDocument();
  });

  it("shows the empty state when there are no saved recipes", async () => {
    vi.stubGlobal("fetch", stubFetchFor([]));
    render(<RecipesPage />);

    expect(
      await screen.findByText(/no saved recipes yet/i),
    ).toBeInTheDocument();
  });

  it("shows a distinct no-results state when a search matches nothing", async () => {
    const all = Array.from({ length: 3 }, (_, i) => makeRecipe(i));
    vi.stubGlobal("fetch", stubFetchFor(all));

    render(<RecipesPage />);
    await screen.findByText("Page 1 of 1");

    await userEvent.type(
      screen.getByRole("textbox", { name: /search your saved recipes/i }),
      "zzz-no-match",
    );
    await userEvent.click(screen.getByRole("button", { name: "Search" }));

    expect(await screen.findByText(/no recipes match your search/i)).toBeInTheDocument();
  });

  it("shows an error with retry when the fetch fails", async () => {
    const fetchMock = vi.fn(() => jsonResponse(null, false, 500));
    vi.stubGlobal("fetch", fetchMock);

    render(<RecipesPage />);

    const retry = await screen.findByRole("button", { name: "Retry" });
    expect(retry).toBeInTheDocument();

    fetchMock.mockImplementation(() => jsonResponse(pageResults([makeRecipe(0)], 0)));
    await userEvent.click(retry);

    await waitFor(() =>
      expect(screen.getByRole("link", { name: "View" })).toBeInTheDocument(),
    );
  });
});
