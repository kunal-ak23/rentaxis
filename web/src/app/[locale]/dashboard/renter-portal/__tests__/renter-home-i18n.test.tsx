import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import ar from "../../../../../../messages/ar.json";

/**
 * Gap #36: `/ar/dashboard/renter-portal` laid out RTL but kept the next-payment
 * card, the lease badges and tiles and the whole My Meetings card in English.
 * Every string on the page now comes from next-intl, and lease badges go
 * through `Leasing.leaseStatus.*` rather than the raw Java enum.
 */

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

function lease(over: Record<string, unknown>) {
    return {
        id: "l1", unitId: "u1", renterId: "r1", unitIdentifier: "A-102",
        renterName: "Ahmed", startDate: "2025-01-01", endDate: "2025-12-31",
        status: "ACTIVE", rentAmount: 90000, depositAmount: 5000, ejariNumber: "EJ-1",
        paymentTerms: 4, propertyName: "Miftah Residences", hasContract: false,
        ...over,
    };
}

const OVERDUE = {
    id: "c2", leaseId: "l1", installmentNumber: 2, dueDate: "2025-01-01",
    amount: 31500, status: "REGISTERED", mode: "PDC", chequeNumber: "700102",
    bankName: "ENBD", narration: null, propertyName: "Miftah Residences", unitIdentifier: "A-102",
    renterName: "Ahmed", due: true, overdue: true, daysOverdue: 625,
    gracePeriodDays: 5, penaltyOutstanding: 0, payable: 31500, payableOnline: false,
    onlineEnabled: false, penaltyAssessmentId: null, failureReason: null,
    clearedAt: null, statusChangedAt: null,
};

const MEETING = {
    id: "m1", title: null, purpose: "LEASE_RENEWAL", type: "OFFICE_VISIT", status: "REQUESTED",
    slotStart: "2026-10-01T09:00:00Z", slotEnd: "2026-10-01T09:30:00Z",
    hostName: null, propertyName: "Miftah Residences", unitNumber: "A-102",
};

beforeEach(() => {
    global.fetch = vi.fn(async (url: RequestInfo | URL) => {
        const href = String(url);
        const body = href.includes("/leases/my-leases")
            ? [lease({ id: "l1", status: "RENEWED" }), lease({ id: "l2", status: "ACTIVE" })]
            : href.includes("/online-payments/my-payments")
              ? [OVERDUE]
              : href.includes("/meetings/my")
                ? { content: [MEETING], totalElements: 1, totalPages: 1, number: 0, size: 5 }
                : [];
        return { ok: true, status: 200, json: async () => body };
    }) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

describe("Renter home in Arabic (#36)", () => {
    it("renders the payment card, lease badges and tiles and the meetings card from ar.json", async () => {
        render(
            <NextIntlClientProvider locale="ar" messages={ar}>
                <RenterPortalPage />
            </NextIntlClientProvider>,
        );

        expect(await screen.findByText(ar.RenterHome.nextPayment)).toBeInTheDocument();
        expect(screen.getByText(ar.RenterHome.viewAllPayments)).toBeInTheDocument();
        expect(screen.getByText(ar.Leasing.leaseStatus.RENEWED)).toBeInTheDocument();
        expect(screen.getByText(ar.Leasing.leaseStatus.ACTIVE)).toBeInTheDocument();
        expect(screen.getAllByText(ar.RenterHome.rent)).toHaveLength(2);
        expect(screen.getAllByText(ar.RenterHome.ejari)).toHaveLength(2);
        expect(await screen.findByText(ar.RenterHome.myMeetings)).toBeInTheDocument();
        expect(screen.getByText(ar.RenterHome.colPurpose)).toBeInTheDocument();
        expect(screen.getByText(ar.Meetings.leaseRenewal)).toBeInTheDocument();
        expect(screen.getByText(ar.Meetings.status.REQUESTED)).toBeInTheDocument();

        const body = document.body.textContent ?? "";
        for (const english of [
            "Next Payment", "days overdue", "Due in", "Due today", "View all payments",
            "RENEWED", "ACTIVE", "Rent", "Start", "End", "Ejari",
            "My Meetings", "Request Meeting", "Date & Time", "Purpose", "REQUESTED", "LEASE RENEWAL",
        ]) {
            expect(body).not.toContain(english);
        }
    });
});
