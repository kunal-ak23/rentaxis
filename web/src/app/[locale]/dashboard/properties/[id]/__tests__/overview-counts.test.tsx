import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../messages/en.json";
import ar from "../../../../../../../messages/ar.json";

/**
 * Demo feedback 2026-09-29: the property Overview shows unit counts (total,
 * occupied, vacant, reserved, under maintenance) and parking counts (total,
 * assigned, free) — not only the property manager and key contacts.
 */

let role = "TENANT_ADMIN";
vi.mock("next/navigation", () => ({
    useParams: () => ({ id: "p1" }),
    useRouter: () => ({ push: vi.fn(), refresh: vi.fn() }),
    usePathname: () => "/en/dashboard/properties/p1",
    useSearchParams: () => new URLSearchParams(),
}));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>,
    useRouter: () => ({ push: vi.fn() }),
}));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role, name: "U", tenantId: "t1" } } }) }));

import PropertyDetailPage from "../page";
import { unitCounts } from "../_components/OverviewCounts";

const UNITS = [
    { id: "u1", unitNumber: "101", status: "OCCUPIED", occupancy: "OCCUPIED" },
    { id: "u2", unitNumber: "102", status: "OCCUPIED", occupancy: "OCCUPIED" },
    { id: "u3", unitNumber: "103", status: "VACANT", occupancy: "VACANT" },
    // A contract posted for next month: stored VACANT, reserved by date.
    { id: "u4", unitNumber: "104", status: "VACANT", occupancy: "RESERVED" },
    { id: "u5", unitNumber: "105", status: "MAINTENANCE", occupancy: "MAINTENANCE" },
    { id: "u6", unitNumber: "106", status: "VACANT" },
];

const calls: string[] = [];
function mockApi(parking: { total: number; assigned: number; free: number; inactive: number } | null) {
    calls.length = 0;
    global.fetch = vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        calls.push(url);
        const ok = (body: unknown) => ({ ok: true, status: 200, json: async () => body }) as unknown as Response;
        if (url.endsWith("/v1/properties/p1")) return ok({ id: "p1", nameEn: "Desert Rose Gardens", emirate: "DUBAI", type: "RESIDENTIAL" });
        if (url.endsWith("/v1/units/property/p1")) return ok(UNITS);
        if (url.includes("/v1/parking-spots/summary")) {
            return parking ? ok(parking) : ({ ok: false, status: 500, json: async () => ({}) } as unknown as Response);
        }
        return ok([]);
    }) as unknown as typeof fetch;
}

const renderPage = (locale: "en" | "ar" = "en") =>
    render(<NextIntlClientProvider locale={locale} messages={locale === "en" ? en : ar}><PropertyDetailPage /></NextIntlClientProvider>);

afterEach(() => { cleanup(); vi.restoreAllMocks(); role = "TENANT_ADMIN"; });

describe("property Overview — unit and parking counts", () => {
    it("counts units by date-based occupancy, falling back to the stored status", () => {
        expect(unitCounts(UNITS)).toEqual({ total: 6, occupied: 2, vacant: 2, reserved: 1, maintenance: 1 });
        expect(unitCounts([])).toEqual({ total: 0, occupied: 0, vacant: 0, reserved: 0, maintenance: 0 });
    });

    it("shows the unit tiles and the parking tiles on the Overview tab", async () => {
        mockApi({ total: 12, assigned: 7, free: 5, inactive: 2 });
        renderPage();
        expect(await screen.findByTestId("property-overview-counts")).toBeInTheDocument();
        expect(await screen.findByText("Total units")).toBeInTheDocument();
        await screen.findByTestId("overview-parking");
        expect(screen.getByTestId("overview-units-total")).toHaveTextContent("6");
        expect(screen.getByTestId("overview-units-occupied")).toHaveTextContent("2");
        expect(screen.getByTestId("overview-units-vacant")).toHaveTextContent("2");
        expect(screen.getByTestId("overview-units-reserved")).toHaveTextContent("1");
        expect(screen.getByTestId("overview-units-maintenance")).toHaveTextContent("1");
        expect(screen.getByTestId("overview-parking-total")).toHaveTextContent("12");
        expect(screen.getByTestId("overview-parking-assigned")).toHaveTextContent("7");
        expect(screen.getByTestId("overview-parking-free")).toHaveTextContent("5");
        expect(screen.getByTestId("overview-parking-inactive")).toHaveTextContent("2 inactive spots not counted");
        // One summary call, scoped to this property — not a page-by-page walk of the spot list.
        expect(calls.filter(u => u.includes("parking-spots"))).toEqual(["/api/proxy/v1/parking-spots/summary?propertyId=p1"]);
    });

    it("leaves the parking card out when the summary cannot be read", async () => {
        mockApi(null);
        renderPage();
        await screen.findByText("Total units");
        await vi.waitFor(() => expect(calls.some(u => u.includes("parking-spots/summary"))).toBe(true));
        expect(screen.queryByTestId("overview-parking")).not.toBeInTheDocument();
    });

    it("does not ask for parking for a role that cannot read it", async () => {
        role = "ACCOUNTANT";
        mockApi({ total: 1, assigned: 0, free: 1, inactive: 0 });
        renderPage();
        await screen.findByText("Total units");
        expect(calls.some(u => u.includes("parking-spots"))).toBe(false);
        expect(screen.queryByTestId("overview-parking")).not.toBeInTheDocument();
    });

    it("labels the tiles in Arabic", async () => {
        mockApi({ total: 3, assigned: 1, free: 2, inactive: 0 });
        renderPage("ar");
        expect(await screen.findByText("إجمالي الوحدات")).toBeInTheDocument();
        expect(await screen.findByText("إجمالي المواقف")).toBeInTheDocument();
        expect(screen.getByText("مخصصة")).toBeInTheDocument();
    });
});
