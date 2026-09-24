import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";
import ar from "../../../../messages/ar.json";
import type { VatTaxPoint } from "@/lib/api/leasing";
import type { Cheque } from "@/lib/api/leasing";
import { ApiError } from "@/lib/api/leasing";
import type { ChequeAction } from "../ChequeActionDialog";

/**
 * The lease page's own single-row dialog. Two things it used to get wrong:
 * it seeded a replacement with the bounced cheque's own number, which
 * `ChequeService.takenNumbers` (:1096-1104) counts as taken whatever the row's
 * status, and it gated Replace on the amount alone, so a row with no cheque
 * date went out and came back a 400.
 */

// A real select so the receive-account tests can read the default it was
// given and change it; the other describes here only check it renders.
vi.mock("@/components/finance/AccountPicker", () => ({
    default: ({ value, onChange }: { value: string | null; onChange: (id: string) => void }) => (
        <input data-testid="account-picker" value={value ?? ""} onChange={e => onChange(e.target.value)} />
    ),
}));

const api = vi.hoisted(() => ({
    replace: vi.fn(), releaseOnline: vi.fn(), cancel: vi.fn(), receive: vi.fn(), schedule: vi.fn(), leaseCheques: vi.fn(),
    fiscal: vi.fn(), defaultsGet: vi.fn(), settlementTarget: vi.fn(),
}));

vi.mock("@/lib/api/ledger", async orig => {
    const m = await orig<typeof import("@/lib/api/ledger")>();
    return {
        ...m,
        ledgerApi: {
            ...m.ledgerApi,
            fiscal: { ...m.ledgerApi.fiscal, get: api.fiscal },
            defaults: { ...m.ledgerApi.defaults, get: api.defaultsGet },
        },
    };
});

vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return {
        ...m,
        chequeApi: { ...m.chequeApi, replace: api.replace, releaseOnline: api.releaseOnline, cancel: api.cancel, receive: api.receive, settlementTarget: api.settlementTarget },
        vatApi: { ...m.vatApi, schedule: api.schedule },
        leaseApi: { ...m.leaseApi, cheques: api.leaseCheques },
    };
});

import ChequeActionDialog from "../ChequeActionDialog";

function cheque(over: Partial<Cheque> = {}): Cheque {
    return {
        id: "c1", leaseId: "l1", propertyId: "p1", unitId: "u1", renterId: "r1",
        propertyName: "L'Olivier", unitIdentifier: "A-101", renterName: "Prabhjot Singh",
        seqNo: 3, postingDate: "2026-06-01", chequeNumber: "100041", chequeDate: "2026-06-01",
        payeeBank: "ENBD", payerName: null, debitAccountId: "acc-1", debitAccountName: "Bank",
        amount: 13700, narration: null, mode: "PDC", status: "BOUNCED",
        failureReason: "BOUNCE", replacesId: null, replacedById: null, imageUrl: null,
        depositedAt: null, clearedAt: null, bouncedAt: "2026-06-05", returnedAt: null,
        pdrJournalId: null, crtJournalId: null, cbrJournalId: null, penaltyAssessmentId: null,
        due: false, overdue: false, daysOverdue: 0, ledgerSettled: false,
        ...over,
    };
}

function renderDialog(action: ChequeAction, over: Partial<Cheque> = {}, locale: "en" | "ar" = "en") {
    return render(
        <NextIntlClientProvider locale={locale} messages={locale === "en" ? en : ar}>
            <ChequeActionDialog
                action={action}
                cheque={cheque(over)}
                propertyId="p1"
                onClose={() => {}}
                onDone={() => {}}
            />
        </NextIntlClientProvider>,
    );
}

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

beforeEach(() => {
    api.settlementTarget.mockResolvedValue({ target: null, options: [] });
    api.fiscal.mockResolvedValue({ fiscalYearStartMonth: 1, booksStartDate: null, booksLockedThrough: null });
    api.defaultsGet.mockResolvedValue([]);
});

