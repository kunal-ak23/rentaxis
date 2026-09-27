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

/**
 * Backend PR #372: the post dry run's `notices` — what a real post would do
 * that the accountant should know, not an error (unlike `errors`, they never
 * block the button).
 */
describe("PostLeaseDialog notices", () => {
    afterEach(() => { cleanup(); vi.clearAllMocks(); });

    it("shows the dry run's notices and still allows posting", async () => {
        leaseApi.dryRunPost.mockResolvedValue({
            ok: true, errors: [], contractValue: 10000, contractValueInclVat: 10000, chequeTotal: 10000,
            depositCarriedForward: 0, journals: { tco: 1, tcoLines: 2, pdr: 2 },
            notices: ["The amendment dated today keeps every posted recognition row and catches up in one line."],
        });
        render(
            <NextIntlClientProvider locale="en" messages={en}>
                <PostLeaseDialog open lease={{ id: "B", displayContractNumber: null } as unknown as LeaseDetail}
                                 onClose={() => {}} onPosted={() => {}} />
            </NextIntlClientProvider>,
        );
        const notices = await screen.findByTestId("post-dry-run-notices");
        expect(notices).toHaveTextContent("keeps every posted recognition row");
        expect((screen.getByTestId("post-lease-confirm") as HTMLButtonElement).disabled).toBe(false);
    });

    it("shows nothing when there are no notices", async () => {
        leaseApi.dryRunPost.mockResolvedValue({
            ok: true, errors: [], contractValue: 10000, contractValueInclVat: 10000, chequeTotal: 10000,
            depositCarriedForward: 0, journals: { tco: 1, tcoLines: 2, pdr: 2 }, notices: [],
        });
        render(
            <NextIntlClientProvider locale="en" messages={en}>
                <PostLeaseDialog open lease={{ id: "B", displayContractNumber: null } as unknown as LeaseDetail}
                                 onClose={() => {}} onPosted={() => {}} />
            </NextIntlClientProvider>,
        );
        await screen.findByTestId("post-dry-run-ok");
        expect(screen.queryByTestId("post-dry-run-notices")).toBeNull();
    });
});
