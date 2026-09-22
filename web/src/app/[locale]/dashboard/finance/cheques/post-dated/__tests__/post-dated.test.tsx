import { cleanup, render, screen, waitFor } from "@testing-library/react";
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
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return { ...m, chequeApi: { ...m.chequeApi, postDated: (...a: unknown[]) => postDated(...(a as [])) } };
});

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
        due: false, overdue: false, daysOverdue: 0,
        ...over,
    };
}

beforeEach(() => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => [] })) as unknown as typeof fetch;
    postDated.mockReset();
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

describe("Post-dated cheques page", () => {
    it("groups by maturity date with a per-day subtotal and a running total across the month", async () => {
        postDated.mockResolvedValue([
            cheque({ id: "c1", seqNo: 1, amount: 5000, chequeDate: "2026-06-05" }),
            cheque({ id: "c2", seqNo: 2, amount: 3000, chequeDate: "2026-06-05" }),
            cheque({ id: "c3", seqNo: 3, amount: 7000, chequeDate: "2026-06-20" }),
        ]);

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

        expect(postDated).toHaveBeenCalledWith(expect.objectContaining({ propertyId: undefined }));
    });

    it("shows an empty state when nothing matures this month", async () => {
        postDated.mockResolvedValue([]);
        render(
            <NextIntlClientProvider locale="en" messages={en}>
                <PostDatedChequesPage />
            </NextIntlClientProvider>,
        );
        await waitFor(() => expect(screen.getByText(en.Cheques.noPostDated)).toBeInTheDocument());
    });
});
