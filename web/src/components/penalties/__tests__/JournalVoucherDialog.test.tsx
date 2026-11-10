import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";

/**
 * "Add journal voucher" on a contract: an income account, an amount and what it
 * is for are all required, and one confirm posts the JV through
 * `POST /penalties/lease/{id}/journal-voucher`.
 */

vi.mock("@/components/finance/AccountPicker", () => ({
    default: ({ onChange, accountType }: { onChange: (id: string) => void; accountType?: string }) => (
        <button type="button" data-testid="pick-account" data-type={accountType} onClick={() => onChange("acc-parking")}>pick</button>
    ),
}));

const journalVoucher = vi.hoisted(() => vi.fn());
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return { ...m, penaltyApi: { ...m.penaltyApi, journalVoucher } };
});

import JournalVoucherDialog from "../JournalVoucherDialog";

afterEach(() => { cleanup(); vi.clearAllMocks(); });

function renderDialog(onPosted = vi.fn()) {
    render(
        <NextIntlClientProvider locale="en" messages={en}>
            <JournalVoucherDialog open leaseId="lease-1" propertyId="p1" onClose={vi.fn()} onPosted={onPosted} />
        </NextIntlClientProvider>,
    );
    return onPosted;
}

describe("JournalVoucherDialog", () => {
    it("offers income accounts only and stays disabled until account, amount and narration are given", () => {
        renderDialog();
        expect(screen.getByTestId("pick-account")).toHaveAttribute("data-type", "INCOME");
        const confirm = screen.getByTestId("journal-voucher-confirm");
        expect(confirm).toBeDisabled();

        fireEvent.click(screen.getByTestId("pick-account"));
        fireEvent.change(screen.getByLabelText(en.Leasing.amount), { target: { value: "300" } });
        expect(confirm).toBeDisabled();
        fireEvent.change(screen.getByLabelText(en.Leasing.narration), { target: { value: "Extra parking bay B2" } });
        expect(confirm).toBeEnabled();
    });

    it("posts the voucher for this lease and hands back the result", async () => {
        const posted = { id: "pa-1", reason: "JOURNAL_VOUCHER" };
        journalVoucher.mockResolvedValue(posted);
        const onPosted = renderDialog();

        fireEvent.click(screen.getByTestId("pick-account"));
        fireEvent.change(screen.getByLabelText(en.Leasing.amount), { target: { value: "300" } });
        fireEvent.change(screen.getByLabelText(en.Leasing.narration), { target: { value: "  Extra parking bay B2  " } });
        fireEvent.click(screen.getByTestId("journal-voucher-confirm"));

        await waitFor(() => expect(onPosted).toHaveBeenCalledWith(posted));
        expect(journalVoucher).toHaveBeenCalledWith("lease-1", expect.objectContaining({
            accountId: "acc-parking", amount: 300, narration: "Extra parking bay B2", vatable: false,
        }));
    });
});
