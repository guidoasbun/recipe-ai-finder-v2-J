import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import MealCalendarPage from "./page";
import type { MealPlan, MealPlanEntry } from "@/types/mealPlan";
import { todayIso, startOfWeek } from "@/lib/calendar";

// The page reads window.matchMedia to pick a default view; default to desktop (month).
beforeEach(() => {
  vi.restoreAllMocks();
  vi.stubGlobal(
    "matchMedia",
    vi.fn().mockImplementation((query: string) => ({
      matches: false, // not mobile → month view
      media: query,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      addListener: vi.fn(),
      removeListener: vi.fn(),
      dispatchEvent: vi.fn(),
    })),
  );
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

function planWith(entries: MealPlanEntry[]): MealPlan {
  return {
    mealPlanId: "cal-1",
    ownerUserId: "u1",
    name: "My Meal Plan",
    startDate: null,
    endDate: null,
    servings: 4,
    entries,
    createdAt: "2026-02-01T00:00:00Z",
    updatedAt: "2026-02-01T00:00:00Z",
  };
}

describe("MealCalendarPage", () => {
  it("shows a loading indicator before the plan arrives", () => {
    vi.stubGlobal("fetch", vi.fn(() => new Promise(() => {})));
    const { container } = render(<MealCalendarPage />);
    expect(container.querySelector(".animate-spin")).toBeInTheDocument();
  });

  it("defaults to month view with Week/Month toggle", async () => {
    vi.stubGlobal("fetch", vi.fn(() => jsonResponse(planWith([]))));
    render(<MealCalendarPage />);

    expect(await screen.findByRole("button", { name: "Month" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
    expect(screen.getByRole("button", { name: "Week" })).toHaveAttribute(
      "aria-pressed",
      "false",
    );
  });

  it("switches to week view when toggled", async () => {
    const user = userEvent.setup();
    vi.stubGlobal("fetch", vi.fn(() => jsonResponse(planWith([]))));
    render(<MealCalendarPage />);

    await screen.findByRole("button", { name: "Week" });
    await user.click(screen.getByRole("button", { name: "Week" }));

    expect(screen.getByRole("button", { name: "Week" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
  });

  it("fetches the default (implicit) calendar endpoint", async () => {
    const fetchMock = vi.fn(() => jsonResponse(planWith([])));
    vi.stubGlobal("fetch", fetchMock);
    render(<MealCalendarPage />);

    await screen.findByRole("button", { name: "Month" });
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/backend/api/meal-plans/default",
      expect.anything(),
    );
  });

  it("opens the recipe picker from a day's add control (week view)", async () => {
    const user = userEvent.setup();
    vi.stubGlobal("fetch", vi.fn(() => jsonResponse(planWith([]))));
    render(<MealCalendarPage />);

    // Switch to week view where each slot has a labeled add button.
    await user.click(await screen.findByRole("button", { name: "Week" }));

    const addButtons = await screen.findAllByRole("button", {
      name: /Add a recipe to Dinner/,
    });
    await user.click(addButtons[0]);

    expect(await screen.findByRole("dialog", { name: "Add a recipe" })).toBeInTheDocument();
    // The meal-prep span control is present.
    expect(screen.getByLabelText("Meal prep for")).toBeInTheDocument();
  });

  it("renders a multi-day meal-prep entry as leftovers on continuation days (week view)", async () => {
    const user = userEvent.setup();
    // Anchor to the start of the current week so all 3 covered days fall in the visible week.
    const start = startOfWeek(todayIso());
    const plan = planWith([
      {
        entryId: "e1",
        date: start,
        slot: "DINNER",
        servings: null,
        spanDays: 3,
        recipeSource: "CATALOG",
        recipeId: "cat-1",
        available: true,
        title: "Batch Chili",
        imageUrl: null,
      },
    ]);
    vi.stubGlobal("fetch", vi.fn(() => jsonResponse(plan)));
    render(<MealCalendarPage />);

    await user.click(await screen.findByRole("button", { name: "Week" }));

    // The title appears once (start day) with the meal-prep badge.
    expect(await screen.findByText("Batch Chili")).toBeInTheDocument();
    expect(screen.getByText(/meal prep · 3 days/)).toBeInTheDocument();
    // Continuation days show "Leftovers" (2 of them for a 3-day span).
    expect(screen.getAllByText("Leftovers")).toHaveLength(2);
  });

  it("shows an error with retry when loading fails", async () => {
    const fetchMock = vi.fn(() => jsonResponse(null, false, 500));
    vi.stubGlobal("fetch", fetchMock);
    render(<MealCalendarPage />);

    const retry = await screen.findByRole("button", { name: "Retry" });
    fetchMock.mockImplementation(() => jsonResponse(planWith([])));
    await userEvent.click(retry);

    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Month" })).toBeInTheDocument(),
    );
  });
});
