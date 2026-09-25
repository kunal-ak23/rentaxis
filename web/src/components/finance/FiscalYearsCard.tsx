"use client";

import { useCallback, useEffect, useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import { CalendarCheck, Loader2, LockOpen, Lock } from "lucide-react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { ApiError } from "@/lib/api/facilities";
import { fmtAmount, ledgerApi, type FiscalYear, type YearCloseIssue, type YearClosePreview } from "@/lib/api/ledger";
import { formatDate } from "@/lib/format";
import { cn } from "@/lib/utils";

type Props = {
    /** TENANT_ADMIN / SUPER_ADMIN: may re-open a closed year (spec §3). */
    canReopen: boolean;
    /** Called after a close or re-open, which moves the period lock. */
    onChanged?: () => void;
};

/** Amounts and dates inside translated sentences stay LTR (the VoucherForm convention). */
const bdi = { n: (chunks: React.ReactNode) => <bdi dir="ltr">{chunks}</bdi> };

const chip: Record<FiscalYear["status"], string> = {
    OPEN: "bg-input text-muted",
    CLOSED: "bg-success/10 text-success",
    REOPENED: "bg-warning/10 text-warning",
};

/**
 * Spec 2026-09-24 §3: the fiscal years, their result, and Close / Re-open.
 *
 * Close shows what stops it, what only warns, the year's P&L and the Retained
 * Earnings line per property, then posts one closing entry dated the year's last
 * day and locks the year. Re-open reverses that entry and unlocks every period
 * after the day before the year.
 */
export default function FiscalYearsCard({ canReopen, onChanged }: Props) {
    const t = useTranslations("FiscalYears");
    const tCommon = useTranslations("Common");
    const locale = useLocale();
    const [years, setYears] = useState<FiscalYear[]>([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);

    const [closing, setClosing] = useState<number | null>(null);
    const [preview, setPreview] = useState<YearClosePreview | null>(null);
    const [previewError, setPreviewError] = useState<string | null>(null);
    const [override, setOverride] = useState(false);
    const [busy, setBusy] = useState(false);
    const [actionError, setActionError] = useState<string | null>(null);

    const [reopening, setReopening] = useState<FiscalYear | null>(null);
    const [reason, setReason] = useState("");

    const load = useCallback(async () => {
        setLoading(true);
        setError(null);
        try {
            setYears(await ledgerApi.fiscalYears.list());
        } catch (e) {
            setError(e instanceof ApiError ? e.message : tCommon("loadFailed"));
        } finally {
            setLoading(false);
        }
    }, [tCommon]);

    useEffect(() => {
        load();
    }, [load]);

    const label = (y: { fiscalYear: number; periodStart: string; periodEnd: string }) =>
        y.periodStart.slice(0, 4) === y.periodEnd.slice(0, 4)
            ? String(y.fiscalYear)
            : `${y.fiscalYear}/${String(y.fiscalYear + 1).slice(2)}`;

    const issueText = (i: YearCloseIssue) => {
        const key = `issues.${i.code}`;
        return t.has(key) ? t(key, i.args ?? {}) : i.message;
    };

    const openClose = async (fy: number) => {
        setClosing(fy);
        setPreview(null);
        setPreviewError(null);
        setOverride(false);
        setActionError(null);
        try {
            setPreview(await ledgerApi.fiscalYears.preview(fy));
        } catch (e) {
            setPreviewError(e instanceof ApiError ? e.message : tCommon("loadFailed"));
        }
    };

    const close = async () => {
        if (closing == null) return;
        setBusy(true);
        setActionError(null);
        try {
            await ledgerApi.fiscalYears.close(closing, override);
            setClosing(null);
            await load();
            onChanged?.();
        } catch (e) {
            setActionError(e instanceof ApiError ? e.message : tCommon("loadFailed"));
        } finally {
            setBusy(false);
        }
    };

    const reopen = async () => {
        if (!reopening) return;
        setBusy(true);
        setActionError(null);
        try {
            await ledgerApi.fiscalYears.reopen(reopening.fiscalYear, reason.trim());
            setReopening(null);
            setReason("");
            await load();
            onChanged?.();
        } catch (e) {
            setActionError(e instanceof ApiError ? e.message : tCommon("loadFailed"));
        } finally {
            setBusy(false);
        }
    };

    const latestClosed = years.filter(y => y.status === "CLOSED").map(y => y.fiscalYear).sort((a, b) => b - a)[0];
    const blocked = !preview || preview.blockers.length > 0 || (preview.warnings.length > 0 && !override);
    const dayBefore = (iso: string) => {
        const d = new Date(`${iso}T00:00:00Z`);
        d.setUTCDate(d.getUTCDate() - 1);
        return d.toISOString().slice(0, 10);
    };

    return (
        <div className="bg-surface border border-border rounded-xl shadow-sm p-5 mt-6" data-testid="fiscal-years-card">
            <h2 className="text-sm font-bold text-foreground mb-1 flex items-center gap-2">
                <CalendarCheck size={15} className="text-primary" />
                {t("title")}
            </h2>
            <p className="text-xs text-muted font-medium mb-4">{t("description")}</p>

            {error && <p role="alert" className="text-xs font-semibold text-error mb-3">{error}</p>}
            {loading ? (
                <div className="bg-input rounded-lg h-20 animate-pulse" />
            ) : (
                <div className="overflow-x-auto">
                    <table className="w-full min-w-[560px] text-xs" data-testid="fiscal-years-table">
                        <thead>
                            <tr className="text-[10px] text-muted uppercase tracking-wider">
                                <th className="text-start py-2 pe-3">{t("year")}</th>
                                <th className="text-start py-2 pe-3">{t("period")}</th>
                                <th className="text-start py-2 pe-3">{t("status")}</th>
                                <th className="text-end py-2 pe-3">{t("netResult")}</th>
                                <th className="text-end py-2" />
                            </tr>
                        </thead>
                        <tbody>
                            {years.map(y => (
                                <tr key={y.fiscalYear} className="border-t border-border" data-testid={`fiscal-year-${y.fiscalYear}`}>
                                    <td className="py-2 pe-3 font-semibold">{label(y)}</td>
                                    <td className="py-2 pe-3 tabular-nums">{formatDate(y.periodStart)} – {formatDate(y.periodEnd)}</td>
                                    <td className="py-2 pe-3">
                                        <span className={cn("inline-block rounded px-1.5 py-0.5 text-[10px] font-bold", chip[y.status])}>
                                            {t(`statuses.${y.status}`)}
                                        </span>
                                        {y.journalNumber && y.status === "CLOSED" && (
                                            <span className="ms-2 text-[10px] text-muted">{y.journalNumber}</span>
                                        )}
                                    </td>
                                    <td className="py-2 pe-3 text-end tabular-nums"><bdi dir="ltr">{fmtAmount(y.netResult)}</bdi></td>
                                    <td className="py-2 text-end">
                                        {y.status !== "CLOSED" ? (
                                            <button type="button" data-testid={`fiscal-year-close-${y.fiscalYear}`}
                                                onClick={() => openClose(y.fiscalYear)}
                                                className="inline-flex items-center gap-1 px-2.5 py-1 rounded-lg border border-border text-[11px] font-semibold hover:bg-input cursor-pointer">
                                                <Lock size={12} /> {t("close")}
                                            </button>
                                        ) : canReopen && y.fiscalYear === latestClosed ? (
                                            <button type="button" data-testid={`fiscal-year-reopen-${y.fiscalYear}`}
                                                onClick={() => { setReopening(y); setReason(""); setActionError(null); }}
                                                className="inline-flex items-center gap-1 px-2.5 py-1 rounded-lg border border-border text-[11px] font-semibold hover:bg-input cursor-pointer">
                                                <LockOpen size={12} /> {t("reopen")}
                                            </button>
                                        ) : null}
                                    </td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>
            )}

            <ConfirmDialog
                isOpen={closing != null}
                onClose={() => setClosing(null)}
                onConfirm={close}
                isLoading={busy}
                confirmDisabled={blocked}
                title={closing == null ? "" : t("closeTitle", { year: closing })}
                description={t("closeDescription")}
                confirmText={t("close")}
                confirmTestId="fiscal-close-confirm"
                cancelText={t("cancel")}
            >
                {!preview && !previewError && <Loader2 size={16} className="animate-spin text-muted" />}
                {previewError && <p role="alert" className="text-xs font-semibold text-error">{previewError}</p>}
                {preview && (
                    <div className="space-y-3 text-xs" data-testid="fiscal-close-preview">
                        {preview.blockers.length > 0 && (
                            <ul className="space-y-1" data-testid="fiscal-close-blockers">
                                {preview.blockers.map(b => (
                                    <li key={b.code} className="text-error font-semibold">{issueText(b)}</li>
                                ))}
                            </ul>
                        )}
                        {preview.warnings.length > 0 && (
                            <div className="rounded-lg border border-warning/30 bg-warning/10 px-3 py-2 space-y-1">
                                {preview.warnings.map(w => <p key={w.code} className="text-warning">{issueText(w)}</p>)}
                                <label className="flex items-center gap-2 text-foreground">
                                    <input type="checkbox" data-testid="fiscal-close-override" checked={override}
                                        onChange={e => setOverride(e.target.checked)} />
                                    {t("override")}
                                </label>
                            </div>
                        )}
                        <dl className="grid grid-cols-2 gap-x-4 gap-y-1 tabular-nums">
                            <dt className="text-muted">{t("income")}</dt><dd className="text-end">{fmtAmount(preview.income)}</dd>
                            <dt className="text-muted">{t("expense")}</dt><dd className="text-end">{fmtAmount(preview.expense)}</dd>
                            <dt className="font-semibold">{t("netResult")}</dt>
                            <dd className="text-end font-semibold" data-testid="fiscal-close-net">{fmtAmount(preview.netResult)}</dd>
                        </dl>
                        {preview.retainedEarnings.length > 0 && (
                            <div>
                                <div className="text-[10px] font-semibold text-muted uppercase tracking-wider mb-1">{t("retainedEarnings")}</div>
                                <ul className="space-y-0.5 tabular-nums" data-testid="fiscal-close-retained">
                                    {preview.retainedEarnings.map(r => (
                                        <li key={r.propertyId ?? "none"}>
                                            <div className="flex justify-between gap-3">
                                                <span>{r.propertyName ?? t("noProperty")}</span>
                                                <span>{r.profit >= 0 ? t.rich("credit", { amount: fmtAmount(r.profit), ...bdi }) : t.rich("debit", { amount: fmtAmount(-r.profit), ...bdi })}</span>
                                            </div>
                                            {/* F15-01: an earlier open year's result, apart from this year's. */}
                                            {(r.broughtForward ?? 0) !== 0 && (
                                                <div className="flex justify-between gap-3 text-muted" data-testid="fiscal-close-brought-forward">
                                                    <span>{t("broughtForward")}</span>
                                                    <span>{(r.broughtForward ?? 0) >= 0
                                                        ? t.rich("credit", { amount: fmtAmount(r.broughtForward ?? 0), ...bdi })
                                                        : t.rich("debit", { amount: fmtAmount(-(r.broughtForward ?? 0)), ...bdi })}</span>
                                                </div>
                                            )}
                                        </li>
                                    ))}
                                </ul>
                            </div>
                        )}
                        {preview.lockAfter && (
                            <p className="text-muted">{t.rich("lockAfter", { date: formatDate(preview.lockAfter), ...bdi })}</p>
                        )}
                    </div>
                )}
                {actionError && <p role="alert" className="text-xs font-semibold text-error">{actionError}</p>}
            </ConfirmDialog>

            <ConfirmDialog
                isOpen={reopening != null}
                onClose={() => setReopening(null)}
                onConfirm={reopen}
                isLoading={busy}
                isDestructive
                confirmDisabled={!reason.trim()}
                title={reopening ? t("reopenTitle", { year: label(reopening) }) : ""}
                confirmText={t("reopen")}
                confirmTestId="fiscal-reopen-confirm"
                cancelText={t("cancel")}
            >
                {reopening && (
                    <p className="text-xs text-muted" data-testid="fiscal-reopen-warning">
                        {t.rich("reopenWarning", { date: formatDate(dayBefore(reopening.periodStart)), ...bdi })}
                    </p>
                )}
                <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1" htmlFor="fiscal-reopen-reason">
                    {t("reason")}
                </label>
                <textarea id="fiscal-reopen-reason" data-testid="fiscal-reopen-reason" dir={locale === "ar" ? "rtl" : "ltr"}
                    className="w-full bg-input border border-border rounded-lg px-3 py-2 text-xs"
                    value={reason} onChange={e => setReason(e.target.value)} rows={2} />
                {actionError && <p role="alert" className="text-xs font-semibold text-error">{actionError}</p>}
            </ConfirmDialog>
        </div>
    );
}
