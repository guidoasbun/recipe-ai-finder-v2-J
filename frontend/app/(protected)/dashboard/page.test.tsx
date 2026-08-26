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

  it("shows an empty-state box with no badges when there are no restrictions", async () => {
    vi.stubGlobal("fetch", vi.fn(() => mockProfile([])));
    render(<DashboardPage />);

    // The box and its empty-state message render, but no restriction badges.
    expect(await screen.findByText(/Dietary restrictions:/)).toBeInTheDocument();
    expect(screen.getByText(/No dietary restrictions/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Edit/ })).toHaveAttribute(
      "href",
      "/account/dietary"
    );
  });

  it("shows the empty-state box when the profile fetch fails", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(() =>
        Promise.resolve({ ok: false, status: 500, json: () => Promise.resolve(null) } as Response)
      )
    );
    render(<DashboardPage />);

    expect(await screen.findByText(/No dietary restrictions/)).toBeInTheDocument();
  });
});
