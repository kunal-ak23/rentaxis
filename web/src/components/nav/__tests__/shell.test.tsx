import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import ar from "../../../../messages/ar.json";
import en from "../../../../messages/en.json";

const role = { current: "TENANT_ADMIN" };
const path = { current: "/en/dashboard" };
const search = { current: "" };
const flags = { current: ["LISTINGS", "MEETINGS", "GATEPASS"] as string[] };

vi.mock("next/navigation", () => ({ useSearchParams: () => new URLSearchParams(search.current), usePathname: () => path.current, useRouter: () => ({ push: vi.fn(), refresh: vi.fn() }) }));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>,
    useRouter: () => ({ push: vi.fn() }),
}));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: role.current, name: "U", tenantId: "t1" } } }) }));
vi.mock("@/hooks/useTenantFeatures", () => ({
    useTenantFeatures: () => ({ isEnabled: (f: string) => flags.current.includes(f), tenantSlug: "acme", features: {}, loading: false }),
}));
vi.mock("@/components/nav/useNavCounts", () => ({
    useNavCounts: () => ({ collectionBadge: 7, chequesToDeposit: 3, booksLockedThrough: "2025-12-31", booksLive: true }),
}));
vi.mock("@/components/ui/TenantSwitcher", () => ({ TenantSwitcher: () => <div data-testid="org-switcher" /> }));
vi.mock("@/components/nav/orgStore", () => ({ useMyOrgs: () => ({ orgs: [{ id: "t1", name: "Acme Holdings" }], active: { id: "t1", name: "Acme Holdings" } }) }));
vi.mock("next/image", () => ({ default: ({ alt }: { alt: string }) => <img alt={alt} /> }));

import MvpSidebar from "@/components/ui/MvpSidebar";
import { NavShellProvider } from "@/components/nav/NavShellContext";

function renderShell(locale: "en" | "ar" = "en") {
    return render(
        <NextIntlClientProvider locale={locale} messages={locale === "en" ? en : ar}>
            <NavShellProvider><MvpSidebar /></NavShellProvider>
        </NextIntlClientProvider>,
    );
}

beforeEach(() => {
    role.current = "TENANT_ADMIN";
    path.current = "/en/dashboard";
    search.current = "";
    flags.current = ["LISTINGS", "MEETINGS", "GATEPASS"];
    Object.defineProperty(window, "localStorage", { value: { getItem: () => null, setItem: () => {}, removeItem: () => {} }, writable: true });
});
afterEach(() => { cleanup(); vi.clearAllMocks(); });

describe("icon rail", () => {
    it("shows the tenant admin's seven sections in order", () => {
        renderShell();
        const rail = screen.getByTestId("nav-rail");
        expect(Array.from(rail.querySelectorAll("[data-rail]")).map(a => a.getAttribute("data-rail"))).toEqual(
            ["home", "leasing", "collection", "accounting", "operations", "settings", "more"]);
    });

    it("shows an accountant four sections", () => {
        role.current = "ACCOUNTANT";
        renderShell();
        expect(Array.from(screen.getByTestId("nav-rail").querySelectorAll("[data-rail]")).map(a => a.getAttribute("data-rail")))
            .toEqual(["home", "leasing", "collection", "accounting"]);
    });

    it("hides More when none of its pages is open to the role", () => {
        role.current = "ACCOUNTANT";
        renderShell();
        expect(screen.queryByTestId("rail-more")).toBeNull();
    });

    it("keeps Gate pass for a property manager with every flag off (role-gated, as before)", () => {
        role.current = "PROPERTY_MANAGER";
        flags.current = [];
        path.current = "/en/dashboard/gatepass";
        renderShell();
        expect(screen.getByTestId("rail-more")).toBeInTheDocument();
        expect(within(screen.getByTestId("nav-panel")).getByTestId("sidebar-gatepass")).toHaveAttribute("href", "/dashboard/gatepass");
    });

    it("badges Collection with the actionable count", () => {
        renderShell();
        expect(within(screen.getByTestId("rail-collection")).getByText("7")).toBeInTheDocument();
    });

    it("has no Help link — Help lives in the header", () => {
        const { container } = renderShell();
        expect(container.querySelector('a[href="/dashboard/help"]')).toBeNull();
    });
});

