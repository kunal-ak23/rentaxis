import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>,
}));
vi.mock("@/lib/businessDate", () => ({ businessTodayIso: () => "2026-09-25" }));
import UnitStatusBoard from "../UnitStatusBoard";

const unit = (id: string, unitNumber: string, occupancy: string, building: string | null = null) =>
    ({ id, unitNumber, occupancy, status: occupancy === "MAINTENANCE" ? "MAINTENANCE" : "VACANT", building: building ? { id: building } : null, type: "BHK1" });
const PAGE0 = [unit("u1", "A-101", "OCCUPIED", "bA"), unit("u2", "A-102", "VACANT", "bA"), unit("u3", "A-201", "OCCUPIED", "bA")];
const PAGE1 = [unit("u4", "B-101", "MAINTENANCE", "bB"), unit("u5", "B-102", "RESERVED", "bB")];
const LEASES = [
    { id: "l1", unitId: "u1", startDate: "2026-01-01", endDate: "2026-12-31", status: "ACTIVE", renterName: "Long Stay" },
    { id: "l3", unitId: "u3", startDate: "2025-11-01", endDate: "2026-10-31", status: "ACTIVE", renterName: "Leaving Soon" },
];
let calls: string[] = [];

beforeEach(() => {
    calls = [];
    global.fetch = vi.fn(async (u: RequestInfo | URL) => {
        const url = String(u);
        calls.push(url);
        const json = (body: unknown) => ({ ok: true, status: 200, json: async () => body });
        if (url.endsWith("/v1/properties")) return json([{ property: { id: "p1", nameEn: "Marina Tower", nameAr: "برج المارينا" } }]);
        if (url.includes("/units/paged")) {
            const page = Number(new URL(url, "http://x").searchParams.get("page"));
            return json({ content: page === 0 ? PAGE0 : PAGE1, totalPages: 2, totalElements: 5 });
        }
        if (url.includes("/leases/paged")) {
            const status = new URL(url, "http://x").searchParams.get("status");
            return json({ content: status === "ACTIVE" ? LEASES : [], totalPages: 1, totalElements: status === "ACTIVE" ? 2 : 0 });
        }
        if (url.includes("/buildings/property/p1")) return json([{ id: "bA", nameEn: "Tower A" }, { id: "bB", nameEn: "Tower B" }]);
        return json([]);
    }) as unknown as typeof fetch;
});
afterEach(cleanup);

const renderBoard = () => render(<NextIntlClientProvider locale="en" messages={en}><UnitStatusBoard /></NextIntlClientProvider>);

describe("Unit status board", () => {
    it("reads one property a page at a time and never the organisation's whole unit list", async () => {
        renderBoard();
        await screen.findByTestId("unit-tile-u5");
        const unitCalls = calls.filter(u => u.includes("/units"));
        expect(unitCalls).toHaveLength(2);
        expect(unitCalls.every(u => u.includes("/units/paged?propertyId=p1"))).toBe(true);
        expect(calls.some(u => /\/v1\/units($|\?)/.test(u))).toBe(false);
        expect(calls.filter(u => u.includes("/leases/paged")).every(u => u.includes("propertyId=p1"))).toBe(true);
    });

    it("groups by building and floor and colours each unit by status", async () => {
        renderBoard();
        await screen.findByTestId("unit-tile-u5");
        const towerA = screen.getByTestId("unit-board-building-bA");
        expect(towerA).toHaveTextContent("Tower A");
        expect(within(towerA).getByTestId("unit-board-floor-1")).toHaveTextContent("A-101");
        expect(within(towerA).getByTestId("unit-board-floor-2")).toHaveTextContent("A-201");
        expect(screen.getByTestId("unit-tile-u1")).toHaveAttribute("data-status", "OCCUPIED");
        expect(screen.getByTestId("unit-tile-u2")).toHaveAttribute("data-status", "VACANT");
        expect(screen.getByTestId("unit-tile-u3")).toHaveAttribute("data-status", "EXPIRING");
        expect(screen.getByTestId("unit-tile-u4")).toHaveAttribute("data-status", "MAINTENANCE");
        expect(screen.getByTestId("unit-tile-u5")).toHaveAttribute("data-status", "RESERVED");
        expect(screen.getByTestId("unit-board-filter-expiring")).toHaveTextContent("1");
    });

    it("filters by a legend status", async () => {
        renderBoard();
        await screen.findByTestId("unit-tile-u5");
        fireEvent.click(screen.getByTestId("unit-board-filter-vacant"));
        expect(screen.getByTestId("unit-board-filter-vacant")).toHaveAttribute("aria-pressed", "true");
        expect(screen.getByTestId("unit-tile-u2")).toBeInTheDocument();
        expect(screen.queryByTestId("unit-tile-u1")).toBeNull();
        expect(screen.queryByTestId("unit-board-building-bB")?.querySelector("[data-testid^='unit-tile']") ?? null).toBeNull();
    });

    it("opens the unit and its contract in a side panel", async () => {
        renderBoard();
        fireEvent.click(await screen.findByTestId("unit-tile-u3"));
        const panel = await screen.findByTestId("unit-board-drawer");
        expect(panel).toHaveTextContent("Unit A-201");
        expect(within(panel).getByTestId("unit-board-panel-status")).toHaveTextContent("Expiring");
        expect(within(panel).getByTestId("unit-board-panel-lease")).toHaveTextContent("Leaving Soon");
        expect(within(panel).getByTestId("unit-board-open-contract")).toHaveAttribute("href", "/dashboard/leases/l3");
        expect(within(panel).getByTestId("unit-board-open-units")).toHaveAttribute("href", "/dashboard/properties/p1/units");
    });
});
