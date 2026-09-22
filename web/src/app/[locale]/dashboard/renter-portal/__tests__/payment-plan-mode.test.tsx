import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../messages/en.json";

/**
 * The renter's payment plan reads `/online-payments/my-payments`, whose rows
 * are `RenterChequeDTO`. That DTO has no `paymentMethod`, so the Method column
 * was `undefined || 'CHEQUE'` — a hardcoded, untranslated literal on 100% of
 * rows, bank transfers and online ones included. The field that exists is
 * `mode`, and it has translations in both languages.
 */

vi.mock("next-auth/react", () => ({
    useSession: () => ({ data: { user: { name: "Prabhjot Singh", role: "RENTER" } } }),
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

const LEASE = {
    id: "lease-1", unitId: "u1", renterId: "r1", unitIdentifier: "A-101",
    renterName: "Prabhjot Singh", startDate: "2026-01-01", endDate: "2026-12-31",
    status: "PENDING_SIGNATURE", rentAmount: 60000, depositAmount: 5000, ejariNumber: "E-1",
    paymentTerms: 3, propertyName: "L'Olivier", hasContract: true,
};

function payment(over: Record<string, unknown>) {
    return {
        id: "c1", leaseId: "lease-1", installmentNumber: 1, dueDate: "2026-03-01",
        amount: 21000, status: "REGISTERED", mode: "PDC", chequeNumber: "000101",
        bankName: "ENBD", narration: null, propertyName: "L'Olivier", unitIdentifier: "A-101",
        renterName: "Prabhjot Singh", due: false, overdue: false, daysOverdue: 0,
        gracePeriodDays: 5, penaltyOutstanding: 0, payable: 21000, payableOnline: false,
        onlineEnabled: false, penaltyAssessmentId: null, failureReason: null,
        clearedAt: null, statusChangedAt: null,
        ...over,
    };
}

const PAYMENTS = [
    payment({ id: "c1", installmentNumber: 1, mode: "PDC" }),
    payment({ id: "c2", installmentNumber: 2, mode: "TRANSFER", dueDate: "2026-06-01" }),
    payment({ id: "c3", installmentNumber: 3, mode: "ONLINE", dueDate: "2026-09-01" }),
];

beforeEach(() => {
    global.fetch = vi.fn(async (url: RequestInfo | URL) => {
        const href = String(url);
        const body = href.includes("/leases/my-leases")
            ? [LEASE]
            : href.includes("/online-payments/my-payments")
              ? PAYMENTS
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

describe("Renter payment plan — method column", () => {
    it("renders each row's own translated mode, not a hardcoded CHEQUE", async () => {
        render(
            <NextIntlClientProvider locale="en" messages={en}>
                <RenterPortalPage />
            </NextIntlClientProvider>,
        );

        // The plan is the card a renter reads before signing.
        const toggle = await screen.findByText(/Payment Plan/i);
        fireEvent.click(toggle);

        await waitFor(() => expect(screen.getByText("Post-Dated Cheque")).toBeInTheDocument());
        expect(screen.getByText("Bank Transfer")).toBeInTheDocument();
        expect(screen.getByText("Online")).toBeInTheDocument();
        expect(screen.queryByText("CHEQUE")).not.toBeInTheDocument();
    });
});
