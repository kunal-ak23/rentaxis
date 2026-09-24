import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../messages/en.json";
import ar from "../../../../../../messages/ar.json";

// #79: once the renter has accepted a PENDING_SIGNATURE contract, the card says
// so and stops offering Accept / Reject — the server refuses a reject after an
// acceptance anyway, and a second Accept would only move the date.

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

describe("Renter portal — accepted contract", () => {
    it("offers Accept and Reject before acceptance", async () => {
        leases = [{ ...base, renterAcceptedAt: null }];
        renderPage();
        expect(await screen.findByText(en.MasterData.acceptLease)).toBeTruthy();
        expect(screen.getByText(en.MasterData.rejectLease)).toBeTruthy();
        expect(screen.queryByTestId("lease-accepted")).toBeNull();
    });

    it("shows 'Accepted on … — awaiting landlord' and no buttons once accepted", async () => {
        leases = [{ ...base, renterAcceptedAt: "2026-09-20T08:00:00Z" }];
        renderPage();
        const note = await screen.findByTestId("lease-accepted");
        expect(note.textContent).toBe("Accepted on 20/09/2026 — awaiting landlord");
        expect(screen.queryByText(en.MasterData.acceptLease)).toBeNull();
        expect(screen.queryByText(en.MasterData.rejectLease)).toBeNull();
    });

    it("says it in Arabic too", async () => {
        leases = [{ ...base, renterAcceptedAt: "2026-09-20T08:00:00Z" }];
        renderPage("ar");
        const note = await screen.findByTestId("lease-accepted");
        expect(note.textContent).toContain("تم القبول في");
        expect(note.textContent).toContain("بانتظار المالك");
    });

    // PR #344 review M5: accept names the contract version on screen, so a page
    // opened before the landlord regenerated the contract cannot accept it.
    it("accepts the contract version it is showing", async () => {
        leases = [{ ...base, renterAcceptedAt: null, contractDocumentId: "doc-7" }];
        renderPage();
        fireEvent.click(await screen.findByText(en.MasterData.acceptLease));
        const confirm = await screen.findAllByText(en.RenterHome.acceptTitle);
        fireEvent.click(confirm[confirm.length - 1]);
        await waitFor(() => expect(vi.mocked(global.fetch)).toHaveBeenCalledWith(
            "/api/proxy/v1/leases/l1/accept?documentId=doc-7", { method: "PUT" }));
    });

    it("says so when the server refuses a stale version", async () => {
        acceptOk = false;
        leases = [{ ...base, renterAcceptedAt: null, contractDocumentId: "doc-old" }];
        renderPage();
        fireEvent.click(await screen.findByText(en.MasterData.acceptLease));
        const confirm = await screen.findAllByText(en.RenterHome.acceptTitle);
        fireEvent.click(confirm[confirm.length - 1]);
        expect((await screen.findByRole("alert")).textContent).toBe(en.MasterData.acceptFailed);
    });
});
