import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../messages/en.json";
import ar from "../../../../../../../messages/ar.json";
import type { LeaseDetail } from "@/lib/api/leasing";

/**
 * Break-it R2 silent-mutation sweep: the lease page's supporting documents ignored
 * a refused upload (P1) and a refused delete (P2). Each now says so, EN/AR; the
 * upload names the file, and a refused delete keeps the row.
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
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "ACCOUNTANT" } } }) }));
vi.mock("@/components/finance/AccountPicker", () => ({ default: () => <div data-testid="account-picker" /> }));
vi.mock("@/components/leases/LeaseInteractionsPanel", () => ({ default: () => null }));

const LEASE: LeaseDetail = {
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

const DOC = { id: "a1", leaseId: "lease-1", name: "Ejari scan", fileUrl: "x", fileType: "pdf", fileSize: 2048, uploadedAt: "2026-01-02" };

vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return {
        ...m,
        chargeTypeApi: { ...m.chargeTypeApi, list: vi.fn(async () => []) },
        penaltyApi: { ...m.penaltyApi, list: vi.fn(async () => ({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 25 })) },
        leaseApi: { ...m.leaseApi, get: vi.fn(async () => LEASE), cheques: vi.fn(async () => []), addenda: vi.fn(async () => []) },
    };
});

import LeaseDetailPage from "../page";

let upload: () => Response;
let remove: () => Response;

beforeEach(() => {
    global.fetch = vi.fn(async (url: RequestInfo | URL, init?: RequestInit) => {
        const href = String(url);
        if (href.startsWith("/api/upload")) return upload();
        if (href.includes("/leases/attachments/") && init?.method === "DELETE") return remove();
        if (href.endsWith("/leases/lease-1/attachments")) return { ok: true, status: 200, json: async () => [DOC] } as Response;
        return { ok: true, status: 200, json: async () => [] } as Response;
    }) as unknown as typeof fetch;
});
afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

async function openDocuments(locale: "en" | "ar") {
    render(
        <NextIntlClientProvider locale={locale} messages={locale === "en" ? en : ar}>
            <LeaseDetailPage />
        </NextIntlClientProvider>,
    );
    fireEvent.click(await screen.findByTestId("lease-tab-documents"));
    await screen.findByText("Ejari scan");
}

describe("lease page — supporting documents say when a change is refused", () => {
    it("a refused upload names the file (P1)", async () => {
        upload = () => ({ ok: false, status: 413, json: async () => ({}) }) as Response;
        await openDocuments("en");
        fireEvent.change(screen.getByPlaceholderText(en.MasterData.documentNamePlaceholder), { target: { value: "Passport" } });
        const input = document.querySelector('input[type="file"]') as HTMLInputElement;
        fireEvent.change(input, { target: { files: [new File(["x"], "passport.pdf", { type: "application/pdf" })] } });
        expect((await screen.findByTestId("lease-doc-error")).textContent)
            .toBe(en.MasterData.documentUploadFailed.replace("{name}", "passport.pdf"));
    });

    it("a refused delete says so in Arabic and keeps the row (P2)", async () => {
        remove = () => ({ ok: false, status: 403, json: async () => ({}) }) as Response;
        await openDocuments("ar");
        fireEvent.click(screen.getByLabelText(ar.MasterData.delete));
        expect((await screen.findByTestId("lease-doc-error")).textContent).toBe(ar.MasterData.documentDeleteFailed);
        await waitFor(() => expect(screen.getByText("Ejari scan")).toBeInTheDocument());
    });
});
