import type { ImportBatchStatus } from "@/lib/api/cutover";
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
