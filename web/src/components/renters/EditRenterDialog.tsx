"use client";

import { useState } from "react";
import { useTranslations } from "next-intl";
import { X } from "lucide-react";
import { ApiError, throwIfNotOk } from "@/lib/api/facilities";
import { serverText } from "@/components/finance/bankrec/serverText";

/**
 * Edit tenant: prefilled fields from {@link EditableRenter}, PUT to
 * {@code /v1/renters/{id}} (`UpdateRenterDTO` on the backend — no
 * `createPortalAccount`, which only means something on create). A stale panel
 * still sends every field back (the backend's PUT is a full replace, not a
 * partial patch), so there is nothing to diff here.
 *
 * A server refusal is shown translated via `serverText` — the same
 * `Common.errors.<code>` convention bank-rec and other coded refusals use — so
 * a duplicate portal email (`renter.emailTaken`), a mismatched portal login
 * (`renter.portalUserMismatch`) or a blanked email on a tenant with a portal
 * login (`renter.portalEmailRequired`) all read in the caller's own language.
 */
export type EditableRenter = {
    id: string;
    nameEn: string;
    nameAr: string | null;
    email: string | null;
    phone: string | null;
    primaryLanguage: string | null;
};

export function EditRenterDialog({ renter, onClose, onSaved }: {
    renter: EditableRenter;
    onClose: () => void;
    onSaved: (updated: EditableRenter & Record<string, unknown>) => void;
}) {
    const t = useTranslations("MasterData");
    const tCommon = useTranslations("Common");
    const [formData, setFormData] = useState({
        nameEn: renter.nameEn ?? "",
        nameAr: renter.nameAr ?? "",
        email: renter.email ?? "",
        phone: renter.phone ?? "",
        primaryLanguage: renter.primaryLanguage ?? "EN",
    });
    const [submitting, setSubmitting] = useState(false);
    const [formError, setFormError] = useState<string | null>(null);

    const handleSubmit = async (ev: React.FormEvent) => {
        ev.preventDefault();
        if (submitting) return;
        setSubmitting(true);
        setFormError(null);
        try {
            const res = await fetch(`/api/proxy/v1/renters/${encodeURIComponent(renter.id)}`, {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(formData),
            });
            await throwIfNotOk(res);
            const data = await res.json();
            onSaved(data);
            onClose();
        } catch (err) {
            console.error(err);
            setFormError(err instanceof ApiError ? serverText(tCommon, err) : t("genericError"));
        } finally {
            setSubmitting(false);
        }
    };

    return (
        <div className="fixed inset-0 bg-foreground/40 backdrop-blur-sm flex items-center justify-center p-4 z-[100]" role="dialog" aria-modal="true">
            <div className="bg-surface rounded-xl p-8 max-w-xl w-full shadow-2xl border border-border relative">
                <button onClick={onClose} aria-label={t("close")} className="cursor-pointer absolute end-6 top-6 p-2 text-muted hover:text-foreground transition-all duration-200 focus:ring-2 focus:ring-primary/30 focus:outline-none rounded-lg">
                    <X size={18} />
                </button>
                <h2 className="text-lg font-bold mb-1">{t("editRenter")}</h2>
                <p className="text-xs text-muted mb-8 font-medium">{t("editRenterProfile")}</p>
                <form onSubmit={handleSubmit} className="grid grid-cols-2 gap-5">
                    <div className="col-span-1">
                        <label className="block text-[10px] font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ms-1">{t("nameEn")}</label>
                        <input
                            required
                            placeholder="John Doe"
                            className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none"
                            value={formData.nameEn}
                            onChange={ev => setFormData({ ...formData, nameEn: ev.target.value })}
                        />
                    </div>
                    <div className="col-span-1">
                        <label className="block text-[10px] font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ms-1">{t("nameAr")}</label>
                        <input
                            dir="rtl"
                            placeholder="جون دو"
                            className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none"
                            value={formData.nameAr}
                            onChange={ev => setFormData({ ...formData, nameAr: ev.target.value })}
                        />
                    </div>
                    <div className="col-span-1">
                        <label className="block text-[10px] font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ms-1">{t("email")}</label>
                        <input
                            type="email"
                            placeholder="john@example.com"
                            className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none"
                            value={formData.email}
                            onChange={ev => setFormData({ ...formData, email: ev.target.value })}
                        />
                    </div>
                    <div className="col-span-1">
                        <label className="block text-[10px] font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ms-1">{t("phone")}</label>
                        <input
                            placeholder="+971 50 123 4567"
                            className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none"
                            value={formData.phone}
                            onChange={ev => setFormData({ ...formData, phone: ev.target.value })}
                        />
                    </div>
                    <div className="col-span-1">
                        <label className="block text-[10px] font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ms-1">{t("preferredLanguage")}</label>
                        <select
                            className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none"
                            value={formData.primaryLanguage}
                            onChange={ev => setFormData({ ...formData, primaryLanguage: ev.target.value })}
                        >
                            <option value="EN">{t("languageEN")}</option>
                            <option value="AR">{t("languageAR")}</option>
                        </select>
                    </div>
                    {formError && (
                        <div className="col-span-2 bg-error/10 border border-error/20 text-error text-xs font-medium rounded-lg px-3 py-2" role="alert">
                            {formError}
                        </div>
                    )}
                    <div className="col-span-2 flex justify-end gap-3 mt-4">
                        <button type="button" onClick={onClose} className="cursor-pointer px-6 py-3 text-xs font-bold text-muted hover:text-foreground transition-all duration-200 focus:ring-2 focus:ring-primary/30 focus:outline-none rounded-lg">
                            {t("cancel")}
                        </button>
                        <button
                            type="submit"
                            disabled={submitting}
                            aria-busy={submitting}
                            className="cursor-pointer px-8 py-3 bg-primary text-primary-foreground rounded-xl text-xs font-bold transition-all duration-200 focus:ring-2 focus:ring-primary/30 focus:outline-none disabled:opacity-50 disabled:cursor-not-allowed"
                        >
                            {t("saveChanges")}
                        </button>
                    </div>
                </form>
            </div>
        </div>
    );
}
