import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../../messages/en.json";
import ar from "../../../../../../../../messages/ar.json";
import type { LeaseDetail, SettlementResponse, SettlementStatement } from "@/lib/api/leasing";

/**
 * (Harness copied from statement.test.tsx.) The move-out statement (spec §9.2).
 *
 * Every gate here is a Java rule wearing a UI:
 *
 *  - a refund needs a bank account — `SettlementService.finalizeSettlement`
 *    :452-454 / `requireRefundBank` :800-804, and only when `netRefund > 0`;
 *  - a refund paid while the register still holds something needs the
 *    accountant to say so on purpose — `acknowledgeOutstanding`;
 *  - PENALTIES / UNPAID_RENT / PREPAID_RENT / UTILITY_OVERPAYMENT are refused
 *    as lines, so they are not in the selects at all (:737-761);
 *  - a lease that has not been terminated cannot be settled (:645-660);
 *  - **finalise does not close the contract.** `LeaseClosureService` closes it
 *    only when nothing is left on the register, so the screen reads the lease's
 *    real status back rather than promising closure.
 */

const push = vi.fn();
let role = "ACCOUNTANT";

vi.mock("next/navigation", () => ({ useParams: () => ({ id: "lease-1", locale: "en" }) }));
vi.mock("@/i18n/routing", () => ({
    useRouter: () => ({ push }),
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
}));
// `data` is null until NextAuth has resolved the session, which is the state
// the page has to survive without fetching — see "waits for the session".
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: role ? { user: { role } } : null }) }));
vi.mock("@/components/finance/AccountPicker", () => ({
    default: ({ value, onChange }: { value: string | null; onChange: (id: string) => void }) => (
        <button type="button" data-testid="account-picker" data-value={value ?? ""} onClick={() => onChange("acc-9")}>
            pick
        </button>
    ),
}));

const api = vi.hoisted(() => ({
    lease: vi.fn(),
    statement: vi.fn(),
    get: vi.fn(),
    saveDraft: vi.fn(),
    finalize: vi.fn(),
    fiscal: vi.fn(),
}));

vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return {
        ...m,
        leaseApi: { ...m.leaseApi, get: api.lease },
        settlementApi: {
            ...m.settlementApi,
            statement: api.statement,
            get: api.get,
            saveDraft: api.saveDraft,
            finalize: api.finalize,
        },
    };
});
vi.mock("@/lib/api/ledger", async orig => {
    const m = await orig<typeof import("@/lib/api/ledger")>();
    return { ...m, ledgerApi: { ...m.ledgerApi, fiscal: { ...m.ledgerApi.fiscal, get: api.fiscal } } };
});

import SettlementPage from "../page";

const LEASE: LeaseDetail = {
    id: "lease-1", unitId: "u1", renterId: "r1", unitIdentifier: "204", renterName: "Prabhjot Singh",
    startDate: "2026-01-01", endDate: "2026-12-31", status: "TERMINATED",
    rentAmount: 120000, depositAmount: 10000, ejariNumber: null, paymentTerms: 4,
    installmentDistribution: "UNIFORM", paymentMethod: "CHEQUE", depositPaymentMethod: "CHEQUE",
    paymentReferenceNumber: null, propertyId: "p1", propertyName: "L'Olivier", propertyCode: "OLV",
    hasContract: true, contractNumber: 15, displayContractNumber: "TCO-26/15",
    agreementDate: null, rentVatApplicable: false, contractDate: "2026-01-01", totalDays: 365,
    gracePeriodDays: 5, firstDueDate: "2026-01-01", renterAcceptedAt: null,
    renewedFromLeaseId: null, chainId: "chain-1", receivableAccountId: null, incomeAccountId: null,
    postingJournalId: "j0", postedAt: "2026-01-01T00:00:00Z", contractValue: 120000,
    terminatedOn: "2026-06-30", terminationJournalId: "j1", terminationNotes: null,
    lines: [],
};

function statement(over: Partial<SettlementStatement> = {}): SettlementStatement {
    return {
        asOf: "2026-07-05",
        earnedRent: 59835.62, receivedTotal: 60000, receivableBalance: -164.38,
        depositsHeld: 10000, penaltiesOutstanding: 0,
        instrumentsOutstanding: 0, outstandingInstruments: [],
        deductions: [], additions: [],
        totalDeductions: 0, totalAdditions: 0,
        netRefund: 10164.38, unrecognisedEntries: 0,
        ...over,
    };
}

