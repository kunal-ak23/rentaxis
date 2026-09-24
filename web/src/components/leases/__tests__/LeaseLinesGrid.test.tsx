import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { useState } from "react";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";
import ar from "../../../../messages/ar.json";
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
function Harness({ initial, errors, rentVat }: { initial: LineRow[]; errors?: string[]; rentVat?: boolean }) {
    const [lines, setLines] = useState(initial);
    return (
        <NextIntlClientProvider locale="en" messages={en}>
            <LeaseLinesGrid
                lines={lines}
                chargeTypes={CHARGE_TYPES}
                editable
                onChange={setLines}
                errors={errors}
                rentVat={rentVat}
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

function renderReadOnlyAr(lines: LineRow[]) {
    return render(
        <NextIntlClientProvider locale="ar" messages={ar}>
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

    describe("rentVat: the lease header's rent-VAT flag (#54)", () => {
        const vatBox = (i: number) => screen.getByTestId(`lease-line-vat-${i}`) as HTMLInputElement;
        const pick = (i: number, id: string) =>
            fireEvent.change(screen.getByTestId(`lease-line-type-${i}`), { target: { value: id } });

        it("a RENT charge takes the header flag over its catalogue default", () => {
            // Catalogue says RENT is taxed; this lease's header says it is not.
            render(<Harness initial={[row({ key: 0, grossAmount: 10000 })]} rentVat={false} />);
            pick(0, "ct-rent");
            expect(vatBox(0).checked).toBe(false);
            expect(screen.getByTestId("lease-lines-total-vat")).toHaveTextContent("0.00");

            cleanup();
            render(<Harness initial={[row({ key: 0, grossAmount: 10000, vatApplicable: false })]} rentVat />);
            pick(0, "ct-rent");
            expect(vatBox(0).checked).toBe(true);
            expect(screen.getByTestId("lease-lines-contract-value")).toHaveTextContent("10,500.00");
        });

        it("a non-RENT charge keeps its catalogue default whatever the header says", () => {
            render(<Harness initial={[row({ key: 0, grossAmount: 100 })]} rentVat={false} />);
            pick(0, "ct-fee");
            expect(vatBox(0).checked).toBe(true);
        });

        it("without the prop a RENT charge falls back to the catalogue default", () => {
            render(<Harness initial={[row({ key: 0, grossAmount: 100 })]} />);
            pick(0, "ct-rent");
            expect(vatBox(0).checked).toBe(true);
        });

        it("never rewrites a row's persisted flag it was handed, even when the header flag changes", () => {
            // The amend guarantee (review m-5): a posted lease's lines are
            // re-sent with their stored flags. A grid that re-applied
            // `rentVat` on a prop change would silently re-price them.
            const onChange = vi.fn();
            const lines = [
                row({ key: 0, id: "line-1", chargeTypeId: "ct-rent", grossAmount: 100, vatApplicable: true }),
                row({ key: 1, id: "line-2", chargeTypeId: "ct-rent", grossAmount: 100, vatApplicable: false }),
            ];
            const grid = (rentVat: boolean) => (
                <NextIntlClientProvider locale="en" messages={en}>
                    <LeaseLinesGrid lines={lines} chargeTypes={CHARGE_TYPES} editable onChange={onChange} rentVat={rentVat} />
                </NextIntlClientProvider>
            );
            const { rerender } = render(grid(false));
            expect([vatBox(0).checked, vatBox(1).checked]).toEqual([true, false]);

            rerender(grid(true));
            rerender(grid(false));
            rerender(grid(true));

            expect(onChange).not.toHaveBeenCalled();
            expect([vatBox(0).checked, vatBox(1).checked]).toEqual([true, false]);
        });
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

    /**
     * F14-15: /ar lease detail showed "Rent", "Parking Fee" etc. in English —
     * the grid read the catalogue's `nameEn` alone. The read-only view now
     * prefers the persisted line's own `chargeTypeNameAr` /
     * `creditAccountNameAr` on Arabic, and falls back sensibly when a line
     * predates those fields.
     */
    describe("Arabic charge and account names (F14-15)", () => {
        it("shows the line's own Arabic charge and account names on /ar", () => {
            renderReadOnlyAr([
                row({
                    key: 0, chargeTypeId: "ct-rent", grossAmount: 60000,
                    chargeTypeName: "Rent", chargeTypeNameAr: "الإيجار",
                    creditAccountId: "acc-1", creditAccountCode: "410100",
                    creditAccountName: "Rent Income", creditAccountNameAr: "إيرادات الإيجار",
                }),
            ]);
            const row0 = screen.getByTestId("lease-line-row-0");
            expect(row0).toHaveTextContent("الإيجار");
            expect(row0).not.toHaveTextContent("Rent");
            expect(row0).toHaveTextContent("إيرادات الإيجار");
            expect(row0).not.toHaveTextContent("Rent Income");
        });

        it("falls back to the English name when a line has no Arabic name yet", () => {
            renderReadOnlyAr([
                row({
                    key: 0, chargeTypeId: "ct-fee", grossAmount: 1500,
                    chargeTypeName: "Admin Fee", chargeTypeNameAr: null,
                    creditAccountId: "acc-2", creditAccountCode: "400200",
                    creditAccountName: "Admin Income", creditAccountNameAr: null,
                }),
            ]);
            const row0 = screen.getByTestId("lease-line-row-0");
            expect(row0).toHaveTextContent("Admin Fee");
            expect(row0).toHaveTextContent("Admin Income");
        });

        it("shows the English name on /en even when an Arabic name is present", () => {
            renderReadOnly([
                row({
                    key: 0, chargeTypeId: "ct-rent", grossAmount: 60000,
                    chargeTypeName: "Rent", chargeTypeNameAr: "الإيجار",
                }),
            ]);
            const row0 = screen.getByTestId("lease-line-row-0");
            expect(row0).toHaveTextContent("Rent");
            expect(row0).not.toHaveTextContent("الإيجار");
        });

        it("wraps the table so every column, including VAT, stays reachable rather than clipped", () => {
            renderReadOnlyAr([row({ key: 0, chargeTypeId: "ct-rent", grossAmount: 60000 })]);
            const wrapper = screen.getByTestId("lease-lines-grid");
            const scroller = wrapper.querySelector(":scope > .overflow-x-auto");
            expect(scroller).not.toBeNull();
            expect(scroller?.querySelector("table")).not.toBeNull();
            // No fixed negative margin or left-anchored positioning hiding a column.
            expect(wrapper.className).not.toMatch(/-ml-|left-\d/);
        });
    });
});
