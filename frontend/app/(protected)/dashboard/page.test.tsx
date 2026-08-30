import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import DashboardPage from "./page";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn() }),
}));

vi.mock("next/link", () => ({
  default: ({
    children,
    href,
    ...props
  }: {
    children: React.ReactNode;
    href: string;
  } & React.AnchorHTMLAttributes<HTMLAnchorElement>) => (
    <a href={href} {...props}>
      {children}
    </a>
  ),
}));

function mockProfile(dietaryRestrictions: string[] | null) {
  return Promise.resolve({
    ok: true,
    status: 200,
    json: () =>
      Promise.resolve({
        userId: "u1",
        email: "a@b.com",
        username: "user",
        createdAt: new Date().toISOString(),
        accountStatus: "ACTIVE",
        scheduledDeletionDate: null,
        dietaryRestrictions,
      }),
  } as Response);
}

describe("DashboardPage dietary badges", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("renders restriction badges and an Edit link when restrictions exist", async () => {
    vi.stubGlobal("fetch", vi.fn(() => mockProfile(["VEGAN", "GLUTEN_FREE"])));
    render(<DashboardPage />);

    expect(await screen.findByText("Vegan")).toBeInTheDocument();
    expect(screen.getByText("Gluten-Free")).toBeInTheDocument();
    expect(screen.getByText(/Dietary restrictions:/)).toBeInTheDocument();

    const edit = screen.getByRole("link", { name: /Edit/ });
    expect(edit).toHaveAttribute("href", "/account/dietary");
  });

  it("renders the box with an empty-state message and Edit link when there are no restrictions", async () => {
    vi.stubGlobal("fetch", vi.fn(() => mockProfile([])));
    render(<DashboardPage />);

    // The box renders on a successful load even when the list is empty.
    expect(await screen.findByText(/Dietary restrictions:/)).toBeInTheDocument();
    expect(screen.getByText(/None set/i)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Edit/ })).toHaveAttribute(
      "href",
      "/account/dietary"
    );
    // An empty profile is a success, not an error.
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("surfaces an error (not an empty state) when the profile fetch fails", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(() =>
        Promise.resolve({ ok: false, status: 500, json: () => Promise.resolve(null) } as Response)
      )
    );
    render(<DashboardPage />);

    // The failure must be visible to the user, not masked as an empty profile.
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent(/couldn.?t load your dietary restrictions/i);
    expect(screen.getByRole("button", { name: /Retry/ })).toBeInTheDocument();

    // And it must not be presented as a confirmed empty profile.
    expect(screen.queryByText(/Dietary restrictions:/)).not.toBeInTheDocument();
  });

  it("recovers and shows badges when Retry succeeds after a failure", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({
        ok: false,
        status: 500,
        json: () => Promise.resolve(null),
      } as Response)
      .mockImplementationOnce(() => mockProfile(["VEGAN"]));
    vi.stubGlobal("fetch", fetchMock);

    render(<DashboardPage />);

    const retry = await screen.findByRole("button", { name: /Retry/ });
    fireEvent.click(retry);

    expect(await screen.findByText("Vegan")).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});
