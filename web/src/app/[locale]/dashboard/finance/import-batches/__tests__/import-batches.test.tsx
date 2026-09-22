import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../messages/en.json";
import type { ImportBatch } from "@/lib/api/cutover";

/**
 * The cut-over Import Batches screen (spec §10.3, §11).
 *
 * The rules it has to wear:
 *  - `ImportBatchController.java:44` — SA/TA/ACCOUNTANT only; a PROPERTY_MANAGER
 *    gets an access-denied panel and no fetch.
 *  - `ImportBatchService.reverse:177-180` — only a POSTED batch can be reversed.
 *  - `ImportBatchStatus` — REVERSED is the end of the line; a corrected
 *    spreadsheet comes back as a NEW batch, which the copy has to say.
 *  - `PortfolioImportController#template:78-79` — SA/TA only, one role narrower
 *    than the page it sits on.
 *  - `ImportBatchController.requireTenantSelected` — a platform admin with no
 *    organisation picked gets a 400, not an empty table.
 */

let role: string | null = "ACCOUNTANT";

vi.mock("next-auth/react", () => ({
    useSession: () => ({ data: role ? { user: { role, id: "user-1", tenantId: "tenant-1" } } : null }),
}));
vi.mock("@/i18n/routing", () => ({
    useRouter: () => ({ push: vi.fn() }),
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>
            {children}
        </a>
    ),
}));

const api = vi.hoisted(() => ({
    list: vi.fn(), reverse: vi.fn(), upload: vi.fn(), status: vi.fn(),
    post: vi.fn(), postStatus: vi.fn(), discard: vi.fn(),
}));
vi.mock("@/lib/api/cutover", async orig => {
    const m = await orig<typeof import("@/lib/api/cutover")>();
    return {
        ...m,
        cutoverApi: {
            ...m.cutoverApi,
            batches: {
                ...m.cutoverApi.batches,
                list: api.list, reverse: api.reverse,
                post: api.post, postStatus: api.postStatus, discard: api.discard,
            },
            contractImport: { ...m.cutoverApi.contractImport, upload: api.upload, status: api.status },
        },
    };
});

import ImportBatchesPage from "../page";
import { ApiError } from "@/lib/api/facilities";
import type { ContractImportResult } from "@/lib/api/cutover";

function jobResult(over: Partial<ContractImportResult> = {}): ContractImportResult {
    return {
        jobId: "job-1", status: "COMPLETED",
        propertiesCreated: 3, buildingsCreated: 1, unitsCreated: 40, rentersCreated: 38,
        leasesCreated: 38, chequesCreated: 152, chequesFromSheet: 152, bookingDepositsCreated: 0,
        importBatchId: "b-new", contractsCreated: 38, mappingsCreated: 9,
        errors: [], warnings: [],
        ...over,
    };
}

function postJob(over: Record<string, unknown> = {}) {
    return {
        jobId: "post-job-1", batchId: "b-posted", status: "COMPLETED",
        processed: 38, total: 38, errors: [],
        result: {
            batchId: "b-posted", repostOf: null, status: "POSTED",
            leasesPosted: 36, leasesSkipped: 1, leasesFailed: 1,
            chequesDeposited: 100, chequesCleared: 40, chequesBounced: 2,
            recognitionEntriesPosted: 210, journalsPosted: 412,
            leases: [
                { leaseId: "l1", externalContractRef: "C-001", outcome: "POSTED", reason: null, journals: 4, chequesDeposited: 3, chequesCleared: 1, chequesBounced: 0, recognitionEntriesPosted: 6 },
                { leaseId: "l2", externalContractRef: "C-002", outcome: "SKIPPED_ALREADY_POSTED", reason: null, journals: 0, chequesDeposited: 0, chequesCleared: 0, chequesBounced: 0, recognitionEntriesPosted: 0 },
                { leaseId: "l3", externalContractRef: "C-003", outcome: "FAILED", reason: "No rent receivable account for the property", journals: 0, chequesDeposited: 0, chequesCleared: 0, chequesBounced: 0, recognitionEntriesPosted: 0 },
            ],
            failures: [],
        },
        ...over,
    };
}

