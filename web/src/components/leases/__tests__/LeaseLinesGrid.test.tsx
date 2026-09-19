import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { useState } from "react";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";
import LeaseLinesGrid from "../LeaseLinesGrid";
import { blankLine, type LineRow } from "../leaseMath";
import type { ChargeType } from "@/lib/api/leasing";

// The picker fetches the whole chart of accounts on mount; none of these
// assertions are about it.
vi.mock("@/components/finance/AccountPicker", () => ({
    default: ({ accountType }: { accountType?: string }) => (
        <div data-testid="account-picker" data-account-type={accountType ?? ""} />
    ),
}));

const CHARGE_TYPES: ChargeType[] = [
    {
        id: "ct-rent", code: "RENT", nameEn: "Rent", nameAr: null, role: "ADVANCE_RENT",
        behaviour: "RENT", vatApplicableDefault: true, active: true, displayOrder: 1,
    },
    {
        id: "ct-dep", code: "SECURITY_DEPOSIT", nameEn: "Security Deposit", nameAr: null, role: "SECURITY_DEPOSIT",
        behaviour: "DEPOSIT", vatApplicableDefault: false, active: true, displayOrder: 2,
    },
    {
        id: "ct-fee", code: "ADMIN_FEE", nameEn: "Admin Fee", nameAr: null, role: "ADMIN_FEE",
        behaviour: "FEE", vatApplicableDefault: true, active: true, displayOrder: 3,
    },
];

function row(over: Partial<LineRow> & { key: number }): LineRow {
    return { ...blankLine(over.key), ...over };
}

/** Controlled wrapper — the grid is a pure render of whatever it is handed. */
function Harness({ initial, errors }: { initial: LineRow[]; errors?: string[] }) {
    const [lines, setLines] = useState(initial);
    return (
        <NextIntlClientProvider locale="en" messages={en}>
            <LeaseLinesGrid
                lines={lines}
                chargeTypes={CHARGE_TYPES}
                editable
                onChange={setLines}
                errors={errors}
            />
        </NextIntlClientProvider>
    );
}

function renderReadOnly(lines: LineRow[]) {
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <LeaseLinesGrid lines={lines} chargeTypes={CHARGE_TYPES} editable={false} />
        </NextIntlClientProvider>,
    );
}

afterEach(cleanup);

