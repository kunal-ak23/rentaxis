"use client";

import { useState } from "react";
import { useTranslations } from "next-intl";
import { Loader2, CreditCard } from "lucide-react";
import { ApiError, onlinePayApi, type RenterCheque } from "@/lib/api/leasing";

/**
 * The renter's "Pay" button for one due cheque row.
 *
 * Loads Razorpay's own checkout script from the `sdkJsUrl` the order
 * response carries — never a hardcoded URL, so a gateway or CDN change on
 * the backend needs no client release. Only rendered by the caller on a row
 * that is both due and the property's own `onlineEnabled`, but the same
 * guard is repeated here so this component can never render a Pay button
 * the backend would refuse.
 */

type RazorpayHandlerResponse = {
    razorpay_order_id: string;
    razorpay_payment_id: string;
    razorpay_signature: string;
};

type RazorpayOptions = {
    key: string;
    amount: number;
    currency: string;
    order_id: string;
    name?: string;
    prefill?: { name?: string; email?: string };
    handler: (response: RazorpayHandlerResponse) => void;
    modal?: { ondismiss?: () => void };
};

type RazorpayInstance = { open: () => void };

declare global {
    interface Window {
        Razorpay?: new (options: RazorpayOptions) => RazorpayInstance;
    }
}

/** Loaded once per session — a second cheque's Pay button reuses the same script tag. */
function loadCheckoutScript(src: string): Promise<void> {
    if (window.Razorpay) return Promise.resolve();
    return new Promise((resolve, reject) => {
        const existing = document.querySelector(`script[src="${src}"]`);
        if (existing) {
            existing.addEventListener("load", () => resolve());
            existing.addEventListener("error", () => reject(new Error("script load failed")));
            return;
        }
        const script = document.createElement("script");
        script.src = src;
        script.async = true;
        script.onload = () => resolve();
        script.onerror = () => reject(new Error("script load failed"));
        document.body.appendChild(script);
    });
}

type Props = {
    cheque: RenterCheque;
    /** Refresh the caller's own row list after a verified payment. */
    onPaid: () => void;
};

export default function PayOnlineButton({ cheque, onPaid }: Props) {
    const t = useTranslations("OnlinePayments");
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState<string | null>(null);

    if (!cheque.onlineEnabled || !cheque.due || cheque.payable <= 0) return null;

    const pay = async () => {
        setBusy(true);
        setError(null);
        try {
            const order = await onlinePayApi.createOrder(cheque.id);
            await loadCheckoutScript(order.sdkJsUrl);
            if (!window.Razorpay) throw new Error("Razorpay checkout did not load");

            const checkout = new window.Razorpay({
                key: order.gatewayKey,
                amount: order.amount,
                currency: order.currency,
                order_id: order.orderId,
                prefill: { name: order.renterName ?? undefined, email: order.renterEmail ?? undefined },
                handler: response => {
                    onlinePayApi
                        .verify({
                            gatewayOrderId: response.razorpay_order_id,
                            gatewayPaymentId: response.razorpay_payment_id,
                            gatewaySignature: response.razorpay_signature,
                        })
                        .then(() => onPaid())
                        .catch(e => setError(e instanceof ApiError ? e.message : t("payFailed")))
                        .finally(() => setBusy(false));
                },
                modal: {
                    ondismiss: () => {
                        onlinePayApi
                            .cancel(cheque.id)
                            .catch(() => {
                                // Best-effort: the row simply stays ONLINE_PENDING until it expires.
                            })
                            .finally(() => setBusy(false));
                    },
                },
            });
            checkout.open();
        } catch (e) {
            setError(e instanceof ApiError ? e.message : t("payFailed"));
            setBusy(false);
        }
    };

    return (
        <div>
            <button
                type="button"
                data-testid={`pay-online-${cheque.id}`}
                onClick={pay}
                disabled={busy}
                className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[11px] font-bold bg-primary text-primary-foreground hover:bg-primary/90 transition-colors cursor-pointer disabled:opacity-50"
            >
                {busy ? <Loader2 size={12} className="animate-spin" /> : <CreditCard size={12} />}
                {t("payNow")}
            </button>
            {error && (
                <p className="mt-1 text-[10px] text-error" data-testid={`pay-online-error-${cheque.id}`}>
                    {error}
                </p>
            )}
        </div>
    );
}
