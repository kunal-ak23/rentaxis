"use client";

import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { Plus, Trash2 } from "lucide-react";
import LeaseDialog from "@/components/leases/LeaseDialog";
import SettlementAccountPicker from "@/components/finance/SettlementAccountPicker";
import { NumberInput } from "@/components/ui/NumberInput";
import { fmtAmount } from "@/lib/api/ledger";
import { todayIso } from "@/components/leases/leaseMath";
import { round2 } from "@/components/leases/leaseMath";
import { ApiError, chequeApi, type Cheque, type ChequeMode, type ChequeRowInput } from "@/lib/api/leasing";
import { TYPEABLE_MODES, chequeRowsAreValid } from "./chequeRowRules";
import { chequeTitle } from "./chequeLabel";

/**
 * A bounced cheque is replaced by one or more new instruments (spec §7.4,
 * PACT's "Cheque Return / Replace"). ONLINE is not offered as a replacement
 * mode — `ChequeService#replace` refuses it — and the running total is
 * checked against the bounced amount client-side too, so the operator sees
 * why before the server 400s.
 */

type ReplacementRow = {
    key: number;
    mode: ChequeMode;
    chequeNumber: string;
    chequeDate: string;
    payeeBank: string;
    debitAccountId: string | null;
    amount: number;
    narration: string;
};

const REPLACEMENT_MODES: ChequeMode[] = TYPEABLE_MODES;

const field =
    "w-full bg-input border border-border rounded-lg px-2 py-1.5 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200";
const label = "block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1";

function blankRow(key: number, amount: number): ReplacementRow {
    return {
        key,
        mode: "PDC",
        chequeNumber: "",
        chequeDate: todayIso(),
        payeeBank: "",
        debitAccountId: null,
        amount,
        narration: "",
    };
}

type Props = {
    cheque: Cheque | null;
    propertyId?: string | null;
    onClose: () => void;
    onDone: () => void;
};

