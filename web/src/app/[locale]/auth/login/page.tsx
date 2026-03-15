"use client";

import { useState } from "react";
import { signIn } from "next-auth/react";
import { Link, useRouter } from "@/i18n/routing";
import { motion } from "framer-motion";
import { Mail, Lock, ArrowRight, Loader2 } from "lucide-react";
import Image from "next/image";
import { useTranslations } from "next-intl";

export default function LoginPage() {
    const t = useTranslations("Index"); // Reusing for common terms, or create new namespace
    const router = useRouter();
    const [email, setEmail] = useState("");
    const [password, setPassword] = useState("");
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState("");
    const [fieldErrors, setFieldErrors] = useState<{ email?: string; password?: string }>({});

    const handleLogin = async (e: React.FormEvent) => {
        e.preventDefault();
        setLoading(true);
        setError("");
        setFieldErrors({});

        try {
            const res = await signIn("credentials", {
                email,
                password,
                redirect: false,
            });

            if (res?.error) {
                setError("Invalid email or password. Please try again.");
                setFieldErrors({ email: "Check your email", password: "Check your password" });
                setLoading(false);
            } else {
                // Fetch session to check role for redirect
                const { getSession } = await import("next-auth/react");
                const session = await getSession();
                const role = session?.user?.role;
                if (role === "RENTER") {
                    router.push("/dashboard/renter-portal");
                } else {
                    router.push("/dashboard");
                }
            }
        } catch (err) {
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
                                type="password"
                                value={password}
                                onChange={(e) => setPassword(e.target.value)}
                                placeholder="••••••••"
                                className="w-full border border-border rounded-lg bg-surface p-3.5 pl-11 text-xs text-foreground placeholder:text-muted/50 focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all font-medium"
                            />
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
