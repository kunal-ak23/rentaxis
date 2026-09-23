import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../messages/en.json";
import ChequeRegisterPage from "../page";
import ClearBatchDialog from "@/components/cheques/ClearBatchDialog";
import { ApiError, type Cheque } from "@/lib/api/leasing";
import type { Page } from "@/lib/api/ledger";

/**
 * Gap #57: deposits go to the bank in batches and the bank clears them as one
 * credit, but the register could only clear one dialog at a time. Filtered to
 * Deposited, the register now offers row checkboxes and "Clear Batch (n)".
 */

vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
}));
vi.mock("next/navigation", () => ({
    useSearchParams: () => new URLSearchParams(),
}));
vi.mock("next-auth/react", () => ({
    useSession: () => ({ data: { user: { role: "TENANT_ADMIN", name: "Admin" } } }),
}));
vi.mock("@/components/cheques/UnappliedPaymentsTile", () => ({ default: () => null }));
vi.mock("@/components/finance/useNameLookup", () => ({
    useNameLookup: () => ({ options: [], byId: new Map() }),
}));

const list = vi.fn();
const clearBatch = vi.fn();
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return {
        ...m,
        chequeApi: {
            ...m.chequeApi,
            list: (...a: unknown[]) => list(...(a as [])),
            summary: async () => null,
            aging: async () => null,
            clearBatch: (...a: unknown[]) => clearBatch(...(a as [])),
        },
    };
});

function cheque(over: Partial<Cheque> & { id: string; seqNo: number }): Cheque {
    return {
        leaseId: "l1", propertyId: "p1", unitId: "u1", renterId: "r1",
        propertyName: "Miftah Residences", unitIdentifier: "A-102", renterName: "Omar",
        postingDate: "2026-06-01", chequeNumber: "700101", chequeDate: "2026-06-01",
        payeeBank: "ENBD", payerName: null, debitAccountId: "acc-1", debitAccountName: "Bank",
        amount: 5000, narration: null, mode: "PDC", status: "DEPOSITED",
        failureReason: null, replacesId: null, replacedById: null, imageUrl: null,
        depositedAt: "2026-06-02", clearedAt: null, bouncedAt: null, returnedAt: null,
        pdrJournalId: null, crtJournalId: null, cbrJournalId: null, penaltyAssessmentId: null,
        due: false, overdue: false, daysOverdue: 0,
        ...over,
    };
}

function page(content: Cheque[]): Page<Cheque> {
    return { content, totalElements: content.length, totalPages: 1, number: 0, size: 25 };
}

const ROWS = [
    cheque({ id: "c1", seqNo: 1, chequeNumber: "700101", amount: 31500 }),
    cheque({ id: "c2", seqNo: 2, chequeNumber: "700102", amount: 10000 }),
    cheque({ id: "c3", seqNo: 3, chequeNumber: "700103", amount: 2000 }),
];

beforeEach(() => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => [] })) as unknown as typeof fetch;
    list.mockReset();
    clearBatch.mockReset();
    list.mockResolvedValue(page(ROWS));
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

function withIntl(node: React.ReactNode) {
    return <NextIntlClientProvider locale="en" messages={en}>{node}</NextIntlClientProvider>;
}

async function filterToDeposited() {
    render(withIntl(<ChequeRegisterPage />));
    await screen.findByTestId("cheque-row-c1");
    fireEvent.change(screen.getByTestId("cheque-status-filter"), { target: { value: "DEPOSITED" } });
    fireEvent.click(screen.getByTestId("cheque-filter-apply"));
    await screen.findByTestId("clear-batch-open");
}

describe("Cheque register batch clear (#57)", () => {
    it("offers no row checkboxes unless the register is filtered to Deposited", async () => {
        render(withIntl(<ChequeRegisterPage />));
        await screen.findByTestId("cheque-row-c1");
        expect(screen.queryByTestId("clear-batch-select-c1")).toBeNull();
        expect(screen.queryByTestId("clear-batch-open")).toBeNull();
    });

    it("clears exactly the ticked ids with the chosen date and narration", async () => {
        clearBatch.mockResolvedValue([]);
        await filterToDeposited();

        const open = screen.getByTestId("clear-batch-open");
        expect(open).toBeDisabled();
        fireEvent.click(screen.getByTestId("clear-batch-select-c1"));
        fireEvent.click(screen.getByTestId("clear-batch-select-c3"));
        expect(open).toHaveTextContent(`${en.Cheques.clearBatch} (2)`);
        expect(screen.getByTestId("clear-batch-selected-total")).toHaveTextContent("33,500.00");

        fireEvent.click(open);
        expect(await screen.findByTestId("clear-batch-total")).toHaveTextContent("33,500.00");
        fireEvent.change(screen.getByTestId("clear-batch-date"), { target: { value: "2026-07-08" } });
        fireEvent.change(screen.getByTestId("clear-batch-narration"), { target: { value: "ENBD credit 8 Jul" } });
        fireEvent.click(screen.getByTestId("clear-batch-confirm"));

        await waitFor(() => expect(clearBatch).toHaveBeenCalledTimes(1));
        expect(clearBatch).toHaveBeenCalledWith({
            chequeIds: ["c1", "c3"],
            clearingDate: "2026-07-08",
            narration: "ENBD credit 8 Jul",
        });
        // The register reloads and the selection is spent.
        await waitFor(() => expect(list.mock.calls.length).toBeGreaterThanOrEqual(3));
        await waitFor(() => expect(screen.getByTestId("clear-batch-open")).toHaveTextContent("(0)"));
    });
});

describe("ClearBatchDialog", () => {
    it("shows the server's refusal verbatim and stays open", async () => {
        clearBatch.mockRejectedValue(new ApiError(400, "These cheques cannot be cleared: 700102 is CLEARED. Only DEPOSITED cheques can be cleared, and nothing was cleared."));
        const onDone = vi.fn();
        render(withIntl(
            <ClearBatchDialog open chequeIds={["c1", "c2"]} total={41500} onClose={() => {}} onDone={onDone} />,
        ));

        fireEvent.click(screen.getByTestId("clear-batch-confirm"));

        expect(await screen.findByTestId("clear-batch-error")).toHaveTextContent("700102 is CLEARED");
        expect(onDone).not.toHaveBeenCalled();
        expect(clearBatch).toHaveBeenCalledWith(expect.objectContaining({ chequeIds: ["c1", "c2"], narration: null }));
    });
});
