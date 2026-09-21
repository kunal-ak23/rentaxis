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

const api = vi.hoisted(() => ({ list: vi.fn(), reverse: vi.fn() }));
vi.mock("@/lib/api/cutover", async orig => {
    const m = await orig<typeof import("@/lib/api/cutover")>();
    return { ...m, cutoverApi: { batches: { ...m.cutoverApi.batches, list: api.list, reverse: api.reverse } } };
});

import ImportBatchesPage from "../page";
import { ApiError } from "@/lib/api/facilities";

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
    vi.clearAllMocks();
    role = "ACCOUNTANT";
    api.list.mockResolvedValue(ROWS);
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
     * PortfolioImportController#template is SA/TA — one role narrower than this
     * page. An accountant offered that link gets a 403 on click.
     */
    it.each([
        ["SUPER_ADMIN", true],
        ["TENANT_ADMIN", true],
        ["ACCOUNTANT", false],
    ])("offers the template download to %s: %s", async (r, offered) => {
        role = r;
        renderPage();
        await screen.findByTestId("batch-row-b-draft");
        if (offered) {
            expect(screen.getByTestId("download-template")).toHaveAttribute(
                "href",
                "/api/proxy/v1/import/portfolio/template",
            );
        } else {
            expect(screen.queryByTestId("download-template")).not.toBeInTheDocument();
        }
    });
});
