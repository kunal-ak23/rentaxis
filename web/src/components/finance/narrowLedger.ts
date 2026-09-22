import type { AccountLedger } from "@/lib/api/ledger";

/**
 * Narrow a renter's ledger to one lease.
 *
 * `GET /finance/ledger/renter/{id}` is not lease-filtered server-side — its
 * rows carry their own `leaseId` — so any screen showing one contract's
 * postings has to do the narrowing itself. Two of them do: the contract's
 * Journals tab, and the tenant ledger reached from that contract's Ledger
 * action.
 *
 * The totals are recomputed rather than carried over, which is the whole
 * reason this is a function and not two `filter` calls. `totalDebit`,
 * `totalCredit` and `closingBalance` describe the account across every lease
 * the tenant holds; printing them under a narrowed set of rows gives a Sub
 * Total that does not add up to the rows above it, which is precisely the
 * kind of number an accountant chases for an afternoon.
 *
 * The opening balance goes to zero for the same reason: it is the account's
 * position before the window, not this contract's, and there is no such thing
 * as one contract's opening balance part-way through a shared account. Each
 * block therefore reads as "what this contract moved on this account", and
 * `balance` is the running total of that movement.
 *
 * Accounts left with no rows drop out — an empty band with a sub total of zero
 * says nothing and pushes the ones that matter off the screen.
 */
export function narrowLedgersToLease(ledgers: AccountLedger[], leaseId: string | undefined): AccountLedger[] {
    if (!leaseId) return ledgers;
    const out: AccountLedger[] = [];
    for (const ledger of ledgers) {
        const rows = ledger.rows.filter(r => r.leaseId === leaseId);
        if (rows.length === 0) continue;

        let dr = 0;
        let cr = 0;
        const rebalanced = rows.map(r => {
            dr += r.debit;
            cr += r.credit;
            return { ...r, balance: round2(dr - cr) };
        });

        out.push({
            ...ledger,
            openingBalance: 0,
            rows: rebalanced,
            totalDebit: round2(dr),
            totalCredit: round2(cr),
            closingBalance: round2(dr - cr),
        });
    }
    return out;
}

function round2(n: number): number {
    return Math.round((n + Number.EPSILON) * 100) / 100;
}
