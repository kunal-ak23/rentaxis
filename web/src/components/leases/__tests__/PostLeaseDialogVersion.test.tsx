import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";

import en from "../../../../messages/en.json";
import { ApiError } from "@/lib/api/facilities";

const leaseApi = vi.hoisted(() => ({ dryRunPost: vi.fn(), post: vi.fn() }));
vi.mock("@/lib/api/leasing", async (orig) => {
    const real = await orig<typeof import("@/lib/api/leasing")>();
    return { ...real, leaseApi: { ...real.leaseApi, ...leaseApi } };
});

import PostLeaseDialog from "../PostLeaseDialog";
import type { LeaseDetail } from "@/lib/api/leasing";

const dry = (value: number) => ({
    ok: true, errors: [], contractValue: value, contractValueInclVat: value, chequeTotal: value,
    depositCarriedForward: 0, journals: { tco: 1, tcoLines: 2, pdr: 4 }, notices: [],
});
const CHANGED = new ApiError(409, "This contract changed since you opened it — review it again",
    JSON.stringify({ error: true, status: 409, code: "lease.changed", message: "This contract changed since you opened it — review it again" }));

/**
 * Break-it round 2 (contracts2) F2: the dialog posts only the contract it
 * showed. It sends the version it loaded; when the server says the contract
 * changed since, it says so, asks the page to reload the lease, and prices the
 * contract again — it never posts the new figures unseen.
 */
describe("PostLeaseDialog — the version it showed", () => {
    afterEach(() => { cleanup(); vi.clearAllMocks(); });

    function renderDialog(lease: LeaseDetail, onStale = vi.fn(), onPosted = vi.fn()) {
        const utils = render(
            <NextIntlClientProvider locale="en" messages={en}>
                <PostLeaseDialog open lease={lease} onClose={() => {}} onPosted={onPosted} onStale={onStale} />
            </NextIntlClientProvider>,
        );
        return { ...utils, onStale, onPosted };
    }

    it("posts with the lease version it loaded", async () => {
        leaseApi.dryRunPost.mockResolvedValue(dry(65000));
        leaseApi.post.mockResolvedValue({ tcoEntryNumber: "TCO-1" });
        const { onPosted } = renderDialog({ id: "D", version: 3, displayContractNumber: null } as unknown as LeaseDetail);
        await screen.findByTestId("post-dry-run-ok");
        fireEvent.click(screen.getByTestId("post-lease-confirm"));
        await waitFor(() => expect(onPosted).toHaveBeenCalled());
        expect(leaseApi.post).toHaveBeenCalledWith("D", 3);
    });

    it("on 409 lease.changed: says so, asks for a reload, and prices the reloaded contract", async () => {
        leaseApi.dryRunPost.mockResolvedValueOnce(dry(65000)).mockResolvedValueOnce(dry(95000));
        leaseApi.post.mockRejectedValue(CHANGED);
        const lease = { id: "D", version: 3, displayContractNumber: null } as unknown as LeaseDetail;
        const { onStale, onPosted, rerender } = renderDialog(lease);
        await screen.findByTestId("post-dry-run-ok");
        fireEvent.click(screen.getByTestId("post-lease-confirm"));

        expect(await screen.findByTestId("post-error")).toHaveTextContent("This contract changed since you opened it");
        expect(onStale).toHaveBeenCalledTimes(1);
        expect(onPosted).not.toHaveBeenCalled();

        // The page reloads the lease: a new version, and the dialog re-runs the dry run.
        rerender(
            <NextIntlClientProvider locale="en" messages={en}>
                <PostLeaseDialog open lease={{ ...lease, version: 5 } as LeaseDetail} onClose={() => {}} onPosted={onPosted} onStale={onStale} />
            </NextIntlClientProvider>,
        );
        await waitFor(() => expect(leaseApi.dryRunPost).toHaveBeenCalledTimes(2));
        expect((await screen.findAllByText("95,000.00")).length).toBeGreaterThan(0);
        // The warning stays up until the user acts again.
        expect(screen.getByTestId("post-error")).toHaveTextContent("This contract changed since you opened it");
    });
});
