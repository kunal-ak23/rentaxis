import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";

import en from "../../../../../../../messages/en.json";

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
});
