import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../messages/en.json";
import type { LineRow } from "@/components/leases/leaseMath";

/**
 * Scale PR B, task 4: the contract wizard (and the list that opens it) stopped
 * loading every unit and renter into the browser. The Parties step now asks the
 * server — `/units/search?status=VACANT`, `/renters/search` — and what the
 * wizard used to read off the full unit list (the property's type for the
 * rent-VAT default, its id for the grace default and the lines grid, the
 * name for the summary line) now comes from the picked `UnitOption`.
 */

vi.mock("@/i18n/routing", () => ({
    useRouter: () => ({ push: vi.fn() }),
    Link: ({ href, children }: { href: string; children: React.ReactNode }) => <a href={href}>{children}</a>,
}));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "TENANT_ADMIN" } } }) }));
vi.mock("@/components/ui/Pagination", () => ({ Pagination: () => <div data-testid="pagination" /> }));
vi.mock("@/lib/businessDate", async orig => ({
    ...(await orig<typeof import("@/lib/businessDate")>()),
    businessTodayIso: () => "2026-09-25",
}));

const api = vi.hoisted(() => ({ paged: vi.fn(), statsByLeases: vi.fn(), createDraft: vi.fn() }));
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return {
        ...m,
        chargeTypeApi: {
            ...m.chargeTypeApi,
            list: async () => [{ id: "ct-rent", code: "RENT", behaviour: "RENT", vatApplicableDefault: false, active: true }],
        },
        leaseApi: {
            ...m.leaseApi,
            paged: api.paged,
            createDraft: api.createDraft,
            cheques: async () => [],
        },
        chequeApi: { ...m.chequeApi, statsByLeases: api.statsByLeases },
    };
});
/** The grid reduced to its property id and one button that fills a valid rent line. */
vi.mock("@/components/leases/LeaseLinesGrid", () => ({
    default: ({ lines, onChange, propertyId }: { lines: LineRow[]; onChange: (r: LineRow[]) => void; propertyId: string | null }) => (
        <div>
            <span data-testid="grid-property">{propertyId}</span>
            <button onClick={() => onChange(lines.map(l => ({ ...l, chargeTypeId: "ct-rent", grossAmount: 51000 })))}>fill-rent</button>
        </div>
    ),
}));

import LeasesPage from "../page";
import LeaseWizard from "../LeaseWizard";

const SHOP = {
    id: "u-shop", unitNumber: "G-02", propertyId: "p-souk", propertyName: "Souk Plaza", propertyType: "COMMERCIAL",
    buildingId: null, buildingName: null, status: "VACANT",
};
const RENTER = { id: "r1", nameEn: "Ali Hassan", nameAr: "علي حسن", phone: null, email: "ali@example.com" };

/** Every URL the page and wizard fetched. */
const urls = () => (global.fetch as unknown as ReturnType<typeof vi.fn>).mock.calls.map(c => String(c[0]));
/** The two unbounded reads this task removed: exactly `/units` or `/renters`, with or without a query. */
const UNBOUNDED = /\/api\/proxy\/v1\/(units|renters)(\?|$)/;

let unitRows: unknown[] = [SHOP];