describe("ChequeActionDialog — replace", () => {
    it("starts the replacement without the bounced cheque's number", () => {
        renderDialog("replace");
        expect((screen.getByTestId("cheque-detail-number") as HTMLInputElement).value).toBe("");
    });

    it("still shows the cheque's own number when correcting its details", () => {
        renderDialog("details");
        expect((screen.getByTestId("cheque-detail-number") as HTMLInputElement).value).toBe("100041");
    });

    it("blocks Replace while the row is one ChequeRowRules would refuse", () => {
        renderDialog("replace");
        const confirm = screen.getByTestId("cheque-replace-confirm");
        expect(confirm).toBeEnabled();

        fireEvent.change(screen.getByLabelText("Date"), { target: { value: "" } });
        expect(confirm).toBeDisabled();

        fireEvent.change(screen.getByLabelText("Date"), { target: { value: "2026-08-01" } });
        expect(confirm).toBeEnabled();

        fireEvent.change(screen.getByLabelText("Amount"), { target: { value: "0" } });
        expect(confirm).toBeDisabled();
    });

    it("sends the row it showed", async () => {
        api.replace.mockResolvedValueOnce([]);
        renderDialog("replace");
        fireEvent.change(screen.getByTestId("cheque-detail-number"), { target: { value: "100099" } });
        fireEvent.click(screen.getByTestId("cheque-replace-confirm"));

        await waitFor(() => expect(api.replace).toHaveBeenCalled());
        expect(api.replace).toHaveBeenCalledWith("c1", expect.objectContaining({
            replacements: [expect.objectContaining({ chequeNumber: "100099", chequeDate: "2026-06-01", mode: "PDC" })],
        }));
    });
});

describe("ChequeActionDialog — title (#43)", () => {
    it("does not fall back to #seqNo for a numberless CASH row", () => {
        renderDialog("receive", { mode: "CASH", chequeNumber: null, seqNo: 6 });
        const title = screen.getByRole("heading", { level: 3 }).textContent ?? "";
        expect(title).not.toContain("#");
        // No empty label segment: "Receive · 1,000.00", not "Receive — — · 1,000.00".
        expect(title).not.toContain("—");
        expect(title).toMatch(/ · /);
    });
});

describe("ChequeActionDialog — release online", () => {
    it("releases an abandoned gateway session back onto the register", async () => {
        api.releaseOnline.mockResolvedValueOnce(cheque({ status: "REGISTERED" }));
        renderDialog("releaseOnline", { status: "ONLINE_PENDING", mode: "ONLINE", chequeNumber: null });

        expect(screen.getByTestId("release-online-hint")).toHaveTextContent("puts the cheque back on the register");
        fireEvent.click(screen.getByTestId("cheque-releaseOnline-confirm"));

        await waitFor(() => expect(api.releaseOnline).toHaveBeenCalledWith("c1", expect.objectContaining({ notes: null })));
    });
});

/**
 * F14-17 + R1 P2-2/P2-3: "Received into" is the server's settlement target (what a
 * post with no override lands in) and the options any staff role may pick; the
 * account shown is the account sent, and a failed lookup is shown, not swallowed.
 */
