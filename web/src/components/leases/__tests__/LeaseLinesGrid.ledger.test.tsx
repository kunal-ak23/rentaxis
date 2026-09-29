import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";
import ar from "../../../../messages/ar.json";
import LeaseLinesGrid, { ledgerHref } from "../LeaseLinesGrid";
import { blankLine, type LineRow } from "../leaseMath";
import type { ChargeType } from "@/lib/api/leasing";

/**
 * Demo feedback 2026-09-29: the charge-lines grid's "Credit A/c" column is now
 * "Ledger". It names the account plainly ("Advance Rent – Desert Rose Gardens"),
 * without the numeric code, which stays in the tooltip; on a contract page the
 * name links to the General Ledger filtered to that account and this contract.
 */

const pickerProps = vi.hoisted(() => ({ last: {} as Record<string, unknown> }));
vi.mock("@/components/finance/AccountPicker", () => ({
    default: (p: Record<string, unknown>) => {
        pickerProps.last = p;
        return <div data-testid="account-picker" />;
    },
}));

const CHARGE_TYPES: ChargeType[] = [{
    id: "ct-rent", code: "RENT", nameEn: "Rent", nameAr: null, role: "ADVANCE_RENT",
    behaviour: "RENT", vatApplicableDefault: false, active: true, displayOrder: 1,
}];

const LINE: LineRow = {
    ...blankLine(0), chargeTypeId: "ct-rent", grossAmount: 60000,
    creditAccountId: "acc-9", creditAccountCode: "210104", creditAccountName: "Advance Rent – Desert Rose Gardens",
    creditAccountNameAr: "إيجار مقدم – حدائق وردة الصحراء",
};

function renderGrid(locale: "en" | "ar", props: Partial<React.ComponentProps<typeof LeaseLinesGrid>> = {}) {
    return render(
        <NextIntlClientProvider locale={locale} messages={locale === "en" ? en : ar}>
            <LeaseLinesGrid lines={[LINE]} chargeTypes={CHARGE_TYPES} editable={false} {...props} />
        </NextIntlClientProvider>,
    );
}

afterEach(() => cleanup());

describe("LeaseLinesGrid — Ledger column", () => {
    it("is headed Ledger / دفتر الأستاذ, not Credit A/c", () => {
        renderGrid("en");
        expect(screen.getByRole("columnheader", { name: "Ledger" })).toBeInTheDocument();
        expect(screen.queryByText("Credit A/c")).not.toBeInTheDocument();
        cleanup();
        renderGrid("ar");
        expect(screen.getByRole("columnheader", { name: "دفتر الأستاذ" })).toBeInTheDocument();
    });

    it("shows the plain account name, the code only in the tooltip", () => {
        renderGrid("en");
        const cell = screen.getByTestId("lease-line-ledger-0");
        expect(cell).toHaveTextContent("Advance Rent – Desert Rose Gardens");
        expect(cell).not.toHaveTextContent("210104");
        expect(cell).toHaveAttribute("title", "210104");
    });

    it("links to the General Ledger for that account and this contract", () => {
        renderGrid("en", { ledgerLink: { leaseId: "lease-1", from: "2026-01-05", to: "2027-01-31" } });
        const link = screen.getByTestId("lease-line-ledger-0");
        expect(link.tagName).toBe("A");
        expect(link).toHaveAttribute("href", "/en/dashboard/finance/general-ledger?accountIds=acc-9&leaseId=lease-1&from=2026-01-05&to=2027-01-31");
        expect(link).toHaveTextContent("Advance Rent – Desert Rose Gardens");
        expect(link.getAttribute("title")).toContain("210104");
    });

    it("uses the Arabic account name on /ar", () => {
        renderGrid("ar", { ledgerLink: { leaseId: "lease-1" } });
        expect(screen.getByTestId("lease-line-ledger-0")).toHaveTextContent("إيجار مقدم – حدائق وردة الصحراء");
        expect(screen.getByTestId("lease-line-ledger-0")).toHaveAttribute("href", "/ar/dashboard/finance/general-ledger?accountIds=acc-9&leaseId=lease-1");
    });

    it("builds the ledger URL without a contract when none is named", () => {
        expect(ledgerHref("a1", {})).toBe("/dashboard/finance/general-ledger?accountIds=a1");
    });

    it("the editable grid's picker shows the name only, under the Ledger placeholder", () => {
        renderGrid("en", { editable: true, onChange: () => {} });
        expect(pickerProps.last).toMatchObject({ nameOnly: true, placeholder: "Ledger", value: "acc-9" });
    });
});
