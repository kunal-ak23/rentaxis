"use client";

import { useState } from "react";
import { Link, useRouter } from "@/i18n/routing";
import { motion } from "framer-motion";
import { User, Mail, Lock, Building, ArrowRight, ShieldCheck, Loader2 } from "lucide-react";
import { useTranslations } from "next-intl";

export default function RegisterPage() {
    const t = useTranslations("Index");
    const router = useRouter();
    const [formData, setFormData] = useState({
        fullName: "",
        email: "",
        password: "",
        companyName: "",
    });
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

        // For Phase 3, we'll just simulate registration for now
        // or call an actual /api/auth/register if it exists in Spring Boot
        try {
            // Simulated 1s delay
            await new Promise(r => setTimeout(r, 1000));
            router.push("/auth/login?registered=true");
        } catch (err) {
            setError("Registration failed. Please try again.");
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
                className="w-full max-w-[480px] bg-white border border-border/50 rounded-[2.5rem] p-8 md:p-12 shadow-[0_32px_64px_-16px_rgba(0,0,0,0.08)] relative z-10"
            >
                <div className="text-center mb-10">
                    <div className="inline-flex items-center justify-center w-12 h-12 bg-primary/5 rounded-2xl mb-6 border border-primary/10">
                        <User className="text-primary w-6 h-6" />
                    </div>
                    <h1 className="text-2xl font-black text-foreground tracking-tight mb-2">Create Account</h1>
                    <p className="text-[13px] text-gray-500 font-medium">Join the RentAxis Enterprise Network</p>
                </div>

                <form onSubmit={handleRegister} className="space-y-4">
                    <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                        <div>
                            <label htmlFor="register-fullname" className="block text-[10px] font-bold text-gray-400 uppercase tracking-widest mb-2 ml-1">Full Name</label>
                            <div className="relative group">
                                <User className="absolute left-4 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-400 group-focus-within:text-primary transition-colors" />
                                <input
                                    id="register-fullname"
                                    required
                                    type="text"
                                    value={formData.fullName}
                                    onChange={(e) => setFormData({ ...formData, fullName: e.target.value })}
                                    placeholder="John Doe"
                                    className="w-full bg-gray-50 border border-border p-3.5 pl-11 rounded-2xl text-xs placeholder:text-gray-300 focus:outline-none focus:ring-2 focus:ring-primary/30 focus:border-primary/30 transition-all font-medium"
                                />
                            </div>
                            {fieldErrors.fullName && (
                                <p className="text-[10px] font-bold text-red-500 mt-1 ml-1">{fieldErrors.fullName}</p>
                            )}
                        </div>
                        <div>
                            <label htmlFor="register-company" className="block text-[10px] font-bold text-gray-400 uppercase tracking-widest mb-2 ml-1">Company Name</label>
                            <div className="relative group">
                                <Building className="absolute left-4 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-400 group-focus-within:text-primary transition-colors" />
                                <input
                                    id="register-company"
                                    required
                                    type="text"
                                    value={formData.companyName}
                                    onChange={(e) => setFormData({ ...formData, companyName: e.target.value })}
                                    placeholder="Al Futtaim"
                                    className="w-full bg-gray-50 border border-border p-3.5 pl-11 rounded-2xl text-xs placeholder:text-gray-300 focus:outline-none focus:ring-2 focus:ring-primary/30 focus:border-primary/30 transition-all font-medium"
                                />
                            </div>
                            {fieldErrors.companyName && (
                                <p className="text-[10px] font-bold text-red-500 mt-1 ml-1">{fieldErrors.companyName}</p>
                            )}
                        </div>
                    </div>

                    <div>
                        <label htmlFor="register-email" className="block text-[10px] font-bold text-gray-400 uppercase tracking-widest mb-2 ml-1">Email Address</label>
                        <div className="relative group">
                            <Mail className="absolute left-4 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-400 group-focus-within:text-primary transition-colors" />
                            <input
                                id="register-email"
                                required
                                type="email"
                                value={formData.email}
                                onChange={(e) => setFormData({ ...formData, email: e.target.value })}
                                placeholder="name@company.com"
                                className="w-full bg-gray-50 border border-border p-3.5 pl-11 rounded-2xl text-xs placeholder:text-gray-300 focus:outline-none focus:ring-2 focus:ring-primary/30 focus:border-primary/30 transition-all font-medium"
                            />
                        </div>
                        {fieldErrors.email && (
                            <p className="text-[10px] font-bold text-red-500 mt-1 ml-1">{fieldErrors.email}</p>
                        )}
                    </div>

                    <div>
                        <label htmlFor="register-password" className="block text-[10px] font-bold text-gray-400 uppercase tracking-widest mb-2 ml-1">Password</label>
                        <div className="relative group">
                            <Lock className="absolute left-4 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-400 group-focus-within:text-primary transition-colors" />
                            <input
                                id="register-password"
                                required
                                type="password"
                                value={formData.password}
                                onChange={(e) => setFormData({ ...formData, password: e.target.value })}
                                placeholder="Create a strong password"
                                className="w-full bg-gray-50 border border-border p-3.5 pl-11 rounded-2xl text-xs placeholder:text-gray-300 focus:outline-none focus:ring-2 focus:ring-primary/30 focus:border-primary/30 transition-all font-medium"
                            />
                        </div>
                        {fieldErrors.password && (
                            <p className="text-[10px] font-bold text-red-500 mt-1 ml-1">{fieldErrors.password}</p>
                        )}
                    </div>

                    {error && (
                        <div className="bg-red-50 text-red-600 p-3 rounded-xl text-[10px] font-bold text-center border border-red-100">
                            {error}
                        </div>
                    )}

                    <div className="flex items-start gap-3 py-2 px-1">
                        <input id="register-terms" type="checkbox" required className="mt-1 accent-primary cursor-pointer focus:ring-2 focus:ring-primary/30" />
                        <label htmlFor="register-terms" className="text-[10px] text-gray-500 font-medium leading-relaxed cursor-pointer">
                            I agree to the <Link href="/terms" className="text-primary font-bold">Terms of Service</Link> and <Link href="/privacy" className="text-primary font-bold">Privacy Policy</Link>.
                        </label>
                    </div>

                    <button
                        disabled={loading}
                        className="w-full bg-primary text-primary-foreground py-4 rounded-2xl text-xs font-black uppercase tracking-[0.1em] shadow-xl shadow-primary/20 hover:opacity-90 active:scale-[0.98] transition-all flex items-center justify-center gap-2 group disabled:opacity-70 mt-2 cursor-pointer disabled:cursor-not-allowed focus:outline-none focus:ring-2 focus:ring-primary/30"
                    >
                        {loading ? (
                            <Loader2 className="w-4 h-4 animate-spin text-primary-foreground" />
                        ) : (
                            <>
                                Create Organization
                                <ArrowRight className="w-4 h-4 transition-transform group-hover:translate-x-1" />
                            </>
                        )}
                    </button>
                </form>

                <div className="mt-8 pt-8 border-t border-border/50 text-center">
                    <p className="text-[11px] text-gray-500 font-medium">
                        Already have an account?{" "}
                        <Link href="/auth/login" className="text-primary font-black uppercase tracking-widest ml-1 hover:opacity-80">Sign In</Link>
                    </p>
                </div>
            </motion.div>
        </div>
    );
}