describe("LeaseLinesGrid", () => {
    it("computes After Discount from amount minus discount, and never lets it be typed", () => {
        render(<Harness initial={[row({ key: 0, chargeTypeId: "ct-rent", grossAmount: 60000, discountAmount: 2500 })]} />);
        expect(screen.getByTestId("lease-line-net-0")).toHaveTextContent("57,500.00");

        fireEvent.change(screen.getByTestId("lease-line-discount-0"), { target: { value: "500" } });
        expect(screen.getByTestId("lease-line-net-0")).toHaveTextContent("59,500.00");

        // There is no input inside the computed cell — the ledger posts this
        // figure, so it cannot be a second place to type a different one.
        expect(screen.getByTestId("lease-line-net-0").querySelector("input")).toBeNull();
    });

    it("blocks a discount larger than the amount, inline on the row", () => {
        render(<Harness initial={[row({ key: 0, chargeTypeId: "ct-rent", grossAmount: 1000, discountAmount: 0 })]} />);
        expect(screen.queryByTestId("lease-line-errors-0")).not.toBeInTheDocument();

        fireEvent.change(screen.getByTestId("lease-line-discount-0"), { target: { value: "1500" } });
        expect(screen.getByTestId("lease-line-errors-0")).toHaveTextContent(
            "Line 1: the discount cannot be more than the amount.",
        );
    });

    it("pre-fills VAT from the charge type's default when the type changes", () => {
        render(<Harness initial={[row({ key: 0, grossAmount: 1000 })]} />);
        const vat = screen.getByTestId("lease-line-vat-0") as HTMLInputElement;
        expect(vat.checked).toBe(false);

        fireEvent.change(screen.getByTestId("lease-line-type-0"), { target: { value: "ct-rent" } });
        expect((screen.getByTestId("lease-line-vat-0") as HTMLInputElement).checked).toBe(true);

        // A deposit is refundable money held, not a supply — it carries no VAT
        // whatever the flag says, and the checkbox says so by being disabled.
        fireEvent.change(screen.getByTestId("lease-line-type-0"), { target: { value: "ct-dep" } });
        const deposit = screen.getByTestId("lease-line-vat-0") as HTMLInputElement;
        expect(deposit.checked).toBe(false);
        expect(deposit).toBeDisabled();
    });

    it("totals amount, discount, net and VAT, and carries VAT into the contract value", () => {
        renderReadOnly([
            // 57,500 net, VAT-applicable rent → 2,875.00
            row({ key: 0, chargeTypeId: "ct-rent", grossAmount: 60000, discountAmount: 2500, vatApplicable: true }),
            // A VAT-flagged deposit still contributes nothing to the VAT total.
            row({ key: 1, chargeTypeId: "ct-dep", grossAmount: 10000, vatApplicable: true }),
            // 2,000 net fee → 100.00
            row({ key: 2, chargeTypeId: "ct-fee", grossAmount: 2000, vatApplicable: true }),
        ]);

        expect(screen.getByTestId("lease-lines-total-gross")).toHaveTextContent("72,000.00");
        expect(screen.getByTestId("lease-lines-total-discount")).toHaveTextContent("2,500.00");
        expect(screen.getByTestId("lease-lines-total-net")).toHaveTextContent("69,500.00");
        expect(screen.getByTestId("lease-lines-total-vat")).toHaveTextContent("2,975.00");
        expect(screen.getByTestId("lease-lines-contract-value")).toHaveTextContent("72,475.00");
    });

    it("narrows the credit-account picker by the charge type's behaviour", () => {
        renderReadOnly([]);
        cleanup();
        render(
            <Harness
                initial={[
                    row({ key: 0, chargeTypeId: "ct-dep" }),
                    row({ key: 1, chargeTypeId: "ct-fee" }),
                ]}
            />,
        );
        const pickers = screen.getAllByTestId("account-picker");
        expect(pickers[0]).toHaveAttribute("data-account-type", "LIABILITY");
        expect(pickers[1]).toHaveAttribute("data-account-type", "INCOME");
    });

    it("puts a line-addressed server error next to its row", () => {
        render(
            <Harness
                initial={[row({ key: 0, chargeTypeId: "ct-rent" }), row({ key: 1, chargeTypeId: "ct-fee" })]}
                errors={[
                    "Line 2 (ADMIN_FEE): credit account 400100 is inactive.",
                    "Cannot post on 2026-01-01: books are locked through 2026-03-31.",
                ]}
            />,
        );
        expect(screen.queryByTestId("lease-line-errors-0")).not.toBeInTheDocument();
        expect(screen.getByTestId("lease-line-errors-1")).toHaveTextContent("credit account 400100 is inactive.");
        // The unaddressed one is the caller's banner, not the grid's.
        expect(screen.queryByText(/books are locked/)).not.toBeInTheDocument();
    });

    it("adds and removes rows only while editable", () => {
        render(<Harness initial={[row({ key: 0, chargeTypeId: "ct-rent" })]} />);
        fireEvent.click(screen.getByTestId("lease-lines-add"));
        expect(screen.getAllByTestId(/^lease-line-row-/)).toHaveLength(2);

        fireEvent.click(screen.getByLabelText("Remove line 1"));
        expect(screen.getAllByTestId(/^lease-line-row-/)).toHaveLength(1);

        cleanup();
        renderReadOnly([row({ key: 0, chargeTypeId: "ct-rent" })]);
        expect(screen.queryByTestId("lease-lines-add")).not.toBeInTheDocument();
    });
});
