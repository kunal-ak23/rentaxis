import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

/**
 * Where Logout lives.
 *
 * <p>It used to be a button in the sidebar; it is now the last entry of the
 * profile popover in the header, which only exists once the avatar button is
 * clicked. `e2e/auth/logout.spec.ts` drives exactly that pair of hooks
 * (`profile-menu` then `logout`) for every role project, and it spent a whole
 * suite run timing out on a sidebar button that no longer existed. These two
 * test ids are therefore contract, not decoration.
 */

const signOut = vi.fn();

vi.mock("next-auth/react", () => ({
    useSession: () => ({ data: { user: { name: "Ada Admin", email: "ada@example.com", role: "TENANT_ADMIN" } } }),
    signOut: (...args: unknown[]) => signOut(...args),
}));
vi.mock("next/navigation", () => ({
    useSearchParams: () => new URLSearchParams(),
    usePathname: () => "/en/dashboard",
    useRouter: () => ({ push: vi.fn(), refresh: vi.fn() }),
}));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
    useRouter: () => ({ push: vi.fn() }),
}));
vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
vi.mock("../GlobalSearch", () => ({ default: () => <div /> }));
vi.mock("@/hooks/useTenantFeatures", () => ({
    useTenantFeatures: () => ({ isEnabled: () => true, tenantSlug: "acme", features: {}, loading: false }),
}));

import { TopHeader } from "../TopHeader";
import { NavShellProvider } from "@/components/nav/NavShellContext";

beforeEach(() => {
    signOut.mockReset();
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => 0 })) as never;
});

afterEach(cleanup);

describe("TopHeader profile menu", () => {
    it("hides Logout until the profile menu is opened", () => {
        render(<NavShellProvider><TopHeader /></NavShellProvider>);

        expect(screen.getByTestId("profile-menu")).toBeTruthy();
        expect(screen.queryByTestId("logout")).toBeNull();
    });

    it("reveals a Logout entry that signs the user out", () => {
        render(<NavShellProvider><TopHeader /></NavShellProvider>);

        fireEvent.click(screen.getByTestId("profile-menu"));

        const logout = screen.getByTestId("logout");
        expect(logout.textContent).toContain("Logout");

        fireEvent.click(logout);
        expect(signOut).toHaveBeenCalledTimes(1);
    });

    it("links Help from the header", () => {
        render(<NavShellProvider><TopHeader /></NavShellProvider>);
        expect(screen.getByTestId("header-help")).toHaveAttribute("href", "/dashboard/help");
    });
});
