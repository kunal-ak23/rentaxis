import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";

import ar from "../../../../messages/ar.json";
import en from "../../../../messages/en.json";

/**
 * The dashboard shell must actually localize.
 *
 * MvpSidebar and TenantSwitcher render on every dashboard page for every role.
 * Their labels were plain English literals, so an Arabic user saw English
 * primary navigation and an English organization switcher on every screen —
 * inside an RTL layout.
 *
 * These tests deliberately use the REAL NextIntlClientProvider and the REAL
 * ar.json rather than mocking next-intl. A mocked `t` that echoes its key
 * passes whether or not the component is wired up, and would also pass if the
 * Arabic catalog were missing the strings entirely — which is exactly the pair
 * of failures worth catching.
 */

const path = { current: "/en/dashboard" };

vi.mock("next/navigation", () => ({
    usePathname: () => path.current,
    useRouter: () => ({ push: vi.fn(), refresh: vi.fn() }),
}));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
    useRouter: () => ({ push: vi.fn() }),
}));
// tenantId matters: TenantSwitcher returns null outright without a tenant
// context (or SUPER_ADMIN), so a session without it renders nothing at all.
vi.mock("next-auth/react", () => ({
    useSession: () => ({
        data: { user: { role: "TENANT_ADMIN", name: "Admin", tenantId: "tenant-1" } },
    }),
}));
vi.mock("@/hooks/useTenantFeatures", () => ({
    useTenantFeatures: () => ({
        isEnabled: () => true,
        features: {},
        loading: false,
    }),
}));
vi.mock("@/components/nav/useNavCounts", () => ({
    useNavCounts: () => ({ collectionBadge: null, chequesToDeposit: null, booksLockedThrough: null, booksLive: true }),
}));
vi.mock("framer-motion", () => ({
    motion: new Proxy({}, {
        get: () => ({ children, ...rest }: { children?: React.ReactNode }) => <div {...rest}>{children}</div>,
    }),
    AnimatePresence: ({ children }: { children?: React.ReactNode }) => <>{children}</>,
}));
vi.mock("next/image", () => ({
    default: ({ alt }: { alt: string }) => <img alt={alt} />,
}));

import MvpSidebar from "../MvpSidebar";
import { NavShellProvider } from "@/components/nav/NavShellContext";
import { TenantSwitcher } from "../TenantSwitcher";

function renderIn(locale: "en" | "ar", ui: React.ReactElement) {
    return render(
        <NextIntlClientProvider locale={locale} messages={locale === "ar" ? ar : en}>
            <NavShellProvider>{ui}</NavShellProvider>
        </NextIntlClientProvider>,
    );
}

beforeEach(() => {
    path.current = "/en/dashboard";
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => [] })) as unknown as typeof fetch;
    Object.defineProperty(window, "localStorage", {
        value: { getItem: () => null, setItem: () => {}, removeItem: () => {} },
        writable: true,
    });
});

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

describe("dashboard shell localization", () => {
    it("renders sidebar nav labels in Arabic", () => {
        // Each label lives in its section's panel, which shows for the page you are on.
        path.current = "/ar/dashboard/tickets";
        const tickets = renderIn("ar", <MvpSidebar />);
        expect(screen.getByText(ar.Navigation.tickets)).toBeInTheDocument();
        tickets.unmount();
        path.current = "/ar/dashboard/meetings";
        const meetings = renderIn("ar", <MvpSidebar />);
        expect(screen.getByText(ar.Navigation.meetings)).toBeInTheDocument();
        meetings.unmount();
        path.current = "/ar/dashboard/listings";
        renderIn("ar", <MvpSidebar />);
        expect(screen.getByText(ar.Navigation.enquiry)).toBeInTheDocument();
    });

    it("leaves no English nav literal on an Arabic page", () => {
        for (const p of ["/ar/dashboard", "/ar/dashboard/tickets", "/ar/dashboard/meetings", "/ar/dashboard/listings", "/ar/dashboard/finance/journals", "/ar/dashboard/settings"]) {
            path.current = p;
            const { container, unmount } = renderIn("ar", <MvpSidebar />);
            const text = container.textContent ?? "";
            // The exact literals that used to be hardcoded.
            for (const literal of ["Tickets", "Meetings", "Listings", "Account Mappings", "Cheque-failure Fines"]) {
                expect(text, `"${literal}" should not appear on ${p}`).not.toContain(literal);
            }
            unmount();
        }
    });

    it("still renders English nav labels in the English locale", () => {
        path.current = "/en/dashboard/tickets";
        const tickets = renderIn("en", <MvpSidebar />);
        expect(screen.getByText("Tickets")).toBeInTheDocument();
        tickets.unmount();
        path.current = "/en/dashboard/meetings";
        renderIn("en", <MvpSidebar />);
        expect(screen.getByText("Meetings")).toBeInTheDocument();
    });

    it("localizes the tenant switcher, including its accessible name", async () => {
        renderIn("ar", <TenantSwitcher isCollapsed={false} />);

        await waitFor(() =>
            expect(screen.getByText(ar.TenantSwitcher.organization)).toBeInTheDocument(),
        );
        // The switcher's only accessible name came from a hardcoded English
        // aria-label, so a screen reader in Arabic was read English.
        expect(
            screen.queryByLabelText("Switch organization")
            ?? screen.queryByLabelText("Current organization"),
        ).toBeNull();
    });

    it("keeps the two catalogs at full key parity for the new namespaces", () => {
        for (const ns of ["Navigation", "TenantSwitcher"] as const) {
            expect(Object.keys(ar[ns]).sort()).toEqual(Object.keys(en[ns]).sort());
            // An untranslated Arabic value is a silent regression: the key is
            // present, parity checks pass, and the UI still shows English.
            for (const [key, value] of Object.entries(ar[ns])) {
                expect(value, `ar.${ns}.${key} should not be the English string`)
                    .not.toBe((en[ns] as Record<string, string>)[key]);
            }
        }
    });
});
