"use client";

import { motion } from "framer-motion";
import { ArrowRight } from "lucide-react";
import { Link } from "@/i18n/routing";
import { useTranslations } from "next-intl";

export default function MvpHero() {
    const t = useTranslations("Index");

    return (
        <section className="relative min-h-screen pt-32 pb-16 overflow-hidden bg-white">
            {/* Background Decorative Elements */}
            <div className="absolute top-0 left-1/2 -translate-x-1/2 w-full h-full pointer-events-none">
                <div className="absolute top-[-5%] left-[-5%] w-[35%] h-[35%] bg-primary/5 rounded-full blur-[100px] opacity-40" />
                <div className="absolute bottom-[-5%] right-[-5%] w-[35%] h-[35%] bg-primary/10 rounded-full blur-[100px] opacity-40" />
            </div>

            <div className="container relative z-10 mx-auto px-4 md:px-6">
                <div className="max-w-3xl mx-auto text-center">
                    <motion.div
                        initial={{ opacity: 0, y: 15 }}
                        animate={{ opacity: 1, y: 0 }}
                        transition={{ duration: 0.5 }}
                    >
                        <span className="inline-block px-3 py-1 mb-5 text-[10px] font-bold tracking-[0.2em] text-primary uppercase bg-primary/5 rounded-full border border-primary/10">
                            Enterprise Property Management
                        </span>
                        <h1 className="mb-6 text-3xl font-black tracking-tight text-foreground md:text-5xl lg:text-5xl leading-[1.1]">
                            {t("title")}<br />
                            <span className="text-primary italic">
                                simplified.
                            </span>
                        </h1>
                        <p className="mb-10 text-base text-gray-500 md:text-lg max-w-xl mx-auto leading-relaxed font-medium">
                            The complete Multi-Tenant Real Estate Cloud for modern Property Management.
                            Built for scale, designed for simplicity.
                        </p>

                        <div className="flex flex-col items-center justify-center gap-4 sm:flex-row">
                            <Link
                                href="/dashboard/properties"
                                className="group flex items-center justify-center px-8 py-3.5 text-[11px] font-black uppercase tracking-widest text-primary-foreground transition-all duration-200 bg-primary rounded-full hover:opacity-90 shadow-xl shadow-primary/20 active:scale-95"
                            >
                                {t("portal")}
                                <ArrowRight className="ml-2 h-3.5 w-3.5 transition-transform group-hover:translate-x-1" />
                            </Link>
                            <button className="px-8 py-3.5 text-[11px] font-black uppercase tracking-widest text-gray-500 transition-all duration-200 bg-white border border-border rounded-full hover:bg-gray-50 hover:text-foreground active:scale-95">
                                Documentation
                            </button>
                        </div>
                    </motion.div>

                    {/* Visual Elements */}
                    <motion.div
                        initial={{ opacity: 0, scale: 0.95, y: 30 }}
                        animate={{ opacity: 1, scale: 1, y: 0 }}
                        transition={{ duration: 0.8, delay: 0.2 }}
                        className="relative mt-16 max-w-5xl mx-auto"
                    >
                        <div className="relative p-1.5 bg-white border border-gray-100 rounded-[2rem] shadow-[0_20px_50px_rgba(0,0,0,0.05)] md:p-3 overflow-hidden">
                            <div className="overflow-hidden bg-gray-50 border border-gray-100 rounded-[1.5rem] h-[300px] md:h-[500px] flex items-center justify-center relative">
                                {/* Simulated Dashboard UI - Simplified and cleaner */}
                                <div className="w-full h-full p-8 flex flex-col gap-4">
                                    <div className="flex gap-1.5">
                                        <div className="w-2 h-2 rounded-full bg-red-400/60" />
                                        <div className="w-2 h-2 rounded-full bg-amber-400/60" />
                                        <div className="w-2 h-2 rounded-full bg-emerald-400/60" />
                                    </div>
                                    <div className="mt-4 grid grid-cols-4 gap-4">
                                        {[1, 2, 3, 4].map(i => (
                                            <div key={i} className="h-20 bg-white/70 rounded-xl border border-gray-100/50" />
                                        ))}
                                    </div>
                                    <div className="flex-1 mt-2 bg-white/70 rounded-xl border border-gray-100/50" />
                                </div>
                            </div>

                            {/* Floating Badges - Scaled Down */}
                            <motion.div
                                animate={{ y: [0, -8, 0] }}
                                transition={{ duration: 4, repeat: Infinity, ease: "easeInOut" }}
                                className="absolute top-10 -left-4 md:-left-8 p-3 bg-white rounded-xl shadow-[0_8px_30px_rgb(0,0,0,0.04)] border border-gray-50 flex items-center gap-2.5"
                            >
                                <div className="w-8 h-8 bg-emerald-50 text-emerald-600 rounded-lg flex items-center justify-center text-sm font-bold">✓</div>
                                <div className="text-left">
                                    <div className="text-[9px] text-gray-400 font-bold uppercase tracking-wider">Status</div>
                                    <div className="text-xs font-bold text-foreground">Live</div>
                                </div>
                            </motion.div>

                            <motion.div
                                animate={{ y: [0, 8, 0] }}
                                transition={{ duration: 5, repeat: Infinity, ease: "easeInOut", delay: 1 }}
                                className="absolute bottom-10 -right-4 md:-right-8 p-3 bg-white rounded-xl shadow-[0_8px_30px_rgb(0,0,0,0.04)] border border-gray-50 flex items-center gap-2.5"
                            >
                                <div className="w-8 h-8 bg-primary/5 text-primary rounded-lg flex items-center justify-center text-sm font-bold">⚡</div>
                                <div className="text-left">
                                    <div className="text-[9px] text-gray-400 font-bold uppercase tracking-wider">Growth</div>
                                    <div className="text-xs font-bold text-foreground">+42%</div>
                                </div>
                            </motion.div>
                        </div>
                    </motion.div>
                </div>
            </div>
        </section>
    );
}
