import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";
import type { PenaltyAssessment } from "@/lib/api/leasing";
import type { UserRole } from "@/lib/rbac";
import { todayIso } from "@/components/leases/leaseMath";

/**
 * Proposing a fine and deciding one are different permissions, and the screen
 * has to draw the line where PenaltyAssessmentController does.
 *
 * This replaces the old `waive-role-gate` test on the lease detail page, which
 * covered the v1 `/penalties/{id}/waive` shape hanging off a payment-schedule
 * row. The rule it protected is the same and still worth pinning: a property
 * manager may say a renter should be fined, but turning that into a charge on
 * the ledger writes a journal, so Approve, Waive and Reverse are finance's.
 */

const api = vi.hoisted(() => ({ list: vi.fn(), propose: vi.fn(), approve: vi.fn(), waive: vi.fn(), reverse: vi.fn() }));

vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return { ...m, penaltyApi: { ...m.penaltyApi, ...api } };
});

import LeasePenaltiesTab from "../LeasePenaltiesTab";

function assessment(over: Partial<PenaltyAssessment> = {}): PenaltyAssessment {
    return {
        id: "pen-1", leaseId: "lease-1", chequeId: "c1", chequeNumber: "000101",
        renterId: "r1", renterName: "Prabhjot Singh", propertyId: "p1", propertyName: "L'Olivier",
        reason: "CHEQUE_RETURN", amount: 500, description: "Cheque returned unpaid",
        status: "PROPOSED", proposedBy: "system", proposedAt: "2026-02-02", approvedBy: null,
        approvedAt: null, journalId: null, collectionChequeId: null, collectionStatus: null,
        resolutionNote: null,
        ...over,
    };
}

function renderTab(role: UserRole) {
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <LeasePenaltiesTab leaseId="lease-1" userRole={role} />
        </NextIntlClientProvider>,
    );
}

beforeEach(() => {
    api.list.mockImplementation(async () => ({
        content: [assessment()], totalElements: 1, totalPages: 1, number: 0, size: 25,
    }));
});

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

describe("LeasePenaltiesTab role gating", () => {
    it("offers Approve and Waive to an ACCOUNTANT", async () => {
        renderTab("ACCOUNTANT");
        expect(await screen.findByTestId("penalty-approve-0")).toBeInTheDocument();
        expect(screen.getByTestId("penalty-waive-0")).toBeInTheDocument();
    });

    it("lets a PROPERTY_MANAGER propose but not decide", async () => {
        renderTab("PROPERTY_MANAGER");
        await waitFor(() => expect(screen.getByTestId("penalty-row-0")).toBeInTheDocument());
        expect(screen.getByTestId("penalty-propose-open")).toBeInTheDocument();
        expect(screen.queryByTestId("penalty-approve-0")).not.toBeInTheDocument();
        expect(screen.queryByTestId("penalty-waive-0")).not.toBeInTheDocument();
    });

    it("offers Reverse, not Approve, once the fine is on the ledger", async () => {
        api.list.mockImplementation(async () => ({
            content: [assessment({ status: "APPROVED", journalId: "j9" })],
            totalElements: 1, totalPages: 1, number: 0, size: 25,
        }));
        renderTab("TENANT_ADMIN");
        expect(await screen.findByTestId("penalty-reverse-0")).toBeInTheDocument();
        expect(screen.queryByTestId("penalty-approve-0")).not.toBeInTheDocument();
    });

    it("opens a date-picker confirm before calling approve, and surfaces the backend's own message when it is refused", async () => {
        const { ApiError } = await import("@/lib/api/leasing");
        api.approve.mockRejectedValue(new ApiError(409, "This penalty has already been waived."));
        renderTab("ACCOUNTANT");
        (await screen.findByTestId("penalty-approve-0")).click();

        // Approve is gated behind a confirm dialog carrying a date picker —
        // the row action alone must not call the API.
        expect(await screen.findByTestId("penalty-decision-date")).toBeInTheDocument();
        expect(api.approve).not.toHaveBeenCalled();

        screen.getByTestId("penalty-approve-confirm").click();
        // The exact date, not expect.any(String): decisionDate and decisionNote
        // are both strings, and decisionNote defaults to "" — which is itself a
        // String — so a swap that wired the note in place of the date would still
        // satisfy expect.any(String) here.
        await waitFor(() => expect(api.approve).toHaveBeenCalledWith("pen-1", todayIso()));
        await waitFor(() =>
            expect(screen.getByTestId("penalty-error")).toHaveTextContent("This penalty has already been waived."),
        );
    });
});
