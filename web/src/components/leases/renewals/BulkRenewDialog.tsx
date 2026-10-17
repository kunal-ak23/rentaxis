"use client";

import { useMemo, useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import { CheckCircle, XCircle } from "lucide-react";
import LeaseDialog from "@/components/leases/LeaseDialog";
import { Link } from "@/i18n/routing";
import { serverText } from "@/components/finance/bankrec/serverText";
import { fmtIsoDate, todayIso } from "@/components/leases/leaseMath";
import { formatCurrency } from "@/lib/format";
import { parseMoneyInput } from "@/lib/money";
import { isBeyondManualPostingWindow } from "@/lib/businessDate";
import {
    ApiError, leaseBatchApi,
    type BulkRentRule, type BulkRenewRow, type ChargeType, type LeaseDetail, type LeaseLineInput,
} from "@/lib/api/leasing";
import { BATCH_CHUNK, chunks, ruleExample } from "@/lib/leases/renewalSeason";

type Mode = BulkRentRule["mode"];

const field = "w-full bg-input border border-border rounded-lg px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none";
const label = "block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1";

const num = (s: string): number | null => {
    const n = Number(s.replace(",", "."));
    return s.trim() !== "" && Number.isFinite(n) ? n : null;
};

/** The label a result row carries: unit and tenant, as on the bulk post's results. */
export type RenewTarget = Pick<LeaseDetail, "id" | "unitIdentifier" | "renterName" | "propertyName" | "currentRentAmount" | "rentAmount">;

/**
 * Bulk renew (scale #16): one rule for every selected contract — no change, +x %, or + an
 * amount, whole AED — sent to `POST /leases/renew-batch` in chunks, which drafts each renewal
 * with the single renew. Drafts only: the results link to the Draft list, where the existing
 * bulk post puts them on the books after review.
 */
export default function BulkRenewDialog({ open, targets, chargeTypes, onClose, onDone }: {
    open: boolean;
    targets: RenewTarget[];
    chargeTypes: ChargeType[];
    onClose: () => void;
    /** After a run, so the queue re-reads its stages. */
    onDone: () => void;
}) {
    const t = useTranslations("RenewalSeason");
    const tCommon = useTranslations("Common");
    const locale = useLocale();
    const [mode, setMode] = useState<Mode>("NONE");
    const [percent, setPercent] = useState("");
    const [amount, setAmount] = useState("");
    const [sameTerm, setSameTerm] = useState(true);
    const [months, setMonths] = useState("12");
    const [contractDate, setContractDate] = useState(todayIso());
    const [carryDeposit, setCarryDeposit] = useState(true);
    const [generateGrid, setGenerateGrid] = useState(true);
    const [feeTypeId, setFeeTypeId] = useState("");
    const [feeAmount, setFeeAmount] = useState("");
    const [progress, setProgress] = useState<{ done: number; total: number } | null>(null);
    const [results, setResults] = useState<BulkRenewRow[] | null>(null);
    const [error, setError] = useState<string | null>(null);

    const feeTypes = useMemo(() => chargeTypes.filter(c => c.active !== false && c.behaviour === "FEE"), [chargeTypes]);

    // Mounted fresh for each run (the queue renders it only while open), so every field starts at its default.

    const pct = num(percent);
    const add = (() => { const r = parseMoneyInput(amount, { allowNegative: true }); return r.ok ? r.value : null; })();
    const fee = (() => { const r = parseMoneyInput(feeAmount); return r.ok ? r.value : null; })();
    const termMonths = sameTerm ? null : num(months);
    const ruleReady = mode === "NONE" || (mode === "PERCENT" && pct != null) || (mode === "ADD" && add != null && add !== 0);
    const termReady = sameTerm || (termMonths != null && Number.isInteger(termMonths) && termMonths >= 1 && termMonths <= 120);
    const feeReady = !feeTypeId || (fee != null && fee > 0);
    const dateReady = !!contractDate && !isBeyondManualPostingWindow(contractDate);
    const sample = targets[0];
    const sampleRent = sample ? Number(sample.currentRentAmount ?? sample.rentAmount ?? 0) : 0;
    const sampleNew = sampleRent > 0 ? ruleExample(sampleRent, mode, mode === "PERCENT" ? pct : add) : null;

    const labelOf = (id: string) => {
        const l = targets.find(x => x.id === id);
        return l ? `${l.unitIdentifier ?? "—"} · ${l.renterName ?? "—"}` : id;
    };

    const run = async (ids: string[], keep: BulkRenewRow[] = []) => {
        setError(null);
        setProgress({ done: 0, total: ids.length });
        const rule: BulkRentRule = { mode, percent: mode === "PERCENT" ? pct : null, amount: mode === "ADD" ? add : null };
        const additionalLines: LeaseLineInput[] | null = feeTypeId && fee
            ? [{ chargeTypeId: feeTypeId, grossAmount: fee, discountAmount: 0, vatApplicable: false }] : null;
        const out: BulkRenewRow[] = [];
        try {
            // Sequential chunks: each call is a few seconds, and the progress is the server's own count.
            for (const part of chunks(ids, BATCH_CHUNK)) {
                const rows = await leaseBatchApi.renew({
                    leaseIds: part, rule, termMonths, contractDate, carryDepositForward: carryDeposit, generateGrid, additionalLines,
                });
                out.push(...rows);
                setProgress({ done: out.length, total: ids.length });
            }
        } catch (e) {
            setError(e instanceof ApiError ? (serverText(tCommon, e) || e.message) : t("batchFailed"));
        }
        const byId = new Map(out.map(r => [r.leaseId, r]));
        setResults(keep.length ? keep.map(r => byId.get(r.leaseId) ?? r) : out);
        setProgress(null);
        onDone();
    };

    const failed = (results ?? []).filter(r => !r.ok);
    const okCount = (results ?? []).length - failed.length;
    const showResults = results !== null;

    return (
        <LeaseDialog
            open={open}
            title={showResults ? t("renewResultsTitle") : t("bulkRenewTitle", { n: targets.length })}
            onClose={onClose}
            onConfirm={() => (showResults ? onClose() : run(targets.map(x => x.id)))}
            confirmText={showResults ? t("close") : progress ? t("renewRunning", progress) : t("draftRenewals", { n: targets.length })}
            cancelText={t("cancel")}
            confirmDisabled={!showResults && (!ruleReady || !termReady || !feeReady || !dateReady || targets.length === 0 || !!progress)}
            busy={!!progress}
            confirmTestId="bulk-renew-confirm"
            width="lg"
        >
            {!showResults ? (
                <div className="space-y-4" data-testid="bulk-renew-form">
                    <p className="text-xs text-muted">{t("bulkRenewIntro")}</p>
                    <fieldset>
                        <legend className={label}>{t("ruleLabel")}</legend>
                        <div className="flex flex-wrap gap-2">
                            {(["NONE", "PERCENT", "ADD"] as Mode[]).map(m => (
                                <label key={m} className="flex items-center gap-1.5 text-xs cursor-pointer border border-border rounded-lg px-3 py-2">
                                    <input type="radio" name="bulk-renew-rule" value={m} checked={mode === m} onChange={() => setMode(m)}
                                        data-testid={`bulk-renew-mode-${m}`} />
                                    {t(`rule.${m}`)}
                                </label>
                            ))}
                        </div>
                        {mode === "PERCENT" && (
                            <input className={`${field} mt-2 max-w-[10rem]`} inputMode="decimal" value={percent} onChange={e => setPercent(e.target.value)}
                                aria-label={t("percentLabel")} placeholder="5" data-testid="bulk-renew-percent" />
                        )}
                        {mode === "ADD" && (
                            <input className={`${field} mt-2 max-w-[10rem]`} inputMode="decimal" value={amount} onChange={e => setAmount(e.target.value)}
                                aria-label={t("amountLabel")} placeholder="2500" data-testid="bulk-renew-amount" />
                        )}
                        <p className="mt-2 text-[11px] text-muted">{t("roundingNote")}</p>
                        {sample && sampleNew != null && (
                            <p className="mt-1 text-[11px] text-[var(--ink-600)]" data-testid="bulk-renew-example">
                                {t("example", { unit: sample.unitIdentifier ?? "—", from: formatCurrency(sampleRent), to: formatCurrency(sampleNew) })}
                            </p>
                        )}
                    </fieldset>

                    <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                        <div>
                            <span className={label}>{t("termLabel")}</span>
                            <label className="flex items-center gap-2 text-xs">
                                <input type="checkbox" checked={sameTerm} onChange={e => setSameTerm(e.target.checked)} data-testid="bulk-renew-same-term" />
                                {t("sameTerm")}
                            </label>
                            {!sameTerm && (
                                <input className={`${field} mt-2 max-w-[8rem]`} inputMode="numeric" value={months} onChange={e => setMonths(e.target.value)}
                                    aria-label={t("monthsLabel")} data-testid="bulk-renew-months" />
                            )}
                            {mode === "PERCENT" && !sameTerm && <p className="mt-1 text-[11px] text-warning">{t("percentNeedsSameTerm")}</p>}
                        </div>
                        <label className="block">
                            <span className={label}>{t("contractDate")}</span>
                            <input type="date" className={field} value={contractDate} onChange={e => setContractDate(e.target.value)}
                                data-testid="bulk-renew-contract-date" />
                        </label>
                    </div>

                    <div className="space-y-2">
                        <label className="flex items-center gap-2 text-xs">
                            <input type="checkbox" checked={carryDeposit} onChange={e => setCarryDeposit(e.target.checked)} data-testid="bulk-renew-carry-deposit" />
                            {t("carryDeposit")}
                        </label>
                        <label className="flex items-center gap-2 text-xs">
                            <input type="checkbox" checked={generateGrid} onChange={e => setGenerateGrid(e.target.checked)} data-testid="bulk-renew-grid" />
                            {t("generateGrid")}
                        </label>
                    </div>

                    {feeTypes.length > 0 && (
                        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                            <label className="block">
                                <span className={label}>{t("feeLabel")}</span>
                                <select className={field} value={feeTypeId} onChange={e => setFeeTypeId(e.target.value)} data-testid="bulk-renew-fee-type">
                                    <option value="">{t("noFee")}</option>
                                    {feeTypes.map(c => <option key={c.id} value={c.id}>{(locale === "ar" ? c.nameAr : null) || c.nameEn || c.code}</option>)}
                                </select>
                            </label>
                            {feeTypeId && (
                                <label className="block">
                                    <span className={label}>{t("feeAmount")}</span>
                                    <input className={field} inputMode="decimal" value={feeAmount} onChange={e => setFeeAmount(e.target.value)}
                                        data-testid="bulk-renew-fee-amount" />
                                </label>
                            )}
                        </div>
                    )}
                    <p className="text-[11px] text-muted">{t("draftsOnlyNote")}</p>
                    {error && <p className="text-xs text-error" role="alert">{error}</p>}
                </div>
            ) : (
                <div className="space-y-3" data-testid="bulk-renew-results">
                    <p className="text-xs">{t("renewSummary", { ok: okCount, failed: failed.length })}</p>
                    {error && <p className="text-xs text-error" role="alert">{error}</p>}
                    <div className="flex flex-wrap gap-2">
                        {okCount > 0 && (
                            <Link href="/dashboard/leases?view=draft" className="text-xs font-semibold text-primary hover:underline" data-testid="bulk-renew-review-drafts">
                                {t("reviewDrafts")}
                            </Link>
                        )}
                        {failed.length > 0 && (
                            <button type="button" className="text-xs font-semibold text-primary hover:underline disabled:opacity-50" disabled={!!progress}
                                onClick={() => run(failed.map(r => r.leaseId), results ?? [])} data-testid="bulk-renew-retry">
                                {t("retryFailed")}
                            </button>
                        )}
                    </div>
                    <ul className="space-y-1.5 text-xs max-h-[50vh] overflow-y-auto">
                        {(results ?? []).map(r => (
                            <li key={r.leaseId} className="flex items-start gap-2" data-testid={`bulk-renew-result-${r.leaseId}`} data-ok={r.ok}>
                                {r.ok ? <CheckCircle size={14} className="text-success shrink-0 mt-0.5" /> : <XCircle size={14} className="text-error shrink-0 mt-0.5" />}
                                <span className="min-w-0">
                                    <span className="font-medium">{labelOf(r.leaseId)}</span>{" "}
                                    {r.ok ? (
                                        <Link href={`/dashboard/leases/${r.renewalLeaseId}`} className="text-[var(--ink-600)] hover:underline">
                                            {t("drafted", {
                                                from: r.previousRent != null ? formatCurrency(r.previousRent) : "—",
                                                to: formatCurrency(r.newRent),
                                                start: fmtIsoDate(r.startDate, locale),
                                                end: fmtIsoDate(r.endDate, locale),
                                                cheques: r.chequeRows,
                                            })}
                                        </Link>
                                    ) : (
                                        <span className="text-error">{serverText(tCommon, r) || t("batchFailed")}</span>
                                    )}
                                </span>
                            </li>
                        ))}
                    </ul>
                </div>
            )}
        </LeaseDialog>
    );
}
