"use client";

import { useTranslations } from "next-intl";
import {
    PROMO_REDIRECT_SOURCES,
    REDIRECT_PARAMS_MAX,
    REDIRECT_PARAM_NAME,
    REDIRECT_PARAM_VALUE_MAX,
    REDIRECT_URL_MAX,
    type PromoAdDTO,
    type PromoRedirectParam,
    type PromoRedirectParamSource,
} from "@/types/promotion";

/**
 * A coupon ad's three links, each keyed by the request field that carries it (so a
 * field error, a message key and a payload field share one name): `redirectUrl` is
 * the link every phone opens unless the platform's own is set, which then replaces it.
 */
export type RedirectLinkKey = "redirectUrl" | "redirectUrlAndroid" | "redirectUrlIos";
export const REDIRECT_LINK_KEYS: RedirectLinkKey[] = ["redirectUrl", "redirectUrlAndroid", "redirectUrlIos"];
export type RedirectLinks = Record<RedirectLinkKey, string>;

/** The link's own https / allowlist verdict, which AdEditor shares with a WEBSITE ctaUrl. */
export type RedirectLinkProblem = "https" | "domain" | null;

/** The label each link carries wherever the three appear together (the example, the ad list). */
export const REDIRECT_LINK_LABEL: Record<RedirectLinkKey, string> = {
    redirectUrl: "redirectLinkDefault",
    redirectUrlAndroid: "redirectLinkAndroid",
    redirectUrlIos: "redirectLinkIos",
};

export const linksFromAd = (
    ad: Pick<PromoAdDTO, "redirectUrl" | "redirectUrlAndroid" | "redirectUrlIos"> | null | undefined,
): RedirectLinks => ({
    redirectUrl: ad?.redirectUrl ?? "",
    redirectUrlAndroid: ad?.redirectUrlAndroid ?? "",
    redirectUrlIos: ad?.redirectUrlIos ?? "",
});

/** Parameters need at least one of the three links (they are shared by all of them). */
export const hasRedirectLink = (links: RedirectLinks): boolean =>
    REDIRECT_LINK_KEYS.some(k => links[k].trim() !== "");

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
    ["RENTER_NAME", "RENTER_PHONE", "RENTER_EMAIL", "PROPERTY_NAME", "UNIT_NUMBER", "PROPERTY_ID", "UNIT_ID"];

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
 * Field errors for the redirect links, as message keys: `redirectUrl`,
 * `redirectUrlAndroid` and `redirectUrlIos` for each link (each held to the same
 * rules), `redirectParams` for the list, `row-{id}` for one row. Mirrors the
 * server's checks (PromotionService) so a typo is a field error, not a round
 * trip; the server stays the authority. `problems` is each link's own https /
 * allowlist verdict.
 */