function discardResult(over: Record<string, unknown> = {}) {
    return {
        batchId: "b-draft", status: "DISCARDED",
        leasesDeleted: 12, unitsDeleted: 40, buildingsDeleted: 1, rentersDeleted: 11, propertiesDeleted: 2,
        kept: [{ type: "PROPERTY", id: "p1", name: "L'Olivier", reason: "It has contracts from another batch" }],
        ...over,
    };
}

/** Pick a file on the workbook input. */
function pickWorkbook(name = "cutover.xlsx", size?: number) {
    const input = screen.getByTestId("upload-cutover") as HTMLInputElement;
    const file = new File(["PK"], name, { type: "" });
    if (size !== undefined) Object.defineProperty(file, "size", { value: size, configurable: true });
    // configurable: a test that picks twice redefines this.
    Object.defineProperty(input, "files", { value: [file], configurable: true });
    fireEvent.change(input);
    return file;
}

function batch(over: Partial<ImportBatch> & { id: string }): ImportBatch {
    return {
        kind: "CONTRACT_IMPORT",
        status: "DRAFT",
        label: "September cut-over",
        importJobId: "job-1",
        leasesImported: 12,
        journalsPosted: 0,
        postedAt: null,
        reversedAt: null,
        discardedAt: null,
        createdAt: "2026-09-11T08:00:00Z",
        ...over,
    };
}

const ROWS: ImportBatch[] = [
    batch({ id: "b-draft" }),
    batch({
        id: "b-posted", status: "POSTED", label: "L'Olivier contracts",
        journalsPosted: 36, postedAt: "2026-09-12T09:00:00Z",
    }),
    batch({
        id: "b-reversed", status: "REVERSED", label: "First attempt",
        journalsPosted: 8, postedAt: "2026-09-10T09:00:00Z", reversedAt: "2026-09-10T11:00:00Z",
    }),
];

function renderPage() {
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <ImportBatchesPage />
        </NextIntlClientProvider>,
    );
}

beforeEach(() => {
    vi.resetAllMocks();
    role = "ACCOUNTANT";
    api.list.mockResolvedValue(ROWS);
    api.upload.mockResolvedValue({ jobId: "job-1" });
    api.status.mockResolvedValue(jobResult());
    api.post.mockResolvedValue({ jobId: "post-job-1", batchId: "b-posted" });
    api.postStatus.mockResolvedValue(postJob());
    api.discard.mockResolvedValue(discardResult());
    window.sessionStorage.clear();
});
afterEach(cleanup);

