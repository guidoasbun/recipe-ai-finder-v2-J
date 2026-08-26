import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import AccountNav from "./AccountNav";

const mockUsePathname = vi.fn();

vi.mock("next/navigation", () => ({
  usePathname: () => mockUsePathname(),
}));

// next/link renders a plain anchor in the test environment.
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

describe("AccountNav", () => {
  beforeEach(() => {
    mockUsePathname.mockReset();
  });

  it("renders a labeled navigation with both links", () => {
    mockUsePathname.mockReturnValue("/account/settings");
    render(<AccountNav />);

    const nav = screen.getByRole("navigation", { name: "Account navigation" });
    expect(nav).toBeInTheDocument();

    expect(screen.getByRole("link", { name: /Settings/ })).toHaveAttribute(
      "href",
      "/account/settings"
    );
    expect(
      screen.getByRole("link", { name: /Dietary Restrictions/ })
    ).toHaveAttribute("href", "/account/dietary");
  });

  it("marks the settings link active on /account/settings", () => {
    mockUsePathname.mockReturnValue("/account/settings");
    render(<AccountNav />);

    const settings = screen.getByRole("link", { name: /Settings/ });
    const dietary = screen.getByRole("link", { name: /Dietary Restrictions/ });

    expect(settings).toHaveAttribute("aria-current", "page");
    expect(dietary).not.toHaveAttribute("aria-current");
  });

  it("marks the dietary link active on /account/dietary", () => {
    mockUsePathname.mockReturnValue("/account/dietary");
    render(<AccountNav />);

    const settings = screen.getByRole("link", { name: /Settings/ });
    const dietary = screen.getByRole("link", { name: /Dietary Restrictions/ });

    expect(dietary).toHaveAttribute("aria-current", "page");
    expect(settings).not.toHaveAttribute("aria-current");
  });
});
