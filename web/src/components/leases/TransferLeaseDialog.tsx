"use client";

import { useEffect, useMemo, useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import LeaseDialog from "./LeaseDialog";
import { serverText } from "@/components/finance/bankrec/serverText";
import { fmtAmount } from "@/lib/api/ledger";
import { fmtIsoDate, round2 } from "./leaseMath";
import { ApiError, leaseApi, type LeaseDetail, type TransferPreview } from "@/lib/api/leasing";

/**
 * Spec 2026-09-24 §2 (#52): move the renter to another unit mid-lease. Step 1 is
 * the move (last night in the current unit, the unit, the end of the new term and
 * its rent — pre-filled with the current day rate × the new term's days); step 2 is
 * what happens to each uncleared cheque (Carry to the new lease / Keep here for
 * collection / Return to the renter) with the money summary. "Create draft" writes
 * nothing to the ledger: the new lease opens as a draft to review, add rows for the
 * gap, and post — posting completes the transfer.
 */

type Disposition = "CARRY" | "KEEP" | "RETURN";
const DISPOSITIONS: Disposition[] = ["CARRY", "KEEP", "RETURN"];

const field =
    "w-full bg-input border border-border rounded-lg px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none";
const label = "block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5";

type Unit = { id: string; unitNumber: string; occupancy?: string | null; status?: string | null; property?: { id: string; nameEn?: string | null } | null };

type Props = {
    open: boolean;
    lease: LeaseDetail;
    onClose: () => void;
    onDrafted: (successor: LeaseDetail) => void;
};

/** The gap the new rows must cover: the preview's, plus returned paper, plus the rent's change incl. VAT. */
export function gapFor(preview: TransferPreview, dispositions: Record<string, Disposition>, rent: number | null,
                       rentVat: boolean): number {
    const base = preview.gapToCollect ?? 0;
    const returned = preview.cheques
        .filter(c => (dispositions[c.chequeId] ?? c.disposition) === "RETURN")
        .reduce((s, c) => s + c.amount, 0);
    // Carry → Keep changes nothing here: the kept cheque's money reaches the new lease
    // through the balance carried instead of as a cheque.
    const rentDelta = rent == null || preview.suggestedRent == null ? 0
        : (rent - preview.suggestedRent) * (rentVat ? 1.05 : 1);
    return round2(base + returned + rentDelta);
}

export default function TransferLeaseDialog({ open, lease, onClose, onDrafted }: Props) {
    const t = useTranslations("Leasing");
    const tCommon = useTranslations("Common");
    const locale = useLocale();
    const [units, setUnits] = useState<Unit[]>([]);
    const [moveDate, setMoveDate] = useState("");
    const [targetUnitId, setTargetUnitId] = useState("");
    const [endDate, setEndDate] = useState("");
    const [rent, setRent] = useState("");
    const [rentTouched, setRentTouched] = useState(false);
    const [dispositions, setDispositions] = useState<Record<string, Disposition>>({});
    const [preview, setPreview] = useState<TransferPreview | null>(null);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        if (!open) return;
        setMoveDate("");
        setTargetUnitId("");
        setEndDate(lease.endDate);
        setRent("");
        setRentTouched(false);
        setDispositions({});
        setPreview(null);
        setError(null);
        leaseApi.unitOptions()
            .then(all => setUnits(all.filter(u => u.id !== lease.unitId
                && (u.occupancy ?? u.status ?? "VACANT") === "VACANT")))
            .catch(() => setUnits([]));
    }, [open, lease.endDate, lease.unitId]);

    useEffect(() => {
        if (!open || !moveDate || !targetUnitId) {
            setPreview(null);
            return;
        }
        let live = true;
        leaseApi.transferPreview(lease.id, moveDate, targetUnitId, endDate || null)
            .then(p => {
                if (!live) return;
                setPreview(p);
                if (!rentTouched && p.suggestedRent != null) setRent(p.suggestedRent.toFixed(2));
            })
            .catch(e => { if (live) setError(e instanceof ApiError ? serverText(tCommon, e) || e.message : t("transfer.failed")); });
        return () => { live = false; };
    }, [open, moveDate, targetUnitId, endDate, lease.id, rentTouched, t, tCommon]);

    const rentNum = rent.trim() === "" || !Number.isFinite(Number(rent)) ? null : Number(rent);
    const gap = useMemo(() => preview ? gapFor(preview, dispositions, rentNum, !!lease.rentVatApplicable) : null,
        [preview, dispositions, rentNum, lease.rentVatApplicable]);
    const carried = preview ? round2(preview.cheques
        .filter(c => (dispositions[c.chequeId] ?? c.disposition) === "CARRY")
        .reduce((s, c) => s + c.amount, 0)) : 0;
    const problems = preview?.problems ?? [];
    const ready = !!preview && problems.length === 0 && rentNum != null && rentNum > 0;
    const bdi = (v: number | null | undefined) => <bdi dir="ltr">{fmtAmount(v ?? 0)}</bdi>;

    const submit = async () => {
        setBusy(true);
        setError(null);
        try {
            const b = await leaseApi.transfer(lease.id, {
                moveDate,
                targetUnitId,
                endDate: endDate || null,
                chequeDispositions: (preview?.cheques ?? []).map(c => ({
                    chequeId: c.chequeId, disposition: dispositions[c.chequeId] ?? c.disposition })),
                rent: rentTouched && rentNum != null ? rentNum : null,
            });
            onDrafted(b);
        } catch (e) {
            setError(e instanceof ApiError ? serverText(tCommon, e) || e.message : t("transfer.failed"));
        } finally {
            setBusy(false);
        }
    };

    const carriedBalance = preview?.balanceCarried ?? 0;

    return (
        <LeaseDialog
            open={open}
            title={t("transfer.title")}
            onClose={onClose}
            onConfirm={submit}
            confirmText={t("transfer.createDraft")}
            cancelText={t("cancel")}
            confirmDisabled={!ready}
            busy={busy}
            confirmTestId="transfer-confirm"
            width="xl"
        >
            <div className="space-y-4" data-testid="transfer-dialog">
                <p className="text-[11px] text-muted">{t("transfer.hint")}</p>
                <ol className="space-y-4">
                    <li>
                        <h4 className="text-[11px] font-semibold text-muted uppercase tracking-wider mb-2">1 · {t("transfer.step1")}</h4>
                        <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                            <div>
                                <label className={label} htmlFor="transfer-move-date">{t("transfer.moveDate")}</label>
                                <input id="transfer-move-date" data-testid="transfer-move-date" type="date" className={field}
                                       min={lease.startDate} max={lease.endDate}
                                       value={moveDate} onChange={e => setMoveDate(e.target.value)} />
                            </div>
                            <div>
                                <label className={label} htmlFor="transfer-unit">{t("transfer.targetUnit")}</label>
                                <select id="transfer-unit" data-testid="transfer-unit" className={field}
                                        value={targetUnitId} onChange={e => setTargetUnitId(e.target.value)}>
                                    <option value="">{t("transfer.chooseUnit")}</option>
                                    {units.map(u => (
                                        <option key={u.id} value={u.id}>
                                            {u.unitNumber}{u.property?.nameEn ? ` · ${u.property.nameEn}` : ""}
                                        </option>
                                    ))}
                                </select>
                            </div>
                            <div>
                                <label className={label} htmlFor="transfer-end">{t("transfer.endDate")}</label>
                                <input id="transfer-end" type="date" className={field}
                                       value={endDate} onChange={e => setEndDate(e.target.value)} />
                            </div>
                            <div>
                                <label className={label} htmlFor="transfer-rent">{t("transfer.rent")}</label>
                                <input id="transfer-rent" data-testid="transfer-rent" inputMode="decimal"
                                       className={`${field} text-end tabular-nums`} value={rent}
                                       onChange={e => { setRent(e.target.value); setRentTouched(true); }} />
                                {preview?.suggestedRent != null && (
                                    <p className="text-[10px] text-muted mt-1">
                                        {t.rich("transfer.rentHint", { days: preview.newDays,
                                            amount: fmtAmount(preview.suggestedRent), n: c => <bdi dir="ltr">{c}</bdi> })}
                                    </p>
                                )}
                            </div>
                        </div>
                    </li>

                    {preview && (
                        <li>
                            <h4 className="text-[11px] font-semibold text-muted uppercase tracking-wider mb-2">2 · {t("transfer.step2")}</h4>
                            {preview.cheques.length === 0 ? (
                                <p className="text-xs text-muted">{t("transfer.noCheques")}</p>
                            ) : (
                                <ul className="space-y-1.5 text-xs" data-testid="transfer-cheques">
                                    {preview.cheques.map(c => {
                                        const d = dispositions[c.chequeId] ?? c.disposition;
                                        return (
                                            <li key={c.chequeId} className="flex flex-wrap items-center justify-between gap-2">
                                                <span>#{c.seqNo} {c.chequeNumber ?? ""} · {c.chequeDate ? fmtIsoDate(c.chequeDate, locale) : "—"} · {bdi(c.amount)}</span>
                                                <span role="radiogroup" aria-label={`${t("transfer.disposition")} #${c.seqNo}`}
                                                      className="inline-flex rounded-lg border border-border overflow-hidden">
                                                    {DISPOSITIONS.map(opt => (
                                                        <button key={opt} type="button" role="radio" aria-checked={d === opt}
                                                                data-testid={`transfer-${opt.toLowerCase()}-${c.chequeId}`}
                                                                disabled={opt === "CARRY" && c.status !== "REGISTERED"}
                                                                onClick={() => setDispositions(m => ({ ...m, [c.chequeId]: opt }))}
                                                                className={`px-2 py-1 text-[11px] font-semibold disabled:opacity-40 ${d === opt ? "bg-primary text-primary-foreground" : "bg-input text-foreground"}`}>
                                                            {t(`transfer.dispositions.${opt}`)}
                                                        </button>
                                                    ))}
                                                </span>
                                            </li>
                                        );
                                    })}
                                </ul>
                            )}
                            <dl className="mt-3 grid grid-cols-2 gap-x-4 gap-y-1 text-xs tabular-nums bg-input/30 rounded-lg p-3" data-testid="transfer-summary">
                                <dt className="text-muted">{t("transfer.earned")}</dt><dd className="text-end">{bdi(preview.earnedThrough)}</dd>
                                <dt className="text-muted">{carriedBalance <= 0 ? t("transfer.prepaidCarried") : t("transfer.owedCarried")}</dt>
                                <dd className="text-end" data-testid="transfer-balance">{bdi(Math.abs(carriedBalance))}</dd>
                                <dt className="text-muted">{t("transfer.depositCarried")}</dt><dd className="text-end">{bdi(preview.depositCarried)}</dd>
                                <dt className="text-muted">{t("transfer.chequesCarried")}</dt><dd className="text-end" data-testid="transfer-carried">{bdi(carried)}</dd>
                                <dt className="font-semibold">{t("transfer.gap")}</dt>
                                <dd className="text-end font-semibold" data-testid="transfer-gap">{bdi(gap)}</dd>
                            </dl>
                            <p className="text-[10px] text-muted mt-1">{t("transfer.gapHint")}</p>
                        </li>
                    )}
                </ol>
                {problems.length > 0 && (
                    <ul className="text-[11px] text-error space-y-1" data-testid="transfer-problems">
                        {problems.map((p, i) => <li key={i}>{p}</li>)}
                    </ul>
                )}
                {error && <p role="alert" className="text-[11px] text-error" data-testid="transfer-error">{error}</p>}
            </div>
        </LeaseDialog>
    );
}
