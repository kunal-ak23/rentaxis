"use client";

import { motion } from "framer-motion";
import { Link } from "@/i18n/routing";
import { Globe } from "lucide-react";

export default function MvpHeader() {
    return (
        <motion.header
            initial={{ y: -20, opacity: 0 }}
            animate={{ y: 0, opacity: 1 }}
            className="fixed top-0 left-0 right-0 z-50 flex items-center justify-center p-4"
        >
            <nav className="flex items-center gap-6 px-6 py-3 bg-white/70 backdrop-blur-md rounded-full border border-gray-200/50 shadow-sm transition-all hover:shadow-md">
                <Link href="/" className="flex items-center gap-2 mr-4">
                    <div className="w-8 h-8 bg-primary rounded-lg flex items-center justify-center text-primary-foreground font-black text-xs shadow-md shadow-primary/20">
                        R
                    </div>
                    <span className="text-base font-black text-foreground tracking-tight">
                        RentAxis
                    </span>
                </Link>

                <div className="hidden md:flex items-center gap-6 text-[11px] font-bold uppercase tracking-wider text-gray-400">
                    <Link href="#features" className="hover:text-primary transition-colors">Features</Link>
                    <Link href="#solutions" className="hover:text-primary transition-colors">Solutions</Link>
                    <Link href="#pricing" className="hover:text-primary transition-colors">Pricing</Link>
                </div>

                <div className="h-4 w-[1px] bg-border mx-2" />

                <div className="flex items-center gap-4">
                    <Link
                        href="/dashboard/properties"
                        className="text-[11px] font-bold text-gray-500 hover:text-primary uppercase tracking-wider transition-colors"
                    >
                        Sign In
                    </Link>
                    <Link
                        href="/dashboard/properties"
                        className="px-5 py-2.5 text-[11px] font-bold text-primary-foreground bg-primary rounded-full hover:opacity-90 transition-all shadow-lg shadow-primary/10 uppercase tracking-widest"
                    >
                        Get Started
                    </Link>
                </div>
            </nav>
        </motion.header>
    );
}
