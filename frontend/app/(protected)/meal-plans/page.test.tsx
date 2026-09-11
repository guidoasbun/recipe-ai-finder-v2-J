import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import MealPlansPage from "./page";
import type { MealPlan } from "@/types/mealPlan";

const pushMock = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock }),
}));

beforeEach(() => {
  vi.restoreAllMocks();
  pushMock.mockReset();
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

function plan(id: string, name: string, entries = 0): MealPlan {
  return {
    mealPlanId: id,
    ownerUserId: "u1",
    name,
    startDate: null,
    endDate: null,
    servings: 4,
    entries: Array.from({ length: entries }, (_, i) => ({
      entryId: `e${i}`,
      date: "2026-02-10",
      slot: "DINNER",
      servings: null,
      recipeSource: "CATALOG",
      recipeId: "cat-1",
      available: true,
      title: "Tacos",
      imageUrl: null,
    })),
    createdAt: "2026-02-01T00:00:00Z",
    updatedAt: "2026-02-01T00:00:00Z",
  };
}

describe("MealPlansPage", () => {
  it("shows the empty state when the user has no plans", async () => {
    vi.stubGlobal("fetch", vi.fn(() => jsonResponse([])));
    render(<MealPlansPage />);
    expect(await screen.findByText(/No meal plans yet/i)).toBeInTheDocument();
  });

  it("lists the user's plans with meal counts", async () => {
    vi.stubGlobal("fetch", vi.fn(() => jsonResponse([plan("p1", "Week A", 3)])));
    render(<MealPlansPage />);

    expect(await screen.findByText("Week A")).toBeInTheDocument();
    expect(screen.getByText(/3 meals/)).toBeInTheDocument();
  });

  it("creates a plan and navigates to it", async () => {
    const user = userEvent.setup();
    const fetchMock = vi
      .fn()
      .mockImplementationOnce(() => jsonResponse([])) // initial list
      .mockImplementationOnce(() => jsonResponse(plan("new-1", "Fresh"))); // create
    vi.stubGlobal("fetch", fetchMock);
    render(<MealPlansPage />);

    await screen.findByText(/No meal plans yet/i);
    await user.click(screen.getByRole("button", { name: /New plan/i }));
    await user.type(screen.getByPlaceholderText(/This Week/i), "Fresh");
    await user.click(screen.getByRole("button", { name: "Create" }));

    await waitFor(() => expect(pushMock).toHaveBeenCalledWith("/meal-plans/new-1"));
  });

  it("shows an error with retry when loading fails", async () => {
    const fetchMock = vi.fn(() => jsonResponse(null, false, 500));
    vi.stubGlobal("fetch", fetchMock);
    render(<MealPlansPage />);

    const retry = await screen.findByRole("button", { name: "Retry" });
    fetchMock.mockImplementation(() => jsonResponse([]));
    await userEvent.click(retry);

    expect(await screen.findByText(/No meal plans yet/i)).toBeInTheDocument();
  });
});
