"use client";

import { useTranslations } from "next-intl";
import {
    PROMO_REDIRECT_SOURCES,
    REDIRECT_PARAMS_MAX,
    REDIRECT_PARAM_NAME,
    REDIRECT_PARAM_VALUE_MAX,
    REDIRECT_URL_MAX,
    type PromoRedirectParam,
    type PromoRedirectParamSource,
} from "@/types/promotion";

/** One editable parameter row; `id` keeps the row's inputs steady while others are added or removed. */
export interface RedirectParamRow {
    id: number;
    name: string;
    source: PromoRedirectParamSource;
    value: string;
}

let rowSeq = 0;
export const newRedirectRow = (init: Partial<Omit<RedirectParamRow, "id">> = {}): RedirectParamRow =>
    ({ id: ++rowSeq, name: "", source: "COUPON_CODE", value: "", ...init });

export const rowsFromAd = (params: PromoRedirectParam[] | null | undefined): RedirectParamRow[] =>
    (params ?? []).map(p => newRedirectRow({ name: p.name, source: p.source, value: p.value ?? "" }));

/** The renter-data sources: the app fills these in for the tenant, after they confirm. */
export const RENTER_DATA_SOURCES: PromoRedirectParamSource[] =
    ["RENTER_NAME", "RENTER_PHONE", "RENTER_EMAIL", "PROPERTY_NAME", "UNIT_NUMBER"];

export const sharesRenterData = (params: { source: PromoRedirectParamSource }[]) =>
    params.some(p => RENTER_DATA_SOURCES.includes(p.source));

/**
 * The request's `redirectParams`: names trimmed, a value only for STATIC (the
 * server ignores and clears it for every other source, so it is not sent).
 */
export function rowsToParams(rows: RedirectParamRow[]): PromoRedirectParam[] {
    return rows.map(r => r.source === "STATIC"
        ? { name: r.name.trim(), source: r.source, value: r.value.trim() }
        : { name: r.name.trim(), source: r.source });
}

/**
 * Field errors for the redirect link, as message keys: `redirectUrl` and
 * `redirectParams` for the link and the list, `row-{id}` for one row. Mirrors
 * the server's checks (PromotionService) so a typo is a field error, not a
 * round trip; the server stays the authority. `urlProblem` is the link's own
 * https / allowlist verdict, which AdEditor shares with a WEBSITE ctaUrl.
 */
export function redirectErrors(
    url: string, rows: RedirectParamRow[], urlProblem: "https" | "domain" | null,
): Record<string, string> {
    const errors: Record<string, string> = {};
    const trimmed = url.trim();
    if (trimmed !== "") {
        if (trimmed.length > REDIRECT_URL_MAX) errors.redirectUrl = "redirectUrlTooLong";
        else if (urlProblem === "https") errors.redirectUrl = "httpsRequired";
        else if (urlProblem === "domain") errors.redirectUrl = "domainNotAllowed";
    } else if (rows.length > 0) {
        errors.redirectParams = "redirectParamsNeedUrl";
    }
    const seen = new Set<string>();
    for (const r of rows) {
        const name = r.name.trim();
        if (!REDIRECT_PARAM_NAME.test(name)) errors[`row-${r.id}`] = "redirectParamNameInvalid";
        else if (seen.has(name)) errors[`row-${r.id}`] = "redirectParamNameDuplicate";
        else if (r.source === "STATIC" && (r.value.trim() === "" || r.value.trim().length > REDIRECT_PARAM_VALUE_MAX)) {
            errors[`row-${r.id}`] = "redirectParamValueRequired";
        }
        seen.add(name);
    }
    return errors;
}

