// Tenant Ledger: switching views keeps the applied filters under Next's real `useSearchParams`.
//
// The other tests stub `useSearchParams` with a fixed string, so a view that re-reads it on mount
// always sees what the test put there. Here it behaves like Next 16's App Router
// (next/dist/client/components/app-router.js): `useSearchParams` is the router's URL, not the
// address bar's. A `history.replaceState` whose state carries the router's own marker (`__NA`)
// is taken for one of the router's calls and passes straight through, so the router never moves;
// any other call gets the marker copied on and moves the router to the new URL. Passing
// `history.state` back on a write therefore left every view switch reading the URL of arrival.
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
const router = vi.hoisted(() => {
    let search = "";
    const listeners = new Set<() => void>();
    return {
        search: () => search,
        move(next: string) {
            search = next;
            listeners.forEach(l => l());
        },
        subscribe(l: () => void) {
            listeners.add(l);
            return () => { listeners.delete(l); };
        },
    };
});
vi.mock("next/navigation", async () => {
    const { useSyncExternalStore } = await import("react");
    return { useSearchParams: () => new URLSearchParams(useSyncExternalStore(router.subscribe, router.search)) };
});
const renter = vi.hoisted(() => vi.fn());
const general = vi.hoisted(() => vi.fn());
const get = vi.hoisted(() => vi.fn());
const accounts = vi.hoisted(() => vi.fn());
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
vi.mock("@/lib/api/reports", async orig => {
    const m = await orig<typeof import("@/lib/api/reports")>();
    return { ...m, reportsApi: { ...m.reportsApi, tenantLedger: { ...m.reportsApi.tenantLedger, get, accounts } } };
});
vi.mock("@/components/finance/AccountPicker", () => ({ default: () => null, loadAccounts: async () => [] }));
import Page from "../page";
import { defaultLedgerRange } from "@/components/finance/LedgerFilters";
import { grid } from "@/components/reports/__tests__/grid.fixture";

const PATH = "/en/dashboard/finance/tenant-ledger";
const nativeReplaceState = window.history.replaceState;

type NextHistoryState = { __NA?: boolean; _N?: boolean; __PRIVATE_NEXTJS_INTERNALS_TREE?: unknown } | null | undefined;

/** Patch `replaceState` the way Next's App Router does (app-router.js, "Patch replaceState"). */
function patchReplaceStateLikeNext() {
    window.history.replaceState = function replaceState(data: NextHistoryState, unused: string, url?: string | URL | null) {
        if (data?.__NA || data?._N) return nativeReplaceState.call(window.history, data, unused, url);
        const current = window.history.state as NextHistoryState;
        const copied = { ...(data ?? {}), __NA: current?.__NA, __PRIVATE_NEXTJS_INTERNALS_TREE: current?.__PRIVATE_NEXTJS_INTERNALS_TREE };
        nativeReplaceState.call(window.history, copied, unused, url);
        if (url != null) router.move(new URL(String(url), window.location.href).search.replace(/^\?/, ""));
    };
}

/** Land on the page as the App Router does: its own marker in `history.state`, the router at the URL. */
function arrive(search: string) {
    nativeReplaceState.call(window.history, { __NA: true, __PRIVATE_NEXTJS_INTERNALS_TREE: ["tenant-ledger"] }, "", `${PATH}${search ? `?${search}` : ""}`);
    router.move(search);
}

const url = () => Object.fromEntries(new URL(window.location.href).searchParams);
const range = defaultLedgerRange();

async function pick(label: string, name: string) {
    fireEvent.click(screen.getByLabelText(label));
    fireEvent.click(await screen.findByText(name));
}

beforeEach(() => {
    patchReplaceStateLikeNext();
    renter.mockResolvedValue([]);
    general.mockResolvedValue([]);
    get.mockResolvedValue(grid);
    accounts.mockResolvedValue([]);
});
afterEach(() => {
    cleanup();
    window.history.replaceState = nativeReplaceState;
    nativeReplaceState.call(window.history, null, "", "/");
    router.move("");
    vi.clearAllMocks();
});

