"use client";

import { useTranslations } from "next-intl";
import { codedOf } from "@/components/finance/bankrec/serverText";
import { wholeAed } from "./format";

/**
 * Final review I1: the server refuses an edit of a renewal's successor draft whose rent goes above the rental index
 * cap until it carries a reason (`renewal.indexOverrideRequired`, as renew does). The cap the server named, ready to
 * show ("126,000"; "" when it sent none), or null when the error is something else.
 */
export function indexOverrideCapOf(err: unknown): string | null {
    const c = codedOf(err);
    if (c.code !== "renewal.indexOverrideRequired") return null;
    const cap = c.args?.cap;
    if (cap == null) return "";
    return typeof cap === "number" ? wholeAed(cap) : String(cap);
}

const field =
    "w-full bg-input border border-border rounded-lg px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200";
const label = "block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5";
const bdi = { n: (chunks: React.ReactNode) => <bdi dir="ltr">{chunks}</bdi> };

type Props = {
    /** From {@link indexOverrideCapOf}: the cap to name, or "" to say "above the cap" without a figure. */
    cap: string;
    value: string;
    onChange: (v: string) => void;
    /** Prefix for the element ids and test ids. */
    idPrefix: string;
};

/** The red notice and the reason textarea shown once the server asks for a reason (same copy as the renew dialog). */
export default function IndexOverrideReason({ cap, value, onChange, idPrefix }: Props) {
    const t = useTranslations("RentalIndex");
    const missing = value.trim() === "";
    return (
        <div className="space-y-1.5" data-testid={`${idPrefix}-index-section`}>
            <p role="alert" data-testid={`${idPrefix}-index-above`}
                className="text-[11px] text-error bg-error/10 border border-error/20 rounded-lg px-3 py-2">
                {cap ? t.rich("aboveCap", { cap, ...bdi }) : t("aboveCapNoAmount")}
            </p>
            <label className={label} htmlFor={`${idPrefix}-index-reason`}>{t("reasonLabel")}</label>
            <textarea id={`${idPrefix}-index-reason`} data-testid={`${idPrefix}-index-reason`} className={field} rows={3}
                maxLength={500} value={value} onChange={e => onChange(e.target.value)} />
            <p className={`text-[11px] ${missing ? "text-error font-semibold" : "text-muted"}`}>
                {missing ? t("reasonNeeded") : t("reasonCount", { n: value.length })}
            </p>
        </div>
    );
}
