"use client";

import { useEffect, useRef } from "react";

import { motion, AnimatePresence } from "framer-motion";
import { Loader2 } from "lucide-react";

interface ConfirmDialogProps {
    isOpen: boolean;
    onClose: () => void;
    onConfirm: () => void;
    title: string;
    description?: string;
    confirmText?: string;
    cancelText?: string;
    isDestructive?: boolean;
    isLoading?: boolean;
    /**
     * A stable hook for the confirm button. The dialog is where irreversible
     * acts are actually committed, and a test (or a walkthrough) that clicks it
     * by its visible label breaks the moment the label is translated.
     */
    confirmTestId?: string;
    /**
     * Refuse the confirmation from inside the dialog. Needed where the dialog
     * itself collects a value that can be invalid — a voucher amendment's
     * reversal date, which the server refuses inside a locked period — so the
     * button says no where the reason is, rather than letting the request go and
     * rendering the server's sentence afterwards.
     */
    confirmDisabled?: boolean;
    /** Extra fields the confirmation needs — rendered under the description. */
    children?: React.ReactNode;
}

export function ConfirmDialog({
    isOpen,
    onClose,
    onConfirm,
    title,
    description,
    confirmText = "Confirm",
    cancelText = "Cancel",
    isDestructive = false,
    isLoading = false,
    confirmTestId,
    confirmDisabled = false,
    children,
}: ConfirmDialogProps) {
    const dialogRef = useRef<HTMLDivElement>(null);
    // Esc closes the dialog on top — this one when it is, never a drawer it was
    // opened from. #105 N4: Tab is trapped the same way (wraps at the first/last
    // focusable) — a confirm opened from inside a `SideDrawer` stood aside
    // correctly (its own trap bails once this dialog is topmost), but nothing
    // then stopped Tab walking out of this dialog and back into the drawer or
    // the page behind it.
    useEffect(() => {
        if (!isOpen) return;
        const isTop = () => {
            const modals = document.querySelectorAll('[aria-modal="true"]');
            return modals[modals.length - 1] === dialogRef.current;
        };
        const onKey = (e: KeyboardEvent) => {
            if (e.defaultPrevented || !isTop()) return;
            if (e.key === "Escape") {
                e.preventDefault();
                if (!isLoading) onClose();
                return;
            }
            if (e.key !== "Tab" || !dialogRef.current) return;
            const items = Array.from(
                dialogRef.current.querySelectorAll<HTMLElement>(
                    'a[href], button:not([disabled]), input:not([disabled]):not([type="hidden"]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
                ),
            );
            if (items.length === 0) return;
            const first = items[0], last = items[items.length - 1];
            const inside = dialogRef.current.contains(document.activeElement);
            if (e.shiftKey && (document.activeElement === first || !inside)) {
                e.preventDefault();
                last.focus();
            } else if (!e.shiftKey && (document.activeElement === last || !inside)) {
                e.preventDefault();
                first.focus();
            }
        };
        window.addEventListener("keydown", onKey, true);
        return () => window.removeEventListener("keydown", onKey, true);
    }, [isOpen, isLoading, onClose]);
    return (
        <AnimatePresence>
            {isOpen && (
                <div className="fixed inset-0 z-[105] flex items-center justify-center p-4">
                    <motion.div
                        initial={{ opacity: 0 }}
                        animate={{ opacity: 1 }}
                        exit={{ opacity: 0 }}
                        transition={{ duration: 0.15 }}
                        className="absolute inset-0 bg-black/20 backdrop-blur-sm"
                        onClick={isLoading ? undefined : onClose}
                    />
                    <motion.div
                        role="dialog"
                        aria-modal="true"
                        aria-label={title}
                        ref={dialogRef}
                        initial={{ opacity: 0, scale: 0.95, y: 10 }}
                        animate={{ opacity: 1, scale: 1, y: 0 }}
                        exit={{ opacity: 0, scale: 0.95, y: 10 }}
                        transition={{ duration: 0.2, type: "spring", bounce: 0 }}
                        className="relative w-full max-w-sm bg-surface rounded-xl shadow-2xl flex flex-col overflow-hidden"
                    >
                        <div className="p-6">
                            <h2 className="text-lg font-bold text-foreground mb-2 tracking-tight">{title}</h2>
                            {description && (
                                <p className="text-sm text-muted font-medium leading-relaxed">{description}</p>
                            )}
                            {children && <div className="mt-4 space-y-3">{children}</div>}
                        </div>
                        <div className="px-6 py-4 bg-input/80 border-t border-border flex justify-end gap-3">
                            <button
                                onClick={onClose}
                                disabled={isLoading}
                                className="px-4 py-2.5 text-xs font-bold uppercase tracking-wider text-foreground hover:text-foreground/80 transition-colors duration-200 bg-surface border border-border rounded-xl shadow-sm hover:bg-input active:scale-95 cursor-pointer focus:ring-2 focus:ring-primary/20 focus:outline-none disabled:opacity-50 disabled:cursor-not-allowed"
                            >
                                {cancelText}
                            </button>
                            <button
                                onClick={onConfirm}
                                data-testid={confirmTestId}
                                disabled={isLoading || confirmDisabled}
                                className={`px-4 py-2.5 text-xs font-bold uppercase tracking-wider rounded-xl transition-all duration-200 shadow-md active:scale-95 cursor-pointer focus:ring-2 focus:ring-primary/20 focus:outline-none disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-2 ${isDestructive
                                    ? "bg-error text-white hover:bg-error/90 shadow-error/20"
                                    : "bg-accent text-accent-foreground hover:brightness-110 shadow-accent/20"
                                    }`}
                            >
                                {isLoading && <Loader2 size={14} className="animate-spin" />}
                                {confirmText}
                            </button>
                        </div>
                    </motion.div>
                </div>
            )}
        </AnimatePresence>
    );
}
