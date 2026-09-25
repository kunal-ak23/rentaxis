import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, within } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";

import en from "../../../../messages/en.json";

const leaseApi = vi.hoisted(() => ({ dryRunPost: vi.fn(), post: vi.fn() }));
vi.mock("@/lib/api/leasing", async (orig) => {
    const real = await orig<typeof import("@/lib/api/leasing")>();
    return { ...real, leaseApi: { ...real.leaseApi, ...leaseApi } };
});

import PostLeaseDialog from "../PostLeaseDialog";
import type { LeaseDetail } from "@/lib/api/leasing";

/** F15-13: a transfer's review lists the carried cheques and their total, and posting is allowed. */
describe("PostLeaseDialog carried cheques", () => {
    afterEach(() => { cleanup(); vi.clearAllMocks(); });

    it("lists the carried cheques with their total", async () => {
        leaseApi.dryRunPost.mockResolvedValue({
            ok: true, errors: [], contractValue: 37808.22, contractValueInclVat: 37808.22, chequeTotal: 0,
            depositCarriedForward: 500, journals: { tco: 1, tcoLines: 2, pdr: 2 },
            carriedCheques: [
                { seqNo: 3, chequeNumber: "620003", chequeDate: "2026-10-01", amount: 2000 },
                { seqNo: 4, chequeNumber: "620004", chequeDate: "2027-01-01", amount: 2150 },
            ],
        });
        render(
            <NextIntlClientProvider locale="en" messages={en}>
                <PostLeaseDialog open lease={{ id: "B", displayContractNumber: null } as unknown as LeaseDetail}
                                 onClose={() => {}} onPosted={() => {}} />
            </NextIntlClientProvider>,
        );
        const list = await screen.findByTestId("post-carried-cheques");
        expect(within(list).getByText("Cheque 620003")).toBeTruthy();
        expect(screen.getByTestId("post-carried-total").textContent).toContain("4,150.00");
        expect((screen.getByTestId("post-lease-confirm") as HTMLButtonElement).disabled).toBe(false);
    });
});