describe("import batches list", () => {
    it("renders one row per batch with its counts and status", async () => {
        renderPage();
        const posted = await screen.findByTestId("batch-row-b-posted");
        expect(posted).toHaveTextContent("L'Olivier contracts");
        expect(posted).toHaveTextContent("36");
        expect(within(posted).getByTestId("batch-status-b-posted")).toHaveAttribute("data-status", "POSTED");
    });

    /** ImportBatchService.reverse refuses anything that is not POSTED. */
    it("offers Reverse on a POSTED batch only", async () => {
        renderPage();
        await screen.findByTestId("batch-row-b-posted");
        expect(screen.getByTestId("reverse-batch-b-posted")).toBeInTheDocument();
        expect(screen.queryByTestId("reverse-batch-b-draft")).not.toBeInTheDocument();
        expect(screen.queryByTestId("reverse-batch-b-reversed")).not.toBeInTheDocument();
    });

    /**
     * Superseded by Task 11: a REVERSED batch is no longer the end of the line.
     * Its leases are still there, so it offers "Post again" (which creates a
     * successor batch) and "Discard". Only DISCARDED offers nothing.
     */
    it("offers a reversed batch Post again, and nothing else", async () => {
        renderPage();
        await screen.findByTestId("batch-row-b-reversed");
        expect(screen.getByTestId("post-batch-b-reversed")).toBeInTheDocument();
        expect(screen.queryByTestId("discard-batch-b-reversed")).not.toBeInTheDocument();
        expect(screen.queryByTestId("batch-final-b-reversed")).not.toBeInTheDocument();
    });

    it("reverses behind a confirmation that collects a date and a reason", async () => {
        api.reverse.mockResolvedValue(batch({ id: "b-posted", status: "REVERSED" }));
        renderPage();
        await screen.findByTestId("batch-row-b-posted");
        fireEvent.click(screen.getByTestId("reverse-batch-b-posted"));

        // The confirmation explains what it is about to do.
        expect(await screen.findByTestId("batch-reverse-date")).toBeInTheDocument();
        expect(screen.getByText(en.Cutover.reverseBatchHint)).toBeInTheDocument();

        fireEvent.change(screen.getByTestId("batch-reverse-date"), { target: { value: "2026-09-30" } });
        fireEvent.change(screen.getByTestId("batch-reverse-reason"), { target: { value: "bad rents" } });
        fireEvent.click(screen.getByTestId("confirm-reverse-batch"));

        await waitFor(() =>
            expect(api.reverse).toHaveBeenCalledWith("b-posted", { date: "2026-09-30", reason: "bad rents" }),
        );
        // And the list reloads so the row's status is the server's, not a guess.
        await waitFor(() => expect(api.list).toHaveBeenCalledTimes(2));
        expect(await screen.findByTestId("batch-reversed-banner")).toBeInTheDocument();
    });

    /**
     * A batch reversal is exempt from the period lock
     * (`PostingService.reverse` skips `assertOpen` when `importBatchId != null`),
     * so the date field carries no lock gate — refusing here would be the
     * mirror-image bug.
     */
    it("does not require a reversal date after the period lock", async () => {
        api.reverse.mockResolvedValue(batch({ id: "b-posted", status: "REVERSED" }));
        renderPage();
        await screen.findByTestId("batch-row-b-posted");
        fireEvent.click(screen.getByTestId("reverse-batch-b-posted"));
        fireEvent.change(await screen.findByTestId("batch-reverse-date"), { target: { value: "2026-01-31" } });
        expect(screen.getByTestId("confirm-reverse-batch")).toBeEnabled();
    });

    /**
     * `ReverseBatchDTO.date` is `@NotNull` (ImportBatchController.java:52) and
     * `ImportBatchService.reverse:181` refuses a null one — so an empty date
     * field must not reach the server.
     */
    it("will not reverse without a date", async () => {
        renderPage();
        await screen.findByTestId("batch-row-b-posted");
        fireEvent.click(screen.getByTestId("reverse-batch-b-posted"));

        fireEvent.change(await screen.findByTestId("batch-reverse-date"), { target: { value: "" } });
        await waitFor(() => expect(screen.getByTestId("confirm-reverse-batch")).toBeDisabled());
        expect(screen.getByTestId("batch-reverse-blocker")).toHaveTextContent(en.Cutover.reverseDateRequired);

        fireEvent.change(screen.getByTestId("batch-reverse-date"), { target: { value: "2026-09-30" } });
        await waitFor(() => expect(screen.getByTestId("confirm-reverse-batch")).toBeEnabled());
    });

    /** "This import batch is being reversed right now; try again" — RowLockedException. */
    it("surfaces the server's refusal when a reverse is rejected", async () => {
        api.reverse.mockRejectedValue(
            new ApiError(409, "This import batch is being reversed right now; try again"),
        );
        renderPage();
        await screen.findByTestId("batch-row-b-posted");
        fireEvent.click(screen.getByTestId("reverse-batch-b-posted"));
        fireEvent.click(await screen.findByTestId("confirm-reverse-batch"));
        expect(await screen.findByRole("alert")).toHaveTextContent("being reversed right now");
    });

    it("shows an empty state that explains what a batch is", async () => {
        api.list.mockResolvedValue([]);
        renderPage();
        expect(await screen.findByTestId("batches-empty")).toHaveTextContent(en.Cutover.noBatches);
        expect(screen.queryByTestId("batches-table")).not.toBeInTheDocument();
    });

    /** ImportBatchController.requireTenantSelected. */
    it("surfaces the tenant-less 400 in a retryable banner", async () => {
        api.list.mockRejectedValue(new ApiError(400, "Select an organisation first"));
        renderPage();
        expect(await screen.findByRole("alert")).toHaveTextContent("Select an organisation first");
        api.list.mockResolvedValue(ROWS);
        fireEvent.click(screen.getByText(en.Common.retry));
        expect(await screen.findByTestId("batch-row-b-draft")).toBeInTheDocument();
    });

    it.each([
        ["SUPER_ADMIN", true],
        ["TENANT_ADMIN", true],
        ["ACCOUNTANT", true],
        ["PROPERTY_MANAGER", false],
        ["RENTER", false],
    ])("admits %s: %s", async (r, allowed) => {
        role = r;
        renderPage();
        if (allowed) {
            expect(await screen.findByTestId("batch-row-b-draft")).toBeInTheDocument();
        } else {
            expect(await screen.findByTestId("import-batches-access-denied")).toBeInTheDocument();
            expect(api.list).not.toHaveBeenCalled();
        }
    });

    it("does not fetch until the session resolves", async () => {
        role = null;
        renderPage();
        expect(screen.getByTestId("import-batches-loading")).toBeInTheDocument();
        expect(api.list).not.toHaveBeenCalled();
    });

    /**
     * The CUT-OVER template is its own route with its own gate
     * (PortfolioImportController CUTOVER_ROLES, :109) and admits ACCOUNTANT —
     * unlike the v1 `/template`, which this page used to link and which would
     * have 403'd them.
     */
    it.each([
        ["SUPER_ADMIN", true],
        ["TENANT_ADMIN", true],
        ["ACCOUNTANT", true],
        ["PROPERTY_MANAGER", false],
    ])("offers the cut-over template download to %s: %s", async (r, offered) => {
        role = r;
        renderPage();
        if (!offered) {
            expect(await screen.findByTestId("import-batches-access-denied")).toBeInTheDocument();
            return;
        }
        await screen.findByTestId("batch-row-b-draft");
        expect(screen.getByTestId("download-template")).toHaveAttribute(
            "href",
            "/api/proxy/v1/import/portfolio/cutover/template",
        );
    });
});

