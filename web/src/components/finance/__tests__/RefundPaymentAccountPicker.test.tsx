import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";
import RefundPaymentAccountPicker from "../RefundPaymentAccountPicker";

/**
 * "Pay from" on a deposit-refund voucher follows the server's own rule
 * (`VoucherService` refund checks with `OwnedBankLeaf`): once the organisation has
 * set up a bank account in Bank Reconciliation, a bank leaf qualifies only if some
 * bank account owns it; before any has, every bank leaf does. Cash always does.
 *
 * Client report 2026-10-29: with no bank account set up, the picker offered cash
 * only while the voucher defaulted to "Bank transfer", so Post could never be enabled.
 */

const data = vi.hoisted(() => ({ accounts: [] as unknown[], banks: [] as unknown[] }));
vi.mock("../AccountPicker", () => ({ loadAccounts: () => Promise.resolve(data.accounts) }));
vi.mock("@/lib/api/bankRec", () => ({ bankRecApi: { accounts: () => Promise.resolve(data.banks) } }));

const leaf = (id: string, code: string, name: string, accountSubType: string, over: object = {}) => ({
    id, code, name, accountSubType, accountType: "ASSET", group: false, active: true, ...over,
});
const CASH = leaf("acc-cash", "A-02-05-001", "Cash Account", "CASH");
const TOWER_BANK = leaf("acc-bank-tower", "100003", "Bank - Residence Tower", "BANK");
const MARINA_BANK = leaf("acc-bank-marina", "100004", "Bank - Marina Heights", "BANK");
const INACTIVE_BANK = leaf("acc-bank-old", "100009", "Bank - closed", "BANK", { active: false });
const RECEIVABLE = leaf("acc-rr", "A-02-01-001", "Rent Receivable", "RECEIVABLE");

function renderPicker() {
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <RefundPaymentAccountPicker value={null} onChange={() => {}} />
        </NextIntlClientProvider>,
    );
}

const optionIds = () =>
    [...(screen.getByTestId("refund-payment-account") as HTMLSelectElement).options].map(o => o.value).filter(Boolean);

afterEach(cleanup);

describe("Pay from on a deposit refund", () => {
    it("offers every bank leaf and cash while no bank account is set up", async () => {
        data.accounts = [CASH, TOWER_BANK, MARINA_BANK, INACTIVE_BANK, RECEIVABLE];
        data.banks = [];
        renderPicker();
        await waitFor(() => expect(optionIds()).toEqual(["acc-cash", "acc-bank-tower", "acc-bank-marina"]));
    });

    it("offers only the bank leaves a bank account owns once one is set up", async () => {
        data.accounts = [CASH, TOWER_BANK, MARINA_BANK, RECEIVABLE];
        data.banks = [{ id: "b1", bankName: "Emirates NBD", leaves: [{ id: "acc-bank-tower" }] }];
        renderPicker();
        await waitFor(() => expect(optionIds()).toEqual(["acc-cash", "acc-bank-tower"]));
    });
});
