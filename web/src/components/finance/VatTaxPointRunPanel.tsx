"use client";

import { useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import { Eye, Loader2, Play } from "lucide-react";
import { fmtAmount } from "@/lib/api/ledger";
import { fmtIsoDate, todayIso } from "@/components/leases/leaseMath";
import { ApiError, vatApi, type VatTaxPointRunResult } from "@/lib/api/leasing";

/**
 * "Run tax points to date" (spec 2026-09-24 §1): posts every PLANNED VAT tax point
 * dated on or before the chosen day as a VTP with its tax invoice — the manual
 * twin of the nightly job, for a month-end that cannot wait for tonight or a lock
 * that `lockThrough` refused because a point was still waiting.
 *
 * Preview first, always: it writes nothing and says what would post. The server
 * refuses a date in the future (a tax point is something that has happened).
 */
export default function VatTaxPointRunPanel() {
    const t = useTranslations("VatSchedule");
    const locale = useLocale();
    const [to, setTo] = useState(todayIso());
    const [busy, setBusy] = useState<"preview" | "run" | null>(null);
    const [result, setResult] = useState<VatTaxPointRunResult | null>(null);
    const [error, setError] = useState<string | null>(null);

    const execute = async (dryRun: boolean) => {
        setBusy(dryRun ? "preview" : "run");
        setError(null);
        try {
            setResult(await vatApi.run(to, dryRun));
        } catch (e) {
            setError(e instanceof ApiError ? e.message : t("runFailed"));
        } finally {
            setBusy(null);
        }
    };

    return (
        <div className="bg-surface border border-border rounded-xl p-4 space-y-3 shadow-sm" data-testid="vat-run-panel">
            <div>
                <h2 className="text-sm font-semibold text-foreground">{t("runTitle")}</h2>
                <p className="text-[11px] text-muted mt-0.5">{t("runIntro")}</p>
            </div>
            <div className="flex flex-wrap items-end gap-3">
                <label className="flex flex-col gap-1">
                    <span className="text-[10px] font-semibold text-muted uppercase tracking-wider">{t("runThrough")}</span>
                    <input
                        type="date"
                        data-testid="vat-run-to"
                        max={todayIso()}
                        value={to}
                        onChange={e => setTo(e.target.value)}
                        className="bg-input border border-border rounded-lg px-2 py-1.5 text-xs"
                    />
                </label>
                <button
                    type="button"
                    data-testid="vat-run-preview"
                    disabled={busy !== null || !to}
                    onClick={() => execute(true)}
                    className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[11px] font-semibold border border-border text-foreground hover:bg-input/40 cursor-pointer disabled:opacity-50"
                >
                    {busy === "preview" ? <Loader2 size={12} className="animate-spin" /> : <Eye size={12} />}
                    {t("preview")}
                </button>
                <button
                    type="button"
                    data-testid="vat-run-post"
                    disabled={busy !== null || !to || !result || !result.preview || result.wouldPost === 0}
                    onClick={() => execute(false)}
                    className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[11px] font-semibold bg-primary text-primary-foreground hover:bg-primary/90 cursor-pointer disabled:opacity-50"
                >
                    {busy === "run" ? <Loader2 size={12} className="animate-spin" /> : <Play size={12} />}
                    {t("run")}
                </button>
            </div>
            {error && (
                <p role="alert" className="text-[11px] text-error" data-testid="vat-run-error">
                    {error}
                </p>
            )}
            {result && (
                <div className="text-xs space-y-1" data-testid="vat-run-result">
                    <p>
                        {result.preview
                            ? t("wouldPost", { count: result.wouldPost, amount: fmtAmount(result.vatAmount), date: fmtIsoDate(to, locale) })
                            : t("posted", { count: result.posted, amount: fmtAmount(result.vatAmount) })}
                    </p>
                    {result.skippedLocked > 0 && (
                        <p className="text-warning">
                            {t("skippedLocked", {
                                count: result.skippedLocked,
                                date: result.booksLockedThrough ? fmtIsoDate(result.booksLockedThrough, locale) : "—",
                            })}
                        </p>
                    )}
                    {result.errors.map(e => (
                        <p key={e} className="text-error">
                            {e}
                        </p>
                    ))}
                </div>
            )}
        </div>
    );
}