export default function ReplaceChequeDialog({ cheque, propertyId, onClose, onDone }: Props) {
    const t = useTranslations("Cheques");
    const tl = useTranslations("Leasing");

    const [date, setDate] = useState(todayIso());
    const [notes, setNotes] = useState("");
    const [rows, setRows] = useState<ReplacementRow[]>([]);
    const [nextKey, setNextKey] = useState(1);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        if (!cheque) return;
        setDate(todayIso());
        setNotes("");
        setRows([blankRow(0, cheque.amount)]);
        setNextKey(1);
        setError(null);
    }, [cheque]);

    if (!cheque) return null;

    const patch = (key: number, next: Partial<ReplacementRow>) =>
        setRows(prev => prev.map(r => (r.key === key ? { ...r, ...next } : r)));

    const addRow = () => {
        setRows(prev => [...prev, blankRow(nextKey, 0)]);
        setNextKey(k => k + 1);
    };

    const removeRow = (key: number) => setRows(prev => (prev.length > 1 ? prev.filter(r => r.key !== key) : prev));

    const total = rows.reduce((s, r) => round2(s + (r.amount || 0)), 0);
    const residual = round2(cheque.amount - total);
    const overBounced = total > cheque.amount + 0.005;

    /**
     * The wire shape, built once so the submit gate below judges exactly what
     * the server will be sent rather than a near-miss of it.
     */
    const replacements: ChequeRowInput[] = rows.map(r => ({
        postingDate: r.chequeDate || date,
        chequeNumber: r.mode === "PDC" ? r.chequeNumber || null : null,
        // Every mode needs the date it matures or is expected on —
        // ChequeRowRules.validateRow (:140-144) refuses a null for CASH and
        // TRANSFER too ("a CASH receipt needs the date it is expected on").
        // Only the NUMBER is PDC-only (:148). Nulling the date here made Cash
        // and Bank Transfer replacements impossible to submit at all.
        chequeDate: r.chequeDate || null,
        payeeBank: r.payeeBank || null,
        debitAccountId: r.debitAccountId,
        amount: r.amount || 0,
        narration: r.narration || null,
        mode: r.mode,
    }));

    const submit = async () => {
        setBusy(true);
        setError(null);
        try {
            await chequeApi.replace(cheque.id, { date, notes: notes || null, replacements });
            onDone();
        } catch (e) {
            setError(e instanceof ApiError ? e.message : t("actionFailed"));
        } finally {
            setBusy(false);
        }
    };

    const title = chequeTitle(t("replace"), cheque, fmtAmount(cheque.amount));

    return (
        <LeaseDialog
            open
            title={title}
            onClose={onClose}
            onConfirm={submit}
            confirmText={t("replace")}
            cancelText={tl("cancel")}
            busy={busy}
            confirmDisabled={overBounced || total <= 0 || !chequeRowsAreValid(replacements)}
            confirmTestId="replace-confirm"
            width="lg"
        >
            <div className="space-y-3">
                <div>
                    <label className={label} htmlFor="replace-date">{t("depositDate")}</label>
                    <input
                        id="replace-date"
                        data-testid="replace-date"
                        type="date"
                        className={field}
                        value={date}
                        onChange={e => setDate(e.target.value)}
                    />
                </div>

                <div className="space-y-2">
                    {rows.map((r, i) => (
                        <div
                            key={r.key}
                            data-testid={`replace-row-${i}`}
                            className="grid grid-cols-2 md:grid-cols-6 gap-2 items-end border border-border rounded-lg p-2"
                        >
                            <div>
                                <label className={label}>{tl("chequeMode")}</label>
                                <select
                                    data-testid={`replace-row-${i}-mode`}
                                    className={field}
                                    value={r.mode}
                                    onChange={e => patch(r.key, { mode: e.target.value as ChequeMode })}
                                >
                                    {REPLACEMENT_MODES.map(m => (
                                        <option key={m} value={m}>{tl(`mode.${m}`)}</option>
                                    ))}
                                </select>
                            </div>
                            {r.mode === "PDC" && (
                                <div>
                                    <label className={label}>{tl("chequeNo")}</label>
                                    <input
                                        data-testid={`replace-row-${i}-number`}
                                        className={field}
                                        value={r.chequeNumber}
                                        onChange={e => patch(r.key, { chequeNumber: e.target.value })}
                                    />
                                </div>
                            )}
                            <div>
                                <label className={label}>{tl("chequeDate")}</label>
                                <input
                                    data-testid={`replace-row-${i}-date`}
                                    type="date"
                                    className={field}
                                    value={r.chequeDate}
                                    onChange={e => patch(r.key, { chequeDate: e.target.value })}
                                />
                            </div>
                            <div>
                                <label className={label}>{tl("payeeBank")}</label>
                                <input
                                    data-testid={`replace-row-${i}-bank`}
                                    className={field}
                                    value={r.payeeBank}
                                    onChange={e => patch(r.key, { payeeBank: e.target.value })}
                                />
                            </div>
                            <div>
                                <label className={label}>{tl("amount")}</label>
                                <NumberInput
                                    data-testid={`replace-row-${i}-amount`}
                                    min={0}
                                    step={0.01}
                                    className={`${field} text-end tabular-nums`}
                                    value={r.amount}
                                    onChange={v => patch(r.key, { amount: v })}
                                />
                            </div>
                            <div>
                                <label className={label}>{tl("debitAccount")}</label>
                                <SettlementAccountPicker
                                    value={r.debitAccountId}
                                    onChange={id => patch(r.key, { debitAccountId: id })}
                                    propertyId={propertyId}
                                    placeholder={tl("debitAccount")}
                                />
                            </div>
                            <div className="flex items-end gap-1">
                                <input
                                    data-testid={`replace-row-${i}-narration`}
                                    className={field}
                                    placeholder={tl("narration")}
                                    value={r.narration}
                                    onChange={e => patch(r.key, { narration: e.target.value })}
                                />
                                <button
                                    type="button"
                                    data-testid={`replace-row-${i}-remove`}
                                    disabled={rows.length <= 1}
                                    onClick={() => removeRow(r.key)}
                                    className="p-1.5 rounded-md text-muted hover:bg-input disabled:opacity-30 cursor-pointer"
                                    aria-label={t("removeReplacement")}
                                >
                                    <Trash2 size={13} />
                                </button>
                            </div>
                        </div>
                    ))}
                </div>

                <button
                    type="button"
                    data-testid="replace-add-row"
                    onClick={addRow}
                    className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[11px] font-semibold border border-border text-foreground hover:bg-input/40 cursor-pointer"
                >
                    <Plus size={12} /> {t("addReplacement")}
                </button>

                <div
                    data-testid="replace-residual"
                    data-over={overBounced ? "true" : "false"}
                    className={`rounded-lg px-3 py-2 text-[11.5px] font-semibold ${
                        overBounced ? "bg-error/10 text-error" : "bg-input/40 text-foreground"
                    }`}
                >
                    {t("replacementsTotal", { total: fmtAmount(total), bounced: fmtAmount(cheque.amount) })}
                    {" — "}
                    {overBounced ? t("replacementsExceed") : t("residualStaysInReceivable", { residual: fmtAmount(residual) })}
                </div>

                <div>
                    <label className={label} htmlFor="replace-notes">{tl("narration")}</label>
                    <input
                        id="replace-notes"
                        className={field}
                        value={notes}
                        onChange={e => setNotes(e.target.value)}
                    />
                </div>

                {error && (
                    <p className="text-[11px] text-error" data-testid="replace-error">{error}</p>
                )}
            </div>
        </LeaseDialog>
    );
}
