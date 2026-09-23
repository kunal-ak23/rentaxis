import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { chequeSummary } from "@/components/renters/chequeSummary";

// #8: the staff renter-detail page — profile, contracts (linked), cheques and
// their summary, tickets, the ledger link and Resend invite.

let role = "TENANT_ADMIN";
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role } } }) }));
vi.mock("next/navigation", () => ({ useParams: () => ({ id: "r1" }) }));
vi.mock("next-intl", () => {
    const t = Object.assign((key: string) => key, { has: () => true });
    return { useTranslations: () => t, useLocale: () => "en" };
});
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
}));

import RenterDetailPage from "../page";

const jsonRes = (body: unknown, ok = true, status = 200) =>
    ({ ok, status, json: async () => body, text: async () => JSON.stringify(body) }) as unknown as Response;

const renter = {
    id: "r1", nameEn: "Ahmed Al Mansoori", nameAr: "أحمد", email: "ahmed@x.com", phone: "+971500000000",
    primaryLanguage: "EN", userId: "u1", invitePending: true, inviteExpiresAt: "2099-01-01T00:00:00Z",
};
const leases = [
    { id: "L1", unitIdentifier: "101", propertyName: "Tower", startDate: "2026-01-01", endDate: "2026-12-31", status: "ACTIVE", rentAmount: 60000, displayContractNumber: "TCO-26/1" },
];
const cheques = [
    { id: "c1", leaseId: "L1", chequeNumber: "000101", chequeDate: "2026-01-01", amount: 30000, status: "CLEARED" },
    { id: "c2", leaseId: "L1", chequeNumber: "000102", chequeDate: "2026-07-01", amount: 30000, status: "REGISTERED" },
];
const tickets = [
    { id: "t-mine", title: "Leaking tap", status: "OPEN", leaseId: null, reportedBy: "u1", reportedDate: "2026-03-01", createdAt: "2026-03-01T00:00:00Z" },
    { id: "t-lease", title: "AC noise", status: "CLOSED", leaseId: "L1", reportedBy: "staff", reportedDate: "2026-04-01", createdAt: "2026-04-01T00:00:00Z" },
    { id: "t-other", title: "Someone else", status: "OPEN", leaseId: "L9", reportedBy: "u9", reportedDate: "2026-04-01", createdAt: "2026-04-01T00:00:00Z" },
];

let renterStatus = 200;
let leasesStatus = 200;
let chequesStatus = 200;
let ticketsStatus = 200;
const twoLeases = [
    ...leases,
    { id: "L2", unitIdentifier: "102", propertyName: "Tower", startDate: "2025-01-01", endDate: "2025-12-31", status: "EXPIRED", rentAmount: 55000, displayContractNumber: "TCO-25/9" },
];
let leaseRows: typeof twoLeases = leases;

const res = (status: number, body: unknown) => (status === 200 ? jsonRes(body) : jsonRes({ message: "boom" }, false, status));

