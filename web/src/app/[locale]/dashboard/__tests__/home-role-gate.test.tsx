import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";

import en from "../../../../../messages/en.json";
import ar from "../../../../../messages/ar.json";

/**
 * Sandbox role test (2026-10-15) M4: the Company User (TENANT_USER) and the
 * accountant opened Home to "Unable to load dashboard data", because
 * GET /dashboard/summary and /monthly-collections answered 403.
 *
 * The accountant is now admitted on the server (DashboardControllerRoleIT), so
 * its Home loads the figures like any other finance role. TENANT_USER is still
 * refused — it has no business with the organisation's money — so its Home asks
 * for nothing and shows a greeting and the way to Help instead of an error.
 */

const session = vi.hoisted(() => ({ role: "TENANT_USER" as string | undefined, status: "authenticated" }));

vi.mock("next-auth/react", () => ({
    useSession: () => ({
        data: session.status === "loading" ? null : { user: { name: "Noor Staff", role: session.role } },
        status: session.status,
    }),
}));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
}));
vi.mock("@/components/dashboard/TodayList", () => ({ default: () => null }));
vi.mock("@/components/dashboard/UnitStatusBoard", () => ({ default: () => null }));

import DashboardPage from "../page";

const SUMMARY = {
    totalProperties: 1, totalUnits: 8, occupiedUnits: 3, vacantUnits: 5, occupancyRate: 37.5,
    activeLeases: 3, draftLeases: 1, expiringLeases: 0, overdueAmount: 0, collectedAmount: 0,
    pendingThisMonthAmount: 0, totalRentRevenue: 0, receivedThisMonth: 0, receivedLastMonth: 0,
    dueThisMonth: 0, collectedForThisMonth: 0, collectedAgainstDueThisMonth: 0, collectedArrears: 0,
    collectedAdvance: 0, recentActivity: [],
};

const fetchMock = vi.fn(async (url: unknown) => {
    const u = String(url);
    if (u.includes("/dashboard/summary")) return { ok: true, status: 200, json: async () => SUMMARY };
    if (u.includes("/monthly-collections")) return { ok: true, status: 200, json: async () => [] };
    return { ok: true, status: 200, json: async () => ({ content: [], totalElements: 0 }) };
});
const dashboardCalls = () => fetchMock.mock.calls.map(c => String(c[0])).filter(u => u.includes("/dashboard/"));

function renderPage(messages: typeof en = en, locale = "en") {
    return render(
        <NextIntlClientProvider locale={locale} messages={messages}>
            <DashboardPage />
        </NextIntlClientProvider>,
    );
}

beforeEach(() => {
    session.role = "TENANT_USER";
    session.status = "authenticated";
    fetchMock.mockClear();
    global.fetch = fetchMock as unknown as typeof fetch;
});

afterEach(() => cleanup());

describe("Home — roles without the summary (sandbox role test M4)", () => {
    it("shows a Company User a Home with Help, not an error, and asks the server for nothing", async () => {
        renderPage();
        expect(await screen.findByTestId("home-limited")).toBeInTheDocument();
        expect(screen.getByText(en.Dashboard.limitedHomeTitle)).toBeInTheDocument();
        expect(screen.getByTestId("home-limited-help")).toHaveAttribute("href", "/dashboard/help");
        expect(screen.queryByText(en.Dashboard.unableToLoad)).toBeNull();
        expect(dashboardCalls()).toEqual([]);
        // The Company User welcome tour's first step points here.
        expect(screen.getByTestId("home-limited").querySelector('[data-tour="dashboard-header"]')).not.toBeNull();
    });

    it("renders the limited Home in Arabic", async () => {
        renderPage(ar as unknown as typeof en, "ar");
        expect(await screen.findByText(ar.Dashboard.limitedHomeTitle)).toBeInTheDocument();
        expect(screen.getByText(ar.Dashboard.limitedHomeHelp)).toBeInTheDocument();
    });

    it("does not fetch while the session is still loading", async () => {
        session.status = "loading";
        renderPage();
        await new Promise(r => setTimeout(r, 20));
        expect(dashboardCalls()).toEqual([]);
    });

    it.each(["ACCOUNTANT", "PROPERTY_MANAGER", "TENANT_ADMIN"])("loads the summary for %s", async r => {
        session.role = r;
        renderPage();
        await waitFor(() => expect(dashboardCalls()).toEqual(
            expect.arrayContaining(["/api/proxy/v1/dashboard/summary", "/api/proxy/v1/dashboard/monthly-collections"])));
        expect(await screen.findByTestId("kpi-overdue")).toBeInTheDocument();
        expect(screen.queryByTestId("home-limited")).toBeNull();
    });
});
