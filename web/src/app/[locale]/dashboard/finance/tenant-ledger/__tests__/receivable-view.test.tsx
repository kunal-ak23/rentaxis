// Tenant Ledger: opens on PACT's receivable view (report grid, paged by tenant groups, exports);
// "All accounts" keeps the one-tenant statement.
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
const query = { current: "" };
const renter = vi.hoisted(() => vi.fn());
const general = vi.hoisted(() => vi.fn());
const get = vi.hoisted(() => vi.fn());
const accounts = vi.hoisted(() => vi.fn());
vi.mock("next/navigation", () => ({ useSearchParams: () => new URLSearchParams(query.current) }));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "TENANT_ADMIN" } } }) }));
vi.mock("@/i18n/routing", () => ({ Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a> }));
vi.mock("@/components/finance/useNameLookup", () => ({
    useNameLookup: () => ({ options: [{ id: "p1", label: "Sample Tower" }], name: (id: string | null | undefined) => (id === "p1" ? "Sample Tower" : ""), loading: false }),
}));
const lookup = vi.hoisted(() => {
    const people = [
        { id: "r1", nameEn: "Tenant A", nameAr: null, phone: null, email: "a@example.com" },
        { id: "r2", nameEn: "Tenant B", nameAr: null, phone: null, email: null },
    ];
    return {
        searchUnits: vi.fn(async () => []),
        unitNames: vi.fn(async () => ({ rows: [], failedIds: [] })),
        searchRenters: vi.fn(async () => people),
        renterNames: vi.fn(async (ids: string[]) => ({ rows: people.filter(p => ids.includes(p.id)), failedIds: [] })),
    };
});
vi.mock("@/lib/api/lookup", () => ({ lookupApi: lookup }));
vi.mock("@/lib/api/ledger", async orig => {
    const m = await orig<typeof import("@/lib/api/ledger")>();
    return { ...m, ledgerApi: { ...m.ledgerApi, ledger: { ...m.ledgerApi.ledger, renter, general } } };
});
// `get` and `accounts` are spies; `fileUrl` is the real one, so the export URLs are the ones the browser would fetch.
vi.mock("@/lib/api/reports", async orig => {
    const m = await orig<typeof import("@/lib/api/reports")>();
    return { ...m, reportsApi: { ...m.reportsApi, tenantLedger: { ...m.reportsApi.tenantLedger, get, accounts } } };
});
vi.mock("@/lib/download", () => ({ saveResponse: vi.fn() }));
vi.mock("@/components/finance/AccountPicker", () => ({ default: () => null, loadAccounts: async () => [] }));
import Page from "../page";
import { defaultLedgerRange } from "@/components/finance/LedgerFilters";
import { grid, item } from "@/components/reports/__tests__/grid.fixture";
import { saveResponse } from "@/lib/download";
import { ApiError } from "@/lib/api/facilities";
import type { ReportGrid } from "@/lib/api/reports";

const RECEIVABLE = [
    { id: "a1", code: "1200", name: "Rent Receivable - Sample Tower", nameAr: null, propertyId: "p1", propertyName: "Sample Tower" },
    { id: "a2", code: "1210", name: "Rent Receivable - Sample Court", nameAr: null, propertyId: "p2", propertyName: "Sample Court" },
];

/** The mocked `useSearchParams` and the real address bar start from the same query string. */
function at(search: string) {
    query.current = search;
    window.history.replaceState(null, "", `/en/dashboard/finance/tenant-ledger${search ? `?${search}` : ""}`);
}
const url = () => Object.fromEntries(new URL(window.location.href).searchParams);

beforeEach(() => {
    at("");
    renter.mockResolvedValue([]);
    general.mockResolvedValue([]);
    get.mockResolvedValue(grid);
    accounts.mockResolvedValue(RECEIVABLE);
});
afterEach(() => { cleanup(); vi.clearAllMocks(); vi.unstubAllGlobals(); });

