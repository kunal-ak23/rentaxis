"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import { useSession } from "next-auth/react";
import { Download, Loader2, ShieldCheck } from "lucide-react";
import { cn } from "@/lib/utils";
import { Pagination } from "@/components/ui/Pagination";
import { downloadCsv, toCsv } from "@/lib/csv";
import { hasPermission, type UserRole } from "@/lib/rbac";

// ── Types ──────────────────────────────────────────────────────────────────

type ScanDirection = "ENTRY" | "EXIT";
type ScanResult = "ALLOWED" | "REJECTED";
type GatePassType = "SINGLE_USE" | "RECURRING";

/**
 * Mirrors `GatePassDtos.GatePassReportRow` field-for-field.
 *
 * Note what is absent: `qrToken` and `numericCode`. The server withholds them from
 * every non-creator view on purpose — anyone holding either can walk through the
 * gate — so there is nothing to render here and nothing to add to the CSV.
 */
type GatePassReportRow = {
    scanId: string;
    scannedAt: string;
    direction: ScanDirection;
    result: ScanResult;
    rejectionReason: string | null;
    scannedByUserId: string;
    /**
     * Resolved server-side, and it has to be: PROPERTY_MANAGER may read this report
     * but not /api/admin/users, so this page cannot turn an id into a person itself.
     * Null when the server could not resolve the row — fall back to the id.
     */
    scannedByName: string | null;
    gatePassId: string;
    propertyId: string;
    unitNumber: string | null;
    guestName: string;
    guestPhone: string;
    vehicleNumber: string | null;
    purpose: string | null;
    passType: GatePassType;
};

/**
 * GET /api/v1/properties returns each property wrapped in a portfolio-summary row
 * (PropertyStatsDTO), not a flat property object — see the same note on the
 * tickets page, where reading `p.id` directly rendered blank dropdown options.
 */
type PropertyRow = {
    property: { id: string; nameEn: string; nameAr: string | null };
};

// ── Badge maps ─────────────────────────────────────────────────────────────

const RESULT_COLORS: Record<ScanResult, string> = {
    ALLOWED: "bg-success/10 text-success",
    REJECTED: "bg-error/10 text-error",
};

const DIRECTION_COLORS: Record<ScanDirection, string> = {
    ENTRY: "bg-info/10 text-info",
    EXIT: "bg-input text-muted",
};

// ── Date helpers ───────────────────────────────────────────────────────────

