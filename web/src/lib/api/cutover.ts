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
 * `api/OpeningBalanceController.java` and `api/dto/cutover/*`. The contract-import
 * upload, the cut-over template and the bulk post are still deliberately absent —
 * those routes do not exist yet.
 */

// ---- enums (domain/entity/enums/ImportBatchKind.java, ImportBatchStatus.java) ----

/** One value today; the enum exists for a future PDC-only or vendor-balance loader. */
export type ImportBatchKind = "CONTRACT_IMPORT";

/**
 * `DRAFT` — leases exist, nothing posted. `POSTED` — the batch's journals are in
 * the ledger and the whole run can still be undone. `REVERSED` — the end of the
 * line: a reversed batch is history, and a corrected spreadsheet comes back as a
 * NEW batch, never as a re-post of this one.
 */
export type ImportBatchStatus = "DRAFT" | "POSTED" | "REVERSED";

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
 * `OpeningBalanceGridDTO` — the whole screen in one response.
 *
 * `asOf` is the books start date minus one day, computed by the server
 * (`OpeningBalanceService.asOf:503-510`) and never by this client. `posted` and
 * `journalNumber` say whether a live OB journal exists, which is what decides
 * between Post and Replace. `difference` is `totalDebit - totalCredit` over the
 * entered figures and closes against OPENING_BALANCE_DIFFERENCE when the journal
 * is written — a non-zero one is normal, not an error. `problems` are
 * configuration faults that would make posting fail, listed so the screen can say
 * so before anyone presses Post.
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
    problems: string[];
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
};