describe("Tenant Ledger — the view switch under Next's router", () => {
    it("keeps a property applied in Receivable through a trip to All accounts and back", async () => {
        arrive("");
        render(<Page />);
        fireEvent.change(screen.getByLabelText("Property"), { target: { value: "p1" } });
        fireEvent.click(screen.getByRole("button", { name: "Apply" }));
        await waitFor(() => expect(get).toHaveBeenCalledTimes(1));
        // The router heard the apply, not only the address bar.
        expect(new URLSearchParams(router.search()).get("propertyId")).toBe("p1");

        fireEvent.click(screen.getByRole("button", { name: "All accounts" }));
        expect(await screen.findByText("Pick a tenant to see their ledger")).toBeInTheDocument();
        // The switch itself moves the router: it and the address bar agree on the view.
        expect(router.search()).toBe(window.location.search.replace(/^\?/, ""));
        expect(new URLSearchParams(router.search()).get("view")).toBe("all");
        fireEvent.click(screen.getByRole("button", { name: "Receivable (PACT)" }));
        await waitFor(() => expect(get).toHaveBeenCalledTimes(2));
        expect(get).toHaveBeenLastCalledWith(
            expect.objectContaining({ propertyId: "p1", from: range.from, to: range.to }), 0, "en", expect.anything());
        expect(url()).toEqual({ view: "receivable", propertyId: "p1", from: range.from, to: range.to });
    });

    it("opens All accounts on the tenant applied in Receivable", async () => {
        arrive("");
        render(<Page />);
        await pick("Tenant (optional)", "Tenant A");
        fireEvent.click(screen.getByRole("button", { name: "Apply" }));
        await waitFor(() => expect(get).toHaveBeenCalledWith(expect.objectContaining({ renterId: "r1" }), 0, "en", expect.anything()));

        fireEvent.click(screen.getByRole("button", { name: "All accounts" }));
        await waitFor(() => expect(renter).toHaveBeenCalledWith("r1", { from: range.from, to: range.to }));
        expect(screen.queryByText("Pick a tenant to see their ledger")).toBeNull();
        expect(url()).toMatchObject({ view: "all", renterId: "r1" });
    });

    it("runs Receivable on the tenant picked in All accounts, not the one the lease link brought", async () => {
        arrive("renterId=r1&leaseId=l1");
        render(<Page />);
        await waitFor(() => expect(renter).toHaveBeenCalledWith("r1", { from: range.from, to: range.to }));
        expect(screen.getByRole("button", { name: "All accounts" })).toHaveAttribute("aria-pressed", "true");

        await pick("Tenant", "Tenant B");
        fireEvent.click(screen.getByRole("button", { name: "Apply" }));
        await waitFor(() => expect(renter).toHaveBeenCalledWith("r2", { from: range.from, to: range.to }));
        expect(url()).toMatchObject({ view: "all", renterId: "r2" });
        expect(url().leaseId).toBeUndefined();

        fireEvent.click(screen.getByRole("button", { name: "Receivable (PACT)" }));
        await waitFor(() => expect(get).toHaveBeenCalledTimes(1));
        const [q] = get.mock.calls[0];
        expect(q).toMatchObject({ renterId: "r2" });
        expect(q.leaseId).toBeUndefined();
        expect(url()).toMatchObject({ view: "receivable", renterId: "r2" });
    });

    it("moves the router with the pager and reopens on that page after a switch", async () => {
        get.mockImplementation(async (_q: unknown, page: number) => ({ ...grid, page: { ...grid.page, page } }));
        arrive("propertyId=p1");
        render(<Page />);
        await waitFor(() => expect(get).toHaveBeenCalledTimes(1));
        fireEvent.click(await screen.findByRole("button", { name: "Next" }));
        await waitFor(() => expect(get).toHaveBeenLastCalledWith(expect.objectContaining({ propertyId: "p1" }), 1, "en", expect.anything()));
        expect(new URLSearchParams(router.search()).get("page")).toBe("1");

        fireEvent.click(screen.getByRole("button", { name: "All accounts" }));
        fireEvent.click(await screen.findByRole("button", { name: "Receivable (PACT)" }));
        await waitFor(() => expect(get).toHaveBeenCalledTimes(3));
        expect(get).toHaveBeenLastCalledWith(expect.objectContaining({ propertyId: "p1" }), 1, "en", expect.anything());
    });
});
