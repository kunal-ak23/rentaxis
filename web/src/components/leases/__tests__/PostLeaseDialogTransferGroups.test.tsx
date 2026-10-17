import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";

import en from "../../../../messages/en.json";
import ar from "../../../../messages/ar.json";

const leaseApi = vi.hoisted(() => ({ dryRunPost: vi.fn(), post: vi.fn() }));
vi.mock("@/lib/api/leasing", async (orig) => {
    const real = await orig<typeof import("@/lib/api/leasing")>();
    return { ...real, leaseApi: { ...real.leaseApi, ...leaseApi } };
});

import PostLeaseDialog from "../PostLeaseDialog";
import type { LeaseDetail, PostLeaseDryRunResponse } from "@/lib/api/leasing";

const BASE: PostLeaseDryRunResponse = {
    ok: true, errors: [], contractValue: 41589.04, contractValueInclVat: 41589.04, chequeTotal: 3780.82,
    depositCarriedForward: 3000, journals: { tco: 1, tcoLines: 2, pdr: 3 },
};

function renderDialog(dry: PostLeaseDryRunResponse, locale: "en" | "ar" = "en") {
    leaseApi.dryRunPost.mockResolvedValue(dry);
    return render(
        <NextIntlClientProvider locale={locale} messages={locale === "en" ? en : ar}>
            <PostLeaseDialog open lease={{ id: "B", displayContractNumber: null } as unknown as LeaseDetail}
                             onClose={() => {}} onPosted={() => {}} />
        </NextIntlClientProvider>,
    );
}

/** #100-f: a transfer's review lists every journal group the transfer posts, not only B's TCO and PDR counts. */
describe("PostLeaseDialog transfer journal groups", () => {
    afterEach(() => { cleanup(); vi.clearAllMocks(); });

    it("lists the PDR reversals, the TCR, the balance carry and the deposit carry", async () => {
        renderDialog({
            ...BASE,
            journals: {
                ...BASE.journals,
                transfer: {
                    moveDate: "2026-05-15", pdrReversals: 2, pdrReversalTotal: 30000,
                    unearned: 37808.22, unearnedVat: 1890.41, balanceCarried: -7808.22,
                    balanceCarriedDate: "2026-05-16", depositCarried: 3000,
                },
            },
        });
        const block = await screen.findByTestId("post-transfer-journals");
        expect(block).toHaveTextContent("The transfer also posts");
        expect(screen.getByTestId("post-transfer-pdr")).toHaveTextContent("30,000.00");
        expect(block).toHaveTextContent("2 cheque reversals on the old contract");
        expect(screen.getByTestId("post-transfer-tcr")).toHaveTextContent("37,808.22");
        expect(screen.getByTestId("post-transfer-tcr-vat")).toHaveTextContent("1,890.41");
        expect(screen.getByTestId("post-transfer-balance")).toHaveTextContent("7,808.22");
        expect(block).toHaveTextContent("Prepaid balance carried to this contract");
        expect(screen.getByTestId("post-transfer-deposit")).toHaveTextContent("3,000.00");
        // The old counts line is still there.
        expect(screen.getByTestId("post-journals")).toHaveTextContent("1 TCO with 2 lines, 3 PDR");
    });

    it("says an owed balance is owed, and leaves out groups that post nothing", async () => {
        renderDialog({
            ...BASE,
            journals: {
                ...BASE.journals,
                transfer: {
                    moveDate: "2026-05-15", pdrReversals: 0, pdrReversalTotal: 0,
                    unearned: 37808.22, unearnedVat: 0, balanceCarried: 1200,
                    balanceCarriedDate: "2026-05-16", depositCarried: 0,
                },
            },
        });
        const block = await screen.findByTestId("post-transfer-journals");
        expect(block).toHaveTextContent("Balance owed carried to this contract");
        expect(screen.queryByTestId("post-transfer-pdr")).toBeNull();
        expect(screen.queryByTestId("post-transfer-tcr-vat")).toBeNull();
        expect(screen.queryByTestId("post-transfer-deposit")).toBeNull();
    });

    it("shows no transfer block for an ordinary lease (an old server sends no groups)", async () => {
        renderDialog(BASE);
        await screen.findByTestId("post-journals");
        expect(screen.queryByTestId("post-transfer-journals")).toBeNull();
    });

    it("renders in Arabic", async () => {
        renderDialog({
            ...BASE,
            journals: {
                ...BASE.journals,
                transfer: {
                    moveDate: "2026-05-15", pdrReversals: 2, pdrReversalTotal: 30000,
                    unearned: 37808.22, unearnedVat: 0, balanceCarried: -7808.22,
                    balanceCarriedDate: "2026-05-16", depositCarried: 3000,
                },
            },
        }, "ar");
        const block = await screen.findByTestId("post-transfer-journals");
        expect(block).toHaveTextContent("يُرحِّل النقل أيضًا");
        expect(block).toHaveTextContent("عكس شيكين");
    });
});
