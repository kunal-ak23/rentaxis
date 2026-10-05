import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";
import ar from "../../../../messages/ar.json";

/**
 * Tutorial 35: a DEPOSITED cheque awaiting clearing showed "29 days overdue" in red —
 * the tenant has paid; only the bank is pending. It reads "Deposited N days ago".
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
        propertyName: "Palm Ridge", unitIdentifier: "R-101", renterName: "Omar",
        postingDate: "2026-01-01", chequeNumber: "880102", chequeDate: "2026-09-01",
        payeeBank: "ENBD", payerName: "Omar", debitAccountId: null, debitAccountName: null,
        narration: null, mode: "PDC", status: "DEPOSITED", failureReason: null,
        replacesId: null, replacedById: null, imageUrl: null, depositedAt: "2026-09-06", clearedAt: null,
        bouncedAt: null, returnedAt: null, pdrJournalId: null, crtJournalId: null, cbrJournalId: null,
        penaltyAssessmentId: null, due: true, overdue: true, daysOverdue: 29, ledgerSettled: false,
        amount: 30000,
        ...over,
    };
}
const page = (content: Cheque[]) => ({ content, totalElements: content.length, totalPages: 1, number: 0, size: 25 });

function renderPanel(messages: typeof en = en, locale = "en") {
    return render(
        <NextIntlClientProvider locale={locale} messages={messages}>
            <ChequeRegisterPanel />
        </NextIntlClientProvider>,
    );
}

afterEach(() => { cleanup(); vi.clearAllMocks(); vi.useRealTimers(); });

describe("ChequeRegisterPanel — deposited cheques", () => {
    it("says when a deposited cheque was banked instead of calling it overdue", async () => {
        vi.useFakeTimers({ toFake: ["Date"] });
        vi.setSystemTime(new Date(2026, 9, 5, 12));
        api.list.mockResolvedValue(page([
            cheque({ id: "d1", seqNo: 1 }),
            cheque({ id: "r1", seqNo: 2, status: "REGISTERED", depositedAt: null, daysOverdue: 12 }),
        ]));
        api.summary.mockResolvedValue({});
        api.aging.mockResolvedValue({ buckets: [], totalCount: 0 });
        renderPanel();

        expect(await screen.findByTestId("cheque-awaiting-d1")).toHaveTextContent("Deposited 29 days ago");
        // PR #399 R1 P3-4: past 14 days, an amber flag as well.
        expect(screen.getByTestId("cheque-stale-deposit-d1")).toHaveTextContent("Deposit not cleared — check with the bank");
        expect(screen.getByTestId("cheque-awaiting-d1").closest("td")).not.toHaveTextContent("overdue");
        expect(screen.queryByTestId("cheque-awaiting-r1")).toBeNull();
        expect(screen.getByText("12 days overdue")).toBeInTheDocument();
    });

    it("does not flag a deposit that is only days old", async () => {
        vi.useFakeTimers({ toFake: ["Date"] });
        vi.setSystemTime(new Date(2026, 9, 5, 12));
        api.list.mockResolvedValue(page([cheque({ id: "d2", seqNo: 1, depositedAt: "2026-09-25" })]));
        api.summary.mockResolvedValue({});
        api.aging.mockResolvedValue({ buckets: [], totalCount: 0 });
        renderPanel();
        expect(await screen.findByTestId("cheque-awaiting-d2")).toHaveTextContent("Deposited 10 days ago");
        expect(screen.queryByTestId("cheque-stale-deposit-d2")).toBeNull();
    });

    it("words it in Arabic", async () => {
        vi.useFakeTimers({ toFake: ["Date"] });
        vi.setSystemTime(new Date(2026, 9, 5, 12));
        api.list.mockResolvedValue(page([cheque({ id: "d1", seqNo: 1 })]));
        api.summary.mockResolvedValue({});
        api.aging.mockResolvedValue({ buckets: [], totalCount: 0 });
        renderPanel(ar as typeof en, "ar");
        expect(await screen.findByTestId("cheque-awaiting-d1")).toHaveTextContent("أودع منذ 29 يوم");
    });
});
