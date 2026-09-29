import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";
import type { ChargeType, LeaseDetail, LeaseLine } from "@/lib/api/leasing";

/**
 * I2 (#49 follow-on): "carry deposit forward" is ticked by default. If the
 * operator unticks "copy lines" to edit the rent, the grid still holds last
 * year's DEPOSIT line — and sending it as well would charge a second deposit
 * while the old one is JV-moved across.
 */

vi.mock("@/components/leases/LeaseLinesGrid", () => ({ default: () => <div data-testid="lines-grid" /> }));

const renew = vi.fn();
const renewalPreview = vi.fn();
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return { ...m, leaseApi: { ...m.leaseApi, renew: (...a: unknown[]) => renew(...a),
        renewalPreview: (...a: unknown[]) => renewalPreview(...a) } };
});

import RenewLeaseDialog from "../RenewLeaseDialog";

const rent: LeaseLine = {
    id: "line-1", seqNo: 1, chargeTypeId: "ct-rent", chargeTypeCode: "RENT", chargeTypeName: "Rent",
    behaviour: "RENT", creditAccountId: "acc-1", creditAccountCode: "2100", creditAccountName: "Advance rent",
    grossAmount: 48000, discountAmount: 0, netAmount: 48000, narration: null, vatApplicable: false,
    periodStart: "2024-10-01", periodEnd: "2025-09-30", addendumId: null,
};
const deposit: LeaseLine = {
    ...rent, id: "line-2", seqNo: 2, chargeTypeId: "ct-dep", chargeTypeCode: "DEPOSIT",
    chargeTypeName: "Security deposit", behaviour: "DEPOSIT", grossAmount: 5000, netAmount: 5000,
    periodStart: null, periodEnd: null,
};

const LEASE = {
    id: "lease-1", propertyId: "p1", startDate: "2024-10-01", endDate: "2025-09-30",
    lines: [rent, deposit],
} as unknown as LeaseDetail;

const CHARGE_TYPES = [
    { id: "ct-rent", behaviour: "RENT", active: true },
    { id: "ct-dep", behaviour: "DEPOSIT", active: true },
] as unknown as ChargeType[];

function renderDialog() {
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <RenewLeaseDialog open lease={LEASE} chargeTypes={CHARGE_TYPES} onClose={() => {}} onRenewed={() => {}} />
        </NextIntlClientProvider>,
    );
}

afterEach(() => {
    cleanup();
    renew.mockReset();
    renewalPreview.mockReset();
});

beforeEach(() => {
    renewalPreview.mockResolvedValue({ baseRent: 48000, newRent: 48000, changePercent: null, copiedLines: [],
        skippedOneOffLines: [], warnPercent: null, exceedsWarn: false });
});

describe("RenewLeaseDialog deposit carry-forward (I2)", () => {
    it("does not send last year's deposit line while the deposit is carried forward", async () => {
        renew.mockResolvedValue({ id: "lease-2" });
        renderDialog();
        fireEvent.click(screen.getByTestId("renew-copy-lines"));
        fireEvent.click(screen.getByTestId("renew-lease-confirm"));
        await waitFor(() => expect(renew).toHaveBeenCalled());
        const body = renew.mock.calls[0][1];
        expect(body.carryDepositForward).toBe(true);
        expect(body.lines.map((l: { chargeTypeId: string }) => l.chargeTypeId)).toEqual(["ct-rent"]);
    });

    it("sends it when the deposit is not carried forward", async () => {
        renew.mockResolvedValue({ id: "lease-2" });
        renderDialog();
        fireEvent.click(screen.getByTestId("renew-copy-lines"));
        fireEvent.click(screen.getByTestId("renew-carry-deposit"));
        fireEvent.click(screen.getByTestId("renew-lease-confirm"));
        await waitFor(() => expect(renew).toHaveBeenCalled());
        const body = renew.mock.calls[0][1];
        expect(body.carryDepositForward).toBe(false);
        expect(body.lines.map((l: { chargeTypeId: string }) => l.chargeTypeId)).toEqual(["ct-rent", "ct-dep"]);
    });
});

/**
 * Spec §4c: a one-off fee (an admin fee) is not copied into the new term, and the
 * dialog says so by name and amount — nothing is dropped silently.
 */
