import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../../messages/en.json";
import PostDatedChequesPage from "../page";
import type { Cheque } from "@/lib/api/leasing";

vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
}));
vi.mock("next-auth/react", () => ({
    useSession: () => ({ data: { user: { role: "TENANT_ADMIN", name: "Admin" } } }),
}));

const postDated = vi.fn();
const totals = vi.fn();
const unbounded = vi.fn();
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return { ...m, chequeApi: { ...m.chequeApi,
        postDated: (...a: unknown[]) => unbounded(...(a as [])),
        postDatedPaged: (...a: unknown[]) => postDated(...(a as [])),
        postDatedTotals: (...a: unknown[]) => totals(...(a as [])) } };
});

const pageOf = (content: Cheque[], totalElements = content.length) =>
    ({ content, totalElements, totalPages: Math.ceil(totalElements / 50), number: 0, size: 50 });

/** The totals the server computes over the whole window, from the rows given. */
const totalsOf = (rows: Cheque[], amountBefore = 0) => {
    const days = new Map<string, { date: string; count: number; amount: number }>();
    for (const c of rows) {
        const d = days.get(c.chequeDate!) ?? { date: c.chequeDate!, count: 0, amount: 0 };
        d.count += 1; d.amount += c.amount;
        days.set(c.chequeDate!, d);
    }
    return { count: rows.length, total: rows.reduce((s, c) => s + c.amount, 0), days: [...days.values()], amountBefore };
};

function cheque(over: Partial<Cheque> & { id: string; seqNo: number; amount: number; chequeDate: string }): Cheque {
    return {
        leaseId: "l1", propertyId: "p1", unitId: "u1", renterId: "r1",
        propertyName: "L'Olivier", unitIdentifier: "A-101", renterName: "Prabhjot Singh",
        postingDate: over.chequeDate, chequeNumber: `00010${over.seqNo}`,
        payeeBank: "ENBD", payerName: null, debitAccountId: "acc-1", debitAccountName: "PDC Receivable",
        narration: null, mode: "PDC", status: "REGISTERED",
        failureReason: null, replacesId: null, replacedById: null, imageUrl: null,
        depositedAt: null, clearedAt: null, bouncedAt: null, returnedAt: null,
        pdrJournalId: null, crtJournalId: null, cbrJournalId: null, penaltyAssessmentId: null,
        due: false, overdue: false, daysOverdue: 0, ledgerSettled: false,
        ...over,
    };
}

beforeEach(() => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => [] })) as unknown as typeof fetch;
    postDated.mockReset();
    totals.mockReset();
    unbounded.mockReset();
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

describe("Post-dated cheques page", () => {
    it("groups by maturity date with a per-day subtotal and a running total across the month", async () => {
        const rows = [
            cheque({ id: "c1", seqNo: 1, amount: 5000, chequeDate: "2026-06-05" }),
            cheque({ id: "c2", seqNo: 2, amount: 3000, chequeDate: "2026-06-05" }),
            cheque({ id: "c3", seqNo: 3, amount: 7000, chequeDate: "2026-06-20" }),
        ];
        postDated.mockResolvedValue(pageOf(rows));
        totals.mockResolvedValue(totalsOf(rows));

        render(
            <NextIntlClientProvider locale="en" messages={en}>
                <PostDatedChequesPage />
            </NextIntlClientProvider>,
        );

        await waitFor(() => screen.getByTestId("post-dated-group-2026-06-05"));

        // Day one: 5000 + 3000 = 8000 subtotal.
        expect(screen.getByTestId("post-dated-subtotal-2026-06-05")).toHaveTextContent("8,000.00");
        // Day two: 7000 subtotal, standing on its own.
        expect(screen.getByTestId("post-dated-subtotal-2026-06-20")).toHaveTextContent("7,000.00");

        // Running total accumulates across groups in maturity order.
        expect(screen.getByTestId("post-dated-running-c1")).toHaveTextContent("5,000.00");
        expect(screen.getByTestId("post-dated-running-c2")).toHaveTextContent("8,000.00");
        expect(screen.getByTestId("post-dated-running-c3")).toHaveTextContent("15,000.00");

        // The month total in the header matches the grand total.
        expect(screen.getByTestId("post-dated-running-total")).toHaveTextContent("15,000.00");

        expect(postDated).toHaveBeenCalledWith(expect.objectContaining({ propertyId: undefined, page: 0, size: 50 }));
        expect(totals).toHaveBeenCalledWith(expect.objectContaining({ propertyId: undefined, offset: 0 }));
        // Scale follow-up: never the unbounded list.
        expect(unbounded).not.toHaveBeenCalled();
    });

    it("pages the book: the running total carries on from the rows ahead, a day split across pages shows its full subtotal", async () => {
        // 52 cheques: 50 on page 1, then two more on 2026-06-20, which page 1 also ends on.
        const all = Array.from({ length: 52 }, (_, i) => cheque({
            id: `c${i}`, seqNo: i + 1, amount: 1000, chequeDate: i < 49 ? "2026-06-05" : "2026-06-20",
        }));
        postDated.mockImplementation(async (q: { page: number; size: number }) =>
            pageOf(all.slice(q.page * q.size, (q.page + 1) * q.size), all.length));
        totals.mockImplementation(async (q: { offset: number }) => totalsOf(all, q.offset * 1000));

        render(
            <NextIntlClientProvider locale="en" messages={en}>
                <PostDatedChequesPage />
            </NextIntlClientProvider>,
        );
        await waitFor(() => screen.getByTestId("post-dated-running-c49"));
        expect(screen.getByTestId("post-dated-running-c49")).toHaveTextContent("50,000.00");
        // 2026-06-20 has one row here but three in the window: the subtotal is the day's.
        expect(screen.getByTestId("post-dated-subtotal-2026-06-20")).toHaveTextContent("3,000.00");
        expect(screen.getByTestId("post-dated-running-total")).toHaveTextContent("52,000.00");

        fireEvent.click(screen.getByRole("button", { name: "2" }));
        await waitFor(() => screen.getByTestId("post-dated-running-c50"));
        expect(postDated).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1, size: 50 }));
        expect(totals).toHaveBeenLastCalledWith(expect.objectContaining({ offset: 50 }));
        expect(screen.getByTestId("post-dated-running-c50")).toHaveTextContent("51,000.00");
        expect(screen.getByTestId("post-dated-running-c51")).toHaveTextContent("52,000.00");
        expect(screen.getByTestId("post-dated-subtotal-2026-06-20")).toHaveTextContent("3,000.00");
        expect(screen.queryByTestId("post-dated-running-c0")).toBeNull();
    });

    it("shows an empty state when nothing matures this month", async () => {
        postDated.mockResolvedValue(pageOf([]));
        totals.mockResolvedValue(totalsOf([]));
        render(
            <NextIntlClientProvider locale="en" messages={en}>
                <PostDatedChequesPage />
            </NextIntlClientProvider>,
        );
        await waitFor(() => expect(screen.getByText(en.Cheques.noPostDated)).toBeInTheDocument());
    });
});
