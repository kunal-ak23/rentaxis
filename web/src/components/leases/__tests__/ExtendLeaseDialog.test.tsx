import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";
import type { ChargeType, LeaseDetail } from "@/lib/api/leasing";

/**
 * Extend posts immediately, so its gate has to be the server's gate: the
 * totals must agree AND every cheque row must be one
 * `ChequeRowRules.validateRow` accepts. Issue #266: the default row was
 * `{ amount: 0, mode: "PDC", postingDate: today }` with no `chequeDate`, and
 * the button was live — the 400 ("a post-dated cheque needs the date written
 * on it") landed in the generic error list after the fact.
 */

vi.mock("@/components/finance/AccountPicker", () => ({ default: () => <div data-testid="account-picker" /> }));
vi.mock("@/components/leases/LeaseLinesGrid", () => ({ default: () => <div data-testid="lines-grid" /> }));

/**
 * The lines half is stubbed out to a valid 1,000.00 so the only thing left
 * that can disable Extend is the cheque half — otherwise `linesAreValid` on an
 * empty blank line would disable the button for a reason this file is not
 * about, and the cheque gate could be deleted without a test noticing.
 */
vi.mock("@/components/leases/leaseMath", async orig => {
    const m = await orig<typeof import("@/components/leases/leaseMath")>();
    return {
        ...m,
        linesAreValid: () => true,
        totalsOf: () => ({ gross: 1000, discount: 0, net: 1000, vat: 0, inclVat: 1000 }),
    };
});

import ExtendLeaseDialog from "../ExtendLeaseDialog";

const LEASE = {
    id: "lease-1", propertyId: "p1", startDate: "2026-01-01", endDate: "2026-12-31",
} as unknown as LeaseDetail;

const CHARGE_TYPES: ChargeType[] = [];

function renderDialog() {
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <ExtendLeaseDialog
                open
                lease={LEASE}
                chargeTypes={CHARGE_TYPES}
                onClose={() => {}}
                onExtended={() => {}}
            />
        </NextIntlClientProvider>,
    );
}

afterEach(cleanup);

describe("ExtendLeaseDialog cheque rows", () => {
    it("gives the default row a cheque date instead of leaving it dateless (#266)", () => {
        renderDialog();
        const date = screen.getByLabelText("Date 1") as HTMLInputElement;
        expect(date.value).not.toBe("");
        expect(date.value).toBe((screen.getByLabelText("Posting Date 1") as HTMLInputElement).value);
        expect(screen.queryByTestId("extend-cheque-row-errors")).not.toBeInTheDocument();
    });

    it("gives an added row one too", () => {
        renderDialog();
        fireEvent.click(screen.getByTestId("extend-add-cheque"));
        expect((screen.getByLabelText("Date 2") as HTMLInputElement).value).not.toBe("");
    });

    it("blocks Extend on a dateless PDC row and says why", () => {
        renderDialog();
        fireEvent.change(screen.getByTestId("extend-new-end-date"), { target: { value: "2027-06-30" } });
        fireEvent.change(screen.getByLabelText("Amount 1"), { target: { value: "1000" } });
        // Totals agree and the lines are valid, so the cheque row is now the
        // only thing the gate can be judging.
        expect(screen.getByTestId("extend-match")).toHaveAttribute("data-match", "true");
        expect(screen.getByTestId("extend-lease-confirm")).toBeEnabled();

        fireEvent.change(screen.getByLabelText("Date 1"), { target: { value: "" } });

        expect(screen.getByTestId("extend-lease-confirm")).toBeDisabled();
        expect(screen.getByTestId("extend-cheque-row-errors")).toHaveTextContent(
            "Row 1: a post-dated cheque needs the date written on it",
        );
    });

    it("never offers ONLINE as an extension cheque mode", () => {
        renderDialog();
        const modes = Array.from((screen.getByLabelText("Mode 1") as HTMLSelectElement).options).map(o => o.value);
        expect(modes).toEqual(["PDC", "CASH", "TRANSFER"]);
    });
});

