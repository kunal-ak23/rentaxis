"use client";

import { motion } from "framer-motion";
import { Link } from "@/i18n/routing";
import { useState, useEffect } from "react";
import { Menu, X } from "lucide-react";

export default function MvpHeader() {
    const [scrolled, setScrolled] = useState(false);
    const [mobileOpen, setMobileOpen] = useState(false);

    useEffect(() => {
        const onScroll = () => setScrolled(window.scrollY > 20);
        window.addEventListener("scroll", onScroll, { passive: true });
        return () => window.removeEventListener("scroll", onScroll);
    }, []);

    const navLinks = [
        { href: "#features", label: "Features" },
        { href: "#how-it-works", label: "How It Works" },
        { href: "#pricing", label: "Pricing" },
    ];

    return (
        <>
            <motion.header
                initial={{ y: -20, opacity: 0 }}
                animate={{ y: 0, opacity: 1 }}
                className="fixed top-0 left-0 right-0 z-50 flex items-center justify-center p-4"
            >
                <nav
                    className={`flex items-center gap-6 px-6 py-3 rounded-full border transition-all duration-300 ${
                        scrolled
                            ? "bg-white/90 backdrop-blur-md border-gray-200/60 shadow-lg"
                            : "bg-white/70 backdrop-blur-md border-gray-200/50 shadow-sm"
                    }`}
                >
                    <Link href="/" className="flex items-center gap-2 mr-4 cursor-pointer">
                        <div className="w-8 h-8 bg-primary rounded-lg flex items-center justify-center text-primary-foreground font-black text-xs shadow-md shadow-primary/20">
                            R
                        </div>
                        <span className="text-base font-black text-foreground tracking-tight">
                            RentAxis
                        </span>
                    </Link>

                    <div className="hidden md:flex items-center gap-6 text-[11px] font-bold uppercase tracking-wider text-gray-400">
                        {navLinks.map((link) => (
                            <a
                                key={link.href}
                                href={link.href}
                                className="hover:text-primary transition-colors cursor-pointer"
                            >
                                {link.label}
                            </a>
                        ))}
                    </div>

                    <div className="hidden md:block h-4 w-[1px] bg-border mx-2" />

                    <div className="hidden md:flex items-center gap-4">
                        <Link
                            href="/dashboard/properties"
                            className="text-[11px] font-bold text-gray-500 hover:text-primary uppercase tracking-wider transition-colors cursor-pointer"
                        >
                            Sign In
                        </Link>
                        <Link
                            href="/dashboard/properties"
                            className="px-5 py-2.5 text-[11px] font-bold text-primary-foreground bg-primary rounded-full hover:opacity-90 transition-all shadow-lg shadow-primary/10 uppercase tracking-widest cursor-pointer"
                        >
                            Get Started
                        </Link>
                    </div>

                    <button
                        className="md:hidden p-1 cursor-pointer"
                        onClick={() => setMobileOpen(!mobileOpen)}
                        aria-label="Toggle menu"
                    >
                        {mobileOpen ? <X size={18} /> : <Menu size={18} />}
                    </button>
                </nav>
            </motion.header>

            {/* Mobile menu */}
            {mobileOpen && (
                <motion.div
                    initial={{ opacity: 0, y: -10 }}
                    animate={{ opacity: 1, y: 0 }}
                    className="fixed top-20 left-4 right-4 z-50 bg-white/95 backdrop-blur-md rounded-2xl border border-gray-200/60 shadow-xl p-6 flex flex-col gap-4 md:hidden"
                >
                    {navLinks.map((link) => (
                        <a
                            key={link.href}
                            href={link.href}
                            onClick={() => setMobileOpen(false)}
                            className="text-sm font-bold text-gray-600 hover:text-primary transition-colors cursor-pointer"
                        >
                            {link.label}
                        </a>
                    ))}
                    <div className="h-[1px] bg-border" />
                    <Link
                        href="/dashboard/properties"
                        className="text-sm font-bold text-gray-500 hover:text-primary transition-colors cursor-pointer"
                    >
                        Sign In
                    </Link>
                    <Link
                        href="/dashboard/properties"
                        className="px-5 py-3 text-xs font-bold text-center text-primary-foreground bg-primary rounded-full hover:opacity-90 transition-all shadow-lg shadow-primary/10 uppercase tracking-widest cursor-pointer"
                    >
                        Get Started
                    </Link>
                </motion.div>
            )}
        </>
    );
}
