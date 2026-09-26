"use client";

import { useEffect, useMemo, useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import { Loader2 } from "lucide-react";
import { Link } from "@/i18n/routing";
import { cn } from "@/lib/utils";
import { fmtAmount } from "@/lib/api/ledger";
import { leaseApi, type LeaseDetail, type LeaseStatus } from "@/lib/api/leasing";
import { businessTodayIso } from "@/lib/businessDate";
import { fmtIsoDate } from "@/components/leases/leaseMath";
import { useNameLookup } from "@/components/finance/useNameLookup";
import SideDrawer from "@/components/ui/SideDrawer";
import {
    BOARD_STATUSES, buildBoard, countByStatus,
    type BoardBuilding, type BoardCell, type BoardStatus, type BoardUnit,
} from "@/lib/units/unitBoard";

/** Rows per request (the backend's page cap) and the most pages one property may take. */
const PAGE = 200;
const MAX_PAGES = 25;
const STORE_KEY = "unitBoard.propertyId";

const TILE: Record<BoardStatus, string> = {
    OCCUPIED: "border-s-[var(--teal-600)] bg-[var(--teal-600)]/10",
    RESERVED: "border-s-[var(--gold-500)] bg-[var(--gold-500)]/15",
    EXPIRING: "border-s-warning bg-warning/15",
    VACANT: "border-s-[var(--ink-500)]/40 bg-surface",
    MAINTENANCE: "border-s-error bg-error/10",
};
const DOT: Record<BoardStatus, string> = {
    OCCUPIED: "bg-[var(--teal-600)]",
    RESERVED: "bg-[var(--gold-500)]",
    EXPIRING: "bg-warning",
    VACANT: "bg-[var(--ink-500)]/40",
    MAINTENANCE: "bg-error",
};

type Loaded = { key: string; units: BoardUnit[]; leases: LeaseDetail[]; buildings: BoardBuilding[]; truncated: boolean; failed: boolean };

async function pages<T>(fetchPage: (page: number) => Promise<{ content?: T[]; totalPages?: number }>): Promise<{ rows: T[]; truncated: boolean }> {
    const rows: T[] = [];
    for (let p = 0; p < MAX_PAGES; p++) {
        const res = await fetchPage(p);
        rows.push(...(res.content ?? []));
        if (p + 1 >= (res.totalPages ?? 1)) return { rows, truncated: false };
    }
    return { rows, truncated: true };
}

async function loadProperty(propertyId: string): Promise<Omit<Loaded, "key">> {
    const unitsPage = (page: number) => fetch(`/api/proxy/v1/units/paged?propertyId=${encodeURIComponent(propertyId)}&page=${page}&size=${PAGE}`)
        .then(r => { if (!r.ok) throw new Error(String(r.status)); return r.json(); });
    const leasesOf = (status: LeaseStatus) => pages<LeaseDetail>(page => leaseApi.paged({ propertyId, status, page, size: PAGE }));
    // PENDING_SIGNATURE too: a unit held by a contract awaiting signature opens with that contract (R1 P3-7).
    const [units, active, notice, pending, buildings] = await Promise.all([
        pages<BoardUnit>(unitsPage),
        leasesOf("ACTIVE"),
        leasesOf("NOTICE_GIVEN"),
        leasesOf("PENDING_SIGNATURE"),
        fetch(`/api/proxy/v1/buildings/property/${encodeURIComponent(propertyId)}`).then(r => (r.ok ? r.json() : [])).catch(() => []),
    ]);
    return {
        units: units.rows,
        leases: [...active.rows, ...notice.rows, ...pending.rows],
        buildings: Array.isArray(buildings) ? buildings : [],
        truncated: units.truncated || active.truncated || notice.truncated || pending.truncated,
        failed: false,
    };
}

function readStored(): string {
    try { return window.localStorage.getItem(STORE_KEY) ?? ""; } catch { return ""; }
}

/**
 * Unit status board (scale spec #20; PACT's "Floor Wise Expiry" and "Daily
 * Vacant Flat"): one property at a time, grouped by building (when it has
 * any) and floor, each unit coloured Occupied / Reserved / Expiring ≤ 60 d /
 * Vacant / Maintenance. A click opens the unit and its contract in a side
 * panel. It reads only the chosen property — `GET /units/paged?propertyId=`
 * and `GET /leases/paged?propertyId=&status=` a page at a time — so an
 * organisation of thousands of units never loads them all.
 */
export default function UnitStatusBoard() {
    const t = useTranslations("UnitBoard");
    const tA = useTranslations("LeaseActions");
    const locale = useLocale();
    const properties = useNameLookup("properties");
    const [picked, setPicked] = useState<string>(() => (typeof window === "undefined" ? "" : readStored()));
    const [data, setData] = useState<Loaded | null>(null);
    const [only, setOnly] = useState<BoardStatus | null>(null);
    const [open, setOpen] = useState<BoardCell | null>(null);
    const today = useMemo(() => businessTodayIso(), []);

    // A remembered property that is gone (or another organisation's) falls back to the first one.
    const propertyId = properties.options.some(o => o.id === picked) ? picked : properties.options[0]?.id ?? "";

    useEffect(() => {
        if (!propertyId) return;
        let alive = true;
        loadProperty(propertyId)
            .then(d => { if (alive) setData({ key: propertyId, ...d }); })
            .catch(() => { if (alive) setData({ key: propertyId, units: [], leases: [], buildings: [], truncated: false, failed: true }); });
        return () => { alive = false; };
    }, [propertyId]);

    const current = data?.key === propertyId ? data : null;
    const groups = useMemo(() => (current ? buildBoard(current.units, current.leases, current.buildings, today) : []), [current, today]);
    const counts = useMemo(() => countByStatus(groups), [groups]);
    const buildingName = (id: string | null) => {
        if (!id) return t("noBuilding");
        const b = current?.buildings.find(x => x.id === id);
        return (locale === "ar" ? b?.nameAr || b?.nameEn : b?.nameEn || b?.nameAr) ?? t("noBuilding");
    };
    const pick = (id: string) => {
        setPicked(id);
        setOnly(null);
        try { window.localStorage.setItem(STORE_KEY, id); } catch { /* per-viewer convenience only */ }
    };
    const showBuildings = groups.some(g => g.buildingId !== null);

    return (
        <section id="unit-status" aria-labelledby="unit-board-title" data-testid="unit-board"
            className="scroll-mt-6 bg-surface border border-border rounded-[var(--radius-lg)] p-5 min-w-0">
            <div className="flex flex-wrap items-center justify-between gap-3 mb-3">
                <div className="min-w-0">
                    <h2 id="unit-board-title" className="font-serif text-[20px] font-semibold text-foreground">{t("title")}</h2>
                    <p className="text-[12px] text-[var(--ink-500)]">{t("subtitle")}</p>
                </div>
                {properties.options.length > 0 && (
                    <label className="flex items-center gap-2 text-[12px] text-[var(--ink-500)] min-w-0 max-w-full">
                        <span className="shrink-0">{t("property")}</span>
                        <select data-testid="unit-board-property" value={propertyId} onChange={e => pick(e.target.value)}
                            className="min-w-0 max-w-[16rem] bg-surface border border-border rounded-lg px-2.5 py-1.5 text-xs text-foreground focus:ring-2 focus:ring-primary/20 focus:outline-none">
                            {properties.options.map(o => <option key={o.id} value={o.id}>{o.label}</option>)}
                        </select>
                    </label>
                )}
            </div>

            {/* Legend doubles as a filter: pick a status to show only those units. */}
            <div role="group" aria-label={t("legend")} className="flex flex-wrap gap-1.5 mb-4" data-testid="unit-board-legend">
                {BOARD_STATUSES.map(s => (
                    <button key={s} type="button" aria-pressed={only === s} data-testid={`unit-board-filter-${s.toLowerCase()}`}
                        onClick={() => setOnly(o => (o === s ? null : s))}
                        className={cn("inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-[11.5px] cursor-pointer",
                            only === s ? "border-[var(--ink-900)] bg-[var(--sand-100)] font-semibold" : "border-border hover:bg-[var(--sand-100)]")}>
                        <span className={cn("inline-block w-2 h-2 rounded-full", DOT[s])} aria-hidden />
                        {t(`status.${s}`)}
                        <span className="tabular-nums text-[var(--ink-500)]">{current ? counts[s] : "…"}</span>
                    </button>
                ))}
            </div>

            {!properties.loading && properties.options.length === 0 ? (
                <p className="text-[13px] text-[var(--ink-500)]" data-testid="unit-board-no-properties">{t("noProperties")}</p>
            ) : !current ? (
                <div className="flex items-center gap-2 py-8 justify-center text-[13px] text-[var(--ink-500)]"><Loader2 size={15} className="animate-spin" />{t("loading")}</div>
            ) : current.failed ? (
                <p className="text-[13px] text-error" data-testid="unit-board-failed">{t("loadFailed")}</p>
            ) : current.units.length === 0 ? (
                <p className="text-[13px] text-[var(--ink-500)]" data-testid="unit-board-empty">{t("noUnits")}</p>
            ) : (
                <div className="space-y-5">
                    {current.truncated && <p className="text-[12px] text-warning" data-testid="unit-board-truncated">{t("truncated", { count: current.units.length })}</p>}
                    {groups.map(g => (
                        <div key={g.buildingId ?? "none"} data-testid={`unit-board-building-${g.buildingId ?? "none"}`}>
                            {showBuildings && <h3 className="text-[12px] font-semibold uppercase tracking-wider text-[var(--ink-600)] mb-2">{buildingName(g.buildingId)}</h3>}
                            <div className="space-y-2">
                                {g.floors.map(f => {
                                    const cells = only ? f.cells.filter(c => c.status === only) : f.cells;
                                    if (cells.length === 0) return null;
                                    return (
                                        <div key={f.floor} className="flex gap-3 items-start" data-testid={`unit-board-floor-${f.floor || "other"}`}>
                                            <div className="w-16 shrink-0 pt-1.5 text-[11px] font-semibold text-[var(--ink-500)]">
                                                {f.floor ? t("floor", { floor: f.floor }) : t("otherFloor")}
                                            </div>
                                            <ul className="flex flex-wrap gap-1.5 min-w-0 flex-1">
                                                {cells.map(c => (
                                                    <li key={c.unit.id}>
                                                        <button type="button" onClick={() => setOpen(c)} data-testid={`unit-tile-${c.unit.id}`} data-status={c.status}
                                                            aria-label={t("tileLabel", { unit: c.unit.unitNumber, status: t(`status.${c.status}`) })}
                                                            title={[c.unit.unitNumber, t(`status.${c.status}`), c.lease?.renterName ?? c.unit.currentTenantName].filter(Boolean).join(" · ")}
                                                            className={cn("min-w-[4.5rem] max-w-[9rem] rounded-md border border-border border-s-4 px-2 py-1.5 text-start cursor-pointer hover:shadow-sm focus:outline-none focus:ring-2 focus:ring-primary/30", TILE[c.status])}>
                                                            <span className="block text-[12px] font-semibold text-foreground truncate" dir="ltr">{c.unit.unitNumber}</span>
                                                            <span className="block text-[10.5px] text-[var(--ink-500)] truncate">
                                                                {c.status === "EXPIRING" && c.lease ? fmtIsoDate(c.lease.endDate, locale) : t(`status.${c.status}`)}
                                                            </span>
                                                        </button>
                                                    </li>
                                                ))}
                                            </ul>
                                        </div>
                                    );
                                })}
                            </div>
                        </div>
                    ))}
                </div>
            )}

            <SideDrawer open={open !== null} onClose={() => setOpen(null)} title={open ? t("drawerTitle", { unit: open.unit.unitNumber }) : ""}
                closeLabel={tA("close")} testId="unit-board-drawer">
                {open && <UnitPanel cell={open} propertyId={propertyId} />}
            </SideDrawer>
        </section>
    );
}

function UnitPanel({ cell, propertyId }: { cell: BoardCell; propertyId: string }) {
    const t = useTranslations("UnitBoard");
    const tL = useTranslations("Leasing");
    const locale = useLocale();
    const { unit, lease, status, successor } = cell;
    const row = (label: string, value: React.ReactNode) => (
        <div className="flex justify-between gap-3 py-1.5 border-b border-border last:border-b-0 text-xs">
            <span className="text-muted">{label}</span>
            <span className="font-medium text-foreground text-end min-w-0 break-words">{value}</span>
        </div>
    );
    return (
        <div className="space-y-4" data-testid="unit-board-panel">
            <div className="flex items-center gap-2">
                <span className={cn("inline-block w-2.5 h-2.5 rounded-full", DOT[status])} aria-hidden />
                <span className="text-sm font-semibold" data-testid="unit-board-panel-status">{t(`status.${status}`)}</span>
            </div>
            <div>
                {row(t("unitNumber"), <bdi>{unit.unitNumber}</bdi>)}
                {unit.type && row(t("type"), unit.type)}
                {unit.sizeSqft ? row(t("size"), <bdi dir="ltr">{unit.sizeSqft} sq ft</bdi>) : null}
                {unit.expectedRent ? row(t("expectedRent"), <bdi dir="ltr">{fmtAmount(unit.expectedRent)}</bdi>) : null}
                {unit.currentTenantName && row(t("currentTenant"), unit.currentTenantName)}
                {unit.nextLeaseStart && row(t("nextLease"), `${unit.nextTenantName ?? "—"} · ${fmtIsoDate(unit.nextLeaseStart, locale)}`)}
                {successor && (
                    <p className="pt-1.5 text-xs text-[var(--teal-600)]" data-testid="unit-board-panel-renewed">
                        <Link href={`/dashboard/leases/${successor.id}`} className="hover:underline">
                            {t("renewed", { date: fmtIsoDate(successor.startDate, locale) })}
                        </Link>
                    </p>
                )}
            </div>
            {lease && (
                <div className="rounded-lg border border-border p-3 space-y-1" data-testid="unit-board-panel-lease">
                    <p className="text-[11px] font-semibold uppercase tracking-wider text-muted">{t("contract")}</p>
                    {row(tL("renter"), lease.renterName ?? "—")}
                    {row(tL("startDate"), fmtIsoDate(lease.startDate, locale))}
                    {row(tL("endDate"), fmtIsoDate(lease.endDate, locale))}
                    {lease.status && row(t("contractStatus"), tL(`leaseStatus.${lease.status}`))}
                    <Link href={`/dashboard/leases/${lease.id}`} data-testid="unit-board-open-contract"
                        className="inline-flex mt-2 items-center gap-1.5 bg-primary text-primary-foreground px-3 py-1.5 rounded-lg text-xs font-semibold">
                        {t("openContract")}
                    </Link>
                </div>
            )}
            <Link href={`/dashboard/properties/${propertyId}/units`} data-testid="unit-board-open-units" className="text-xs font-semibold text-primary hover:underline">
                {t("openUnits")}
            </Link>
        </div>
    );
}
