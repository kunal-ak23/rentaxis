"use client";

import { useEffect, useRef, useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import { Download, Search } from "lucide-react";
import { Link } from "@/i18n/routing";
import { cn } from "@/lib/utils";
import { Pagination } from "@/components/ui/Pagination";
import { useNameLookup } from "@/components/finance/useNameLookup";
import RatingBadge from "@/components/renters/rating/RatingBadge";
import { useRenterRatingEnabled } from "@/components/renters/rating/useRenterRatingEnabled";
import { fmtIsoDate } from "@/components/leases/leaseMath";
import { saveResponse } from "@/lib/download";
import { renterRatingsApi, type RatingGrade, type RatingListParams, type RenterRatingRow } from "@/lib/api/renterRatings";

const GRADES: RatingGrade[] = ["A", "B", "C", "D", "NEW"];
const SEARCH_DEBOUNCE_MS = 300;

/**
 * Portfolio list of the premium tenant rating (staff only, flag RENTER_RATING). Rows come
 * from the stored ratings, so each badge is drawn from row data (no per-row fetch).
 */
export default function RenterRatingsPage() {
    const t = useTranslations("RenterRating");
    const locale = useLocale();
    const enabled = useRenterRatingEnabled();
    const properties = useNameLookup("properties", enabled);

    const [grade, setGrade] = useState<RatingGrade | "">("");
    const [propertyId, setPropertyId] = useState("");
    const [searchText, setSearchText] = useState("");
    const [q, setQ] = useState("");
    const [sortDir, setSortDir] = useState<"asc" | "desc">("asc");
    const [page, setPage] = useState(1);
    const [size, setSize] = useState(25);
    const [rows, setRows] = useState<RenterRatingRow[]>([]);
    const [total, setTotal] = useState(0);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [exporting, setExporting] = useState(false);
    const latest = useRef(0);

    // Debounced search; a new term goes back to page 1.
    useEffect(() => {
        if (searchText === q) return;
        const id = setTimeout(() => { setQ(searchText); setPage(1); }, SEARCH_DEBOUNCE_MS);
        return () => clearTimeout(id);
    }, [searchText, q]);

    const filters = (): RatingListParams => ({
        grade: grade || undefined, propertyId: propertyId || undefined, q: q || undefined, sort: `score,${sortDir}`,
    });

    useEffect(() => {
        if (!enabled) return;
        const mine = ++latest.current;
        setLoading(true);
        setError(null);
        renterRatingsApi.list({
            grade: grade || undefined, propertyId: propertyId || undefined, q: q || undefined,
            sort: `score,${sortDir}`, page: page - 1, size,
        })
            .then(data => {
                if (mine !== latest.current) return;
                setRows(data.content ?? []);
                setTotal(data.totalElements ?? 0);
            })
            .catch(() => { if (mine === latest.current) setError(t("loadError")); })
            .finally(() => { if (mine === latest.current) setLoading(false); });
    }, [enabled, grade, propertyId, q, sortDir, page, size, t]);

    if (!enabled) return null;

    const pick = (g: RatingGrade | "") => { setGrade(g); setPage(1); };
    const exportCsv = async () => {
        setExporting(true);
        setError(null);
        try {
            const res = await fetch(renterRatingsApi.exportUrl(filters()));
            if (!res.ok) throw new Error(String(res.status));
            await saveResponse(res, "tenant-ratings.csv");
        } catch {
            setError(t("exportFailed"));
        } finally {
            setExporting(false);
        }
    };

    const chip = (value: RatingGrade | "", label: string) => (
        <button key={value || "all"} type="button" onClick={() => pick(value)} aria-pressed={grade === value}
            data-testid={`rating-grade-${value || "ALL"}`}
            className={cn("px-3 py-1.5 rounded-full text-xs font-medium border transition-colors",
                grade === value ? "bg-primary text-primary-foreground border-primary" : "bg-surface border-border text-[var(--ink-600)] hover:border-primary")}>
            {label}
        </button>
    );
    const empty = !loading && total === 0;
    const nothingYet = empty && !grade && !propertyId && !q;
    const nameOf = (r: RenterRatingRow) => (locale === "ar" ? r.renterNameAr || r.renterName : r.renterName);

    return (
        <div className="space-y-4" data-testid="renter-ratings-page">
            <div>
                <h1 className="mb-0 font-serif text-[26px] font-semibold tracking-tight">{t("pageTitle")}</h1>
                <p className="text-sm text-muted">{t("pageSubtitle")}</p>
            </div>

            <div className="flex flex-wrap items-center gap-2" role="group" aria-label={t("filterGrade")}>
                {chip("", t("all"))}
                {GRADES.map(g => chip(g, g))}
            </div>

            <div className="flex flex-wrap items-center gap-2">
                <select aria-label={t("property")} data-testid="rating-property-filter" value={propertyId}
                    onChange={e => { setPropertyId(e.target.value); setPage(1); }}
                    className="max-w-[14rem] bg-surface border border-border rounded-lg px-3 py-2 text-xs text-foreground cursor-pointer focus:ring-2 focus:ring-primary/20 focus:outline-none">
                    <option value="">{t("allProperties")}</option>
                    {properties.options.map(o => <option key={o.id} value={o.id}>{o.label}</option>)}
                </select>
                <div className="relative">
                    <Search size={14} className="absolute start-3 top-1/2 -translate-y-1/2 text-muted pointer-events-none" />
                    <input type="search" value={searchText} onChange={e => setSearchText(e.target.value)} placeholder={t("search")}
                        aria-label={t("search")} data-testid="rating-search"
                        className="bg-surface border border-border rounded-lg ps-9 pe-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:outline-none" />
                </div>
                <button type="button" onClick={exportCsv} disabled={exporting} data-testid="rating-export"
                    className="ms-auto flex items-center gap-2 bg-accent text-accent-foreground px-4 py-2 rounded-lg text-xs font-semibold hover:brightness-110 disabled:opacity-50">
                    <Download size={14} />{t("exportCsv")}
                </button>
            </div>
            {error && <p className="text-xs text-error" role="alert">{error}</p>}

            <section className="bg-surface border border-border rounded-[var(--radius-lg)] overflow-hidden">
                {nothingYet ? (
                    <div className="p-10 text-center space-y-1" data-testid="rating-empty">
                        <p className="text-sm font-semibold">{t("emptyCalculating")}</p>
                        <p className="text-xs text-muted">{t("emptyHint")}</p>
                    </div>
                ) : (
                    <div className="overflow-x-auto">
                        <table className="w-full text-xs" data-testid="rating-table">
                            <thead className="bg-input text-muted text-[10px] uppercase tracking-wider">
                                <tr>
                                    <th className="p-2 text-start">{t("colTenant")}</th>
                                    <th className="p-2 text-start">{t("colGrade")}</th>
                                    <th className="p-2 text-start">
                                        <button type="button" onClick={() => { setSortDir(d => (d === "asc" ? "desc" : "asc")); setPage(1); }}
                                            data-testid="rating-sort-score" aria-label={t("sortScore")} className="uppercase hover:underline">
                                            {t("colScore")} {sortDir === "asc" ? "▲" : "▼"}
                                        </button>
                                    </th>
                                    <th className="p-2 text-end">{t("colDeductions")}</th>
                                    <th className="p-2 text-start">{t("colUnit")}</th>
                                    <th className="p-2 text-start">{t("colProperty")}</th>
                                    <th className="p-2 text-start">{t("colComputed")}</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-border">
                                {loading && rows.length === 0 && <tr><td colSpan={7} className="p-6 text-center text-muted">{t("loading")}</td></tr>}
                                {empty && <tr><td colSpan={7} className="p-6 text-center text-muted">{t("noMatch")}</td></tr>}
                                {rows.map(r => (
                                    <tr key={r.renterId} data-testid={`rating-row-${r.renterId}`}>
                                        <td className="p-2 font-medium"><Link href={`/dashboard/renters/${r.renterId}`} className="hover:underline">{nameOf(r)}</Link></td>
                                        <td className="p-2"><RatingBadge compact renterId={r.renterId} prefetched={{ score: r.score, grade: r.grade }} /></td>
                                        <td className="p-2 tabular-nums">{r.score}</td>
                                        <td className="p-2 text-end tabular-nums">{r.deductionsCount}</td>
                                        <td className="p-2">{r.unitLabel ?? "—"}</td>
                                        <td className="p-2">{r.propertyName ?? "—"}</td>
                                        <td className="p-2 whitespace-nowrap">{fmtIsoDate(r.computedAt.slice(0, 10), locale)}</td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                )}
                {total > size && (
                    <div className="p-3 border-t border-border">
                        <Pagination currentPage={page} totalItems={total} itemsPerPage={size} onPageChange={setPage}
                            onItemsPerPageChange={n => { setSize(n); setPage(1); }} itemsPerPageOptions={[25, 50, 100]} />
                    </div>
                )}
            </section>
        </div>
    );
}