describe("RenewLeaseDialog one-off charges (§4c)", () => {
    const adminFee: LeaseLine = {
        ...rent, id: "line-3", seqNo: 3, chargeTypeId: "ct-admin", chargeTypeCode: "ADMIN_FEE",
        chargeTypeName: "Admin Fee", behaviour: "FEE", grossAmount: 1500, netAmount: 1500,
        periodStart: null, periodEnd: null, recognition: "ONE_OFF",
    };
    const parking: LeaseLine = { ...adminFee, id: "line-4", chargeTypeId: "ct-park", chargeTypeName: "Parking", recognition: "RENT_LIKE" };
    const oneOffByAddendum: LeaseLine = { ...adminFee, id: "line-5", chargeTypeName: "Key fee", addendumId: "add-1" };

    function renderWith(lines: LeaseLine[]) {
        return render(
            <NextIntlClientProvider locale="en" messages={en}>
                <RenewLeaseDialog open lease={{ ...LEASE, lines } as LeaseDetail} chargeTypes={CHARGE_TYPES}
                    onClose={() => {}} onRenewed={() => {}} />
            </NextIntlClientProvider>,
        );
    }

    it("names the one-off charges that will not be copied", () => {
        renderWith([rent, deposit, adminFee, parking, oneOffByAddendum]);
        const note = screen.getByTestId("renew-skipped-one-offs");
        expect(note).toHaveTextContent("Not copied (one-off): Admin Fee 1,500.00");
        expect(note).not.toHaveTextContent("Parking");
        expect(note).not.toHaveTextContent("Key fee");
    });

    it("leaves them out of a hand-edited grid too", async () => {
        renew.mockResolvedValue({ id: "lease-2" });
        renderWith([rent, deposit, adminFee, parking]);
        fireEvent.click(screen.getByTestId("renew-copy-lines"));
        fireEvent.click(screen.getByTestId("renew-lease-confirm"));
        await waitFor(() => expect(renew).toHaveBeenCalled());
        expect(renew.mock.calls[0][1].lines.map((l: { chargeTypeId: string }) => l.chargeTypeId))
            .toEqual(["ct-rent", "ct-park"]);
    });
});

/** Spec §4a/§4d: rent change with a live preview, added charges and the Ejari. */
describe("RenewLeaseDialog renewal terms (§4a, §4d)", () => {
    const TYPES = [
        ...CHARGE_TYPES,
        { id: "ct-renewal", code: "RENEWAL_FEE", nameEn: "Renewal Fee", nameAr: "رسوم التجديد", behaviour: "FEE",
            active: true, vatApplicableDefault: false },
    ] as unknown as ChargeType[];

    function renderTerms() {
        return render(
            <NextIntlClientProvider locale="en" messages={en}>
                <RenewLeaseDialog open lease={LEASE} chargeTypes={TYPES} onClose={() => {}} onRenewed={() => {}} />
            </NextIntlClientProvider>,
        );
    }

    it("previews a percentage, warns above the building's threshold, and sends the change", async () => {
        renewalPreview.mockResolvedValue({ baseRent: 48000, newRent: 51840, changePercent: 8, copiedLines: [],
            skippedOneOffLines: [], warnPercent: 5, exceedsWarn: true });
        renew.mockResolvedValue({ id: "lease-2" });
        renderTerms();
        fireEvent.click(screen.getByTestId("renew-mode-PERCENT"));
        fireEvent.change(screen.getByTestId("renew-percent"), { target: { value: "8" } });
        expect(await screen.findByTestId("renew-rent-preview")).toHaveTextContent("48,000.00 → 51,840.00 (+8.00%)");
        expect(screen.getByTestId("renew-rent-notice")).toHaveTextContent("5%");
        await waitFor(() => expect(renewalPreview).toHaveBeenLastCalledWith("lease-1",
            expect.objectContaining({ mode: "PERCENT", percent: 8 })));

        fireEvent.click(screen.getByTestId("renew-lease-confirm"));
        await waitFor(() => expect(renew).toHaveBeenCalled());
        const body = renew.mock.calls[0][1];
        expect(body.lines).toBeNull();
        expect(body.rentChange).toEqual({ mode: "PERCENT", percent: 8, newRentAmount: null });
    });

    /** PR #358 R1 P2-3: the discount that does not renew is its own line; amounts isolated LTR. */
    it("names the dropped discount and keeps the preview's figures LTR", async () => {
        renewalPreview.mockResolvedValue({ baseRent: 80000, newRent: 86400, changePercent: 8, copiedLines: [],
            skippedOneOffLines: [], warnPercent: null, exceedsWarn: false, droppedDiscount: 5000 });
        renderTerms();
        expect(await screen.findByTestId("renew-dropped-discount"))
            .toHaveTextContent("Discount 5,000.00 on the current contract does not renew");
        const preview = await screen.findByTestId("renew-rent-preview");
        expect(Array.from(preview.querySelectorAll("bdi[dir='ltr']")).map(b => b.textContent))
            .toEqual(["80,000.00", "86,400.00", "+8.00%"]);
    });

    it("shows the server's refusal of a percentage on a changed term", async () => {
        const { ApiError } = await import("@/lib/api/leasing");
        renewalPreview.mockRejectedValue(new ApiError(400, "The term length changed; enter the new rent amount instead of a percentage."));
        renderTerms();
        fireEvent.click(screen.getByTestId("renew-mode-PERCENT"));
        fireEvent.change(screen.getByTestId("renew-percent"), { target: { value: "8" } });
        expect(await screen.findByTestId("renew-preview-error")).toHaveTextContent("term length changed");
    });

    it("adds a renewal fee and the Ejari to the request", async () => {
        renew.mockResolvedValue({ id: "lease-2" });
        renderTerms();
        fireEvent.click(screen.getByTestId("renew-add-charge"));
        expect((screen.getByTestId("renew-extra-type-0") as HTMLSelectElement).value).toBe("ct-renewal");
        expect(screen.getByTestId("renew-lease-confirm")).toBeDisabled();
        fireEvent.change(screen.getByTestId("renew-extra-amount-0"), { target: { value: "1050" } });
        fireEvent.change(screen.getByTestId("renew-ejari"), { target: { value: "EJ-2025-9" } });
        fireEvent.click(screen.getByTestId("renew-lease-confirm"));
        await waitFor(() => expect(renew).toHaveBeenCalled());
        const body = renew.mock.calls[0][1];
        expect(body.additionalLines).toEqual([{ chargeTypeId: "ct-renewal", grossAmount: 1050, discountAmount: 0, vatApplicable: false }]);
        expect(body.ejariNumber).toBe("EJ-2025-9");
        expect(body.rentChange).toBeNull();
    });
});

