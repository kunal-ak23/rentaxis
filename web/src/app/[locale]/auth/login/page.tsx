"use client";

import { useState } from "react";
import { signIn } from "next-auth/react";
import { Link, useRouter } from "@/i18n/routing";
import { motion } from "framer-motion";
import { Mail, Lock, ArrowRight, ShieldCheck, Loader2 } from "lucide-react";
import { useTranslations } from "next-intl";

export default function LoginPage() {
    const t = useTranslations("Index"); // Reusing for common terms, or create new namespace
    const router = useRouter();
    const [email, setEmail] = useState("");
    const [password, setPassword] = useState("");
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState("");

    const handleLogin = async (e: React.FormEvent) => {
        e.preventDefault();
        setLoading(true);
        setError("");

        try {
            const res = await signIn("credentials", {
                email,
                password,
                redirect: false,
            });

            if (res?.error) {
                setError("Invalid email or password. Please try again.");
                setLoading(false);
            } else {
                // Fetch session to check role for redirect
                const { getSession } = await import("next-auth/react");
                const session = await getSession();
                const role = session?.user?.role;
                if (role === "RENTER") {
                    router.push("/dashboard/renter-portal");
                } else {
                    router.push("/dashboard/properties");
                }
            }
        } catch (err) {
            setError("Something went wrong. Please try again later.");
            setLoading(false);
        }
    };

    return (
        <div className="min-h-screen bg-[#fafafa] flex items-center justify-center p-4">
            <div className="absolute top-0 left-0 w-full h-full pointer-events-none overflow-hidden">
                <div className="absolute top-[-10%] right-[-10%] w-[50%] h-[50%] bg-primary/5 rounded-full blur-[120px]" />
                <div className="absolute bottom-[-10%] left-[-10%] w-[50%] h-[50%] bg-primary/5 rounded-full blur-[120px]" />
            </div>

            <motion.div
                initial={{ opacity: 0, y: 20 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ duration: 0.5 }}
                className="w-full max-w-[420px] bg-white border border-border/50 rounded-[2.5rem] p-8 md:p-12 shadow-[0_32px_64px_-16px_rgba(0,0,0,0.08)] relative z-10"
            >
                <div className="text-center mb-10">
                    <div className="inline-flex items-center justify-center w-12 h-12 bg-primary/5 rounded-2xl mb-6 border border-primary/10">
                        <ShieldCheck className="text-primary w-6 h-6" />
                    </div>
                    <h1 className="text-2xl font-black text-foreground tracking-tight mb-2">Welcome Back</h1>
                    <p className="text-[13px] text-gray-500 font-medium">Enterprise Lease Management Portal</p>
                </div>

                <form onSubmit={handleLogin} className="space-y-5">
                    <div>
                        <label className="block text-[10px] font-bold text-gray-400 uppercase tracking-widest mb-2 ml-1">Email Address</label>
                        <div className="relative group">
                            <Mail className="absolute left-4 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-400 group-focus-within:text-primary transition-colors" />
                            <input
                                required
                                type="email"
                                value={email}
                                onChange={(e) => setEmail(e.target.value)}
                                placeholder="name@company.com"
                                className="w-full bg-gray-50 border border-border p-3.5 pl-11 rounded-2xl text-xs placeholder:text-gray-300 focus:outline-none focus:ring-2 focus:ring-primary/10 focus:border-primary/30 transition-all font-medium"
                            />
                        </div>
                    </div>

                    <div>
                        <div className="flex items-center justify-between mb-2 ml-1">
                            <label className="block text-[10px] font-bold text-gray-400 uppercase tracking-widest">Password</label>
                        </div>
                        <div className="relative group">
                            <Lock className="absolute left-4 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-400 group-focus-within:text-primary transition-colors" />
                            <input
                                required
                                type="password"
                                value={password}
                                onChange={(e) => setPassword(e.target.value)}
                                placeholder="••••••••"
                                className="w-full bg-gray-50 border border-border p-3.5 pl-11 rounded-2xl text-xs placeholder:text-gray-300 focus:outline-none focus:ring-2 focus:ring-primary/10 focus:border-primary/30 transition-all font-medium"
                            />
                        </div>
                    </div>

                    {error && (
                        <motion.div
                            initial={{ opacity: 0, scale: 0.95 }}
                            animate={{ opacity: 1, scale: 1 }}
                            className="bg-red-50 text-red-600 p-3 rounded-xl text-[10px] font-bold text-center border border-red-100"
                        >
                            {error}
                        </motion.div>
                    )}

                    <button
                        disabled={loading}
                        className="w-full bg-primary text-primary-foreground py-4 rounded-2xl text-xs font-black uppercase tracking-[0.1em] shadow-xl shadow-primary/20 hover:opacity-90 active:scale-[0.98] transition-all flex items-center justify-center gap-2 group disabled:opacity-70"
                    >
                        {loading ? (
                            <Loader2 className="w-4 h-4 animate-spin text-primary-foreground" />
                        ) : (
                            <>
                                Sign In
                                <ArrowRight className="w-4 h-4 transition-transform group-hover:translate-x-1" />
                            </>
                        )}
                    </button>
                </form>

                <div className="mt-8 pt-8 border-t border-border/50 text-center">
                    <p className="text-[11px] text-gray-500 font-medium">
                        New to RentAxis?{" "}
                        <Link href="/auth/register" className="text-primary font-black uppercase tracking-widest ml-1 hover:opacity-80">Create Account</Link>
                    </p>
                </div>
            </motion.div>
        </div>
    );
}
