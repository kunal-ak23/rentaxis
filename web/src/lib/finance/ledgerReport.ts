// src/lib/finance/ledgerReport.ts
import { fmtBalance, type AccountLedger, type LedgerRow } from "@/lib/api/ledger";

export interface LedgerTotals { debit: number; credit: number; balance: number }
export interface LedgerGroup {
    accountId: string; code: string; ledger: AccountLedger;
    /** Balance brought forward: the account's balance the day before the period starts. */
    opening: number;
    rows: LedgerRow[]; subTotal: LedgerTotals; truncated: boolean;
}

const fils = (n: number) => Math.round(n * 100) / 100;

/**
 * PACT's ledger report (General Ledger, Tenant Ledger): one band per account,
 * the balance brought forward, its rows, a Sub Total, then a REPORT TOTAL.
 * Balances are debit-positive, so a Sub Total's balance is brought forward +
 * Σdebit − Σcredit. The endpoints return the brought-forward balance as
 * `openingBalance` (the balance before `from`), so no second call is needed.
 *
 * A truncated account (`truncated`: the server stopped at its row cap) is
 * partial either way — the server computes its totals over the same capped
 * rows (LedgerQueryService) — so its Sub Total covers the rows shown, and the
 * table says the rows were cut short.
 */
export function buildLedgerReport(ledgers: AccountLedger[]): { groups: LedgerGroup[]; total: LedgerTotals } {
    const groups = [...ledgers]
        .sort((a, b) => a.accountCode.localeCompare(b.accountCode, "en", { numeric: true }))
        .map(l => {
            const debit = fils(l.rows.reduce((s, r) => s + r.debit, 0));
            const credit = fils(l.rows.reduce((s, r) => s + r.credit, 0));
            const balance = fils(l.openingBalance + debit - credit);
            return { accountId: l.accountId, code: l.accountCode, ledger: l, opening: l.openingBalance, rows: l.rows, subTotal: { debit, credit, balance }, truncated: l.truncated };
        });
    const total = groups.reduce<LedgerTotals>((t, g) => ({
        debit: fils(t.debit + g.subTotal.debit), credit: fils(t.credit + g.subTotal.credit), balance: fils(t.balance + g.subTotal.balance),
    }), { debit: 0, credit: 0, balance: 0 });
    return { groups, total };
}

/** A running balance with PACT's Dr/Cr suffix. */
export const drCr = (n: number): string => fmtBalance(n);
