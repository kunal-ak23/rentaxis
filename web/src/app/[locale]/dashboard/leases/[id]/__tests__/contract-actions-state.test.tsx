import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../messages/en.json";
import type { LeaseDetail } from "@/lib/api/leasing";

/**
 * Break-it R4 (tutorials/bugs/11, "seen alongside"): the contract section offered
 * Preview Contract (and its Confirm & Save) on an Active contract, where the server
 * refuses to generate, and View settlement on a draft or active one, which has no
 * settlement. Both follow the page's one action model (leaseActions.ts) now.
 */

vi.mock("next/navigation", () => ({
    useParams: () => ({ id: "lease-1" }),
    useSearchParams: () => new URLSearchParams(),
}));
vi.mock("@/i18n/routing", () => ({
    useRouter: () => ({ push: vi.fn() }),
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
}));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "TENANT_ADMIN" } } }) }));
vi.mock("@/components/finance/AccountPicker", () => ({ default: () => <div data-testid="account-picker" /> }));
vi.mock("@/components/leases/LeaseInteractionsPanel", () => ({ default: () => null }));

let LEASE: LeaseDetail;
const BASE: LeaseDetail = {
    id: "lease-1", unitId: "u1", renterId: "r1", unitIdentifier: "A-101", renterName: "Prabhjot Singh",
    startDate: "2026-01-01", endDate: "2026-12-31", status: "ACTIVE",
    rentAmount: null, depositAmount: null, ejariNumber: null, paymentTerms: 4,
    installmentDistribution: "LAST_LARGER", paymentMethod: "CHEQUE", depositPaymentMethod: "CHEQUE",
    paymentReferenceNumber: null, propertyId: "p1", propertyName: "L'Olivier", propertyCode: "OLV",
    hasContract: false, contractNumber: null, displayContractNumber: "TCO-26/15",
    agreementDate: null, rentVatApplicable: true, contractDate: "2026-01-01", totalDays: 365,
    gracePeriodDays: 5, firstDueDate: "2026-01-01", renterAcceptedAt: null,
    renewedFromLeaseId: null, chainId: "chain-1", receivableAccountId: null, incomeAccountId: null,
    postingJournalId: null, postedAt: null, contractValue: 60000,
    terminatedOn: null, terminationJournalId: null, terminationNotes: null,
    lines: [{
        id: "ln1", seqNo: 1, chargeTypeId: "ct-rent", chargeTypeCode: "RENT", chargeTypeName: "Rent",
        behaviour: "RENT", creditAccountId: "acc-1", creditAccountCode: "210100", creditAccountName: "Advance Rent",
        grossAmount: 60000, discountAmount: 0, netAmount: 60000, narration: null,
        vatApplicable: true, periodStart: null, periodEnd: null,
    }],
};


vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return {
        ...m,
        chargeTypeApi: { ...m.chargeTypeApi, list: vi.fn(async () => []) },
        penaltyApi: { ...m.penaltyApi, list: vi.fn(async () => ({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 25 })) },
        leaseApi: { ...m.leaseApi, get: vi.fn(async () => LEASE), cheques: vi.fn(async () => []), addenda: vi.fn(async () => []) },
        settlementApi: { ...m.settlementApi, get: vi.fn(async () => { throw new m.ApiError(404, "none"); }) },
    };
});

import LeaseDetailPage from "../page";

beforeEach(() => {
    global.fetch = vi.fn(async () => ({ ok: true, status: 200, json: async () => [] }) as Response) as unknown as typeof fetch;
});
afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

async function contractSection(status: LeaseDetail["status"]) {
    LEASE = { ...BASE, status, postedAt: status === "DRAFT" || status === "PENDING_SIGNATURE" ? null : "2026-01-01T00:00:00Z" };
    render(
        <NextIntlClientProvider locale="en" messages={en}>
            <LeaseDetailPage />
        </NextIntlClientProvider>,
    );
    fireEvent.click(await screen.findByTestId("lease-tab-documents"));
    await waitFor(() => expect(screen.getByText(en.MasterData.contractNumber)).toBeInTheDocument());
}

describe("contract section offers only what the server allows", () => {
    it.each([
        ["DRAFT", true, false],
        ["PENDING_SIGNATURE", true, false],
        ["ACTIVE", false, false],
        ["NOTICE_GIVEN", false, false],
        ["TERMINATED", false, true],
        ["EXPIRED", false, true],
    ] as [LeaseDetail["status"], boolean, boolean][])("%s: preview %s, view settlement %s", async (status, preview, settlement) => {
        await contractSection(status);
        expect(screen.queryByText(en.MasterData.generatePreview) !== null).toBe(preview);
        expect(screen.queryByRole("link", { name: en.MasterData.viewSettlement }) !== null).toBe(settlement);
    });
});
