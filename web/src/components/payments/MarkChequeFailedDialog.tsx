"use client";

import { useState } from "react";
import { motion, AnimatePresence } from "framer-motion";
import { Loader2, AlertTriangle } from "lucide-react";
import { useTranslations } from "next-intl";
import { formatCurrency } from "@/lib/format";

export type PenaltySummary = {
    id: string;
    penaltyType: string;
    penaltyAmount: number;
    fineGraceDays: number;
    finePerDayRate: number;
    createdAt: string;
};

export type MarkChequeFailedDialogProps = {
    paymentId: string;
    installmentNumber: number;
    amount: number | string;
    isOpen: boolean;
    onClose: () => void;
    onSuccess: (response: { schedule: unknown; penalty: PenaltySummary }) => void;
};

const FAILURE_REASONS = [
    { value: "BOUNCE", labelKey: "reasonBounce" },
    { value: "SIGNATURE_MISMATCH", labelKey: "reasonSignatureMismatch" },
    { value: "ACCOUNT_CLOSED", labelKey: "reasonAccountClosed" },
] as const;

type FailureReason = (typeof FAILURE_REASONS)[number]["value"];

export function MarkChequeFailedDialog({
    paymentId,
    installmentNumber,
    amount,
    isOpen,
    onClose,
    onSuccess,
}: MarkChequeFailedDialogProps) {
    const t = useTranslations("MarkChequeFailedDialog");

    const [reason, setReason] = useState<FailureReason | "">("");
    const [notes, setNotes] = useState("");
    const [submitting, setSubmitting] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const handleClose = () => {
        if (submitting) return;
        setReason("");
        setNotes("");
        setError(null);
        onClose();
    };

    const handleSubmit = async (ev: React.FormEvent) => {
        ev.preventDefault();
        if (!reason) return;
        setSubmitting(true);
        setError(null);
        try {
            const res = await fetch(`/api/proxy/v1/payments/${paymentId}/mark-failed`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ failureReason: reason, notes: notes.trim() || undefined }),
            });
            if (res.ok) {
                const data = await res.json();
                setReason("");
                setNotes("");
                onSuccess(data);
                onClose();
            } else {
                let msg: string | null = null;
                try {
                    const body = await res.json();
                    msg = body?.message || body?.error || null;
                } catch {}
                setError(msg || t("errorGeneric"));
            }
        } catch {
            setError(t("errorNetwork"));
        } finally {
            setSubmitting(false);
        }
    };

    const displayAmount =
        typeof amount === "number" ? formatCurrency(amount) : `${amount} AED`;

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
                        onClick={handleClose}
                    />
                    <motion.div
                        initial={{ opacity: 0, scale: 0.95, y: 10 }}
                        animate={{ opacity: 1, scale: 1, y: 0 }}
                        exit={{ opacity: 0, scale: 0.95, y: 10 }}
                        transition={{ duration: 0.2, type: "spring", bounce: 0 }}
                        className="relative w-full max-w-md bg-surface rounded-xl shadow-2xl flex flex-col overflow-hidden"
                    >
                        <form onSubmit={handleSubmit}>
                            {/* Header */}
                            <div className="p-6 pb-4">
                                <div className="flex items-center gap-2 mb-1">
                                    <AlertTriangle size={16} className="text-error shrink-0" />
                                    <h2 className="text-lg font-bold text-foreground tracking-tight">
                                        {t("title")}
                                    </h2>
                                </div>
                                <p className="text-sm text-muted font-medium leading-relaxed mt-1">
                                    {t("subtitle", {
                                        installmentNumber,
                                        amount: displayAmount,
                                    })}
                                </p>
                            </div>

                            {/* Body */}
                            <div className="px-6 space-y-4 pb-4">
                                {/* Reason dropdown */}
                                <div>
                                    <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">
                                        {t("reasonLabel")} *
                                    </label>
                                    <select
                                        required
                                        value={reason}
                                        onChange={(e) => {
                                            setReason(e.target.value as FailureReason | "");
                                            setError(null);
                                        }}
                                        className="w-full appearance-none border border-border rounded-lg bg-surface px-3 py-2.5 text-xs font-medium text-foreground focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200"
                                    >
                                        <option value="">{t("reasonPlaceholder")}</option>
                                        {FAILURE_REASONS.map((r) => (
                                            <option key={r.value} value={r.value}>
                                                {t(r.labelKey)}
                                            </option>
                                        ))}
                                    </select>
                                </div>

                                {/* Notes textarea */}
                                <div>
                                    <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">
                                        {t("notesLabel")}
                                    </label>
                                    <textarea
                                        value={notes}
                                        onChange={(e) => setNotes(e.target.value)}
                                        placeholder={t("notesPlaceholder")}
                                        rows={3}
                                        className="w-full border border-border rounded-lg bg-surface px-3 py-2 text-xs text-foreground placeholder:text-muted/50 focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none resize-none transition-all duration-200"
                                    />
                                </div>

                                {/* Inline error */}
                                {error && (
                                    <p className="text-xs text-error font-medium">{error}</p>
                                )}
                            </div>

                            {/* Footer */}
                            <div className="px-6 py-4 bg-input/80 border-t border-border flex justify-end gap-3">
                                <button
                                    type="button"
                                    onClick={handleClose}
                                    disabled={submitting}
                                    className="px-4 py-2.5 text-xs font-bold uppercase tracking-wider text-foreground hover:text-foreground/80 transition-colors duration-200 bg-surface border border-border rounded-xl shadow-sm hover:bg-input active:scale-95 cursor-pointer focus:ring-2 focus:ring-primary/20 focus:outline-none disabled:opacity-50 disabled:cursor-not-allowed"
                                >
                                    {t("cancel")}
                                </button>
                                <button
                                    type="submit"
                                    disabled={!reason || submitting}
                                    className="px-4 py-2.5 text-xs font-bold uppercase tracking-wider rounded-xl transition-all duration-200 shadow-md active:scale-95 cursor-pointer focus:ring-2 focus:ring-primary/20 focus:outline-none disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-2 bg-error text-white hover:bg-error/90 shadow-error/20"
                                >
                                    {submitting && <Loader2 size={14} className="animate-spin" />}
                                    {t("markFailed")}
                                </button>
                            </div>
                        </form>
                    </motion.div>
                </div>
            )}
        </AnimatePresence>
    );
}
