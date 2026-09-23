"use client";

import { useState } from "react";
import { signIn } from "next-auth/react";
import { Link, useRouter } from "@/i18n/routing";
import { motion } from "framer-motion";
import { Mail, Lock, ArrowRight, Loader2, Eye, EyeOff } from "lucide-react";
import Image from "next/image";
import { useSearchParams } from "next/navigation";

const LOCKED_OUT_MESSAGES: Record<string, string> = {
    ACCOUNT_INACTIVE: "This account has been deactivated. Contact your administrator.",
    ORG_INACTIVE: "This organisation is inactive. Contact support to reactivate it.",
    RATE_LIMITED: "Too many sign-in attempts. Please wait a minute and try again.",
};

export default function LoginPage() {
    const router = useRouter();
    const registered = useSearchParams().get("registered") === "true";
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
            setError("Something went wrong. Please try again later.");
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
        const lockedOut = res?.error ? LOCKED_OUT_MESSAGES[res.error] : undefined;
        if (lockedOut) {
            setError(lockedOut);
            setLoading(false);
            return;
        }

        if (res?.error) {
            setError("Invalid email or password. Please try again.");
            setFieldErrors({ email: "Check your email", password: "Check your password" });
            setLoading(false);
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
            setError("Something went wrong. Please try again later.");
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
                        src="/logo.png"
                        alt="RentAxis"
                        width={180}
                        height={52}
                        className="mx-auto mb-4 object-contain"
                        priority
                    />
                    <p className="text-[13px] text-muted font-medium">Property Management Portal</p>
                </div>

                {registered && (
                    <div
                        role="status"
                        className="mb-5 rounded-xl border border-success/20 bg-success/10 p-3 text-center text-xs font-bold text-success"
                    >
                        Organization created successfully. Sign in with your new administrator account.
                    </div>
                )}

                <form onSubmit={handleLogin} className="space-y-5">
                    <div>
                        <label htmlFor="login-email" className="block text-[10px] font-bold text-muted uppercase tracking-widest mb-2 ml-1">Email Address</label>
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
                            <label htmlFor="login-password" className="block text-[10px] font-bold text-muted uppercase tracking-widest">Password</label>
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
                                aria-label={showPassword ? "Hide password" : "Show password"}
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
                                This email is registered in multiple organizations. Pick one:
                            </p>
                            {tenantCandidates.map((t) => (
                                <button
                                    key={t.tenantId}
                                    type="button"
                                    onClick={() => handlePickTenant(t.tenantId)}
                                    disabled={loading}
                                    className="w-full text-left bg-input hover:bg-input/70 border border-border rounded-lg px-3 py-2 text-xs font-bold text-foreground transition-colors disabled:opacity-60"
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
                                Sign In
                                <ArrowRight className="w-4 h-4 transition-transform group-hover:translate-x-1" />
                            </>
                        )}
                    </button>
                </form>

                <div className="mt-8 pt-8 border-t border-border/50 text-center">
                    <p className="text-[11px] text-muted font-medium">
                        New to RentAxis?{" "}
                        <Link href="/auth/register" className="text-accent font-bold uppercase tracking-widest ml-1 hover:brightness-110">Create Account</Link>
                    </p>
                </div>
            </motion.div>
        </div>
    );
}
