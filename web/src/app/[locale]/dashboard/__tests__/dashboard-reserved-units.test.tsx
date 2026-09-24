import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";

import en from "../../../../../messages/en.json";

vi.mock("next-auth/react", () => ({
    useSession: () => ({ data: { user: { name: "Tutorial Admin", role: "TENANT_ADMIN" } } }),
}));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
}));
vi.mock("@/components/dashboard/FollowUpsWidget", () => ({ default: () => null }));
vi.mock("@/components/dashboard/OverduePaymentsWidget", () => ({ default: () => null }));
vi.mock("@/components/dashboard/ChequesToDepositWidget", () => ({ default: () => null }));

import DashboardPage from "../page";

/**
 * F14-01a: occupancy is now derived by date, so a unit whose next lease has
 * not started yet is RESERVED, not OCCUPIED — the dashboard must surface that
 * count instead of folding it silently into vacant or occupied.
 */
const BASE_SUMMARY = {
    totalProperties: 1,
    totalUnits: 10,
    occupiedUnits: 6,
    reservedUnits: 2,
    vacantUnits: 2,
    occupancyRate: 60,
    activeLeases: 8,
    draftLeases: 0,
    expiringLeases: 0,
    overdueAmount: 0,
    collectedAmount: 0,
    pendingThisMonthAmount: 0,
    totalRentRevenue: 0,
    dueThisMonth: 0,
    collectedAgainstDueThisMonth: 0,
    collectedArrears: 0,
    collectedAdvance: 0,
    recentActivity: [],
};

let summary: Record<string, unknown> = BASE_SUMMARY;

beforeEach(() => {
    summary = BASE_SUMMARY;
    global.fetch = vi.fn(async (url: unknown) => {
        const u = String(url);
        if (u.includes("monthly")) return { ok: true, json: async () => [] } as Response;
        return { ok: true, json: async () => summary } as Response;
    }) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

function renderEn() {
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <DashboardPage />
        </NextIntlClientProvider>,
    );
}

describe("dashboard reserved units (F14-01a)", () => {
    it("shows a reserved sub-line on the Occupancy tile", async () => {
        renderEn();
        await waitFor(() => expect(screen.getByText("2 units reserved")).toBeInTheDocument());
    });

    it("lists the reserved count in the portfolio snapshot", async () => {
        renderEn();
        await waitFor(() => expect(screen.getByText("Reserved units")).toBeInTheDocument());
        const label = screen.getByText("Reserved units");
        const row = label.closest("div.flex") as HTMLElement;
        expect(row.textContent).toContain("2");
    });

    it("omits the reserved sub-line when nothing is reserved", async () => {
        summary = { ...BASE_SUMMARY, reservedUnits: 0, vacantUnits: 4 };
        renderEn();
        await waitFor(() => expect(screen.getByText("Occupancy")).toBeInTheDocument());
        expect(screen.queryByText(/units reserved/)).toBeNull();
    });
});
