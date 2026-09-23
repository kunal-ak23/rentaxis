import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";
import ChequeGrid, { draftRowsAreValid } from "../ChequeGrid";
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

    it("initializes the generate form's distribution from defaultDistribution, not a hardcoded LAST_LARGER (#46)", () => {
        const onGenerate = vi.fn();
        renderGrid({
            cheques: [],
            editable: true,
            onGenerate,
            contractValueInclVat: 30000,
            defaultDistribution: "UNIFORM",
        });
        fireEvent.click(screen.getByTestId("cheque-grid-generate"));
        expect(screen.getByLabelText("Distribution")).toHaveValue("UNIFORM");

        fireEvent.change(screen.getByLabelText("Installments"), { target: { value: "4" } });
        fireEvent.change(screen.getByLabelText("First Due Date"), { target: { value: "2026-01-01" } });
        fireEvent.click(screen.getByTestId("cheque-generate-confirm"));
        expect(onGenerate).toHaveBeenCalledWith(expect.objectContaining({ distribution: "UNIFORM" }));
    });

    it("still defaults to LAST_LARGER when no defaultDistribution is given", () => {
        renderGrid({ cheques: [], editable: true, contractValueInclVat: 30000 });
        fireEvent.click(screen.getByTestId("cheque-grid-generate"));
        expect(screen.getByLabelText("Distribution")).toHaveValue("LAST_LARGER");
    });
});