describe("cut-over contract import", () => {
    it("refuses a non-xlsx and an oversize workbook before they travel", async () => {
        renderPage();
        await screen.findByTestId("batch-row-b-draft");

        pickWorkbook("cutover.csv");
        await waitFor(() =>
            expect(screen.getByTestId("import-upload-error")).toHaveTextContent(en.Cutover.workbookWrongType),
        );
        expect(api.upload).not.toHaveBeenCalled();

        pickWorkbook("cutover.xlsx", 11 * 1024 * 1024);
        await waitFor(() =>
            expect(screen.getByTestId("import-upload-error")).toHaveTextContent(en.Cutover.workbookTooBig),
        );
        expect(api.upload).not.toHaveBeenCalled();
    });

    /**
     * Split from the completion case on purpose: the hook's backoff means the
     * second poll is a second away, and the timing of that is already covered by
     * `useImportJobPolling.test.tsx` under fake timers. What this page owes is
     * the right thing on screen for each state.
     */
    it("shows the job as running while it validates", async () => {
        api.status.mockResolvedValue(jobResult({ status: "VALIDATING", importBatchId: null }));
        renderPage();
        await screen.findByTestId("batch-row-b-draft");

        const file = pickWorkbook();
        await waitFor(() => expect(api.upload).toHaveBeenCalledWith(file));
        expect(await screen.findByTestId("import-job-status")).toHaveAttribute("data-status", "VALIDATING");
        expect(screen.getByTestId("import-job-status")).toHaveTextContent(en.Cutover.importRunning);
        expect(screen.queryByTestId("import-success")).not.toBeInTheDocument();
    });

    it("uploads the workbook and reports completion", async () => {
        renderPage();
        await screen.findByTestId("batch-row-b-draft");

        const file = pickWorkbook();
        await waitFor(() => expect(api.upload).toHaveBeenCalledWith(file));
        await waitFor(() =>
            expect(screen.getByTestId("import-job-status")).toHaveAttribute("data-status", "COMPLETED"),
        );
        expect(screen.getByTestId("import-success")).toHaveTextContent(en.Cutover.importCompleted);
    });

    it("shows the counts the DTO gives and links to the new batch", async () => {
        renderPage();
        await screen.findByTestId("batch-row-b-draft");
        pickWorkbook();

        const summary = await screen.findByTestId("import-counts");
        for (const n of ["3", "40", "38", "152"]) expect(summary).toHaveTextContent(n);
        // The pointer to the batch the upload made, which outlives the panel.
        expect(await screen.findByTestId("import-view-batch-b-new")).toBeInTheDocument();
    });

    /** The batch the import just made is highlighted, so it is not a hunt. */
    it("highlights the newly imported batch in the table", async () => {
        api.list.mockResolvedValue([...ROWS, batch({ id: "b-new", label: "Cut-over" })]);
        renderPage();
        await screen.findByTestId("batch-row-b-draft");
        pickWorkbook();

        await waitFor(() => expect(screen.getByTestId("batch-row-b-new")).toHaveAttribute("data-imported", "true"));
        expect(screen.getByTestId("batch-row-b-draft")).toHaveAttribute("data-imported", "false");
        // And the list reloads so the new batch is actually there.
        expect(api.list.mock.calls.length).toBeGreaterThan(1);
    });

    /**
     * ContractImportPersistService runs in one transaction and a validation
     * failure writes nothing — the copy has to say so, or the accountant goes
     * looking for half-imported properties.
     */
    it("lists every validation error with sheet, row and column, and says nothing was saved", async () => {
        api.status.mockResolvedValue(
            jobResult({
                status: "VALIDATION_FAILED", importBatchId: null,
                propertiesCreated: 0, unitsCreated: 0, rentersCreated: 0, leasesCreated: 0, chequesCreated: 0,
                errors: [
                    { sheet: "Contracts", row: 7, field: "DebitAccount", message: "No account named 'Rent Recievable'" },
                    { sheet: "Contracts", row: 9, field: "EjariNumber", message: "Required" },
                    { sheet: "Cheques", row: 22, field: "ChequeNumber", message: "Duplicate cheque number 000431" },
                ],
            }),
        );
        renderPage();
        await screen.findByTestId("batch-row-b-draft");
        pickWorkbook();

        expect(await screen.findByTestId("import-validation-failed")).toHaveTextContent(
            en.Cutover.importValidationFailed,
        );
        const table = screen.getByTestId("import-errors-table");
        expect(within(table).getByTestId("import-error-0")).toHaveTextContent("Contracts");
        expect(within(table).getByTestId("import-error-0")).toHaveTextContent("7");
        expect(within(table).getByTestId("import-error-0")).toHaveTextContent("DebitAccount");
        expect(within(table).getByTestId("import-error-2")).toHaveTextContent("Duplicate cheque number 000431");
        expect(screen.queryByTestId("import-view-batch")).not.toBeInTheDocument();
    });

    it("pages a long error list rather than dropping any of it", async () => {
        api.status.mockResolvedValue(
            jobResult({
                status: "VALIDATION_FAILED", importBatchId: null,
                errors: Array.from({ length: 45 }, (_, i) => ({
                    sheet: "Contracts", row: i + 2, field: "Rent", message: `Problem ${i + 1}`,
                })),
            }),
        );
        renderPage();
        await screen.findByTestId("batch-row-b-draft");
        pickWorkbook();

        await screen.findByTestId("import-errors-table");
        expect(screen.getByTestId("import-error-0")).toHaveTextContent("Problem 1");
        expect(screen.queryByTestId("import-error-25")).not.toBeInTheDocument();
        expect(screen.getByTestId("import-errors-title")).toHaveTextContent("45 problems");
    });

    it("reports a failed import as saving nothing", async () => {
        api.status.mockResolvedValue(jobResult({ status: "FAILED", importBatchId: null, errors: [] }));
        renderPage();
        await screen.findByTestId("batch-row-b-draft");
        pickWorkbook();
        expect(await screen.findByTestId("import-failed")).toHaveTextContent(en.Cutover.importFailed);
    });

    it("surfaces the server's refusal when the upload itself is rejected", async () => {
        api.upload.mockRejectedValue(new ApiError(400, "Only .xlsx workbooks are supported; this file is not one"));
        renderPage();
        await screen.findByTestId("batch-row-b-draft");
        pickWorkbook();
        expect(await screen.findByTestId("import-upload-error")).toHaveTextContent("not one");
    });

    it("can be dismissed once it is done", async () => {
        renderPage();
        await screen.findByTestId("batch-row-b-draft");
        pickWorkbook();
        await screen.findByTestId("import-success");
        fireEvent.click(screen.getByTestId("import-dismiss"));
        await waitFor(() => expect(screen.queryByTestId("import-success")).not.toBeInTheDocument());
    });

    /** The implementer's notes, surfaced where they are needed. */
    it("shows the workbook help before anything is uploaded", async () => {
        renderPage();
        await screen.findByTestId("batch-row-b-draft");
        const help = screen.getByTestId("cutover-help");
        expect(help).toHaveTextContent(en.Cutover.cutoverHelpNewProperties);
        expect(help).toHaveTextContent(en.Cutover.cutoverHelpAccounts);
        expect(help).toHaveTextContent(en.Cutover.cutoverHelpCreditAccount);
        expect(help).toHaveTextContent(en.Cutover.cutoverHelpEjari);
    });

    it("offers no upload control to a role the controller refuses", async () => {
        role = "PROPERTY_MANAGER";
        renderPage();
        await screen.findByTestId("import-batches-access-denied");
        expect(screen.queryByTestId("upload-cutover")).not.toBeInTheDocument();
    });
});

