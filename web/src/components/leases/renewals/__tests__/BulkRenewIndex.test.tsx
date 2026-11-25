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
});
