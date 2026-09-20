import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Account } from "@/lib/api/ledger";

/**
 * A debit account on a cheque is a settlement account, and
 * `ChequeService.requireSettlementAccount` (ChequeService.java:1083-1093)
 * accepts exactly one shape: an active, non-group ASSET leaf whose sub-type is
 * BANK or CASH. Everything else 400s with "Debit account 410200 must be a bank
 * or cash account".
 *
 * Until this wrapper existed the seven debit-account pickers passed only
 * `leafOnly`, so every income, liability and expense leaf was offered for a
 * field that can only ever hold a bank or a cash account.
 */

const list = vi.fn();

vi.mock("@/lib/api/ledger", async orig => {
    const m = await orig<typeof import("@/lib/api/ledger")>();
    return { ...m, ledgerApi: { ...m.ledgerApi, accounts: { ...m.ledgerApi.accounts, list: () => list() } } };
});

import SettlementAccountPicker from "../SettlementAccountPicker";
import { invalidateAccounts } from "../AccountPicker";

function account(over: Partial<Account> & { id: string; code: string; name: string }): Account {
    return {
        nameEn: null, nameAr: null, alias: null,
        accountType: "ASSET", accountSubType: "BANK",
        parentId: null, propertyId: null,
        system: false, group: false, active: true, displayOrder: 0, description: null,
        ...over,
    };
}

const ACCOUNTS: Account[] = [
    account({ id: "a1", code: "102100", name: "Bank account" }),
    account({ id: "a2", code: "101100", name: "Cash account", accountSubType: "CASH" }),
    account({ id: "a3", code: "410200", name: "Rent income account", accountType: "INCOME", accountSubType: null }),
    account({ id: "a4", code: "103100", name: "Receivable account", accountSubType: "RECEIVABLE" }),
    account({ id: "a5", code: "102900", name: "Closed bank account", active: false }),
    account({ id: "a6", code: "102000", name: "Banks group account", group: true }),
];

beforeEach(() => {
    invalidateAccounts();
    list.mockResolvedValue(ACCOUNTS);
});

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

describe("SettlementAccountPicker", () => {
    it("offers only active ASSET bank and cash leaves", async () => {
        render(<SettlementAccountPicker value={null} onChange={vi.fn()} placeholder="Debit Account" />);
        await waitFor(() => expect(list).toHaveBeenCalled());

        fireEvent.change(screen.getByLabelText("Debit Account"), { target: { value: "account" } });

        await waitFor(() => expect(screen.getByText("Bank account")).toBeInTheDocument());
        expect(screen.getByText("Cash account")).toBeInTheDocument();
        // The server refuses each of these with "must be a bank or cash account".
        expect(screen.queryByText("Rent income account")).not.toBeInTheDocument();
        expect(screen.queryByText("Receivable account")).not.toBeInTheDocument();
        expect(screen.queryByText("Closed bank account")).not.toBeInTheDocument();
        expect(screen.queryByText("Banks group account")).not.toBeInTheDocument();
    });

    it("reports the picked account to its caller", async () => {
        const onChange = vi.fn();
        render(<SettlementAccountPicker value={null} onChange={onChange} placeholder="Debit Account" />);
        await waitFor(() => expect(list).toHaveBeenCalled());

        fireEvent.change(screen.getByLabelText("Debit Account"), { target: { value: "Cash" } });
        await waitFor(() => expect(screen.getByText("Cash account")).toBeInTheDocument());
        fireEvent.click(screen.getByText("Cash account"));

        expect(onChange).toHaveBeenCalledWith("a2");
    });
});
