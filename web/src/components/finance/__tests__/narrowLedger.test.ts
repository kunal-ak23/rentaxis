import { describe, expect, it } from "vitest";
import { narrowLedgersToLease } from "../narrowLedger";
import type { AccountLedger, LedgerRow } from "@/lib/api/ledger";

function row(over: Partial<LedgerRow> & { entryId: string }): LedgerRow {
    return {
        entryNumber: "TCO-26/15", entryDate: "2026-09-11", docType: "TCO",
        particular: "Advance Rent", narration: "", debit: 0, credit: 0, balance: 0,
        propertyId: null, unitId: "u1", leaseId: "lease-1", renterId: "r1", chequeId: null,
        ...over,
    };
}

const LEDGERS: AccountLedger[] = [
    {
        accountId: "a1", accountCode: "166269", accountName: "Rent Receivable", accountType: "ASSET",
        openingBalance: 2500, totalDebit: 90000, totalCredit: 20000, closingBalance: 72500, truncated: false,
        rows: [
            row({ entryId: "e1", debit: 60000, balance: 62500 }),
            row({ entryId: "e2", credit: 13700, balance: 48800 }),
            row({ entryId: "e3", leaseId: "lease-2", debit: 30000, balance: 78800 }),
        ],
    },
    {
        accountId: "a2", accountCode: "300100", accountName: "Security Deposit", accountType: "LIABILITY",
        openingBalance: 0, totalDebit: 0, totalCredit: 5000, closingBalance: -5000, truncated: false,
        rows: [row({ entryId: "e4", leaseId: "lease-2", credit: 5000, balance: -5000 })],
    },
];

describe("narrowLedgersToLease", () => {
    it("returns the ledgers untouched when no lease is given", () => {
        expect(narrowLedgersToLease(LEDGERS, undefined)).toBe(LEDGERS);
    });

    it("keeps only the lease's rows and drops accounts left empty", () => {
        const out = narrowLedgersToLease(LEDGERS, "lease-1");
        expect(out).toHaveLength(1);
        expect(out[0].accountCode).toBe("166269");
        expect(out[0].rows.map(r => r.entryId)).toEqual(["e1", "e2"]);
    });

    it("recomputes the sub total so it adds up to the rows printed under it", () => {
        // The server's figures describe every lease the tenant holds: 90,000 Dr
        // against a set of rows that now totals 60,000. A Sub Total that does
        // not add up to the rows above it is a number someone chases all
        // afternoon.
        const [ledger] = narrowLedgersToLease(LEDGERS, "lease-1");
        expect(ledger.totalDebit).toBe(60000);
        expect(ledger.totalCredit).toBe(13700);
        expect(ledger.closingBalance).toBe(46300);
    });

    it("zeroes the opening balance and re-runs the balance column", () => {
        // There is no such thing as one contract's opening balance part-way
        // through a shared account, so each block reads as this contract's
        // movement and `balance` is the running total of that movement.
        const [ledger] = narrowLedgersToLease(LEDGERS, "lease-1");
        expect(ledger.openingBalance).toBe(0);
        expect(ledger.rows.map(r => r.balance)).toEqual([60000, 46300]);
    });

    it("does not mutate the ledgers it was given", () => {
        narrowLedgersToLease(LEDGERS, "lease-1");
        expect(LEDGERS[0].rows).toHaveLength(3);
        expect(LEDGERS[0].totalDebit).toBe(90000);
        expect(LEDGERS[0].openingBalance).toBe(2500);
    });
});
