import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";
import type { Cheque } from "@/lib/api/leasing";
import type { ChequeAction } from "../ChequeActionDialog";

/**
 * The lease page's own single-row dialog. Two things it used to get wrong:
 * it seeded a replacement with the bounced cheque's own number, which
 * `ChequeService.takenNumbers` (:1096-1104) counts as taken whatever the row's
 * status, and it gated Replace on the amount alone, so a row with no cheque
 * date went out and came back a 400.
 */

vi.mock("@/components/finance/AccountPicker", () => ({ default: () => <div data-testid="account-picker" /> }));

const api = vi.hoisted(() => ({ replace: vi.fn(), releaseOnline: vi.fn() }));

vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return { ...m, chequeApi: { ...m.chequeApi, replace: api.replace, releaseOnline: api.releaseOnline } };
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

function renderDialog(action: ChequeAction, over: Partial<Cheque> = {}) {
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
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

describe("ChequeActionDialog — release online", () => {
    it("releases an abandoned gateway session back onto the register", async () => {
        api.releaseOnline.mockResolvedValueOnce(cheque({ status: "REGISTERED" }));
        renderDialog("releaseOnline", { status: "ONLINE_PENDING", mode: "ONLINE", chequeNumber: null });

        expect(screen.getByTestId("release-online-hint")).toHaveTextContent("puts the cheque back on the register");
        fireEvent.click(screen.getByTestId("cheque-releaseOnline-confirm"));

        await waitFor(() => expect(api.releaseOnline).toHaveBeenCalledWith("c1", expect.objectContaining({ notes: null })));
    });
});
