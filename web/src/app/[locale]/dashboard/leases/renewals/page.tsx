"use client";

import { Suspense, useCallback, useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { useLocale, useTranslations } from "next-intl";
import { ArrowLeft, Search } from "lucide-react";
import { useSession } from "next-auth/react";
import { Link } from "@/i18n/routing";
import { cn } from "@/lib/utils";
import { hasPermission, type UserRole } from "@/lib/rbac";
import { useNameLookup } from "@/components/finance/useNameLookup";
import { TowerSelect } from "@/components/ui/TowerSelect";
import { businessTodayIso } from "@/lib/businessDate";
import { monthOptions, parseQueueState, type QueueState } from "@/lib/leases/renewalSeason";
import RenewalQueue from "@/components/leases/renewals/RenewalQueue";
import BulkTerminate from "@/components/leases/renewals/BulkTerminate";

const select = "bg-surface border border-border rounded-lg px-3 py-2 text-xs text-foreground cursor-pointer focus:ring-2 focus:ring-primary/20 focus:outline-none";

/**
 * The queue's state in the URL (bookmarkable; Home's "Needs you now" links here). Written with
 * the Next router, so `useSearchParams` re-renders the page — a bare `history.replaceState`
 * changed the address bar and left the queue on the old stage (found in the browser sweep).
 */
function useSetQuery() {
    const router = useRouter();
    return useCallback((changes: Partial<Record<keyof QueueState, string | null>>) => {
        const url = new URL(window.location.href);
        for (const [k, v] of Object.entries(changes)) {
            if (v) url.searchParams.set(k, v);
            else url.searchParams.delete(k);
        }
        router.replace(`${url.pathname}${url.search}`, { scroll: false });
    }, [router]);
}

export default function RenewalSeasonPage() {
    return (
        <Suspense fallback={null}>
            <RenewalSeason />
        </Suspense>
    );
}

/**
 * Renewal season (scale spec #16, #17): the month's expiring contracts worked from one queue —
 * row actions and bulk renew on the Renewals tab, PACT's "Bulk Terminate" on the second.
 * Reached from Tenancy Contracts (the header and the Expiring pill's note), the Leasing
 * section of the side panel, and Home's "Needs you now".
 */
function RenewalSeason() {
    const t = useTranslations("RenewalSeason");
    const tList = useTranslations("ListActions");
    const locale = useLocale();
    const sp = useSearchParams();
    const setQuery = useSetQuery();
    const today = businessTodayIso();
    const state = parseQueueState(new URLSearchParams(sp?.toString() ?? ""), today);
    const properties = useNameLookup("properties");
    const { data: session } = useSession();
    const role = session?.user?.role as UserRole | undefined;
    const perms = {
        renew: hasPermission(role, "canRenewLeases"),
        extend: hasPermission(role, "canExtendLeases"),
        notice: hasPermission(role, "canGiveNotice"),
    };
    const canPreviewTermination = hasPermission(role, "canPreviewTermination");
    const canTerminate = hasPermission(role, "canTerminateLeases");

    // The search box shows what is typed; the URL (and the read) follows 350 ms later.
    const [draft, setDraft] = useState(state.search);
    useEffect(() => {
        if (draft.trim() === state.search) return;
        const h = setTimeout(() => setQuery({ search: draft.trim() || null }), 350);
        return () => clearTimeout(h);
    }, [draft, state.search, setQuery]);
    const [floorDraft, setFloorDraft] = useState(state.floor);
    useEffect(() => {
        if (floorDraft.trim() === state.floor) return;
        const h = setTimeout(() => setQuery({ floor: floorDraft.trim() || null }), 350);
        return () => clearTimeout(h);
    }, [floorDraft, state.floor, setQuery]);

    const monthName = (ym: string) => new Date(`${ym}-01T00:00:00Z`)
        .toLocaleDateString(locale === "ar" ? "ar-AE" : "en-GB", { month: "long", year: "numeric", timeZone: "UTC" });
    const tab = state.tab === "terminate" && canPreviewTermination ? "terminate" : "renewals";

    return (
        <div className="space-y-5 max-w-full" data-testid="renewal-season">
            <div>
                <Link href="/dashboard/leases" className="inline-flex items-center gap-1 text-xs text-muted hover:text-foreground">
                    <ArrowLeft size={12} className="rtl:rotate-180" />{t("backToContracts")}
                </Link>
                <h1 className="font-serif text-[26px] font-semibold text-foreground mt-1">{t("title")}</h1>
                <p className="text-[13px] text-[var(--ink-500)]">{t("subtitle")}</p>
            </div>

            <div className="flex items-center bg-input rounded-lg p-0.5 border border-border w-fit" role="tablist">
                <button type="button" role="tab" aria-selected={tab === "renewals"} data-testid="renewal-tab-renewals"
                    onClick={() => setQuery({ tab: null })}
                    className={cn("px-3 py-1.5 rounded-md text-xs font-medium", tab === "renewals" ? "bg-surface shadow-sm border border-border" : "text-muted")}>
                    {t("tabRenewals")}
                </button>
                {canPreviewTermination && (
                    <button type="button" role="tab" aria-selected={tab === "terminate"} data-testid="renewal-tab-terminate"
                        onClick={() => setQuery({ tab: "terminate" })}
                        className={cn("px-3 py-1.5 rounded-md text-xs font-medium", tab === "terminate" ? "bg-surface shadow-sm border border-border" : "text-muted")}>
                        {t("tabTerminate")}
                    </button>
                )}
            </div>

            <div className="flex flex-wrap items-end gap-3">
                {tab === "renewals" && (
                    <label className="block text-[11px] font-semibold text-muted">
                        {t("expiryMonth")}
                        <select className={cn(select, "mt-1 block")} value={state.month} onChange={e => setQuery({ month: e.target.value, stage: null })}
                            data-testid="renewal-month">
                            {monthOptions(today).map(m => <option key={m} value={m}>{monthName(m)}</option>)}
                        </select>
                    </label>
                )}
                <label className="block text-[11px] font-semibold text-muted">
                    {t("property")}
                    <select className={cn(select, "mt-1 block max-w-[14rem]")} value={state.propertyId}
                        onChange={e => setQuery({ propertyId: e.target.value || null, buildingId: null })} data-testid="renewal-property">
                        <option value="">{t("allProperties")}</option>
                        {properties.options.map(o => <option key={o.id} value={o.id}>{o.label}</option>)}
                    </select>
                </label>
                {state.propertyId && (
                    <TowerSelect propertyId={state.propertyId} value={state.buildingId} testId="renewal-tower"
                        onChange={id => setQuery({ buildingId: id || null })}
                        onAvailabilityChange={ok => { if (!ok && state.buildingId) setQuery({ buildingId: null }); }} />
                )}
                <label className="block text-[11px] font-semibold text-muted">
                    {t("floor")}
                    <input value={floorDraft} onChange={e => setFloorDraft(e.target.value)} placeholder={t("floorPlaceholder")}
                        className={cn(select, "mt-1 block w-24 cursor-text")} data-testid="renewal-floor" />
                </label>
                <div className="relative">
                    <Search size={14} className="absolute start-3 top-1/2 -translate-y-1/2 text-muted" aria-hidden />
                    <input value={draft} onChange={e => setDraft(e.target.value)} placeholder={t("searchPlaceholder")} aria-label={tList("search")}
                        className="ps-9 pe-3 py-2 bg-surface border border-border rounded-lg text-xs w-56 max-w-full" data-testid="renewal-search" />
                </div>
            </div>

            {tab === "renewals" ? (
                <RenewalQueue
                    filters={{ month: state.month, stage: state.stage, propertyId: state.propertyId, buildingId: state.buildingId, floor: state.floor, search: state.search }}
                    onStage={st => setQuery({ stage: st === "todo" ? null : st })}
                    perms={perms}
                />
            ) : (
                <BulkTerminate
                    filters={{ date: state.date, propertyId: state.propertyId, buildingId: state.buildingId, floor: state.floor, search: state.search }}
                    onDateChange={d => setQuery({ date: d })}
                    canTerminate={canTerminate}
                />
            )}
        </div>
    );
}
