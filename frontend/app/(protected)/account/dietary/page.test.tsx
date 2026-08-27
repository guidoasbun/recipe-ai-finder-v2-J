import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import DietaryRestrictionsPage from "./page";
import { DIETARY_RESTRICTIONS } from "@/lib/dietary";

function mockFetchResponse(body: unknown, ok = true, status = 200) {
  return Promise.resolve({
    ok,
    status,
    json: () => Promise.resolve(body),
  } as Response);
}

describe("DietaryRestrictionsPage", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("shows a loading indicator before data arrives", () => {
    // Never resolves during this test -> stays in loading state.
    vi.stubGlobal("fetch", vi.fn(() => new Promise(() => {})));
    const { container } = render(<DietaryRestrictionsPage />);
    expect(container.querySelector(".animate-spin")).toBeInTheDocument();
  });

  it("renders all 10 restriction chips after loading", async () => {
    vi.stubGlobal("fetch", vi.fn(() => mockFetchResponse([])));
    render(<DietaryRestrictionsPage />);

    for (const { label } of DIETARY_RESTRICTIONS) {
      expect(await screen.findByRole("switch", { name: label })).toBeInTheDocument();
    }
    expect(screen.getAllByRole("switch")).toHaveLength(10);
  });

  it("pre-selects chips matching saved restrictions", async () => {
    vi.stubGlobal("fetch", vi.fn(() => mockFetchResponse(["VEGAN", "KETO"])));
    render(<DietaryRestrictionsPage />);

    const vegan = await screen.findByRole("switch", { name: "Vegan" });
    const keto = await screen.findByRole("switch", { name: "Keto" });
    const paleo = await screen.findByRole("switch", { name: "Paleo" });

    expect(vegan).toHaveAttribute("aria-checked", "true");
    expect(keto).toHaveAttribute("aria-checked", "true");
    expect(paleo).toHaveAttribute("aria-checked", "false");
  });

  it("toggles a chip's selected state on click", async () => {
    vi.stubGlobal("fetch", vi.fn(() => mockFetchResponse([])));
    render(<DietaryRestrictionsPage />);

    const vegan = await screen.findByRole("switch", { name: "Vegan" });
    expect(vegan).toHaveAttribute("aria-checked", "false");

    await userEvent.click(vegan);
    expect(vegan).toHaveAttribute("aria-checked", "true");

    await userEvent.click(vegan);
    expect(vegan).toHaveAttribute("aria-checked", "false");
  });

  it("shows an error with a retry option when the initial fetch fails", async () => {
    const fetchMock = vi.fn(() => mockFetchResponse(null, false, 500));
    vi.stubGlobal("fetch", fetchMock);
    render(<DietaryRestrictionsPage />);

    const retry = await screen.findByRole("button", { name: "Retry" });
    expect(retry).toBeInTheDocument();

    // Retry triggers another fetch; make the second one succeed.
    fetchMock.mockImplementation(() => mockFetchResponse([]));
    await userEvent.click(retry);

    expect(await screen.findByRole("switch", { name: "Vegan" })).toBeInTheDocument();
  });

  it("shows a success toast after a successful save", async () => {
    const fetchMock = vi
      .fn()
      .mockImplementationOnce(() => mockFetchResponse([])) // initial GET
      .mockImplementationOnce(() => mockFetchResponse(["VEGAN"])); // PUT
    vi.stubGlobal("fetch", fetchMock);
    render(<DietaryRestrictionsPage />);

    const vegan = await screen.findByRole("switch", { name: "Vegan" });
    await userEvent.click(vegan);
    await userEvent.click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() =>
      expect(screen.getByRole("status")).toHaveTextContent(/saved/i)
    );
  });

  it("retains selections and shows an error when saving fails", async () => {
    const fetchMock = vi
      .fn()
      .mockImplementationOnce(() => mockFetchResponse([])) // initial GET
      .mockImplementationOnce(() => mockFetchResponse(null, false, 400)); // PUT fails
    vi.stubGlobal("fetch", fetchMock);
    render(<DietaryRestrictionsPage />);

    const vegan = await screen.findByRole("switch", { name: "Vegan" });
    await userEvent.click(vegan);
    await userEvent.click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() =>
      expect(screen.getByText(/failed to save/i)).toBeInTheDocument()
    );
    // Selection is retained for retry.
    expect(vegan).toHaveAttribute("aria-checked", "true");
  });
});