describe("bulk post", () => {
    it("offers Post on a DRAFT and not on a DISCARDED batch", async () => {
        api.list.mockResolvedValue([
            batch({ id: "b-draft" }),
            batch({ id: "b-gone", status: "DISCARDED", discardedAt: "2026-09-13T08:00:00Z" }),
        ]);
        renderPage();
        await screen.findByTestId("batch-row-b-draft");
        expect(screen.getByTestId("post-batch-b-draft")).toBeInTheDocument();
        expect(screen.queryByTestId("post-batch-b-gone")).not.toBeInTheDocument();
        expect(screen.queryByTestId("discard-batch-b-gone")).not.toBeInTheDocument();
        expect(screen.queryByTestId("reverse-batch-b-gone")).not.toBeInTheDocument();
        expect(screen.getByTestId("batch-final-b-gone")).toHaveTextContent(en.Cutover.discardedBatchFinal);
    });

    it("explains what posting does before it does it", async () => {
        renderPage();
        await screen.findByTestId("batch-row-b-draft");
        fireEvent.click(screen.getByTestId("post-batch-b-draft"));

        const dialog = await screen.findByTestId("confirm-post-batch");
        expect(screen.getByText(/own contract date/)).toBeInTheDocument();
        expect(screen.getByText(/No e-mails are sent/)).toBeInTheDocument();
        fireEvent.click(dialog);
        await waitFor(() => expect(api.post).toHaveBeenCalledWith("b-draft"));
    });

    it("shows progress while the job runs", async () => {
        api.postStatus.mockResolvedValue(postJob({ status: "POSTING", processed: 12, total: 38, result: null }));
        renderPage();
        await screen.findByTestId("batch-row-b-draft");
        fireEvent.click(screen.getByTestId("post-batch-b-draft"));
        fireEvent.click(await screen.findByTestId("confirm-post-batch"));

        const progress = await screen.findByTestId("post-progress");
        expect(progress).toHaveTextContent("12");
        expect(progress).toHaveTextContent("38");
        expect(screen.queryByTestId("post-results-table")).not.toBeInTheDocument();
    });

    it("lists every contract outcome, failures first", async () => {
        renderPage();
        await screen.findByTestId("batch-row-b-draft");
        fireEvent.click(screen.getByTestId("post-batch-b-draft"));
        fireEvent.click(await screen.findByTestId("confirm-post-batch"));

        await screen.findByTestId("post-results-table");
        // Failures first: C-003 failed, so it leads.
        expect(screen.getByTestId("post-result-0")).toHaveTextContent("C-003");
        expect(screen.getByTestId("post-result-0")).toHaveTextContent("No rent receivable account");
        expect(screen.getByTestId("post-result-0")).toHaveAttribute("data-outcome", "FAILED");
        expect(screen.getByTestId("post-result-1")).toHaveAttribute("data-outcome", "POSTED");
        expect(screen.getByTestId("post-summary")).toHaveTextContent("412");
    });

    /**
     * Evidence, not optimism: a POSTED batch offers nothing until a run of THIS
     * batch has actually left a FAILED contract behind.
     */
    it("offers no action on a posted batch until a failure is known", async () => {
        renderPage();
        await screen.findByTestId("batch-row-b-posted");
        expect(screen.queryByTestId("post-batch-b-posted")).not.toBeInTheDocument();
    });

    it("offers Retry failed contracts once a run has reported one", async () => {
        renderPage();
        await screen.findByTestId("batch-row-b-draft");
        // Post the draft; its result carries one FAILED contract.
        fireEvent.click(screen.getByTestId("post-batch-b-draft"));
        fireEvent.click(await screen.findByTestId("confirm-post-batch"));
        await screen.findByTestId("post-results-table");

        // The result is for b-posted (the job's batchId), so that row now offers a retry.
        await waitFor(() => expect(screen.getByTestId("post-batch-b-posted")).toBeInTheDocument());
        expect(screen.getByTestId("post-batch-b-posted")).toHaveTextContent(en.Cutover.retryFailed);
    });

    it("offers nothing when the run left no failures", async () => {
        api.postStatus.mockResolvedValue(
            postJob({
                result: {
                    ...postJob().result, leasesFailed: 0,
                    leases: [
                        { leaseId: "l1", externalContractRef: "C-001", outcome: "POSTED", reason: null, journals: 4, chequesDeposited: 0, chequesCleared: 0, chequesBounced: 0, recognitionEntriesPosted: 0 },
                    ],
                },
            }),
        );
        renderPage();
        await screen.findByTestId("batch-row-b-draft");
        fireEvent.click(screen.getByTestId("post-batch-b-draft"));
        fireEvent.click(await screen.findByTestId("confirm-post-batch"));
        await screen.findByTestId("post-results-table");
        expect(screen.queryByTestId("post-batch-b-posted")).not.toBeInTheDocument();
    });

    /** A REVERSED batch re-posts as a SUCCESSOR; the copy has to say so. */
    it("calls a reversed batch's post 'Post again' and warns it makes a new batch", async () => {
        renderPage();
        await screen.findByTestId("batch-row-b-reversed");
        const button = screen.getByTestId("post-batch-b-reversed");
        expect(button).toHaveTextContent(en.Cutover.postAgain);
        fireEvent.click(button);
        await screen.findByTestId("confirm-post-batch");
        expect(screen.getByText(/creates a NEW batch/)).toBeInTheDocument();
    });

    it("surfaces a batch-level refusal", async () => {
        api.post.mockRejectedValue(
            new ApiError(400, "Set the books start date in Settings → Fiscal before posting a cut-over batch"),
        );
        renderPage();
        await screen.findByTestId("batch-row-b-draft");
        fireEvent.click(screen.getByTestId("post-batch-b-draft"));
        fireEvent.click(await screen.findByTestId("confirm-post-batch"));
        expect(await screen.findByRole("alert")).toHaveTextContent("books start date");
    });

    it("reloads the list when the post finishes", async () => {
        renderPage();
        await screen.findByTestId("batch-row-b-draft");
        const before = api.list.mock.calls.length;
        fireEvent.click(screen.getByTestId("post-batch-b-draft"));
        fireEvent.click(await screen.findByTestId("confirm-post-batch"));
        await screen.findByTestId("post-results-table");
        await waitFor(() => expect(api.list.mock.calls.length).toBeGreaterThan(before));
    });
});

