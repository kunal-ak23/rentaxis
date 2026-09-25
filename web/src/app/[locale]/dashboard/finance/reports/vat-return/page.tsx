"use client";

import { useCallback, useEffect, useState } from "react";
import { useSession } from "next-auth/react";
import { useLocale, useTranslations } from "next-intl";
import { AlertTriangle, CheckCircle2, Download, FileText, Lock, Receipt, ShieldCheck, Unlock } from "lucide-react";
import VatReturnView from "@/components/finance/vat/VatReturnView";
import { serverText } from "@/components/finance/bankrec/serverText";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { ApiError } from "@/lib/api/facilities";
import { fmtAmount } from "@/lib/api/ledger";
import { lastQuarterStart, vatReturnsApi, type VatFiling, type VatReturn } from "@/lib/api/vatReturns";
import { hasPermission, type UserRole } from "@/lib/rbac";

const input = "px-3 py-2 rounded-lg border border-border bg-background text-xs focus:ring-2 focus:ring-primary/20 focus:outline-none";
const button = "flex items-center gap-1.5 px-4 py-2 rounded-lg bg-surface text-foreground border border-border text-xs font-bold hover:bg-input transition-all focus:ring-2 focus:ring-primary/20 focus:outline-none";

/**
 * Finance → Reports → VAT return (#55): a quarter in the FTA VAT 201 layout, the
 * documents behind each box, and marking the quarter filed — which locks VAT
 * dated in it; later corrections are dated in an open quarter.
 */