/**
 * Owner request (2026-09-29): a new start date re-derives the end date while it
 * still holds the proposed value (same length as the current term — a year for a
 * year); an end date the user typed is kept.
 */
describe("RenewLeaseDialog end date follows the start date until typed", () => {
    it("moves the proposed end with the start, and keeps a typed one", () => {
        renderDialog();
        const start = document.getElementById("renew-start-date") as HTMLInputElement;
        const end = () => (document.getElementById("renew-end-date") as HTMLInputElement).value;
        expect(end()).toBe("2026-09-30");
        fireEvent.change(start, { target: { value: "2025-11-01" } });
        expect(end()).toBe("2026-10-31");
        fireEvent.change(screen.getByTestId("renew-end-date"), { target: { value: "2026-06-30" } });
        fireEvent.change(start, { target: { value: "2025-12-01" } });
        expect(end()).toBe("2026-06-30");
    });
});

/** F15-05: the new term defaults to the current one's length, not a year. */
describe("RenewLeaseDialog default term (F15-05)", () => {
    it("proposes a six-month renewal of a six-month lease", () => {
        render(
            <NextIntlClientProvider locale="en" messages={en}>
                <RenewLeaseDialog open lease={{ ...LEASE, startDate: "2026-04-01", endDate: "2026-09-30" } as LeaseDetail}
                    chargeTypes={CHARGE_TYPES} onClose={() => {}} onRenewed={() => {}} />
            </NextIntlClientProvider>,
        );
        expect((document.getElementById("renew-start-date") as HTMLInputElement).value).toBe("2026-10-01");
        expect((document.getElementById("renew-end-date") as HTMLInputElement).value).toBe("2027-03-31");
    });

    it("keeps a year for a year, and a month-and-days term to the day", () => {
        renderDialog();
        expect((document.getElementById("renew-end-date") as HTMLInputElement).value).toBe("2026-09-30");
    });
});

/**
 * Break-it round 1 (money) F1/F2: a new rent of 0.001 drafted a successor with
 * RENT 0.00 (and then blocked any further renewal); 3 decimals were rounded.
 */
