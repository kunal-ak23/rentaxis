import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";
import ChequeGrid from "../ChequeGrid";
import { registerActionsFor } from "@/components/cheques/registerActions";
import type { Cheque } from "@/lib/api/leasing";

vi.mock("@/components/finance/AccountPicker", () => ({
    default: () => <div data-testid="account-picker" />,
}));

function cheque(over: Partial<Cheque> & { id: string; seqNo: number }): Cheque {
    return {
        leaseId: "l1", propertyId: "p1", unitId: "u1", renterId: "r1",
        propertyName: "L'Olivier", unitIdentifier: "A-101", renterName: "Prabhjot Singh",
        postingDate: "2026-01-01", chequeNumber: "000101", chequeDate: "2026-01-01",
        payeeBank: "ENBD", payerName: null, debitAccountId: "acc-1", debitAccountName: "PDC Receivable",
        amount: 13700, narration: "Rent - 1st Installment", mode: "PDC", status: "REGISTERED",
        failureReason: null, replacesId: null, replacedById: null, imageUrl: null,
        depositedAt: null, clearedAt: null, bouncedAt: null, returnedAt: null,
        pdrJournalId: null, crtJournalId: null, cbrJournalId: null, penaltyAssessmentId: null,
        due: false, overdue: false, daysOverdue: 0,
        ...over,
    };
}

function renderGrid(props: Partial<React.ComponentProps<typeof ChequeGrid>> = {}) {
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <ChequeGrid
                cheques={props.cheques ?? []}
                editable={props.editable ?? false}
                contractValueInclVat={props.contractValueInclVat ?? 0}
                {...props}
            />
        </NextIntlClientProvider>,
    );
}

afterEach(cleanup);

describe("ChequeGrid footer", () => {
    it("shows a mismatch when the cheques do not add up to the contract value incl. VAT", () => {
        renderGrid({
            cheques: [cheque({ id: "c1", seqNo: 1, amount: 13700 }), cheque({ id: "c2", seqNo: 2, amount: 13700 })],
            contractValueInclVat: 30000,
        });
        const badge = screen.getByTestId("cheque-grid-match");
        expect(badge).toHaveAttribute("data-match", "false");
        expect(badge).toHaveTextContent("Cheques total 27,400.00 but contract value is 30,000.00");
        expect(screen.getByTestId("cheque-grid-total")).toHaveTextContent("27,400.00");
    });

    it("goes green once the two agree", () => {
        renderGrid({
            cheques: [cheque({ id: "c1", seqNo: 1, amount: 15000 }), cheque({ id: "c2", seqNo: 2, amount: 15000 })],
            contractValueInclVat: 30000,
        });
        const badge = screen.getByTestId("cheque-grid-match");
        expect(badge).toHaveAttribute("data-match", "true");
        expect(badge).toHaveTextContent("Cheques match the contract value of 30,000.00");
    });
});

describe("ChequeGrid editability", () => {
    it("types rows in place while editable", () => {
        const onChange = vi.fn();
        renderGrid({
            cheques: [cheque({ id: "c1", seqNo: 1 })],
            editable: true,
            onChange,
            contractValueInclVat: 13700,
        });
        fireEvent.change(screen.getByLabelText("Cheque No 1"), { target: { value: "000199" } });
        expect(onChange).toHaveBeenCalledWith([expect.objectContaining({ id: "c1", chequeNumber: "000199" })]);
        expect(screen.getByTestId("cheque-grid-generate")).toBeInTheDocument();
    });

    it("is read-only on a posted lease — no inputs, no generate buttons, status shown instead", () => {
        renderGrid({
            cheques: [cheque({ id: "c1", seqNo: 1, status: "DEPOSITED" })],
            editable: false,
            contractValueInclVat: 13700,
        });
        expect(screen.queryByLabelText("Cheque No 1")).not.toBeInTheDocument();
        expect(screen.queryByTestId("cheque-grid-generate")).not.toBeInTheDocument();
        expect(screen.queryByTestId("cheque-grid-numbers")).not.toBeInTheDocument();
        expect(screen.getByTestId("cheque-status-0")).toHaveTextContent("Deposited");
    });

    it("opens the generate form and hands the small form's values up", () => {
        const onGenerate = vi.fn();
        renderGrid({ cheques: [], editable: true, onGenerate, contractValueInclVat: 30000 });
        fireEvent.click(screen.getByTestId("cheque-grid-generate"));
        fireEvent.change(screen.getByLabelText("Installments"), { target: { value: "4" } });
        fireEvent.change(screen.getByLabelText("First Due Date"), { target: { value: "2026-01-01" } });
        fireEvent.click(screen.getByTestId("cheque-generate-confirm"));
        expect(onGenerate).toHaveBeenCalledWith(
            expect.objectContaining({ installments: 4, firstDueDate: "2026-01-01", distribution: "LAST_LARGER" }),
        );
    });
});

