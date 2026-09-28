import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import Cookies from "js-cookie";

vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
const path = { current: "/en/dashboard" };
const session = { current: { user: { id: "u", role: "TENANT_USER", tenantId: "t1" } } as { user: { id: string; role: string; tenantId?: string } } };
vi.mock("next/navigation", () => ({ usePathname: () => path.current }));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: session.current, status: "authenticated" }) }));
const router = { push: vi.fn(), replace: vi.fn() };
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>,
    useRouter: () => router,
}));
vi.mock("@/components/ui/MvpSidebar", () => ({ default: () => <nav data-testid="sidebar" /> }));
vi.mock("@/components/ui/TopHeader", () => ({ TopHeader: () => <header data-testid="header" /> }));
const tourProps = { autoStart: undefined as boolean | undefined };
vi.mock("@/components/tour/TourProvider", () => ({
    default: ({ children, autoStart }: { children: React.ReactNode; autoStart?: boolean }) => { tourProps.autoStart = autoStart; return <>{children}</>; },
}));
vi.mock("@/components/help/HelpFAB", () => ({ default: () => null }));

import AuthenticatedLayout from "../AuthenticatedLayout";
import { resetPageOrg } from "@/lib/session/orgSync";

const Page = () => <div data-testid="page-body">admin shell with New User</div>;
const renderAt = (p: string) => {
    path.current = p;
    return render(<AuthenticatedLayout><Page /></AuthenticatedLayout>);
};

beforeEach(() => {
    resetPageOrg();
    session.current = { user: { id: "u", role: "TENANT_USER", tenantId: "t1" } };
});
afterEach(() => { cleanup(); Cookies.remove("active_tenant_id", { path: "/" }); });

describe("AuthenticatedLayout — route role guard (F8)", () => {
    it.each(["/en/superadmin/users", "/en/superadmin/tenants", "/en/dashboard/finance/accounts", "/en/dashboard/finance/bank-accounts", "/ar/dashboard/staff"])(
        "shows access denied, not the admin page, to a TENANT_USER at %s", (p) => {
            renderAt(p);
            expect(screen.getByTestId("page-access-denied")).toBeInTheDocument();
            expect(screen.queryByTestId("page-body")).not.toBeInTheDocument();
            // The shell stays, so the user can navigate away.
            expect(screen.getByTestId("sidebar")).toBeInTheDocument();
        });

    it("renders the page for a role the registry admits, with the tour allowed", () => {
        session.current = { user: { id: "u", role: "TENANT_ADMIN", tenantId: "t1" } };
        renderAt("/en/dashboard/staff");
        expect(screen.getByTestId("page-body")).toBeInTheDocument();
        expect(tourProps.autoStart).toBe(true);
    });

    it("does not auto-start the tour over an access-denied page", () => {
        renderAt("/en/dashboard/staff");
        expect(tourProps.autoStart).toBe(false);
    });

    it("renders the ticket pages a TENANT_USER works in", () => {
        renderAt("/en/dashboard/tickets/abc");
        expect(screen.getByTestId("page-body")).toBeInTheDocument();
    });
});

describe("AuthenticatedLayout — super admin Global View (F7)", () => {
    beforeEach(() => { session.current = { user: { id: "sa", role: "SUPER_ADMIN" } }; });

    it("asks for an organisation instead of mounting an org-scoped page, with a way to the organisation list", () => {
        renderAt("/en/dashboard/leases");
        expect(screen.getByTestId("page-select-org")).toBeInTheDocument();
        expect(screen.queryByTestId("page-body")).not.toBeInTheDocument();
        expect(screen.getByRole("link", { name: /choose an organisation/i })).toHaveAttribute("href", "/en/superadmin/tenants");
    });

    it("lands on the organisation list, not a bare card, when the home page is opened with no organisation (review fix 5)", () => {
        router.replace.mockClear();
        renderAt("/en/dashboard");
        expect(router.replace).toHaveBeenCalledWith("/superadmin/tenants");
        expect(screen.queryByTestId("page-body")).not.toBeInTheDocument();
    });

    it("never lets the tour auto-start while an organisation must be chosen", () => {
        renderAt("/en/dashboard/leases");
        expect(tourProps.autoStart).toBe(false);
    });

    it("mounts the page once an organisation is selected", () => {
        Cookies.set("active_tenant_id", "brk1", { path: "/" });
        renderAt("/en/dashboard");
        expect(screen.getByTestId("page-body")).toBeInTheDocument();
    });

    it("keeps the super admin pages usable in Global View", () => {
        renderAt("/en/superadmin/tenants");
        expect(screen.getByTestId("page-body")).toBeInTheDocument();
    });
});
