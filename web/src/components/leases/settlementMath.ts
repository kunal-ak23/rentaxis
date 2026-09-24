/**
 * The one figure the settlement screen has to be able to compute for itself.
 *
 * `SettlementStatementDTO.netRefund` is derived from the lines that are
 * **stored**, so the moment a line is typed the server's figure is describing
 * a different document than the one on screen. Everything else on the
 * statement comes from the ledger and does not move as lines are edited, so
 * the recomputation is exactly `SettlementService.buildStatement`:290-293:
 *
 *     netRefund = depositsHeld − receivableBalance − Σdeductions − Σdeduction VAT + Σadditions
 *
 * `>0` the landlord pays out, `<0` the renter still owes. Nothing else is
 * netted here — not the outstanding penalties (already inside
 * `receivableBalance`) and not the outstanding instruments (deliberately not
 * netted at all; see `OutstandingInstrumentDTO`).
 */

import type {
    AdditionCategory,
    DeductionAttachment,
    DeductionCategory,
    SettlementLineType,
    SettlementStatement,
    SaveSettlementLine,
} from "@/lib/api/leasing";

/** One editable row of the settlement grid. */
export type SettlementRow = {
    /** Stable across inserts and removals so React does not re-key the grid. */
    key: number;
    id?: string | null;
    type: SettlementLineType;
    category: DeductionCategory | AdditionCategory;
    description: string;
    amount: number;
    /** Override the leaf; null means "let the category resolve it". */
    accountId: string | null;
    accountName?: string | null;
    autoCalculated: boolean;
    attachments: DeductionAttachment[];
    /**
     * F14-37/F14-61: the VAT a stored recharge line carries on top of `amount`
     * (net). Used as-is on a FINALIZED settlement; a draft re-prices it with
     * `lineVatOf` so an unsaved line shows its VAT too.
     */
    vatAmount: number;
};

/** F14-61: the statement's VAT rule — `SettlementService.deductionVat`. */
export type VatRule = { vatRate?: number | null; vatableCategories?: DeductionCategory[] | null };

/** F14-61: VAT on one deduction row, half-up to the fils, exactly as the server prices it. */
export function lineVatOf(row: Pick<SettlementRow, "type" | "category" | "amount">, rule: VatRule | null | undefined): number {
    const rate = rule?.vatRate ?? 0;
    if (row.type !== "DEDUCTION" || !rate || !(rule?.vatableCategories ?? []).includes(row.category as DeductionCategory)) {
        return 0;
    }
    return vatFilsOf(row.amount || 0, rate) / 100;
}

/**
 * F14-61 R1 (P2-1): VAT in whole fils, rounded HALF_UP exactly as
 * `amount.multiply(RATE).setScale(2, HALF_UP)` does in Java. Floating-point
 * `round2(amount * rate)` rounds 2.6 % of half-fil cases down (80.30 → 4.01,
 * the server books 4.02), so the product is taken in integers: the amount in
 * fils times the rate in basis points, then half-up division by 10,000.
 */
export function vatFilsOf(amount: number, rate: number): number {
    const fils = Math.round(Math.abs(amount) * 100);
    const bp = Math.round(rate * 10000);
    const vat = Math.floor((fils * bp + 5000) / 10000);
    return amount < 0 ? -vat : vat;
}

/** F14-61: the rows with each deduction's VAT re-priced by the rule (a draft's live view). */
export function withLineVat(rows: SettlementRow[], rule: VatRule | null | undefined): SettlementRow[] {
    return rows.map(r => ({ ...r, vatAmount: lineVatOf(r, rule) }));
}

/** Half-up to the fils — the server's `money(...)` scale. */
export function round2(n: number): number {
    if (!Number.isFinite(n)) return 0;
    return Math.round((n + Number.EPSILON) * 100) / 100;
}

export function totalOf(rows: SettlementRow[], type: SettlementLineType): number {
    return round2(rows.filter(r => r.type === type).reduce((s, r) => s + (r.amount || 0), 0));
}

/** F14-37/F14-61: Σ of the deduction rows' VAT — "VAT on recharges", on top of `totalOf(rows, "DEDUCTION")`. */
export function totalVatOf(rows: SettlementRow[]): number {
    return round2(rows.filter(r => r.type === "DEDUCTION").reduce((s, r) => s + (r.vatAmount || 0), 0));
}

export function netRefundOf(statement: SettlementStatement, rows: SettlementRow[]): number {
    return round2(
        statement.depositsHeld
        - statement.receivableBalance
        - totalOf(rows, "DEDUCTION")
        - totalVatOf(withLineVat(rows, statement))
        + totalOf(rows, "ADDITION"),
    );
}

/**
 * The grid as `SaveSettlementDTO` wants it: the whole thing on every save,
 * never a diff — an omitted draft row is a deleted one.
 *
 * A zero-amount row is dropped rather than sent: `@DecimalMin("0.00")` would
 * accept it, and a settlement line for nothing is a line somebody forgot to
 * fill in.
 */
export function toSaveLines(rows: SettlementRow[]): SaveSettlementLine[] {
    return rows
        .filter(r => (r.amount || 0) > 0)
        .map(r =>
            r.type === "ADDITION"
                ? {
                    id: r.id ?? undefined,
                    type: "ADDITION" as const,
                    additionCategory: r.category as AdditionCategory,
                    description: r.description || null,
                    amount: r.amount,
                    autoCalculated: r.autoCalculated,
                    accountId: r.accountId,
                }
                : {
                    id: r.id ?? undefined,
                    type: "DEDUCTION" as const,
                    category: r.category as DeductionCategory,
                    description: r.description || null,
                    amount: r.amount,
                    autoCalculated: r.autoCalculated,
                    accountId: r.accountId,
                },
        );
}
