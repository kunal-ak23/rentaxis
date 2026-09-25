import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";

import ar from "../../../../messages/ar.json";
import en from "../../../../messages/en.json";

const api = vi.hoisted(() => ({ candidates: vi.fn(), forLease: vi.fn(), propose: vi.fn(), approve: vi.fn(), reject: vi.fn(), reverse: vi.fn(), recover: vi.fn() }));
vi.mock("@/lib/api/badDebts", () => ({ badDebtsApi: api }));

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
        await waitFor(() => expect(api.approve).toHaveBeenCalledWith("w1", ""));
    });

    it("offers no decision to finance without the admin role", async () => {
        api.candidates.mockResolvedValue([]);
        api.forLease.mockResolvedValue([proposed]);
        renderIn("en", false);
        await screen.findByTestId("bd-list");
        expect(screen.queryByTestId("bd-approve")).toBeNull();
    });
});
