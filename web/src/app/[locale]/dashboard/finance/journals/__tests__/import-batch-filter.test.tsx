import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../messages/en.json";

/**
 * The journals list, filtered to one cut-over batch.
 *
 * `JournalController.list` gained an optional `importBatchId` (:52), which is
 * what the Import Batches page's "View journals" link uses to drill through. The
 * filter arrives in the URL, so it has to be visible and removable — a list
 * quietly showing a fraction of the ledger with nothing saying why is worse than
 * no filter at all.
 */

const search = { current: new URLSearchParams("") };

vi.mock("next/navigation", () => ({ useSearchParams: () => search.current }));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "ACCOUNTANT" } } }) }));
vi.mock("@/i18n/routing", () => ({
    useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
}));
vi.mock("@/components/finance/useNameLookup", () => ({
    useNameLookup: () => ({ name: () => "", options: [], loading: false }),
}));

const api = vi.hoisted(() => ({ list: vi.fn(), docTypes: vi.fn() }));
vi.mock("@/lib/api/ledger", async orig => {
    const m = await orig<typeof import("@/lib/api/ledger")>();
    return {
        ...m,
        ledgerApi: { ...m.ledgerApi, journals: { ...m.ledgerApi.journals, list: api.list, docTypes: api.docTypes } },
    };
});

import JournalsPage from "../page";

function renderPage() {
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <JournalsPage />
        </NextIntlClientProvider>,
    );
}

beforeEach(() => {
    vi.resetAllMocks();
    search.current = new URLSearchParams("");
    api.docTypes.mockResolvedValue([]);
    api.list.mockResolvedValue({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 25 });
});
afterEach(cleanup);

describe("journals filtered by import batch", () => {
    it("sends no importBatchId when the URL carries none", async () => {
        renderPage();
        await waitFor(() => expect(api.list).toHaveBeenCalled());
        expect(api.list.mock.calls.at(-1)![0].importBatchId).toBeUndefined();
        expect(screen.queryByTestId("import-batch-chip")).not.toBeInTheDocument();
    });

    it("applies the batch from the URL and shows a chip", async () => {
        search.current = new URLSearchParams("importBatchId=b-7");
        renderPage();
        await waitFor(() =>
            expect(api.list).toHaveBeenCalledWith(expect.objectContaining({ importBatchId: "b-7" })),
        );
        expect(screen.getByTestId("import-batch-chip")).toHaveTextContent(en.Ledger.importBatchFilter);
    });

    it("drops the filter when the chip is removed", async () => {
        search.current = new URLSearchParams("importBatchId=b-7");
        renderPage();
        await waitFor(() =>
            expect(api.list).toHaveBeenCalledWith(expect.objectContaining({ importBatchId: "b-7" })),
        );

        fireEvent.click(screen.getByTestId("import-batch-chip-remove"));
        await waitFor(() =>
            expect(api.list.mock.calls.at(-1)![0].importBatchId).toBeUndefined(),
        );
        expect(screen.queryByTestId("import-batch-chip")).not.toBeInTheDocument();
    });
});