beforeEach(() => {
    window.history.replaceState(null, "", "/en/dashboard/leases");
    unitRows = [SHOP];
    api.paged.mockResolvedValue({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 25 });
    api.statsByLeases.mockResolvedValue([]);
    api.createDraft.mockResolvedValue({ id: "l1", lines: [], propertyId: "p-souk" });
    global.fetch = vi.fn(async (u: RequestInfo | URL) => {
        const url = String(u);
        const body = url.includes("/units/search") ? unitRows
            : url.includes("/renters/search") ? [RENTER]
            : url.includes("/rent-settings/p-souk") ? { gracePeriodDays: 7 }
            : [];
        return { ok: true, status: 200, json: async () => body } as Response;
    }) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

function renderWizard() {
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <LeaseWizard open onClose={() => {}} onCreated={() => {}} />
        </NextIntlClientProvider>,
    );
}

async function pickParties() {
    fireEvent.click(screen.getByTestId("wizard-unit"));
    fireEvent.click(await screen.findByText("G-02"));
    fireEvent.click(screen.getByTestId("wizard-renter"));
    fireEvent.click(await screen.findByText("Ali Hassan (علي حسن)"));
}

describe("leases list: no unbounded unit/renter reads", () => {
    it("never calls /api/proxy/v1/units or /api/proxy/v1/renters", async () => {
        render(
            <NextIntlClientProvider locale="en" messages={en}>
                <LeasesPage />
            </NextIntlClientProvider>,
        );
        await waitFor(() => expect(api.paged).toHaveBeenCalled());
        await waitFor(() => expect(urls().length).toBeGreaterThan(0));
        expect(urls().filter(u => UNBOUNDED.test(u))).toEqual([]);
    });
});

describe("lease wizard: server-backed Unit and Renter pickers", () => {
    it("searches VACANT units and renters on the server, never the full lists", async () => {
        renderWizard();
        await pickParties();
        expect(urls().some(u => u.includes("/units/search") && u.includes("status=VACANT"))).toBe(true);
        expect(urls().some(u => u.includes("/renters/search"))).toBe(true);
        expect(urls().filter(u => UNBOUNDED.test(u))).toEqual([]);
    });

    it("reads the property's type, id and name off the picked unit", async () => {
        renderWizard();
        await pickParties();
        expect(screen.getByTestId("wizard-unit")).toHaveTextContent("G-02");
        expect(screen.getByTestId("wizard-renter")).toHaveTextContent("Ali Hassan (علي حسن)");
        expect(screen.getByTestId("wizard-unit-property")).toHaveTextContent("Souk Plaza • COMMERCIAL");

        fireEvent.click(screen.getByTestId("wizard-next"));
        await waitFor(() => expect(screen.getByTestId("wizard-rent-vat")).toBeInTheDocument());
        // A commercial unit's rent carries VAT by default.
        expect((screen.getByTestId("wizard-rent-vat") as HTMLInputElement).checked).toBe(true);
        // The grace default is the picked unit's property's.
        await waitFor(() => expect((screen.getByTestId("grace-days-input") as HTMLInputElement).placeholder).toBe("Property default: 7 days"));

        fireEvent.change(screen.getByTestId("wizard-start-date"), { target: { value: "2026-10-01" } });
        fireEvent.change(screen.getByTestId("wizard-end-date"), { target: { value: "2027-09-30" } });
        fireEvent.click(screen.getByTestId("wizard-next"));
        expect(await screen.findByTestId("grid-property")).toHaveTextContent("p-souk");

        fireEvent.click(screen.getByText("fill-rent"));
        fireEvent.click(screen.getByTestId("wizard-next"));
        await waitFor(() => expect(api.createDraft).toHaveBeenCalledTimes(1));
        expect(api.createDraft.mock.calls[0][0]).toMatchObject({ unitId: "u-shop", renterId: "r1", rentVatApplicable: true });
    });

    it("still shows the unit number on Parties once the unit is no longer VACANT", async () => {
        renderWizard();
        await pickParties();
        fireEvent.click(screen.getByTestId("wizard-next"));
        await waitFor(() => expect(screen.getByTestId("wizard-start-date")).toBeInTheDocument());

        // The unit is now OCCUPIED: a VACANT search no longer returns it.
        unitRows = [];
        fireEvent.click(screen.getByText("Back"));
        await waitFor(() => expect(screen.getByTestId("wizard-unit")).toBeInTheDocument());
        expect(screen.getByTestId("wizard-unit")).toHaveTextContent("G-02");
        expect(screen.getByTestId("wizard-unit-property")).toHaveTextContent("Souk Plaza • COMMERCIAL");
        expect(urls().filter(u => UNBOUNDED.test(u))).toEqual([]);
    });
});
