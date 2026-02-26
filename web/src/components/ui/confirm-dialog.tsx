"use client";

import { motion, AnimatePresence } from "framer-motion";

interface ConfirmDialogProps {
    isOpen: boolean;
    onClose: () => void;
    onConfirm: () => void;
    title: string;
    description?: string;
    confirmText?: string;
    cancelText?: string;
    isDestructive?: boolean;
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
                        onClick={onClose}
                    />
                    <motion.div
                        initial={{ opacity: 0, scale: 0.95, y: 10 }}
                        animate={{ opacity: 1, scale: 1, y: 0 }}
                        exit={{ opacity: 0, scale: 0.95, y: 10 }}
                        transition={{ duration: 0.2, type: "spring", bounce: 0 }}
                        className="relative w-full max-w-sm bg-white rounded-[24px] shadow-2xl flex flex-col overflow-hidden"
                    >
                        <div className="p-6">
                            <h2 className="text-lg font-black text-foreground mb-2 tracking-tight">{title}</h2>
                            {description && (
                                <p className="text-sm text-gray-500 font-medium leading-relaxed">{description}</p>
                            )}
                        </div>
                        <div className="px-6 py-4 bg-gray-50/80 border-t border-border flex justify-end gap-3">
                            <button
                                onClick={onClose}
                                className="px-4 py-2.5 text-xs font-bold uppercase tracking-wider text-gray-600 hover:text-gray-900 transition-colors bg-white border border-border rounded-xl shadow-sm hover:bg-gray-50 active:scale-95"
                            >
                                {cancelText}
                            </button>
                            <button
                                onClick={() => {
                                    onConfirm();
                                }}
                                className={`px-4 py-2.5 text-xs font-bold uppercase tracking-wider rounded-xl transition-all shadow-md active:scale-95 ${isDestructive
                                    ? "bg-red-500 text-white hover:bg-red-600 shadow-red-500/20"
                                    : "bg-primary text-primary-foreground hover:opacity-90 shadow-primary/20"
                                    }`}
                            >
                                {confirmText}
                            </button>
                        </div>
                    </motion.div>
                </div>
            )}
        </AnimatePresence>
    );
}