describe("section panel", () => {
    it("shows the active section's pages, the org name (read-only) and the status card", () => {
        path.current = "/en/dashboard/finance/journals";
        renderShell();
        const panel = screen.getByTestId("nav-panel");
        // The switcher is the header's (one instance); the panel only names the organisation.
        expect(within(panel).getByTestId("panel-org-name")).toHaveTextContent("Acme Holdings");
        expect(within(panel).queryByTestId("org-switcher")).toBeNull();
        expect(within(panel).queryByRole("button", { name: en.TenantSwitcher.switchOrganization })).toBeNull();
        expect(within(panel).getByText(en.AccountingNav.groupJournalEntries)).toBeInTheDocument();
        expect(within(panel).getByTestId("sidebar-journals")).toHaveAttribute("aria-current", "page");
        expect(within(panel).getByTestId("nav-status-card")).toHaveTextContent("2025");
    });

    it("lights Collection, not Accounting, on a cheque page", () => {
        path.current = "/en/dashboard/finance/cheques/collection";
        renderShell();
        expect(screen.getByTestId("rail-collection")).toHaveAttribute("aria-current", "true");
        expect(screen.getByTestId("rail-accounting")).not.toHaveAttribute("aria-current");
    });

    it("keeps collapsed accounting groups in the DOM, hidden, and opens them on click", () => {
        path.current = "/en/dashboard/finance/journals";
        renderShell();
        const setup = screen.getByTestId("panel-group-items-setup");
        expect(setup).not.toBeVisible();
        fireEvent.click(screen.getByTestId("panel-group-toggle-setup"));
        expect(setup).toBeVisible();
    });
});

describe("pages that share a path", () => {
    it("lights the Settings section the URL names, in the panel and the rail", () => {
        path.current = "/en/dashboard/settings";
        search.current = "section=payments";
        renderShell();
        const panel = screen.getByTestId("nav-panel");
        expect(within(panel).getByTestId("settings-nav-payments")).toHaveAttribute("aria-current", "page");
        expect(within(panel).getByTestId("settings-nav-organisation")).not.toHaveAttribute("aria-current");
        expect(screen.getByTestId("rail-settings")).toHaveAttribute("aria-current", "true");
    });

    it("shows the short rail label for Collection and keeps the full name as its accessible name", () => {
        renderShell();
        const rail = screen.getByTestId("rail-collection");
        expect(rail).toHaveTextContent(en.Navigation.collectionShort);
        expect(rail).toHaveAttribute("aria-label", en.Navigation.collections);
    });
});

describe("RTL", () => {
    it("uses logical properties only", () => {
        const { container } = renderShell("ar");
        const physical = /(^|\s)(-?(left|right)-|m[lr]-|p[lr]-|border-[lr](\s|$|-)|rounded-[lr](\s|$|-)|text-left|text-right)/;
        const offenders = Array.from(container.querySelectorAll("[class]"))
            .map(el => el.getAttribute("class") ?? "").filter(c => physical.test(c));
        expect(offenders).toEqual([]);
    });

    it("renders Arabic labels", () => {
        path.current = "/ar/dashboard";
        renderShell("ar");
        expect(screen.getAllByText(ar.Navigation.home).length).toBeGreaterThan(0);
        expect(screen.getByText(ar.Navigation.today)).toBeInTheDocument();
    });
});

describe("phone drawer", () => {
    it("renders the drawer only while open", async () => {
        const { useNavShell } = await import("@/components/nav/NavShellContext");
        function Opener() { const { setDrawerOpen } = useNavShell(); return <button onClick={() => setDrawerOpen(true)}>open</button>; }
        render(
            <NextIntlClientProvider locale="en" messages={en}>
                <NavShellProvider><Opener /><MvpSidebar /></NavShellProvider>
            </NextIntlClientProvider>,
        );
        expect(screen.queryByTestId("nav-drawer")).toBeNull();
        fireEvent.click(screen.getByText("open"));
        expect(screen.getByTestId("nav-drawer")).toHaveAttribute("role", "dialog");
    });
});