describe("ChequeGrid row actions", () => {
    it("shares registerActionsFor with the register — no lease-page table of its own", () => {
        // Mirrors ChequeController's transitions (spec §7.4), via the one table
        // the register (Task 15) and this grid both read.
        expect(registerActionsFor("REGISTERED", "PDC", false)).toEqual(["deposit", "details"]);
        expect(registerActionsFor("REGISTERED", "CASH", false)).toEqual(["receive", "details"]);
        expect(registerActionsFor("DEPOSITED", "PDC", false)).toEqual(["clear", "bounce"]);
        expect(registerActionsFor("BOUNCED", "PDC", false)).toEqual(["replace"]);
        // ONLINE_PENDING moves only through the online-payment webhook, never a
        // row action — the server 400s a manual "receive" on it.
        expect(registerActionsFor("ONLINE_PENDING", "ONLINE", false)).toEqual([]);
        expect(registerActionsFor("CLEARED", "PDC", false)).toEqual(["bounce", "receipt"]);
        expect(registerActionsFor("CLEARED", "CASH", false)).toEqual(["receipt"]);
        expect(registerActionsFor("CANCELLED", "PDC", false)).toEqual([]);
    });

    it("renders those actions on a posted grid and reports the click", () => {
        const onRowAction = vi.fn();
        const deposited = cheque({ id: "c1", seqNo: 1, status: "DEPOSITED" });
        renderGrid({ cheques: [deposited], editable: false, onRowAction, contractValueInclVat: 13700 });

        expect(screen.getByTestId("cheque-action-clear-0")).toBeInTheDocument();
        expect(screen.getByTestId("cheque-action-bounce-0")).toBeInTheDocument();
        expect(screen.queryByTestId("cheque-action-deposit-0")).not.toBeInTheDocument();

        fireEvent.click(screen.getByTestId("cheque-action-bounce-0"));
        expect(onRowAction).toHaveBeenCalledWith(deposited, "bounce");
    });

    it("offers nothing for an ONLINE_PENDING row and 'receive' for a CASH REGISTERED one", () => {
        const online = cheque({ id: "c1", seqNo: 1, status: "ONLINE_PENDING", mode: "ONLINE" });
        renderGrid({ cheques: [online], editable: false, onRowAction: vi.fn(), contractValueInclVat: 13700 });
        // ONLINE_PENDING has no row actions, so the grid renders no actions column at all.
        expect(screen.queryByTestId("cheque-action-receive-0")).not.toBeInTheDocument();
        expect(screen.queryByTestId(/^cheque-action-/)).not.toBeInTheDocument();
        cleanup();

        const cashRow = cheque({ id: "c2", seqNo: 1, status: "REGISTERED", mode: "CASH" });
        renderGrid({ cheques: [cashRow], editable: false, onRowAction: vi.fn(), contractValueInclVat: 13700 });
        expect(screen.getByTestId("cheque-action-receive-0")).toBeInTheDocument();
        expect(screen.queryByTestId("cheque-action-deposit-0")).not.toBeInTheDocument();
    });

    it("shows the notice when the backend dropped the draft rows", () => {
        renderGrid({
            cheques: [],
            editable: true,
            contractValueInclVat: 0,
            notice: "The cheque grid was cleared because the charge lines changed. Generate it again.",
        });
        expect(screen.getByTestId("cheque-grid-notice")).toHaveTextContent("cleared because the charge lines changed");
    });
});
