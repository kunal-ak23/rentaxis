import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../../messages/en.json";
import ChequeCollectionPage from "../page";
import { ApiError, type Cheque } from "@/lib/api/leasing";
import type { Page } from "@/lib/api/ledger";

vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
}));
vi.mock("next-auth/react", () => ({
    useSession: () => ({ data: { user: { role: "TENANT_ADMIN", name: "Admin" } } }),
}));
vi.mock("@/components/finance/AccountPicker", () => ({
    default: () => <div data-testid="account-picker" />,
}));

const toDeposit = vi.fn();
const depositBatch = vi.fn();
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return {
        ...m,
        chequeApi: {
            ...m.chequeApi,
            toDeposit: (...a: unknown[]) => toDeposit(...(a as [])),
            depositBatch: (...a: unknown[]) => depositBatch(...(a as [])),
        },
    };
});

function cheque(over: Partial<Cheque> & { id: string; seqNo: number }): Cheque {
    return {
        leaseId: "l1", propertyId: "p1", unitId: "u1", renterId: "r1",
        propertyName: "Sample Heights", unitIdentifier: "A-101", renterName: "Sample Renter One",
        postingDate: "2026-06-01", chequeNumber: "000101", chequeDate: "2026-06-01",
        payeeBank: "ENBD", payerName: null, debitAccountId: "acc-1", debitAccountName: "PDC Receivable",
        amount: 5000, narration: null, mode: "PDC", status: "REGISTERED",
        failureReason: null, replacesId: null, replacedById: null, imageUrl: null,
        depositedAt: null, clearedAt: null, bouncedAt: null, returnedAt: null,
        pdrJournalId: null, crtJournalId: null, cbrJournalId: null, penaltyAssessmentId: null,
        due: true, overdue: false, daysOverdue: 0,
        ...over,
    };
}

function page(content: Cheque[]): Page<Cheque> {
    return { content, totalElements: content.length, totalPages: 1, number: 0, size: 25 };
}

beforeEach(() => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => [] })) as unknown as typeof fetch;
    toDeposit.mockReset();
    depositBatch.mockReset();
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

function renderPage() {
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <ChequeCollectionPage />
        </NextIntlClientProvider>,
    );
}

describe("Cheque collection batch deposit", () => {
    it("deposits exactly the selected ids with the chosen date", async () => {
        toDeposit.mockResolvedValue(page([
            cheque({ id: "c1", seqNo: 1, chequeNumber: "000101", amount: 5000 }),
            cheque({ id: "c2", seqNo: 2, chequeNumber: "000102", amount: 3000 }),
        ]));
        depositBatch.mockResolvedValue([]);

        renderPage();
        await waitFor(() => screen.getByTestId("cheque-row-c1"));

        fireEvent.click(screen.getByTestId("collection-select-c1"));
        fireEvent.click(screen.getByTestId("collection-select-c2"));
        expect(screen.getByTestId("collection-selected-total")).toHaveTextContent("8,000.00");
        expect(screen.getByTestId("collection-selected-total")).toHaveTextContent("2");

        fireEvent.click(screen.getByTestId("collection-deposit-selected"));
        const dateInput = await screen.findByTestId("deposit-batch-date");
        fireEvent.change(dateInput, { target: { value: "2026-06-10" } });
        fireEvent.click(screen.getByTestId("deposit-batch-confirm"));

        await waitFor(() => expect(depositBatch).toHaveBeenCalled());
        expect(depositBatch).toHaveBeenCalledWith({
            chequeIds: expect.arrayContaining(["c1", "c2"]),
            date: "2026-06-10",
            debitAccountId: null,
        });
        expect((depositBatch.mock.calls[0][0] as { chequeIds: string[] }).chequeIds).toHaveLength(2);
    });

    it("keeps the selection and shows the server's message verbatim on refusal", async () => {
        toDeposit.mockResolvedValue(page([
            cheque({ id: "c1", seqNo: 1, chequeNumber: "000101", amount: 5000 }),
            cheque({ id: "c2", seqNo: 2, chequeNumber: "000102", amount: 3000 }),
        ]));
        depositBatch.mockRejectedValue(new ApiError(400, "Cheque 000102 is already DEPOSITED"));

        renderPage();
        await waitFor(() => screen.getByTestId("cheque-row-c1"));

        fireEvent.click(screen.getByTestId("collection-select-all"));
        expect(screen.getByTestId("collection-select-c1")).toBeChecked();
        expect(screen.getByTestId("collection-select-c2")).toBeChecked();

        fireEvent.click(screen.getByTestId("collection-deposit-selected"));
        fireEvent.click(await screen.findByTestId("deposit-batch-confirm"));

        await waitFor(() =>
            expect(screen.getByTestId("deposit-batch-error")).toHaveTextContent("Cheque 000102 is already DEPOSITED"),
        );

        // The selection survives the refusal — both checkboxes are still checked
        // and the running total is unchanged.
        expect(screen.getByTestId("collection-select-c1")).toBeChecked();
        expect(screen.getByTestId("collection-select-c2")).toBeChecked();
        expect(screen.getByTestId("collection-selected-total")).toHaveTextContent("8,000.00");
    });
});
