"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import { useSession } from "next-auth/react";
import { CalendarClock, ShieldCheck } from "lucide-react";
import { Link } from "@/i18n/routing";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";
import { useNameLookup } from "@/components/finance/useNameLookup";
import ChequeStatusBadge from "@/components/cheques/ChequeStatusBadge";
import { fmtIsoDate, round2 } from "@/components/leases/leaseMath";
import { fmtAmount } from "@/lib/api/ledger";
import { ApiError, chequeApi, type Cheque } from "@/lib/api/leasing";
import { hasPermission, type UserRole } from "@/lib/rbac";
import { businessTodayIso } from "@/lib/businessDate";
import { cn } from "@/lib/utils";
import {
    PDC_PRESETS, presetWindow, windowFromParams, windowProblem, writeWindowParams,
    type PdcPreset, type PdcWindow,
} from "./pdcWindow";

const field =
    "bg-input border border-border rounded-lg px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200";
const label = "block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5";
const th = "text-start px-4 py-3 text-[11px] font-semibold text-muted uppercase tracking-wider whitespace-nowrap";
const td = "px-4 py-3 text-xs text-foreground";

const PRESET_LABEL: Record<PdcPreset, string> = {
    "1w": "windowNext1w", "2w": "windowNext2w", "1m": "windowNext1m", custom: "windowCustom",
};

/** The window the address bar names (default Next 2 weeks), on the Dubai business date. */
function initialWindow(): PdcWindow {
    const search = typeof window === "undefined" ? "" : window.location.search;
    return windowFromParams(new URLSearchParams(search), businessTodayIso());
}

/** Keeps the choice in the URL (replace, not push), alongside whatever the hub put there. */
function reflectInUrl(w: PdcWindow) {
    if (typeof window === "undefined") return;
    const url = new URL(window.location.href);
    writeWindowParams(url.searchParams, w);
    window.history.replaceState(window.history.state, "", url.toString());
}

/** Maturity date grouping, in chequeDate order, each day carrying its own
 * subtotal and a running total across the whole window. */
function groupByMaturity(rows: Cheque[]): { date: string; rows: Cheque[]; subtotal: number }[] {
    const byDate = new Map<string, Cheque[]>();
    for (const c of rows) {
        const key = (c.chequeDate ?? c.postingDate).slice(0, 10);
        const list = byDate.get(key) ?? [];
        list.push(c);
        byDate.set(key, list);
    }
    return [...byDate.entries()]
        .sort(([a], [b]) => a.localeCompare(b))
        .map(([date, dateRows]) => ({
            date,
            rows: dateRows,
            subtotal: dateRows.reduce((s, c) => round2(s + c.amount), 0),
        }));
}

