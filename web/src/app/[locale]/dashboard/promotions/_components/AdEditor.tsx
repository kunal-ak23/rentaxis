"use client";

import { useMemo, useState } from "react";
import { useTranslations } from "next-intl";
import { AdCardPreview } from "./AdCardPreview";
import {
    PROMO_CTA_TYPES,
    type PromoAdDTO,
    type PromoAdRequest,
    type PromoBusinessDTO,
    type PromoCtaType,
    type PromoPlacement,
} from "@/types/promotion";

export interface PropertyOption {
    id: string;
    nameEn: string;
}

interface AdEditorProps {
    businesses: PromoBusinessDTO[];
    properties: PropertyOption[];
    ad: PromoAdDTO | null;
    onSave: (body: PromoAdRequest) => void;
    onCancel: () => void;
}

/**
 * Mirrors PromotionUrlValidator.isAllowed so the client sees a field-level
 * error instead of a round-trip 400. The backend check is still the authority
 * — this is a convenience layer, never the security boundary.
 *
 * Two implementations of a security predicate drift. If you change the rules
 * here, change them in `PromotionUrlValidator` too, and vice versa. In
 * particular the backend rejects userinfo outright, so this must as well or
 * the form will call a URL valid that the server then refuses.
 *
 * Note `domains` arrives already normalised by the backend (it is the parsed
 * `allowedDomains` list off the business DTO), so no host surgery is needed
 * here — matching the raw string the admin typed is not this function's job.
 */
