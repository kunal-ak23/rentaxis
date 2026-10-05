import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";

/**
 * PR #397 R1-P3-c: a partly settled returned cheque reads "X of Y" on the register row,
 * as on the Returned queue. #396 follow-up: an attached scan opens through the app.
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
        propertyName: "Palm Court", unitIdentifier: "204", renterName: "Test Renter",
        postingDate: "2026-01-01", chequeNumber: "000101", chequeDate: "2026-06-01",
        payeeBank: "ENBD", payerName: "Test Renter", debitAccountId: null, debitAccountName: null,
        narration: null, mode: "PDC", status: "REGISTERED", failureReason: null,
        replacesId: null, replacedById: null, imageUrl: null, depositedAt: null, clearedAt: null,
        bouncedAt: null, returnedAt: null, pdrJournalId: null, crtJournalId: null, cbrJournalId: null,
        penaltyAssessmentId: null, due: false, overdue: false, daysOverdue: 0, ledgerSettled: false,
        amount: 30000,
        ...over,
    };
}
const page = (content: Cheque[]) => ({ content, totalElements: content.length, totalPages: 1, number: 0, size: 25 });

afterEach(() => { cleanup(); vi.clearAllMocks(); });

describe("ChequeRegisterPanel — owed amount and scans", () => {
    it("shows what is owed of the face value on a partly settled bounce, the face value elsewhere", async () => {
        api.list.mockResolvedValue(page([
            cheque({ id: "part", seqNo: 1, status: "BOUNCED", openAmount: 12000 }),
            cheque({ id: "whole", seqNo: 2, status: "BOUNCED", openAmount: 30000 }),
            cheque({ id: "plain", seqNo: 3 }),
        ]));
        api.summary.mockResolvedValue({});
        api.aging.mockResolvedValue({ buckets: [], totalCount: 0 });
        render(<NextIntlClientProvider locale="en" messages={en}><ChequeRegisterPanel /></NextIntlClientProvider>);

        const part = await screen.findByTestId("cheque-row-amount-part");
        expect(part.textContent).toContain("12,000");
        expect(screen.getByTestId("cheque-row-face-part").textContent).toContain("30,000");
        expect(screen.queryByTestId("cheque-row-face-whole")).toBeNull();
        expect(screen.queryByTestId("cheque-row-face-plain")).toBeNull();
        expect(screen.getByTestId("cheque-row-amount-plain").textContent).toContain("30,000");
    });

    it("opens an attached scan through the app, never the private blob URL", async () => {
        api.list.mockResolvedValue(page([
            cheque({ id: "scanned", seqNo: 1, imageUrl: "https://acct.blob.core.windows.net/tenant-x/cheques/a.jpg" }),
            cheque({ id: "bare", seqNo: 2 }),
        ]));
        api.summary.mockResolvedValue({});
        api.aging.mockResolvedValue({ buckets: [], totalCount: 0 });
        render(<NextIntlClientProvider locale="en" messages={en}><ChequeRegisterPanel /></NextIntlClientProvider>);

        expect(await screen.findByTestId("cheque-row-scan-link-scanned")).toHaveAttribute("href", "/api/proxy/v1/cheques/scanned/image");
        expect(screen.queryByTestId("cheque-row-scan-link-bare")).toBeNull();
    });
});
