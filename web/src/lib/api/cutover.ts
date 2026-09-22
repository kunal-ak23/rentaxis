import { apiGet, apiSend } from "@/lib/api/ledger";
import { throwIfNotOk } from "@/lib/api/facilities";
import type { AccountRole } from "@/lib/api/ledger";

const BASE = "/api/proxy/v1";

/**
 * The cut-over client (spec §10.3, §11).
 *
 * Typed from the Java, not from the plan's sketch: `api/ImportBatchController.java`
 * for the routes and `api/dto/cutover/ImportBatchDTO.java` for the shape. The
 * controller exposes exactly three handlers — list, get and reverse — so this
 * module has exactly three. The brief also described a bulk-post endpoint and a
 * `BulkPostResult`; neither exists in the backend, and a button wired to a route
 * that is not there is the 404 this project refuses to ship.
 *
 * Opening balances and the reconciliation report were added in task 15 against
 * `api/OpeningBalanceController.java` and `api/dto/cutover/*`; the contract import
 * against `api/PortfolioImportController.java`'s three `/cutover` handlers. The
 * BULK POST is still deliberately absent — that route does not exist yet, so a
 * DRAFT batch has no post action and the page says why.
 */

// ---- enums (domain/entity/enums/ImportBatchKind.java, ImportBatchStatus.java) ----

/** One value today; the enum exists for a future PDC-only or vendor-balance loader. */
export type ImportBatchKind = "CONTRACT_IMPORT";

/**
 * `DRAFT` — leases exist, nothing posted. `POSTED` — the batch's journals are in
 * the ledger and the whole run can still be undone. `REVERSED` — the journals are
 * off and every lease is back to a clean DRAFT; `markPosted` refuses to mark it
 * POSTED again, so re-posting the same contracts creates a SUCCESSOR batch to
 * hold the new journals (`repostOf` names this one). `DISCARDED` — the batch and
 * everything it created are gone, which is what makes "correct the workbook and
 * import it again" possible at all.
 */
export type ImportBatchStatus = "DRAFT" | "POSTED" | "REVERSED" | "DISCARDED";

// ---- responses ----

/**
 * `ImportBatchDTO`. `label` and `importJobId` are nullable columns; the two
 * counters are `int NOT NULL`; `postedAt` and `reversedAt` are null until the
 * batch reaches those states. Instants are ISO-8601 strings.
 */
export type ImportBatch = {
    id: string;
    kind: ImportBatchKind;
    status: ImportBatchStatus;
    label: string | null;
    importJobId: string | null;
    leasesImported: number;
    journalsPosted: number;
    postedAt: string | null;
    reversedAt: string | null;
    /** Set when the batch was thrown away; its leases and created rows no longer exist. */
    discardedAt: string | null;
    createdAt: string;
};

/** `ImportBatchController.ReverseBatchDTO` — `date` is `@NotNull`, `reason` is free text. */
export type ReverseBatchInput = { date: string; reason: string };

// ---- opening balances (api/OpeningBalanceController.java) ----

/**
 * `OpeningBalanceRowDTO`. One account of the chart, with whatever figure the
 * snapshot holds for it.
 *
 * `derived` means the contract import produces this account's balance, so the
 * API refuses a hand-typed figure for it (`OpeningBalanceService.setRow:256-260`)
 * — the screen shows it read-only and `derivedRole` is what to tell the
 * accountant when they ask why.
 */
export type OpeningBalanceRow = {
    accountId: string;
    code: string;
    name: string;
    accountType: string;
    propertyId: string | null;
    derived: boolean;
    derivedRole: AccountRole | null;
    /**
     * What the posted cut-over contracts put on this account, for a DERIVED row.
     * Zero until the batch is bulk-posted, which is why the reconciliation
     * report's honesty banner keys off them rather than off a flag.
     */
    derivedDebit?: number;
    derivedCredit?: number;
    enteredDebit: number;
    enteredCredit: number;
    /**
     * True for an account the server works out for itself — the
     * OPENING_BALANCE_DIFFERENCE row, which `postFresh` folds into its balancing
     * line and whose stored figure is therefore discarded.
     *
     * Optional because an older backend does not send it; when it is absent the
     * screen falls back to the OPENING_BALANCE_DIFFERENCE default mapping. When
     * it is present it is authoritative and no lookup is needed.
     */
    computed?: boolean;
};

/**
 * How much a grid problem matters.
 *
 * `ERROR` is a fault that makes `postFresh` throw — today exactly one:
 * `postable`'s "no opening-balance difference account". `WARNING` is a fact the
 * accountant has to know and the server posts happily over, of which the
 * ordinary one is "PACT's own opening-balance difference is not carried over;
 * ours is recomputed from the other rows" — emitted on every real PACT export
 * that carries a figure on that leaf.
 */
