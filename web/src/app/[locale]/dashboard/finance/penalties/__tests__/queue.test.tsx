import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../messages/en.json";
import type { PenaltyAssessment } from "@/lib/api/leasing";

/**
 * The finance-wide penalty queue: who may even open it, and — inside it —
 * who may turn a proposal into a decision. Mirrors
 * `PenaltyAssessmentController`: list/propose admit SA/TA/ACCOUNTANT/PM,
 * approve/waive/reverse admit only SA/TA/ACCOUNTANT.
 */

const api = vi.hoisted(() => ({ list: vi.fn(), approve: vi.fn(), waive: vi.fn(), reverse: vi.fn() }));

vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return { ...m, penaltyApi: { ...m.penaltyApi, ...api } };
});

let role = "ACCOUNTANT";
vi.mock("next-auth/react", () => ({
    useSession: () => ({ data: { user: { role, name: "Tester" } } }),
}));

import PenaltiesQueuePage from "../page";

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

function renderPage() {
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <PenaltiesQueuePage />
        </NextIntlClientProvider>,
    );
}

beforeEach(() => {
    role = "ACCOUNTANT";
    api.list.mockImplementation(async () => ({
        content: [assessment()], totalElements: 1, totalPages: 1, number: 0, size: 200,
    }));
});

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

describe("Penalties queue page — access", () => {
    it("refuses a role PenaltyAssessmentController#list itself refuses", async () => {
        role = "TENANT_USER";
        renderPage();
        expect(await screen.findByText(/permission to view the penalty queue/i)).toBeInTheDocument();
        expect(screen.queryByTestId("penalty-queue")).not.toBeInTheDocument();
    });

    it("opens the queue for a PROPERTY_MANAGER, who may propose but not decide", async () => {
        role = "PROPERTY_MANAGER";
        renderPage();
        expect(await screen.findByTestId("penalty-row-0")).toBeInTheDocument();
        expect(screen.queryByTestId("penalty-approve-0")).not.toBeInTheDocument();
        expect(screen.queryByTestId("penalty-waive-0")).not.toBeInTheDocument();
    });

    it("opens the queue for an ACCOUNTANT with Approve and Waive on a PROPOSED row", async () => {
        renderPage();
        expect(await screen.findByTestId("penalty-approve-0")).toBeInTheDocument();
        expect(screen.getByTestId("penalty-waive-0")).toBeInTheDocument();
    });
});

describe("Penalties queue page — decisions", () => {
    it("Approve needs a confirm with a date before it calls the API", async () => {
        renderPage();
        (await screen.findByTestId("penalty-approve-0")).click();
        expect(await screen.findByTestId("penalty-decision-date")).toBeInTheDocument();
        expect(api.approve).not.toHaveBeenCalled();

        screen.getByTestId("penalty-approve-confirm").click();
        await waitFor(() => expect(api.approve).toHaveBeenCalledWith("pen-1", expect.any(String)));
    });

    it("Waive needs a confirm with a note before it calls the API", async () => {
        renderPage();
        (await screen.findByTestId("penalty-waive-0")).click();
        const note = await screen.findByTestId("penalty-decision-note");
        expect(note).toBeInTheDocument();
        // No date field on Waive — only Approve and Reverse carry one.
        expect(screen.queryByTestId("penalty-decision-date")).not.toBeInTheDocument();

        screen.getByTestId("penalty-waive-confirm").click();
        await waitFor(() => expect(api.waive).toHaveBeenCalledWith("pen-1", undefined));
    });

    it("offers Reverse, not Approve/Waive, on an APPROVED row, gated the same way", async () => {
        api.list.mockImplementation(async () => ({
            content: [assessment({ status: "APPROVED", journalId: "j9" })],
            totalElements: 1, totalPages: 1, number: 0, size: 200,
        }));
        renderPage();
        expect(await screen.findByTestId("penalty-reverse-0")).toBeInTheDocument();
        expect(screen.queryByTestId("penalty-approve-0")).not.toBeInTheDocument();

        screen.getByTestId("penalty-reverse-0").click();
        expect(await screen.findByTestId("penalty-decision-date")).toBeInTheDocument();
        expect(await screen.findByTestId("penalty-decision-note")).toBeInTheDocument();

        screen.getByTestId("penalty-reverse-confirm").click();
        await waitFor(() =>
            expect(api.reverse).toHaveBeenCalledWith("pen-1", { date: expect.any(String), note: undefined }),
        );
    });

    it("surfaces the backend's own message when a decision is refused", async () => {
        const { ApiError } = await import("@/lib/api/leasing");
        api.approve.mockRejectedValue(new ApiError(409, "This penalty has already been waived."));
        renderPage();
        (await screen.findByTestId("penalty-approve-0")).click();
        (await screen.findByTestId("penalty-approve-confirm")).click();
        await waitFor(() =>
            expect(screen.getByTestId("penalty-error")).toHaveTextContent("This penalty has already been waived."),
        );
    });
});
