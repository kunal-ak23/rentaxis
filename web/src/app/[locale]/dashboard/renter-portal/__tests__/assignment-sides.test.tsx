import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../messages/en.json";
import ar from "../../../../../../messages/ar.json";

// PR #359 R1: after an assignment each renter's portal says which side of the
// hand-over they are on, with the opening line for the incoming renter.

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

const base = {
    id: "l1", unitId: "u1", renterId: "r1", renterName: "Ahmed", rentAmount: 60000, depositAmount: 5000,
    ejariNumber: "E-1", paymentTerms: 4, propertyName: "Tower", unitIdentifier: "A-203",
    startDate: "2026-10-01", endDate: "2027-09-30", status: "PENDING_SIGNATURE", hasContract: true,
};
let leases: Array<Record<string, unknown>>;
let acceptOk = true;

beforeEach(() => {
    acceptOk = true;
    global.fetch = vi.fn(async (url: RequestInfo | URL) => {
        const href = String(url);
        if (href.includes("/accept")) {
            return { ok: acceptOk, status: acceptOk ? 200 : 422, json: async () => ({}) };
        }
        const body = href.includes("/leases/my-leases")
            ? leases
            : href.includes("/meetings/my")
              ? { content: [], totalElements: 0, totalPages: 0, number: 0, size: 5 }
              : [];
        return { ok: true, status: 200, json: async () => body };
    }) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

const renderPage = (locale: "en" | "ar" = "en") => render(
    <NextIntlClientProvider locale={locale} messages={locale === "en" ? en : ar}>
        <RenterPortalPage />
    </NextIntlClientProvider>,
);

describe("Renter portal — a lease that changed hands (PR #359 R1)", () => {
    it("shows the incoming renter the opening line, in Arabic with amounts left-to-right", async () => {
        leases = [{ ...base, status: "ACTIVE", assignedToYouOn: "2027-02-01", openingReceivable: -7808.22, openingDeposit: 3000 }];
        renderPage("ar");
        const note = await screen.findByTestId("portal-assigned-l1");
        expect(note.textContent).toContain("2027");
        expect(note.textContent).toContain("لصالحك");
        expect(note.querySelectorAll("bdi[dir='ltr']").length).toBe(2);
    });

    it("tells the outgoing renter where their history ends", async () => {
        leases = [{ ...base, status: "ACTIVE", yourAccessEndedOn: "2027-02-01" }];
        renderPage();
        const note = await screen.findByTestId("portal-handed-over-l1");
        expect(note.textContent).toBe("You handed this contract over on 01/02/2027. You can see its history up to that date.");
    });
});