describe("ChequeActionDialog — receive account (F14-17)", () => {
    const CASH = { id: "acc-cash", code: "A-02-05-001", name: "Cash in hand", nameAr: null, kind: "CASH" as const, bankAccount: null };
    const BANK = { id: "acc-bank", code: "A-02-02-001", name: "Emirates Islamic", nameAr: null, kind: "BANK" as const, bankAccount: "Emirates Islamic 0012" };

    it("shows the server's target for a CASH row and sends it", async () => {
        api.settlementTarget.mockResolvedValue({ target: CASH, options: [CASH, BANK] });
        api.receive.mockResolvedValue({});
        renderDialog("receive", { mode: "CASH", debitAccountId: "acc-1" });

        await waitFor(() => expect((screen.getByTestId("cheque-receive-account") as HTMLSelectElement).value).toBe("acc-cash"));
        fireEvent.click(screen.getByTestId("cheque-receive-confirm"));
        await waitFor(() => expect(api.receive).toHaveBeenCalledTimes(1));
        expect(api.receive).toHaveBeenCalledWith("c1", expect.objectContaining({ debitAccountId: "acc-cash" }));
    });

    it("sends the account the operator picks instead", async () => {
        api.settlementTarget.mockResolvedValue({ target: CASH, options: [CASH, BANK] });
        api.receive.mockResolvedValue({});
        renderDialog("receive", { mode: "CASH", debitAccountId: "acc-1" });

        await waitFor(() => expect((screen.getByTestId("cheque-receive-account") as HTMLSelectElement).value).toBe("acc-cash"));
        fireEvent.change(screen.getByTestId("cheque-receive-account"), { target: { value: "acc-bank" } });
        fireEvent.click(screen.getByTestId("cheque-receive-confirm"));
        await waitFor(() => expect(api.receive).toHaveBeenCalledTimes(1));
        expect(api.receive).toHaveBeenCalledWith("c1", expect.objectContaining({ debitAccountId: "acc-bank" }));
    });

    it("shows a transfer row's resolved bank leaf, not the row's stamped one", async () => {
        api.settlementTarget.mockResolvedValue({ target: BANK, options: [CASH, BANK] });
        api.receive.mockResolvedValue({});
        renderDialog("receive", { mode: "TRANSFER", debitAccountId: "acc-orphan" });

        await waitFor(() => expect((screen.getByTestId("cheque-receive-account") as HTMLSelectElement).value).toBe("acc-bank"));
        fireEvent.click(screen.getByTestId("cheque-receive-confirm"));
        await waitFor(() => expect(api.receive).toHaveBeenCalledTimes(1));
        expect(api.receive).toHaveBeenCalledWith("c1", expect.objectContaining({ debitAccountId: "acc-bank" }));
    });

    it("shows a failed lookup instead of swallowing it", async () => {
        api.settlementTarget.mockRejectedValue(new ApiError(403, "Access denied"));
        renderDialog("receive", { mode: "CASH", debitAccountId: "acc-1" });

        expect((await screen.findByTestId("cheque-settlement-error")).textContent).toContain("Access denied");
        // R2 N-3: nothing can be confirmed, so the row's stamped leaf is never sent.
        expect((screen.getByTestId("cheque-receive-confirm") as HTMLButtonElement).disabled).toBe(true);
        fireEvent.click(screen.getByTestId("cheque-receive-confirm"));
        expect(api.receive).not.toHaveBeenCalled();
    });

    it("keeps Receive disabled until the target has loaded", async () => {
        let resolve: (v: unknown) => void = () => {};
        api.settlementTarget.mockReturnValue(new Promise(r => { resolve = r; }));
        api.receive.mockResolvedValue({});
        renderDialog("receive", { mode: "CASH", debitAccountId: "acc-1" });
        expect((screen.getByTestId("cheque-receive-confirm") as HTMLButtonElement).disabled).toBe(true);
        resolve({ target: CASH, options: [CASH] });
        await waitFor(() => expect((screen.getByTestId("cheque-receive-confirm") as HTMLButtonElement).disabled).toBe(false));
    });
});

