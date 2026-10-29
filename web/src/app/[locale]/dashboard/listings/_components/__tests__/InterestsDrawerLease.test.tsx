import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";

import en from "../../../../../../../messages/en.json";
import { ApiError } from "@/lib/api/facilities";

const push = vi.hoisted(() => vi.fn());
const api = vi.hoisted(() => ({ fetchInterests: vi.fn(), createLeaseFromInterest: vi.fn() }));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { accessToken: "tok" } } }) }));
vi.mock("@/i18n/routing", () => ({
    useRouter: () => ({ push }),
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>,
}));
vi.mock("@/lib/api/listings", () => api);

import { InterestsDrawer } from "../InterestsDrawer";

/** F14-51: "Create lease" on an enquiry opens the new draft. */
describe("InterestsDrawer create lease", () => {
    afterEach(() => { cleanup(); vi.clearAllMocks(); });

    it("creates the draft from the enquiry and opens it", async () => {
        api.fetchInterests.mockResolvedValue({
            content: [{ id: "i1", listingId: "L1", renterUserId: "u1", renterName: "Twin Tariq", renterEmail: "t@x", renterPhone: null,
                note: null, status: "ACTIVE", createdAt: "2026-09-01T10:00:00" }],
            totalElements: 1, totalPages: 1, number: 0, size: 10,
        });
        api.createLeaseFromInterest.mockResolvedValue({ id: "lease-9" });
        render(
            <NextIntlClientProvider locale="en" messages={en}>
                <InterestsDrawer listingId="L1" listingTitle="Bright 1BR" onClose={() => {}} />
            </NextIntlClientProvider>,
        );
        fireEvent.click(await screen.findByTestId("interest-create-lease-i1"));
        await waitFor(() => expect(push).toHaveBeenCalledWith("/dashboard/leases/lease-9"));
        expect(api.createLeaseFromInterest).toHaveBeenCalledWith("L1", "i1", "tok");
    });

    it("shows a converted enquiry as translated, with a link to its lease and no second create", async () => {
        api.fetchInterests.mockResolvedValue({
            content: [{ id: "i2", listingId: "L1", renterUserId: "u1", renterName: "Twin Tariq", renterEmail: null, renterPhone: null,
                note: null, status: "CONVERTED", createdAt: "2026-09-01T10:00:00", leaseId: "lease-7" }],
            totalElements: 1, totalPages: 1, number: 0, size: 10,
        });
        render(
            <NextIntlClientProvider locale="en" messages={en}>
                <InterestsDrawer listingId="L1" listingTitle="Bright 1BR" onClose={() => {}} />
            </NextIntlClientProvider>,
        );
        expect(await screen.findByText("Tenancy Contract drafted")).toBeTruthy();
        expect(screen.getByTestId("interest-lease-i2").getAttribute("href")).toBe("/dashboard/leases/lease-7");
        expect(screen.queryByTestId("interest-create-lease-i2")).toBeNull();
    });

    /**
     * Client request 2026-10-29 (ruling: ask at conversion): an enquirer whose account has
     * no mobile is refused by the server until staff give one; the drawer then asks for it
     * on that enquiry and sends it normalized.
     */
    it("asks for the tenant's mobile when the server needs one, then creates the contract", async () => {
        api.fetchInterests.mockResolvedValue({
            content: [{ id: "i1", listingId: "L1", renterUserId: "u1", renterName: "Noor Haddad", renterEmail: "n@x", renterPhone: null,
                note: null, status: "ACTIVE", createdAt: "2026-09-01T10:00:00" }],
            totalElements: 1, totalPages: 1, number: 0, size: 10,
        });
        api.createLeaseFromInterest
            .mockRejectedValueOnce(new ApiError(400, "The tenant needs a mobile number",
                JSON.stringify({ code: "listing.tenantMobileRequired", message: "The tenant needs a mobile number" })))
            .mockResolvedValueOnce({ id: "lease-10" });
        render(
            <NextIntlClientProvider locale="en" messages={en}>
                <InterestsDrawer listingId="L1" listingTitle="Bright 1BR" onClose={() => {}} />
            </NextIntlClientProvider>,
        );
        fireEvent.click(await screen.findByTestId("interest-create-lease-i1"));
        const mobile = await screen.findByTestId("interest-mobile-i1");
        expect(screen.getByText(en.Common.errors.listing.tenantMobileRequired)).toBeTruthy();

        fireEvent.change(mobile, { target: { value: "N/A" } });
        fireEvent.click(screen.getByTestId("interest-create-lease-i1"));
        expect(await screen.findByText(en.MasterData.phoneInvalid)).toBeTruthy();
        expect(api.createLeaseFromInterest).toHaveBeenCalledTimes(1);

        fireEvent.change(mobile, { target: { value: "٠٥٠ ١٢٣ ٤٥٦٧" } });
        fireEvent.click(screen.getByTestId("interest-create-lease-i1"));
        await waitFor(() => expect(push).toHaveBeenCalledWith("/dashboard/leases/lease-10"));
        expect(api.createLeaseFromInterest).toHaveBeenLastCalledWith("L1", "i1", "tok", "050 123 4567");
    });
});