describe("ChequeGrid and the server's row rules", () => {
    it("labels the Status column with a string, not the Cheques.status dictionary (#264)", () => {
        renderGrid({
            cheques: [cheque({ id: "c1", seqNo: 1, status: "DEPOSITED" })],
            editable: false,
            contractValueInclVat: 13700,
        });
        // `tc("status")` resolved to an object: next-intl logged INSUFFICIENT_PATH
        // and rendered the raw key on every posted lease.
        expect(screen.getByRole("columnheader", { name: "Status" })).toBeInTheDocument();
        expect(screen.queryByText("Cheques.status")).not.toBeInTheDocument();
    });

    it("never offers ONLINE, in the row select or the generate form — no user-facing door accepts it", () => {
        renderGrid({
            cheques: [cheque({ id: "c1", seqNo: 1 })],
            editable: true,
            onChange: vi.fn(),
            contractValueInclVat: 13700,
        });
        const rowModes = Array.from((screen.getByLabelText("Mode 1") as HTMLSelectElement).options).map(o => o.value);
        expect(rowModes).toEqual(["PDC", "CASH", "TRANSFER"]);

        fireEvent.click(screen.getByTestId("cheque-grid-generate"));
        const genModes = Array.from((screen.getByLabelText("Mode") as HTMLSelectElement).options).map(o => o.value);
        expect(genModes).toEqual(["PDC", "CASH", "TRANSFER"]);
    });

    it("says what ChequeRowRules would refuse, per row, while the grid is editable", () => {
        renderGrid({
            cheques: [
                cheque({ id: "c1", seqNo: 1, chequeDate: null }),
                cheque({ id: "c2", seqNo: 2, amount: 0, chequeNumber: "000102" }),
            ],
            editable: true,
            onChange: vi.fn(),
            contractValueInclVat: 13700,
        });
        const errors = screen.getByTestId("cheque-grid-row-errors");
        expect(errors).toHaveTextContent("Row 1: a post-dated cheque needs the date written on it");
        expect(errors).toHaveTextContent("Row 2: amount must be greater than zero");
    });

    it("says nothing about rows on a posted lease, which are no longer typed", () => {
        renderGrid({
            cheques: [cheque({ id: "c1", seqNo: 1, chequeDate: null, status: "REGISTERED" })],
            editable: false,
            contractValueInclVat: 13700,
        });
        expect(screen.queryByTestId("cheque-grid-row-errors")).not.toBeInTheDocument();
    });

    it("draftRowsAreValid is the gate both Save buttons use", () => {
        expect(draftRowsAreValid([cheque({ id: "c1", seqNo: 1 })])).toBe(true);
        expect(draftRowsAreValid([cheque({ id: "c1", seqNo: 1, chequeDate: null })])).toBe(false);
        expect(draftRowsAreValid([cheque({ id: "c1", seqNo: 1, amount: 0 })])).toBe(false);
        // Two rows of one payload may not claim the same cheque number.
        expect(
            draftRowsAreValid([
                cheque({ id: "c1", seqNo: 1, chequeNumber: "000101" }),
                cheque({ id: "c2", seqNo: 2, chequeNumber: "000101" }),
            ]),
        ).toBe(false);
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
        // ONLINE_PENDING never moves through "receive" — the server 400s that —
        // but staff may release an abandoned gateway session back to the register.
        expect(registerActionsFor("ONLINE_PENDING", "ONLINE", false)).toEqual(["releaseOnline"]);
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

    it("offers only Release on an ONLINE_PENDING row and 'receive' for a CASH REGISTERED one", () => {
        const online = cheque({ id: "c1", seqNo: 1, status: "ONLINE_PENDING", mode: "ONLINE" });
        renderGrid({ cheques: [online], editable: false, onRowAction: vi.fn(), contractValueInclVat: 13700 });
        // Receive would 400; releasing the abandoned session would not.
        expect(screen.queryByTestId("cheque-action-receive-0")).not.toBeInTheDocument();
        expect(screen.getByTestId("cheque-action-releaseOnline-0")).toBeInTheDocument();
        cleanup();

        const cashRow = cheque({ id: "c2", seqNo: 1, status: "REGISTERED", mode: "CASH" });
        renderGrid({ cheques: [cashRow], editable: false, onRowAction: vi.fn(), contractValueInclVat: 13700 });
        expect(screen.getByTestId("cheque-action-receive-0")).toBeInTheDocument();
        expect(screen.queryByTestId("cheque-action-deposit-0")).not.toBeInTheDocument();
    });

    it("offers nothing on a CLOSED contract — requireCollectable refuses every one", () => {
        const cleared = cheque({ id: "c1", seqNo: 1, status: "CLEARED" });
        renderGrid({
            cheques: [cleared],
            editable: false,
            onRowAction: vi.fn(),
            contractValueInclVat: 13700,
            leaseStatus: "CLOSED",
        });
        // The reachable half of the twelve-cases defect: a cleared PDC on a
        // closed contract still showed the late-return Bounce.
        expect(screen.queryByTestId("cheque-action-bounce-0")).not.toBeInTheDocument();
        expect(screen.queryByTestId("cheque-action-receipt-0")).not.toBeInTheDocument();
        // ...and the actions column disappears with them.
        expect(screen.queryByText("Actions")).not.toBeInTheDocument();
    });

    it("keeps them on a TERMINATED contract, which COLLECTABLE admits", () => {
        renderGrid({
            cheques: [cheque({ id: "c1", seqNo: 1, status: "DEPOSITED" })],
            editable: false,
            onRowAction: vi.fn(),
            contractValueInclVat: 13700,
            leaseStatus: "TERMINATED",
        });
        expect(screen.getByTestId("cheque-action-clear-0")).toBeInTheDocument();
        expect(screen.getByTestId("cheque-action-bounce-0")).toBeInTheDocument();
    });

    it("drops Cancel once the lease's settlement is finalised, keeping Replace", () => {
        renderGrid({
            cheques: [
                cheque({ id: "c1", seqNo: 1, status: "REGISTERED" }),
                cheque({ id: "c2", seqNo: 2, status: "BOUNCED" }),
            ],
            editable: false,
            onRowAction: vi.fn(),
            canCancelCheques: true,
            contractValueInclVat: 27400,
            leaseStatus: "TERMINATED",
            settlementFinalized: true,
        });
        expect(screen.queryByTestId("cheque-action-cancel-0")).not.toBeInTheDocument();
        expect(screen.getByTestId("cheque-action-deposit-0")).toBeInTheDocument();
        expect(screen.getByTestId("cheque-action-replace-1")).toBeInTheDocument();
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