function stored(over: Partial<SettlementResponse> = {}): SettlementResponse {
    return {
        id: "s1", leaseId: "lease-1", depositAmount: 10000,
        totalDeductions: 0, totalAdditions: 0, refundAmount: 10164.38,
        notes: null, status: "DRAFT", settledBy: null, settledByName: null, settledAt: null,
        createdAt: "2026-07-01T00:00:00Z", settlementDate: null,
        earnedRent: 59835.62, receivedTotal: 60000, receivableBalance: -164.38,
        depositsHeld: 10000, penaltiesOutstanding: 0, balanceDue: 0, refundBankAccountId: null,
        journalId: null, journalNumber: null, collectionChequeId: null,
        deductions: [],
        ...over,
    };
}

function renderPage() {
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <SettlementPage />
        </NextIntlClientProvider>,
    );
}

beforeEach(() => {
    role = "ACCOUNTANT";
    push.mockClear();
    api.lease.mockResolvedValue(LEASE);
    api.statement.mockResolvedValue(statement());
    api.get.mockResolvedValue(stored());
    api.saveDraft.mockImplementation(async () => stored());
    api.finalize.mockResolvedValue(
        stored({ status: "FINALIZED", journalId: "j9", journalNumber: "STL/2026/0004", settlementDate: "2026-07-05" }),
    );
    api.fiscal.mockResolvedValue({ fiscalYearStartMonth: 1, booksStartDate: null, booksLockedThrough: null });
});

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});


/**
 * Break-it R2 silent-mutation sweep: a deduction's evidence upload / delete
 * checked `res.ok` with no else and no catch — a refused file (empty, wrong
 * type, too big) simply never appeared, and the accountant was told nothing.
 */
const ATT = { id: "att-1", deductionId: "d1", name: "photo.jpg", fileUrl: "/x/photo.jpg", fileType: "image/jpeg", fileSize: 10, uploadedAt: "2026-07-01T00:00:00Z" };
let upload: () => Response;
let del: () => Response;

function withLine() {
    api.get.mockResolvedValue(stored({
        deductions: [{
            id: "d1", category: "DAMAGES", description: "Wall", amount: 500, autoCalculated: false, type: "DEDUCTION",
            additionCategory: null, accountId: null, accountName: null, attachments: [ATT],
        } as never],
    }));
    global.fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        if (url.startsWith("/api/upload")) return upload();
        if (init?.method === "DELETE") return del();
        return new Response(JSON.stringify([ATT]), { status: 200 });
    }) as unknown as typeof fetch;
}

async function pickFile() {
    const input = await waitFor(() => {
        const el = document.querySelector('input[type="file"]');
        if (!el) throw new Error("no file input yet");
        return el as HTMLInputElement;
    });
    fireEvent.change(input, { target: { files: [new File(["x"], "evidence.pdf", { type: "application/pdf" })] } });
}

describe("Settlement deduction attachments — failures are shown", () => {
    it("names the file when the upload is refused", async () => {
        withLine();
        upload = () => new Response(JSON.stringify({ message: "File is empty" }), { status: 400 });
        renderPage();
        await pickFile();
        const alert = await screen.findByTestId("settlement-attachment-error");
        expect(alert).toHaveTextContent(en.Settlement.attachmentUploadFailed.replace("{name}", "evidence.pdf"));
    });

    it("says so when the upload request fails outright", async () => {
        withLine();
        upload = () => { throw new TypeError("Failed to fetch"); };
        renderPage();
        await pickFile();
        expect(await screen.findByTestId("settlement-attachment-error"))
            .toHaveTextContent(en.Settlement.attachmentUploadFailed.replace("{name}", "evidence.pdf"));
    });

    it("says so when removing an attachment is refused", async () => {
        withLine();
        del = () => new Response("", { status: 403 });
        renderPage();
        await screen.findByAltText("photo.jpg");
        const trash = document.querySelector("button.bg-error") as HTMLButtonElement;
        fireEvent.click(trash);
        expect(await screen.findByTestId("settlement-attachment-error")).toHaveTextContent(en.Settlement.attachmentDeleteFailed);
    });

    it("has both messages in Arabic", () => {
        expect(ar.Settlement.attachmentUploadFailed).toContain("{name}");
        expect(ar.Settlement.attachmentDeleteFailed).toBeTruthy();
    });
});
