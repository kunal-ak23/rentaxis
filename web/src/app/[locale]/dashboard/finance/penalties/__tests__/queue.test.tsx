import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../messages/en.json";
import type { PenaltyAssessment } from "@/lib/api/leasing";
import { todayIso } from "@/components/leases/leaseMath";

/**
 * The finance-wide penalty queue: who may even open it, and — inside it —
 * who may turn a proposal into a decision. Mirrors
 * `PenaltyAssessmentController`: list/propose admit SA/TA/ACCOUNTANT/PM,
 * approve/waive/reverse admit only SA/TA/ACCOUNTANT.
 */

const api = vi.hoisted(() => ({ list: vi.fn(), approve: vi.fn(), waive: vi.fn(), reduce: vi.fn(), reverse: vi.fn() }));

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

    it("shows the proposer's own justification to the person deciding (#265)", async () => {
        // `LeasePenaltiesTab` asks the proposer for this and posts it as
        // `description`; the approver is the only one who acts on the row.
        renderPage();
        expect(await screen.findByTestId("penalty-description-0")).toHaveTextContent("Cheque returned unpaid");
    });

    it("says nothing where there is no description rather than an empty line", async () => {
        api.list.mockImplementation(async () => ({
            content: [assessment({ description: null })], totalElements: 1, totalPages: 1, number: 0, size: 200,
        }));
        renderPage();
        expect(await screen.findByTestId("penalty-row-0")).toBeInTheDocument();
        expect(screen.queryByTestId("penalty-description-0")).not.toBeInTheDocument();
    });

    it("labels the date column for what it actually shows — the DTO carries proposedBy as a bare id, never rendered", async () => {
        renderPage();
        expect(await screen.findByText("Proposed At")).toBeInTheDocument();
        expect(screen.queryByText(/proposed by/i)).not.toBeInTheDocument();
    });
});

