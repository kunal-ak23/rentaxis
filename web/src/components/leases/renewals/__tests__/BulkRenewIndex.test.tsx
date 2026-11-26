import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../messages/en.json";
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>,
}));
const renew = vi.fn();
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return { ...m, leaseBatchApi: { ...m.leaseBatchApi, renew: (...a: unknown[]) => renew(...a) } };
});
import BulkRenewDialog, { type RenewTarget } from "../BulkRenewDialog";

const T = (id: string) => ({ id, unitIdentifier: `U-${id}`, renterName: "R", propertyName: "P", currentRentAmount: 100000, rentAmount: 100000 }) as RenewTarget;
const row = (leaseId: string, extra: object) => ({
    leaseId, ok: false, renewalLeaseId: null, startDate: null, endDate: null, previousRent: null, newRent: null,
    changePercent: null, chequeRows: 0, ...extra,
});
afterEach(() => { cleanup(); renew.mockReset(); });

describe("BulkRenewDialog rental index", () => {
    it("lists above-cap refusals, then retries those with one shared reason", async () => {
        renew.mockResolvedValueOnce([
            row("a", { ok: true, renewalLeaseId: "x", previousRent: 100000, newRent: 105000 }),
            row("b", { code: "renewal.indexOverrideRequired", args: { cap: "105,000" }, message: "above" }),
        ]);
        renew.mockResolvedValueOnce([row("b", { ok: true, renewalLeaseId: "y", previousRent: 100000, newRent: 110000, indexAboveCap: true })]);
        render(
            <NextIntlClientProvider locale="en" messages={en}>
                <BulkRenewDialog open targets={[T("a"), T("b")]} chargeTypes={[]} onClose={() => {}} onDone={() => {}} />
            </NextIntlClientProvider>,
        );
        fireEvent.click(screen.getByTestId("bulk-renew-confirm"));
        expect(await screen.findByTestId("bulk-renew-result-b")).toHaveTextContent("above the rental index cap of AED 105,000");
        expect(renew.mock.calls[0][0]).not.toHaveProperty("indexOverrideReason");
        const reason = screen.getByTestId("bulk-index-reason");
        fireEvent.change(reason, { target: { value: "Agreed" } });
        fireEvent.click(screen.getByTestId("bulk-index-retry"));
        await waitFor(() => expect(renew).toHaveBeenCalledTimes(2));
        expect(renew.mock.calls[1][0].leaseIds).toEqual(["b"]);
        expect(renew.mock.calls[1][0].indexOverrideReason).toBe("Agreed");
    });

    it("retries with the first run's rule and terms, whatever the screen holds by then (final review M2)", async () => {
        const fee = { id: "ct-fee", code: "ADMIN_FEE", nameEn: "Admin fee", nameAr: null, role: "ADMIN_FEE",
            behaviour: "FEE", vatApplicableDefault: true, active: true, displayOrder: 1 };
        renew.mockResolvedValueOnce([row("b", { code: "renewal.indexOverrideRequired", args: { cap: "105,000" }, message: "above" })]);
        renew.mockResolvedValueOnce([row("b", { ok: true, renewalLeaseId: "y", previousRent: 100000, newRent: 110000, indexAboveCap: true })]);
        const ui = (chargeTypes: unknown[]) => (
            <NextIntlClientProvider locale="en" messages={en}>
                <BulkRenewDialog open targets={[T("b")]} chargeTypes={chargeTypes as never} onClose={() => {}} onDone={() => {}} />
            </NextIntlClientProvider>
        );
        const view = render(ui([fee]));
        fireEvent.click(screen.getByTestId("bulk-renew-mode-PERCENT"));
        fireEvent.change(screen.getByTestId("bulk-renew-percent"), { target: { value: "10" } });
        fireEvent.click(screen.getByTestId("bulk-renew-same-term"));
        fireEvent.change(screen.getByTestId("bulk-renew-months"), { target: { value: "6" } });
        fireEvent.change(screen.getByTestId("bulk-renew-fee-type"), { target: { value: "ct-fee" } });
        fireEvent.change(screen.getByTestId("bulk-renew-fee-amount"), { target: { value: "500" } });
        fireEvent.click(screen.getByTestId("bulk-renew-confirm"));
        await screen.findByTestId("bulk-index-reason");
        const first = renew.mock.calls[0][0];
        expect(first.rule).toEqual({ mode: "PERCENT", percent: 10, amount: null });
        expect(first.termMonths).toBe(6);
        expect(first.additionalLines).toEqual([{ chargeTypeId: "ct-fee", grossAmount: 500, discountAmount: 0, vatApplicable: true }]);

        // The page reloads its charge types after the run (the fee type is now switched off, its VAT default gone).
        view.rerender(ui([{ ...fee, active: false, vatApplicableDefault: false }]));
        fireEvent.change(screen.getByTestId("bulk-index-reason"), { target: { value: "Agreed" } });
        fireEvent.click(screen.getByTestId("bulk-index-retry"));
        await waitFor(() => expect(renew).toHaveBeenCalledTimes(2));
        const second = renew.mock.calls[1][0];
        expect(second.leaseIds).toEqual(["b"]);
        expect(second.indexOverrideReason).toBe("Agreed");
        expect({ ...second, leaseIds: null, indexOverrideReason: null }).toEqual({ ...first, leaseIds: null, indexOverrideReason: null });
    });
});
