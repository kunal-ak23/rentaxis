import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";

/**
 * S16-14: a cheque banked by the previous owner before an acquisition shows
 * "Banked by previous owner" and offers no bounce/replace/receipt — the
 * server (`ChequeService.requireOurs`) refuses all three outright.
 */

vi.mock("next/navigation", () => ({ useSearchParams: () => new URLSearchParams() }));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "TENANT_ADMIN" } } }) }));
vi.mock("@/i18n/routing", () => ({ Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a> }));
vi.mock("@/components/finance/useNameLookup", () => ({ useNameLookup: () => ({ options: [], name: () => "", loading: false }) }));
vi.mock("@/lib/api/bankRec", () => ({ bankRecApi: { chequeEvidence: vi.fn().mockResolvedValue([]) } }));

const api = vi.hoisted(() => ({ list: vi.fn(), summary: vi.fn(), aging: vi.fn() }));
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return { ...m, chequeApi: { ...m.chequeApi, list: api.list, summary: api.summary, aging: api.aging } };
});

import ChequeRegisterPanel from "../ChequeRegisterPanel";
import type { Cheque } from "@/lib/api/leasing";

function cheque(over: Partial<Cheque> & { id: string; seqNo: number }): Cheque {
    return {
        leaseId: "l1", propertyId: "p1", unitId: "u1", renterId: "r1",
        propertyName: "L'Olivier", unitIdentifier: "204", renterName: "Prabhjot Singh",
        postingDate: "2026-01-01", chequeNumber: "000101", chequeDate: "2026-06-01",
        payeeBank: "ENBD", payerName: "Prabhjot Singh", debitAccountId: null, debitAccountName: null,
        narration: null, mode: "PDC", status: "CLEARED", failureReason: null,
        replacesId: null, replacedById: null, imageUrl: null, depositedAt: null, clearedAt: "2026-06-01T00:00:00Z",
        bouncedAt: null, returnedAt: null, pdrJournalId: null, crtJournalId: null, cbrJournalId: null,
        penaltyAssessmentId: null, due: false, overdue: false, daysOverdue: 0, ledgerSettled: false,
        amount: 30000,
        ...over,
    };
}
const page = (content: Cheque[]) => ({ content, totalElements: content.length, totalPages: 1, number: 0, size: 25 });

function renderPanel() {
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <ChequeRegisterPanel />
        </NextIntlClientProvider>,
    );
}

afterEach(() => { cleanup(); vi.clearAllMocks(); });

describe("ChequeRegisterPanel — acquisition cheques", () => {
    it("shows the badge and offers no bounce/receipt on a cheque settled before the acquisition", async () => {
        api.list.mockResolvedValue(page([cheque({ id: "c1", seqNo: 1, settledBeforeAcquisition: true })]));
        api.summary.mockResolvedValue({});
        api.aging.mockResolvedValue({ buckets: [], totalCount: 0 });
        renderPanel();

        expect(await screen.findByTestId("cheque-settled-before-acquisition-c1")).toHaveTextContent("Banked by previous owner");
        expect(screen.queryByTestId("cheque-row-action-bounce-c1")).not.toBeInTheDocument();
        expect(screen.queryByTestId("cheque-row-action-receipt-c1")).not.toBeInTheDocument();
    });

    it("still offers bounce/receipt on an ordinary CLEARED PDC row", async () => {
        api.list.mockResolvedValue(page([cheque({ id: "c2", seqNo: 2 })]));
        api.summary.mockResolvedValue({});
        api.aging.mockResolvedValue({ buckets: [], totalCount: 0 });
        renderPanel();

        expect(await screen.findByTestId("cheque-row-action-bounce-c2")).toBeInTheDocument();
        expect(screen.getByTestId("cheque-row-action-receipt-c2")).toBeInTheDocument();
        expect(screen.queryByTestId("cheque-settled-before-acquisition-c2")).not.toBeInTheDocument();
    });
});
