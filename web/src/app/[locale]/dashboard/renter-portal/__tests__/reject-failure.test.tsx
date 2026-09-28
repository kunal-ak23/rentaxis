import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../messages/en.json";
import ar from "../../../../../../messages/ar.json";

// Break-it round 2 follow-up (silent mutation sweep): Reject closed the
// confirm dialog and, on a refused or failed PUT, said nothing — the renter
// believed the contract was rejected. It must say it was not.

vi.mock("next-auth/react", () => ({
    useSession: () => ({ data: { user: { name: "Ahmed", role: "RENTER" } } }),
}));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
    useRouter: () => ({ push: vi.fn() }),
}));
vi.mock("@/app/[locale]/dashboard/meetings/CreateMeetingModal", () => ({ default: () => null }));
vi.mock("@/components/renewals/RenewalBanner", () => ({ default: () => null }));

import RenterPortalPage from "../page";

const lease = {
    id: "l1", unitId: "u1", renterId: "r1", renterName: "Ahmed", rentAmount: 60000, depositAmount: 5000,
    ejariNumber: "E-1", paymentTerms: 4, propertyName: "Tower", unitIdentifier: "A-203",
    startDate: "2026-10-01", endDate: "2027-09-30", status: "PENDING_SIGNATURE", hasContract: true, renterAcceptedAt: null,
};
let reject: () => Response;

beforeEach(() => {
    global.fetch = vi.fn(async (url: RequestInfo | URL) => {
        const href = String(url);
        if (href.includes("/reject")) return reject();
        const body = href.includes("/leases/my-leases")
            ? [lease]
            : href.includes("/meetings/my")
              ? { content: [], totalElements: 0, totalPages: 0, number: 0, size: 5 }
              : [];
        return { ok: true, status: 200, json: async () => body } as Response;
    }) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

async function rejectContract(locale: "en" | "ar") {
    const m = locale === "en" ? en : ar;
    render(
        <NextIntlClientProvider locale={locale} messages={m}>
            <RenterPortalPage />
        </NextIntlClientProvider>,
    );
    fireEvent.click(await screen.findByText(m.MasterData.rejectLease));
    const confirm = await screen.findAllByText(m.RenterHome.rejectTitle);
    fireEvent.click(confirm[confirm.length - 1]);
}

describe("Renter portal — a failed Reject is shown", () => {
    it("says the contract was not rejected when the server refuses", async () => {
        reject = () => ({ ok: false, status: 409, json: async () => ({}) }) as Response;
        await rejectContract("en");
        expect((await screen.findByRole("alert")).textContent).toBe(en.MasterData.rejectFailed);
    });

    it("says it in Arabic when the request fails outright", async () => {
        reject = () => { throw new TypeError("Failed to fetch"); };
        await rejectContract("ar");
        expect((await screen.findByRole("alert")).textContent).toBe(ar.MasterData.rejectFailed);
    });
});