describe("ChequeActionDialog — a statement already covers this date (F14-20)", () => {
    it("shows the refusal with a checkbox, and resubmits with notOnStatement once it is ticked", async () => {
        const cash = { id: "acc-cash", code: "A-02-05-001", name: "Cash in hand", nameAr: null, kind: "CASH" as const, bankAccount: null };
        api.settlementTarget.mockResolvedValue({ target: cash, options: [cash] });
        const covered = new ApiError(400, "covered", JSON.stringify({
            code: "bank.statementCovers",
            args: { bank: "Emirates Islamic 0123", from: "01/09/2026", to: "30/09/2026", date: "15/09/2026" },
            message: "covered",
        }));
        api.receive.mockRejectedValueOnce(covered).mockResolvedValueOnce({});
        renderDialog("receive", { mode: "CASH", debitAccountId: "acc-1" });
        await waitFor(() => expect((screen.getByTestId("cheque-receive-confirm") as HTMLButtonElement).disabled).toBe(false));

        fireEvent.click(screen.getByTestId("cheque-receive-confirm"));
        expect(await screen.findByTestId("cheque-receive-notice")).toHaveTextContent("Emirates Islamic 0123");
        // Not shown as a generic error — the notice is the whole story.
        expect(screen.queryByTestId("cheque-action-error")).not.toBeInTheDocument();

        fireEvent.click(screen.getByTestId("cheque-receive-not-on-statement"));
        fireEvent.click(screen.getByTestId("cheque-receive-confirm"));
        await waitFor(() => expect(api.receive).toHaveBeenCalledTimes(2));
        expect(api.receive).toHaveBeenLastCalledWith("c1", expect.objectContaining({ notOnStatement: true }));
    });

    it.each([
        ["before", "06/07/2026", "is before the statement starts (03/09/2026)"],
        ["inside", "10/09/2026", "is inside the statement period (03/09/2026 to 24/09/2026)"],
    ])("says where a date %s the statement falls (F14-60)", async (when, date, text) => {
        const cash = { id: "acc-cash", code: "A-02-05-001", name: "Cash in hand", nameAr: null, kind: "CASH" as const, bankAccount: null };
        api.settlementTarget.mockResolvedValue({ target: cash, options: [cash] });
        api.receive.mockRejectedValueOnce(new ApiError(400, "covered", JSON.stringify({
            code: "bank.statementCovers",
            args: { bank: "Emirates Islamic 2001", from: "03/09/2026", to: "24/09/2026", date, when },
            message: "covered",
        })));
        renderDialog("receive", { mode: "CASH", debitAccountId: "acc-1" });
        await waitFor(() => expect((screen.getByTestId("cheque-receive-confirm") as HTMLButtonElement).disabled).toBe(false));
        fireEvent.click(screen.getByTestId("cheque-receive-confirm"));
        const notice = await screen.findByTestId("cheque-receive-notice");
        expect(notice).toHaveTextContent(`An entry dated ${date} ${text}`);
        if (when === "before") expect(notice).not.toHaveTextContent("inside");
    });
});

function point(chequeId: string, over: Partial<VatTaxPoint> = {}): VatTaxPoint {
    return {
        id: `vtp-${chequeId}`, leaseId: "l1", chequeId, chequeSeqNo: null, chequeNumber: null,
        propertyId: "p1", propertyName: null, unitNumber: null, kind: "INSTALMENT",
        taxPointDate: "2026-06-01", taxableAmount: 12000, vatAmount: 600, status: "PLANNED",
        journalId: null, journalNumber: null, invoiceId: null, invoiceNumber: null,
        ...over,
    };
}

/**
 * Cancelling a REGISTERED row whose VAT is not declared yet: the server refuses
 * without `?moveVatTo=` (VatTaxPointService.beforeCancel), so the dialog asks
 * which other pending instalment of the lease takes it — the next one by date
 * unless the user picks another.
 */
