import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";
import ar from "../../../../messages/ar.json";
import type { VatTaxPoint } from "@/lib/api/leasing";
import type { Cheque } from "@/lib/api/leasing";
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
    fiscal: vi.fn(), defaultsGet: vi.fn(),
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
        chequeApi: { ...m.chequeApi, replace: api.replace, releaseOnline: api.releaseOnline, cancel: api.cancel, receive: api.receive },
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
        due: false, overdue: false, daysOverdue: 0,
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
 * F14-17: a Receive on a CASH instalment used to post straight to the bank
 * leaf the row was generated with, whatever the accountant actually did with
 * the cash. It now offers a "Received into" picker, defaulting a CASH row to
 * the tenant's CASH role account and sending whatever the field ends up with.
 */
describe("ChequeActionDialog — receive account (F14-17)", () => {
    it("defaults a CASH row to the tenant's CASH default account and sends it", async () => {
        api.defaultsGet.mockResolvedValue([
            { role: "CASH", accountId: "acc-cash", accountCode: "410300", accountName: "Cash in hand", inherited: false },
        ]);
        api.receive.mockResolvedValue({});
        renderDialog("receive", { mode: "CASH", debitAccountId: "acc-1" });

        await waitFor(() => expect(api.defaultsGet).toHaveBeenCalled());
        await waitFor(() => expect((screen.getByTestId("account-picker") as HTMLInputElement).value).toBe("acc-cash"));

        fireEvent.click(screen.getByTestId("cheque-receive-confirm"));
        await waitFor(() => expect(api.receive).toHaveBeenCalledTimes(1));
        expect(api.receive).toHaveBeenCalledWith("c1", expect.objectContaining({ debitAccountId: "acc-cash" }));
    });

    it("sends whatever account the operator picks instead of the default", async () => {
        api.defaultsGet.mockResolvedValue([{ role: "CASH", accountId: "acc-cash", accountCode: "410300", accountName: "Cash in hand", inherited: false }]);
        api.receive.mockResolvedValue({});
        renderDialog("receive", { mode: "CASH", debitAccountId: "acc-1" });

        await waitFor(() => expect((screen.getByTestId("account-picker") as HTMLInputElement).value).toBe("acc-cash"));
        fireEvent.change(screen.getByTestId("account-picker"), { target: { value: "acc-manual" } });

        fireEvent.click(screen.getByTestId("cheque-receive-confirm"));
        await waitFor(() => expect(api.receive).toHaveBeenCalledTimes(1));
        expect(api.receive).toHaveBeenCalledWith("c1", expect.objectContaining({ debitAccountId: "acc-manual" }));
    });

    it("falls back to the row's own debit account when the chart has no CASH default", async () => {
        api.defaultsGet.mockResolvedValue([]);
        api.receive.mockResolvedValue({});
        renderDialog("receive", { mode: "CASH", debitAccountId: "acc-1" });

        await waitFor(() => expect(api.defaultsGet).toHaveBeenCalled());
        fireEvent.click(screen.getByTestId("cheque-receive-confirm"));
        await waitFor(() => expect(api.receive).toHaveBeenCalledTimes(1));
        // The row's own account is left to the server (F14-16 may move an orphan bank leaf).
        expect(api.receive).toHaveBeenCalledWith("c1", expect.objectContaining({ debitAccountId: null }));
    });

    it("defaults a TRANSFER row to its own current debit account, not the CASH default", async () => {
        api.receive.mockResolvedValue({});
        renderDialog("receive", { mode: "TRANSFER", debitAccountId: "acc-bank-7" });

        expect((screen.getByTestId("account-picker") as HTMLInputElement).value).toBe("acc-bank-7");
        expect(api.defaultsGet).not.toHaveBeenCalled();

        fireEvent.click(screen.getByTestId("cheque-receive-confirm"));
        await waitFor(() => expect(api.receive).toHaveBeenCalledTimes(1));
        expect(api.receive).toHaveBeenCalledWith("c1", expect.objectContaining({ debitAccountId: null }));
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
