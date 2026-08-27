import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen } from "@testing-library/react";
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

  it("does not render the restrictions banner when there are no saved restrictions", async () => {
    vi.stubGlobal("fetch", vi.fn(() => mockProfile([])));
    render(<DashboardPage />);

    // Wait for the primary form to render so the profile load has settled.
    expect(
      await screen.findByPlaceholderText(/chicken, garlic, lemon/i)
    ).toBeInTheDocument();

    // Per Requirement 5.2, the banner must not render for an empty profile.
    expect(screen.queryByText(/Dietary restrictions:/)).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /Edit/ })).not.toBeInTheDocument();
  });

  it("does not render the restrictions banner when the profile fetch fails", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(() =>
        Promise.resolve({ ok: false, status: 500, json: () => Promise.resolve(null) } as Response)
      )
    );
    render(<DashboardPage />);

    // Wait for the primary form to render so the profile load has settled.
    expect(
      await screen.findByPlaceholderText(/chicken, garlic, lemon/i)
    ).toBeInTheDocument();

    // A failed request must not be presented as a confirmed empty profile.
    expect(screen.queryByText(/Dietary restrictions:/)).not.toBeInTheDocument();
  });
});
