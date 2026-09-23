import type { ChequeMode } from "@/lib/api/leasing";

/**
 * The register's "Cheque No" rule, shared with the row action dialog's title
 * so the two cannot drift (#43 — the dialog title used to fall back to
 * `#seqNo` for every mode, including a CASH collection row that never had a
 * cheque number to begin with).
 *
 * A numberless PDC row is a cheque still awaiting its number, so `#seqNo` is
 * a fair placeholder. A CASH/TRANSFER/ONLINE row — e.g. the collection row an
 * approved penalty or a settlement balance creates — has no cheque number by
 * nature, and showing `#7` reads as cheque number 7, an instrument no cheque
 * book contains. Show a dash there instead.
 */
export function chequeLabel(c: { chequeNumber: string | null; seqNo: number; mode: ChequeMode }): string {
    return c.chequeNumber || (c.mode === "PDC" ? `#${c.seqNo}` : "—");
}
