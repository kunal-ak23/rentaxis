"use client";

import { useState } from "react";
import { Link, useRouter } from "@/i18n/routing";
import { motion } from "framer-motion";
import { User, Mail, Lock, Building, ArrowRight, Loader2, Eye, EyeOff } from "lucide-react";
import Image from "next/image";

export default function RegisterPage() {
    const router = useRouter();
    const [formData, setFormData] = useState({
        fullName: "",
        email: "",
        password: "",
        companyName: "",
    });
    const [showPassword, setShowPassword] = useState(false);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState("");
    const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

    const handleRegister = async (e: React.FormEvent) => {
        e.preventDefault();
        setLoading(true);
        setError("");
        setFieldErrors({});

        // Basic client-side validation
        const errors: Record<string, string> = {};
        if (formData.password.length < 6) {
            errors.password = "Password must be at least 6 characters";
        }
        if (Object.keys(errors).length > 0) {
            setFieldErrors(errors);
            setLoading(false);
            return;
        }

        try {
            const res = await fetch("/api/proxy/auth/register", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(formData),
            });
            const body = await res.json().catch(() => null) as { message?: string } | null;
            if (!res.ok) {
                throw new Error(body?.message || "Registration failed. Please try again.");
            }
            router.push("/auth/login?registered=true");
        } catch (err) {
            setError(err instanceof Error ? err.message : "Registration failed. Please try again.");
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
                className="w-full max-w-[480px] bg-surface border border-border/50 rounded-xl p-8 md:p-12 shadow-[0_32px_64px_-16px_rgba(0,0,0,0.25)] relative z-10"
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
                    <h1 className="text-2xl font-bold text-foreground tracking-tight mb-2" style={{ fontFamily: 'Cinzel, serif' }}>Create Account</h1>
                    <p className="text-[13px] text-muted font-medium">Join the RentAxis Network</p>
                </div>

                <form onSubmit={handleRegister} className="space-y-4">
                    <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                        <div>
                            <label htmlFor="register-fullname" className="block text-[10px] font-bold text-muted uppercase tracking-widest mb-2 ml-1">Full Name</label>
                            <div className="relative group">
                                <User className="absolute left-4 top-1/2 -translate-y-1/2 w-4 h-4 text-muted group-focus-within:text-primary transition-colors" />
                                <input
                                    id="register-fullname"
                                    required
                                    maxLength={200}
                                    type="text"
                                    value={formData.fullName}
                                    onChange={(e) => setFormData({ ...formData, fullName: e.target.value })}
                                    placeholder="John Doe"
                                    className="w-full border border-border rounded-lg bg-surface p-3.5 pl-11 text-xs text-foreground placeholder:text-muted/50 focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all font-medium"
                                />
                            </div>
                            {fieldErrors.fullName && (
                                <p className="text-[10px] font-bold text-error mt-1 ml-1">{fieldErrors.fullName}</p>
                            )}
                        </div>
                        <div>
                            <label htmlFor="register-company" className="block text-[10px] font-bold text-muted uppercase tracking-widest mb-2 ml-1">Company Name</label>
                            <div className="relative group">
                                <Building className="absolute left-4 top-1/2 -translate-y-1/2 w-4 h-4 text-muted group-focus-within:text-primary transition-colors" />
                                <input
                                    id="register-company"
                                    required
                                    maxLength={200}
                                    type="text"
                                    value={formData.companyName}
                                    onChange={(e) => setFormData({ ...formData, companyName: e.target.value })}
                                    placeholder="Al Futtaim"
                                    className="w-full border border-border rounded-lg bg-surface p-3.5 pl-11 text-xs text-foreground placeholder:text-muted/50 focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all font-medium"
                                />
                            </div>
                            {fieldErrors.companyName && (
                                <p className="text-[10px] font-bold text-error mt-1 ml-1">{fieldErrors.companyName}</p>
                            )}
                        </div>
                    </div>

                    <div>
                        <label htmlFor="register-email" className="block text-[10px] font-bold text-muted uppercase tracking-widest mb-2 ml-1">Email Address</label>
                        <div className="relative group">
                            <Mail className="absolute left-4 top-1/2 -translate-y-1/2 w-4 h-4 text-muted group-focus-within:text-primary transition-colors" />
                            <input
                                id="register-email"
                                required
                                maxLength={254}
                                type="email"
                                value={formData.email}
                                onChange={(e) => setFormData({ ...formData, email: e.target.value })}
                                placeholder="name@company.com"
                                className="w-full border border-border rounded-lg bg-surface p-3.5 pl-11 text-xs text-foreground placeholder:text-muted/50 focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all font-medium"
                            />
                        </div>
                        {fieldErrors.email && (
                            <p className="text-[10px] font-bold text-error mt-1 ml-1">{fieldErrors.email}</p>
                        )}
                    </div>

                    <div>
                        <label htmlFor="register-password" className="block text-[10px] font-bold text-muted uppercase tracking-widest mb-2 ml-1">Password</label>
                        <div className="relative group">
                            <Lock className="absolute left-4 top-1/2 -translate-y-1/2 w-4 h-4 text-muted group-focus-within:text-primary transition-colors" />
                            <input
                                id="register-password"
                                required
                                maxLength={72}
                                type={showPassword ? "text" : "password"}
                                value={formData.password}
                                onChange={(e) => setFormData({ ...formData, password: e.target.value })}
                                placeholder="Create a strong password"
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
                        <div className="bg-error/10 text-error p-3 rounded-xl text-[10px] font-bold text-center border border-error/20">
                            {error}
                        </div>
                    )}

                    <div className="flex items-start gap-3 py-2 px-1">
                        <input id="register-terms" type="checkbox" required className="mt-1 accent-primary cursor-pointer focus:ring-2 focus:ring-primary/20" />
                        <label htmlFor="register-terms" className="text-[10px] text-muted font-medium leading-relaxed cursor-pointer">
                            I agree to the <Link href="/terms" className="text-primary font-bold">Terms of Service</Link> and <Link href="/privacy" className="text-primary font-bold">Privacy Policy</Link>.
                        </label>
                    </div>

                    <button
                        disabled={loading}
                        className="w-full bg-accent text-accent-foreground py-4 rounded-xl text-xs font-bold uppercase tracking-[0.1em] shadow-xl shadow-accent/20 hover:brightness-110 active:scale-[0.98] transition-all flex items-center justify-center gap-2 group disabled:opacity-70 mt-2 cursor-pointer disabled:cursor-not-allowed focus:outline-none focus:ring-2 focus:ring-accent/30"
                    >
                        {loading ? (
                            <Loader2 className="w-4 h-4 animate-spin text-accent-foreground" />
                        ) : (
                            <>
                                Create Organization
                                <ArrowRight className="w-4 h-4 transition-transform group-hover:translate-x-1" />
                            </>
                        )}
                    </button>
                </form>

                <div className="mt-8 pt-8 border-t border-border/50 text-center">
                    <p className="text-[11px] text-muted font-medium">
                        Already have an account?{" "}
                        <Link href="/auth/login" className="text-accent font-bold uppercase tracking-widest ml-1 hover:brightness-110">Sign In</Link>
                    </p>
                </div>
            </motion.div>
        </div>
    );
}