export default function VatReturnPage() {
    const t = useTranslations("VatReturn");
    const tCommon = useTranslations("Common");
    const locale = useLocale();
    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;
    const allowed = hasPermission(userRole, "canViewVatReturn");
    const canFile = hasPermission(userRole, "canFileVatReturn");

    const [periodStart, setPeriodStart] = useState(lastQuarterStart());
    const [data, setData] = useState<VatReturn | null>(null);
    const [filings, setFilings] = useState<VatFiling[]>([]);
    const [loading, setLoading] = useState(true);
    const [loadError, setLoadError] = useState<string | null>(null);
    const [actionError, setActionError] = useState<string | null>(null);
    const [reference, setReference] = useState("");
    const [busy, setBusy] = useState(false);
    const [confirm, setConfirm] = useState<"file" | "reopen" | null>(null);
    const [reopenReason, setReopenReason] = useState("");

    const load = useCallback(async (start: string) => {
        setLoading(true);
        setLoadError(null);
        try {
            const [r, f] = await Promise.all([vatReturnsApi.get(start), vatReturnsApi.filings()]);
            setData(r);
            setFilings(f);
        } catch (err) {
            setData(null);
            setLoadError(err instanceof ApiError ? serverText(tCommon, err) || err.message : tCommon("loadFailed"));
        } finally {
            setLoading(false);
        }
    }, [tCommon]);

    useEffect(() => {
        if (!allowed) { setLoading(false); return; }
        load(periodStart);
    }, [allowed, periodStart, load]);

    const act = async (fn: () => Promise<unknown>) => {
        setBusy(true);
        setActionError(null);
        try {
            await fn();
            setConfirm(null);
            setReopenReason("");
            await load(periodStart);
        } catch (err) {
            setActionError(err instanceof ApiError ? serverText(tCommon, err) || err.message : tCommon("loadFailed"));
        } finally {
            setBusy(false);
        }
    };

    if (userRole && !allowed) {
        return (
            <div className="max-w-4xl">
                <div className="bg-surface rounded-xl p-12 shadow-sm border border-border text-center">
                    <ShieldCheck size={48} className="mx-auto text-muted mb-4" />
                    <p className="text-sm text-muted">{t("accessDenied")}</p>
                </div>
            </div>
        );
    }

    // Quarters: the last eight calendar quarters, newest first.
    const quarters = Array.from({ length: 8 }, (_, i) => {
        const now = new Date();
        const d = new Date(now.getFullYear(), Math.floor(now.getMonth() / 3) * 3 - 3 * i, 1);
        return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-01`;
    });
    if (!quarters.includes(periodStart)) quarters.push(periodStart);

    return (
        <div>
            <div className="flex items-start justify-between gap-4 mb-6 flex-wrap">
                <div>
                    <h1 className="text-xl font-bold text-foreground tracking-tight mb-1 flex items-center gap-2">
                        <Receipt size={20} className="text-primary" />
                        {t("title")}
                    </h1>
                    <p className="text-xs text-muted font-medium">{t("desc")}</p>
                </div>
                {data && (
                    <div className="flex flex-wrap items-center gap-2">
                        <a className={button} href={vatReturnsApi.pdfUrl(periodStart, "en")} target="_blank" rel="noopener noreferrer"><FileText size={13} />{t("pdfEn")}</a>
                        <a className={button} href={vatReturnsApi.pdfUrl(periodStart, "ar")} target="_blank" rel="noopener noreferrer"><FileText size={13} />{t("pdfAr")}</a>
                        <a className={button} href={vatReturnsApi.csvUrl(periodStart, locale)}><Download size={13} />{t("csv")}</a>
                    </div>
                )}
            </div>

            <div className="flex flex-wrap items-end gap-3 mb-5 bg-surface border border-border rounded-xl p-4">
                <label className="flex flex-col gap-1 text-[11px] font-semibold text-muted">
                    {t("quarter")}
                    <select className={input} value={periodStart} onChange={e => setPeriodStart(e.target.value)} data-testid="vat-quarter">
                        {quarters.map(q => <option key={q} value={q}>{q}</option>)}
                    </select>
                </label>
                {data && (
                    <span data-testid="vat-status"
                          className={`flex items-center gap-1 px-3 py-1.5 rounded-lg text-xs font-semibold border ${
                              data.status === "FILED" ? "bg-success/10 text-success border-success/30" : "bg-input text-muted border-border"}`}>
                        {data.status === "FILED" ? <Lock size={13} /> : <Unlock size={13} />}
                        {t(`status${data.status}`)}
                        {data.filedAt && <> · <bdi dir="ltr">{data.filedAt.slice(0, 10)}</bdi></>}
                        {data.filingReference && <> · <bdi dir="ltr">{data.filingReference}</bdi></>}
                    </span>
                )}
                {data?.outputCheck && (
                    <span data-testid="vat-check"
                          className={`flex items-center gap-1 px-3 py-1.5 rounded-lg text-xs font-semibold border ${
                              data.outputCheck.ok ? "bg-success/10 text-success border-success/30" : "bg-error/10 text-error border-error/30"}`}>
                        {data.outputCheck.ok ? <CheckCircle2 size={13} /> : <AlertTriangle size={13} />}
                        {data.outputCheck.ok ? t("checkOk") : t("checkDiff", { amount: fmtAmount(data.outputCheck.difference) })}
                    </span>
                )}
            </div>

            {loadError && <LoadErrorBanner message={loadError} onRetry={() => load(periodStart)} />}

            {loading ? (
                <div className="space-y-3 animate-pulse">{[1, 2, 3].map(i => <div key={i} className="bg-input rounded-xl h-12" />)}</div>
            ) : data ? (
                <>
                    <VatReturnView data={data} locale={locale} />
                    {data.commercialWithoutVat !== 0 && (
                        <p className="mt-3 text-xs text-warning" data-testid="vat-commercial">
                            {t("commercialWithoutVat", { amount: fmtAmount(data.commercialWithoutVat) })}
                        </p>
                    )}
                    {!!data.inputVatOther && (
                        <p className="mt-2 text-xs text-warning" data-testid="vat-input-other">
                            {t("inputVatOther", { amount: fmtAmount(data.inputVatOther) })}
                        </p>
                    )}
                    {!!data.inputVatOnExempt && (
                        <p className="mt-2 text-xs text-warning" data-testid="vat-input-exempt">
                            {t("inputVatOnExempt", { amount: fmtAmount(data.inputVatOnExempt) })}
                        </p>
                    )}
                    <p className="mt-3 text-xs text-muted">{t("lockNote")}</p>

                    {canFile && data.status !== "FILED" && (
                        <div className="mt-5 flex flex-wrap items-end gap-3 bg-surface border border-border rounded-xl p-4">
                            <label className="flex flex-col gap-1 text-[11px] font-semibold text-muted">
                                {t("reference")}
                                <input className={input} value={reference} onChange={e => setReference(e.target.value)} data-testid="vat-reference" />
                            </label>
                            <button type="button" disabled={busy || !data.canFile} data-testid="vat-file"
                                    onClick={() => setConfirm("file")}
                                    className="flex items-center gap-1.5 px-4 py-2 rounded-lg bg-primary text-primary-foreground text-xs font-bold disabled:opacity-50">
                                <Lock size={13} />{t("markFiled")}
                            </button>
                            {!data.canFile && data.cannotFileReason && (
                                <span className="text-xs text-muted">{serverText(tCommon, { code: data.cannotFileReason, message: "" })}</span>
                            )}
                        </div>
                    )}
                    {canFile && data.status === "FILED" && data.id && (
                        <button type="button" disabled={busy} data-testid="vat-reopen" className={`${button} mt-5`}
                                onClick={() => setConfirm("reopen")}>
                            <Unlock size={13} />{t("reopen")}
                        </button>
                    )}
                    {actionError && <p className="mt-2 text-xs text-error" data-testid="vat-error">{actionError}</p>}
                    {/* PR #361 R1: the app's dialog; re-opening asks for its reason in the dialog. */}
                    <ConfirmDialog
                        isOpen={confirm !== null}
                        onClose={() => setConfirm(null)}
                        isLoading={busy}
                        isDestructive={confirm === "reopen"}
                        title={confirm === "reopen" ? t("reopen") : t("markFiled")}
                        description={confirm === "reopen" ? t("reopenPrompt") : t("fileConfirm")}
                        confirmText={confirm === "reopen" ? t("reopen") : t("markFiled")}
                        cancelText={t("cancel")}
                        confirmTestId="vat-confirm"
                        confirmDisabled={confirm === "reopen" && !reopenReason.trim()}
                        onConfirm={() => {
                            if (confirm === "file") act(() => vatReturnsApi.file(periodStart, reference));
                            if (confirm === "reopen" && data.id) act(() => vatReturnsApi.reopen(data.id!, reopenReason.trim()));
                        }}
                    >
                        {confirm === "reopen" && (
                            <textarea className={`${input} w-full`} rows={3} value={reopenReason} data-testid="vat-reopen-reason"
                                      placeholder={t("reopenReason")} onChange={e => setReopenReason(e.target.value)} />
                        )}
                    </ConfirmDialog>
                </>
            ) : null}

            {filings.length > 0 && (
                <div className="mt-8">
                    <h2 className="text-sm font-bold mb-2">{t("history")}</h2>
                    <ul className="text-xs space-y-1" data-testid="vat-history">
                        {filings.map(f => (
                            <li key={f.id}>
                                <bdi dir="ltr">{f.periodStart} – {f.periodEnd}</bdi> · {t(`status${f.status}`)}
                                {f.netVat != null && <> · <bdi dir="ltr">{fmtAmount(f.netVat)}</bdi></>}
                                {f.reopenReason && <> · {f.reopenReason}</>}
                            </li>
                        ))}
                    </ul>
                </div>
            )}
        </div>
    );
}