describe("discard", () => {
    /**
     * Controller ruling landing now: "A reversed batch keeps its contracts; post
     * it again or leave it reversed." DRAFT alone discards.
     */
    it("offers Discard on a DRAFT batch and on nothing else", async () => {
        renderPage();
        await screen.findByTestId("batch-row-b-draft");
        expect(screen.getByTestId("discard-batch-b-draft")).toBeInTheDocument();
        expect(screen.queryByTestId("discard-batch-b-reversed")).not.toBeInTheDocument();
        expect(screen.queryByTestId("discard-batch-b-posted")).not.toBeInTheDocument();
    });

    it("surfaces the reversed-batch refusal if it ever arrives", async () => {
        api.discard.mockRejectedValue(
            new ApiError(400, "A reversed batch keeps its contracts; post it again or leave it reversed"),
        );
        renderPage();
        await screen.findByTestId("batch-row-b-draft");
        fireEvent.click(screen.getByTestId("discard-batch-b-draft"));
        fireEvent.click(await screen.findByTestId("confirm-discard-batch"));
        expect(await screen.findByRole("alert")).toHaveTextContent("keeps its contracts");
    });

    it("discards behind a destructive confirmation and summarises what went and what stayed", async () => {
        renderPage();
        await screen.findByTestId("batch-row-b-draft");
        fireEvent.click(screen.getByTestId("discard-batch-b-draft"));
        fireEvent.click(await screen.findByTestId("confirm-discard-batch"));

        await waitFor(() => expect(api.discard).toHaveBeenCalledWith("b-draft"));
        const summary = await screen.findByTestId("discard-summary");
        expect(summary).toHaveTextContent("12");
        expect(summary).toHaveTextContent("40");

        const kept = screen.getByTestId("discard-kept-table");
        expect(within(kept).getByTestId("discard-kept-0")).toHaveTextContent("L'Olivier");
        expect(within(kept).getByTestId("discard-kept-0")).toHaveTextContent("another batch");
        await waitFor(() => expect(api.list.mock.calls.length).toBeGreaterThan(1));
    });

    it("surfaces the server's refusal", async () => {
        api.discard.mockRejectedValue(
            new ApiError(400, "Import batch is POSTED; only a DRAFT or REVERSED batch can be discarded"),
        );
        renderPage();
        await screen.findByTestId("batch-row-b-draft");
        fireEvent.click(screen.getByTestId("discard-batch-b-draft"));
        fireEvent.click(await screen.findByTestId("confirm-discard-batch"));
        expect(await screen.findByRole("alert")).toHaveTextContent("only a DRAFT or REVERSED");
    });
});