export type ProblemSeverity = "ERROR" | "WARNING";

/**
 * One entry of `OpeningBalanceGridDTO.problems`.
 *
 * A bare string is the older shape, from before the server said how much each
 * one mattered. It is read as an ERROR — see `problemSeverity` — because a
 * screen that guessed "advisory" would offer Post on the one problem posting
 * actually fails on.
 */
export type GridProblem = string | { message: string; severity?: ProblemSeverity };

/**
 * `OpeningBalanceGridDTO` — the whole screen in one response.
 *
 * `asOf` is the books start date minus one day, computed by the server
 * (`OpeningBalanceService.asOf:503-510`) and never by this client. `posted` and
 * `journalNumber` say whether a live OB journal exists, which is what decides
 * between Post and Replace. `difference` is `totalDebit - totalCredit` over the
 * entered figures and closes against OPENING_BALANCE_DIFFERENCE when the journal
 * is written — a non-zero one is normal, not an error. `problems` are a mixed
 * bag, which is why each carries a `severity`: an ERROR is a configuration fault
 * that makes posting fail and an advisory is a note the server posts over. The
 * screen gates Post on the first kind only — blocking on the second refused the
 * plan's headline path, because a real PACT export always carries the
 * difference-account advisory.
 */
export type OpeningBalanceGrid = {
    asOf: string;
    posted: boolean;
    journalId: string | null;
    journalNumber: string | null;
    rows: OpeningBalanceRow[];
    totalDebit: number;
    totalCredit: number;
    difference: number;
    problems: GridProblem[];
    /**
     * True when the snapshot has been edited since the live OB journal was
     * posted, so the books and the grid no longer agree and a Replace is due.
     * Optional: an older backend does not send it, and its absence says nothing.
     */
    changedSincePosted?: boolean;
};

/**
 * `ManualOpeningBalanceDTO`. Both fields are boxed and optional: `{debit: 5000}`
 * means "debit 5,000, credit nothing", and a body with neither clears the row.
 */
export type ManualOpeningBalanceInput = { debit: number | null; credit: number | null };

/**
 * `SnapshotUploadResultDTO`.
 *
 * `unmatchedCodes` are stored but unrecognised — PACT accounts our chart has no
 * equivalent for. They are not an error: they appear on the reconciliation report
 * so the accountant can decide whether to create the account or ignore the
 * balance. `problems` are lines that could not be read at all, each carrying the
 * file line number.
 */
export type SnapshotUploadResult = {
    stored: number;
    unmatchedCodes: string[];
    problems: string[];
    /**
     * The file's own two column totals and whether they agree. An unbalanced file
     * is accepted on purpose — "that disagreement is what the reconciliation
     * report exists to show" — so this is a warning, never a refusal. All three
     * are optional; an older backend sends none of them and the panel says
     * nothing about balance.
     */
    totalDebit?: number;
    totalCredit?: number;
    balanced?: boolean;
};

/** `OpeningBalanceController.PostedJournalDTO` — enough for a toast and a link to the GL. */
export type PostedJournal = { id: string; entryNumber: string; entryDate: string };

/** `OpeningBalanceController.RepostObDTO` — why the books are being opened again. */
export type RepostInput = { reason: string };

/**
 * `OpeningBalanceController.ReverseObDTO`.
 *
 * No date: the server always dates the reversal to the live opening journal's
 * own date, and no longer accepts one from the caller. Letting a caller pick
 * would leave the opening entry and its mirror in different periods.
 */
export type ReverseObInput = { reason: string };

/**
 * `ReconciliationRowDTO`.
 *
 * Both balances are signed debit-positive, the same convention
 * `TrialBalanceRowDTO.balance` uses, so a credit-balance account reads negative on
 * both sides and `difference` still means `derivedBalance - pactBalance`.
 * `accountId` is null for a PACT code our chart has no account for — the row is
 * kept so nothing is silently lost.
 */
export type ReconciliationRow = {
    accountId: string | null;
    code: string;
    name: string;
    derived: boolean;
    derivedBalance: number;
    pactBalance: number;
    difference: number;
};

// ---- the client ----

// ---- the cut-over contract import (api/PortfolioImportController.java) ----

/**
 * `ImportJob.status`, as `PortfolioImportService` sets it (`:630-662`).
 *
 * `VALIDATING` and `PERSISTING` are in-flight; the other three are terminal and
 * stop the poll. There is no cancel.
 */
