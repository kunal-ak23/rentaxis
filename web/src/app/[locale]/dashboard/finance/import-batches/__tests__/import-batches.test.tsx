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

vi.mock("next-auth/react", () => ({ useSession: () => ({ data: role ? { user: { role } } : null }) }));
vi.mock("@/i18n/routing", () => ({
    useRouter: () => ({ push: vi.fn() }),
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>
            {children}
        </a>
    ),
}));

const api = vi.hoisted(() => ({ list: vi.fn(), reverse: vi.fn(), upload: vi.fn(), status: vi.fn() }));
vi.mock("@/lib/api/cutover", async orig => {
    const m = await orig<typeof import("@/lib/api/cutover")>();
    return {
        ...m,
        cutoverApi: {
            ...m.cutoverApi,
            batches: { ...m.cutoverApi.batches, list: api.list, reverse: api.reverse },
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

    /** A reversed batch is history — the screen has to say a re-import is a new batch. */
    it("says a reversed batch can never be re-posted", async () => {
        renderPage();
        const reversed = await screen.findByTestId("batch-row-b-reversed");
        expect(within(reversed).getByTestId("batch-final-b-reversed")).toHaveTextContent(
            en.Cutover.reversedBatchFinal,
        );
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
        expect(screen.getByTestId("import-view-batch")).toBeInTheDocument();
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

    /** Task 11 has not landed; the page says so rather than leaving a gap. */
    it("says posting a batch is not available yet", async () => {
        renderPage();
        await screen.findByTestId("batch-row-b-draft");
        expect(screen.getByTestId("bulk-post-unavailable")).toHaveTextContent(en.Cutover.bulkPostNotAvailable);
    });

    it("offers no upload control to a role the controller refuses", async () => {
        role = "PROPERTY_MANAGER";
        renderPage();
        await screen.findByTestId("import-batches-access-denied");
        expect(screen.queryByTestId("upload-cutover")).not.toBeInTheDocument();
    });
});
