import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";

import en from "../../../../messages/en.json";

const leaseApi = vi.hoisted(() => ({ dryRunPost: vi.fn(), post: vi.fn() }));
vi.mock("@/lib/api/leasing", async (orig) => {
    const real = await orig<typeof import("@/lib/api/leasing")>();
    return { ...real, leaseApi: { ...real.leaseApi, ...leaseApi } };
});

import PostLeaseDialog from "../PostLeaseDialog";
import type { LeaseDetail } from "@/lib/api/leasing";

const open = () => render(
    <NextIntlClientProvider locale="en" messages={en}>
        <PostLeaseDialog open lease={{ id: "L", displayContractNumber: null } as unknown as LeaseDetail}
                         onClose={() => {}} onPosted={() => {}} />
    </NextIntlClientProvider>,
);

/** QA-16: the post review shows the refundable deposit on its own, outside the contract value. */
describe("PostLeaseDialog deposit", () => {
    afterEach(() => { cleanup(); vi.clearAllMocks(); });

    it("shows the deposit as held, next to a contract value without it", async () => {
        leaseApi.dryRunPost.mockResolvedValue({
            ok: true, errors: [], contractValue: 85000, contractValueInclVat: 85000, chequeTotal: 89250,
            depositTotal: 4250, depositCarriedForward: 0, journals: { tco: 1, tcoLines: 2, pdr: 4 },
        });
        open();
        const deposit = await screen.findByTestId("post-deposit-total");
        expect(deposit.textContent).toContain("4,250.00");
        expect(screen.getByText("Security deposit (held, not income)")).toBeTruthy();
        expect(screen.getByTestId("post-cheque-total").textContent).toContain("89,250.00");
    });

    it("has no deposit row without a deposit", async () => {
        leaseApi.dryRunPost.mockResolvedValue({
            ok: true, errors: [], contractValue: 60000, contractValueInclVat: 60000, chequeTotal: 60000,
            depositTotal: 0, depositCarriedForward: 0, journals: { tco: 1, tcoLines: 1, pdr: 4 },
        });
        open();
        await screen.findByTestId("post-cheque-total");
        expect(screen.queryByTestId("post-deposit-total")).toBeNull();
    });
});
