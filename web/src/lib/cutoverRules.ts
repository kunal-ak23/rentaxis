import type {
    GridProblem, ImportBatchStatus, ImportJobStatus, LeaseOutcomeStatus, OpeningBalanceGrid,
    OpeningBalanceRow, ProblemSeverity, ReconciliationRow,
} from "@/lib/api/cutover";
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
 * Is there nothing left to do with this batch?
 *
 * Only DISCARDED. `ImportBatchStatus`'s own doc: "the batch and everything it
 * created are gone". A REVERSED batch is NOT final — its leases are still there,
 * carrying the imported statuses and dates the reverse deliberately kept, so it
 * can be posted again (as a successor) or discarded.
 */
export function isBatchFinal(status: ImportBatchStatus): boolean {
    return status === "DISCARDED";
}

/**
 * May this batch be bulk-posted?
 *
 * `ContractImportPostService.runUnderBatchLock` (:169-177) takes every status but
 * one. DRAFT posts. REVERSED posts as a SUCCESSOR batch, because `markPosted`
 * refuses REVERSED → POSTED by design — "an undo that has already happened must
 * not become undoable a second time" — while the leases are still there, so the
 * corrected portfolio does not need re-uploading. POSTED is the RETRY path: the
 * run walks the whole plan, contracts already on the books come back
 * `SKIPPED_ALREADY_POSTED`, and the ones that failed last time are tried again.
 * DISCARDED is refused outright: "its leases have been deleted. Import the
 * corrected workbook again."
 */
export function canPostBatch(status: ImportBatchStatus): boolean {
    return status !== "DISCARDED";
}

/**
 * Would posting this batch create a successor rather than post this one?
 *
 * True only for REVERSED (`runUnderBatchLock:171-174`). The UI calls it "Post
 * again" and says that a new batch will hold the new journals — otherwise the
 * row the accountant pressed stays REVERSED and looks like nothing happened.
 */
export function isRepost(status: ImportBatchStatus): boolean {
    return status === "REVERSED";
}

/**
 * May this batch be discarded?
 *
 * **DRAFT alone.**
 *
 * `ImportBatchDiscardService.requireDiscardable` and
 * `ImportBatchService.markDiscarded` allowed DRAFT *or* REVERSED when this screen
 * was first written, and it offered both. A controller ruling in the backend's
 * current fix round narrows it: a REVERSED batch keeps its contracts, so the way
 * back is to post it again — not to throw the contracts away. The server refuses
 * it with "A reversed batch keeps its contracts; post it again or leave it
 * reversed", and the UI must not offer what the server refuses, so this changes
 * ahead of the commit rather than after it.
 *
 * (When that commit lands, the Java to cite is the same two methods; until then
 * this is deliberately stricter than the code in `main`.)
 */
export function canDiscardBatch(status: ImportBatchStatus): boolean {
    return status === "DRAFT";
}

/** Just enough of a contract outcome to decide whether a retry has anything to do. */
export type OutcomeLike = { outcome: LeaseOutcomeStatus };

/**
 * The one action this batch's row should offer, or null for none.
 *
 * `retry` is deliberately **evidence-based**. Posting a POSTED batch is a legal
 * retry — the run walks the whole plan and already-posted contracts come back
 * `SKIPPED_ALREADY_POSTED` — but offering it unconditionally promises a retry
 * nothing knows exists, and pressing it on a clean batch just reports that
 * everything was already posted. So it appears only when the last run left a
 * FAILED contract behind. A batch whose contracts are all on the books offers
 * nothing, which is the truth about it.
 *
 * @param lastResult the outcomes of the most recent post of THIS batch, or null
 *                   when none has been seen in this session.
 */
export function batchAction(
    status: ImportBatchStatus,
    lastResult: OutcomeLike[] | null,
): "post" | "repost" | "retry" | null {
    if (!canPostBatch(status)) return null;
    if (status === "DRAFT") return "post";
    if (isRepost(status)) return "repost";
    // POSTED: only if something is known to have failed.
    return lastResult?.some(r => r.outcome === "FAILED") ? "retry" : null;
}

/**
 * Has the bulk post stopped?
 *
 * The job row is an `ImportJob`, so its statuses are that column's. The run ends
 * COMPLETED or FAILED; anything else is in flight.
 */
export function isBulkPostTerminal(status: string): boolean {
    return status === "COMPLETED" || status === "FAILED";
}

/**
 * The CUT-OVER template download, and every other cut-over control.
 *
 * `api/PortfolioImportController`'s `CUTOVER_ROLES` (:109) is
 * `hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','ACCOUNTANT')`, and the controller
 * says why in as many words: "the person who assembles a cut-over workbook out
 * of a PACT export is the accountant, and a template they must ask an admin to
 * fetch is a template they will rebuild by hand".
 *
 * This used to return `canAccessFinanceOps` (SA/TA), mirroring the **v1**
 * `/import/portfolio/template` handler, which keeps its narrower gate. The
 * cut-over template is a different route with a different annotation, so the
 * page links that one and this widens to match it. Same set as
 * `canManageImportBatches`, and deliberately so — they are now the same
 * controller-level decision about who runs a cut-over.
 */
