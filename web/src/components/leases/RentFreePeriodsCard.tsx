"use client";

import { useEffect, useMemo, useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import { Gift, Loader2, Plus, Trash2 } from "lucide-react";
import { ApiError, leaseApi, type LeaseDetail, type RentFreePeriod } from "@/lib/api/leasing";
import { fmtIsoDate } from "@/components/leases/leaseMath";

const field =
    "w-full bg-input border border-border rounded-lg px-2 py-1.5 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none";
const label = "block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1";

type Draft = { fromDate: string; toDate: string; concessionOverride: string; note: string };

const fmt = (n: number, locale: string) =>
    n.toLocaleString(locale === "ar" ? "ar-AE" : "en-AE", { minimumFractionDigits: 2, maximumFractionDigits: 2 });

function daysBetween(from: string, to: string): number {
    if (!from || !to) return 0;
    const ms = Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`);
    return Number.isFinite(ms) && ms >= 0 ? Math.round(ms / 86_400_000) + 1 : 0;
}

/** Headline × free days ÷ term days, to the fil — the server's rule (spec §4b). */
export function computedConcession(headline: number, freeDays: number, termDays: number): number {
    if (termDays <= 0 || freeDays <= 0) return 0;
    return Math.round((headline * freeDays * 100) / termDays) / 100;
}

type Props = {
    lease: LeaseDetail;
    /** A DRAFT the viewer may edit. Otherwise the periods are shown read-only (or nothing, when there are none). */
    editable: boolean;
    onSaved: (lease: LeaseDetail) => void;
};

/**
 * Spec 2026-09-24 §4b (#50): the contract's rent-free windows. The rent line keeps
 * its headline for the whole term; the concession comes off what the renter pays.
 * Income is recognised straight-line over the whole term, free months included.
 */
export default function RentFreePeriodsCard({ lease, editable, onSaved }: Props) {
    const t = useTranslations("RentFree");
    const locale = useLocale();
    const saved = useMemo(() => lease.rentFreePeriods ?? [], [lease.rentFreePeriods]);
    const [rows, setRows] = useState<Draft[]>([]);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        setRows(saved.map(p => ({
            fromDate: p.fromDate,
            toDate: p.toDate,
            concessionOverride: p.concessionOverride == null ? "" : String(p.concessionOverride),
            note: p.note ?? "",
        })));
    }, [saved]);

    const rentLine = lease.lines.find(l => l.behaviour === "RENT" && !l.addendumId);
    const headline = rentLine?.grossAmount ?? 0;
    const termDays = daysBetween(lease.startDate, lease.endDate);

    if (!editable && saved.length === 0) return null;

    const concessionOf = (r: Draft) =>
        r.concessionOverride.trim() !== "" ? Number(r.concessionOverride) : computedConcession(headline, daysBetween(r.fromDate, r.toDate), termDays);
    const total = Math.round(rows.reduce((s, r) => s + (concessionOf(r) || 0), 0) * 100) / 100;

    const save = async () => {
        setBusy(true);
        setError(null);
        try {
            const body: RentFreePeriod[] = rows.map(r => ({
                fromDate: r.fromDate,
                toDate: r.toDate,
                concessionOverride: r.concessionOverride.trim() === "" ? null : Number(r.concessionOverride),
                note: r.note.trim() || null,
            }));
            onSaved(await leaseApi.setRentFreePeriods(lease.id, body));
        } catch (e) {
            setError(e instanceof ApiError ? e.message : t("saveFailed"));
        } finally {
            setBusy(false);
        }
    };

    const update = (i: number, patch: Partial<Draft>) => setRows(prev => prev.map((r, j) => (j === i ? { ...r, ...patch } : r)));

    return (
        <div className="bg-surface border border-border rounded-xl shadow-sm p-4" data-testid="rent-free-card">
            <div className="flex items-center gap-2 mb-1">
                <Gift size={13} className="text-primary" />
                <h3 className="text-xs font-bold text-foreground">{t("title")}</h3>
            </div>
            <p className="text-[11px] text-muted mb-3">{t("hint")}</p>

            {!editable ? (
                <ul className="space-y-1">
                    {saved.map(p => (
                        <li key={p.id ?? p.fromDate} className="text-xs text-foreground" data-testid="rent-free-row">
                            {fmtIsoDate(p.fromDate, locale)} – {fmtIsoDate(p.toDate, locale)}
                            {" · "}{t("days", { days: p.days ?? daysBetween(p.fromDate, p.toDate) })}
                            {" · "}{t("concession", { amount: fmt(p.concession ?? 0, locale) })}
                            {p.note ? <span className="text-muted"> · {p.note}</span> : null}
                        </li>
                    ))}
                </ul>
            ) : (
                <div className="space-y-2">
                    {rows.map((r, i) => (
                        <div key={i} className="grid gap-2 sm:grid-cols-[1fr_1fr_1fr_1.4fr_auto] items-end" data-testid="rent-free-row">
                            <div>
                                <label className={label} htmlFor={`rf-from-${i}`}>{t("from")}</label>
                                <input id={`rf-from-${i}`} type="date" className={field} value={r.fromDate}
                                    min={lease.startDate} max={lease.endDate}
                                    onChange={e => update(i, { fromDate: e.target.value })} />
                            </div>
                            <div>
                                <label className={label} htmlFor={`rf-to-${i}`}>{t("to")}</label>
                                <input id={`rf-to-${i}`} type="date" className={field} value={r.toDate}
                                    min={r.fromDate || lease.startDate} max={lease.endDate}
                                    onChange={e => update(i, { toDate: e.target.value })} />
                            </div>
                            <div>
                                <label className={label} htmlFor={`rf-override-${i}`}>{t("override")}</label>
                                <input id={`rf-override-${i}`} type="number" min={0} step="0.01" className={`${field} text-end`}
                                    placeholder={fmt(computedConcession(headline, daysBetween(r.fromDate, r.toDate), termDays), "en")}
                                    value={r.concessionOverride}
                                    data-testid={`rent-free-override-${i}`}
                                    onChange={e => update(i, { concessionOverride: e.target.value })} />
                            </div>
                            <div>
                                <label className={label} htmlFor={`rf-note-${i}`}>{t("note")}</label>
                                <input id={`rf-note-${i}`} className={field} value={r.note}
                                    onChange={e => update(i, { note: e.target.value })} />
                            </div>
                            <button type="button" aria-label={t("remove")}
                                onClick={() => setRows(prev => prev.filter((_, j) => j !== i))}
                                className="p-2 rounded-lg text-muted hover:text-error hover:bg-input cursor-pointer">
                                <Trash2 size={13} />
                            </button>
                        </div>
                    ))}
                    <button type="button" data-testid="rent-free-add"
                        onClick={() => setRows(prev => [...prev, { fromDate: lease.startDate, toDate: "", concessionOverride: "", note: "" }])}
                        className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg text-[11px] font-semibold border border-border hover:bg-input/40 cursor-pointer">
                        <Plus size={12} /> {t("add")}
                    </button>
                    <div className="flex flex-wrap items-center justify-between gap-2 pt-2 border-t border-border">
                        <p className="text-[11px] text-muted tabular-nums" data-testid="rent-free-summary">
                            {t("summary", { headline: fmt(headline, locale), concession: fmt(total, locale), payable: fmt(Math.max(0, headline - (rentLine?.discountAmount ?? 0) - total), locale) })}
                        </p>
                        <button type="button" data-testid="rent-free-save" onClick={save}
                            disabled={busy || rows.some(r => !r.fromDate || !r.toDate)}
                            className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-primary text-primary-foreground text-[11px] font-bold cursor-pointer disabled:opacity-50">
                            {busy && <Loader2 size={12} className="animate-spin" />}
                            {t("save")}
                        </button>
                    </div>
                    {error && <p role="alert" className="text-[11px] font-semibold text-error">{error}</p>}
                </div>
            )}
        </div>
    );
}
