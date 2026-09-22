import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../../messages/en.json";
import type { Cheque, LeaseDetail, TerminationPreview } from "@/lib/api/leasing";

/**
 * Terminating a contract (spec §9.1).
 *
 * Three rules are pinned here, each mirroring a Java one:
 *
 *  - the two cheque lists are a decision, not a filter — `POST /terminate`
 *    refuses a body that leaves an uncleared row out of both
 *    (`LeaseTerminationService.chosenReturns`:279-315), so flipping one row
 *    must still send all of them;
 *  - `receivableAfter` is the server's figure for the server's default split,
 *    and the screen recomputes it as rows are flipped;
 *  - a PROPERTY_MANAGER may price a termination and may not perform one
 *    (`LeaseController#previewTermination` :273-274 admits them,
 *    `#terminateLease` :287-288 does not).
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
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role } } }) }));

const api = vi.hoisted(() => ({
    get: vi.fn(),
    preview: vi.fn(),
    terminate: vi.fn(),
    fiscal: vi.fn(),
}));

vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return {
        ...m,
        leaseApi: { ...m.leaseApi, get: api.get },
        terminationApi: { ...m.terminationApi, preview: api.preview, terminate: api.terminate },
    };
});
vi.mock("@/lib/api/ledger", async orig => {
    const m = await orig<typeof import("@/lib/api/ledger")>();
    return { ...m, ledgerApi: { ...m.ledgerApi, fiscal: { ...m.ledgerApi.fiscal, get: api.fiscal } } };
});

import TerminateLeasePage from "../page";

function cheque(over: Partial<Cheque> & { id: string; seqNo: number; amount: number }): Cheque {
    return {
        leaseId: "lease-1", propertyId: "p1", unitId: "u1", renterId: "r1",
        propertyName: "L'Olivier", unitIdentifier: "204", renterName: "Prabhjot Singh",
        postingDate: "2026-01-01", chequeNumber: "000101", chequeDate: "2026-06-01",
        payeeBank: "ENBD", payerName: "Prabhjot Singh", debitAccountId: null, debitAccountName: null,
        narration: null, mode: "PDC", status: "REGISTERED", failureReason: null,
        replacesId: null, replacedById: null, imageUrl: null, depositedAt: null, clearedAt: null,
        bouncedAt: null, returnedAt: null, pdrJournalId: null, crtJournalId: null, cbrJournalId: null,
        penaltyAssessmentId: null, due: false, overdue: false, daysOverdue: 0,
        ...over,
    };
}

const LEASE: LeaseDetail = {
    id: "lease-1", unitId: "u1", renterId: "r1", unitIdentifier: "204", renterName: "Prabhjot Singh",
    startDate: "2026-01-01", endDate: "2026-12-31", status: "ACTIVE",
    rentAmount: 120000, depositAmount: 10000, ejariNumber: null, paymentTerms: 4,
    installmentDistribution: "UNIFORM", paymentMethod: "CHEQUE", depositPaymentMethod: "CHEQUE",
    paymentReferenceNumber: null, propertyId: "p1", propertyName: "L'Olivier", propertyCode: "OLV",
    hasContract: true, contractNumber: 15, displayContractNumber: "TCO-26/15",
    agreementDate: null, rentVatApplicable: false, contractDate: "2026-01-01", totalDays: 365,
    gracePeriodDays: 5, firstDueDate: "2026-01-01", renterAcceptedAt: null,
    renewedFromLeaseId: null, chainId: "chain-1", receivableAccountId: null, incomeAccountId: null,
    postingJournalId: "j0", postedAt: "2026-01-01T00:00:00Z", contractValue: 120000,
    terminatedOn: null, terminationJournalId: null, terminationNotes: null,
    lines: [],
};

const PREVIEW: TerminationPreview = {
    terminationDate: "2026-06-30",
    earnedRentThroughDate: 59835.62,
    recognisedSoFar: 49863.01,
    unearnedRent: 60164.38,
    unearnedVat: 0,
    chequesToKeep: [cheque({ id: "c2", seqNo: 2, amount: 30000, chequeDate: "2026-04-01" })],
    chequesToReturn: [
        cheque({ id: "c3", seqNo: 3, amount: 30000, chequeDate: "2026-07-01" }),
        cheque({ id: "c4", seqNo: 4, amount: 30000, chequeDate: "2026-10-01" }),
    ],
    bouncedOutstanding: [cheque({ id: "c1", seqNo: 1, amount: 30000, status: "BOUNCED", chequeDate: "2026-01-01" })],
    receivableAfter: 5000,
};

function renderPage() {
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <TerminateLeasePage />
        </NextIntlClientProvider>,
    );
}

beforeEach(() => {
    role = "ACCOUNTANT";
    push.mockClear();
    api.get.mockResolvedValue(LEASE);
    api.preview.mockResolvedValue(PREVIEW);
    api.terminate.mockResolvedValue({ ...LEASE, status: "TERMINATED", terminatedOn: "2026-06-30" });
    api.fiscal.mockResolvedValue({ fiscalYearStartMonth: 1, booksStartDate: null, booksLockedThrough: null });
});

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

describe("Termination page", () => {
    it("shows the preview's default split, with bounced rows listed apart", async () => {
        renderPage();
        await waitFor(() => expect(screen.getByTestId("terminate-row-c2")).toBeInTheDocument());

        expect(screen.getByTestId("terminate-decision-c2")).toHaveAttribute("data-decision", "KEEP");
        expect(screen.getByTestId("terminate-keep-c2")).toHaveAttribute("aria-pressed", "true");
        expect(screen.getByTestId("terminate-decision-c3")).toHaveAttribute("data-decision", "RETURN");
        expect(screen.getByTestId("terminate-return-c3")).toHaveAttribute("aria-pressed", "true");
        // BOUNCED is not "uncleared", so it is neither returned nor kept and
        // must not be offered as a choice.
        expect(screen.queryByTestId("terminate-row-c1")).toBeNull();
        expect(screen.getByTestId("terminate-bounced-c1")).toBeInTheDocument();
    });

    it("recomputes the receivable client-side as a row is flipped from Return to Keep", async () => {
        renderPage();
        await waitFor(() => expect(screen.getByTestId("terminate-receivable-after")).toHaveTextContent("5,000.00"));

        fireEvent.click(screen.getByTestId("terminate-keep-c4"));

        // 5,000 − 30,000: the cheque is banked instead of handed back.
        expect(screen.getByTestId("terminate-receivable-after")).toHaveTextContent("-25,000.00");
        // No second round trip for a decision the client can price itself.
        expect(api.preview).toHaveBeenCalledTimes(1);
    });

    it("sends every uncleared row in exactly one list after a row is flipped", async () => {
        renderPage();
        await waitFor(() => expect(screen.getByTestId("terminate-row-c4")).toBeInTheDocument());

        fireEvent.click(screen.getByTestId("terminate-keep-c4"));
        fireEvent.change(screen.getByTestId("terminate-notes"), { target: { value: "Relocating" } });
        fireEvent.click(screen.getByTestId("terminate-submit"));
        fireEvent.click(await screen.findByTestId("terminate-confirm"));

        await waitFor(() =>
            expect(api.terminate).toHaveBeenCalledWith("lease-1", {
                terminationDate: "2026-06-30",
                returnChequeIds: ["c3"],
                keepChequeIds: ["c2", "c4"],
                notes: "Relocating",
            }),
        );
        await waitFor(() => expect(push).toHaveBeenCalledWith("/dashboard/leases/lease-1/settlement"));
    });

    it("lets a PROPERTY_MANAGER price the termination but never offers the button", async () => {
        role = "PROPERTY_MANAGER";
        renderPage();

        await waitFor(() => expect(screen.getByTestId("terminate-receivable-after")).toBeInTheDocument());
        expect(screen.getByTestId("terminate-preview-only")).toBeInTheDocument();
        expect(screen.queryByTestId("terminate-submit")).toBeNull();
    });

    it("refuses a contract that is not ACTIVE or NOTICE_GIVEN, without pricing it", async () => {
        api.get.mockResolvedValue({ ...LEASE, status: "TERMINATED" });
        renderPage();

        // The status reaches the reader as its label, not as the Java enum —
        // in Arabic the raw token was the only Latin text in the sentence.
        expect(await screen.findByTestId("terminate-not-terminable")).toHaveTextContent("This one is Terminated.");
        expect(screen.queryByTestId("terminate-submit")).toBeNull();
        expect(api.preview).not.toHaveBeenCalled();
    });

    it("keeps the date inside the contract term — the server's own bounds", async () => {
        renderPage();
        const date = await screen.findByTestId("terminate-date");
        expect(date).toHaveAttribute("min", "2026-01-01");
        expect(date).toHaveAttribute("max", "2026-12-31");
    });

    it("ignores a pricing that the user's edit has already superseded", async () => {
        // The page opens priced for today and the user moves the date before
        // that answer lands. The two responses come back out of order.
        let releaseFirst: (p: TerminationPreview) => void = () => {};
        api.preview
            .mockImplementationOnce(() => new Promise<TerminationPreview>(res => { releaseFirst = res; }))
            .mockResolvedValueOnce({ ...PREVIEW, terminationDate: "2026-06-15", receivableAfter: 7000 });

        renderPage();
        const date = await screen.findByTestId("terminate-date");
        fireEvent.change(date, { target: { value: "2026-06-15" } });

        await waitFor(() => expect(screen.getByTestId("terminate-receivable-after")).toHaveTextContent("7,000.00"));

        // …and only now does the first one arrive.
        releaseFirst({ ...PREVIEW, terminationDate: "2026-06-30", receivableAfter: 5000 });
        await waitFor(() => expect(api.preview).toHaveBeenCalledTimes(2));

        // It must not land: the confirm posts `preview.terminationDate`, so a
        // stale answer here ends the contract on the date the user moved off.
        expect(screen.getByTestId("terminate-receivable-after")).toHaveTextContent("7,000.00");

        fireEvent.click(screen.getByTestId("terminate-submit"));
        fireEvent.click(await screen.findByTestId("terminate-confirm"));
        await waitFor(() =>
            expect(api.terminate).toHaveBeenCalledWith(
                "lease-1",
                expect.objectContaining({ terminationDate: "2026-06-15" }),
            ),
        );
    });

    it("names the back arrow, which was icon-only", async () => {
        renderPage();
        expect(await screen.findByTestId("terminate-back")).toHaveAccessibleName("Back to contract");
    });

    it("translates the instrument's mode rather than printing the enum", async () => {
        renderPage();
        const row = await screen.findByTestId("terminate-row-c2");
        expect(row).toHaveTextContent("Post-Dated Cheque");
        expect(row).not.toHaveTextContent("PDC");
    });
});

/**
 * `TerminationPreviewDTO.unearnedVat` — the VAT on the unearned rent, which the
 * same TCR credits back as a credit note (Dr OUTPUT_VAT / Cr RENT_RECEIVABLE).
 * Zero on a residential tenancy, so it earns a card only when there is one.
 * `receivableAfter` already has it netted in (`LeaseTerminationService` :155,
 * :414-422), which is why the flip arithmetic does not touch it.
 */
describe("Unearned VAT", () => {
    it("gets its own card on a commercial tenancy", async () => {
        api.preview.mockResolvedValue({ ...PREVIEW, unearnedVat: 3008.22 });
        renderPage();
        expect(await screen.findByTestId("terminate-unearned-vat")).toHaveTextContent("3,008.22");
    });

    it("is not shown at all when it is zero", async () => {
        renderPage();
        await screen.findByTestId("terminate-unearned");
        expect(screen.queryByTestId("terminate-unearned-vat")).toBeNull();
    });

    it("is absent from an older server's answer and read as zero, not as NaN", async () => {
        const withoutVat: TerminationPreview = { ...PREVIEW };
        delete withoutVat.unearnedVat;
        api.preview.mockResolvedValue(withoutVat);
        renderPage();
        await screen.findByTestId("terminate-unearned");
        expect(screen.queryByTestId("terminate-unearned-vat")).toBeNull();
        expect(screen.getByTestId("terminate-receivable-after")).toHaveTextContent("5,000.00");
    });
});
