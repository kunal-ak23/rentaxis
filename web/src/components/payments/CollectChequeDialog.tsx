"use client";

import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { X, Loader2 } from "lucide-react";
import { cn } from "@/lib/utils";
import ChequeScanner from "@/components/cheques/ChequeScanner";

type Mode = "collect" | "replace";

type ChequeForm = {
  chequeNumber: string;
  bankName: string;
  payerName: string;
  chequeDate: string;
  chequeImageUrl: string;
  chequeImageBlobPath: string;
  chequeImageUploadedAt: string;
};

const emptyForm: ChequeForm = {
  chequeNumber: "",
  bankName: "",
  payerName: "",
  chequeDate: "",
  chequeImageUrl: "",
  chequeImageBlobPath: "",
  chequeImageUploadedAt: "",
};

type Props = {
  open: boolean;
  paymentId: string | null;
  /** "collect" hits PUT /payments/{id}/collect, "replace" hits POST /payments/{id}/replace */
  mode: Mode;
  onClose: () => void;
  onSuccess: () => void;
};

/**
 * Shared collect/replace cheque dialog. Used by:
 *  - Finance → Payments page (collect from PENDING, replace from BOUNCED)
 *  - Lease detail page → Payment schedule tab (collect from PENDING)
 *
 * Includes the ChequeScanner so users can auto-fill from a photo.
 */
export function CollectChequeDialog({ open, paymentId, mode, onClose, onSuccess }: Props) {
  const t = useTranslations("Payments");
  const [form, setForm] = useState<ChequeForm>(emptyForm);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (open) {
      setForm(emptyForm);
      setError(null);
    }
  }, [open, paymentId]);

  if (!open || !paymentId) return null;

  const submit = async (ev: React.FormEvent) => {
    ev.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      const url = mode === "replace"
        ? `/api/proxy/v1/payments/${paymentId}/replace`
        : `/api/proxy/v1/payments/${paymentId}/collect`;
      const method = mode === "replace" ? "POST" : "PUT";
      const res = await fetch(url, {
        method,
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(form),
      });
      if (res.ok) {
        onSuccess();
        onClose();
        return;
      }
      // Surface the backend's reason if available; fall back to a generic
      // message with the status code so the user knows something failed.
      let detail: string | null = null;
      try {
        const data = await res.json();
        detail = data?.message || data?.error || null;
      } catch { /* response wasn't JSON */ }
      setError(detail || `Request failed (${res.status})`);
    } catch (err) {
      console.error(err);
      setError(err instanceof Error ? err.message : "Network error");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="fixed inset-0 bg-gray-900/40 backdrop-blur-sm flex items-center justify-center p-4 z-[100]">
      <div className="bg-surface rounded-xl p-8 max-w-md w-full shadow-2xl border border-border relative">
        <button
          onClick={onClose}
          aria-label="Close modal"
          className="absolute right-6 top-6 p-2 text-muted hover:text-foreground cursor-pointer transition-all duration-200 focus:ring-2 focus:ring-primary/20 focus:outline-none rounded-lg"
        >
          <X size={18} />
        </button>
        <h2 className="text-lg font-bold mb-1">
          {mode === "replace" ? t("replacementCheque") : t("chequeDetails")}
        </h2>
        <p className="text-xs text-muted mb-8 font-medium">{t("enterChequeDetails")}</p>

        <form onSubmit={submit} className="space-y-5">
          <div>
            <div className="mb-3">
              <ChequeScanner
                onExtracted={(data) =>
                  setForm((prev) => ({
                    ...prev,
                    chequeNumber: data.chequeNumber ?? prev.chequeNumber,
                    bankName: data.bankName ?? prev.bankName,
                    payerName: data.payerName ?? prev.payerName,
                    chequeDate: data.chequeDate ?? prev.chequeDate,
                    chequeImageUrl: data.imageUrl,
                    chequeImageBlobPath: data.imageBlobPath,
                    chequeImageUploadedAt: data.imageUploadedAt,
                  }))
                }
              />
            </div>
            <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ms-1">
              {t("chequeNumber")}
            </label>
            <input
              required
              placeholder="CHQ-000001"
              className="w-full border border-border rounded-lg bg-surface p-3 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200"
              value={form.chequeNumber}
              onChange={(ev) => setForm({ ...form, chequeNumber: ev.target.value })}
            />
          </div>

          <div>
            <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ms-1">
              {t("bankName")}
            </label>
            <input
              required
              placeholder="Emirates NBD"
              className="w-full border border-border rounded-lg bg-surface p-3 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200"
              value={form.bankName}
              onChange={(ev) => setForm({ ...form, bankName: ev.target.value })}
            />
          </div>

          <div>
            <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ms-1">
              {t("payerName")}
            </label>
            <input
              required
              placeholder="John Doe"
              className="w-full border border-border rounded-lg bg-surface p-3 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200"
              value={form.payerName}
              onChange={(ev) => setForm({ ...form, payerName: ev.target.value })}
            />
          </div>

          <div>
            <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ms-1">
              {t("chequeDate")}
            </label>
            <input
              required
              type="date"
              className="w-full border border-border rounded-lg bg-surface p-3 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200"
              value={form.chequeDate}
              onChange={(ev) => setForm({ ...form, chequeDate: ev.target.value })}
            />
          </div>

          {error && (
            <div className="rounded-lg border border-error/30 bg-error/5 px-3 py-2 text-xs text-error">
              {error}
            </div>
          )}

          <div className="flex justify-end gap-3 mt-6">
            <button
              type="button"
              onClick={onClose}
              className="px-6 py-3 text-xs font-bold text-muted cursor-pointer transition-all duration-200 focus:ring-2 focus:ring-primary/20 focus:outline-none rounded-lg"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={submitting}
              className={cn(
                "px-8 py-3 rounded-lg text-xs font-bold text-white cursor-pointer transition-all duration-200 focus:ring-2 focus:ring-primary/20 focus:outline-none disabled:opacity-50 flex items-center gap-2",
                mode === "replace" ? "bg-purple-600 hover:bg-purple-700" : "bg-primary hover:opacity-90"
              )}
            >
              {submitting && <Loader2 size={14} className="animate-spin" />}
              {mode === "replace" ? t("replace") : t("collect")}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