describe("journals drill-through", () => {
    it("links a posted batch to its own journals", async () => {
        renderPage();
        await screen.findByTestId("batch-row-b-posted");
        expect(screen.getByTestId("view-journals-b-posted")).toHaveAttribute(
            "href",
            "/dashboard/finance/journals?importBatchId=b-posted",
        );
        // Nothing to drill into on a batch that wrote no journals.
        expect(screen.queryByTestId("view-journals-b-draft")).not.toBeInTheDocument();
    });

    it("no longer says posting is unavailable", async () => {
        renderPage();
        await screen.findByTestId("batch-row-b-draft");
        expect(screen.queryByTestId("bulk-post-unavailable")).not.toBeInTheDocument();
    });
});

describe("carried review items", () => {
    /** (e) Dismissing the import result must not un-highlight the batch it made. */
    it("keeps the imported batch highlighted after the import panel is dismissed", async () => {
        api.list.mockResolvedValue([...ROWS, batch({ id: "b-new", label: "Cut-over" })]);
        renderPage();
        await screen.findByTestId("batch-row-b-draft");
        pickWorkbook();

        await waitFor(() => expect(screen.getByTestId("batch-row-b-new")).toHaveAttribute("data-imported", "true"));
        fireEvent.click(screen.getByTestId("import-dismiss"));
        await waitFor(() => expect(screen.queryByTestId("import-success")).not.toBeInTheDocument());
        expect(screen.getByTestId("batch-row-b-new")).toHaveAttribute("data-imported", "true");
        expect(screen.getByTestId("import-view-batch-b-new")).toBeInTheDocument();
    });
});

