import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../messages/en.json";
import type { PenaltyAssessment } from "@/lib/api/leasing";

/**
 * The renter's penalties screen reads `/penalties/mine`
 * (`PenaltyAssessmentController#mine`), never the finance `/penalties` list —
 * that endpoint is APPROVED-and-own-renter scoped server-side, and this
 * screen carries no status filter or decision controls of its own.
 */

const api = vi.hoisted(() => ({ mine: vi.fn(), list: vi.fn() }));
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return { ...m, penaltyApi: { ...m.penaltyApi, ...api } };
});

import RenterPenaltiesPage from "../page";

function assessment(over: Partial<PenaltyAssessment> = {}): PenaltyAssessment {
    return {
        id: "pen-1", leaseId: "lease-1", chequeId: "c1", chequeNumber: "000101",
        renterId: "r1", renterName: "Tenant", propertyId: "p1", propertyName: "L'Olivier",
        reason: "LATE_PAYMENT", amount: 250, description: "Rent cleared 12 days late",
        status: "APPROVED", proposedBy: "system", proposedAt: "2026-02-01",
        approvedBy: "u1", approvedAt: "2026-02-03", journalId: "j1",
        collectionChequeId: "c1", collectionStatus: "REGISTERED", resolutionNote: null,
        ...over,
    };
}

function renderPage() {
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <RenterPenaltiesPage />
        </NextIntlClientProvider>,
    );
}

beforeEach(() => {
    api.mine.mockResolvedValue([assessment()]);
});

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

describe("RenterPenaltiesPage", () => {
    it("reads /penalties/mine, never the finance list endpoint", async () => {
        renderPage();
        await waitFor(() => expect(api.mine).toHaveBeenCalled());
        expect(api.list).not.toHaveBeenCalled();
    });

    it("renders the approved fine's reason, amount and cheque", async () => {
        renderPage();
        const row = await screen.findByTestId("renter-penalty-row-0");
        expect(row).toHaveTextContent("Late Payment");
        expect(row).toHaveTextContent("250.00");
        expect(row).toHaveTextContent("000101");
    });

    it("shows the empty state when there are no approved penalties", async () => {
        api.mine.mockResolvedValue([]);
        renderPage();
        expect(await screen.findByText(/No approved penalties/i)).toBeInTheDocument();
    });

    it("surfaces a load failure", async () => {
        const { ApiError } = await import("@/lib/api/leasing");
        api.mine.mockRejectedValue(new ApiError(500, "boom"));
        renderPage();
        expect(await screen.findByText("boom")).toBeInTheDocument();
    });
});