export type ImportJobStatus = "VALIDATING" | "PERSISTING" | "COMPLETED" | "VALIDATION_FAILED" | "FAILED";

/**
 * `api/dto/ImportErrorDTO`. `field` is the workbook COLUMN — the DTO's own name
 * for it — and `row` is the 1-based row within `sheet`.
 */
export type ImportError = {
    sheet: string;
    /**
     * The 1-based row within `sheet`, or **null** for a problem with the file as
     * a whole — a missing sheet, a workbook that could not be opened. Nullable on
     * the server for exactly that reason, so "row 0" must never be rendered.
     */
    row: number | null;
    field: string;
    message: string;
};

/**
 * `api/dto/PortfolioImportResultDTO`, the body of the cut-over status poll.
 *
 * `importBatchId` is the whole point of the cut-over shape: it is how the screen
 * goes from "my upload finished" to the batch that can be posted or reversed,
 * without guessing which one is its own. It is null for a v1 import and for a
 * cut-over job that never reached the persist phase — including one whose
 * persist rolled back, whose batch id and counters the controller clears
 * deliberately so the web is never sent after a batch that no longer exists.
 *
 * `chequesCreated` is the job's `schedules_created` column reused; the wire name
 * follows what it now holds.
 */
export type ContractImportResult = {
    jobId: string;
    status: ImportJobStatus;
    propertiesCreated: number;
    buildingsCreated: number;
    unitsCreated: number;
    rentersCreated: number;
    leasesCreated: number;
    chequesCreated: number;
    chequesFromSheet: number;
    bookingDepositsCreated: number;
    importBatchId: string | null;
    contractsCreated: number;
    mappingsCreated: number;
    errors: ImportError[];
    warnings: ImportError[];
};

// ---- bulk post and discard (api/ImportBatchController.java) ----

/** `ImportBatchController.PostStartedDTO` (:76). */
export type PostStarted = { jobId: string; batchId: string };

/** `ContractImportPostService.LeaseOutcome.Outcome` (:106-113). */
export type LeaseOutcomeStatus = "POSTED" | "SKIPPED_ALREADY_POSTED" | "FAILED";

/**
 * `ContractImportPostService.LeaseOutcome` — how one contract fared.
 *
 * `SKIPPED_ALREADY_POSTED` is not a failure: it is what a retry looks like when
 * a contract was already on the books. A `FAILED` one carries its `reason` and
 * stays a DRAFT of the batch, so posting again retries exactly those.
 */
export type LeaseOutcome = {
    leaseId: string;
    externalContractRef: string | null;
    outcome: LeaseOutcomeStatus;
    reason: string | null;
    journals: number;
    chequesDeposited: number;
    chequesCleared: number;
    chequesBounced: number;
    recognitionEntriesPosted: number;
};

/**
 * `ContractImportPostService.BulkPostResult` (:127-136).
 *
 * `batchId` is the batch that now holds the journals — normally the one asked
 * for, but for a re-post of a REVERSED batch it is the SUCCESSOR this call
 * created, and `repostOf` names the reversed one. `journalsPosted` is read back
 * from the ledger rather than accumulated.
 */
export type BulkPostResult = {
    batchId: string;
    repostOf: string | null;
    status: ImportBatchStatus;
    leasesPosted: number;
    leasesSkipped: number;
    leasesFailed: number;
    chequesDeposited: number;
    chequesCleared: number;
    chequesBounced: number;
    recognitionEntriesPosted: number;
    journalsPosted: number;
    leases: LeaseOutcome[];
    failures: ImportError[];
};

/**
 * `ImportBatchController.PostJobDTO` (:87-88).
 *
 * `processed`/`total` are the progress signal; `result` is null until the run
 * finishes. `errors` is only ever about the batch as a whole — a books start
 * date that is not set, someone else posting it — because a contract that fails
 * is inside `result.leases`, not here.
 */
export type PostJob = {
    jobId: string;
    batchId: string | null;
    status: string;
    processed: number | null;
    total: number | null;
    result: BulkPostResult | null;
    errors: ImportError[];
};

/** `ImportBatchDiscardService.Kept` (:140) — a row the discard did NOT delete, and why. */
export type DiscardKept = { type: string; id: string; name: string | null; reason: string };

/** `ImportBatchDiscardService.DiscardResult` (:144-147). */
export type DiscardResult = {
    batchId: string;
    status: ImportBatchStatus;
    leasesDeleted: number;
    unitsDeleted: number;
    buildingsDeleted: number;
    rentersDeleted: number;
    propertiesDeleted: number;
    kept: DiscardKept[];
};

