import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("next-auth/react", () => ({
    useSession: () => ({ data: { user: { name: "Admin" } } }),
}));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
}));
// Child widgets fetch on their own — stub them out so this test only covers the charts.
vi.mock("@/components/dashboard/FollowUpsWidget", () => ({ default: () => null }));
vi.mock("@/components/dashboard/OverduePaymentsWidget", () => ({ default: () => null }));
vi.mock("@/components/dashboard/ChequesToDepositWidget", () => ({ default: () => null }));

import DashboardPage from "../page";

const SUMMARY = {
    totalProperties: 1, totalUnits: 10, occupiedUnits: 8, vacantUnits: 2, occupancyRate: 80,
    activeLeases: 8, draftLeases: 1, expiringLeases: 0, totalRentRevenue: 100000,
    collectedAmount: 50000, pendingAmount: 20000, overdueAmount: 12000, recentActivity: [],
};

const MONTHLY = Array.from({ length: 12 }, (_, i) => ({
    month: ["Jul", "Aug", "Sep", "Oct", "Nov", "Dec", "Jan", "Feb", "Mar", "Apr", "May", "Jun"][i],
    ym: `2025-${String(i + 1).padStart(2, "0")}`,
    expected: 10000,
    collected: 8000 + i * 100,
}));

let monthlyFetched = false;

beforeEach(() => {
    monthlyFetched = false;
    global.fetch = vi.fn(async (url: unknown) => {
        const u = String(url);
        if (u.includes("/dashboard/monthly-collections")) {
            monthlyFetched = true;
            return { ok: true, json: async () => MONTHLY } as Response;
        }
        return { ok: true, json: async () => SUMMARY } as Response;
    }) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

describe("DashboardPage charts use real data", () => {
    it("fetches the monthly series and renders the chart (not the empty state)", async () => {
        render(<DashboardPage />);
        await waitFor(() => expect(screen.getByText("12-month performance")).toBeTruthy());
        expect(monthlyFetched).toBe(true);
        expect(screen.queryByText(/no collection data yet/i)).toBeNull();
    });

    it("renders the occupancy donut with real occupied/vacant counts", async () => {
        render(<DashboardPage />);
        await waitFor(() => expect(screen.getByText("12-month performance")).toBeTruthy());
        expect(screen.getByText("Occupied")).toBeTruthy();
        expect(screen.getByText("Vacant")).toBeTruthy(); // legend label (distinct from "Vacant units" totals row)
        expect(screen.getByText("80%")).toBeTruthy();
    });

    it("does not render the old hardcoded mock deltas", async () => {
        render(<DashboardPage />);
        await waitFor(() => expect(screen.getByText("12-month performance")).toBeTruthy());
        // Previously hardcoded fake deltas on the stat cards — must be gone.
        expect(screen.queryByText("+12.4%")).toBeNull();
        expect(screen.queryByText("+3.0%")).toBeNull();
        expect(screen.queryByText("+1.2%")).toBeNull();
        expect(screen.queryByText("-2.1%")).toBeNull();
    });
});