/** What the tenant would be sent, for the preview: sample values, appended as the server does. */
export function exampleRedirectUrl(base: string, pairs: [string, string][]): string {
    const url = base.trim();
    if (url === "" || pairs.length === 0) return url;
    const hash = url.indexOf("#");
    const head = hash >= 0 ? url.slice(0, hash) : url;
    const fragment = hash >= 0 ? url.slice(hash) : "";
    const sep = !head.includes("?") ? "?" : head.endsWith("?") || head.endsWith("&") ? "" : "&";
    return head + sep + pairs.map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`).join("&") + fragment;
}

/** Sample renter data for the preview; real values come from the tenant's account in the app. */
const SAMPLE: Partial<Record<PromoRedirectParamSource, string>> = {
    RENTER_NAME: "Sara Ahmed",
    RENTER_PHONE: "+971501234567",
    RENTER_EMAIL: "sara@example.test",
    UNIT_NUMBER: "A-204",
};

export function exampleFor(
    base: string, rows: RedirectParamRow[], couponCode: string, propertyName: string,
): string {
    const pairs: [string, string][] = [];
    for (const r of rows) {
        const name = r.name.trim();
        if (!REDIRECT_PARAM_NAME.test(name)) continue;
        let value: string | undefined;
        if (r.source === "STATIC") value = r.value.trim();
        else if (r.source === "COUPON_CODE") value = couponCode.trim() || "SAVE20";
        else if (r.source === "PROPERTY_NAME") value = propertyName;
        else value = SAMPLE[r.source];
        if (value) pairs.push([name, value]);
    }
    return exampleRedirectUrl(base, pairs);
}

interface RedirectLinkFieldsProps {
    url: string;
    onUrl: (v: string) => void;
    rows: RedirectParamRow[];
    onRows: (rows: RedirectParamRow[]) => void;
    allowedDomains: string[];
    errors: Record<string, string>;
    example: string;
}

/**
 * The coupon section's optional redirect: after the tenant copies the code the
 * app opens this link, with parameters whose names are chosen here and whose
 * values come from the app. Shared by the admin and promoter editors.
 */
export function RedirectLinkFields({ url, onUrl, rows, onRows, allowedDomains, errors, example }: RedirectLinkFieldsProps) {
    const t = useTranslations("Promotions");
    // `errors` arrives translated, like the rest of the editor's field errors.
    const err = (key: string) => errors[key]
        ? <p role="alert" id={`err-${key}`} className="mt-1 text-sm text-red-600">{errors[key]}</p>
        : null;
    const invalid = (key: string) =>
        errors[key] ? { "aria-invalid": true, "aria-describedby": `err-${key}` } as const : {};
    const update = (id: number, patch: Partial<RedirectParamRow>) =>
        onRows(rows.map(r => (r.id === id ? { ...r, ...patch } : r)));
    const showParams = url.trim() !== "" || rows.length > 0;

    return (
        <div className="space-y-3 rounded-lg border p-3">
            <label className="block">
                <span className="mb-1 block text-sm font-medium">{t("redirectUrl")}</span>
                <input aria-label={t("redirectUrl")} dir="ltr"
                    placeholder="https://" {...invalid("redirectUrl")}
                    className="w-full rounded-lg border px-3 py-2"
                    value={url} onChange={e => onUrl(e.target.value)} />
                <span className="mt-1 block text-xs text-gray-500">{t("redirectUrlHint")}</span>
                {allowedDomains.length > 0 && (
                    <span className="block text-xs text-gray-500" dir="ltr">{allowedDomains.join(", ")}</span>
                )}
                {err("redirectUrl")}
            </label>

            {showParams && (
                <fieldset className="space-y-2">
                    <legend className="text-sm font-medium">{t("redirectParams")}</legend>
                    <p className="text-xs text-gray-500">{t("redirectParamsHint", { max: REDIRECT_PARAMS_MAX })}</p>
                    {err("redirectParams")}
                    {rows.map((r, i) => (
                        <div key={r.id}>
                            <div className="grid gap-2 sm:grid-cols-[1fr_1fr_1fr_auto]">
                                <input aria-label={`${t("redirectParamName")} ${i + 1}`} dir="ltr" maxLength={40}
                                    {...invalid(`row-${r.id}`)}
                                    className="rounded-lg border px-3 py-2"
                                    value={r.name} onChange={e => update(r.id, { name: e.target.value })} />
                                <select aria-label={`${t("redirectParamSource")} ${i + 1}`}
                                    className="rounded-lg border px-3 py-2"
                                    value={r.source}
                                    onChange={e => update(r.id, { source: e.target.value as PromoRedirectParamSource })}>
                                    {PROMO_REDIRECT_SOURCES.map(s => (
                                        <option key={s} value={s}>{t(`redirectSource${s}`)}</option>
                                    ))}
                                </select>
                                {r.source === "STATIC" ? (
                                    <input aria-label={`${t("redirectParamValue")} ${i + 1}`} dir="ltr"
                                        maxLength={REDIRECT_PARAM_VALUE_MAX} {...invalid(`row-${r.id}`)}
                                        className="rounded-lg border px-3 py-2"
                                        value={r.value} onChange={e => update(r.id, { value: e.target.value })} />
                                ) : <span aria-hidden="true" />}
                                <button type="button" aria-label={`${t("redirectParamRemove")} ${i + 1}`}
                                    className="rounded-lg border px-3 py-2"
                                    onClick={() => onRows(rows.filter(x => x.id !== r.id))}>×</button>
                            </div>
                            {err(`row-${r.id}`)}
                        </div>
                    ))}
                    <button type="button" disabled={rows.length >= REDIRECT_PARAMS_MAX || url.trim() === ""}
                        className="rounded-lg border px-3 py-1.5 text-sm disabled:opacity-40"
                        onClick={() => onRows([...rows, newRedirectRow()])}>
                        {t("redirectParamAdd")}
                    </button>
                </fieldset>
            )}

            {sharesRenterData(rows) && (
                <p role="note" data-testid="redirect-shares-note"
                    className="rounded-lg bg-amber-50 px-3 py-2 text-xs text-amber-800">
                    {t("redirectSharesDetails")}
                </p>
            )}

            {example && (
                <div>
                    <span className="block text-xs font-medium text-gray-600">{t("redirectExample")}</span>
                    <code dir="ltr" data-testid="redirect-example"
                        className="block break-all rounded bg-gray-50 px-2 py-1 text-xs">{example}</code>
                </div>
            )}
        </div>
    );
}
