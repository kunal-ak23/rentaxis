/**
 * The one figure the settlement screen has to be able to compute for itself.
 *
 * `SettlementStatementDTO.netRefund` is derived from the lines that are
 * **stored**, so the moment a line is typed the server's figure is describing
 * a different document than the one on screen. Everything else on the
 * statement comes from the ledger and does not move as lines are edited, so
 * the recomputation is exactly `SettlementService.buildStatement`:290-293:
 *
 *     netRefund = depositsHeld − receivableBalance − Σdeductions + Σadditions
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
    /** F14-37: VAT this recharge line carries — already inside `amount`. */
    vatAmount: number;
};

/** Half-up to the fils — the server's `money(...)` scale. */
export function round2(n: number): number {
    if (!Number.isFinite(n)) return 0;
    return Math.round((n + Number.EPSILON) * 100) / 100;
}

export function totalOf(rows: SettlementRow[], type: SettlementLineType): number {
    return round2(rows.filter(r => r.type === type).reduce((s, r) => s + (r.amount || 0), 0));
}

/** F14-37: Σ of the deduction rows' VAT — "VAT on recharges", already inside `totalOf(rows, "DEDUCTION")`. */
export function totalVatOf(rows: SettlementRow[]): number {
    return round2(rows.filter(r => r.type === "DEDUCTION").reduce((s, r) => s + (r.vatAmount || 0), 0));
}

export function netRefundOf(statement: SettlementStatement, rows: SettlementRow[]): number {
    return round2(
        statement.depositsHeld
        - statement.receivableBalance
        - totalOf(rows, "DEDUCTION")
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