export const cutoverApi = {
    batches: {
        /** `GET /finance/import-batches` — a plain list, oldest first (`findAllByOrderByCreatedAtAsc`). */
        list: () => apiGet<ImportBatch[]>("/finance/import-batches"),
        /** Another tenant's id is a 404, deliberately: whether it exists is not this tenant's business. */
        get: (id: string) => apiGet<ImportBatch>(`/finance/import-batches/${id}`),
        /**
         * Reverses every journal the batch wrote, newest first, and returns its
         * leases to DRAFT — one transaction, so a half-reversed batch is not a
         * state the books can be left in.
         */
        reverse: (id: string, body: ReverseBatchInput) =>
            apiSend<ImportBatch>("POST", `/finance/import-batches/${id}/reverse`, body),
        /**
         * Start the bulk post. Asynchronous like the upload that produced the
         * batch: a six-hundred-contract portfolio posts a few thousand journals,
         * which is not a request to hold a connection open for. Answers the job
         * id at once; poll `postStatus`.
         */
        post: (id: string) => apiSend<PostStarted>("POST", `/finance/import-batches/${id}/post`),
        /** The poll. 404 when the job is not this batch's, or not this organisation's. */
        postStatus: (id: string, jobId: string) =>
            apiGet<PostJob>(`/finance/import-batches/${id}/post/${jobId}`),
        /**
         * Throw the batch away: its draft contracts, and the properties,
         * buildings, units and renters it created, when nothing else refers to
         * them. Synchronous — it writes no journals, and the accountant pressing
         * it is waiting to re-upload the corrected file.
         */
        discard: (id: string) => apiSend<DiscardResult>("POST", `/finance/import-batches/${id}/discard`),
    },
    openingBalances: {
        grid: () => apiGet<OpeningBalanceGrid>("/finance/opening-balances"),
        /** `PUT /opening-balances/{accountId}` -> 204. Refused for a derived or group account. */
        setRow: (accountId: string, body: ManualOpeningBalanceInput) =>
            apiSend<void>("PUT", `/finance/opening-balances/${accountId}`, body),
        /**
         * Multipart with a single `file` part. No `Content-Type` header is set
         * deliberately — only the browser can write the multipart boundary. The
         * server replaces the stored snapshot wholesale: a re-upload is a
         * correction of the whole file, not an addition to it.
         */
        uploadSnapshot: async (file: File) => {
            const fd = new FormData();
            fd.append("file", file);
            const res = await fetch(`${BASE}/finance/opening-balances/snapshot`, { method: "POST", body: fd });
            await throwIfNotOk(res);
            return res.json() as Promise<SnapshotUploadResult>;
        },
        /** Opens the books. Refused when they are already open — `repost` is the way to replace. */
        post: () => apiSend<PostedJournal>("POST", "/finance/opening-balances/post"),
        /** Reverses the live OB journal and posts a corrected one, in one transaction. */
        repost: (body: RepostInput) => apiSend<PostedJournal>("POST", "/finance/opening-balances/repost", body),
        /** Takes the OB journal off the books, leaving the grid's figures in place. */
        reverse: (body: ReverseObInput) =>
            apiSend<PostedJournal>("POST", "/finance/opening-balances/reverse", body),
    },
    reconciliation: () => apiGet<ReconciliationRow[]>("/finance/reconciliation"),
    contractImport: {
        /**
         * The CUT-OVER template, not the v1 one at `/import/portfolio/template`.
         * Separate routes with separate role gates: this one admits ACCOUNTANT,
         * "because the person who assembles a cut-over workbook out of a PACT
         * export is the accountant, and a template they must ask an admin to
         * fetch is a template they will rebuild by hand".
         *
         * A URL rather than a fetch: the browser downloads it through the proxy
         * with the filename the server sends in `Content-Disposition`.
         */
        templateUrl: () => `${BASE}/import/portfolio/cutover/template`,

        /**
         * `POST /import/portfolio/cutover`, multipart with one `file` part.
         * Answers `{ jobId }` at once and validates on the import executor — a
         * six-hundred-contract workbook is not a request to hold a connection
         * open for. No `Content-Type` header: the browser writes the boundary.
         */
        upload: async (file: File) => {
            const fd = new FormData();
            fd.append("file", file);
            const res = await fetch(`${BASE}/import/portfolio/cutover`, { method: "POST", body: fd });
            await throwIfNotOk(res);
            return res.json() as Promise<{ jobId: string }>;
        },

        /** The poll. 404 for another organisation's job, deliberately. */
        status: (jobId: string) => apiGet<ContractImportResult>(`/import/portfolio/cutover/${jobId}/status`),
    },
};