describe("ExtendLeaseDialog current end date (F14-34)", () => {
    it("shows the current end date formatted as dd/mm/yyyy, not raw ISO", () => {
        renderDialog();
        const current = screen.getByLabelText("Current End Date") as HTMLInputElement;
        expect(current.value).toBe("31/12/2026");
    });

    it("shows a validation message when the new end date is not after the current one", () => {
        renderDialog();
        fireEvent.change(screen.getByTestId("extend-new-end-date"), { target: { value: "2026-12-31" } });
        expect(screen.getByTestId("extend-new-end-date-error")).toHaveTextContent(
            "The new end date must be after the current one",
        );
        expect(screen.getByTestId("extend-lease-confirm")).toBeDisabled();
    });

    it("clears the validation message once the new end date is after the current one", () => {
        renderDialog();
        fireEvent.change(screen.getByTestId("extend-new-end-date"), { target: { value: "2026-12-31" } });
        expect(screen.getByTestId("extend-new-end-date-error")).toBeInTheDocument();

        fireEvent.change(screen.getByTestId("extend-new-end-date"), { target: { value: "2027-06-30" } });
        expect(screen.queryByTestId("extend-new-end-date-error")).not.toBeInTheDocument();
    });
});

/**
 * Round-2 forms2 Finding 2 (and its own note: "the same gap likely exists in
 * TransferLeaseDialog... though only Renew was exercised end-to-end"). The
 * server checks the WHOLE term from the lease's original start
 * (`LeaseRenewalService.requireSaneTerm(lease.getStartDate(), r.newEndDate())`,
 * line 574) — the client warning has to use the same two dates, not just the
 * extension's own span.
 */
describe("ExtendLeaseDialog term-length guard (forms2 Finding 2)", () => {
    it("blocks a new end date more than 50 years past the lease's original start", () => {
        renderDialog();
        fireEvent.change(screen.getByTestId("extend-new-end-date"), { target: { value: "2999-06-01" } });
        fireEvent.change(screen.getByLabelText("Amount 1"), { target: { value: "1000" } });
        expect(screen.getByTestId("extend-match")).toHaveAttribute("data-match", "true");

        expect(screen.getByTestId("extend-lease-confirm")).toBeDisabled();
        expect(screen.getByTestId("extend-term-too-long")).toHaveTextContent(
            en.Leasing.errTermTooLong.replace("{max}", "50"),
        );
    });

    it("warns past 5 years (from the ORIGINAL start, not the extension's own length) and requires a continue", () => {
        renderDialog();
        fireEvent.change(screen.getByTestId("extend-new-end-date"), { target: { value: "2032-06-30" } });
        fireEvent.change(screen.getByLabelText("Amount 1"), { target: { value: "1000" } });
        expect(screen.getByTestId("extend-match")).toHaveAttribute("data-match", "true");

        expect(screen.getByTestId("extend-lease-confirm")).toBeDisabled();
        expect(screen.getByTestId("extend-long-term-confirm")).toHaveTextContent(
            en.Leasing.longTermConfirm.replace("{years}", "6"),
        );

        fireEvent.click(screen.getByTestId("extend-long-term-continue"));
        expect(screen.queryByTestId("extend-long-term-confirm")).not.toBeInTheDocument();
        expect(screen.getByTestId("extend-lease-confirm")).toBeEnabled();
    });

    it("re-arms once the new end date changes again", () => {
        renderDialog();
        fireEvent.change(screen.getByTestId("extend-new-end-date"), { target: { value: "2032-06-30" } });
        fireEvent.change(screen.getByLabelText("Amount 1"), { target: { value: "1000" } });
        fireEvent.click(screen.getByTestId("extend-long-term-continue"));
        expect(screen.getByTestId("extend-lease-confirm")).toBeEnabled();

        fireEvent.change(screen.getByTestId("extend-new-end-date"), { target: { value: "2033-06-30" } });
        expect(screen.getByTestId("extend-lease-confirm")).toBeDisabled();
        expect(screen.getByTestId("extend-long-term-confirm")).toBeInTheDocument();
    });
});