export function redirectErrors(
    links: RedirectLinks, rows: RedirectParamRow[], problems: Record<RedirectLinkKey, RedirectLinkProblem>,
): Record<string, string> {
    const errors: Record<string, string> = {};
    for (const key of REDIRECT_LINK_KEYS) {
        const trimmed = links[key].trim();
        if (trimmed === "") continue;
        if (trimmed.length > REDIRECT_URL_MAX) errors[key] = "redirectUrlTooLong";
        else if (problems[key] === "https") errors[key] = "httpsRequired";
        else if (problems[key] === "domain") errors[key] = "domainNotAllowed";
    }
    // Parameters are shared by all three links, so any one of them is enough.
    if (!hasRedirectLink(links) && rows.length > 0) errors.redirectParams = "redirectParamsNeedUrl";
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

/**
 * Percent-encodes as the server does when it builds the link
 * (PromotionRedirectService.encode: `URLEncoder.encode(value, UTF_8)` with `+`
 * turned into `%20`), so the preview matches what a tenant is sent: a space is
 * `%20`, and only `A-Z a-z 0-9 . - * _` are left as they are.
 * `encodeURIComponent` alone differs only on `! ' ( ) ~`.
 */
export function urlEncodeLikeServer(value: string): string {
    return encodeURIComponent(value)
        .replace(/[!'()~]/g, c => `%${c.charCodeAt(0).toString(16).toUpperCase()}`);
}

/** What the tenant would be sent, for the preview: sample values, appended as the server does. */
export function exampleRedirectUrl(base: string, pairs: [string, string][]): string {
    const url = base.trim();
    if (url === "" || pairs.length === 0) return url;
    const hash = url.indexOf("#");
    const head = hash >= 0 ? url.slice(0, hash) : url;
    const fragment = hash >= 0 ? url.slice(hash) : "";
    const sep = !head.includes("?") ? "?" : head.endsWith("?") || head.endsWith("&") ? "" : "&";
    return head + sep + pairs.map(([k, v]) => `${urlEncodeLikeServer(k)}=${urlEncodeLikeServer(v)}`).join("&") + fragment;
}

/** Sample renter data for the preview; real values come from the tenant's account in the app. */
const SAMPLE: Partial<Record<PromoRedirectParamSource, string>> = {
    RENTER_NAME: "Sara Ahmed",
    RENTER_PHONE: "+971501234567",
    RENTER_EMAIL: "sara@example.test",
    UNIT_NUMBER: "A-204",
    PROPERTY_ID: "3f2b8c1e-0000-4000-8000-000000000001",
    UNIT_ID: "3f2b8c1e-0000-4000-8000-000000000002",
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
    links: RedirectLinks;
    onLink: (key: RedirectLinkKey, value: string) => void;
    rows: RedirectParamRow[];
    onRows: (rows: RedirectParamRow[]) => void;
    allowedDomains: string[];
    errors: Record<string, string>;
    /** The example URL of each link, empty for a link that is not set. */
    examples: Record<RedirectLinkKey, string>;
}

const PLATFORM_LINKS = ["redirectUrlAndroid", "redirectUrlIos"] as const;
const EXAMPLE_TEST_ID: Record<RedirectLinkKey, string> = {
    redirectUrl: "redirect-example",
    redirectUrlAndroid: "redirect-example-android",
    redirectUrlIos: "redirect-example-ios",
};
const PLATFORM_HINT_ID = "redirect-platform-hint";

/**
 * The coupon section's optional redirect: after the tenant copies the code the
 * app opens a link, with parameters whose names are chosen here and whose
 * values come from the app. The main link is the default; an Android and an
 * iPhone link may replace it on that phone, and take the same parameters.
 * Shared by the admin and promoter editors.
 */
export function RedirectLinkFields({ links, onLink, rows, onRows, allowedDomains, errors, examples }: RedirectLinkFieldsProps) {
    const t = useTranslations("Promotions");
    // `errors` arrives translated, like the rest of the editor's field errors.
    const err = (key: string) => errors[key]
        ? <p role="alert" id={`err-${key}`} className="mt-1 text-sm text-red-600">{errors[key]}</p>
        : null;
    const invalid = (key: string) =>
        errors[key] ? { "aria-invalid": true, "aria-describedby": `err-${key}` } as const : {};
    // A platform link is described by the shared hint, and by its error when it has one.
    const platformInvalid = (key: string) =>
        errors[key]
            ? { "aria-invalid": true, "aria-describedby": `${PLATFORM_HINT_ID} err-${key}` } as const
            : { "aria-describedby": PLATFORM_HINT_ID } as const;
    const update = (id: number, patch: Partial<RedirectParamRow>) =>
        onRows(rows.map(r => (r.id === id ? { ...r, ...patch } : r)));
    const anyLink = hasRedirectLink(links);
    const showParams = anyLink || rows.length > 0;
    const shownExamples = REDIRECT_LINK_KEYS.filter(k => examples[k]);

    return (
        <div className="space-y-3 rounded-lg border p-3">
            <label className="block">
                <span className="mb-1 block text-sm font-medium">{t("redirectUrl")}</span>
                <input aria-label={t("redirectUrl")} dir="ltr"
                    placeholder="https://" {...invalid("redirectUrl")}
                    className="w-full rounded-lg border px-3 py-2"
                    value={links.redirectUrl} onChange={e => onLink("redirectUrl", e.target.value)} />
                <span className="mt-1 block text-xs text-gray-500">{t("redirectUrlHint")}</span>
                {allowedDomains.length > 0 && (
                    <span className="block text-xs text-gray-500" dir="ltr">{allowedDomains.join(", ")}</span>
                )}
                {err("redirectUrl")}
            </label>

            <div>
                <div className="grid gap-3 sm:grid-cols-2">
                    {PLATFORM_LINKS.map(key => (
                        <label key={key} className="block">
                            <span className="mb-1 block text-sm font-medium">{t(key)}</span>
                            <input aria-label={t(key)} dir="ltr"
                                placeholder="https://" {...platformInvalid(key)}
                                className="w-full rounded-lg border px-3 py-2"
                                value={links[key]} onChange={e => onLink(key, e.target.value)} />
                            {err(key)}
                        </label>
                    ))}
                </div>
                <p id={PLATFORM_HINT_ID} className="mt-1 text-xs text-gray-500">{t("redirectPlatformHint")}</p>
            </div>

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
                    <button type="button" disabled={rows.length >= REDIRECT_PARAMS_MAX || !anyLink}
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

            {shownExamples.length > 0 && (
                <div className="space-y-1">
                    <span className="block text-xs font-medium text-gray-600">{t("redirectExample")}</span>
                    {shownExamples.map(k => (
                        <div key={k}>
                            <span className="block text-xs text-gray-500">{t(REDIRECT_LINK_LABEL[k])}</span>
                            <code dir="ltr" data-testid={EXAMPLE_TEST_ID[k]}
                                className="block break-all rounded bg-gray-50 px-2 py-1 text-xs">{examples[k]}</code>
                        </div>
                    ))}
                </div>
            )}
        </div>
    );
}
