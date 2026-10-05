"use client";

import { useEffect, useRef, useState } from "react";
import { signIn } from "next-auth/react";
import { useLocale, useTranslations } from "next-intl";
import Image from "next/image";
import { ArrowRight, Loader2, Phone, ShieldCheck } from "lucide-react";
import type { ConfirmationResult } from "firebase/auth";
import { Link, useRouter } from "@/i18n/routing";
import {
    PHONE_COUNTRIES, RESEND_COOLDOWN_SECONDS, guardAuthErrorKey, guardSignInErrorKey, normaliseOtp, toGuardE164,
    type PhoneCountry,
} from "@/lib/guardAuth/phone";
import {
    confirmGuardCode, firebaseErrorCode, loadGuardAuthConfig, sendGuardCode, type GuardAuthConfig,
} from "@/lib/guardAuth/firebasePhone";

const RECAPTCHA_ID = "guard-recaptcha";

/**
 * Security guard sign-in on the web (tutorial 25). Guards have no password —
 * the Security app signs them in with their phone and an SMS code — so the web
 * gate desk was unreachable. This is the same flow: Firebase sends and checks
 * the code (and rate-limits it), the backend verifies the resulting token and
 * maps the phone to one active guard, and the guard lands on the gate desk.
 */
export default function GuardSignInPage() {
    const t = useTranslations("GuardSignIn");
    const locale = useLocale();
    const router = useRouter();
    const [config, setConfig] = useState<GuardAuthConfig | null | undefined>(undefined);
    const [country, setCountry] = useState<PhoneCountry>(PHONE_COUNTRIES[0]);
    const [phone, setPhone] = useState("");
    const [code, setCode] = useState("");
    const [step, setStep] = useState<"phone" | "code">("phone");
    const [sentTo, setSentTo] = useState("");
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [notice, setNotice] = useState<string | null>(null);
    const [cooldown, setCooldown] = useState(0);
    const confirmation = useRef<ConfirmationResult | null>(null);

    useEffect(() => {
        let live = true;
        loadGuardAuthConfig().then(c => { if (live) setConfig(c); });
        return () => { live = false; };
    }, []);

    useEffect(() => {
        if (cooldown <= 0) return;
        const timer = setTimeout(() => setCooldown(c => c - 1), 1000);
        return () => clearTimeout(timer);
    }, [cooldown]);

    const send = async (resend = false) => {
        if (!config) return;
        const e164 = toGuardE164(country, phone);
        if (!e164) {
            setError(t("errInvalidPhone", { example: `${country.dialCode}${country.example}` }));
            return;
        }
        setBusy(true);
        setError(null);
        setNotice(null);
        try {
            confirmation.current = await sendGuardCode(config, e164, RECAPTCHA_ID, locale);
            setSentTo(e164);
            setStep("code");
            setCode("");
            setCooldown(RESEND_COOLDOWN_SECONDS);
            if (resend) setNotice(t("newCodeSent"));
        } catch (err) {
            setError(t(guardAuthErrorKey(firebaseErrorCode(err)), { example: `${country.dialCode}${country.example}` }));
            // A refused resend still waits out the cooldown, as the Security app does.
            if (resend) setCooldown(RESEND_COOLDOWN_SECONDS);
        } finally {
            setBusy(false);
        }
    };

    const verify = async () => {
        const otp = normaliseOtp(code);
        if (!otp || !confirmation.current) {
            setError(t("errCodeFormat"));
            return;
        }
        setBusy(true);
        setError(null);
        setNotice(null);
        try {
            const idToken = await confirmGuardCode(confirmation.current, otp);
            const res = await signIn("guard-phone", { idToken, redirect: false });
            if (!res || res.error) {
                setError(t(guardSignInErrorKey(res?.error)));
                setBusy(false);
                return;
            }
            router.push("/dashboard/gatepass/gate");
        } catch (err) {
            setError(t(guardAuthErrorKey(firebaseErrorCode(err)), { example: `${country.dialCode}${country.example}` }));
            setBusy(false);
        }
    };

    const input = "w-full border border-border rounded-lg bg-surface p-3.5 text-sm text-foreground placeholder:text-muted/50 focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all font-medium";
    const button = "w-full bg-accent text-accent-foreground py-4 rounded-xl text-xs font-bold uppercase tracking-[0.1em] shadow-xl shadow-accent/20 hover:brightness-110 active:scale-[0.98] transition-all flex items-center justify-center gap-2 disabled:opacity-70 cursor-pointer disabled:cursor-not-allowed focus:outline-none focus:ring-2 focus:ring-accent/30";

    return (
        <div className="min-h-screen bg-sidebar flex items-center justify-center p-4">
            <div className="w-full max-w-[420px] bg-surface border border-border/50 rounded-xl p-6 sm:p-10 shadow-[0_32px_64px_-16px_rgba(0,0,0,0.25)]" data-testid="guard-signin">
                <div className="text-center mb-8">
                    <Image src="/miftah-wordmark.png" alt="Miftah" width={160} height={46} className="mx-auto mb-4 object-contain" priority />
                    <h1 className="text-sm font-bold text-foreground flex items-center justify-center gap-2">
                        <ShieldCheck className="w-4 h-4 text-primary" /> {t("title")}
                    </h1>
                    <p className="text-xs text-muted mt-1">{step === "phone" ? t("subtitle") : t("codeSubtitle", { phone: sentTo })}</p>
                </div>

                {config === undefined && (
                    <div className="flex justify-center py-6"><Loader2 className="w-5 h-5 animate-spin text-primary" /></div>
                )}

                {config === null && (
                    <p role="alert" className="bg-warning/10 text-warning border border-warning/20 rounded-xl p-3 text-xs font-semibold text-center" data-testid="guard-signin-unavailable">
                        {t("notConfigured")}
                    </p>
                )}

                {config && step === "phone" && (
                    <form onSubmit={e => { e.preventDefault(); void send(); }} className="space-y-4">
                        <div>
                            <label htmlFor="guard-phone" className="block text-[10px] font-bold text-muted uppercase tracking-widest mb-2">{t("phoneLabel")}</label>
                            <div className="flex gap-2" dir="ltr">
                                <select
                                    aria-label={t("countryLabel")}
                                    value={country.iso}
                                    onChange={e => setCountry(PHONE_COUNTRIES.find(c => c.iso === e.target.value) ?? PHONE_COUNTRIES[0])}
                                    className="border border-border rounded-lg bg-surface px-2 text-sm font-medium focus:outline-none focus:ring-2 focus:ring-primary/20"
                                    data-testid="guard-country"
                                >
                                    {PHONE_COUNTRIES.map(c => <option key={c.iso} value={c.iso}>{c.dialCode}</option>)}
                                </select>
                                <div className="relative flex-1">
                                    <Phone className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-muted" />
                                    <input
                                        id="guard-phone"
                                        type="tel"
                                        inputMode="tel"
                                        autoComplete="tel-national"
                                        required
                                        value={phone}
                                        onChange={e => { setPhone(e.target.value); setError(null); }}
                                        placeholder={country.example}
                                        className={`${input} pl-9`}
                                        data-testid="guard-phone"
                                    />
                                </div>
                            </div>
                        </div>
                        {error && <p role="alert" className="bg-error/10 text-error p-3 rounded-xl text-[11px] font-bold text-center border border-error/20" data-testid="guard-error">{error}</p>}
                        <button type="submit" disabled={busy} className={button} data-testid="guard-send-code">
                            {busy ? <Loader2 className="w-4 h-4 animate-spin" /> : <>{t("sendCode")} <ArrowRight className="w-4 h-4 rtl:rotate-180" /></>}
                        </button>
                    </form>
                )}

                {config && step === "code" && (
                    <form onSubmit={e => { e.preventDefault(); void verify(); }} className="space-y-4">
                        <div>
                            <label htmlFor="guard-code" className="block text-[10px] font-bold text-muted uppercase tracking-widest mb-2">{t("codeLabel")}</label>
                            <input
                                id="guard-code"
                                type="text"
                                inputMode="numeric"
                                autoComplete="one-time-code"
                                maxLength={7}
                                dir="ltr"
                                required
                                value={code}
                                onChange={e => { setCode(e.target.value); setError(null); }}
                                className={`${input} text-center tracking-[0.5em] tabular-nums`}
                                data-testid="guard-code"
                            />
                        </div>
                        {error && <p role="alert" className="bg-error/10 text-error p-3 rounded-xl text-[11px] font-bold text-center border border-error/20" data-testid="guard-error">{error}</p>}
                        {notice && <p role="status" className="text-[11px] text-success text-center">{notice}</p>}
                        <button type="submit" disabled={busy} className={button} data-testid="guard-verify">
                            {busy ? <Loader2 className="w-4 h-4 animate-spin" /> : <>{t("verify")} <ArrowRight className="w-4 h-4 rtl:rotate-180" /></>}
                        </button>
                        <div className="flex items-center justify-between text-[11px]">
                            <button type="button" className="text-muted hover:text-foreground cursor-pointer" onClick={() => { setStep("phone"); setError(null); setNotice(null); }}>
                                {t("changeNumber")}
                            </button>
                            <button
                                type="button"
                                disabled={busy || cooldown > 0}
                                onClick={() => void send(true)}
                                className="text-primary font-semibold disabled:text-muted disabled:cursor-not-allowed cursor-pointer"
                                data-testid="guard-resend"
                            >
                                {cooldown > 0 ? t("resendIn", { seconds: cooldown }) : t("resend")}
                            </button>
                        </div>
                    </form>
                )}

                {/* Firebase's invisible reCAPTCHA attaches here. */}
                <div id={RECAPTCHA_ID} />

                <div className="mt-8 pt-6 border-t border-border/50 text-center">
                    <Link href="/auth/login" className="text-[11px] text-muted hover:text-foreground font-medium">{t("backToLogin")}</Link>
                </div>
            </div>
        </div>
    );
}
