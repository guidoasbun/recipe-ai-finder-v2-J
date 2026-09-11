import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import MealPlanCalendarPage from "./page";
import type { MealPlan } from "@/types/mealPlan";

// next/navigation is server-aware; stub the hooks the page uses.
const notFoundMock = vi.fn();
vi.mock("next/navigation", () => ({
  useParams: () => ({ id: "plan-1" }),
  notFound: () => notFoundMock(),
}));

// The calendar defaults to the local "today"; compute it the same way the component does so
// entries placed on that date render in the default view. Real timers (fake timers break the
// polling in findBy*/waitFor).
function todayIso(): string {
  const d = new Date();
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${y}-${m}-${day}`;
}
const TODAY = todayIso();

beforeEach(() => {
  vi.restoreAllMocks();
  notFoundMock.mockReset();
});
afterEach(() => {
  vi.restoreAllMocks();
});

function jsonResponse(body: unknown, ok = true, status = 200) {
  return Promise.resolve({
    ok,
    status,
    json: () => Promise.resolve(body),
  } as Response);
}

function planWith(entries: MealPlan["entries"]): MealPlan {
  return {
    mealPlanId: "plan-1",
    ownerUserId: "u1",
    name: "This Week",
    startDate: null,
    endDate: null,
    servings: 4,
    entries,
    createdAt: "2026-02-01T00:00:00Z",
    updatedAt: "2026-02-01T00:00:00Z",
  };
}

describe("MealPlanCalendarPage", () => {
  it("shows a loading indicator before the plan arrives", () => {
    vi.stubGlobal("fetch", vi.fn(() => new Promise(() => {})));
    const { container } = render(<MealPlanCalendarPage />);
    expect(container.querySelector(".animate-spin")).toBeInTheDocument();
  });

  it("renders the plan and shows all four slots with an Add control each", async () => {
    vi.stubGlobal("fetch", vi.fn(() => jsonResponse(planWith([]))));
    render(<MealPlanCalendarPage />);

    expect(await screen.findByRole("heading", { name: "This Week" })).toBeInTheDocument();
    expect(screen.getByText("Breakfast")).toBeInTheDocument();
    expect(screen.getByText("Lunch")).toBeInTheDocument();
    expect(screen.getByText("Dinner")).toBeInTheDocument();
    expect(screen.getByText("Snack")).toBeInTheDocument();
    // One Add button per slot.
    expect(screen.getAllByRole("button", { name: /Add a recipe to/ })).toHaveLength(4);
    // Empty slots show the empty message.
    expect(screen.getAllByText("Nothing planned.")).toHaveLength(4);
  });

  it("renders an available entry in its slot", async () => {
    const plan = planWith([
      {
        entryId: "e1",
        date: TODAY,
        slot: "DINNER",
        servings: null,
        recipeSource: "CATALOG",
        recipeId: "cat-1",
        available: true,
        title: "Tacos",
        imageUrl: null,
      },
    ]);
    vi.stubGlobal("fetch", vi.fn(() => jsonResponse(plan)));
    render(<MealPlanCalendarPage />);

    expect(await screen.findByText("Tacos")).toBeInTheDocument();
  });

  it("links an available catalog entry to its browse detail page", async () => {
    const plan = planWith([
      {
        entryId: "e1",
        date: TODAY,
        slot: "DINNER",
        servings: null,
        recipeSource: "CATALOG",
        recipeId: "cat-1",
        available: true,
        title: "Tacos",
        imageUrl: null,
      },
    ]);
    vi.stubGlobal("fetch", vi.fn(() => jsonResponse(plan)));
    render(<MealPlanCalendarPage />);

    const link = await screen.findByRole("link", { name: /Tacos/ });
    expect(link).toHaveAttribute("href", "/browse/cat-1");
  });

  it("links an available saved entry to its saved-recipe detail page", async () => {
    const plan = planWith([
      {
        entryId: "e1",
        date: TODAY,
        slot: "LUNCH",
        servings: null,
        recipeSource: "SAVED",
        recipeId: "rec-9",
        available: true,
        title: "Omelette",
        imageUrl: null,
      },
    ]);
    vi.stubGlobal("fetch", vi.fn(() => jsonResponse(plan)));
    render(<MealPlanCalendarPage />);

    const link = await screen.findByRole("link", { name: /Omelette/ });
    expect(link).toHaveAttribute("href", "/recipes/rec-9");
  });

  it("does not link an unavailable entry", async () => {
    const plan = planWith([
      {
        entryId: "e1",
        date: TODAY,
        slot: "DINNER",
        servings: null,
        recipeSource: "CATALOG",
        recipeId: "gone",
        available: false,
        title: null,
        imageUrl: null,
      },
    ]);
    vi.stubGlobal("fetch", vi.fn(() => jsonResponse(plan)));
    render(<MealPlanCalendarPage />);

    expect(await screen.findByText("Recipe unavailable")).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /Recipe unavailable/ })).not.toBeInTheDocument();
  });

  it("shows a placeholder for an unavailable entry rather than breaking", async () => {
    const plan = planWith([
      {
        entryId: "e1",
        date: TODAY,
        slot: "LUNCH",
        servings: null,
        recipeSource: "CATALOG",
        recipeId: "gone",
        available: false,
        title: null,
        imageUrl: null,
      },
    ]);
    vi.stubGlobal("fetch", vi.fn(() => jsonResponse(plan)));
    render(<MealPlanCalendarPage />);

    expect(await screen.findByText("Recipe unavailable")).toBeInTheDocument();
    expect(screen.getByText(/may have been deleted/i)).toBeInTheDocument();
  });

  it("opens the recipe picker when an Add control is tapped", async () => {
    const user = userEvent.setup();
    vi.stubGlobal("fetch", vi.fn(() => jsonResponse(planWith([]))));
    render(<MealPlanCalendarPage />);

    const addDinner = await screen.findByRole("button", {
      name: "Add a recipe to Dinner",
    });
    await user.click(addDinner);

    const dialog = await screen.findByRole("dialog", { name: "Add a recipe" });
    expect(dialog).toBeInTheDocument();
    // Picker context shows the chosen slot.
    expect(within(dialog).getByText(/Dinner/)).toBeInTheDocument();
  });

  it("removes an entry when its remove control is tapped (optimistic)", async () => {
    const user = userEvent.setup();
    const plan = planWith([
      {
        entryId: "e1",
        date: TODAY,
        slot: "DINNER",
        servings: null,
        recipeSource: "CATALOG",
        recipeId: "cat-1",
        available: true,
        title: "Tacos",
        imageUrl: null,
      },
    ]);
    const fetchMock = vi
      .fn()
      .mockImplementationOnce(() => jsonResponse(plan)) // initial GET
      .mockImplementationOnce(() => jsonResponse(planWith([]))); // DELETE returns updated plan
    vi.stubGlobal("fetch", fetchMock);
    render(<MealPlanCalendarPage />);

    expect(await screen.findByText("Tacos")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Remove from plan" }));

    await waitFor(() => expect(screen.queryByText("Tacos")).not.toBeInTheDocument());
  });

  it("calls notFound() on a 404", async () => {
    vi.stubGlobal("fetch", vi.fn(() => jsonResponse(null, false, 404)));
    render(<MealPlanCalendarPage />);
    await waitFor(() => expect(notFoundMock).toHaveBeenCalled());
  });
});
