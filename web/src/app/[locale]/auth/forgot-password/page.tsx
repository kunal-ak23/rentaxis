"use client";

import { useState } from "react";
import { useTranslations } from "next-intl";
import { Link } from "@/i18n/routing";
import { ArrowLeft, Loader2, Mail, MailCheck } from "lucide-react";
import Image from "next/image";

/**
 * Public "forgot password" (2026-10-15). The backend answers every email the
 * same way — whether or not it has a login — so this page says the same thing
 * every time: if an account exists, a link was sent. The link (single use, 45
 * minutes) opens /auth/set-password, and the new password applies in every
 * organisation the person belongs to. The one other answer is the per-IP rate
 * limit, which says nothing about the address.
 */
export default function ForgotPasswordPage() {
    const t = useTranslations("Auth");
    const [email, setEmail] = useState("");
    const [state, setState] = useState<"idle" | "sending" | "sent">("idle");
    const [error, setError] = useState<string | null>(null);

    const submit = async (e: React.FormEvent) => {
        e.preventDefault();
        setState("sending");
        setError(null);
        try {
            const res = await fetch("/api/proxy/auth/forgot-password", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ email: email.trim() }),
            });
            if (res.status === 429) {
                setState("idle");
                setError(t("forgotRateLimited"));
            } else if (res.ok) {
                setState("sent");
            } else {
                setState("idle");
                setError(t("genericError"));
            }
        } catch {
            setState("idle");
            setError(t("genericError"));
        }
    };

    return (
        <div className="min-h-screen bg-sidebar flex items-center justify-center p-4">
            <div className="w-full max-w-[420px] bg-surface border border-border/50 rounded-xl p-8 md:p-12 shadow-[0_32px_64px_-16px_rgba(0,0,0,0.25)]">
                <div className="text-center mb-8">
                    <Image src="/miftah-wordmark.png" alt="Miftah" width={180} height={52}
                           className="mx-auto mb-4 object-contain" priority />
                    <h1 className="text-base font-bold text-foreground">{t("forgotTitle")}</h1>
                </div>

                {state === "sent" ? (
                    <div role="status" data-testid="forgot-sent"
                         className="rounded-xl border border-success/20 bg-success/10 p-4 text-xs font-bold text-success flex gap-2 items-start">
                        <MailCheck className="w-4 h-4 shrink-0" />
                        <span>{t("forgotSent")}</span>
                    </div>
                ) : (
                    <form onSubmit={submit} className="space-y-5">
                        <p className="text-xs text-muted font-medium">{t("forgotIntro")}</p>
                        <div>
                            <label htmlFor="forgot-email" className="block text-[10px] font-bold text-muted uppercase tracking-widest mb-2 ms-1">
                                {t("emailLabel")}
                            </label>
                            <div className="relative group">
                                <Mail className="absolute start-4 top-1/2 -translate-y-1/2 w-4 h-4 text-muted group-focus-within:text-primary transition-colors" />
                                <input
                                    id="forgot-email"
                                    required
                                    type="email"
                                    value={email}
                                    onChange={(e) => setEmail(e.target.value)}
                                    placeholder="name@company.com"
                                    className="w-full border border-border rounded-lg bg-surface p-3.5 ps-11 text-xs text-foreground placeholder:text-muted/50 focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all font-medium"
                                />
                            </div>
                        </div>
                        {error && (
                            <div role="alert" className="bg-error/10 text-error p-3 rounded-xl text-[10px] font-bold text-center border border-error/20">
                                {error}
                            </div>
                        )}
                        <button
                            type="submit"
                            disabled={state === "sending"}
                            className="w-full bg-accent text-accent-foreground py-4 rounded-xl text-xs font-bold uppercase tracking-[0.1em] shadow-xl shadow-accent/20 hover:brightness-110 transition-all flex items-center justify-center gap-2 disabled:opacity-70 cursor-pointer disabled:cursor-not-allowed focus:outline-none focus:ring-2 focus:ring-accent/30"
                        >
                            {state === "sending" ? <Loader2 className="w-4 h-4 animate-spin" /> : t("sendLink")}
                        </button>
                    </form>
                )}

                <div className="mt-8 pt-6 border-t border-border/50 text-center">
                    <Link href="/auth/login" className="inline-flex items-center gap-1.5 text-[11px] text-accent font-bold hover:brightness-110">
                        <ArrowLeft className="w-3.5 h-3.5 rtl:-scale-x-100" />
                        {t("backToSignIn")}
                    </Link>
                </div>
            </div>
        </div>
    );
}
