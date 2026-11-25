import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";
import ar from "../../../../messages/ar.json";

/** Final review I2: the stored rental index checks on the lease page — staff only, flag-gated, newest first. */

const h = vi.hoisted(() => ({ role: "TENANT_ADMIN", flag: true }));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: h.role } }, status: "authenticated" }) }));
vi.mock("@/hooks/useTenantFeatures", () => ({
    useTenantFeatures: () => ({ isEnabled: (f: string) => f === "RENTAL_INDEX" && h.flag, tenantSlug: "x" }),
}));
const api = vi.hoisted(() => ({ indexChecks: vi.fn() }));
vi.mock("@/lib/api/rentalIndex", async (orig) => {
    const real = await orig<typeof import("@/lib/api/rentalIndex")>();
    return { ...real, rentalIndexApi: api };
});

import LeaseIndexChecks from "../LeaseIndexChecks";

const check = (o: Record<string, unknown> = {}) => ({
    id: "c1", leaseId: "pred", successorLeaseId: "succ", decidedAt: "2026-11-20T08:00:00Z", decidedBy: "u1",
    decidedByName: "Sam Admin", baseRent: 120000, newRent: 120000, average: 150000, gapPercent: 20,
    allowedPercent: 5, capRent: 126000, aboveCap: false, overrideReason: null, rateSource: "MANUAL",
    rateCheckedOn: "2026-11-01", hasUnitDocument: false, ...o,
});

function show(locale: "en" | "ar" = "en") {
    return render(
        <NextIntlClientProvider locale={locale} messages={locale === "en" ? en : ar}>
            <LeaseIndexChecks leaseId="pred" refreshKey={1} />
        </NextIntlClientProvider>,
    );
}

beforeEach(() => {
    h.role = "TENANT_ADMIN"; h.flag = true;
    api.indexChecks.mockReset();
});
afterEach(cleanup);

describe("LeaseIndexChecks", () => {
    it("lists each check; an above-cap one shows its reason, who and when", async () => {
        api.indexChecks.mockResolvedValue([
            check({ id: "c2", newRent: 130000, aboveCap: true, overrideReason: "Agreed with the renter",
                decidedAt: "2026-11-21T08:00:00Z" }),
            check(),
        ]);
        show();
        const rows = await screen.findAllByTestId("lease-index-check");
        expect(api.indexChecks).toHaveBeenCalledWith("pred");
        expect(rows).toHaveLength(2);
        expect(rows[0].textContent).toContain("Above the cap: AED 130,000 a year against a cap of AED 126,000 (+5%).");
        expect(rows[0].textContent).toContain("Sam Admin on 21/11/2026");
        expect(rows[0].textContent).toContain("Range: Entered by hand, checked on 01/11/2026");
        expect(screen.getByTestId("lease-index-check-reason").textContent).toBe("Reason: Agreed with the renter");
        expect(rows[1].textContent).toContain("Within the cap: AED 120,000 a year");
        expect(rows[1].querySelector("[data-testid=lease-index-check-reason]")).toBeNull();
    });

    it("names the unit's own DLD result, and reads in Arabic with LTR figures", async () => {
        api.indexChecks.mockResolvedValue([check({ hasUnitDocument: true, decidedByName: null })]);
        show("ar");
        const row = await screen.findByTestId("lease-index-check");
        expect(row.textContent).toContain(ar.RentalIndex.checkUnitResult);
        expect(row.textContent).toContain("ضمن الحد الأقصى");
        expect(row.querySelector("bdi[dir=ltr]")?.textContent).toBe("120,000");
    });

    it("renders nothing and asks nothing when the flag is off or the viewer is a renter", async () => {
        h.flag = false;
        const off = show();
        expect(off.container.innerHTML).toBe("");
        off.unmount();
        h.flag = true; h.role = "RENTER";
        const renter = show();
        expect(renter.container.innerHTML).toBe("");
        expect(api.indexChecks).not.toHaveBeenCalled();
    });

    it("renders nothing when there are no checks or they cannot be read", async () => {
        api.indexChecks.mockResolvedValueOnce([]);
        const empty = show();
        await waitFor(() => expect(api.indexChecks).toHaveBeenCalledTimes(1));
        expect(empty.container.innerHTML).toBe("");
        empty.unmount();
        api.indexChecks.mockRejectedValueOnce(new Error("404"));
        const failed = show();
        await waitFor(() => expect(api.indexChecks).toHaveBeenCalledTimes(2));
        expect(failed.container.innerHTML).toBe("");
    });
});
