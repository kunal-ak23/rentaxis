import { apiGet, apiSend } from "@/lib/api/ledger";

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
 * Opening balances and reconciliation are deliberately absent: those endpoints
 * are being written right now and will be typed against the real Java in Task 15.
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
};
