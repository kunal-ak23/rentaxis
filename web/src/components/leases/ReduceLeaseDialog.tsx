"use client";

import { useEffect, useMemo, useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import LeaseDialog from "./LeaseDialog";
import ChequeRowsEditor, { stripKey, type ChequeDraft } from "@/components/cheques/ChequeRowsEditor";
import { chequeRowsAreValid } from "@/components/cheques/chequeRowRules";
import { serverText } from "@/components/finance/bankrec/serverText";
import { fmtAmount } from "@/lib/api/ledger";
import { fmtIsoDate, round2, todayIso } from "./leaseMath";
import {
    ApiError,
    leaseApi,
    type AddendumResponse,
    type LeaseDetail,
    type LeaseLine,
    type ReduceLeaseInput,
    type ReductionPreview,
} from "@/lib/api/leasing";

/**
 * F14-32: reduce or remove a charge for the rest of the term, as a numbered
 * credit addendum — even after cheques have cleared, because nothing on the books
 * is rewritten. The server prices it per day (what the line still has to earn from
 * the effective date, less the rest of the term at the new rate) and the preview
 * shows it before anything posts. The excess either stays on the renter's account
 * as a credit, or is settled by handing back uncleared instalments (and adding
 * smaller ones) that come to the credit exactly.
 */

const field =
    "w-full bg-input border border-border rounded-lg px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200";
const label = "block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5";
const th = "px-2 py-1.5 text-[10px] font-semibold text-muted uppercase tracking-wider text-start";
const td = "px-2 py-1.5 text-xs";
const bdi = (v: number) => <bdi dir="ltr">{fmtAmount(v)}</bdi>;

type Props = {
    open: boolean;
    lease: LeaseDetail;
    onClose: () => void;
    onReduced: (res: AddendumResponse) => void;
};

/** A line a credit addendum can cut: rent, or a fee the lease earns over the term. */
export function reducibleLines(lines: LeaseLine[]): LeaseLine[] {
    return lines.filter(l => {
        if (l.behaviour === "RENT") return true;
        if (l.behaviour !== "FEE") return false;
        const r = l.postedRecognition ?? l.recognition ?? "RENT_LIKE";
        return r === "RENT_LIKE" || r === "PASS_THROUGH";
    });
}

export default function ReduceLeaseDialog({ open, lease, onClose, onReduced }: Props) {
    const t = useTranslations("Leasing");
    const tCommon = useTranslations("Common");
    const locale = useLocale();
    const candidates = useMemo(() => reducibleLines(lease.lines ?? []), [lease.lines]);

    const [effectiveFrom, setEffectiveFrom] = useState("");
    const [contractDate, setContractDate] = useState(todayIso());
    const [reason, setReason] = useState("");
    const [ejariNumber, setEjariNumber] = useState("");
    /** lineId → the new amount as typed; absent = not being cut. */
    const [cuts, setCuts] = useState<Record<string, string>>({});
    const [excess, setExcess] = useState<"CREDIT" | "CHEQUES">("CREDIT");
    const [returned, setReturned] = useState<string[]>([]);
    const [cheques, setCheques] = useState<ChequeDraft[]>([]);
    const [preview, setPreview] = useState<ReductionPreview | null>(null);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        if (!open) return;
        setEffectiveFrom("");
        setContractDate(todayIso());
        setReason("");
        setEjariNumber("");
        setCuts({});
        setExcess("CREDIT");
        setReturned([]);
        setCheques([]);
        setPreview(null);
        setError(null);
    }, [open]);

    const request: ReduceLeaseInput | null = useMemo(() => {
        const lines = Object.entries(cuts)
            .filter(([, v]) => v.trim() !== "" && Number.isFinite(Number(v)))
            .map(([lineId, v]) => ({ lineId, newAmount: Number(v) }));
        if (!effectiveFrom || lines.length === 0) return null;
        return {
            effectiveFrom,
            contractDate: contractDate || null,
            reason: reason.trim() || null,
            ejariNumber: ejariNumber.trim() || null,
            lines,
            excess,
            returnChequeIds: excess === "CHEQUES" ? returned : [],
            cheques: excess === "CHEQUES" ? cheques.map(stripKey) : [],
        };
    }, [cuts, effectiveFrom, contractDate, reason, ejariNumber, excess, returned, cheques]);

    useEffect(() => {
        if (!open || !request) {
            setPreview(null);
            return;
        }
        let live = true;
        const timer = setTimeout(() => {
            leaseApi.reductionPreview(lease.id, request)
                .then(p => { if (live) setPreview(p); })
                .catch(e => { if (live) setError(e instanceof ApiError ? serverText(tCommon, e) || e.message : t("reduction.failed")); });
        }, 300);
        return () => { live = false; clearTimeout(timer); };
    }, [open, request, lease.id, t, tCommon]);

    const problems = preview?.problems ?? [];
    const gapOk = excess === "CREDIT" || Math.abs(preview?.gap ?? 1) < 0.005;
    // Handing cheques back with no replacement is complete on its own; rows typed must be valid.
    const rowsOk = excess === "CREDIT" || cheques.length === 0 || chequeRowsAreValid(cheques.map(stripKey));
    const confirmDisabled = !request || !preview || problems.length > 0 || !gapOk || !rowsOk;
    const newRowsTarget = round2((preview?.returnedTotal ?? 0) - (preview?.creditTotal ?? 0));

    const submit = async () => {
        if (!request) return;
        setBusy(true);
        setError(null);
        try {
            onReduced(await leaseApi.reduce(lease.id, request));
        } catch (e) {
            setError(e instanceof ApiError ? serverText(tCommon, e) || e.message : t("reduction.failed"));
        } finally {
            setBusy(false);
        }
    };

    const lineName = (l: LeaseLine) =>
        (locale === "ar" ? l.chargeTypeNameAr || l.chargeTypeName : l.chargeTypeName) || l.chargeTypeCode;

    return (
        <LeaseDialog
            open={open}
            title={t("reduction.title")}
            onClose={onClose}
            onConfirm={submit}
            confirmText={t("reduction.confirm")}
            cancelText={t("cancel")}
            confirmDisabled={confirmDisabled}
            busy={busy}
            confirmTestId="reduce-confirm"
            width="xl"
        >
            <div className="space-y-4" data-testid="reduce-dialog">
                <p className="text-[11px] text-muted">{t("reduction.hint")}</p>
                <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                    <div>
                        <label className={label} htmlFor="reduce-effective-from">{t("effectiveFrom")}</label>
                        <input id="reduce-effective-from" data-testid="reduce-effective-from" type="date"
                               min={lease.startDate} max={lease.endDate} className={field}
                               value={effectiveFrom} onChange={e => setEffectiveFrom(e.target.value)} />
                    </div>
                    <div>
                        <label className={label} htmlFor="reduce-contract-date">{t("contractDate")}</label>
                        <input id="reduce-contract-date" type="date" className={field}
                               value={contractDate} onChange={e => setContractDate(e.target.value)} />
                    </div>
                    <div>
                        <label className={label} htmlFor="reduce-reason">{t("addendumReason")}</label>
                        <input id="reduce-reason" className={field} value={reason} onChange={e => setReason(e.target.value)} />
                    </div>
                    <div>
                        <label className={label} htmlFor="reduce-ejari">{t("ejariNumberOptional")}</label>
                        <input id="reduce-ejari" className={field} value={ejariNumber} onChange={e => setEjariNumber(e.target.value)} />
                    </div>
                </div>

                <section>
                    <h4 className="text-[11px] font-semibold text-muted uppercase tracking-wider mb-2">{t("reduction.lines")}</h4>
                    <div className="overflow-x-auto">
                        <table className="w-full min-w-[560px]">
                            <thead>
                                <tr>
                                    <th className={th}>{t("particulars")}</th>
                                    <th className={`${th} text-end`}>{t("reduction.current")}</th>
                                    <th className={th}>{t("reduction.newAmount")}</th>
                                    <th className={`${th} text-end`}>{t("reduction.credit")}</th>
                                </tr>
                            </thead>
                            <tbody>
                                {candidates.map(l => {
                                    const cut = cuts[l.id];
                                    const priced = preview?.lines.find(p => p.lineId === l.id);
                                    return (
                                        <tr key={l.id} className="border-t border-border" data-testid={`reduce-line-${l.id}`}>
                                            <td className={td}>
                                                <label className="inline-flex items-center gap-2 cursor-pointer">
                                                    <input type="checkbox" data-testid={`reduce-pick-${l.id}`}
                                                           checked={cut !== undefined}
                                                           onChange={e => setCuts(c => {
                                                               const next = { ...c };
                                                               if (e.target.checked) next[l.id] = String(l.netAmount);
                                                               else delete next[l.id];
                                                               return next;
                                                           })} />
                                                    {lineName(l)}
                                                </label>
                                            </td>
                                            <td className={`${td} text-end tabular-nums`}>{bdi(l.netAmount)}</td>
                                            <td className={td}>
                                                {cut !== undefined && (
                                                    <span className="inline-flex items-center gap-2">
                                                        <input aria-label={`${t("reduction.newAmount")} ${lineName(l)}`}
                                                               data-testid={`reduce-amount-${l.id}`}
                                                               className={`${field} text-end tabular-nums w-32`} inputMode="decimal"
                                                               value={cut} onChange={e => setCuts(c => ({ ...c, [l.id]: e.target.value }))} />
                                                        <button type="button" data-testid={`reduce-remove-${l.id}`}
                                                                onClick={() => setCuts(c => ({ ...c, [l.id]: "0" }))}
                                                                className="px-2 py-1 rounded-md text-[11px] font-semibold border border-border cursor-pointer">
                                                            {t("reduction.remove")}
                                                        </button>
                                                    </span>
                                                )}
                                            </td>
                                            <td className={`${td} text-end tabular-nums`} data-testid={`reduce-credit-${l.id}`}>
                                                {priced ? (
                                                    <span title={t("reduction.perDay", { days: priced.remainingDays,
                                                        from: fmtIsoDate(priced.from, locale), to: fmtIsoDate(priced.to, locale) })}>
                                                        {bdi(priced.credit)}
                                                    </span>
                                                ) : "—"}
                                            </td>
                                        </tr>
                                    );
                                })}
                            </tbody>
                        </table>
                    </div>
                    {candidates.length === 0 && <p className="text-xs text-muted">{t("reduction.nothingReducible")}</p>}
                </section>

                <fieldset>
                    <legend className={label}>{t("reduction.excess")}</legend>
                    <div className="flex flex-wrap gap-4 text-xs">
                        <label className="inline-flex items-center gap-2 cursor-pointer">
                            <input type="radio" name="reduce-excess" data-testid="reduce-excess-credit"
                                   checked={excess === "CREDIT"} onChange={() => setExcess("CREDIT")} />
                            {t("reduction.excessCredit")}
                        </label>
                        <label className="inline-flex items-center gap-2 cursor-pointer">
                            <input type="radio" name="reduce-excess" data-testid="reduce-excess-cheques"
                                   checked={excess === "CHEQUES"} onChange={() => setExcess("CHEQUES")} />
                            {t("reduction.excessCheques")}
                        </label>
                    </div>
                </fieldset>

                {excess === "CHEQUES" && (
                    <section className="space-y-2">
                        <h4 className="text-[11px] font-semibold text-muted uppercase tracking-wider">{t("reduction.handBack")}</h4>
                        {(preview?.returnable ?? []).length === 0 ? (
                            <p className="text-xs text-muted">{t("reduction.noneToHandBack")}</p>
                        ) : (
                            <ul className="space-y-1 text-xs">
                                {(preview?.returnable ?? []).map(c => (
                                    <li key={c.id}>
                                        <label className="inline-flex items-center gap-2 cursor-pointer">
                                            <input type="checkbox" data-testid={`reduce-return-${c.id}`}
                                                   checked={returned.includes(c.id)}
                                                   onChange={e => setReturned(r => e.target.checked ? [...r, c.id] : r.filter(x => x !== c.id))} />
                                            #{c.seqNo} {c.chequeNumber ?? ""} · {c.chequeDate ? fmtIsoDate(c.chequeDate, locale) : "—"} · {bdi(c.amount)}
                                        </label>
                                    </li>
                                ))}
                            </ul>
                        )}
                        <h4 className="text-[11px] font-semibold text-muted uppercase tracking-wider">{t("reduction.replacements")}</h4>
                        <ChequeRowsEditor rows={cheques} onChange={setCheques} propertyId={lease.propertyId}
                                          expectedTotal={newRowsTarget} testIdPrefix="reduce" />
                    </section>
                )}

                {preview && (
                    <dl className="grid grid-cols-2 gap-x-4 gap-y-1 text-xs tabular-nums" data-testid="reduce-summary">
                        <dt className="text-muted">{t("reduction.creditNet")}</dt><dd className="text-end">{bdi(preview.creditNet)}</dd>
                        {preview.vatFromDeferred > 0 && (<>
                            <dt className="text-muted">{t("reduction.vatFromDeferred")}</dt><dd className="text-end">{bdi(preview.vatFromDeferred)}</dd>
                        </>)}
                        {preview.vatCreditNote > 0 && (<>
                            <dt className="text-muted">{t("reduction.vatCreditNote")}</dt>
                            <dd className="text-end" data-testid="reduce-credit-note">{bdi(preview.vatCreditNote)}</dd>
                        </>)}
                        <dt className="font-semibold">{t("reduction.creditTotal")}</dt>
                        <dd className="text-end font-semibold" data-testid="reduce-total">{bdi(preview.creditTotal)}</dd>
                        {excess === "CHEQUES" && (<>
                            <dt className="text-muted">{t("reduction.gap")}</dt>
                            <dd className={`text-end ${gapOk ? "" : "text-error font-semibold"}`} data-testid="reduce-gap">{bdi(preview.gap)}</dd>
                        </>)}
                    </dl>
                )}

                {problems.length > 0 && (
                    <ul className="text-[11px] text-error space-y-1" data-testid="reduce-problems">
                        {problems.map((p, i) => <li key={i}>{serverText(tCommon, p) || p.message}</li>)}
                    </ul>
                )}
                {error && <p role="alert" className="text-[11px] text-error" data-testid="reduce-error">{error}</p>}
            </div>
        </LeaseDialog>
    );
}