/** `<input type="date">` value for a Date, in the *local* calendar. */
function toDateInput(date: Date): string {
    const pad = (n: number) => String(n).padStart(2, "0");
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

/**
 * Widens a `YYYY-MM-DD` input to the UTC instant bounding that local day.
 *
 * Built from the parts rather than `new Date("2026-07-09")`, which the spec parses
 * as UTC midnight: a manager in Dubai (UTC+4) asking for the 9th would silently get
 * 04:00 on the 9th through 04:00 on the 10th, losing the first four hours of the
 * day's traffic and folding in four hours of the next. The backend takes Instants,
 * so the conversion has to happen here, and `toISOString()` is the Z-suffixed form
 * its @DateTimeFormat(ISO.DATE_TIME) expects.
 */
function dayBoundsIso(from: string, to: string): { from: string; to: string } {
    const [fy, fm, fd] = from.split("-").map(Number);
    const [ty, tm, td] = to.split("-").map(Number);
    return {
        from: new Date(fy, fm - 1, fd, 0, 0, 0, 0).toISOString(),
        // Inclusive of the whole `to` day — a manager picking a single date for both
        // bounds means "that day", not "that day's first millisecond".
        to: new Date(ty, tm - 1, td, 23, 59, 59, 999).toISOString(),
    };
}

/**
 * Stable, machine-readable timestamp for the CSV — deliberately not localised.
 *
 * The visible table localises (`ar-AE` renders Arabic-Indic numerals), but a CSV is
 * read by Excel and by scripts: Arabic-Indic digits parse as text, not as a date,
 * so an Arabic-locale export would sort and filter wrongly in the one tool it
 * exists to be opened in.
 */
function csvTimestamp(iso: string): string {
    const d = new Date(iso);
    const pad = (n: number) => String(n).padStart(2, "0");
    return (
        `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ` +
        `${pad(d.getHours())}:${pad(d.getMinutes())}`
    );
}

// ── Page ───────────────────────────────────────────────────────────────────

export default function GatePassReportPage() {
    const t = useTranslations("GatePass");
    const locale = useLocale();
    const { data: session, status } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;
    const canView = hasPermission(userRole, "canViewGatePassReport");

    const defaults = useMemo(() => {
        const today = new Date();
        const weekAgo = new Date();
        weekAgo.setDate(weekAgo.getDate() - 6); // 6 back + today == a 7-day window
        return { from: toDateInput(weekAgo), to: toDateInput(today) };
    }, []);

    const [from, setFrom] = useState(defaults.from);
    const [to, setTo] = useState(defaults.to);
    const [propertyId, setPropertyId] = useState("");

    const [rows, setRows] = useState<GatePassReportRow[]>([]);
    const [properties, setProperties] = useState<PropertyRow[]>([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);

    const [currentPage, setCurrentPage] = useState(1);
    const [itemsPerPage, setItemsPerPage] = useState(25);

    const propertyName = useCallback(
        (id: string) => {
            const match = properties.find((p) => p.property.id === id);
            if (!match) return null;
            const { nameEn, nameAr } = match.property;
            return locale === "ar" && nameAr ? nameAr : nameEn;
        },
        [properties, locale],
    );

    const fetchProperties = useCallback(async () => {
        try {
            const res = await fetch("/api/proxy/v1/properties");
            if (res.ok) setProperties(await res.json());
        } catch {
            // Non-fatal: the filter degrades to "All properties" and the Property
            // column falls back to an id fragment. The report itself still loads.
        }
    }, []);

    const fetchReport = useCallback(async () => {
        // The server rejects an inverted range with a 400; catching it here keeps the
        // message actionable and in the user's language rather than a bare failure.
        if (to < from) {
            setError(t("invalidRange"));
            setRows([]);
            setLoading(false);
            return;
        }

        setLoading(true);
        setError(null);
        try {
            const bounds = dayBoundsIso(from, to);
            const params = new URLSearchParams({ from: bounds.from, to: bounds.to });
            if (propertyId) params.set("propertyId", propertyId);

            const res = await fetch(`/api/proxy/v1/gatepass/report?${params}`);
            if (!res.ok) {
                setError(t("loadError"));
                setRows([]);
                return;
            }
            setRows(await res.json());
            setCurrentPage(1);
        } catch {
            setError(t("loadError"));
            setRows([]);
        } finally {
            setLoading(false);
        }
    }, [from, to, propertyId, t]);

    useEffect(() => {
        if (status !== "authenticated" || !canView) return;
        fetchProperties();
    }, [status, canView, fetchProperties]);

    useEffect(() => {
        if (status !== "authenticated" || !canView) return;
        fetchReport();
    }, [status, canView, fetchReport]);

    const columns = useMemo(
        () => [
            t("colScannedAt"), t("colDirection"), t("colResult"), t("colProperty"),
            t("colUnit"), t("colGuestName"), t("colGuestPhone"), t("colVehicle"),
            t("colPurpose"), t("colPassType"), t("colReason"), t("colGuard"),
        ],
        [t],
    );

    function exportCsv() {
        // Exports every fetched row, not just the visible page — the pagination is a
        // reading aid, and an export that silently dropped 90% of the period would be
        // worse than no export.
        //
        // One column wider than the table: the guard's name AND their full id. The two
        // answer different questions and neither replaces the other — a name is what a
        // reader acts on, but names are not unique, so the id is what the row can still
        // be joined and disambiguated by. This extends the divergence the CSV already
        // had (full id vs the table's fragment) rather than inventing one.
        const csv = toCsv(
            [...columns, t("colGuardId")],
            rows.map((r) => [
                csvTimestamp(r.scannedAt),
                t(r.direction),
                t(r.result),
                propertyName(r.propertyId) ?? r.propertyId,
                r.unitNumber,
                r.guestName,
                r.guestPhone,
                r.vehicleNumber,
                r.purpose,
                t(r.passType),
                r.rejectionReason,
                r.scannedByName,
                r.scannedByUserId,
            ]),
        );
        downloadCsv(`gate-pass-report-${from}-to-${to}.csv`, csv);
    }

    if (status === "loading") {
        return (
            <div className="flex items-center justify-center py-24">
                <Loader2 className="w-6 h-6 animate-spin text-primary opacity-60" />
            </div>
        );
    }

    if (!canView) {
        return (
            <div className="text-center py-24">
                <ShieldCheck size={28} className="mx-auto mb-3 text-muted opacity-40" />
                <p className="text-sm text-muted">{t("noAccess")}</p>
            </div>
        );
    }

    const totalItems = rows.length;
    const paginated = rows.slice(
        (currentPage - 1) * itemsPerPage,
        currentPage * itemsPerPage,
    );

    return (
        <div>
            {/* Header */}
            <div className="flex items-center justify-between mb-6 gap-4 flex-wrap">
                <div>
                    <h1 className="text-lg font-bold text-foreground">{t("reportTitle")}</h1>
                    <p className="text-xs text-muted mt-0.5">{t("reportDescription")}</p>
                </div>
                <button
                    onClick={exportCsv}
                    disabled={loading || totalItems === 0}
                    className="flex items-center gap-2 border border-border text-foreground px-4 py-2 rounded-lg text-xs font-semibold hover:bg-input transition-all cursor-pointer disabled:opacity-40 disabled:cursor-not-allowed"
                >
                    <Download size={14} /> {t("exportCsv")}
                </button>
            </div>

            {/* Filters */}
            <div className="bg-surface rounded-xl border border-border p-4 mb-6">
                <div className="flex flex-wrap items-end gap-3">
                    <div>
                        <label htmlFor="gatepass-from" className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1">
                            {t("from")}
                        </label>
                        <input
                            id="gatepass-from"
                            type="date"
                            value={from}
                            onChange={(e) => setFrom(e.target.value)}
                            className="border border-border rounded-lg bg-surface px-3 py-2 text-xs text-foreground focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none"
                        />
                    </div>
                    <div>
                        <label htmlFor="gatepass-to" className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1">
                            {t("to")}
                        </label>
                        <input
                            id="gatepass-to"
                            type="date"
                            value={to}
                            onChange={(e) => setTo(e.target.value)}
                            className="border border-border rounded-lg bg-surface px-3 py-2 text-xs text-foreground focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none"
                        />
                    </div>
                    <div className="flex-1 min-w-[180px]">
                        <label htmlFor="gatepass-property" className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1">
                            {t("property")}
                        </label>
                        <select
                            id="gatepass-property"
                            value={propertyId}
                            onChange={(e) => setPropertyId(e.target.value)}
                            className="w-full border border-border rounded-lg bg-surface px-3 py-2 text-xs text-foreground focus:ring-2 focus:ring-primary/20 focus:outline-none cursor-pointer"
                        >
                            <option value="">{t("allProperties")}</option>
                            {properties.map((p) => (
                                <option key={p.property.id} value={p.property.id}>
                                    {locale === "ar" && p.property.nameAr ? p.property.nameAr : p.property.nameEn}
                                </option>
                            ))}
                        </select>
                    </div>
                    {!loading && !error && (
                        <span className="text-xs text-muted pb-2 tabular-nums">
                            {t("scanCount", { count: totalItems })}
                        </span>
                    )}
                </div>
            </div>

            {/* Table */}
            <div className="bg-surface rounded-xl border border-border overflow-hidden">
                {loading ? (
                    <div className="flex items-center justify-center py-24">
                        <Loader2 className="w-6 h-6 animate-spin text-primary opacity-60" />
                    </div>
                ) : error ? (
                    <div className="text-center py-16">
                        <p className="text-xs text-muted">{error}</p>
                        <button
                            onClick={fetchReport}
                            className="text-xs text-primary mt-2 hover:underline cursor-pointer"
                        >
                            {t("retry")}
                        </button>
                    </div>
                ) : (
                    <>
                        <div className="overflow-x-auto">
                            <table className="w-full">
                                <thead>
                                    <tr className="bg-input/50">
                                        {columns.map((label, i) => (
                                            <th
                                                key={label}
                                                className={cn(
                                                    "px-4 py-2.5 text-[11px] font-semibold text-muted uppercase tracking-wider whitespace-nowrap",
                                                    // Direction + Result are badges; the rest read as text.
                                                    i === 1 || i === 2 ? "text-center" : "text-start",
                                                )}
                                            >
                                                {label}
                                            </th>
                                        ))}
                                    </tr>
                                </thead>
                                <tbody>
                                    {paginated.map((row) => (
                                        <tr key={row.scanId} className="border-b border-border hover:bg-input/30 transition-colors">
                                            <td className="px-4 py-2.5 text-xs text-muted whitespace-nowrap tabular-nums">
                                                {new Date(row.scannedAt).toLocaleString(
                                                    locale === "ar" ? "ar-AE" : "en-GB",
                                                    { dateStyle: "short", timeStyle: "short" },
                                                )}
                                            </td>
                                            <td className="px-4 py-2.5 text-center">
                                                <span className={cn(
                                                    "px-2 py-0.5 rounded-md text-[9px] font-semibold",
                                                    DIRECTION_COLORS[row.direction] || "bg-input text-muted",
                                                )}>
                                                    {t(row.direction)}
                                                </span>
                                            </td>
                                            <td className="px-4 py-2.5 text-center">
                                                <span className={cn(
                                                    "px-2 py-0.5 rounded-md text-[9px] font-semibold",
                                                    RESULT_COLORS[row.result] || "bg-input text-muted",
                                                )}>
                                                    {t(row.result)}
                                                </span>
                                            </td>
                                            <td className="px-4 py-2.5 text-xs text-muted">
                                                {propertyName(row.propertyId) ?? (
                                                    <span className="font-mono">{row.propertyId.substring(0, 8)}</span>
                                                )}
                                            </td>
                                            <td className="px-4 py-2.5 text-xs text-muted">{row.unitNumber || "—"}</td>
                                            <td className="px-4 py-2.5 text-xs font-medium text-foreground max-w-[180px] truncate">
                                                {row.guestName}
                                            </td>
                                            <td className="px-4 py-2.5 text-xs text-muted whitespace-nowrap" dir="ltr">
                                                {row.guestPhone}
                                            </td>
                                            <td className="px-4 py-2.5 text-xs text-muted" dir="ltr">
                                                {row.vehicleNumber || "—"}
                                            </td>
                                            <td className="px-4 py-2.5 text-xs text-muted max-w-[180px] truncate" title={row.purpose ?? undefined}>
                                                {row.purpose || "—"}
                                            </td>
                                            <td className="px-4 py-2.5 text-xs text-muted whitespace-nowrap">
                                                {t(row.passType)}
                                            </td>
                                            <td className="px-4 py-2.5 text-xs text-muted max-w-[160px] truncate" title={row.rejectionReason ?? undefined}>
                                                {row.rejectionReason || "—"}
                                            </td>
                                            {/* The report's central question. The id fragment is only a
                                                fallback for a row the server could not resolve — it was
                                                previously all this column ever showed, which no reader
                                                could act on. */}
                                            <td className="px-4 py-2.5 text-xs text-muted max-w-[160px] truncate"
                                                title={row.scannedByName ?? row.scannedByUserId}>
                                                {row.scannedByName ?? (
                                                    <span className="font-mono" dir="ltr">
                                                        {row.scannedByUserId.substring(0, 8)}
                                                    </span>
                                                )}
                                            </td>
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                            {totalItems === 0 && (
                                <div className="text-center py-12 text-muted">
                                    <ShieldCheck size={28} className="mx-auto mb-3 opacity-40" />
                                    <p className="text-xs">{t("noRows")}</p>
                                    <p className="text-[10px] mt-1 text-muted/60">{t("noRowsHint")}</p>
                                </div>
                            )}
                        </div>

                        <div className="px-4 pb-3">
                            <Pagination
                                currentPage={currentPage}
                                totalItems={totalItems}
                                itemsPerPage={itemsPerPage}
                                onPageChange={setCurrentPage}
                                onItemsPerPageChange={(n) => { setItemsPerPage(n); setCurrentPage(1); }}
                            />
                        </div>
                    </>
                )}
            </div>
        </div>
    );
}
