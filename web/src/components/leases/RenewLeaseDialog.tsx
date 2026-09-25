"use client";

import { useEffect, useMemo, useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import { Plus, Trash2 } from "lucide-react";
import LeaseDialog from "./LeaseDialog";
import LeaseLinesGrid from "./LeaseLinesGrid";
import { isOneOff, linesAreValid, renewalRows, sameTermEnd, splitLineErrors, toInputs, todayIso, withCarriedDeposit, type LineRow } from "./leaseMath";
import {
    ApiError, leaseApi, type ChargeType, type LeaseDetail, type LeaseLine, type LeaseLineInput,
    type RenewalPreview, type RentChangeMode,
} from "@/lib/api/leasing";
import { fmtAmount } from "@/lib/api/ledger";

/**
 * Spec §4c: the one-off lines a renewal will not copy (an admin fee, last
 * year's renewal fee), so the dialog can say so — nothing is dropped silently.
 * Mirrors `LeaseRenewalService.copiedLines`.
 */
export function skippedOneOffCharges(lines: LeaseLine[]): LeaseLine[] {
    return lines.filter(l => !l.addendumId && isOneOff(l));
}

/**
 * Next year's contract, drafted from this one (spec 2026-09-24 §4a–§4d), in the
 * order the operator decides it: dates, rent change (with a live preview), the
 * deposit, the lines copied and the one-offs not copied, added charges (e.g. a
 * renewal fee), and the new Ejari.
 *
 * The successor is a DRAFT — renewal writes no journals. Leaving "copy the lines"
 * ticked sends no `lines`, which the backend reads as "carry the predecessor's
 * across" and applies the rent change to; untick it and the grid below is what
 * the new contract charges (a rent change is then typed into the grid).
 */

const field =
    "w-full bg-input border border-border rounded-lg px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200";
const label = "block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5";

type Props = {
    open: boolean;
    lease: LeaseDetail;
    chargeTypes: ChargeType[];
    onClose: () => void;
    onRenewed: (successor: LeaseDetail) => void;
};

type Extra = { key: number; chargeTypeId: string; amount: string; vat: boolean };

/** The day after the current term ends — the only start that leaves no gap. */
function dayAfter(iso: string): string {
    const [y, m, d] = iso.slice(0, 10).split("-").map(Number);
    if (!y || !m || !d) return "";
    const next = new Date(y, m - 1, d + 1);
    const pad = (n: number) => String(n).padStart(2, "0");
    return `${next.getFullYear()}-${pad(next.getMonth() + 1)}-${pad(next.getDate())}`;
}

/** A year on from a start date, ending the day before the anniversary. */
function yearFrom(iso: string): string {
    const [y, m, d] = iso.slice(0, 10).split("-").map(Number);
    if (!y || !m || !d) return "";
    const end = new Date(y + 1, m - 1, d - 1);
    const pad = (n: number) => String(n).padStart(2, "0");
    return `${end.getFullYear()}-${pad(end.getMonth() + 1)}-${pad(end.getDate())}`;
}

/** Amounts inside translated sentences stay LTR (the VoucherForm convention). */
const bdi = { n: (chunks: React.ReactNode) => <bdi dir="ltr">{chunks}</bdi> };

const num = (s: string): number | null => {
    const n = Number(s);
    return s.trim() !== "" && Number.isFinite(n) ? n : null;
};

export default function RenewLeaseDialog({ open, lease, chargeTypes, onClose, onRenewed }: Props) {
    const t = useTranslations("Leasing");
    const tr = useTranslations("Renewal");
    const locale = useLocale();
    const [contractDate, setContractDate] = useState(todayIso());
    const [startDate, setStartDate] = useState("");
    const [endDate, setEndDate] = useState("");
    const [copyLines, setCopyLines] = useState(true);
    const [carryDeposit, setCarryDeposit] = useState(true);
    const [rows, setRows] = useState<LineRow[]>([]);
    const [mode, setMode] = useState<RentChangeMode>("NONE");
    const [percent, setPercent] = useState("");
    const [amount, setAmount] = useState("");
    const [ejari, setEjari] = useState("");
    const [extras, setExtras] = useState<Extra[]>([]);
    const [preview, setPreview] = useState<RenewalPreview | null>(null);
    const [previewError, setPreviewError] = useState<string | null>(null);
    const [busy, setBusy] = useState(false);
    const [errors, setErrors] = useState<string[]>([]);

    const skipped = skippedOneOffCharges(lease.lines ?? []);
    const extraTypes = useMemo(
        () => chargeTypes.filter(c => c.active && (c.behaviour === "FEE" || c.behaviour === "DEPOSIT")),
        [chargeTypes],
    );
    const renewalFee = extraTypes.find(c => c.code === "RENEWAL_FEE") ?? extraTypes[0];

    useEffect(() => {
        if (!open) return;
        const start = dayAfter(lease.endDate);
        setContractDate(todayIso());
        setStartDate(start);
        // F15-05: as long as the current term, so "By percent" works as proposed.
        setEndDate(lease.startDate && lease.endDate ? sameTermEnd(lease.startDate, lease.endDate, start) : yearFrom(start));
        setCopyLines(true);
        setCarryDeposit(true);
        setRows(renewalRows(lease.lines, lease.startDate, { carryDepositForward: true }));
        setMode("NONE");
        setPercent("");
        setAmount("");
        setEjari("");
        setExtras([]);
        setPreview(null);
        setPreviewError(null);
        setErrors([]);
    }, [open, lease.startDate, lease.endDate, lease.lines]);

    // The live preview of the rent change (spec §4a). Only with copied lines: a
    // hand-edited grid carries its own rent.
    useEffect(() => {
        if (!open || !copyLines || !startDate || !endDate || endDate <= startDate) return;
        let live = true;
        const handle = setTimeout(() => {
            leaseApi.renewalPreview(lease.id, {
                startDate, endDate, mode,
                percent: mode === "PERCENT" ? num(percent) : null,
                amount: mode === "AMOUNT" ? num(amount) : null,
                carryDeposit,
            }).then(p => {
                if (!live) return;
                setPreview(p);
                setPreviewError(null);
            }).catch(e => {
                if (!live) return;
                setPreview(null);
                setPreviewError(e instanceof ApiError ? e.message : tr("previewFailed"));
            });
        }, 250);
        return () => {
            live = false;
            clearTimeout(handle);
        };
    }, [open, copyLines, lease.id, startDate, endDate, mode, percent, amount, carryDeposit, tr]);

    const { rest } = splitLineErrors(errors);

    const rentChangeReady = mode === "NONE"
        || (mode === "PERCENT" && num(percent) != null)
        || (mode === "AMOUNT" && (num(amount) ?? 0) > 0);
    const extrasReady = extras.every(x => x.chargeTypeId && (num(x.amount) ?? 0) > 0);

    const submit = async () => {
        setBusy(true);
        setErrors([]);
        try {
            const additionalLines: LeaseLineInput[] = extras.map(x => ({
                chargeTypeId: x.chargeTypeId, grossAmount: num(x.amount) ?? 0, discountAmount: 0, vatApplicable: x.vat,
            }));
            const successor = await leaseApi.renew(lease.id, {
                contractDate: contractDate || null,
                startDate,
                endDate,
                lines: copyLines ? null : toInputs(rows, { keepPeriods: false }),
                carryDepositForward: carryDeposit,
                rentChange: copyLines && mode !== "NONE"
                    ? { mode, percent: mode === "PERCENT" ? num(percent) : null, newRentAmount: mode === "AMOUNT" ? num(amount) : null }
                    : null,
                ejariNumber: ejari.trim() || null,
                additionalLines: additionalLines.length ? additionalLines : null,
            });
            onRenewed(successor);
        } catch (e) {
            setErrors(e instanceof ApiError ? [e.message] : [t("renewFailed")]);
        } finally {
            setBusy(false);
        }
    };

    const nameOf = (l: { chargeTypeName: string | null; chargeTypeNameAr?: string | null }) =>
        (locale === "ar" ? l.chargeTypeNameAr : null) || l.chargeTypeName || "";
    const signed = (p: number) => `${p >= 0 ? "+" : ""}${p.toFixed(2)}%`;

    return (
        <LeaseDialog
            open={open}
            title={t("renew")}
            onClose={onClose}
            onConfirm={submit}
            confirmText={t("renew")}
            cancelText={t("cancel")}
            confirmDisabled={!startDate || !endDate || endDate <= startDate || (!copyLines && !linesAreValid(rows))
                || (copyLines && !rentChangeReady) || !extrasReady}
            busy={busy}
            confirmTestId="renew-lease-confirm"
            width={copyLines ? "lg" : "xl"}
        >
            <div className="space-y-4">
                {/* 1. Dates */}
                <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
                    <div>
                        <label className={label} htmlFor="renew-contract-date">{t("contractDate")}</label>
                        <input id="renew-contract-date" type="date" className={field} value={contractDate}
                            onChange={e => setContractDate(e.target.value)} />
                    </div>
                    <div>
                        <label className={label} htmlFor="renew-start-date">{t("startDate")}</label>
                        <input id="renew-start-date" data-testid="renew-start-date" type="date" className={field}
                            value={startDate} onChange={e => setStartDate(e.target.value)} />
                    </div>
                    <div>
                        <label className={label} htmlFor="renew-end-date">{t("endDate")}</label>
                        <input id="renew-end-date" data-testid="renew-end-date" type="date" className={field}
                            value={endDate} onChange={e => setEndDate(e.target.value)} />
                    </div>
                </div>

                <label className="flex items-center gap-2 text-xs text-foreground">
                    <input type="checkbox" data-testid="renew-copy-lines" checked={copyLines}
                        onChange={e => setCopyLines(e.target.checked)} />
                    {t("copyLines")}
                </label>

                {/* 2. Rent change, with a live preview */}
                {copyLines && (
                    <div className="border border-border rounded-lg p-3 space-y-2" data-testid="renew-rent-change">
                        <div className={label}>{tr("rentChange")}</div>
                        <div className="flex flex-wrap items-center gap-3 text-xs">
                            {(["NONE", "PERCENT", "AMOUNT"] as RentChangeMode[]).map(m => (
                                <label key={m} className="flex items-center gap-1.5">
                                    <input type="radio" name="renew-rent-mode" data-testid={`renew-mode-${m}`}
                                        checked={mode === m} onChange={() => setMode(m)} />
                                    {tr(`modes.${m}`)}
                                </label>
                            ))}
                            {mode === "PERCENT" && (
                                <input aria-label={tr("percent")} data-testid="renew-percent" type="number" step="0.01"
                                    className={`${field} w-28 text-end`} value={percent} onChange={e => setPercent(e.target.value)} />
                            )}
                            {mode === "AMOUNT" && (
                                <input aria-label={tr("newRent")} data-testid="renew-amount" type="number" step="0.01" min={0}
                                    className={`${field} w-36 text-end`} value={amount} onChange={e => setAmount(e.target.value)} />
                            )}
                        </div>
                        {preview && preview.baseRent != null && preview.newRent != null && (
                            <p className="text-xs text-foreground tabular-nums" data-testid="renew-rent-preview">
                                {tr.rich("rentPreview", {
                                    ...bdi,
                                    from: fmtAmount(preview.baseRent),
                                    to: fmtAmount(preview.newRent),
                                    change: signed(preview.changePercent ?? (preview.baseRent ? ((preview.newRent - preview.baseRent) * 100) / preview.baseRent : 0)),
                                })}
                            </p>
                        )}
                        {preview && (preview.droppedDiscount ?? 0) > 0 && (
                            <p className="text-[11px] text-muted" data-testid="renew-dropped-discount">
                                {tr.rich("droppedDiscount", { amount: fmtAmount(preview.droppedDiscount ?? 0), ...bdi })}
                            </p>
                        )}
                        {preview?.exceedsWarn && preview.warnPercent != null && (
                            <p role="note" data-testid="renew-rent-notice"
                                className="text-[11px] text-warning bg-warning/10 border border-warning/20 rounded-lg px-3 py-2">
                                {tr.rich("aboveNotice", { percent: preview.warnPercent, ...bdi })}
                            </p>
                        )}
                        {previewError && (
                            <p role="alert" data-testid="renew-preview-error" className="text-[11px] font-semibold text-error">{previewError}</p>
                        )}
                        <p className="text-[11px] text-muted">{tr("concessionsDoNotRenew")}</p>
                    </div>
                )}

                {/* 3. Deposit */}
                <label className="flex items-center gap-2 text-xs text-foreground">
                    <input type="checkbox" data-testid="renew-carry-deposit" checked={carryDeposit}
                        onChange={e => {
                            // Last year's deposit line leaves the grid while the
                            // deposit is carried forward, and comes back when it
                            // is not — sending both would charge it twice (I2).
                            const carry = e.target.checked;
                            setCarryDeposit(carry);
                            setRows(prev => withCarriedDeposit(prev, lease.lines, lease.startDate, carry));
                        }} />
                    {t("carryDepositForward")}
                </label>

                {/* 4. Lines: the one-offs not copied */}
                {skipped.length > 0 && (
                    <p role="note" data-testid="renew-skipped-one-offs"
                        className="text-[11px] text-muted bg-input/40 border border-border rounded-lg px-3 py-2">
                        {tr("notCopied")}{" "}
                        {skipped.map((l, i) => (
                            <span key={l.id}>
                                {i > 0 && ", "}
                                {nameOf(l)} <bdi dir="ltr">{fmtAmount(l.netAmount)}</bdi>
                            </span>
                        ))}
                    </p>
                )}

                {!copyLines && (
                    <LeaseLinesGrid lines={rows} chargeTypes={chargeTypes} propertyId={lease.propertyId} editable
                        onChange={setRows} errors={errors} rentVat={!!lease.rentVatApplicable} />
                )}

                {/* 5. Additional charges (spec §4d) */}
                <div className="space-y-2" data-testid="renew-extras">
                    <div className={label}>{tr("additionalCharges")}</div>
                    {extras.map((x, i) => (
                        <div key={x.key} className="grid grid-cols-[1.4fr_1fr_auto_auto] gap-2 items-center" data-testid="renew-extra-row">
                            <select aria-label={tr("chargeType")} data-testid={`renew-extra-type-${i}`} className={field}
                                value={x.chargeTypeId}
                                onChange={e => {
                                    const ct = extraTypes.find(c => c.id === e.target.value);
                                    setExtras(prev => prev.map(p => p.key === x.key
                                        ? { ...p, chargeTypeId: e.target.value, vat: !!ct?.vatApplicableDefault } : p));
                                }}>
                                {extraTypes.map(c => (
                                    <option key={c.id} value={c.id}>{(locale === "ar" ? c.nameAr : null) || c.nameEn}</option>
                                ))}
                            </select>
                            <input aria-label={tr("amount")} data-testid={`renew-extra-amount-${i}`} type="number" min={0} step="0.01"
                                className={`${field} text-end`} value={x.amount}
                                onChange={e => setExtras(prev => prev.map(p => p.key === x.key ? { ...p, amount: e.target.value } : p))} />
                            <label className="flex items-center gap-1 text-[11px] text-muted">
                                <input type="checkbox" checked={x.vat}
                                    onChange={e => setExtras(prev => prev.map(p => p.key === x.key ? { ...p, vat: e.target.checked } : p))} />
                                {tr("vat")}
                            </label>
                            <button type="button" aria-label={tr("removeCharge")}
                                onClick={() => setExtras(prev => prev.filter(p => p.key !== x.key))}
                                className="p-2 rounded-lg text-muted hover:text-error hover:bg-input cursor-pointer">
                                <Trash2 size={13} />
                            </button>
                        </div>
                    ))}
                    {renewalFee && (
                        <button type="button" data-testid="renew-add-charge"
                            onClick={() => setExtras(prev => [...prev, {
                                key: prev.reduce((m, p) => Math.max(m, p.key), 0) + 1,
                                chargeTypeId: renewalFee.id, amount: "", vat: !!renewalFee.vatApplicableDefault,
                            }])}
                            className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg text-[11px] font-semibold border border-border hover:bg-input/40 cursor-pointer">
                            <Plus size={12} /> {tr("addCharge")}
                        </button>
                    )}
                </div>

                {/* 6. Ejari */}
                <div>
                    <label className={label} htmlFor="renew-ejari">{tr("ejari")}</label>
                    <input id="renew-ejari" data-testid="renew-ejari" className={field} value={ejari}
                        placeholder={tr("ejariPlaceholder")} onChange={e => setEjari(e.target.value)} />
                </div>

                {rest.length > 0 && (
                    <ul className="text-[11px] text-error space-y-1" data-testid="renew-errors">
                        {rest.map((e, i) => (
                            <li key={i}>{e}</li>
                        ))}
                    </ul>
                )}
            </div>
        </LeaseDialog>
    );
}
