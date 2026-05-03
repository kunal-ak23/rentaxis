"use client";

import { useState } from "react";
import { motion, AnimatePresence } from "framer-motion";
import { Loader2, DollarSign } from "lucide-react";
import { useTranslations } from "next-intl";

export type RecordPenaltyPaymentDialogProps = {
    penaltyId: string;
    outstanding: number;
    isOpen: boolean;
    onClose: () => void;
    onSuccess: () => void;
};

const PAYMENT_METHODS = [
    { value: "BANK_TRANSFER", labelKey: "methodBankTransfer" },
    { value: "CHEQUE", labelKey: "methodCheque" },
    { value: "CASH", labelKey: "methodCash" },
] as const;

function todayIso(): string {
    return new Date().toISOString().slice(0, 10);
}

export function RecordPenaltyPaymentDialog({
    penaltyId,
    outstanding,
    isOpen,
    onClose,
    onSuccess,
}: RecordPenaltyPaymentDialogProps) {
    const t = useTranslations("RecordPenaltyPaymentDialog");

    const [amount, setAmount] = useState<string>(String(outstanding));
    const [paymentMethod, setPaymentMethod] = useState("BANK_TRANSFER");
    const [paymentReference, setPaymentReference] = useState("");
    const [receivedAt, setReceivedAt] = useState(todayIso());
    const [notes, setNotes] = useState("");
    const [submitting, setSubmitting] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const handleClose = () => {
        if (submitting) return;
        // reset
        setAmount(String(outstanding));
        setPaymentMethod("BANK_TRANSFER");
        setPaymentReference("");
        setReceivedAt(todayIso());
        setNotes("");
        setError(null);
        onClose();
    };

    const handleSubmit = async (ev: React.FormEvent) => {
        ev.preventDefault();
        const numAmount = parseFloat(amount);
        if (isNaN(numAmount) || numAmount <= 0) {
            setError(t("errorInvalidAmount"));
            return;
        }
        setSubmitting(true);
        setError(null);
        try {
            const body: Record<string, unknown> = {
                amount: numAmount,
                paymentMethod,
                receivedAt: receivedAt || undefined,
                notes: notes.trim() || undefined,
            };
            if (paymentReference.trim()) body.paymentReference = paymentReference.trim();

            const res = await fetch(`/api/proxy/v1/penalties/${penaltyId}/payments`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(body),
            });
            if (res.ok) {
                onSuccess();
                onClose();
            } else {
                let msg: string | null = null;
                try {
                    const resBody = await res.json();
                    msg = resBody?.message || resBody?.error || null;
                } catch {}
                setError(msg || t("errorGeneric"));
            }
        } catch {
            setError(t("errorNetwork"));
        } finally {
            setSubmitting(false);
        }
    };

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
                                    <DollarSign size={16} className="text-primary shrink-0" />
                                    <h2 className="text-lg font-bold text-foreground tracking-tight">
                                        {t("title")}
                                    </h2>
                                </div>
                                <p className="text-sm text-muted font-medium leading-relaxed mt-1">
                                    {t("subtitle", { outstanding })}
                                </p>
                            </div>

                            {/* Body */}
                            <div className="px-6 space-y-4 pb-4">
                                {/* Amount */}
                                <div>
                                    <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">
                                        {t("amountLabel")} *
                                    </label>
                                    <input
                                        required
                                        type="number"
                                        min="0.01"
                                        step="0.01"
                                        value={amount}
                                        onChange={(e) => { setAmount(e.target.value); setError(null); }}
                                        className="w-full border border-border rounded-lg bg-surface px-3 py-2.5 text-xs font-medium text-foreground focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200"
                                    />
                                </div>

                                {/* Payment method */}
                                <div>
                                    <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">
                                        {t("methodLabel")} *
                                    </label>
                                    <select
                                        required
                                        value={paymentMethod}
                                        onChange={(e) => setPaymentMethod(e.target.value)}
                                        className="w-full appearance-none border border-border rounded-lg bg-surface px-3 py-2.5 text-xs font-medium text-foreground focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200"
                                    >
                                        {PAYMENT_METHODS.map((m) => (
                                            <option key={m.value} value={m.value}>
                                                {t(m.labelKey)}
                                            </option>
                                        ))}
                                    </select>
                                </div>

                                {/* Payment reference */}
                                <div>
                                    <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">
                                        {t("referenceLabel")}
                                    </label>
                                    <input
                                        type="text"
                                        value={paymentReference}
                                        onChange={(e) => setPaymentReference(e.target.value)}
                                        placeholder={t("referencePlaceholder")}
                                        className="w-full border border-border rounded-lg bg-surface px-3 py-2.5 text-xs font-medium text-foreground placeholder:text-muted/50 focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200"
                                    />
                                </div>

                                {/* Received at */}
                                <div>
                                    <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">
                                        {t("receivedAtLabel")}
                                    </label>
                                    <input
                                        type="date"
                                        value={receivedAt}
                                        onChange={(e) => setReceivedAt(e.target.value)}
                                        className="w-full border border-border rounded-lg bg-surface px-3 py-2.5 text-xs font-medium text-foreground focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200"
                                    />
                                </div>

                                {/* Notes */}
                                <div>
                                    <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">
                                        {t("notesLabel")}
                                    </label>
                                    <textarea
                                        value={notes}
                                        onChange={(e) => setNotes(e.target.value)}
                                        placeholder={t("notesPlaceholder")}
                                        rows={2}
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
                                    disabled={submitting}
                                    className="px-4 py-2.5 text-xs font-bold uppercase tracking-wider rounded-xl transition-all duration-200 shadow-md active:scale-95 cursor-pointer focus:ring-2 focus:ring-primary/20 focus:outline-none disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-2 bg-accent text-accent-foreground hover:brightness-110 shadow-accent/20"
                                >
                                    {submitting && <Loader2 size={14} className="animate-spin" />}
                                    {t("recordPayment")}
                                </button>
                            </div>
                        </form>
                    </motion.div>
                </div>
            )}
        </AnimatePresence>
    );
}
