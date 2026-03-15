"use client";

import { AnimatePresence, motion } from "framer-motion";
import { useState, useRef, useCallback } from "react";

interface SidebarTooltipProps {
    label: string;
    enabled: boolean;
    children: React.ReactNode;
}

export function SidebarTooltip({ label, enabled, children }: SidebarTooltipProps) {
    const [isVisible, setIsVisible] = useState(false);
    const [pos, setPos] = useState({ top: 0, left: 0 });
    const ref = useRef<HTMLDivElement>(null);
    const timeoutRef = useRef<ReturnType<typeof setTimeout>>(undefined);

    const show = useCallback(() => {
        if (!enabled) return;
        timeoutRef.current = setTimeout(() => {
            if (ref.current) {
                const rect = ref.current.getBoundingClientRect();
                setPos({ top: rect.top + rect.height / 2, left: rect.right + 12 });
            }
            setIsVisible(true);
        }, 80);
    }, [enabled]);

    const hide = useCallback(() => {
        clearTimeout(timeoutRef.current);
        setIsVisible(false);
    }, []);

    return (
        <div ref={ref} onMouseEnter={show} onMouseLeave={hide}>
            {children}
            <AnimatePresence>
                {isVisible && enabled && (
                    <motion.div
                        initial={{ opacity: 0, x: -4, scale: 0.96 }}
                        animate={{ opacity: 1, x: 0, scale: 1 }}
                        exit={{ opacity: 0, x: -4, scale: 0.96 }}
                        transition={{ duration: 0.15, ease: "easeOut" }}
                        className="fixed z-[9999] pointer-events-none"
                        style={{ top: pos.top, left: pos.left, transform: "translateY(-50%)" }}
                    >
                        <div className="relative px-3 py-1.5 bg-sidebar text-white text-xs font-medium rounded-lg shadow-xl border border-white/10 whitespace-nowrap">
                            {label}
                            <div className="absolute right-full top-1/2 -translate-y-1/2 border-[5px] border-transparent border-r-sidebar" />
                        </div>
                    </motion.div>
                )}
            </AnimatePresence>
        </div>
    );
}
