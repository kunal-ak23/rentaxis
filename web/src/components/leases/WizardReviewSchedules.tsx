"use client";

import { useEffect, useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import { Loader2 } from "lucide-react";
import { fmtAmount } from "@/lib/api/ledger";
import { ApiError, leaseApi, type Cheque, type ChequePreviewRow, type GenerateChequesRequest, type RentMonth } from "@/lib/api/leasing";
import { fmtIsoDate } from "@/components/leases/leaseMath";

type Props = {
    leaseId: string;
    /** The rows added on the Cheques step; empty = show the suggestion. */
    cheques: Cheque[];
    /** What "Generate cheques" would be asked for: the Terms step's count, first due date and distribution. */
    request: GenerateChequesRequest;
    /** First due date was left empty, so the suggestion starts on the start date. */
    firstDueDefaulted: boolean;
    /** Generate the suggested schedule (the Cheques step's own generator). */
    onUseSchedule: (request: GenerateChequesRequest) => void;
    busy?: boolean;
};

const table = "w-full min-w-[420px] text-xs";
const th = "text-start font-semibold text-muted py-1.5 pe-3 text-[10px] uppercase tracking-wider";
const td = "py-1.5 pe-3";
const num = "py-1.5 pe-3 text-end tabular-nums";

/**
 * Owner request (2026-09-29): the New Contract wizard's Review step shows the rent
 * month by month and the cheques — or, when none were added, the schedule the
 * generator would create. Both are the backend's own answers
 * (`GET /leases/{id}/recognition/preview` runs ProrationEngine, `POST
 * /leases/{id}/cheques/preview` runs the generator), so nothing here does the maths.
 */
export default function WizardReviewSchedules({ leaseId, cheques, request, firstDueDefaulted, onUseSchedule, busy }: Props) {
    const t = useTranslations("Leasing");
    const locale = useLocale();
    const [months, setMonths] = useState<RentMonth[] | null>(null);
    // Keyed by the request it answers, so a changed count never shows the old rows.
    const [answer, setAnswer] = useState<{ key: string; rows: ChequePreviewRow[] } | null>(null);
    const [error, setError] = useState<string | null>(null);
    const requestKey = JSON.stringify(request);
    const suggested = answer?.key === requestKey ? answer.rows : null;

    useEffect(() => {
        let cancelled = false;
        leaseApi.rentSchedulePreview(leaseId)
            .then(m => { if (!cancelled) setMonths(m); })
            .catch(e => { if (!cancelled) setError(e instanceof ApiError ? e.message : t("reviewPreviewFailed")); });
        return () => { cancelled = true; };
    }, [leaseId, t]);

    useEffect(() => {
        if (cheques.length > 0) return;
        let cancelled = false;
        leaseApi.previewCheques(leaseId, JSON.parse(requestKey) as GenerateChequesRequest)
            .then(r => { if (!cancelled) setAnswer({ key: requestKey, rows: r }); })
            .catch(e => { if (!cancelled) setError(e instanceof ApiError ? e.message : t("reviewPreviewFailed")); });
        return () => { cancelled = true; };
    }, [leaseId, requestKey, cheques.length, t]);

    const monthName = (iso: string) =>
        new Date(`${iso.slice(0, 10)}T00:00:00Z`).toLocaleDateString(locale === "ar" ? "ar-AE" : "en-GB",
            { month: "long", year: "numeric", timeZone: "UTC" });
    const sum = (xs: number[]) => Math.round(xs.reduce((a, b) => a + b, 0) * 100) / 100;

    return (
        <div className="space-y-4">
            {error && <p role="alert" className="text-xs text-error" data-testid="review-preview-error">{error}</p>}

            <section className="rounded-xl border border-border px-4 py-3" data-testid="review-monthly-rent">
                <h3 className="text-xs font-semibold text-foreground mb-1">{t("reviewMonthlyRent")}</h3>
                <p className="text-[11px] text-muted mb-2">{t("reviewMonthlyRentHint")}</p>
                {months === null && !error ? <Loader2 size={14} className="animate-spin text-muted" /> : (
                    <div className="overflow-x-auto">
                        <table className={table}>
                            <thead><tr>
                                <th className={th}>{t("reviewMonth")}</th>
                                <th className={`${th} text-end`}>{t("reviewDays")}</th>
                                <th className={`${th} text-end`}>{t("reviewAmount")}</th>
                            </tr></thead>
                            <tbody>
                                {(months ?? []).map(m => (
                                    <tr key={m.periodStart} className="border-t border-border" data-testid="review-month">
                                        <td className={td}>{monthName(m.periodStart)}</td>
                                        <td className={num}>{m.days}</td>
                                        <td className={num}><bdi dir="ltr">{fmtAmount(m.amount)}</bdi></td>
                                    </tr>
                                ))}
                                <tr className="border-t-2 border-border font-semibold">
                                    <td className={td}>{t("reviewTotal")}</td>
                                    <td className={num}>{sum((months ?? []).map(m => m.days))}</td>
                                    <td className={num} data-testid="review-monthly-total"><bdi dir="ltr">{fmtAmount(sum((months ?? []).map(m => m.amount)))}</bdi></td>
                                </tr>
                            </tbody>
                        </table>
                    </div>
                )}
            </section>

            {cheques.length > 0 ? (
                <section className="rounded-xl border border-border px-4 py-3" data-testid="review-cheques">
                    <h3 className="text-xs font-semibold text-foreground mb-2">{t("reviewCheques")}</h3>
                    <div className="overflow-x-auto">
                        <table className={table}>
                            <thead><tr>
                                <th className={th}>{t("reviewChequeNo")}</th>
                                <th className={th}>{t("reviewDueDate")}</th>
                                <th className={th}>{t("reviewBank")}</th>
                                <th className={`${th} text-end`}>{t("reviewAmount")}</th>
                            </tr></thead>
                            <tbody>
                                {cheques.map(c => (
                                    <tr key={c.id} className="border-t border-border" data-testid="review-cheque">
                                        <td className={td}><bdi dir="ltr">{c.chequeNumber ?? "—"}</bdi></td>
                                        <td className={td}>{fmtIsoDate(c.chequeDate, locale)}</td>
                                        <td className={td}>{c.payeeBank ?? "—"}</td>
                                        <td className={num}><bdi dir="ltr">{fmtAmount(c.amount)}</bdi></td>
                                    </tr>
                                ))}
                                <tr className="border-t-2 border-border font-semibold">
                                    <td className={td} colSpan={3}>{t("reviewTotal")}</td>
                                    <td className={num} data-testid="review-cheques-total"><bdi dir="ltr">{fmtAmount(sum(cheques.map(c => c.amount)))}</bdi></td>
                                </tr>
                            </tbody>
                        </table>
                    </div>
                </section>
            ) : (
                <section className="rounded-xl border border-dashed border-border px-4 py-3" data-testid="review-suggested">
                    <h3 className="text-xs font-semibold text-foreground mb-1">{t("reviewSuggested")}</h3>
                    <p className="text-[11px] text-muted mb-2">
                        {t("reviewSuggestedHint", { count: request.installments ?? 0 })}
                        {firstDueDefaulted && <> {t("reviewFirstDueDefaulted")}</>}
                    </p>
                    {suggested === null && !error ? <Loader2 size={14} className="animate-spin text-muted" /> : (
                        <div className="overflow-x-auto">
                            <table className={table}>
                                <thead><tr>
                                    <th className={th}>#</th>
                                    <th className={th}>{t("reviewDueDate")}</th>
                                    <th className={th}>{t("reviewFor")}</th>
                                    <th className={`${th} text-end`}>{t("reviewAmount")}</th>
                                </tr></thead>
                                <tbody>
                                    {(suggested ?? []).map(r => (
                                        <tr key={r.seqNo} className="border-t border-border" data-testid="review-suggested-row">
                                            <td className={td}>{r.seqNo}</td>
                                            <td className={td}>{fmtIsoDate(r.chequeDate, locale)}</td>
                                            <td className={td}><bdi dir="ltr">{r.narration}</bdi></td>
                                            <td className={num}><bdi dir="ltr">{fmtAmount(r.amount)}</bdi></td>
                                        </tr>
                                    ))}
                                    <tr className="border-t-2 border-border font-semibold">
                                        <td className={td} colSpan={3}>{t("reviewTotal")}</td>
                                        <td className={num} data-testid="review-suggested-total"><bdi dir="ltr">{fmtAmount(sum((suggested ?? []).map(r => r.amount)))}</bdi></td>
                                    </tr>
                                </tbody>
                            </table>
                        </div>
                    )}
                    <button type="button" onClick={() => onUseSchedule(request)} disabled={busy || !suggested?.length}
                        data-testid="review-use-schedule"
                        className="mt-2 inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[11px] font-semibold border border-primary text-primary hover:bg-primary/5 disabled:opacity-50 cursor-pointer">
                        {t("reviewUseSchedule")}
                    </button>
                </section>
            )}
        </div>
    );
}
