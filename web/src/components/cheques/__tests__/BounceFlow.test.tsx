import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";
import type { Cheque } from "@/lib/api/leasing";

/** Scale spec #18: bounce → replace → bounce fee in one flow, over the existing endpoints. */

vi.mock("@/components/finance/SettlementAccountPicker", () => ({ default: () => <div /> }));
const api = vi.hoisted(() => ({ bounce: vi.fn(), replace: vi.fn(), list: vi.fn(), propose: vi.fn() }));
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return { ...m,
        chequeApi: { ...m.chequeApi, bounce: api.bounce, replace: api.replace },
        penaltyApi: { ...m.penaltyApi, list: api.list, propose: api.propose } };
});
import BounceFlow from "../BounceFlow";

const CHEQUE = {
    id: "c1", leaseId: "l1", propertyId: "p1", unitId: "u1", renterId: "r1", propertyName: "P", unitIdentifier: "A-101", renterName: "R",
    seqNo: 2, postingDate: "2026-06-01", chequeNumber: "000102", chequeDate: "2026-06-01", payeeBank: "ENBD", payerName: null,
    debitAccountId: "acc-1", debitAccountName: "Bank", amount: 13700, narration: null, mode: "PDC", status: "DEPOSITED",
    failureReason: null, replacesId: null, replacedById: null, imageUrl: null, depositedAt: "2026-06-02", clearedAt: null, bouncedAt: null,
    returnedAt: null, pdrJournalId: null, crtJournalId: null, cbrJournalId: null, penaltyAssessmentId: null,
    due: false, overdue: false, daysOverdue: 0, ledgerSettled: false,
} as Cheque;
const page = (content: unknown[]) => ({ content, totalElements: content.length, totalPages: 1, number: 0, size: 50 });

function renderFlow(canProposeFee = true) {
    const onChanged = vi.fn(), onClose = vi.fn();
    render(<NextIntlClientProvider locale="en" messages={en}><BounceFlow cheque={CHEQUE} canProposeFee={canProposeFee} onClose={onClose} onChanged={onChanged} /></NextIntlClientProvider>);
    return { onChanged, onClose };
}
async function bounce() {
    fireEvent.click(screen.getByTestId("cheque-bounce-confirm"));
    return screen.findByTestId("bounce-flow");
}
afterEach(() => { cleanup(); vi.clearAllMocks(); });

describe("BounceFlow", () => {
    it("bounces, then replaces and proposes the fee without leaving the flow", async () => {
        api.bounce.mockResolvedValue(CHEQUE);
        api.list.mockResolvedValue(page([]));
        api.replace.mockResolvedValue([]);
        api.propose.mockResolvedValue({});
        const { onChanged, onClose } = renderFlow();
        await bounce();
        expect(api.bounce).toHaveBeenCalledWith("c1", expect.objectContaining({ failureReason: "BOUNCE" }));
        expect(onChanged).toHaveBeenCalledTimes(1);

        fireEvent.click(screen.getByTestId("bounce-flow-replace"));
        fireEvent.click(await screen.findByTestId("replace-confirm"));
        expect(await screen.findByTestId("bounce-flow-replaced")).toBeInTheDocument();
        expect(api.replace).toHaveBeenCalledWith("c1", expect.objectContaining({ replacements: [expect.objectContaining({ amount: 13700 })] }));

        fireEvent.change(await screen.findByTestId("bounce-flow-fee-amount"), { target: { value: "500" } });
        fireEvent.click(screen.getByTestId("bounce-flow-propose-fee"));
        expect(await screen.findByTestId("bounce-flow-fee-proposed")).toHaveTextContent("500.00");
        // The fee carries the bounce date, not "today" by default.
        expect(api.propose).toHaveBeenCalledWith({ leaseId: "l1", chequeId: "c1", reason: "CHEQUE_RETURN", amount: 500, incidentDate: expect.stringMatching(/^\d{4}-\d{2}-\d{2}$/) });
        expect(api.propose.mock.calls[0][0].incidentDate).toBe((api.bounce.mock.calls[0][1] as { date: string }).date);
        expect(onChanged).toHaveBeenCalledTimes(3);

        fireEvent.click(screen.getByTestId("bounce-flow-done"));
        expect(onClose).toHaveBeenCalled();
    });

    it.each(["PROPOSED", "APPROVED", "WRITTEN_OFF"])("does not offer a second fee when the cheque already has a %s cheque-return charge", async status => {
        api.bounce.mockResolvedValue(CHEQUE);
        api.list.mockImplementation(async (q: { status: string }) => page(q.status === status
            ? [{ id: "pen", chequeId: "c1", reason: "CHEQUE_RETURN", amount: 250, status }] : []));
        renderFlow();
        await bounce();
        expect(await screen.findByTestId("bounce-flow-auto-penalty")).toHaveTextContent("250.00");
        expect(screen.queryByTestId("bounce-flow-propose-fee")).toBeNull();
        for (const s of ["PROPOSED", "APPROVED", "WRITTEN_OFF"]) expect(api.list).toHaveBeenCalledWith({ leaseId: "l1", status: s, page: 0, size: 200 });
    });

    it("still offers a fee when only a waived or reversed charge exists", async () => {
        api.bounce.mockResolvedValue(CHEQUE);
        api.list.mockResolvedValue(page([]));
        renderFlow();
        await bounce();
        expect(await screen.findByTestId("bounce-flow-propose-fee")).toBeInTheDocument();
        expect(api.list).not.toHaveBeenCalledWith(expect.objectContaining({ status: "WAIVED" }));
    });

    it("offers no fee when the check for an existing one fails", async () => {
        api.bounce.mockResolvedValue(CHEQUE);
        api.list.mockRejectedValue(new Error("down"));
        renderFlow();
        await bounce();
        expect(await screen.findByTestId("bounce-flow-check-failed")).toBeInTheDocument();
        expect(screen.queryByTestId("bounce-flow-propose-fee")).toBeNull();
    });

    it("offers no fee to a role that may not propose one", async () => {
        api.bounce.mockResolvedValue(CHEQUE);
        api.list.mockResolvedValue(page([]));
        renderFlow(false);
        await bounce();
        await waitFor(() => expect(api.list).toHaveBeenCalled());
        expect(screen.queryByTestId("bounce-flow-propose-fee")).toBeNull();
        expect(screen.getByTestId("bounce-flow-replace")).toBeInTheDocument();
    });

    it("stays on the bounce step when the bounce is refused", async () => {
        api.bounce.mockRejectedValue(new (await import("@/lib/api/leasing")).ApiError(400, "Only a deposited cheque can bounce."));
        const { onChanged } = renderFlow();
        fireEvent.click(screen.getByTestId("cheque-bounce-confirm"));
        expect(await screen.findByTestId("bounce-error")).toHaveTextContent("Only a deposited cheque can bounce.");
        expect(screen.queryByTestId("bounce-flow")).toBeNull();
        expect(onChanged).not.toHaveBeenCalled();
    });
});