beforeEach(() => {
    role = "TENANT_ADMIN";
    renterStatus = 200;
    leasesStatus = 200;
    chequesStatus = 200;
    ticketsStatus = 200;
    leaseRows = leases;
    global.fetch = vi.fn(async (url: unknown) => {
        const u = String(url);
        if (u.endsWith("/v1/renters/r1")) return res(renterStatus, renter);
        if (u.endsWith("/v1/renters/r1/leases")) return res(leasesStatus, leaseRows);
        if (u.includes("/leases/L1/cheques")) return jsonRes(cheques);
        if (u.includes("/leases/L2/cheques")) return res(chequesStatus, []);
        // The server filters by renter (GET /tickets?renterId=); "t-other" is
        // what an unfiltered list would add.
        if (u.endsWith("/v1/tickets?renterId=r1")) return res(ticketsStatus, tickets.filter(tk => tk.id !== "t-other"));
        if (u.endsWith("/v1/tickets")) return res(ticketsStatus, tickets);
        return jsonRes({}, false, 404);
    }) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

describe("RenterDetailPage", () => {
    it("shows the profile, linked contracts, cheques and only this renter's tickets", async () => {
        render(<RenterDetailPage />);

        expect(await screen.findByText("Ahmed Al Mansoori")).toBeTruthy();
        expect(screen.getByText("TCO-26/1").closest("a")?.getAttribute("href")).toBe("/dashboard/leases/L1");
        await waitFor(() => expect(screen.getByText("000102")).toBeTruthy());
        expect(screen.getByText("Leaking tap")).toBeTruthy();
        expect(screen.getByText("AC noise")).toBeTruthy();
        expect(screen.queryByText("Someone else")).toBeNull();
        // One renter's tickets are asked for by id, never the whole tenant's list.
        const urls = (global.fetch as unknown as { mock: { calls: unknown[][] } }).mock.calls.map(c => String(c[0]));
        expect(urls).toContain("/api/proxy/v1/tickets?renterId=r1");
        expect(urls).not.toContain("/api/proxy/v1/tickets");
        expect(screen.getByTestId("renter-ledger").getAttribute("href")).toBe("/dashboard/finance/tenant-ledger?renterId=r1");
        expect(screen.getByText("resend")).toBeTruthy();
    });

    it("hides Resend invite and the ledger from a property manager", async () => {
        role = "PROPERTY_MANAGER";
        render(<RenterDetailPage />);

        expect(await screen.findByText("Ahmed Al Mansoori")).toBeTruthy();
        expect(screen.queryByText("resend")).toBeNull();
        expect(screen.queryByTestId("renter-ledger")).toBeNull();
    });

    it("shows not-found for a renter the API will not return", async () => {
        renterStatus = 404;
        render(<RenterDetailPage />);

        expect(await screen.findByText("notFound")).toBeTruthy();
        expect(screen.queryByText("loadFailed")).toBeNull();
    });

    // Web review I2: a failed load is not "not found" or "nothing here".
    it("shows a load error with a retry, not not-found, when the renter fails to load", async () => {
        renterStatus = 500;
        render(<RenterDetailPage />);

        expect(await screen.findByText("loadFailed")).toBeTruthy();
        expect(screen.queryByText("notFound")).toBeNull();

        renterStatus = 200;
        fireEvent.click(screen.getByText("retry"));
        expect(await screen.findByText("Ahmed Al Mansoori")).toBeTruthy();
    });

    it("hides the cheque totals and says so when one contract's cheques fail to load", async () => {
        leaseRows = twoLeases;
        chequesStatus = 500;
        render(<RenterDetailPage />);

        expect(await screen.findByText("chequesLoadFailed")).toBeTruthy();
        // The cheques that did load are listed, but no total pretends to be complete.
        expect(screen.getByText("000102")).toBeTruthy();
        const tiles = screen.getByTestId("renter-summary");
        expect(tiles.textContent).not.toContain("60,000");
        expect(tiles.textContent).not.toContain("30,000");
        expect(tiles.textContent).toContain("—");
    });

    it("shows the totals when every cheque call succeeds", async () => {
        leaseRows = twoLeases;
        render(<RenterDetailPage />);

        await waitFor(() => expect(screen.getByText("000102")).toBeTruthy());
        expect(screen.queryByText("chequesLoadFailed")).toBeNull();
        expect(screen.getByTestId("renter-summary").textContent).toContain("60,000");
    });

    it("says the contracts failed to load instead of 'no contracts'", async () => {
        leasesStatus = 502;
        render(<RenterDetailPage />);

        expect(await screen.findByText("contractsLoadFailed")).toBeTruthy();
        expect(screen.queryByText("noContracts")).toBeNull();
        expect(screen.getByText("chequesLoadFailed")).toBeTruthy();
    });

    it("says the tickets failed to load instead of 'no tickets'", async () => {
        ticketsStatus = 500;
        render(<RenterDetailPage />);

        expect(await screen.findByText("ticketsLoadFailed")).toBeTruthy();
        expect(screen.queryByText("noTickets")).toBeNull();
    });
});

describe("chequeSummary", () => {
    it("leaves draft, cancelled and replaced cheques out of every figure", () => {
        const s = chequeSummary([
            { amount: 100, status: "CLEARED" },
            { amount: 50, status: "DEPOSITED" },
            { amount: 25, status: "BOUNCED" },
            { amount: 999, status: "DRAFT" },
            { amount: 999, status: "CANCELLED" },
            { amount: 999, status: "REPLACED" },
        ] as never);
        expect(s).toEqual({ count: 3, total: 175, cleared: 100, outstanding: 50, bounced: 1 });
    });
});
