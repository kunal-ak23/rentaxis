import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../messages/en.json";
import ar from "../../../../../../messages/ar.json";

// Break-it R2 re-review N2: a renewal awaiting the renter's signature is withdrawn
// when the lease it renews is terminated. Accepting it then answers 404; the portal
// must say the contract is no longer available, not "review the current contract
// and try again" (acceptFailed), which sends the renter after one that is gone.

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
let accept: () => Response;

beforeEach(() => {
    global.fetch = vi.fn(async (url: RequestInfo | URL) => {
        const href = String(url);
        if (href.includes("/accept")) return accept();
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

async function acceptContract(locale: "en" | "ar") {
    const m = locale === "en" ? en : ar;
    render(
        <NextIntlClientProvider locale={locale} messages={m}>
            <RenterPortalPage />
        </NextIntlClientProvider>,
    );
    fireEvent.click(await screen.findByText(m.MasterData.acceptLease));
    const confirm = await screen.findAllByText(m.RenterHome.acceptTitle);
    fireEvent.click(confirm[confirm.length - 1]);
}

describe("Renter portal — accepting a withdrawn contract", () => {
    it("says the contract is no longer available on a 404", async () => {
        accept = () => ({ ok: false, status: 404, json: async () => ({}) }) as Response;
        await acceptContract("en");
        expect((await screen.findByRole("alert")).textContent).toBe(en.MasterData.acceptUnavailable);
    });

    it("in Arabic too", async () => {
        accept = () => ({ ok: false, status: 404, json: async () => ({}) }) as Response;
        await acceptContract("ar");
        expect((await screen.findByRole("alert")).textContent).toBe(ar.MasterData.acceptUnavailable);
    });

    it("any other refusal still says acceptFailed", async () => {
        accept = () => ({ ok: false, status: 400, json: async () => ({}) }) as Response;
        await acceptContract("en");
        expect((await screen.findByRole("alert")).textContent).toBe(en.MasterData.acceptFailed);
    });
});
