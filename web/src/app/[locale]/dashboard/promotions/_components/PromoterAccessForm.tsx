"use client";

import { useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import { Loader2 } from "lucide-react";
import type {
    PromoBusinessDTO,
    PromoterAccessRequest,
    PromoterDTO,
    PromoterInviteRequest,
} from "@/types/promotion";
import { propertyLabel, type PropertyOption } from "./AdEditor";

interface PromoterAccessFormProps {
    /** The promoter whose access is being edited; null invites a new one. */
    promoter: PromoterDTO | null;
    businesses: PromoBusinessDTO[];
    properties: PropertyOption[];
    busy: boolean;
    /** The server's refusal, shown above the buttons (an email that already has a login, say). */
    error: string | null;
    onInvite: (body: PromoterInviteRequest) => void;
    onSaveAccess: (userId: string, body: PromoterAccessRequest) => void;
    onCancel: () => void;
}

// A shape check only, to catch a typo before the round trip; the server is the authority.
const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

const trimOrNull = (s: string): string | null => (s.trim() === "" ? null : s.trim());

/**
 * The form inside the "Invite promoter" and "Edit access" drawers. Access is a
 * FULL REPLACE on the server (PUT /promotions/promoters/{userId}), so the
 * checkboxes start from what the promoter already has and the whole selection
 * is sent, including ids this page's pickers do not list.
 */
export function PromoterAccessForm({
    promoter, businesses, properties, busy, error, onInvite, onSaveAccess, onCancel,
}: PromoterAccessFormProps) {
    const t = useTranslations("Promotions");
    const locale = useLocale();

    const [name, setName] = useState("");
    const [email, setEmail] = useState("");
    const [phone, setPhone] = useState("");
    const [canCreate, setCanCreate] = useState(promoter?.canCreateBusinesses ?? false);
    const [businessIds, setBusinessIds] = useState<string[]>(promoter?.businessIds ?? []);
    const [propertyIds, setPropertyIds] = useState<string[]>(promoter?.propertyIds ?? []);
    const [errors, setErrors] = useState<Record<string, string>>({});

    const toggle = (list: string[], id: string, on: boolean) =>
        on ? [...list, id] : list.filter(x => x !== id);

    function submit() {
        if (busy) return;
        if (promoter) {
            onSaveAccess(promoter.userId, { canCreateBusinesses: canCreate, businessIds, propertyIds });
            return;
        }
        const found: Record<string, string> = {};
        if (name.trim() === "") found.name = t("promoterNameRequired");
        if (!EMAIL.test(email.trim())) found.email = t("promoterEmailInvalid");
        setErrors(found);
        if (Object.keys(found).length > 0) return;
        onInvite({
            name: name.trim(),
            email: email.trim(),
            phoneNumber: trimOrNull(phone),
            canCreateBusinesses: canCreate,
            businessIds,
            propertyIds,
        });
    }

    const err = (key: string) =>
        errors[key]
            ? <p role="alert" id={`promoter-err-${key}`} className="mt-1 text-sm text-red-600">{errors[key]}</p>
            : null;
    const invalid = (key: string) =>
        errors[key] ? { "aria-invalid": true, "aria-describedby": `promoter-err-${key}` } as const : {};

    return (
        <div className="space-y-5">
            {promoter ? (
                <div className="rounded-lg bg-gray-50 px-3 py-2 text-sm">
                    <p className="font-medium">{promoter.name}</p>
                    <p className="text-gray-600" dir="ltr">{promoter.email}</p>
                </div>
            ) : (
                <div className="space-y-4">
                    <label className="block">
                        <span className="mb-1 block text-sm font-medium">{t("promoterFullName")}</span>
                        <input aria-label={t("promoterFullName")} maxLength={120} {...invalid("name")}
                            className="w-full rounded-lg border px-3 py-2"
                            value={name} onChange={e => setName(e.target.value)} />
                        {err("name")}
                    </label>
                    <label className="block">
                        <span className="mb-1 block text-sm font-medium">{t("promoterEmailLabel")}</span>
                        <input type="email" dir="ltr" aria-label={t("promoterEmailLabel")} {...invalid("email")}
                            className="w-full rounded-lg border px-3 py-2"
                            value={email} onChange={e => setEmail(e.target.value)} />
                        {err("email")}
                    </label>
                    <label className="block">
                        <span className="mb-1 block text-sm font-medium">{t("promoterPhoneOptional")}</span>
                        <input type="tel" dir="ltr" aria-label={t("promoterPhoneOptional")} placeholder="+971501234567"
                            className="w-full rounded-lg border px-3 py-2"
                            value={phone} onChange={e => setPhone(e.target.value)} />
                    </label>
                </div>
            )}

            <label className="flex items-start gap-3">
                <input type="checkbox" role="switch" aria-checked={canCreate} aria-label={t("canCreateSwitch")}
                    className="mt-1" checked={canCreate} onChange={e => setCanCreate(e.target.checked)} />
                <span>
                    <span className="block text-sm font-medium">{t("canCreateSwitch")}</span>
                    <span className="block text-xs text-gray-500">{t("canCreateHint")}</span>
                </span>
            </label>

            <fieldset>
                <legend className="mb-1 text-sm font-medium">{t("grantBusinesses")}</legend>
                {businesses.length === 0 ? (
                    <p className="text-xs text-gray-500">{t("grantBusinessesEmpty")}</p>
                ) : (
                    <div className="max-h-40 space-y-1 overflow-auto">
                        {businesses.map(b => (
                            <label key={b.id} className="flex items-center gap-2 text-sm">
                                <input type="checkbox" checked={businessIds.includes(b.id)}
                                    onChange={e => setBusinessIds(prev => toggle(prev, b.id, e.target.checked))} />
                                {locale === "ar" && b.nameAr ? b.nameAr : b.nameEn}
                            </label>
                        ))}
                    </div>
                )}
            </fieldset>

            <fieldset>
                <legend className="mb-1 text-sm font-medium">{t("grantProperties")}</legend>
                <p className="mb-2 text-xs text-gray-500">{t("grantPropertiesHint")}</p>
                {properties.length === 0 ? (
                    <p className="text-xs text-gray-500">{t("grantPropertiesEmpty")}</p>
                ) : (
                    <div className="max-h-48 space-y-1 overflow-auto">
                        {properties.map(p => (
                            <label key={p.id} className="flex items-center gap-2 text-sm">
                                <input type="checkbox" checked={propertyIds.includes(p.id)}
                                    onChange={e => setPropertyIds(prev => toggle(prev, p.id, e.target.checked))} />
                                {propertyLabel(p, locale)}
                            </label>
                        ))}
                    </div>
                )}
            </fieldset>

            {error && (
                <p role="alert" className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700">{error}</p>
            )}

            <div className="flex gap-2">
                <button type="button" onClick={submit} disabled={busy}
                    className="inline-flex items-center gap-2 rounded-lg bg-gray-900 px-4 py-2 text-white disabled:opacity-40">
                    {busy && <Loader2 size={14} className="animate-spin" aria-hidden="true" />}
                    {promoter ? t("save") : t("sendInvite")}
                </button>
                <button type="button" onClick={onCancel} disabled={busy}
                    className="rounded-lg border px-4 py-2 disabled:opacity-40">
                    {t("cancel")}
                </button>
            </div>
        </div>
    );
}
