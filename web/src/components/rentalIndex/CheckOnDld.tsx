"use client";

import { useEffect, useRef, useState } from "react";
import { useTranslations } from "next-intl";
import { Link } from "@/i18n/routing";
import { Check, Copy, ExternalLink } from "lucide-react";
import { bookmarkletHref, clipboardPayload, type DldTypeLabel } from "./fillFromMiftah";

export const DLD_RENTAL_INDEX_URL = "https://dubailand.gov.ae/en/eservices/rental-index-overview/rental-index/#/";

type Props = {
    /** Contract end date as dd/mm/yyyy, the way DLD's date box takes it. */
    end: string;
    typeLabel: DldTypeLabel;
    dewa: string | null;
    rent: number;
    /** Where "Add the unit's DEWA premise number first" sends the person. */
    unitsHref?: string;
};

function CopyValue({ label, value }: { label: string; value: string }) {
    const t = useTranslations("RentalIndex");
    const [done, setDone] = useState(false);
    const copy = async () => {
        try { await navigator.clipboard.writeText(value); setDone(true); setTimeout(() => setDone(false), 1500); } catch { /* clipboard blocked */ }
    };
    return (
        <div className="flex items-center justify-between gap-3 text-xs">
            <span className="text-muted">{label}</span>
            <span className="flex items-center gap-2">
                <span className="font-semibold text-foreground" dir="ltr">{value}</span>
                <button type="button" onClick={copy} aria-label={`${t("copy")}: ${label}`}
                        className="p-1 rounded border border-border text-muted hover:text-foreground cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none">
                    {done ? <Check size={12} /> : <Copy size={12} />}
                </button>
            </span>
        </div>
    );
}

/** Copies a unit's details for DLD's rental index page and opens it; the bookmark fills the form from them. */
export function CheckOnDld({ end, typeLabel, dewa, rent, unitsHref = "?tab=units" }: Props) {
    const t = useTranslations("RentalIndex");
    const [copied, setCopied] = useState(false);
    const bookmark = useRef<HTMLAnchorElement>(null);
    // React refuses a javascript: href in JSX, so the bookmark's address is set on the node.
    useEffect(() => { bookmark.current?.setAttribute("href", bookmarkletHref()); }, []);

    const copyAll = async () => {
        if (!dewa) return;
        try {
            await navigator.clipboard.writeText(clipboardPayload({ end, typeLabel, number: dewa, rent }));
            setCopied(true);
            setTimeout(() => setCopied(false), 1500);
        } catch { /* clipboard blocked */ }
    };

    return (
        <div className="rounded-xl border border-border bg-surface p-4 space-y-3" data-testid="check-on-dld">
            <h4 className="text-sm font-bold text-foreground">{t("checkOnDld")}</h4>
            <p className="text-xs text-muted">{t("checkIntro")}</p>
            {!dewa && (
                <p className="text-xs text-warning font-semibold">
                    {t("needDewa")}{" "}
                    <Link href={unitsHref} className="underline">{t("unitsLink")}</Link>
                </p>
            )}
            <div className="space-y-1.5">
                <CopyValue label={t("vContractEnd")} value={end} />
                <CopyValue label={t("vPropertyType")} value={typeLabel} />
                {dewa && <CopyValue label={t("vDewa")} value={dewa} />}
                <CopyValue label={t("vRent")} value={String(rent)} />
            </div>
            <div className="flex flex-wrap gap-2">
                <button type="button" onClick={copyAll} disabled={!dewa}
                        className="px-3 py-1.5 rounded-lg text-xs font-bold bg-primary text-primary-foreground disabled:opacity-50 cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none">
                    {copied ? t("copied") : t("copyForDld")}
                </button>
                <a href={DLD_RENTAL_INDEX_URL} target="_blank" rel="noopener"
                   className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-bold border border-border text-foreground hover:bg-background">
                    <ExternalLink size={12} />{t("openDld")}
                </a>
            </div>
            <details className="text-xs">
                <summary className="cursor-pointer font-semibold text-muted">{t("setupToggle")}</summary>
                <ol className="list-decimal ps-5 mt-2 space-y-1 text-muted">
                    <li>{t("setupStep1")}</li>
                    <li>{t("setupStep2")}</li>
                    <li>{t("setupStep3")}</li>
                </ol>
                <a ref={bookmark} draggable onClick={(e) => e.preventDefault()}
                   className="inline-block mt-2 px-3 py-1.5 rounded-lg text-xs font-bold border border-dashed border-primary text-primary cursor-grab">
                    {t("setupBookmark")}
                </a>
            </details>
        </div>
    );
}
