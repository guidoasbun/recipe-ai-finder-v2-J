import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import AddToPlanButton from "./AddToPlanButton";
import type { MealPlan } from "@/types/mealPlan";
import { todayIso } from "@/lib/calendar";

beforeEach(() => {
  vi.restoreAllMocks();
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

const PLAN: MealPlan = {
  mealPlanId: "cal-1",
  ownerUserId: "u1",
  name: "My Meal Plan",
  startDate: null,
  endDate: null,
  servings: 4,
  entries: [],
  createdAt: "2026-02-01T00:00:00Z",
  updatedAt: "2026-02-01T00:00:00Z",
};

/**
 * fetch stub that returns the default plan for GET .../meal-plans/default and echoes the plan
 * for the POST .../entries add. Returns the jest-style mock so tests can inspect calls.
 */
function stubHappyPath() {
  const fetchMock = vi.fn((url: string, init?: RequestInit) => {
    if (typeof url === "string" && url.endsWith("/meal-plans/default")) {
      return jsonResponse(PLAN);
    }
    if (typeof url === "string" && url.includes("/entries") && init?.method === "POST") {
      return jsonResponse(PLAN);
    }
    return jsonResponse(null, false, 404);
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

describe("AddToPlanButton", () => {
  it("does not render when recipeId is blank", () => {
    const { container } = render(<AddToPlanButton source="CATALOG" recipeId="" />);
    expect(container).toBeEmptyDOMElement();
  });

  it("opens the date/slot sheet showing the recipe title", async () => {
    const user = userEvent.setup();
    stubHappyPath();
    render(<AddToPlanButton source="SAVED" recipeId="r1" title="Tacos" />);

    await user.click(screen.getByRole("button", { name: /Add to plan/i }));

    const dialog = await screen.findByRole("dialog");
    expect(dialog).toBeInTheDocument();
    expect(screen.getByText("Tacos")).toBeInTheDocument();
    // Date defaults to today, slot to Dinner, span control present.
    expect(screen.getByLabelText("Date")).toHaveValue(todayIso());
    expect(screen.getByLabelText("Meal slot")).toHaveValue("DINNER");
    expect(screen.getByLabelText("Meal prep for")).toBeInTheDocument();
  });

  it("adds a SAVED recipe to the default plan with the chosen date/slot/span", async () => {
    const user = userEvent.setup();
    const fetchMock = stubHappyPath();
    render(<AddToPlanButton source="SAVED" recipeId="r1" title="Tacos" />);

    await user.click(screen.getByRole("button", { name: /Add to plan/i }));
    await user.selectOptions(await screen.findByLabelText("Meal slot"), "LUNCH");
    await user.selectOptions(screen.getByLabelText("Meal prep for"), "3");
    // Confirm (the submit button inside the sheet).
    const buttons = screen.getAllByRole("button", { name: /Add to plan/i });
    await user.click(buttons[buttons.length - 1]);

    await waitFor(() => {
      const post = fetchMock.mock.calls.find(
        ([url, init]) =>
          typeof url === "string" &&
          url.includes("/meal-plans/cal-1/entries") &&
          (init as RequestInit | undefined)?.method === "POST",
      );
      expect(post).toBeTruthy();
    });

    const post = fetchMock.mock.calls.find(
      ([url, init]) =>
        typeof url === "string" &&
        url.includes("/meal-plans/cal-1/entries") &&
        (init as RequestInit | undefined)?.method === "POST",
    )!;
    const body = JSON.parse((post[1] as RequestInit).body as string);
    expect(body).toEqual({
      date: todayIso(),
      slot: "LUNCH",
      source: "SAVED",
      recipeId: "r1",
      spanDays: 3,
    });

    // First resolves the default plan, then posts the entry.
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/backend/api/meal-plans/default",
      expect.anything(),
    );
  });

  it("sends source CATALOG for a catalog recipe", async () => {
    const user = userEvent.setup();
    const fetchMock = stubHappyPath();
    render(<AddToPlanButton source="CATALOG" recipeId="cat-9" title="Chili" variant="icon" />);

    await user.click(screen.getByRole("button", { name: /Add Chili to plan/i }));
    const buttons = await screen.findAllByRole("button", { name: /Add to plan/i });
    await user.click(buttons[buttons.length - 1]);

    await waitFor(() => {
      const post = fetchMock.mock.calls.find(
        ([url, init]) =>
          typeof url === "string" &&
          url.includes("/entries") &&
          (init as RequestInit | undefined)?.method === "POST",
      );
      expect(post).toBeTruthy();
    });
    const post = fetchMock.mock.calls.find(
      ([url, init]) =>
        typeof url === "string" &&
        url.includes("/entries") &&
        (init as RequestInit | undefined)?.method === "POST",
    )!;
    const body = JSON.parse((post[1] as RequestInit).body as string);
    expect(body.source).toBe("CATALOG");
    expect(body.recipeId).toBe("cat-9");
  });

  it("keeps the sheet open and shows a retryable error when the add fails", async () => {
    const user = userEvent.setup();
    const fetchMock = vi.fn((url: string) => {
      if (typeof url === "string" && url.endsWith("/meal-plans/default")) {
        return jsonResponse(PLAN);
      }
      return jsonResponse(null, false, 500); // the POST fails
    });
    vi.stubGlobal("fetch", fetchMock);
    render(<AddToPlanButton source="SAVED" recipeId="r1" title="Tacos" />);

    await user.click(screen.getByRole("button", { name: /Add to plan/i }));
    const buttons = await screen.findAllByRole("button", { name: /Add to plan/i });
    await user.click(buttons[buttons.length - 1]);

    expect(await screen.findByRole("alert")).toHaveTextContent(/couldn't add/i);
    // The sheet is still open so the user can retry.
    expect(screen.getByRole("dialog")).toBeInTheDocument();
  });

  it("stops the click from bubbling to a surrounding clickable card", async () => {
    const user = userEvent.setup();
    stubHappyPath();
    const onParentClick = vi.fn();
    render(
      // The browse card wraps the whole surface in a next/link; the button must stop the
      // click from bubbling (which would otherwise trigger navigation). A div with an onClick
      // stands in for that clickable ancestor.
      <div onClick={onParentClick}>
        <AddToPlanButton source="CATALOG" recipeId="cat-9" title="Chili" variant="icon" />
      </div>,
    );

    await user.click(screen.getByRole("button", { name: /Add Chili to plan/i }));

    expect(onParentClick).not.toHaveBeenCalled();
    expect(await screen.findByRole("dialog")).toBeInTheDocument();
  });
});