function hostIsAllowed(url: string, domains: string[]): boolean {
    // Any '@' in the authority, not just a non-empty username. WHATWG parses
    // "https://@host/" to username === "", which slipped past the old check,
    // while the backend rejects on the raw authority and 400s it.
    if (/^[a-z]+:\/\/[^/?#]*@/i.test(url.trim())) return false;
    // java.net.URI refuses a raw space where WHATWG percent-encodes it, so the
    // server would 400 a URL this function had called valid.
    if (/\s/.test(url.trim())) return false;
    let parsed: URL;
    try {
        parsed = new URL(url);
    } catch {
        return false;
    }
    if (parsed.username !== "" || parsed.password !== "") return false;
    let host = parsed.hostname.toLowerCase();
    // "host." is the FQDN form of "host". PromotionUrlValidator strips it on
    // both sides; without this the client refuses a URL the server stores, and
    // tells the admin it is off the allowlist, which is untrue.
    if (host.endsWith(".")) host = host.slice(0, -1);
    return domains.some(d => {
        const clean = d.trim().toLowerCase();
        return clean !== "" && (host === clean || host.endsWith(`.${clean}`));
    });
}

/** Asia/Dubai is a fixed +04 with no DST. */
const DUBAI_OFFSET = "+04:00";

/** Mirrors PromoAdRequest's @Pattern and the column's varchar(9). */
const HEX_COLOUR = /^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$/;

const trimOrNull = (s: string): string | null => (s.trim() === "" ? null : s.trim());

export function AdEditor({ businesses, properties, ad, onSave, onCancel }: AdEditorProps) {
    const t = useTranslations("Promotions");

    const [businessId, setBusinessId] = useState(ad?.businessId ?? businesses[0]?.id ?? "");
    const [titleEn, setTitleEn] = useState(ad?.titleEn ?? "");
    const [titleAr, setTitleAr] = useState(ad?.titleAr ?? "");
    const [subtitleEn, setSubtitleEn] = useState(ad?.subtitleEn ?? "");
    const [subtitleAr, setSubtitleAr] = useState(ad?.subtitleAr ?? "");
    const [backgroundImageUrl, setBackgroundImageUrl] = useState(ad?.backgroundImageUrl ?? "");
    const [accentColor, setAccentColor] = useState(ad?.accentColor ?? "");
    const [ctaType, setCtaType] = useState<PromoCtaType>(ad?.ctaType ?? "NONE");
    const [ctaLabelEn, setCtaLabelEn] = useState(ad?.ctaLabelEn ?? "");
    const [ctaLabelAr, setCtaLabelAr] = useState(ad?.ctaLabelAr ?? "");
    const [ctaUrl, setCtaUrl] = useState(ad?.ctaUrl ?? "");
    const [couponCode, setCouponCode] = useState(ad?.couponCode ?? "");
    const [couponTermsEn, setCouponTermsEn] = useState(ad?.couponTermsEn ?? "");
    const [couponTermsAr, setCouponTermsAr] = useState(ad?.couponTermsAr ?? "");
    const [startsAt, setStartsAt] = useState(ad?.startsAt?.slice(0, 10) ?? "");
    const [endsAt, setEndsAt] = useState(ad?.endsAt?.slice(0, 10) ?? "");
    const [priority, setPriority] = useState(ad?.priority ?? 1);
    const [placement, setPlacement] = useState<PromoPlacement>(ad?.placement ?? "HOME_AND_OFFERS");
    const [propertyIds, setPropertyIds] = useState<string[]>(ad?.propertyIds ?? []);
    const [active, setActive] = useState(ad?.active ?? true);
    const [previewAr, setPreviewAr] = useState(false);
    const [errors, setErrors] = useState<Record<string, string>>({});

    // The page can render before businesses load. AdsTab disables "Add ad" in
    // that state, but this component should not post businessId: "" if it is
    // ever mounted without one — Jackson fails the UUID bind before @NotNull runs.
    const hasBusinesses = businesses.length > 0;

    const business = useMemo(
        () => businesses.find(b => b.id === businessId) ?? null,
        [businesses, businessId],
    );

    function validate(): Record<string, string> {
        const next: Record<string, string> = {};
        if (trimOrNull(titleEn) === null && trimOrNull(titleAr) === null) {
            next.title = t("titleRequired");
        }
        // Compare the instants actually submitted, not the raw date strings.
        // Comparing "2026-09-30" to itself made a single-day campaign compare
        // equal and blocked Save, for a body the backend accepts (00:00:00 to
        // 23:59:59 is a valid window).
        if (startsAt && endsAt
            && new Date(`${endsAt}T23:59:59${DUBAI_OFFSET}`)
               <= new Date(`${startsAt}T00:00:00${DUBAI_OFFSET}`)) {
            next.endsAt = t("windowOrder");
        }
        if (ctaType === "WEBSITE") {
            const url = ctaUrl.trim();
            if (!url.toLowerCase().startsWith("https://")) {
                next.ctaUrl = t("httpsRequired");
            } else if (!hostIsAllowed(url, business?.allowedDomains ?? [])) {
                next.ctaUrl = t("domainNotAllowed");
            }
        }
        if (ctaType === "COUPON" && trimOrNull(couponCode) === null) {
            next.couponCode = t("couponRequired");
        }
        if (ctaType === "CALL" && !business?.phoneE164) {
            next.ctaType = t("businessHasNoPhone");
        }
        if (ctaType === "WHATSAPP" && !business?.whatsappE164) {
            next.ctaType = t("businessHasNoWhatsapp");
        }
        // Checked here so a typo is a field error rather than a round trip:
        // the backend validates this twice, at @Pattern and again in applyAd.
        if (accentColor.trim() !== "" && !HEX_COLOUR.test(accentColor.trim())) {
            next.accentColor = t("accentColorInvalid");
        }
        return next;
    }

    function submit() {
        if (!hasBusinesses) return;
        const found = validate();
        setErrors(found);
        if (Object.keys(found).length > 0) return;

        // Fields belonging to other CTA types are dropped, never carried over —
        // the backend clears them too, and the preview must agree with both.
        onSave({
            businessId,
            titleEn: trimOrNull(titleEn),
            titleAr: trimOrNull(titleAr),
            subtitleEn: trimOrNull(subtitleEn),
            subtitleAr: trimOrNull(subtitleAr),
            backgroundImageUrl: trimOrNull(backgroundImageUrl),
            accentColor: trimOrNull(accentColor),
            ctaType,
            ctaLabelEn: trimOrNull(ctaLabelEn),
            ctaLabelAr: trimOrNull(ctaLabelAr),
            ctaUrl: ctaType === "WEBSITE" ? trimOrNull(ctaUrl) : null,
            couponCode: ctaType === "COUPON" ? trimOrNull(couponCode) : null,
            couponTermsEn: ctaType === "COUPON" ? trimOrNull(couponTermsEn) : null,
            couponTermsAr: ctaType === "COUPON" ? trimOrNull(couponTermsAr) : null,
            // Anchored to +04, not Z. The window is compared against absolute
            // instants server-side, so a UTC anchor makes "ends 30 September"
            // actually stop at 04:00 on 1 October Dubai time — a Ramadan or
            // weekend offer visibly outliving its stated end date — and makes
            // "starts 1 September" dark for the first four hours of its own
            // start day. Asia/Dubai is a fixed +04 with no DST, so a literal
            // offset is correct and needs no tz library.
            startsAt: startsAt ? new Date(`${startsAt}T00:00:00${DUBAI_OFFSET}`).toISOString() : null,
            endsAt: endsAt ? new Date(`${endsAt}T23:59:59${DUBAI_OFFSET}`).toISOString() : null,
            priority,
            placement,
            propertyIds,
            active,
        });
    }

    // role="alert" so a screen reader announces a failed save instead of the
    // user clicking Save and hearing nothing.
    const err = (key: string) =>
        errors[key]
            ? <p role="alert" id={`err-${key}`} className="mt-1 text-sm text-red-600">{errors[key]}</p>
            : null;
    const invalid = (key: string) =>
        errors[key] ? { "aria-invalid": true, "aria-describedby": `err-${key}` } as const : {};

    return (
        <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_320px]">
            <div className="space-y-4">
                <label className="block">
                    <span className="mb-1 block text-sm font-medium">{t("business")}</span>
                    <select
                        aria-label={t("business")}
                        className="w-full rounded-lg border px-3 py-2"
                        value={businessId}
                        onChange={e => setBusinessId(e.target.value)}
                    >
                        {businesses.map(b => (
                            <option key={b.id} value={b.id}>{b.nameEn}</option>
                        ))}
                    </select>
                </label>

                <div className="grid gap-4 sm:grid-cols-2">
                    <label className="block">
                        <span className="mb-1 block text-sm font-medium">{t("adTitleEn")}</span>
                        <input aria-label={t("adTitleEn")} maxLength={120} className="w-full rounded-lg border px-3 py-2"
                            value={titleEn} onChange={e => setTitleEn(e.target.value)} />
                    </label>
                    <label className="block">
                        <span className="mb-1 block text-sm font-medium">{t("adTitleAr")}</span>
                        <input aria-label={t("adTitleAr")} maxLength={120} dir="rtl" className="w-full rounded-lg border px-3 py-2"
                            value={titleAr} onChange={e => setTitleAr(e.target.value)} />
                    </label>
                    <label className="block">
                        <span className="mb-1 block text-sm font-medium">{t("subtitleEn")}</span>
                        <input aria-label={t("subtitleEn")} maxLength={160} className="w-full rounded-lg border px-3 py-2"
                            value={subtitleEn} onChange={e => setSubtitleEn(e.target.value)} />
                    </label>
                    <label className="block">
                        <span className="mb-1 block text-sm font-medium">{t("subtitleAr")}</span>
                        <input aria-label={t("subtitleAr")} maxLength={160} dir="rtl" className="w-full rounded-lg border px-3 py-2"
                            value={subtitleAr} onChange={e => setSubtitleAr(e.target.value)} />
                    </label>
                </div>
                {err("title")}

                <div className="grid gap-4 sm:grid-cols-2">
                    <label className="block">
                        <span className="mb-1 block text-sm font-medium">{t("artwork")}</span>
                        <input aria-label={t("artwork")} className="w-full rounded-lg border px-3 py-2"
                            value={backgroundImageUrl}
                            onChange={e => setBackgroundImageUrl(e.target.value)} />
                    </label>
                    <label className="block">
                        <span className="mb-1 block text-sm font-medium">{t("accentColor")}</span>
                        <input aria-label={t("accentColor")} placeholder="#FBF3E2" maxLength={9}
                            {...invalid("accentColor")}
                            className="w-full rounded-lg border px-3 py-2"
                            value={accentColor} onChange={e => setAccentColor(e.target.value)} />
                        {err("accentColor")}
                    </label>
                </div>

                <label className="block">
                    <span className="mb-1 block text-sm font-medium">{t("ctaType")}</span>
                    <select
                        aria-label={t("ctaType")}
                        className="w-full rounded-lg border px-3 py-2"
                        value={ctaType}
                        onChange={e => setCtaType(e.target.value as PromoCtaType)}
                    >
                        {PROMO_CTA_TYPES.map(type => (
                            <option key={type} value={type}>{t(`ctaType${type}`)}</option>
                        ))}
                    </select>
                </label>
                {err("ctaType")}

                {ctaType === "WEBSITE" && (
                    <label className="block">
                        <span className="mb-1 block text-sm font-medium">{t("ctaUrl")}</span>
                        <input aria-label={t("ctaUrl")} maxLength={1024} {...invalid("ctaUrl")}
                            className="w-full rounded-lg border px-3 py-2"
                            value={ctaUrl} onChange={e => setCtaUrl(e.target.value)} />
                        <span className="mt-1 block text-xs text-gray-500">
                            {(business?.allowedDomains ?? []).join(", ")}
                        </span>
                        {err("ctaUrl")}
                    </label>
                )}

                {ctaType === "COUPON" && (
                    <div className="space-y-4">
                        <label className="block">
                            <span className="mb-1 block text-sm font-medium">{t("couponCode")}</span>
                            <input aria-label={t("couponCode")} maxLength={64} {...invalid("couponCode")}
                                className="w-full rounded-lg border px-3 py-2"
                                value={couponCode} onChange={e => setCouponCode(e.target.value)} />
                            {err("couponCode")}
                        </label>
                        <div className="grid gap-4 sm:grid-cols-2">
                            <label className="block">
                                <span className="mb-1 block text-sm font-medium">{t("couponTermsEn")}</span>
                                <textarea aria-label={t("couponTermsEn")} className="w-full rounded-lg border px-3 py-2"
                                    value={couponTermsEn} onChange={e => setCouponTermsEn(e.target.value)} />
                            </label>
                            <label className="block">
                                <span className="mb-1 block text-sm font-medium">{t("couponTermsAr")}</span>
                                <textarea aria-label={t("couponTermsAr")} dir="rtl" className="w-full rounded-lg border px-3 py-2"
                                    value={couponTermsAr} onChange={e => setCouponTermsAr(e.target.value)} />
                            </label>
                        </div>
                    </div>
                )}

                {(ctaType === "CALL" || ctaType === "WHATSAPP") && (
                    <p className="rounded-lg bg-gray-50 px-3 py-2 text-sm text-gray-600">
                        {ctaType === "CALL"
                            ? `${t("phone")}: ${business?.phoneE164 ?? "—"}`
                            : `${t("whatsapp")}: ${business?.whatsappE164 ?? "—"}`}
                    </p>
                )}

                <div className="grid gap-4 sm:grid-cols-2">
                    <label className="block">
                        <span className="mb-1 block text-sm font-medium">{t("startsAt")}</span>
                        <input type="date" aria-label={t("startsAt")} className="w-full rounded-lg border px-3 py-2"
                            value={startsAt} onChange={e => setStartsAt(e.target.value)} />
                    </label>
                    <label className="block">
                        <span className="mb-1 block text-sm font-medium">{t("endsAt")}</span>
                        <input type="date" aria-label={t("endsAt")} className="w-full rounded-lg border px-3 py-2"
                            value={endsAt} onChange={e => setEndsAt(e.target.value)} />
                        {err("endsAt")}
                    </label>
                </div>

                <label className="block">
                    <span className="mb-1 block text-sm font-medium">
                        {t("priority")} — {priority}
                    </span>
                    <input type="range" min={1} max={10} step={1} aria-label={t("priority")}
                        className="w-full" value={priority}
                        onChange={e => setPriority(Number(e.target.value))} />
                    <span className="text-xs text-gray-500">{t("priorityHint")}</span>
                </label>

                <label className="block">
                    <span className="mb-1 block text-sm font-medium">{t("placement")}</span>
                    <select aria-label={t("placement")} className="w-full rounded-lg border px-3 py-2"
                        value={placement}
                        onChange={e => setPlacement(e.target.value as PromoPlacement)}>
                        <option value="HOME_AND_OFFERS">{t("placementHOME_AND_OFFERS")}</option>
                        <option value="OFFERS_ONLY">{t("placementOFFERS_ONLY")}</option>
                    </select>
                </label>

                <fieldset>
                    <legend className="mb-1 text-sm font-medium">{t("targeting")}</legend>
                    <p className="mb-2 text-xs text-gray-500">
                        {propertyIds.length === 0 ? t("targetingAll") : t("targetingSome")}
                    </p>
                    <div className="max-h-40 space-y-1 overflow-auto">
                        {properties.map(p => (
                            <label key={p.id} className="flex items-center gap-2 text-sm">
                                <input
                                    type="checkbox"
                                    checked={propertyIds.includes(p.id)}
                                    onChange={e => setPropertyIds(prev =>
                                        e.target.checked
                                            ? [...prev, p.id]
                                            : prev.filter(id => id !== p.id))}
                                />
                                {p.nameEn}
                            </label>
                        ))}
                    </div>
                </fieldset>

                <label className="flex items-center gap-2 text-sm">
                    <input type="checkbox" checked={active}
                        onChange={e => setActive(e.target.checked)} />
                    {t("active")}
                </label>

                <div className="flex gap-2 pt-2">
                    <button type="button" onClick={submit} disabled={!hasBusinesses}
                        className="rounded-lg bg-gray-900 px-4 py-2 text-white disabled:opacity-40">
                        {t("save")}
                    </button>
                    <button type="button" onClick={onCancel}
                        className="rounded-lg border px-4 py-2">
                        {t("cancel")}
                    </button>
                </div>
            </div>

            <aside className="space-y-3">
                <div className="flex items-center gap-2">
                    <span className="text-sm font-medium">{t("preview")}</span>
                    <button type="button" aria-pressed={!previewAr} onClick={() => setPreviewAr(false)}
                        className={`rounded-md border px-2 py-1 text-xs ${previewAr ? "" : "bg-gray-900 text-white"}`}>
                        {t("previewEn")}
                    </button>
                    <button type="button" aria-pressed={previewAr} onClick={() => setPreviewAr(true)}
                        className={`rounded-md border px-2 py-1 text-xs ${previewAr ? "bg-gray-900 text-white" : ""}`}>
                        {t("previewAr")}
                    </button>
                </div>
                <div className="rounded-2xl bg-[#F6F5FA] p-4">
                    <AdCardPreview
                        title={previewAr ? (titleAr || titleEn) : (titleEn || titleAr)}
                        subtitle={previewAr ? (subtitleAr || subtitleEn) : (subtitleEn || subtitleAr)}
                        businessName={previewAr ? (business?.nameAr ?? business?.nameEn) : business?.nameEn}
                        backgroundImageUrl={backgroundImageUrl || null}
                        accentColor={accentColor || null}
                        ctaType={ctaType}
                        ctaLabel={previewAr ? (ctaLabelAr || ctaLabelEn) : (ctaLabelEn || ctaLabelAr)}
                        rtl={previewAr}
                    />
                </div>
                <div className="grid gap-4 sm:grid-cols-2">
                    <label className="block">
                        <span className="mb-1 block text-sm font-medium">{t("ctaLabelEn")}</span>
                        <input aria-label={t("ctaLabelEn")} maxLength={40} className="w-full rounded-lg border px-3 py-2"
                            value={ctaLabelEn} onChange={e => setCtaLabelEn(e.target.value)} />
                    </label>
                    <label className="block">
                        <span className="mb-1 block text-sm font-medium">{t("ctaLabelAr")}</span>
                        <input aria-label={t("ctaLabelAr")} maxLength={40} dir="rtl" className="w-full rounded-lg border px-3 py-2"
                            value={ctaLabelAr} onChange={e => setCtaLabelAr(e.target.value)} />
                    </label>
                </div>
            </aside>
        </div>
    );
}