describe("bulk post: resume and contention", () => {
    /** (b) A reload rejoins the post, the way the workbook upload already does. */
    it("resumes a running bulk post after a reload", async () => {
        const { importJobStorageKey } = await import("@/hooks/useImportJobPolling");
        window.sessionStorage.setItem(
            importJobStorageKey("bulk-post:b-posted", { tenantId: "tenant-1", userId: "user-1" }),
            "post-job-1",
        );
        api.postStatus.mockResolvedValue(postJob({ status: "POSTING", processed: 5, total: 38, result: null }));

        renderPage();
        await waitFor(() => expect(api.postStatus).toHaveBeenCalledWith("b-posted", "post-job-1"));
        expect(await screen.findByTestId("post-progress")).toHaveTextContent("5");
    });

    /** (c) One job at a time: posting while a workbook is still validating is a trap. */
    it("disables Post while an upload job is in flight", async () => {
        api.status.mockResolvedValue(jobResult({ status: "VALIDATING", importBatchId: null }));
        renderPage();
        await screen.findByTestId("batch-row-b-draft");
        pickWorkbook();

        await waitFor(() => expect(screen.getByTestId("post-batch-b-draft")).toBeDisabled());
        expect(screen.getByTestId("post-blocked")).toHaveTextContent(en.Cutover.postBlockedByUpload);
    });
});
