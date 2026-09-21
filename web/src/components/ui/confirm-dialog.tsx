"use client";

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
    children,
}: ConfirmDialogProps) {
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
                                disabled={isLoading}
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
