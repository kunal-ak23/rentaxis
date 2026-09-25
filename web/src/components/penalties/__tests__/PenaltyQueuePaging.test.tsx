import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";

const list = vi.hoisted(() => vi.fn());
vi.mock("@/lib/api/leasing", async (orig) => {
    const real = await orig<typeof import("@/lib/api/leasing")>();
    return { ...real, penaltyApi: { ...real.penaltyApi, list: (q: unknown) => list(q) } };
});
import PenaltyQueue from "../PenaltyQueue";

const row = (i: number) => ({ id: `p${i}`, leaseId: "L", chequeId: null, chequeNumber: `C${i}`, renterId: "r", renterName: `Renter ${i}`,
    propertyId: "x", propertyName: "P", reason: "LATE_PAYMENT", amount: 100, description: null, status: "PROPOSED",
    proposedBy: null, proposedAt: "2026-09-20T10:00:00Z", approvedBy: null, approvedAt: null, journalId: null,
    collectionChequeId: null, collectionStatus: null, resolutionNote: null, vatable: false, vatAmount: 0, expectedVat: 0 });

/** Scale spec #10: the queue pages on the server instead of silently stopping at 200 rows. */
describe("PenaltyQueue paging", () => {
    afterEach(() => { cleanup(); vi.clearAllMocks(); });

    it("asks for one page, shows the whole backlog's size, and moves between pages", async () => {
        list.mockImplementation((q: { page: number; size: number }) => Promise.resolve({
            content: Array.from({ length: q.page < 11 ? q.size : 1 }, (_, i) => row(q.page * q.size + i)),
            totalElements: 276, totalPages: 12, number: q.page, size: q.size }));
        render(<NextIntlClientProvider locale="en" messages={en}><PenaltyQueue userRole="TENANT_ADMIN" propertyId="x" /></NextIntlClientProvider>);
        await screen.findByText("Renter 0");
        expect(list).toHaveBeenLastCalledWith({ leaseId: undefined, propertyId: "x", status: "PROPOSED", page: 0, size: 25 });
        expect(screen.getByTestId("penalty-pagination").textContent).toContain("276");
        fireEvent.click(screen.getByRole("button", { name: "2" }));
        await screen.findByText("Renter 25");
        expect(list).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1, size: 25 }));
    });

    it("goes back to the first page on another status tab", async () => {
        list.mockResolvedValue({ content: [row(0)], totalElements: 60, totalPages: 3, number: 0, size: 25 });
        render(<NextIntlClientProvider locale="en" messages={en}><PenaltyQueue userRole="TENANT_ADMIN" /></NextIntlClientProvider>);
        await screen.findByText("Renter 0");
        fireEvent.click(screen.getByRole("button", { name: "3" }));
        await waitFor(() => expect(list).toHaveBeenLastCalledWith(expect.objectContaining({ page: 2 })));
        fireEvent.click(screen.getByTestId("penalty-tab-APPROVED"));
        await waitFor(() => expect(list).toHaveBeenLastCalledWith(expect.objectContaining({ status: "APPROVED", page: 0 })));
    });
});