describe("RenewLeaseDialog money guards (F1/F2)", () => {
    function renderIt() {
        return render(
            <NextIntlClientProvider locale="en" messages={en}>
                <RenewLeaseDialog open lease={LEASE} chargeTypes={CHARGE_TYPES} onClose={() => {}} onRenewed={() => {}} />
            </NextIntlClientProvider>,
        );
    }

    it("refuses a new rent below one fil or with three decimals", () => {
        renderIt();
        fireEvent.click(screen.getByTestId("renew-mode-AMOUNT"));
        fireEvent.change(screen.getByTestId("renew-amount"), { target: { value: "0.001" } });
        expect(screen.getByTestId("renew-lease-confirm")).toBeDisabled();
        expect(screen.getByTestId("money-input-error")).toHaveTextContent(en.MoneyInput.min);
        fireEvent.change(screen.getByTestId("renew-amount"), { target: { value: "0" } });
        expect(screen.getByTestId("renew-lease-confirm")).toBeDisabled();
        expect(screen.getByTestId("money-input-error")).toHaveTextContent(en.MoneyInput.min);
        fireEvent.change(screen.getByTestId("renew-amount"), { target: { value: "52000.555" } });
        expect(screen.getByTestId("renew-lease-confirm")).toBeDisabled();
        expect(screen.getByTestId("money-input-error")).toHaveTextContent(en.MoneyInput.decimals);
    });

    it("sends a grouped amount exactly", async () => {
        renew.mockResolvedValue({ id: "lease-2" });
        renderIt();
        fireEvent.click(screen.getByTestId("renew-mode-AMOUNT"));
        fireEvent.change(screen.getByTestId("renew-amount"), { target: { value: "52,000.50" } });
        fireEvent.click(screen.getByTestId("renew-lease-confirm"));
        await waitFor(() => expect(renew).toHaveBeenCalled());
        expect(renew.mock.calls[0][1].rentChange).toEqual({ mode: "AMOUNT", percent: null, newRentAmount: 52000.5 });
    });
});

/**
 * Round-2 forms2 Finding 2: the wizard warns at 5 years and hard-blocks at 50
 * (`web/src/lib/leaseTerm.ts`, `LeaseService.MAX_TERM_YEARS`/backend's
 * `LeaseRenewalService.requireSaneTerm`); the Renew dialog had neither, so a
 * `2999-06-01` typo reached the server before anything said so.
 */
describe("RenewLeaseDialog term-length guard (forms2 Finding 2)", () => {
    it("blocks a renewal end date more than 50 years out, same wording as the wizard", () => {
        renderDialog();
        fireEvent.change(screen.getByTestId("renew-end-date"), { target: { value: "2999-06-01" } });
        expect(screen.getByTestId("renew-lease-confirm")).toBeDisabled();
        expect(screen.getByTestId("renew-term-too-long")).toHaveTextContent(
            en.Leasing.errTermTooLong.replace("{max}", "50"),
        );
    });

    it("warns past 5 years and requires an explicit continue before Confirm is live", async () => {
        renew.mockResolvedValue({ id: "lease-2" });
        renderDialog();
        // Six years from the default 2025-10-01 start.
        fireEvent.change(screen.getByTestId("renew-end-date"), { target: { value: "2031-09-30" } });

        expect(screen.getByTestId("renew-lease-confirm")).toBeDisabled();
        const warning = screen.getByTestId("renew-long-term-confirm");
        expect(warning).toHaveTextContent(en.Leasing.longTermConfirm.replace("{years}", "6"));

        fireEvent.click(screen.getByTestId("renew-long-term-continue"));
        expect(screen.queryByTestId("renew-long-term-confirm")).not.toBeInTheDocument();
        expect(screen.getByTestId("renew-lease-confirm")).not.toBeDisabled();

        fireEvent.click(screen.getByTestId("renew-lease-confirm"));
        await waitFor(() => expect(renew).toHaveBeenCalled());
    });

    it("re-arms the confirmation once the date changes again after acknowledging it", () => {
        renderDialog();
        fireEvent.change(screen.getByTestId("renew-end-date"), { target: { value: "2031-09-30" } });
        fireEvent.click(screen.getByTestId("renew-long-term-continue"));
        expect(screen.getByTestId("renew-lease-confirm")).not.toBeDisabled();

        fireEvent.change(screen.getByTestId("renew-end-date"), { target: { value: "2032-09-30" } });
        expect(screen.getByTestId("renew-lease-confirm")).toBeDisabled();
        expect(screen.getByTestId("renew-long-term-confirm")).toBeInTheDocument();
    });
});

/**
 * Break-it R4 money4 F2: a renewal's contract date typed 2099 (for 2026) posted
 * the contract in 2099. The server refuses a contract date more than a year
 * ahead (posting-date policy, `PostingDatePath.LEASE_POST`); the dialog says so
 * first and caps the input.
 */
describe("RenewLeaseDialog contract date (R4 money4 F2)", () => {
    it("caps the contract date at a year ahead and blocks 2099 with a message", () => {
        renderDialog();
        const input = screen.getByLabelText(en.Leasing.contractDate) as HTMLInputElement;
        expect(input.max).toMatch(/^\d{4}-\d{2}-\d{2}$/);
        fireEvent.change(input, { target: { value: "2099-12-31" } });
        expect(screen.getByTestId("renew-lease-confirm")).toBeDisabled();
        expect(screen.getByTestId("renew-contract-date-too-far")).toHaveTextContent("at most one year from today");
    });
});
