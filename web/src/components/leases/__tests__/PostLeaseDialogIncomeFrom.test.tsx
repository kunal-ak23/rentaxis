import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";

import en from "../../../../messages/en.json";

const leaseApi = vi.hoisted(() => ({ dryRunPost: vi.fn(), post: vi.fn(), incomeFrom: vi.fn() }));
vi.mock("@/lib/api/leasing", async (orig) => {
    const real = await orig<typeof import("@/lib/api/leasing")>();
    return { ...real, leaseApi: { ...real.leaseApi, ...leaseApi } };
});

import PostLeaseDialog, { isBackdated } from "../PostLeaseDialog";
import type { LeaseDetail } from "@/lib/api/leasing";

const DRY = {
    ok: true, errors: [], contractValue: 60000, contractValueInclVat: 60000, chequeTotal: 60000,
    depositCarriedForward: 0, journals: { tco: 1, tcoLines: 2, pdr: 4 }, notices: [],
};

const OPTIONS = {
    backdated: true, earliest: "2026-11-01", latest: "2026-11-01", defaultFrom: "2026-11-01",
    incomeFrom: "2026-11-01", catchUpOn: "2026-11-13", foldedMonths: 1, foldedAmount: 5095.89, monthsNamed: ["10/2026"],
};

function renderDialog(lease: Partial<LeaseDetail>) {
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <PostLeaseDialog open lease={{ id: "R", displayContractNumber: null, version: 3, ...lease } as unknown as LeaseDetail}
                             onClose={() => {}} onPosted={() => {}} />
        </NextIntlClientProvider>,
    );
}

/**
 * Back-dated contracts (client call, Nov 2026): a renewal posted in November for a
 * term that began in October offers "Recognise income from", defaulting to the
 * contract date's month, and posts with it.
 */
describe("PostLeaseDialog income from", () => {
    afterEach(() => { cleanup(); vi.clearAllMocks(); });

    it("knows a back-dated contract from its dates", () => {
        expect(isBackdated({ startDate: "2026-10-01", contractDate: "2026-11-13" })).toBe(true);
        expect(isBackdated({ startDate: "2026-11-01", contractDate: "2026-11-13" })).toBe(false);
        expect(isBackdated({ startDate: "2026-12-01", contractDate: null })).toBe(false);
    });

    it("offers the month, says what folds, and posts with it", async () => {
        leaseApi.dryRunPost.mockResolvedValue(DRY);
        leaseApi.incomeFrom.mockResolvedValue(OPTIONS);
        leaseApi.post.mockResolvedValue({});
        renderDialog({ startDate: "2026-10-01", contractDate: "2026-11-13" });

        expect(await screen.findByTestId("post-income-from-fold")).toHaveTextContent("1 month before this");
        // Every earlier month is in closed books: one entry is the only choice.
        expect(screen.getByTestId("post-income-from-one")).toBeChecked();
        expect(screen.getByTestId("post-income-from-spread")).toBeDisabled();

        fireEvent.click(screen.getByTestId("post-lease-confirm"));
        await waitFor(() => expect(leaseApi.post).toHaveBeenCalledWith("R", 3, "2026-11-01"));
    });

    it("asks nothing for a contract that is not back-dated", async () => {
        leaseApi.dryRunPost.mockResolvedValue(DRY);
        leaseApi.post.mockResolvedValue({});
        renderDialog({ startDate: "2026-11-01", contractDate: "2026-11-13" });

        await screen.findByTestId("post-dry-run-ok");
        expect(screen.queryByTestId("post-income-from")).toBeNull();
        expect(leaseApi.incomeFrom).not.toHaveBeenCalled();
        fireEvent.click(screen.getByTestId("post-lease-confirm"));
        await waitFor(() => expect(leaseApi.post).toHaveBeenCalledWith("R", 3));
    });

    it("defaults to one entry this month, and spreading posts from the earliest open month", async () => {
        leaseApi.dryRunPost.mockResolvedValue(DRY);
        leaseApi.incomeFrom.mockImplementation((_id: string, from: string | null) => Promise.resolve({
            ...OPTIONS, earliest: "2026-08-01", incomeFrom: from ?? "2026-11-01",
            foldedMonths: from === "2026-08-01" ? 0 : 3,
        }));
        leaseApi.post.mockResolvedValue({});
        renderDialog({ startDate: "2026-08-01", contractDate: "2026-11-13" });

        expect(await screen.findByTestId("post-income-from-one")).toBeChecked();
        fireEvent.click(screen.getByTestId("post-income-from-spread"));
        await waitFor(() => expect(screen.getByTestId("post-income-from-spread")).toBeChecked());
        await waitFor(() => expect(screen.getByTestId("post-income-from-fold")).toHaveTextContent("Every month is recognised in its own month"));

        fireEvent.click(screen.getByTestId("post-lease-confirm"));
        await waitFor(() => expect(leaseApi.post).toHaveBeenCalledWith("R", 3, "2026-08-01"));
    });
});
