"use client";

import { useState } from "react";
import { useTranslations } from "next-intl";
import { KeyRound, Loader2, MailCheck } from "lucide-react";
import { ApiError, throwIfNotOk } from "@/lib/api/facilities";

/**
 * "Send password reset" (one login per person, 2026-10-15). The backend emails
 * the user a link to choose a new password, which then applies in every
 * organisation they sign in to. The admin never sees the link and never picks
 * the password, so an admin of one organisation cannot set the credential that
 * opens another. Nothing changes until the person uses the link.
 *
 * TENANT_ADMIN/SUPER_ADMIN only; callers render it for those roles, and the
 * server enforces the same.
 */
export function sendPasswordReset(userId: string): Promise<Response> {
    return fetch(`/api/proxy/admin/users/${encodeURIComponent(userId)}/send-password-reset`, { method: "POST" });
}

export function SendPasswordResetButton({ userId }: { userId: string }) {
    const t = useTranslations("Invites");
    const [state, setState] = useState<"idle" | "sending" | "sent">("idle");
    const [error, setError] = useState<string | null>(null);

    const send = async () => {
        if (!window.confirm(t("resetConfirm"))) return;
        setState("sending");
        setError(null);
        try {
            await throwIfNotOk(await sendPasswordReset(userId));
            setState("sent");
        } catch (e) {
            setState("idle");
            setError(e instanceof ApiError ? e.message : t("resetFailed"));
        }
    };

    return (
        <span className="inline-flex flex-col items-end gap-1">
            <button
                type="button"
                onClick={send}
                disabled={state !== "idle"}
                data-testid="send-password-reset"
                className="cursor-pointer inline-flex items-center gap-1.5 text-xs px-3 py-1.5 bg-primary/10 text-primary rounded-lg hover:bg-primary/20 transition-all duration-200 font-bold focus:ring-2 focus:ring-primary/20 focus:outline-none disabled:opacity-60 disabled:cursor-not-allowed"
            >
                {state === "sending" ? <Loader2 size={12} className="animate-spin" />
                    : state === "sent" ? <MailCheck size={12} /> : <KeyRound size={12} />}
                {state === "sent" ? t("resetSent") : t("resetPassword")}
            </button>
            {error && <span role="alert" className="text-[10px] text-error font-medium">{error}</span>}
        </span>
    );
}