export function canDownloadImportTemplate(role: UserRole | undefined): boolean {
    return hasPermission(role, "canManageImportBatches");
}

// ---- the cut-over contract import ----

/**
 * `PortfolioImportController.MAX_UPLOAD_BYTES` (:112) — the ordinary multipart
 * ceiling, which the global handler turns into a 400 rather than a raw 500.
 */
export const CONTRACT_IMPORT_MAX_BYTES = 10 * 1024 * 1024;

/**
 * `.xlsx` and nothing else. The server checks the file's SIGNATURE — the
 * `PK\x03\x04` zip header (`ZIP_MAGIC`, :115, `looksLikeXlsx`) — not its name
 * or its declared type, because "a renamed executable with an .xlsx extension
 * would otherwise reach the parser".
 *
 * The client cannot read the magic bytes without loading the file, so it matches
 * on the extension: enough to catch the ordinary mistake of picking a .csv,
 * while the signature check is what actually protects the parser.
 */
export const CONTRACT_IMPORT_ACCEPT =
    ".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

export type ContractImportRefusal = "workbookTooBig" | "workbookWrongType";

/** What the server would say, before a 10MB workbook travels to learn it. */
export function contractImportRefusal(file: File): ContractImportRefusal | null {
    if (file.size > CONTRACT_IMPORT_MAX_BYTES) return "workbookTooBig";
    if (!file.name.toLowerCase().endsWith(".xlsx")) return "workbookWrongType";
    return null;
}

/**
 * Has the job stopped moving?
 *
 * `PortfolioImportService` sets exactly five statuses (`:630-662`):
 * `VALIDATING` → `VALIDATION_FAILED`, or `VALIDATING` → `PERSISTING` →
 * `COMPLETED` / `FAILED`. The first two are in flight; the last three are where
 * it stops, and where the poll must stop with it — a poll that keeps running
 * after a terminal state is a request every two seconds, forever.
 */
export function isImportJobTerminal(status: ImportJobStatus): boolean {
    return status === "COMPLETED" || status === "VALIDATION_FAILED" || status === "FAILED";
}

/**
 * **Not** a rule this module exposes, and deliberately: a batch reversal is NOT
 * checked against the period lock, and it has NO DATE.
 *
 * `PostingService.reverse` reads
 * `if (original.getImportBatchId() == null) fiscal.assertOpen(date);` — a
 * journal that belongs to an import batch is exempt, because a cut-over is
 * loaded into periods that are normally closed and a batch that could not be
 * undone afterwards would be a one-way door. `ImportBatchReverseIT` pins this:
 * journals dated 11–12 Sep 2026 reverse cleanly with the books locked through
 * 30 Sep 2026.
 *
 * That exemption is exactly why the date went away. `ImportBatchService.reverse`
 * now dates every mirror on the journal it reverses and `ReverseBatchDTO` has
 * only a `reason`: with the lock not applying, a date the accountant picked was
 * accepted whatever it was, and `JournalLineRepository.balancesAsOf` has no
 * status predicate — so a mirror dated later left the batch's own figures
 * standing at their original dates while the row read REVERSED. A reversal that
 * did not undo anything, on a screen that said it had.
 *
 * So there is no date field and no lock gate here. Adding either would be a bug:
 * a date because the server no longer takes one, a gate because it would refuse
 * what the server allows.
 */
export const BATCH_REVERSAL_IGNORES_PERIOD_LOCK = true;

/**
 * **Not** mirrored here, and the report says why: the cut-over ORDER rule.
 *
 * Backend 2aefd796 refuses a bulk post, a batch reverse and a Post-again with
 * 409 while `TenantFiscalSettingsService.hasLiveOpeningBalance()` — "Opening
 * balances are posted. Reverse them first, then post them again after this
 * step." With the OB journal posting PACT minus what our books already hold, any
 * cut-over act underneath it moves `ours` beneath a journal that already netted
 * the old value out.
 *
 * Mirroring it would mean knowing on the batches screen whether an OB journal is
 * live, and the only thing that answers that is `GET /finance/opening-balances`
 * — the whole chart of accounts, which also 400s outright until the books start
 * date is set, i.e. on exactly the fresh tenant this screen is used on first. A
 * banner saying "could not load" on a page that never needed the call is a worse
 * lie than a refusal that arrives on the press. So the three actions surface the
 * server's own sentence, which names the two-click remedy, and this constant is
 * where the decision is written down rather than being absent.
 */
export const ORDER_RULE_IS_SERVER_SIDE_ONLY = true;

// ---- opening balances (core/service/cutover/OpeningBalanceService.java) ----

