import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
vi.mock("@/i18n/routing", () => ({ Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a> }));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "ACCOUNTANT" } } }) }));

const list = vi.hoisted(() => vi.fn());
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return { ...m, chequeApi: { ...m.chequeApi, list } };
});

import ReturnReplacePanel from "../ReturnReplacePanel";
import type { Cheque } from "@/lib/api/leasing";

/**
 * S16-14: `ChequeService.requireOurs` refuses Replace outright for a cheque
 * banked by the previous owner before an acquisition — the button must not
 * be offered on a row that carries the flag (even though, in practice, such a
 * row is never BOUNCED — `markSettledBeforeAcquisition` sets CLEARED — this
 * is the defensive mirror of that server rule).
 */
function cheque(over: Partial<Cheque> & { id: string; seqNo: number }): Cheque {
    return {
        leaseId: "l1", propertyId: "p1", unitId: "u1", renterId: "r1",
        propertyName: "L'Olivier", unitIdentifier: "204", renterName: "Prabhjot Singh",
        postingDate: "2026-01-01", chequeNumber: "000101", chequeDate: "2026-06-01",
        payeeBank: "ENBD", payerName: "Prabhjot Singh", debitAccountId: null, debitAccountName: null,
        narration: null, mode: "PDC", status: "BOUNCED", failureReason: null,
        replacesId: null, replacedById: null, imageUrl: null, depositedAt: null, clearedAt: null,
        bouncedAt: null, returnedAt: null, pdrJournalId: null, crtJournalId: null, cbrJournalId: null,
        penaltyAssessmentId: null, due: false, overdue: false, daysOverdue: 0, ledgerSettled: false,
        amount: 1000,
        ...over,
    };
}

const page = (content: Cheque[]) => ({ content, totalElements: content.length, totalPages: 1, number: 0, size: 25 });

afterEach(() => { cleanup(); list.mockReset(); });

describe("ReturnReplacePanel", () => {
    it("offers Replace on an ordinary bounced row", async () => {
        list.mockResolvedValue(page([cheque({ id: "c1", seqNo: 1 })]));
        render(<ReturnReplacePanel />);
        expect(await screen.findByTestId("cheque-row-action-replace-c1")).toBeInTheDocument();
    });

    it("withholds Replace for a cheque settled before the acquisition", async () => {
        list.mockResolvedValue(page([cheque({ id: "c2", seqNo: 2, settledBeforeAcquisition: true })]));
        render(<ReturnReplacePanel />);
        await screen.findByTestId("cheque-row-c2");
        expect(screen.queryByTestId("cheque-row-action-replace-c2")).not.toBeInTheDocument();
    });
});
