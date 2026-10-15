"use client";

import { useState } from "react";
import { useTranslations } from "next-intl";
import { signIn } from "next-auth/react";
import { Link, useRouter } from "@/i18n/routing";
import { motion } from "framer-motion";
import { Mail, Lock, ArrowRight, Loader2, Eye, EyeOff } from "lucide-react";
import Image from "next/image";
import { useSearchParams } from "next/navigation";
import { safeCallbackUrl } from "@/lib/session/proxyFetchGuard";

/** The Auth catalogue key for each sign-in refusal that is not a wrong password. */
const LOCKED_OUT_KEYS: Record<string, "accountInactive" | "orgInactive" | "rateLimited"> = {
    ACCOUNT_INACTIVE: "accountInactive",
    ORG_INACTIVE: "orgInactive",
    RATE_LIMITED: "rateLimited",
};

export default function LoginPage() {
    const t = useTranslations("Auth");
    const router = useRouter();
    const searchParams = useSearchParams();
    const registered = searchParams.get("registered") === "true";
    // Set by /auth/set-password after an invite or reset link was used.
    const passwordSet = searchParams.get("welcome") === "1";
    // Break round 1, F6: set when an expired session sent the user here.
    // Validated against this origin at the moment it is used (safeCallbackUrl).
    const rawCallbackUrl = searchParams.get("callbackUrl");
    const [email, setEmail] = useState("");
    const [password, setPassword] = useState("");
    const [showPassword, setShowPassword] = useState(false);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState("");
    const [fieldErrors, setFieldErrors] = useState<{ email?: string; password?: string }>({});
    // Tenant picker — populated when the backend returns 409 (email exists in
    // multiple tenants and password matched in more than one). User picks one
    // and we resubmit with `tenantId` set.
    const [tenantCandidates, setTenantCandidates] = useState<Array<{ tenantId: string; tenantName: string }>>([]);

    const attemptSignIn = async (tenantId?: string) => {
        return signIn("credentials", {
            email,
            password,
            ...(tenantId ? { tenantId } : {}),
            redirect: false,
        });
    };

    const handleLogin = async (e: React.FormEvent) => {
        e.preventDefault();
        setLoading(true);
        setError("");
        setFieldErrors({});
        setTenantCandidates([]);

        try {
            const res = await attemptSignIn();
            await handleSignInResult(res);
        } catch {
            setError(t("genericError"));
            setLoading(false);
        }
    };

    const handleSignInResult = async (res: { error?: string | null; ok?: boolean } | undefined) => {
        // LOGIN_AMBIGUOUS:[{tenantId, tenantName}, ...] — show tenant picker.
        if (res?.error?.startsWith("LOGIN_AMBIGUOUS:")) {
            try {
                const json = res.error.slice("LOGIN_AMBIGUOUS:".length);
                const candidates = JSON.parse(json) as Array<{ tenantId: string; tenantName: string }>;
                setTenantCandidates(candidates);
                setError("");
                setLoading(false);
                return;
            } catch {
                // Fall through to generic error.
            }
        }

        // Deactivated account / organisation, or too many attempts: say so
        // rather than blaming the password the user typed correctly.
        const lockedOut = res?.error ? LOCKED_OUT_KEYS[res.error] : undefined;
        if (lockedOut) {
            setError(t(lockedOut));
            setLoading(false);
            return;
        }

        if (res?.error) {
            setError(t("invalidCredentials"));
            setFieldErrors({ email: t("checkEmail"), password: t("checkPassword") });
            setLoading(false);
            return;
        }

        const callbackUrl = safeCallbackUrl(rawCallbackUrl);
        if (callbackUrl) {
            // A full path with its locale: navigate outside the locale-prefixing router.
            window.location.assign(callbackUrl);
            return;
        }

        // Fetch session to check role for redirect
        const { getSession } = await import("next-auth/react");
        const session = await getSession();
        const role = session?.user?.role;
        if (role === "RENTER") {
            router.push("/dashboard/renter-portal");
        } else {
            router.push("/dashboard");
        }
    };

    const handlePickTenant = async (tenantId: string) => {
        setLoading(true);
        setError("");
        try {
            const res = await attemptSignIn(tenantId);
            await handleSignInResult(res);
        } catch {
            setError(t("genericError"));
            setLoading(false);
        }
    };

    return (
        <div className="min-h-screen bg-sidebar flex items-center justify-center p-4">
            <div className="absolute top-0 left-0 w-full h-full pointer-events-none overflow-hidden">
                <div className="absolute top-[-10%] right-[-10%] w-[50%] h-[50%] bg-primary/5 rounded-full blur-[120px]" />
                <div className="absolute bottom-[-10%] left-[-10%] w-[50%] h-[50%] bg-accent/10 rounded-full blur-[120px]" />
            </div>

            <motion.div
                initial={{ opacity: 0, y: 20 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ duration: 0.5 }}
                className="w-full max-w-[420px] bg-surface border border-border/50 rounded-xl p-8 md:p-12 shadow-[0_32px_64px_-16px_rgba(0,0,0,0.25)] relative z-10"
            >
                <div className="text-center mb-10">
                    <Image
                        src="/miftah-wordmark.png"
                        alt="Miftah"
                        width={180}
                        height={52}
                        className="mx-auto mb-4 object-contain"
                        priority
                    />
                    <p className="text-[13px] text-muted font-medium">{t("portalTagline")}</p>
                </div>

                {registered && (
                    <div
                        role="status"
                        className="mb-5 rounded-xl border border-success/20 bg-success/10 p-3 text-center text-xs font-bold text-success"
                    >
                        {t("registered")}
                    </div>
                )}

                {passwordSet && !registered && (
                    <div
                        role="status"
                        data-testid="login-password-set"
                        className="mb-5 rounded-xl border border-success/20 bg-success/10 p-3 text-center text-xs font-bold text-success"
                    >
                        {t("passwordSet")}
                    </div>
                )}

                <form onSubmit={handleLogin} className="space-y-5">
                    <div>
                        <label htmlFor="login-email" className="block text-[10px] font-bold text-muted uppercase tracking-widest mb-2 ms-1">{t("emailLabel")}</label>
                        <div className="relative group">
                            <Mail className="absolute left-4 top-1/2 -translate-y-1/2 w-4 h-4 text-muted group-focus-within:text-primary transition-colors" />
                            <input
                                id="login-email"
                                required
                                type="email"
                                value={email}
                                onChange={(e) => setEmail(e.target.value)}
                                placeholder="name@company.com"
                                className="w-full border border-border rounded-lg bg-surface p-3.5 pl-11 text-xs text-foreground placeholder:text-muted/50 focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all font-medium"
                            />
                        </div>
                        {fieldErrors.email && (
                            <p className="text-[10px] font-bold text-error mt-1 ml-1">{fieldErrors.email}</p>
                        )}
                    </div>

                    <div>
                        <div className="flex items-center justify-between mb-2 ml-1">
                            <label htmlFor="login-password" className="block text-[10px] font-bold text-muted uppercase tracking-widest">{t("passwordLabel")}</label>
                            <Link href="/auth/forgot-password" data-testid="login-forgot" className="text-[10px] font-bold text-accent hover:brightness-110 me-1">
                                {t("forgotLink")}
                            </Link>
                        </div>
                        <div className="relative group">
                            <Lock className="absolute left-4 top-1/2 -translate-y-1/2 w-4 h-4 text-muted group-focus-within:text-primary transition-colors" />
                            <input
                                id="login-password"
                                required
                                type={showPassword ? "text" : "password"}
                                value={password}
                                onChange={(e) => setPassword(e.target.value)}
                                placeholder="••••••••"
                                className="w-full border border-border rounded-lg bg-surface p-3.5 pl-11 pr-11 text-xs text-foreground placeholder:text-muted/50 focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all font-medium"
                            />
                            <button
                                type="button"
                                onClick={() => setShowPassword((v) => !v)}
                                aria-label={showPassword ? t("hidePassword") : t("showPassword")}
                                aria-pressed={showPassword}
                                className="absolute right-4 top-1/2 -translate-y-1/2 text-muted hover:text-foreground transition-colors focus:outline-none focus:ring-2 focus:ring-primary/20 rounded p-0.5 cursor-pointer"
                            >
                                {showPassword ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                            </button>
                        </div>
                        {fieldErrors.password && (
                            <p className="text-[10px] font-bold text-error mt-1 ml-1">{fieldErrors.password}</p>
                        )}
                    </div>

                    {error && (
                        <motion.div
                            initial={{ opacity: 0, scale: 0.95 }}
                            animate={{ opacity: 1, scale: 1 }}
                            className="bg-error/10 text-error p-3 rounded-xl text-[10px] font-bold text-center border border-error/20"
                        >
                            {error}
                        </motion.div>
                    )}

                    {tenantCandidates.length > 0 && (
                        <motion.div
                            initial={{ opacity: 0, scale: 0.95 }}
                            animate={{ opacity: 1, scale: 1 }}
                            className="bg-surface border border-border rounded-xl p-4 space-y-2"
                        >
                            <p className="text-xs font-bold text-foreground mb-2">
                                {t("pickOrganisation")}
                            </p>
                            {tenantCandidates.map((t) => (
                                <button
                                    key={t.tenantId}
                                    type="button"
                                    onClick={() => handlePickTenant(t.tenantId)}
                                    disabled={loading}
                                    className="w-full text-start bg-input hover:bg-input/70 border border-border rounded-lg px-3 py-2 text-xs font-bold text-foreground transition-colors disabled:opacity-60"
                                >
                                    {t.tenantName}
                                </button>
                            ))}
                        </motion.div>
                    )}

                    <button
                        disabled={loading}
                        className="w-full bg-accent text-accent-foreground py-4 rounded-xl text-xs font-bold uppercase tracking-[0.1em] shadow-xl shadow-accent/20 hover:brightness-110 active:scale-[0.98] transition-all flex items-center justify-center gap-2 group disabled:opacity-70 cursor-pointer disabled:cursor-not-allowed focus:outline-none focus:ring-2 focus:ring-accent/30"
                    >
                        {loading ? (
                            <Loader2 className="w-4 h-4 animate-spin text-accent-foreground" />
                        ) : (
                            <>
                                {t("signIn")}
                                <ArrowRight className="w-4 h-4 transition-transform group-hover:translate-x-1 rtl:-scale-x-100" />
                            </>
                        )}
                    </button>
                </form>

                <div className="mt-8 pt-8 border-t border-border/50 text-center">
                    <p className="text-[11px] text-muted font-medium">
                        {t("newToMiftah")}{" "}
                        <Link href="/auth/register" className="text-accent font-bold uppercase tracking-widest ms-1 hover:brightness-110">{t("createAccount")}</Link>
                    </p>
                </div>
            </motion.div>
        </div>
    );
}
