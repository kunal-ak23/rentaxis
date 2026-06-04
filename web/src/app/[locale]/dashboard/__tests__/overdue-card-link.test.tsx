import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("next-auth/react", () => ({
    useSession: () => ({ data: { user: { name: "Admin User" } } }),
}));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>
            {children}
        </a>
    ),
}));
vi.mock("@/components/dashboard/FollowUpsWidget", () => ({ default: () => null }));

import DashboardPage from "../page";

beforeEach(() => {
    global.fetch = vi.fn(async () => ({
        ok: true,
        json: async () => ({
            totalProperties: 1, totalUnits: 10, occupiedUnits: 8, vacantUnits: 2, occupancyRate: 80,
            activeLeases: 8, draftLeases: 1, expiringLeases: 0, totalRentRevenue: 100000,
            collectedAmount: 50000, pendingAmount: 20000, overdueAmount: 12000, recentActivity: [],
        }),
    })) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

describe("DashboardPage overdue card", () => {
    it("links the Overdue card to the payments page filtered by overdue", async () => {
        render(<DashboardPage />);

        const link = await screen.findByRole("link", { name: /view overdue payments/i });
        expect(link).toHaveAttribute("href", "/dashboard/finance/payments?status=OVERDUE");
    });
});
