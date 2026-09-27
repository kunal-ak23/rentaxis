import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../messages/en.json";

/**
 * Scale PR B, task 5: the create-ticket form's "on behalf of" renter and unit
 * fields stopped loading every renter/unit into the browser. They now ask the
 * server — `/renters/search`, `/units/search?propertyId=` — through the same
 * `RenterPicker`/`UnitPicker` the leases pages moved to in task 4.
 */

vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "TENANT_ADMIN", id: "me-1" } } }) }));
vi.mock("next/navigation", () => ({ useSearchParams: () => new URLSearchParams() }));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
}));

const lookup = vi.hoisted(() => ({ searchUnits: vi.fn(), searchRenters: vi.fn(), unitNames: vi.fn(), renterNames: vi.fn() }));
vi.mock("@/lib/api/lookup", () => ({ lookupApi: lookup }));

import TicketsPage from "../page";

/** The two unbounded reads this task removed: exactly `/units` or `/renters`, with or without a query. */
const UNBOUNDED = /\/api\/proxy\/v1\/(units|renters)(\?|$)/;
const urls = () => (global.fetch as unknown as ReturnType<typeof vi.fn>).mock.calls.map(c => String(c[0]));

const pagedBody = (content: unknown[]) => ({ content, totalElements: content.length, totalPages: 1, number: 0, size: 25 });

const SHOP_UNIT = { id: "u-shop", unitNumber: "G-02", propertyId: "p1", propertyName: "Tower A", propertyType: null, buildingId: null, buildingName: null, status: null };
const RENTER = { id: "ren-1", nameEn: "Rajesh Kumar", nameAr: null, phone: "+971501234567", email: null };

beforeEach(() => {
    lookup.searchUnits.mockResolvedValue([SHOP_UNIT]);
    lookup.unitNames.mockResolvedValue([]);
    lookup.searchRenters.mockResolvedValue([RENTER]);
    lookup.renterNames.mockResolvedValue([]);
    global.fetch = vi.fn(async (url: unknown) => {
        const u = String(url);
        if (u.includes("/v1/tickets/paged")) return { ok: true, status: 200, json: async () => pagedBody([]) } as unknown as Response;
        if (u.endsWith("/v1/properties")) return { ok: true, status: 200, json: async () => [{ property: { id: "p1", nameEn: "Tower A" } }] } as unknown as Response;
        return { ok: true, status: 200, json: async () => [] } as unknown as Response;
    }) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

function renderPage() {
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <TicketsPage />
        </NextIntlClientProvider>,
    );
}

describe("Tickets list: no unbounded unit/renter reads", () => {
    it("never calls /api/proxy/v1/units or /api/proxy/v1/renters on load", async () => {
        renderPage();
        await waitFor(() => expect(urls().length).toBeGreaterThan(0));
        expect(urls().filter(u => UNBOUNDED.test(u))).toEqual([]);
    });

    it("searches units and renters on the server when the create form is opened", async () => {
        renderPage();
        fireEvent.click(await screen.findByText("Create Ticket"));
        fireEvent.change(screen.getByDisplayValue("Select property"), { target: { value: "p1" } });

        fireEvent.click(screen.getByTestId("ticket-unit-picker"));
        await screen.findByText("G-02");
        expect(lookup.searchUnits).toHaveBeenCalledWith(expect.objectContaining({ propertyId: "p1" }));

        const behalfOf = screen.getByLabelText(en.Tickets.onBehalfOfRenter);
        fireEvent.click(behalfOf);
        await screen.findByText(/Rajesh Kumar/);
        expect(lookup.searchRenters).toHaveBeenCalled();

        expect(urls().filter(u => UNBOUNDED.test(u))).toEqual([]);
    });

    it("picks a unit for the new ticket, by id, from the server search", async () => {
        renderPage();
        fireEvent.click(await screen.findByText("Create Ticket"));
        fireEvent.change(screen.getByDisplayValue("Select property"), { target: { value: "p1" } });
        fireEvent.click(screen.getByTestId("ticket-unit-picker"));
        fireEvent.click(await screen.findByText("G-02"));
        expect(screen.getByTestId("ticket-unit-picker")).toHaveTextContent("G-02");
    });
});
