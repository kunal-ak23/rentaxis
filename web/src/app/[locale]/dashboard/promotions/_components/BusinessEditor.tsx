"use client";

import { useState } from "react";
import { useTranslations } from "next-intl";
import {
    PROMO_CATEGORIES,
    type PromoBusinessDTO,
    type PromoBusinessRequest,
    type PromoCategory,
} from "@/types/promotion";

interface BusinessEditorProps {
    business: PromoBusinessDTO | null;
    onSave: (body: PromoBusinessRequest) => void;
    onCancel: () => void;
}

/**
 * Turns whatever the client pastes into a bare hostname. The same
 * normalisation runs server-side in PromotionUrlValidator.parseDomains — this
 * copy exists so the chip shows the real stored value immediately, not so the
 * client can be trusted.
 */
function toHost(value: string): string {
    let s = value.trim().toLowerCase();
    const scheme = s.indexOf("://");
    if (scheme >= 0) s = s.slice(scheme + 3);
    const at = s.indexOf("@");
    if (at >= 0) s = s.slice(at + 1);
    const cut = ["/", "?", "#", ":"]
        .map(c => s.indexOf(c))
        .filter(i => i >= 0)
        .reduce((min, i) => Math.min(min, i), s.length);
    return s.slice(0, cut);
}

const trimOrNull = (s: string): string | null => (s.trim() === "" ? null : s.trim());

export function BusinessEditor({ business, onSave, onCancel }: BusinessEditorProps) {
    const t = useTranslations("Promotions");

    const [nameEn, setNameEn] = useState(business?.nameEn ?? "");
    const [nameAr, setNameAr] = useState(business?.nameAr ?? "");
    const [logoUrl, setLogoUrl] = useState(business?.logoUrl ?? "");
    const [category, setCategory] = useState<PromoCategory>(business?.category ?? "OTHER");
    const [phoneE164, setPhoneE164] = useState(business?.phoneE164 ?? "");
    const [whatsappE164, setWhatsappE164] = useState(business?.whatsappE164 ?? "");
    const [domains, setDomains] = useState<string[]>(business?.allowedDomains ?? []);
    const [domainDraft, setDomainDraft] = useState("");
    const [active, setActive] = useState(business?.active ?? true);
    const [error, setError] = useState<string | null>(null);

    function addDomain() {
        const host = toHost(domainDraft);
        if (host === "" || domains.includes(host)) {
            setDomainDraft("");
            return;
        }
        setDomains([...domains, host]);
        setDomainDraft("");
    }

    function submit() {
        if (trimOrNull(nameEn) === null) {
            setError(t("saveError"));
            return;
        }
        setError(null);
        onSave({
            nameEn: nameEn.trim(),
            nameAr: trimOrNull(nameAr),
            logoUrl: trimOrNull(logoUrl),
            category,
            phoneE164: trimOrNull(phoneE164),
            whatsappE164: trimOrNull(whatsappE164),
            allowedDomains: domains,
            active,
        });
    }

    return (
        <div className="space-y-4">
            <div className="grid gap-4 sm:grid-cols-2">
                <label className="block">
                    <span className="mb-1 block text-sm font-medium">{t("nameEn")}</span>
                    <input aria-label={t("nameEn")} className="w-full rounded-lg border px-3 py-2"
                        value={nameEn} onChange={e => setNameEn(e.target.value)} />
                </label>
                <label className="block">
                    <span className="mb-1 block text-sm font-medium">{t("nameAr")}</span>
                    <input aria-label={t("nameAr")} dir="rtl" className="w-full rounded-lg border px-3 py-2"
                        value={nameAr} onChange={e => setNameAr(e.target.value)} />
                </label>
                <label className="block">
                    <span className="mb-1 block text-sm font-medium">{t("category")}</span>
                    <select aria-label={t("category")} className="w-full rounded-lg border px-3 py-2"
                        value={category}
                        onChange={e => setCategory(e.target.value as PromoCategory)}>
                        {PROMO_CATEGORIES.map(c => <option key={c} value={c}>{c}</option>)}
                    </select>
                </label>
                <label className="block">
                    <span className="mb-1 block text-sm font-medium">{t("logo")}</span>
                    <input aria-label={t("logo")} className="w-full rounded-lg border px-3 py-2"
                        value={logoUrl} onChange={e => setLogoUrl(e.target.value)} />
                </label>
                <label className="block">
                    <span className="mb-1 block text-sm font-medium">{t("phone")}</span>
                    <input aria-label={t("phone")} placeholder="+971501234567"
                        className="w-full rounded-lg border px-3 py-2"
                        value={phoneE164} onChange={e => setPhoneE164(e.target.value)} />
                </label>
                <label className="block">
                    <span className="mb-1 block text-sm font-medium">{t("whatsapp")}</span>
                    <input aria-label={t("whatsapp")} placeholder="+971501234567"
                        className="w-full rounded-lg border px-3 py-2"
                        value={whatsappE164} onChange={e => setWhatsappE164(e.target.value)} />
                </label>
            </div>

            <div>
                <span className="mb-1 block text-sm font-medium">{t("allowedDomains")}</span>
                <div className="mb-2 flex flex-wrap gap-2">
                    {domains.map(d => (
                        <span key={d} className="flex items-center gap-1 rounded-full bg-gray-100 px-3 py-1 text-sm">
                            {d}
                            <button type="button" aria-label={`${t("delete")} ${d}`}
                                onClick={() => setDomains(domains.filter(x => x !== d))}>×</button>
                        </span>
                    ))}
                </div>
                <div className="flex gap-2">
                    <input aria-label={t("allowedDomains")} className="flex-1 rounded-lg border px-3 py-2"
                        value={domainDraft}
                        onChange={e => setDomainDraft(e.target.value)}
                        onKeyDown={e => { if (e.key === "Enter") { e.preventDefault(); addDomain(); } }} />
                    <button type="button" className="rounded-lg border px-3 py-2" onClick={addDomain}>+</button>
                </div>
                <p className="mt-1 text-xs text-gray-500">{t("allowedDomainsHint")}</p>
            </div>

            <label className="flex items-center gap-2 text-sm">
                <input type="checkbox" checked={active} onChange={e => setActive(e.target.checked)} />
                {t("active")}
            </label>

            {error && <p className="text-sm text-red-600">{error}</p>}

            <div className="flex gap-2">
                <button type="button" onClick={submit}
                    className="rounded-lg bg-gray-900 px-4 py-2 text-white">{t("save")}</button>
                <button type="button" onClick={onCancel}
                    className="rounded-lg border px-4 py-2">{t("cancel")}</button>
            </div>
        </div>
    );
}
