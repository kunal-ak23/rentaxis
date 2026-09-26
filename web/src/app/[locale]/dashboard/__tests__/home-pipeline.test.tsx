import { cleanup, render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../messages/en.json";

const session = vi.hoisted(() => ({ role: "TENANT_ADMIN" }));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { name: "Ops Manager", role: session.role } } }) }));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>,
}));
vi.mock("@/components/dashboard/TodayList", () => ({ default: () => <div data-testid="today-list" /> }));
vi.mock("@/components/dashboard/UnitStatusBoard", () => ({ default: () => <div data-testid="unit-board" /> }));
vi.mock("@/lib/businessDate", () => ({ businessTodayIso: () => "2026-09-25" }));
import DashboardPage from "../page";

const SUMMARY = {
    totalProperties: 2, totalUnits: 10, occupiedUnits: 7, reservedUnits: 1, vacantUnits: 2, occupancyRate: 70,
    activeLeases: 8, draftLeases: 1, expiringLeases: 9, overdueAmount: 0, collectedAmount: 0, pendingThisMonthAmount: 0,
    totalRentRevenue: 0, dueThisMonth: 0, collectedAgainstDueThisMonth: 0, collectedArrears: 0, collectedAdvance: 0, recentActivity: [],
};
const TOTALS: Record<string, number> = { DRAFT: 3, ACTIVE: 8, NOTICE_GIVEN: 1, TERMINATED: 2, EXPIRED: 1 };
let pagedCalls: string[] = [];

beforeEach(() => {
    pagedCalls = [];
    session.role = "TENANT_ADMIN";
    global.fetch = vi.fn(async (u: RequestInfo | URL) => {
        const url = String(u);
        const json = (body: unknown) => ({ ok: true, status: 200, json: async () => body }) as Response;
        if (url.includes("/leases/paged")) {
            pagedCalls.push(url);
            const q = new URL(url, "http://x").searchParams;
            const status = q.get("status") ?? "";
            const content = status === "ACTIVE" && q.get("sort") === "endDate,asc"
                ? [{ id: "e1", startDate: "2025-10-15", endDate: "2026-10-14", unitIdentifier: "A-101", renterName: "Leaving" },
                   { id: "a1", startDate: "2026-01-01", endDate: "2026-12-31", unitIdentifier: "A-102", renterName: "Staying" }]
                : status === "DRAFT" ? [{ id: "d1", startDate: "2026-10-01", endDate: "2027-09-30", unitIdentifier: "B-1", renterName: "New" }] : [];
            return json({ content, totalElements: TOTALS[status] ?? 0, totalPages: 1, number: 0, size: 1 });
        }
        if (url.includes("monthly")) return json([]);
        return json(SUMMARY);
    }) as unknown as typeof fetch;
});
afterEach(cleanup);

const renderHome = () => render(<NextIntlClientProvider locale="en" messages={en}><DashboardPage /></NextIntlClientProvider>);

describe("Home (spec §1a, §6)", () => {
    it("leads with New Contract, the contract pipeline and Needs you now, then the tiles, one chart and the unit board", async () => {
        renderHome();
        const pipeline = await screen.findByTestId("contract-pipeline");
        expect(screen.getByTestId("home-new-contract")).toHaveAttribute("href", "/dashboard/leases?new=1");
        expect(within(pipeline).getByTestId("pipeline-draft")).toHaveTextContent("3");
        expect(within(pipeline).getByTestId("pipeline-draft")).toHaveTextContent("Oldest: B-1 · New");
        expect(within(pipeline).getByTestId("pipeline-expiring")).toHaveTextContent("1");
        expect(within(pipeline).getByTestId("pipeline-settlement")).toHaveTextContent("3");
        expect(within(pipeline).getByTestId("pipeline-expiring-link")).toHaveAttribute("href", "/dashboard/leases?view=expiring");
        const order = ["contract-pipeline", "today-list", "kpi-unit-status", "unit-board", "recent-activity"]
            .map(id => screen.getByTestId(id));
        for (let i = 1; i < order.length; i++) {
            expect(order[i - 1].compareDocumentPosition(order[i]) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
        }
        // The expiring figure on Unit Status is the pipeline's, not the summary's older count.
        expect(screen.getByTestId("kpi-unit-status")).toHaveTextContent("7 occupied · 1 expiring · 2 vacant");
        expect(pagedCalls.every(u => /size=(1|50|100)\b/.test(u))).toBe(true);
    });

    it("skips the pipeline and the unit board for a role that cannot read contracts or properties", async () => {
        session.role = "TENANT_USER";
        renderHome();
        await screen.findByTestId("kpi-unit-status");
        expect(screen.queryByTestId("contract-pipeline")).toBeNull();
        expect(screen.queryByTestId("unit-board")).toBeNull();
        expect(pagedCalls).toEqual([]);
        expect(document.getElementById("unit-status")).toBe(screen.getByTestId("kpi-unit-status"));
    });
});
