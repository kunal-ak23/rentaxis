import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// POST /v1/penalties/{id}/waive is SUPER_ADMIN/TENANT_ADMIN only on the
// backend (PenaltyController); the Waive button must be hidden from
// PROPERTY_MANAGER so it never 403s on submit.

const sessionUser = vi.hoisted(() => ({ role: "TENANT_ADMIN" }));

vi.mock("next/navigation", () => ({
    useParams: () => ({ id: "22222222-2222-2222-2222-222222222222" }),
    useSearchParams: () => ({ get: () => null }),
}));
vi.mock("next-auth/react", () => ({
    useSession: () => ({ data: { user: { role: sessionUser.role } } }),
}));
vi.mock("next-intl", () => ({
    useTranslations: () => (key: string) => key,
    useLocale: () => "en",
}));
vi.mock("@/i18n/routing", () => ({
    Link: ({ children }: { children: React.ReactNode }) => <a>{children}</a>,
    useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }),
}));
vi.mock("../../PaymentScheduleEditor", () => ({ default: () => null }));
vi.mock("../../LeaseMetadataEditor", () => ({ default: () => null }));
vi.mock("@/components/payments/MarkChequeFailedDialog", () => ({ MarkChequeFailedDialog: () => null }));
vi.mock("@/components/penalties/RecordPenaltyPaymentDialog", () => ({ RecordPenaltyPaymentDialog: () => null }));
vi.mock("@/components/payments/CollectChequeDialog", () => ({ CollectChequeDialog: () => null }));
vi.mock("@/components/payments/DueDateDelta", () => ({ default: () => null }));
vi.mock("@/components/cheques/BulkChequeUploadFlow", () => ({ default: () => null }));
vi.mock("@/components/leases/LeaseInteractionsPanel", () => ({ default: () => null }));

import LeaseDetailPage from "../page";

const LEASE_ID = "22222222-2222-2222-2222-222222222222";
const PAYMENT_ID = "33333333-3333-3333-3333-333333333333";

const lease = {
    id: LEASE_ID,
    unitId: "44444444-4444-4444-4444-444444444444",
    unitIdentifier: "101",
    renterName: "Renter",
    propertyName: "Tower A",
    startDate: "2026-01-01",
    endDate: "2026-12-31",
    status: "ACTIVE",
    rentAmount: 60000,
    depositAmount: 5000,
    hasContract: false,
};

const payment = {
    id: PAYMENT_ID,
    installmentNumber: 1,
    dueDate: "2026-02-01",
    amount: 5000,
    status: "BOUNCED",
    chequeNumber: "CHQ-1",
    chequeDate: null,
    bankName: "Bank",
    payerName: "Payer",
};

const penalty = {
    id: "55555555-5555-5555-5555-555555555555",
    leaseId: LEASE_ID,
    paymentScheduleId: PAYMENT_ID,
    failureReason: "INSUFFICIENT_FUNDS",
    penaltyType: "BOUNCED_CHEQUE",
    penaltyAmount: 500,
    currentTotal: 500,
    outstanding: 500,
    daysOverdue: 3,
    status: "OPEN",
    waived: false,
    createdAt: "2026-02-02T00:00:00Z",
    payments: [],
};

beforeEach(() => {
    global.fetch = vi.fn(async (url: unknown) => {
        const u = String(url);
        if (u.includes("/v1/payments/lease/")) {
            return { ok: true, json: async () => [payment] } as Response;
        }
        if (u.includes("/attachments")) {
            return { ok: true, json: async () => [] } as Response;
        }
        if (u.includes("/v1/penalties?")) {
            return { ok: true, json: async () => ({ content: [penalty] }) } as Response;
        }
        if (u.includes("/v1/tickets")) {
            return { ok: true, json: async () => [] } as Response;
        }
        if (u.includes(`/v1/leases/${LEASE_ID}`)) {
            return { ok: true, json: async () => lease } as Response;
        }
        return { ok: false, status: 404, json: async () => ({}) } as Response;
    }) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

describe("LeaseDetailPage waive-penalty role gating", () => {
    it("shows the Waive button to TENANT_ADMIN", async () => {
        sessionUser.role = "TENANT_ADMIN";
        render(<LeaseDetailPage />);

        expect(await screen.findByText("Waive")).toBeTruthy();
    });

    it("hides the Waive button from PROPERTY_MANAGER (endpoint would 403)", async () => {
        sessionUser.role = "PROPERTY_MANAGER";
        render(<LeaseDetailPage />);

        // Wait for the payment row (and its penalty cell) to render.
        expect(await screen.findByText("CHQ-1")).toBeTruthy();
        expect(screen.queryByText("Waive")).toBeNull();
    });
});