describe("ChequeActionDialog — cancel with pending VAT", () => {
    const own = cheque({ id: "c3", seqNo: 3, status: "REGISTERED", chequeDate: "2026-06-01", chequeNumber: "100043" });
    const rows = [
        cheque({ id: "c1", seqNo: 1, status: "CLEARED", chequeDate: "2026-01-01", chequeNumber: "100041" }),
        cheque({ id: "c2", seqNo: 2, status: "REGISTERED", chequeDate: "2026-03-01", chequeNumber: "100042" }),
        own,
        cheque({ id: "c5", seqNo: 5, status: "REGISTERED", chequeDate: "2026-12-01", chequeNumber: "100045" }),
        cheque({ id: "c4", seqNo: 4, status: "DEPOSITED", chequeDate: "2026-09-01", chequeNumber: "100044" }),
        // Declared already: VAT cannot move onto it.
        cheque({ id: "c6", seqNo: 6, status: "REGISTERED", chequeDate: "2026-07-01", chequeNumber: "100046" }),
    ];
    const schedule = [
        point("c1", { status: "POSTED" }), point("c2"), point("c3", { vatAmount: 650 }),
        point("c4"), point("c5"), point("c6", { status: "POSTED" }),
    ];

    it("offers the other pending rows, defaults to the next by date, and sends moveVatTo", async () => {
        api.schedule.mockResolvedValue(schedule);
        api.leaseCheques.mockResolvedValue(rows);
        api.cancel.mockResolvedValue({});
        renderDialog("cancel", { id: "c3", seqNo: 3, status: "REGISTERED", chequeDate: "2026-06-01", chequeNumber: "100043" });

        const picker = (await screen.findByTestId("cheque-move-vat-to")) as HTMLSelectElement;
        const offered = Array.from(picker.options).map(o => o.value);
        expect(offered).toEqual(["c2", "c4", "c5"]);
        expect(picker.value).toBe("c4");
        expect(screen.getByTestId("cheque-move-vat-hint")).toHaveTextContent("650.00");

        fireEvent.click(screen.getByTestId("cheque-cancel-confirm"));
        await waitFor(() => expect(api.cancel).toHaveBeenCalledTimes(1));
        expect(api.cancel.mock.calls[0][0]).toBe("c3");
        expect(api.cancel.mock.calls[0][2]).toBe("c4");
    });

    it("sends the row the user picks instead", async () => {
        api.schedule.mockResolvedValue(schedule);
        api.leaseCheques.mockResolvedValue(rows);
        api.cancel.mockResolvedValue({});
        renderDialog("cancel", { id: "c3", seqNo: 3, status: "REGISTERED", chequeDate: "2026-06-01" });

        const picker = await screen.findByTestId("cheque-move-vat-to");
        fireEvent.change(picker, { target: { value: "c2" } });
        fireEvent.click(screen.getByTestId("cheque-cancel-confirm"));
        await waitFor(() => expect(api.cancel).toHaveBeenCalledTimes(1));
        expect(api.cancel.mock.calls[0][2]).toBe("c2");
    });

    it("says so and blocks Cancel when no other instalment can take the VAT", async () => {
        api.schedule.mockResolvedValue([point("c3"), point("c1", { status: "POSTED" })]);
        api.leaseCheques.mockResolvedValue([rows[0], own]);
        renderDialog("cancel", { id: "c3", seqNo: 3, status: "REGISTERED", chequeDate: "2026-06-01" });

        expect(await screen.findByTestId("cheque-move-vat-none")).toHaveTextContent("600.00");
        expect(screen.getByTestId("cheque-cancel-confirm")).toBeDisabled();
    });

    it("asks nothing on a row with no undeclared VAT (a CONTRACT lease has no schedule)", async () => {
        api.schedule.mockResolvedValue([]);
        api.leaseCheques.mockResolvedValue(rows);
        api.cancel.mockResolvedValue({});
        renderDialog("cancel", { id: "c3", seqNo: 3, status: "REGISTERED" });

        await waitFor(() => expect(api.schedule).toHaveBeenCalled());
        expect(screen.queryByTestId("cheque-move-vat")).toBeNull();
        fireEvent.click(screen.getByTestId("cheque-cancel-confirm"));
        await waitFor(() => expect(api.cancel).toHaveBeenCalledTimes(1));
        expect(api.cancel.mock.calls[0][2]).toBeNull();
    });

    it("does not look a schedule up for a row that is not REGISTERED", () => {
        renderDialog("cancel", { status: "DEPOSITED" });
        expect(api.schedule).not.toHaveBeenCalled();
    });

    it("never offers a deposit row, nor one dated inside the locked period", async () => {
        api.schedule.mockResolvedValue(schedule);
        api.leaseCheques.mockResolvedValue([
            ...rows,
            cheque({ id: "d1", seqNo: 7, status: "REGISTERED", chequeDate: "2026-08-01", rowKind: "DEPOSIT" }),
        ]);
        api.fiscal.mockResolvedValue({ fiscalYearStartMonth: 1, booksStartDate: null, booksLockedThrough: "2026-03-31" });
        renderDialog("cancel", { id: "c3", seqNo: 3, status: "REGISTERED", chequeDate: "2026-06-01" });

        const picker = (await screen.findByTestId("cheque-move-vat-to")) as HTMLSelectElement;
        // c2 (01/03) is inside the lock; d1 is a deposit.
        expect(Array.from(picker.options).map(o => o.value)).toEqual(["c4", "c5"]);
    });

    it("reads in Arabic", async () => {
        api.schedule.mockResolvedValue(schedule);
        api.leaseCheques.mockResolvedValue(rows);
        renderDialog("cancel", { id: "c3", seqNo: 3, status: "REGISTERED", chequeDate: "2026-06-01" }, "ar");

        await screen.findByTestId("cheque-move-vat-to");
        expect(screen.getByText(ar.Cheques.moveVatTo)).toBeInTheDocument();
        expect(ar.Cheques.moveVatTo).not.toEqual(en.Cheques.moveVatTo);
    });
});
