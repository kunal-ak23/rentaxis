import { cleanup, render, screen, waitFor } from "@testing-library/react";
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

beforeEach(() => {
    role = "TENANT_ADMIN";
    renterStatus = 200;
    global.fetch = vi.fn(async (url: unknown) => {
        const u = String(url);
        if (u.endsWith("/v1/renters/r1")) return renterStatus === 200 ? jsonRes(renter) : jsonRes({}, false, renterStatus);
        if (u.endsWith("/v1/renters/r1/leases")) return jsonRes(leases);
        if (u.includes("/leases/L1/cheques")) return jsonRes(cheques);
        if (u.endsWith("/v1/tickets")) return jsonRes(tickets);
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
