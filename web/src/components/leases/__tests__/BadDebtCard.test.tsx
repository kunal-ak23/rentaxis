import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";

import ar from "../../../../messages/ar.json";
import en from "../../../../messages/en.json";

const api = vi.hoisted(() => ({ candidates: vi.fn(), forLease: vi.fn(), propose: vi.fn(), approve: vi.fn(), reject: vi.fn(), reverse: vi.fn(), recover: vi.fn(), recoveryAccounts: vi.fn() }));
vi.mock("@/lib/api/badDebts", () => ({ badDebtsApi: api }));
import { ApiError } from "@/lib/api/facilities";

import BadDebtCard from "../BadDebtCard";

/** F14-38: pick open items, propose; an admin approves through the confirm dialog; VAT note in AR. */
const item = { chequeId: "c1", seqNo: 2, chequeNumber: null, date: "2026-08-01", amount: 15000, status: "REGISTERED", mode: "CASH", narration: null };
const proposed = { id: "w1", leaseId: "L", renterId: null, amount: 15000, writeOffDate: "2026-09-01", reason: "gone", status: "PROPOSED",
    vatLease: true, itemIds: ["c1"], proposedAt: "", decidedAt: null, decisionNote: null, journalId: null, reversalJournalId: null, recovered: 0, recoveries: [] };

function renderIn(locale: "en" | "ar", canApprove = true) {
    return render(
        <NextIntlClientProvider locale={locale} messages={locale === "ar" ? ar : en}>
            <BadDebtCard leaseId="L" canApprove={canApprove} />
        </NextIntlClientProvider>,
    );
}

describe("BadDebtCard", () => {
    afterEach(() => { cleanup(); vi.clearAllMocks(); });

    it("proposes the picked items with a reason", async () => {
        api.candidates.mockResolvedValue([item]);
        api.forLease.mockResolvedValue([]);
        api.propose.mockResolvedValue(proposed);
        renderIn("en");
        fireEvent.click(await screen.findByTestId("bd-item-2"));
        fireEvent.change(screen.getByTestId("bd-reason"), { target: { value: "Renter absconded" } });
        fireEvent.click(screen.getByTestId("bd-propose"));
        await waitFor(() => expect(api.propose).toHaveBeenCalled());
        expect(api.propose.mock.calls[0][0]).toMatchObject({ leaseId: "L", chequeIds: ["c1"], reason: "Renter absconded" });
    });

    it("lets an admin approve through the confirm dialog and shows the VAT note in Arabic", async () => {
        api.candidates.mockResolvedValue([]);
        api.forLease.mockResolvedValue([proposed]);
        api.approve.mockResolvedValue({ ...proposed, status: "WRITTEN_OFF" });
        renderIn("ar");
        expect(await screen.findByText(/إعفاء الديون المعدومة ليس تلقائيًا/)).toBeTruthy();
        fireEvent.click(screen.getByTestId("bd-approve"));
        fireEvent.click(await screen.findByTestId("bd-confirm"));
        // F15-22: the note is optional on an approval.
        await waitFor(() => expect(api.approve).toHaveBeenCalledWith("w1", undefined));
    });

    it("shows dates as dd/mm/yyyy and the BDW / BDR numbers (F15-16, F15-22)", async () => {
        api.candidates.mockResolvedValue([item]);
        api.forLease.mockResolvedValue([{ ...proposed, status: "WRITTEN_OFF", journalNumber: "BDW-26/1", recovered: 100,
            recoveries: [{ id: "r1", amount: 100, recoveredOn: "2026-09-20", accountId: "a", note: null, journalId: "j", journalNumber: "BDR-26/1" }] }]);
        renderIn("en");
        expect(await screen.findByText("BDW-26/1")).toBeTruthy();
        expect(screen.getByText("BDR-26/1")).toBeTruthy();
        expect(screen.getByText("01/08/2026")).toBeTruthy();
        expect(screen.getByText("01/09/2026")).toBeTruthy();
        expect(screen.queryByText("2026-08-01")).toBeNull();
    });

    it("offers only the lease property's accounts, labels the fields and keeps a refusal in the dialog (F15-21, F15-22)", async () => {
        api.candidates.mockResolvedValue([]);
        api.forLease.mockResolvedValue([{ ...proposed, status: "WRITTEN_OFF", journalNumber: "BDW-26/1" }]);
        api.recoveryAccounts.mockResolvedValue([
            { id: "b1", code: "100005", name: "Emirates Islamic", nameAr: null, kind: "BANK", bankAccount: "EI 0012" },
        ]);
        api.recover.mockRejectedValue(new ApiError(422, "Only 5,234.25 of this write-off is left to recover.",
            JSON.stringify({ code: "badDebt.recoveryTooMuch", args: { left: "5,234.25" } })));
        renderIn("en");
        fireEvent.click(await screen.findByText("Record recovery"));
        await waitFor(() => expect(api.recoveryAccounts).toHaveBeenCalledWith("w1"));
        expect(screen.getByLabelText("Amount")).toBeTruthy();
        expect(screen.getByLabelText("Date")).toBeTruthy();
        const account = screen.getByLabelText("Bank or cash account") as HTMLSelectElement;
        await waitFor(() => expect(account.options).toHaveLength(2));
        fireEvent.change(account, { target: { value: "b1" } });
        fireEvent.click(screen.getByTestId("bd-confirm"));
        const inDialog = await screen.findByTestId("bd-dialog-error");
        expect(inDialog.textContent).toContain("5,234.25");
        expect(screen.getByTestId("bd-confirm")).toBeTruthy();
    });

    it("offers no decision to finance without the admin role", async () => {
        api.candidates.mockResolvedValue([]);
        api.forLease.mockResolvedValue([proposed]);
        renderIn("en", false);
        await screen.findByTestId("bd-list");
        expect(screen.queryByTestId("bd-approve")).toBeNull();
    });
});
