import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";
import ar from "../../../../messages/ar.json";
import ChequeGrid, { isContractInstalment } from "../ChequeGrid";
import type { Cheque } from "@/lib/api/leasing";

/**
 * Tutorial 40: after a returned cheque is replaced, the footer compared every row —
 * the Replaced original and the penalty's collection row too — with the contract
 * value, and warned "Cheques total 78,500.00 but contract value is 63,000.00".
 */

vi.mock("@/components/finance/AccountPicker", () => ({ default: () => <div /> }));
vi.mock("@/components/finance/SettlementAccountPicker", () => ({ default: () => <div /> }));

function cheque(over: Partial<Cheque> & { id: string; seqNo: number }): Cheque {
    return {
        leaseId: "l1", propertyId: "p1", unitId: "u1", renterId: "r1",
        propertyName: "Tower", unitIdentifier: "R-102", renterName: "Layla",
        postingDate: "2026-04-20", chequeNumber: `00010${over.seqNo}`, chequeDate: "2026-05-01",
        payeeBank: "ENBD", payerName: null, debitAccountId: "acc-1", debitAccountName: "Bank",
        amount: 15750, narration: null, mode: "PDC", status: "CLEARED",
        failureReason: null, replacesId: null, replacedById: null, imageUrl: null,
        depositedAt: null, clearedAt: null, bouncedAt: null, returnedAt: null,
        pdrJournalId: null, crtJournalId: null, cbrJournalId: null, penaltyAssessmentId: null,
        due: false, overdue: false, daysOverdue: 0, ledgerSettled: false, vatAmount: 0, vatTaxableAmount: 15750,
        ...over,
    };
}

// 63,000 in four instalments; the second bounced and was replaced; a 500 bounce fine is collected in cash.
const rows: Cheque[] = [
    cheque({ id: "c1", seqNo: 1 }),
    cheque({ id: "c2", seqNo: 2, status: "REPLACED", replacedById: "c5" }),
    cheque({ id: "c3", seqNo: 3, status: "REGISTERED" }),
    cheque({ id: "c4", seqNo: 4, status: "REGISTERED" }),
    cheque({ id: "c5", seqNo: 5, status: "REGISTERED", replacesId: "c2" }),
    cheque({ id: "c6", seqNo: 6, amount: 500, mode: "CASH", status: "REGISTERED", penaltyAssessmentId: "pa-1" }),
];

function renderGrid(cheques: Cheque[], messages: typeof en = en, locale = "en") {
    return render(
        <NextIntlClientProvider locale={locale} messages={messages}>
            <ChequeGrid cheques={cheques} editable={false} contractValueInclVat={63000} />
        </NextIntlClientProvider>,
    );
}

afterEach(cleanup);

describe("ChequeGrid contract total", () => {
    it("counts only live contract instalments after a replacement and a penalty", () => {
        renderGrid(rows);
        expect(screen.getByTestId("cheque-grid-total")).toHaveTextContent("63,000.00");
        expect(screen.getByTestId("cheque-grid-match")).toHaveAttribute("data-match", "true");
        expect(screen.getByTestId("cheque-grid-excluded")).toHaveTextContent("Excludes 2 replaced, cancelled or penalty rows");
    });

    it("still flags a grid whose live instalments do not add up", () => {
        renderGrid(rows.filter(c => c.id !== "c4"));
        expect(screen.getByTestId("cheque-grid-total")).toHaveTextContent("47,250.00");
        expect(screen.getByTestId("cheque-grid-match")).toHaveAttribute("data-match", "false");
    });

    it("says nothing about exclusions when there are none", () => {
        renderGrid(rows.filter(isContractInstalment));
        expect(screen.queryByTestId("cheque-grid-excluded")).toBeNull();
    });

    it("words the exclusion in Arabic", () => {
        renderGrid(rows, ar as typeof en, "ar");
        expect(screen.getByTestId("cheque-grid-excluded").textContent).toContain("باستثناء");
    });

    it("keeps draft, bounced and returned rows; drops replaced, cancelled, transferred and penalty rows", () => {
        const kinds = (["DRAFT", "REGISTERED", "BOUNCED", "RETURNED", "REPLACED", "CANCELLED", "TRANSFERRED"] as const)
            .map(status => [status, isContractInstalment({ status, penaltyAssessmentId: null })]);
        expect(kinds).toEqual([["DRAFT", true], ["REGISTERED", true], ["BOUNCED", true], ["RETURNED", true],
            ["REPLACED", false], ["CANCELLED", false], ["TRANSFERRED", false]]);
        expect(isContractInstalment({ status: "REGISTERED", penaltyAssessmentId: "pa" })).toBe(false);
    });
});