export default function PostDatedPanel(props: { embedded?: boolean; propertyId?: string }) {
    const embedded = props.embedded ?? false;
    const Heading = embedded ? "h2" : "h1";
    const t = useTranslations("Cheques");
    const tl = useTranslations("Leasing");
    const tLedger = useTranslations("Ledger");
    const tCommon = useTranslations("Common");
    const locale = useLocale();
    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;
    const allowed = hasPermission(userRole, "canManageCheques");

    const properties = useNameLookup("properties", allowed);

    const [win, setWin] = useState<PdcWindow>(initialWindow);
    const problem = windowProblem(win);
    const [ownPropertyId, setPropertyId] = useState("");
    // Inside the Collection hub the hub's property filter drives the query and
    // this panel's own picker is hidden.
    const propertyId = props.propertyId ?? ownPropertyId;
    const [rows, setRows] = useState<Cheque[]>([]);
    const [loading, setLoading] = useState(true);
    const [loadError, setLoadError] = useState<string | null>(null);

    const load = useCallback(async () => {
        setLoading(true);
        setLoadError(null);
        try {
            setRows(await chequeApi.postDated({ from: win.from, to: win.to, propertyId: propertyId || undefined }));
        } catch (err) {
            setRows([]);
            setLoadError(err instanceof ApiError ? err.message : tCommon("loadFailed"));
        } finally {
            setLoading(false);
        }
    }, [win.from, win.to, propertyId, tCommon]);

    useEffect(() => {
        if (!allowed || problem) {
            setLoading(false);
            return;
        }
        load();
    }, [allowed, load, problem]);

    const choose = (next: PdcWindow) => {
        setWin(next);
        reflectInUrl(next);
    };
    const pickPreset = (preset: PdcPreset) =>
        choose(preset === "custom" ? { ...win, preset } : { preset, ...presetWindow(preset, businessTodayIso()) });

    const groups = useMemo(() => groupByMaturity(rows), [rows]);
    const total = useMemo(() => rows.reduce((s, c) => round2(s + c.amount), 0), [rows]);

    if (userRole && !allowed) {
        return (
            <div className="max-w-4xl">
                <div className="bg-surface rounded-xl p-12 shadow-sm border border-border text-center">
                    <ShieldCheck size={48} className="mx-auto text-muted mb-4" />
                    <h2 className="text-lg font-bold text-foreground mb-2">{tLedger("accessDeniedTitle")}</h2>
                    <p className="text-sm text-muted">{t("accessDenied")}</p>
                </div>
            </div>
        );
    }

    let running = 0;

    return (
        <div>
            <div className="flex items-start justify-between gap-4 mb-6">
                <div>
                    <Heading className="text-xl font-bold text-foreground tracking-tight mb-1 flex items-center gap-2">
                        <CalendarClock size={20} className="text-primary" />
                        {t("postDated")}
                    </Heading>
                </div>
                {!embedded && (
                    <Link
                        href="/dashboard/finance/cheques"
                        className="px-3 py-2 rounded-lg text-xs font-semibold border border-border text-foreground hover:bg-input/40"
                    >
                        {t("register")}
                    </Link>
                )}
            </div>

            {loadError && <LoadErrorBanner message={loadError} onRetry={load} />}

            <div className="bg-surface border border-border rounded-xl shadow-sm p-4 mb-6 flex flex-wrap items-end gap-4">
                <div>
                    <span className={label} id="pd-window-label">{t("maturityWindow")}</span>
                    <div role="group" aria-labelledby="pd-window-label" className="inline-flex rounded-lg border border-border overflow-hidden" data-testid="post-dated-presets">
                        {PDC_PRESETS.map(p => (
                            <button
                                key={p}
                                type="button"
                                data-testid={`post-dated-preset-${p}`}
                                aria-pressed={win.preset === p}
                                onClick={() => pickPreset(p)}
                                className={cn(
                                    "px-3 py-2 text-xs font-semibold border-e border-border last:border-e-0 cursor-pointer transition-colors",
                                    win.preset === p ? "bg-primary text-primary-foreground" : "bg-input text-foreground hover:bg-border",
                                )}
                            >
                                {t(PRESET_LABEL[p])}
                            </button>
                        ))}
                    </div>
                </div>
                {win.preset === "custom" ? (
                    <>
                        <div>
                            <label className={label} htmlFor="pd-from">{t("windowFrom")}</label>
                            <input id="pd-from" data-testid="post-dated-from" type="date" className={field}
                                value={win.from} onChange={ev => choose({ ...win, from: ev.target.value })} />
                        </div>
                        <div>
                            <label className={label} htmlFor="pd-to">{t("windowTo")}</label>
                            <input id="pd-to" data-testid="post-dated-to" type="date" className={field}
                                value={win.to} min={win.from || undefined} onChange={ev => choose({ ...win, to: ev.target.value })} />
                        </div>
                    </>
                ) : (
                    <p className="text-xs text-muted pb-2 tabular-nums" data-testid="post-dated-window">
                        {t("windowSummary", { from: fmtIsoDate(win.from, locale), to: fmtIsoDate(win.to, locale) })}
                    </p>
                )}
                {props.propertyId === undefined && (
                    <div>
                        <label className={label} htmlFor="pd-property">{tLedger("propertyFilter")}</label>
                        <select
                            id="pd-property"
                            data-testid="post-dated-property-filter"
                            className={`${field} min-w-[12rem]`}
                            value={propertyId}
                            onChange={ev => setPropertyId(ev.target.value)}
                        >
                            <option value="">{tLedger("selectProperty")}</option>
                            {properties.options.map(p => (
                                <option key={p.id} value={p.id}>{p.label}</option>
                            ))}
                        </select>
                    </div>
                )}
                <div className="ms-auto text-xs text-muted" data-testid="post-dated-running-total">
                    {t("runningTotal")}: <strong className="text-foreground">{fmtAmount(total)}</strong>
                </div>
            </div>

            {problem && (
                <p role="alert" data-testid="post-dated-window-error" className="rounded-xl bg-error/10 border border-error/30 px-4 py-2.5 text-xs text-error mb-4">
                    {t(problem === "tooLong" ? "windowTooLong" : "windowInvalid")}
                </p>
            )}

            {problem ? null : loading ? (
                <div className="space-y-3 animate-pulse">
                    {[1, 2, 3].map(i => <div key={i} className="bg-input rounded-xl h-14" />)}
                </div>
            ) : rows.length === 0 ? (
                <div className="text-center py-24 bg-background border border-dashed border-border rounded-xl flex flex-col items-center">
                    <div className="w-16 h-16 bg-surface rounded-xl flex items-center justify-center text-muted shadow-sm mb-6">
                        <CalendarClock size={28} />
                    </div>
                    <h3 className="text-sm font-bold text-foreground mb-1">{t("noPostDated")}</h3>
                </div>
            ) : (
                <div className="space-y-4">
                    {groups.map(group => (
                        <div key={group.date} data-testid={`post-dated-group-${group.date}`} className="bg-surface border border-border rounded-xl shadow-sm overflow-hidden">
                            <div className="px-4 py-2.5 bg-input/40 border-b border-border flex items-center justify-between">
                                <span className="text-xs font-semibold">{fmtIsoDate(group.date, locale)}</span>
                                <span className="text-xs font-semibold tabular-nums" data-testid={`post-dated-subtotal-${group.date}`}>
                                    {t("subtotal")}: {fmtAmount(group.subtotal)}
                                </span>
                            </div>
                            <table className="w-full">
                                <thead>
                                    <tr>
                                        <th className={th}>{tl("chequeNo")}</th>
                                        <th className={th}>{t("tenant")}</th>
                                        <th className={th}>{tl("unit")}</th>
                                        <th className={th}>{tLedger("propertyFilter")}</th>
                                        <th className={`${th} text-end`}>{tl("amount")}</th>
                                        <th className={th}>{tLedger("status")}</th>
                                        <th className={`${th} text-end`}>{t("runningTotal")}</th>
                                    </tr>
                                </thead>
                                <tbody className="divide-y divide-border">
                                    {group.rows.map(c => {
                                        running = round2(running + c.amount);
                                        return (
                                            <tr key={c.id} data-testid={`cheque-row-${c.id}`}>
                                                <td className={`${td} font-semibold`}>{c.chequeNumber || `#${c.seqNo}`}</td>
                                                <td className={td}>{c.renterName || "—"}</td>
                                                <td className={td}>{c.unitIdentifier || "—"}</td>
                                                <td className={`${td} text-muted`}>{c.propertyName || "—"}</td>
                                                <td className={`${td} text-end tabular-nums`}>{fmtAmount(c.amount)}</td>
                                                <td className={td}>
                                                    <ChequeStatusBadge status={c.status} testId={`cheque-status-${c.id}`} />
                                                </td>
                                                <td className={`${td} text-end tabular-nums font-semibold`} data-testid={`post-dated-running-${c.id}`}>
                                                    {fmtAmount(running)}
                                                </td>
                                            </tr>
                                        );
                                    })}
                                </tbody>
                            </table>
                        </div>
                    ))}
                </div>
            )}
        </div>
    );
}
