import type { ImportBatchStatus, OpeningBalanceGrid, OpeningBalanceRow, ReconciliationRow } from "@/lib/api/cutover";
import { isZeroAmount } from "@/lib/money";
import { hasPermission, type UserRole } from "@/lib/rbac";

/**
 * The server rules the cut-over screens obey, in one place, each naming the Java
 * it mirrors — the same discipline as `voucherRules.ts`, and for the same
 * reason: the UI never offers what the server always refuses.
 *
 * All paths are under backend/src/main/java/com/datagami/rentaxis/.
 */

/**
 * `core/service/cutover/ImportBatchService.reverse:177-180` — "Import batch is
 * {status}; only a POSTED batch can be reversed".
 *
 * A DRAFT has written no journals, so there is nothing to take back; a REVERSED
 * one has already been taken back and cannot be reversed twice.
 */
export function canReverseBatch(status: ImportBatchStatus): boolean {
    return status === "POSTED";
}

/**
 * `domain/entity/enums/ImportBatchStatus.java`, in its own words: REVERSED "is
 * the end of the line — a reversed batch is history, and a corrected spreadsheet
 * is imported as a new one."
 *
 * There is no re-post endpoint and there is not meant to be one, so the screen
 * says so rather than leaving a disabled button implying one might appear.
 */
export function isBatchFinal(status: ImportBatchStatus): boolean {
    return status === "REVERSED";
}

/**
 * The contract-import template download.
 *
 * `api/PortfolioImportController#template` (:78-79) is
 * `@PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')")` — it does **not**
 * admit ACCOUNTANT, unlike `ImportBatchController` and every other control on
 * this page. So the link is gated one role narrower than the page it sits on,
 * rather than handing an accountant a download that 403s on click.
 *
 * `canAccessFinanceOps` is exactly that pair and already mirrors the
 * SA/TA-only controllers, so it is reused instead of a near-duplicate key.
 */
export function canDownloadImportTemplate(role: UserRole | undefined): boolean {
    return hasPermission(role, "canAccessFinanceOps");
}

/**
 * **Not** a rule this module exposes, and deliberately: a batch reversal is NOT
 * checked against the period lock.
 *
 * `PostingService.reverse` reads
 * `if (original.getImportBatchId() == null) fiscal.assertOpen(date);` — a
 * journal that belongs to an import batch is exempt, because a cut-over is
 * loaded into periods that are normally closed and a batch that could not be
 * undone afterwards would be a one-way door. `ImportBatchReverseIT` pins this:
 * journals dated 11–12 Sep 2026 reverse cleanly with the books locked through
 * 30 Sep 2026.
 *
 * So the reversal-date field here carries no lock gate. Adding one would be the
 * mirror-image bug — the UI refusing what the server allows.
 */
export const BATCH_REVERSAL_IGNORES_PERIOD_LOCK = true;

// ---- opening balances (core/service/cutover/OpeningBalanceService.java) ----

/**
 * May this grid cell be typed into?
 *
 * Two reasons it may not, and the server has a different answer for each.
 *
 * `derived` — the account is mapped to one of `DERIVED_ROLES` (`:113-116`), so
 * the contract import produces its balance. `setRow:256-260` 400s a hand-typed
 * figure outright: "is mapped to {role} and is derived from the contract import,
 * so it cannot be entered by hand". An editable cell the server always refuses is
 * the pattern this module exists to prevent.
 *
 * The **opening-balance difference account** is worse than refused: it is
 * ACCEPTED by `setRow` and then silently skipped when the journal is built —
 * `postFresh:365`, "folded into the balancing line below" — because the server
 * computes that figure itself from the gap between the two columns. A cell whose
 * value is quietly discarded teaches the accountant that the screen lies, so it
 * is read-only too. It is not flagged on the row DTO, so the caller identifies it
 * from the OPENING_BALANCE_DIFFERENCE default-account mapping and passes its id.
 *
 * Note what is NOT a reason: a posted grid stays editable. `setRow` has no
 * "already posted" check, and editing the snapshot while an OB journal is live is
 * exactly how a correction is prepared before Replace. Disabling it would refuse
 * what the server allows.
 */
export function canEditOpeningBalanceRow(
    row: OpeningBalanceRow,
    differenceAccountId: string | null,
): boolean {
    if (row.derived) return false;
    if (differenceAccountId && row.accountId === differenceAccountId) return false;
    return true;
}

/**
 * `OpeningBalanceService.post:292-301` refuses a second post — "Opening balances
 * have already been posted. Reverse the existing opening-balance journal first,
 * or re-post to replace it." So Post is offered only while the books are closed.
 */
export function canPostOpeningBalances(grid: OpeningBalanceGrid): boolean {
    return !grid.posted;
}

/**
 * `OpeningBalanceService.repost:311-322` — the live journal is reversed and a
 * fresh one written in one transaction, so the books are never between two
 * opening balances. Its own action rather than a second Post, deliberately: "a
 * double-click must not quietly replace a set of opening balances somebody has
 * started reconciling against".
 */
export function canReplaceOpeningBalances(grid: OpeningBalanceGrid): boolean {
    return grid.posted;
}

/**
 * `spring.servlet.multipart.max-file-size: 10MB` (application.yml:6), which the
 * global handler turns into a 400 rather than a raw 500. A trial balance that big
 * is not a trial balance.
 */
export const SNAPSHOT_MAX_BYTES = 10 * 1024 * 1024;

/**
 * `TrialBalanceCsvParser` reads a CSV and nothing else. Matching on the EXTENSION
 * rather than the MIME type is deliberate: browsers label the same .csv file
 * text/csv, application/csv, application/vnd.ms-excel, text/plain or "" depending
 * on the platform, so refusing by type would reject good files on some machines.
 */
export const SNAPSHOT_ACCEPT = ".csv,text/csv";

export type SnapshotRefusal = "snapshotTooBig" | "snapshotWrongType";

/** What the server would say about this file, before a 10MB upload travels to learn it. */
export function snapshotRefusal(file: File): SnapshotRefusal | null {
    if (file.size > SNAPSHOT_MAX_BYTES) return "snapshotTooBig";
    if (!file.name.toLowerCase().endsWith(".csv")) return "snapshotWrongType";
    return null;
}

// ---- reconciliation ----

/**
 * `ReconciliationRowDTO.difference` is `derivedBalance - pactBalance`, both signed
 * debit-positive. Reconciled means zero **to the fil** on every row — compared
 * through `money.isZeroAmount` so this agrees with the grid totals rather than
 * drifting from them by its own epsilon.
 */
export function isReconciled(rows: Pick<ReconciliationRow, "difference">[]): boolean {
    return rows.every(r => isZeroAmount(r.difference));
}

/**
 * Does this report contain accounts the contract import has yet to fill in?
 *
 * Until the cut-over contracts are imported AND bulk-posted, every DERIVED
 * account reconciles as 0.00 on our side against PACT's real figure — so a fresh
 * reconciliation looks like the books are badly wrong when in fact a step has not
 * run yet. The page says so when this is true; leaving it unsaid is the
 * difference between a report and a false alarm.
 */
export function hasUnfilledDerivedAccounts(rows: ReconciliationRow[]): boolean {
    return rows.some(r => r.derived && isZeroAmount(r.derivedBalance) && !isZeroAmount(r.pactBalance));
}
