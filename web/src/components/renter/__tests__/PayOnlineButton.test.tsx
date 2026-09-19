import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";
import type { RenterCheque } from "@/lib/api/leasing";

/**
 * The Razorpay handoff: the checkout script comes from the order response's
 * own `sdkJsUrl` (never a hardcoded URL — ruling #8), a successful handler
 * calls `verify`, and dismissing the modal calls `cancel`. Also pins the
 * gating a caller relies on: no button at all off a row that is not due, not
 * online-enabled, or already fully paid.
 */

const api = vi.hoisted(() => ({ createOrder: vi.fn(), verify: vi.fn(), cancel: vi.fn() }));

vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return { ...m, onlinePayApi: { ...m.onlinePayApi, ...api } };
});

import PayOnlineButton from "../PayOnlineButton";

function cheque(over: Partial<RenterCheque> = {}): RenterCheque {
    return {
        id: "c1", leaseId: "l1", installmentNumber: 1, dueDate: "2026-06-01",
        amount: 5000, status: "REGISTERED", mode: "PDC", chequeNumber: "000101",
        bankName: "ENBD", narration: null, propertyName: "L'Olivier", unitIdentifier: "A-101",
        renterName: "Tenant", due: true, overdue: false, daysOverdue: 0, gracePeriodDays: 5,
        penaltyOutstanding: 0, payable: 5000, onlineEnabled: true, penaltyAssessmentId: null,
        failureReason: null, clearedAt: null, statusChangedAt: null,
        ...over,
    };
}

type CapturedOptions = {
    key: string;
    order_id: string;
    handler: (r: { razorpay_order_id: string; razorpay_payment_id: string; razorpay_signature: string }) => void;
    modal?: { ondismiss?: () => void };
};

function stubRazorpay() {
    const captured: CapturedOptions[] = [];
    const open = vi.fn();
    (window as unknown as { Razorpay: new (o: CapturedOptions) => { open: () => void } }).Razorpay = class {
        constructor(opts: CapturedOptions) {
            captured.push(opts);
        }
        open() {
            open();
        }
    };
    return { captured, open };
}

function renderButton(props: Partial<Parameters<typeof PayOnlineButton>[0]> = {}) {
    const onPaid = vi.fn();
    render(
        <NextIntlClientProvider locale="en" messages={en}>
            <PayOnlineButton cheque={cheque()} onPaid={onPaid} {...props} />
        </NextIntlClientProvider>,
    );
    return { onPaid };
}

beforeEach(() => {
    api.createOrder.mockResolvedValue({
        orderId: "order_1", amount: 5000, currency: "AED", gatewayKey: "key_test",
        gatewayCode: "RAZORPAY", sdkJsUrl: "https://checkout.example/sdk.js",
        renterName: "Tenant", renterEmail: null,
    });
});

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
    delete (window as { Razorpay?: unknown }).Razorpay;
});

describe("PayOnlineButton gating", () => {
    it("renders nothing when the row is not due", () => {
        renderButton({ cheque: cheque({ due: false }) });
        expect(screen.queryByTestId("pay-online-c1")).not.toBeInTheDocument();
    });

    it("renders nothing when the property has online payments disabled", () => {
        renderButton({ cheque: cheque({ onlineEnabled: false }) });
        expect(screen.queryByTestId("pay-online-c1")).not.toBeInTheDocument();
    });

    it("renders nothing once nothing is payable", () => {
        renderButton({ cheque: cheque({ payable: 0 }) });
        expect(screen.queryByTestId("pay-online-c1")).not.toBeInTheDocument();
    });

    it("renders Pay on a due, online-enabled, payable row", () => {
        renderButton();
        expect(screen.getByTestId("pay-online-c1")).toBeInTheDocument();
    });
});

describe("PayOnlineButton checkout flow", () => {
    it("loads the sdkJsUrl from the order response, not a hardcoded URL", async () => {
        renderButton();
        screen.getByTestId("pay-online-c1").click();
        await waitFor(() => expect(api.createOrder).toHaveBeenCalledWith("c1"));
        // The script tag is appended synchronously once createOrder's promise
        // settles — flush microtasks rather than polling on a real timer, so
        // this assertion cannot straddle a sibling test's own window.Razorpay
        // stub (they share one jsdom `window`).
        await Promise.resolve();
        await Promise.resolve();
        expect(document.querySelector('script[src="https://checkout.example/sdk.js"]')).toBeTruthy();
    });

    it("verifies on the handler callback and refreshes the caller", async () => {
        const { captured } = stubRazorpay();
        api.verify.mockResolvedValue({ success: true, message: null, paymentId: "pay_1" });
        const { onPaid } = renderButton();
        screen.getByTestId("pay-online-c1").click();
        await waitFor(() => expect(captured.length).toBe(1));

        captured[0].handler({
            razorpay_order_id: "order_1",
            razorpay_payment_id: "pay_1",
            razorpay_signature: "sig_1",
        });

        await waitFor(() =>
            expect(api.verify).toHaveBeenCalledWith({
                gatewayOrderId: "order_1",
                gatewayPaymentId: "pay_1",
                gatewaySignature: "sig_1",
            }),
        );
        await waitFor(() => expect(onPaid).toHaveBeenCalled());
    });

    it("cancels the pending payment when the checkout modal is dismissed", async () => {
        const { captured } = stubRazorpay();
        api.cancel.mockResolvedValue(undefined);
        renderButton();
        screen.getByTestId("pay-online-c1").click();
        await waitFor(() => expect(captured.length).toBe(1));

        captured[0].modal?.ondismiss?.();

        await waitFor(() => expect(api.cancel).toHaveBeenCalledWith("c1"));
    });
});