/**
 * How the screen knows which account the server computes for itself.
 *
 * `rows` — the grid's own rows carry `computed`, so they are authoritative and
 * nothing needs looking up. `lookup` — the older shape, where the account is
 * found through the OPENING_BALANCE_DIFFERENCE default mapping; `accountId` is
 * null until that lookup has positively answered. `pending` — no answer yet.
 */
export type ComputedAccountSource =
    | { kind: "rows" }
    | { kind: "lookup"; accountId: string | null }
    | { kind: "pending" };

/**
 * May this grid cell be typed into?
 *
 * Three reasons it may not, and the server has a different answer for each.
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
 * value is quietly discarded teaches the accountant that the screen lies.
 *
 * **Which is why this FAILS CLOSED.** The previous version took a nullable id
 * and read null as "no difference account to worry about", so a failed lookup —
 * a transient 5xx, a tenant switch mid-flight, or simply not having answered yet
 * — left the one cell this guard exists for wide open, and the figure typed into
 * it was accepted with a 204 and then discarded at post. Now nothing is editable
 * until the account is positively identified: either the rows say so themselves,
 * or the lookup has come back with an id. A locked grid an accountant can retry
 * is recoverable; a figure that vanishes without a word is not.
 *
 * Note what is NOT a reason: a posted grid stays editable. `setRow` has no
 * "already posted" check, and editing the snapshot while an OB journal is live is
 * exactly how a correction is prepared before Replace. Disabling it would refuse
 * what the server allows.
 */
export function canEditOpeningBalanceRow(row: OpeningBalanceRow, source: ComputedAccountSource): boolean {
    if (row.derived) return false;
    if (source.kind === "rows") return !row.computed;
    // Fail closed: no positively identified difference account, no editing.
    if (source.kind === "pending" || !source.accountId) return false;
    return row.accountId !== source.accountId;
}

/**
 * Do these rows carry the server's own `computed` flag?
 *
 * `undefined` on every row means an older backend that does not send it, and the
 * caller falls back to the default-account lookup. One row carrying it (true or
 * false) means the field is being sent, so the rows are the source of truth and
 * the second request can be skipped entirely.
 */
export function gridDeclaresComputed(rows: OpeningBalanceRow[]): boolean {
    return rows.some(r => r.computed !== undefined);
}

/** The sentence of a grid problem, whichever shape the server sent it in. */
export function problemMessage(p: GridProblem): string {
    return typeof p === "string" ? p : p.message;
}

/**
 * How much one grid problem matters — **failing closed**.
 *
 * `OpeningBalanceGridDTO.problems` is a mixed bag. Exactly one entry is fatal:
 * `OpeningBalanceService.postable`'s "no opening-balance difference account",
 * which `postFresh` re-asserts and throws on. The others are advisory by design
 * — "PACT's own opening-balance difference is not carried over; ours is
 * recomputed from the other rows" (emitted for every real PACT export carrying a
 * figure on that leaf), and a DERIVED role mapped to a missing account, which
 * `postFresh` never consults because it skips derived accounts entirely.
 *
 * The screen used to block Post on `problems.length > 0`, so the ordinary
 * cut-over arrived with one advisory note and Post permanently greyed out — the
 * UI refusing what the server allows, on the plan's headline path, which is the
 * exact defect this module exists to prevent.
 *
 * An entry with no `severity` — an older backend, or a shape we have not seen —
 * reads ERROR. Guessing "advisory" would re-open the one problem posting really
 * does fail on; a blocked Post an accountant can ask about is recoverable, a 500
 * at post time is not.
 */
export function problemSeverity(p: GridProblem): ProblemSeverity {
    return typeof p === "string" ? "ERROR" : p.severity ?? "ERROR";
}

/** The problems that must be fixed before Post can be offered. */
export function blockingProblems(problems: GridProblem[]): GridProblem[] {
    return problems.filter(p => problemSeverity(p) === "ERROR");
}

/** The problems that are worth saying and that the server posts over. */
export function advisoryProblems(problems: GridProblem[]): GridProblem[] {
    return problems.filter(p => problemSeverity(p) !== "ERROR");
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
 * `OpeningBalanceService.reverse` — "There is no posted opening-balance journal
 * to reverse", so the action exists only while one is live.
 *
 * Its own act, and NOT a substitute for Replace: `repost` reverses and
 * immediately posts a corrected set, so it can fix the opening balances but
 * cannot return the tenant to "books not yet opened". An OB posted on the wrong
 * books start date, or posted before the contract import was ready, has no other
 * way back — the journal detail page refuses an OPENING_BALANCE entry by design
 * (`JournalService.requireManual`, whose message points at this screen).
 *
 * The mirror is dated on the opening entry's own date and the caller does not
 * choose: a mirror dated later leaves the opening balance standing as at D − 1
 * while the marker says "not posted", and the next Post writes a second OB
 * journal on the same day. So there is no date field here, deliberately.
 */
export function canReverseOpeningBalances(grid: OpeningBalanceGrid): boolean {
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