describe("Penalties queue page — decisions", () => {
    it("Approve needs a confirm with a date before it calls the API", async () => {
        renderPage();
        (await screen.findByTestId("penalty-approve-0")).click();
        expect(await screen.findByTestId("penalty-decision-date")).toBeInTheDocument();
        expect(api.approve).not.toHaveBeenCalled();

        screen.getByTestId("penalty-approve-confirm").click();
        // The exact date, not expect.any(String): decisionDate and decisionNote
        // are both strings, and decisionNote defaults to "" — which is itself a
        // String — so a swap that wired the note in place of the date would still
        // satisfy expect.any(String) here.
        await waitFor(() => expect(api.approve).toHaveBeenCalledWith("pen-1", todayIso()));
    });

    it("Waive needs a confirm with a note before it calls the API", async () => {
        renderPage();
        (await screen.findByTestId("penalty-waive-0")).click();
        const note = await screen.findByTestId("penalty-decision-note");
        expect(note).toBeInTheDocument();
        // No date field on Waive — only Approve and Reverse carry one.
        expect(screen.queryByTestId("penalty-decision-date")).not.toBeInTheDocument();

        // PenaltyAssessmentService#waive 400s a blank note, so the confirm stays
        // disabled until one is typed (see the dedicated disabled-state test below).
        fireEvent.change(note, { target: { value: "Renter disputed in good faith" } });
        screen.getByTestId("penalty-waive-confirm").click();
        await waitFor(() => expect(api.waive).toHaveBeenCalledWith("pen-1", "Renter disputed in good faith"));
    });

    it("keeps the Waive confirm disabled until a note is typed — the server 400s a blank one", async () => {
        renderPage();
        (await screen.findByTestId("penalty-waive-0")).click();
        const confirm = await screen.findByTestId("penalty-waive-confirm");
        const note = screen.getByTestId("penalty-decision-note");

        expect(confirm).toBeDisabled();

        fireEvent.change(note, { target: { value: "   " } });
        expect(confirm).toBeDisabled();

        fireEvent.change(note, { target: { value: "Renter disputed in good faith" } });
        expect(confirm).not.toBeDisabled();

        confirm.click();
        await waitFor(() => expect(api.waive).toHaveBeenCalledWith("pen-1", "Renter disputed in good faith"));
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
        const note = await screen.findByTestId("penalty-decision-note");

        // F14-28: the server now requires a note on Reverse — the confirm
        // stays disabled until one is typed, same as Waive.
        expect(screen.getByTestId("penalty-reverse-confirm")).toBeDisabled();
        fireEvent.change(note, { target: { value: "Posted against the wrong cheque" } });
        expect(screen.getByTestId("penalty-reverse-confirm")).not.toBeDisabled();

        screen.getByTestId("penalty-reverse-confirm").click();
        await waitFor(() =>
            expect(api.reverse).toHaveBeenCalledWith("pen-1", { date: expect.any(String), note: "Posted against the wrong cheque" }),
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

describe("Penalties queue page — reduce (F14-28)", () => {
    it("offers Reduce beside Waive on a PROPOSED row, requires 0 < amount < current and a reason", async () => {
        renderPage();
        (await screen.findByTestId("penalty-reduce-0")).click();
        const confirm = await screen.findByTestId("penalty-reduce-confirm");
        const amount = screen.getByTestId("penalty-reduce-amount");
        const note = screen.getByTestId("penalty-decision-note");
        expect(confirm).toBeDisabled();

        // Not less than the current amount (500): refused client-side too.
        fireEvent.change(amount, { target: { value: "500" } });
        fireEvent.change(note, { target: { value: "Renter partly disputed" } });
        expect(confirm).toBeDisabled();
        expect(screen.getByTestId("penalty-reduce-amount-error")).toBeInTheDocument();

        fireEvent.change(amount, { target: { value: "300" } });
        expect(confirm).not.toBeDisabled();

        confirm.click();
        await waitFor(() => expect(api.reduce).toHaveBeenCalledWith("pen-1", 300, "Renter partly disputed"));
    });

    it("never offers Reduce on an APPROVED row", async () => {
        api.list.mockImplementation(async () => ({
            content: [assessment({ status: "APPROVED", journalId: "j9" })],
            totalElements: 1, totalPages: 1, number: 0, size: 200,
        }));
        renderPage();
        expect(await screen.findByTestId("penalty-reverse-0")).toBeInTheDocument();
        expect(screen.queryByTestId("penalty-reduce-0")).not.toBeInTheDocument();
    });

    it("shows 'reduced from' once a row carries a proposedAmount", async () => {
        api.list.mockImplementation(async () => ({
            content: [assessment({ amount: 300, proposedAmount: 500 })],
            totalElements: 1, totalPages: 1, number: 0, size: 200,
        }));
        renderPage();
        expect(await screen.findByTestId("penalty-reduced-from-0")).toHaveTextContent("reduced from 500.00");
    });
});

describe("Penalties queue page — server-generated description (F14-31)", () => {
    it("renders a chequeReturned code through the failure-reason labels, not raw text", async () => {
        api.list.mockImplementation(async () => ({
            content: [assessment({
                description: null,
                descriptionCode: "chequeReturned",
                descriptionArgs: { cheque: "000452", failureReason: "SIGNATURE_MISMATCH", bounces: "2" },
            })],
            totalElements: 1, totalPages: 1, number: 0, size: 200,
        }));
        renderPage();
        expect(await screen.findByTestId("penalty-description-0")).toHaveTextContent(
            "Cheque 000452 returned (Signature Mismatch) — bounce 2 on this contract",
        );
    });

    it("renders a clearedLate code with its dates", async () => {
        api.list.mockImplementation(async () => ({
            content: [assessment({
                description: null,
                descriptionCode: "clearedLate",
                descriptionArgs: { cheque: "000452", days: "4", due: "01/09/2026", cleared: "05/09/2026" },
            })],
            totalElements: 1, totalPages: 1, number: 0, size: 200,
        }));
        renderPage();
        expect(await screen.findByTestId("penalty-description-0")).toHaveTextContent(
            "Cheque 000452 cleared 4 day(s) late (due 01/09/2026, cleared 05/09/2026)",
        );
    });

    it("falls back to the free-text description when there is no code", async () => {
        renderPage();
        expect(await screen.findByTestId("penalty-description-0")).toHaveTextContent("Cheque returned unpaid");
    });
});
