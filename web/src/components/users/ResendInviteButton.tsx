"use client";

import { useState } from "react";
import { useTranslations } from "next-intl";
import { Loader2, MailCheck, Send } from "lucide-react";
import { ApiError, throwIfNotOk } from "@/lib/api/facilities";

/**
 * "Resend invite" for a user whose set-password invite is unused or expired
 * (#7). The backend re-issues the token, which kills the previous link, and
 * emails USER_INVITED again. Nothing about a password is ever shown here: the
 * emailed link is the only way an invited user gets one.
 *
 * The endpoint is TENANT_ADMIN/SUPER_ADMIN only; callers render this button
 * only for those roles, and the server enforces the same.
 */
export function resendInvite(userId: string): Promise<Response> {
    return fetch(`/api/proxy/admin/users/${encodeURIComponent(userId)}/resend-invite`, { method: "POST" });
}

export function ResendInviteButton({ userId, onSent }: { userId: string; onSent?: () => void }) {
    const t = useTranslations("Invites");
    const [state, setState] = useState<"idle" | "sending" | "sent">("idle");
    const [error, setError] = useState<string | null>(null);

    const send = async () => {
        setState("sending");
        setError(null);
        try {
            await throwIfNotOk(await resendInvite(userId));
            setState("sent");
            onSent?.();
        } catch (e) {
            setState("idle");
            setError(e instanceof ApiError ? e.message : t("resendFailed"));
        }
    };

    return (
        <span className="inline-flex flex-col items-end gap-1">
            <button
                type="button"
                onClick={send}
                disabled={state !== "idle"}
                className="cursor-pointer inline-flex items-center gap-1.5 text-xs px-3 py-1.5 bg-primary/10 text-primary rounded-lg hover:bg-primary/20 transition-all duration-200 font-bold focus:ring-2 focus:ring-primary/20 focus:outline-none disabled:opacity-60 disabled:cursor-not-allowed"
            >
                {state === "sending" ? <Loader2 size={12} className="animate-spin" />
                    : state === "sent" ? <MailCheck size={12} /> : <Send size={12} className="rtl:-scale-x-100" />}
                {state === "sent" ? t("resent") : t("resend")}
            </button>
            {error && <span role="alert" className="text-[10px] text-error font-medium">{error}</span>}
        </span>
    );
}