describe("Tenant Ledger — receivable (PACT) view", () => {
    it("opens on the PACT view and fetches nothing until a scope is picked", async () => {
        render(<Page />);
        expect(await screen.findByText("Pick a tenant, a property or an account to run the ledger")).toBeInTheDocument();
        expect(get).not.toHaveBeenCalled();
        expect(renter).not.toHaveBeenCalled();
        expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("Tenant Ledger");
        expect(screen.getByRole("button", { name: "Receivable (PACT)" })).toHaveAttribute("aria-pressed", "true");
        expect(screen.getByRole("button", { name: "All accounts" })).toHaveAttribute("aria-pressed", "false");
        // The view is in the URL from the start, so a later apply that drops the contract cannot flip it.
        expect(url()).toEqual({ view: "receivable" });
        // Nothing loaded, nothing to export.
        expect(screen.getByRole("button", { name: "Export" })).toBeDisabled();
    });

    it("runs for a property over the last 12 months and keeps the filters in the URL", async () => {
        render(<Page />);
        fireEvent.change(screen.getByLabelText("Property"), { target: { value: "p1" } });
        // A draft runs nothing until it is applied.
        expect(get).not.toHaveBeenCalled();
        fireEvent.click(screen.getByRole("button", { name: "Apply" }));
        const range = defaultLedgerRange();
        await waitFor(() => expect(get).toHaveBeenCalledWith(expect.objectContaining({ from: range.from, to: range.to, propertyId: "p1" }), 0, "en", expect.anything()));
        expect(get.mock.calls[0][3]).toBeInstanceOf(AbortSignal);
        expect(url()).toEqual({ view: "receivable", propertyId: "p1", from: range.from, to: range.to });
        expect(await screen.findByTestId("grid-total")).toBeInTheDocument();
        expect(screen.getByText("Rent Receivable - Sample Tower")).toBeInTheDocument();
        // The receivable picker follows the property.
        await waitFor(() => expect(accounts).toHaveBeenLastCalledWith("p1"));
        expect(screen.getByRole("button", { name: "Export" })).toBeEnabled();
    });

    it("adds receivable accounts as chips, runs on them and drops another property's when the property changes", async () => {
        render(<Page />);
        const picker = await screen.findByLabelText("Receivable accounts");
        await screen.findByRole("option", { name: "1200 — Rent Receivable - Sample Tower" });
        expect(screen.getByRole("option", { name: "All receivable accounts" })).toBeInTheDocument();
        fireEvent.change(picker, { target: { value: "a1" } });
        fireEvent.change(picker, { target: { value: "a2" } });
        expect(screen.getByRole("button", { name: "Remove 1200 — Rent Receivable - Sample Tower" })).toBeInTheDocument();
        expect(screen.getByRole("button", { name: "Remove 1210 — Rent Receivable - Sample Court" })).toBeInTheDocument();
        // Once some are picked the empty option invites another, and a picked account is not offered twice.
        expect(screen.getByRole("option", { name: "Add a receivable account" })).toBeInTheDocument();
        expect(screen.queryByRole("option", { name: "1200 — Rent Receivable - Sample Tower" })).toBeNull();

        fireEvent.click(screen.getByRole("button", { name: "Remove 1210 — Rent Receivable - Sample Court" }));
        fireEvent.click(screen.getByLabelText("Only tenants with a balance"));
        fireEvent.click(screen.getByLabelText("Hide reversed pairs"));
        fireEvent.click(screen.getByRole("button", { name: "Apply" }));
        await waitFor(() => expect(get).toHaveBeenCalledWith(
            expect.objectContaining({ accountIds: ["a1"], onlyWithBalance: true, hideReversed: true }), 0, "en", expect.anything()));
        expect(url()).toMatchObject({ view: "receivable", accountIds: "a1", onlyWithBalance: "1", hideReversed: "1" });

        // Sample Court's account cannot sit beside a Sample Tower filter: picking the property drops it, keeps a1.
        fireEvent.change(picker, { target: { value: "a2" } });
        fireEvent.change(screen.getByLabelText("Property"), { target: { value: "p1" } });
        expect(screen.queryByRole("button", { name: "Remove 1210 — Rent Receivable - Sample Court" })).toBeNull();
        expect(screen.getByRole("button", { name: "Remove 1200 — Rent Receivable - Sample Tower" })).toBeInTheDocument();
    });

    it("exports the applied filter", async () => {
        at("view=receivable&propertyId=p1");
        const fetchSpy = vi.fn<(url: string) => Promise<Response>>(async () => new Response("x", { status: 200 }));
        vi.stubGlobal("fetch", fetchSpy);
        render(<Page />);
        await screen.findByTestId("grid-total");
        // An edit not yet applied stays out of the file: the export is what is on screen.
        fireEvent.click(screen.getByLabelText("Only tenants with a balance"));

        fireEvent.click(screen.getByRole("button", { name: "Export" }));
        fireEvent.click(screen.getByRole("menuitem", { name: "Excel (PACT layout)" }));
        await waitFor(() => expect(saveResponse).toHaveBeenCalledWith(expect.any(Response), "tenant-ledger.xlsx"));
        expect(fetchSpy).toHaveBeenCalledWith(expect.stringMatching(/^\/api\/proxy\/v1\/finance\/reports\/tenant-ledger\.xlsx\?.*propertyId=p1.*lang=en/));
        expect(fetchSpy.mock.calls[0][0]).not.toContain("onlyWithBalance");

        // The Arabic PDF asks for Arabic whatever the screen's language.
        await waitFor(() => expect(screen.getByRole("button", { name: "Export" })).toBeEnabled());
        fireEvent.click(screen.getByRole("button", { name: "Export" }));
        fireEvent.click(screen.getByRole("menuitem", { name: "PDF — Arabic" }));
        await waitFor(() => expect(saveResponse).toHaveBeenLastCalledWith(expect.any(Response), "tenant-ledger.pdf"));
        expect(fetchSpy).toHaveBeenLastCalledWith(expect.stringMatching(/^\/api\/proxy\/v1\/finance\/reports\/tenant-ledger\.pdf\?.*propertyId=p1.*lang=ar$/));
    });

    it("pages by tenant groups", async () => {
        const second: ReportGrid = { ...grid, page: { ...grid.page, page: 1, firstGroup: 16, lastGroup: 30 } };
        get.mockImplementation(async (_q: unknown, page: number) => (page === 1 ? second : grid));
        at("propertyId=p1");
        render(<Page />);
        expect(await screen.findByText("Tenants 1–16 of 41")).toBeInTheDocument();
        expect(screen.getByText("Page 1 of 4")).toBeInTheDocument();

        fireEvent.click(screen.getByRole("button", { name: "Next" }));
        await waitFor(() => expect(get).toHaveBeenLastCalledWith(expect.objectContaining({ propertyId: "p1" }), 1, "en", expect.anything()));
        expect(url().page).toBe("1");
        expect(await screen.findByText("Tenants 17–31 of 41")).toBeInTheDocument();
        expect(screen.getByText("Page 2 of 4")).toBeInTheDocument();

        // Applying the bar starts again from the first page, and page 0 stays out of the URL.
        fireEvent.click(screen.getByRole("button", { name: "Apply" }));
        await waitFor(() => expect(get).toHaveBeenLastCalledWith(expect.objectContaining({ propertyId: "p1" }), 0, "en", expect.anything()));
        expect(url().page).toBeUndefined();
        expect(await screen.findByText("Tenants 1–16 of 41")).toBeInTheDocument();
    });

    it("opens the page the URL names", async () => {
        at("propertyId=p1&page=1");
        render(<Page />);
        await waitFor(() => expect(get).toHaveBeenCalledWith(expect.objectContaining({ propertyId: "p1" }), 1, "en", expect.anything()));
    });

    it("cancels a load the user has moved on from", async () => {
        get.mockImplementationOnce(() => new Promise(() => {}));
        at("propertyId=p1");
        render(<Page />);
        await waitFor(() => expect(get).toHaveBeenCalledTimes(1));
        const first = get.mock.calls[0][3] as AbortSignal;
        expect(first.aborted).toBe(false);
        fireEvent.click(screen.getByRole("button", { name: "Apply" }));
        await waitFor(() => expect(get).toHaveBeenCalledTimes(2));
        expect(first.aborted).toBe(true);
        expect(await screen.findByTestId("grid-total")).toBeInTheDocument();
    });

    it("shows the empty state for an empty grid and still exports its zero totals", async () => {
        get.mockResolvedValue({ ...grid, items: [item("total", 0, { label: "REPORT TOTAL", cells: { debit: "0.00", credit: "0.00", balance: "0.00" } })],
            page: { ...grid.page, pageCount: 1, totalRows: 0, totalGroups: 0, firstGroup: 0, lastGroup: -1 } });
        at("propertyId=p1");
        render(<Page />);
        expect(await screen.findByText("Nothing to show for these filters.")).toBeInTheDocument();
        expect(screen.queryByTestId("grid-total")).toBeNull();
        expect(screen.getByRole("button", { name: "Export" })).toBeEnabled();
    });

    it("puts a failed load in a banner with a retry", async () => {
        get.mockRejectedValueOnce(new ApiError(404, "Property not found"));
        at("propertyId=p1");
        render(<Page />);
        expect(await screen.findByText("Property not found")).toBeInTheDocument();
        expect(screen.getByRole("button", { name: "Export" })).toBeDisabled();
        fireEvent.click(screen.getByRole("button", { name: /retry/i }));
        expect(await screen.findByTestId("grid-total")).toBeInTheDocument();
        expect(get).toHaveBeenCalledTimes(2);
    });

    it("switches to All accounts and back without losing the tenant", async () => {
        at("renterId=r1&view=receivable");
        render(<Page />);
        await waitFor(() => expect(get).toHaveBeenCalledWith(expect.objectContaining({ renterId: "r1" }), 0, "en", expect.anything()));

        fireEvent.click(screen.getByRole("button", { name: "All accounts" }));
        const range = defaultLedgerRange();
        await waitFor(() => expect(renter).toHaveBeenCalledWith("r1", { from: range.from, to: range.to }));
        expect(url()).toEqual({ view: "all", renterId: "r1" });
        expect(screen.getByRole("button", { name: "All accounts" })).toHaveAttribute("aria-pressed", "true");
        expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("Tenant Ledger");

        fireEvent.click(screen.getByRole("button", { name: "Receivable (PACT)" }));
        await waitFor(() => expect(get).toHaveBeenCalledTimes(2));
        expect(get).toHaveBeenLastCalledWith(expect.objectContaining({ renterId: "r1" }), 0, "en", expect.anything());
        expect(url()).toEqual({ view: "receivable", renterId: "r1" });
    });
});
