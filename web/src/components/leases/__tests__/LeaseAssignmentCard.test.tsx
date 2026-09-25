import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";
import ar from "../../../../messages/ar.json";
import type { LeaseAssignment, LeaseDetail } from "@/lib/api/leasing";
import { ApiError } from "@/lib/api/facilities";

const api = vi.hoisted(() => ({
    assignments: vi.fn(), draftAssignment: vi.fn(), postAssignment: vi.fn(), cancelAssignment: vi.fn(), renterOptions: vi.fn(),
}));
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return { ...m, leaseApi: { ...m.leaseApi, ...api } };
});

import LeaseAssignmentCard from "../LeaseAssignmentCard";

const LEASE = { id: "lease-1", renterId: "a", status: "ACTIVE", postedAt: "2026-09-16T00:00:00Z",
    startDate: "2026-09-24", endDate: "2027-09-23" } as unknown as LeaseDetail;

const DRAFT: LeaseAssignment = {
    id: "as-1", leaseId: "lease-1", fromRenterId: "a", fromRenterName: "Omar", toRenterId: "b", toRenterName: "Omar's estate",
    effectiveDate: "2027-02-01", reason: "Death of the tenant", takeOverOverdue: false, status: "DRAFT",
    journalId: null, journalNumber: null, createdAt: "2027-01-30T00:00:00Z", postedAt: null,
    balances: [
        { accountId: "pdc", accountCode: "100014", accountName: "PDCs", accountNameAr: "شيكات", amount: 12750 },
        { accountId: "dep", accountCode: "100020", accountName: "Security deposits", accountNameAr: "التأمينات", amount: -3000 },
    ],
    overdue: [], chequesMoving: 2,
};

function renderCard(locale: "en" | "ar" = "en", canPost = true) {
    return render(
        <NextIntlClientProvider locale={locale} messages={locale === "en" ? en : ar}>
            <LeaseAssignmentCard lease={LEASE} canDraft canPost={canPost} onChanged={() => {}} />
        </NextIntlClientProvider>,
    );
}

afterEach(() => { cleanup(); vi.clearAllMocks(); });

describe("LeaseAssignmentCard (F14-39)", () => {
    it("drafts an assignment to another renter with a reason", async () => {
        api.assignments.mockResolvedValueOnce([]).mockResolvedValueOnce([DRAFT]);
        api.renterOptions.mockResolvedValue([{ id: "a", nameEn: "Omar" }, { id: "b", nameEn: "Omar's estate" }]);
        api.draftAssignment.mockResolvedValue(DRAFT);
        renderCard();
        fireEvent.click(await screen.findByTestId("assignment-start"));
        const select = await screen.findByTestId("assignment-renter");
        await waitFor(() => expect(select.querySelectorAll("option")).toHaveLength(2)); // the sitting renter is not offered
        fireEvent.change(select, { target: { value: "b" } });
        fireEvent.change(screen.getByTestId("assignment-date"), { target: { value: "2027-02-01" } });
        expect(screen.getByTestId("assignment-draft")).toBeDisabled();
        fireEvent.change(screen.getByTestId("assignment-reason"), { target: { value: "Death of the tenant" } });
        fireEvent.click(screen.getByTestId("assignment-draft"));
        await waitFor(() => expect(api.draftAssignment).toHaveBeenCalledWith("lease-1",
            { toRenterId: "b", effectiveDate: "2027-02-01", reason: "Death of the tenant", takeOverOverdue: false }));
        expect(await screen.findByTestId("assignment-draft-card")).toHaveTextContent("Omar → Omar's estate");
    });

    it("shows what the post moves, in Arabic, and posts for finance only", async () => {
        api.assignments.mockResolvedValue([DRAFT]);
        api.postAssignment.mockResolvedValue({ ...DRAFT, status: "POSTED" });
        renderCard("ar");
        const balances = await screen.findByTestId("assignment-balances");
        expect(balances).toHaveTextContent("مدين");
        expect(balances).toHaveTextContent("دائن");
        expect(balances.querySelector("bdi[dir='ltr']")?.textContent).toBe("12,750.00");
        fireEvent.click(screen.getByTestId("assignment-post"));
        expect(api.postAssignment).not.toHaveBeenCalled();   // confirmed first
        expect(await screen.findByText(ar.Leasing.assignment.confirmPostTitle)).toBeTruthy();
        fireEvent.click(screen.getByTestId("assignment-confirm"));
        await waitFor(() => expect(api.postAssignment).toHaveBeenCalledWith("lease-1", "as-1", false));
        cleanup();
        renderCard("en", false);
        await screen.findByTestId("assignment-draft-card");
        expect(screen.queryByTestId("assignment-post")).toBeNull();
    });

    it("renders the overdue refusal from its code", async () => {
        api.assignments.mockResolvedValue([DRAFT]);
        api.postAssignment.mockRejectedValue(new ApiError(400, "x", JSON.stringify({ code: "lease.assignmentOverdue",
            args: { from: "Omar", to: "Omar's estate", count: 1, amount: "12,750.00" }, message: "x" })));
        renderCard("ar");
        fireEvent.click(await screen.findByTestId("assignment-post"));
        fireEvent.click(await screen.findByTestId("assignment-confirm"));
        expect(await screen.findByTestId("assignment-error")).toHaveTextContent("أكّد أن Omar's estate يتحملها");
    });

    it("deletes a draft only after confirming, and says nothing was posted", async () => {
        api.assignments.mockResolvedValue([DRAFT]);
        api.cancelAssignment.mockResolvedValue(undefined);
        renderCard();
        fireEvent.click(await screen.findByTestId("assignment-delete"));
        expect(api.cancelAssignment).not.toHaveBeenCalled();
        expect(await screen.findByText(en.Leasing.assignment.confirmDelete)).toBeTruthy();
        fireEvent.click(screen.getByTestId("assignment-confirm"));
        await waitFor(() => expect(api.cancelAssignment).toHaveBeenCalledWith("lease-1", "as-1"));
    });
});
