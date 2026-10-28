import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";
import type { UnappliedOnlinePayment } from "@/lib/api/leasing";

/**
 * The register's "Online payments to refund" tile (Ruling #5). The backend
 * endpoint is not on this branch yet, so this pins the client's contract:
 * hidden below `visible`/zero-count/a failed count fetch, and the list loads
 * lazily on first expand.
 */

const api = vi.hoisted(() => ({ unappliedCount: vi.fn(), unapplied: vi.fn() }));
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return { ...m, onlinePayApi: { ...m.onlinePayApi, ...api } };
});

import UnappliedPaymentsTile from "../UnappliedPaymentsTile";

function payment(over: Partial<UnappliedOnlinePayment> = {}): UnappliedOnlinePayment {
    return {
        id: "u1", createdAt: "2026-06-01T10:00:00Z", capturedAt: "2026-06-01T10:05:00Z",
        amount: 5000, currency: "AED", gatewayOrderId: "order_1", gatewayPaymentId: "pay_1",
        failureReason: "Cheque row never cleared", chequeId: "c1", chequeNumber: "000101",
        chequeStatus: "ONLINE_PENDING", leaseId: "l1", displayContractNumber: "CN-100",
        renterName: "Sample Renter One", propertyName: "Sample Heights", unitIdentifier: "A-101",
        ...over,
    };
}

function renderTile(visible = true) {
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <UnappliedPaymentsTile visible={visible} />
        </NextIntlClientProvider>,
    );
}

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

describe("UnappliedPaymentsTile visibility", () => {
    it("renders nothing when the caller lacks the role", async () => {
        renderTile(false);
        await waitFor(() => expect(api.unappliedCount).not.toHaveBeenCalled());
        expect(screen.queryByTestId("unapplied-payments-tile")).not.toBeInTheDocument();
    });

    it("renders nothing when the count is zero", async () => {
        api.unappliedCount.mockResolvedValue({ count: 0, totalAmount: 0 });
        renderTile();
        await waitFor(() => expect(api.unappliedCount).toHaveBeenCalled());
        expect(screen.queryByTestId("unapplied-payments-tile")).not.toBeInTheDocument();
    });

    it("renders nothing — and does not throw — when the count fetch fails", async () => {
        api.unappliedCount.mockRejectedValue(new Error("network down"));
        renderTile();
        await waitFor(() => expect(api.unappliedCount).toHaveBeenCalled());
        expect(screen.queryByTestId("unapplied-payments-tile")).not.toBeInTheDocument();
    });

    it("shows the count and total when there is a backlog", async () => {
        api.unappliedCount.mockResolvedValue({ count: 2, totalAmount: 8500 });
        renderTile();
        const tile = await screen.findByTestId("unapplied-payments-tile");
        expect(tile).toHaveTextContent("8,500.00");
        expect(tile).toHaveTextContent("2");
    });
});

describe("UnappliedPaymentsTile expand", () => {
    beforeEach(() => {
        api.unappliedCount.mockResolvedValue({ count: 1, totalAmount: 5000 });
    });

    it("fetches the list lazily on first expand, not before", async () => {
        api.unapplied.mockResolvedValue({ content: [payment()], totalElements: 1, totalPages: 1, number: 0, size: 100 });
        renderTile();
        await screen.findByTestId("unapplied-payments-tile");
        expect(api.unapplied).not.toHaveBeenCalled();

        screen.getByTestId("unapplied-payments-toggle").click();

        const row = await screen.findByTestId("unapplied-payments-row-u1");
        expect(row).toHaveTextContent("Sample Renter One");
        expect(row).toHaveTextContent("000101");
        expect(row).toHaveTextContent("pay_1");
        expect(api.unapplied).toHaveBeenCalledTimes(1);
    });

    it("surfaces a list load failure without hiding the tile", async () => {
        const { ApiError } = await import("@/lib/api/leasing");
        api.unapplied.mockRejectedValue(new ApiError(500, "list boom"));
        renderTile();
        await screen.findByTestId("unapplied-payments-tile");

        screen.getByTestId("unapplied-payments-toggle").click();

        expect(await screen.findByTestId("unapplied-payments-error")).toHaveTextContent("list boom");
    });
});
