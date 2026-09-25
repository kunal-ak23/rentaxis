import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";

import ar from "../../../../messages/ar.json";
import en from "../../../../messages/en.json";

const list = vi.hoisted(() => vi.fn());
vi.mock("@/lib/api/leasing", async (orig) => {
    const real = await orig<typeof import("@/lib/api/leasing")>();
    return { ...real, penaltyApi: { ...real.penaltyApi, list: (q: unknown) => list(q) } };
});

import PenaltyQueue from "../PenaltyQueue";

/** PR #361 R2: the queue lists charges taken by a bad-debt write-off (translated tab). */
describe("PenaltyQueue written-off tab", () => {
    afterEach(() => { cleanup(); vi.clearAllMocks(); });

    it("asks the server for WRITTEN_OFF charges", async () => {
        list.mockResolvedValue({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 200 });
        render(
            <NextIntlClientProvider locale="ar" messages={ar}>
                <PenaltyQueue userRole="TENANT_ADMIN" />
            </NextIntlClientProvider>,
        );
        const tab = await screen.findByTestId("penalty-tab-WRITTEN_OFF");
        expect(tab.textContent).toBe("مشطوب");
        fireEvent.click(tab);
        await waitFor(() => expect(list).toHaveBeenCalledWith(expect.objectContaining({ status: "WRITTEN_OFF" })));
    });

    it("shows a proposed recharge's VAT, and net / VAT / gross in the approve dialog (F15-18)", async () => {
        const row = { id: "p1", leaseId: "L", chequeId: null, chequeNumber: null, renterId: "r", renterName: "Mona",
            propertyId: "x", propertyName: "P", reason: "SERVICE_RECHARGE", amount: 400, description: "x", status: "PROPOSED",
            proposedBy: null, proposedAt: "2026-09-20T10:00:00Z", approvedBy: null, approvedAt: null, journalId: null,
            collectionChequeId: null, collectionStatus: null, resolutionNote: null, vatable: true, vatAmount: 0, expectedVat: 20 };
        list.mockResolvedValue({ content: [row], totalElements: 1, totalPages: 1, number: 0, size: 200 });
        render(
            <NextIntlClientProvider locale="en" messages={en}>
                <PenaltyQueue userRole="TENANT_ADMIN" />
            </NextIntlClientProvider>,
        );
        expect((await screen.findByTestId("penalty-vat-0")).textContent).toContain("20.00");
        fireEvent.click(screen.getByText("Approve"));
        const breakdown = await screen.findByTestId("penalty-approve-breakdown");
        expect(breakdown.textContent).toContain("400.00");
        expect(breakdown.textContent).toContain("20.00");
        expect(breakdown.textContent).toContain("420.00");
    });
});
