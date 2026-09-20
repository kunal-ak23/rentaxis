"use client";

import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { Plus, Trash2 } from "lucide-react";
import LeaseDialog from "./LeaseDialog";
import LeaseLinesGrid from "./LeaseLinesGrid";
import { NumberInput } from "@/components/ui/NumberInput";
import SettlementAccountPicker from "@/components/finance/SettlementAccountPicker";
import { fmtAmount } from "@/lib/api/ledger";
import { blankLine, linesAreValid, round2, splitLineErrors, toInputs, todayIso, totalsOf, type LineRow } from "./leaseMath";
import { TYPEABLE_MODES, chequeRowsAreValid, chequeRowsErrors } from "@/components/cheques/chequeRowRules";
import {
    ApiError,
    leaseApi,
    type ChargeType,
    type ChequeMode,
    type ChequeRowInput,
    type LeaseDetail,
    type PostLeaseResponse,
} from "@/lib/api/leasing";

/**
 * Push the end date out and charge for the extra months.
 *
 * Unlike a renewal this posts immediately — a further TCO for the new lines
 * only, plus a PDR per new cheque — which is why the two totals have to agree
 * before the button is live. The backend refuses a mismatch; catching it here
 * saves the accountant a round trip, and the figure shown is the same
 * VAT-inclusive one the server compares.
 */

const field =
    "w-full bg-input border border-border rounded-lg px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200";
const label = "block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5";
const td = "px-2 py-1.5 text-xs";
const MODES: ChequeMode[] = TYPEABLE_MODES;

/** A row of the extension's cheque grid. `key` is React's, not the server's. */
type ChequeDraft = ChequeRowInput & { key: number };

/**
 * A row the server would accept as far as its defaults go: a PDC needs the
 * date written on it (ChequeRowRules.java:140-141), so the date it is expected
 * on starts equal to its posting date rather than empty — an Extend with the
 * default row used to be live and always 400.
 */
function blankChequeRow(key: number): ChequeDraft {
    const today = todayIso();
    return { key, amount: 0, mode: "PDC", postingDate: today, chequeDate: today };
}

function stripKey(row: ChequeDraft): ChequeRowInput {
    const copy: Partial<ChequeDraft> = { ...row };
    delete copy.key;
    return copy as ChequeRowInput;
}

type Props = {
    open: boolean;
    lease: LeaseDetail;
    chargeTypes: ChargeType[];
    onClose: () => void;
    onExtended: (res: PostLeaseResponse) => void;
};

export default function ExtendLeaseDialog({ open, lease, chargeTypes, onClose, onExtended }: Props) {
    const t = useTranslations("Leasing");
    const tc = useTranslations("Cheques");
    const [newEndDate, setNewEndDate] = useState("");
    const [contractDate, setContractDate] = useState(todayIso());
    const [rows, setRows] = useState<LineRow[]>([]);
    const [cheques, setCheques] = useState<ChequeDraft[]>([]);
    const [busy, setBusy] = useState(false);
    const [errors, setErrors] = useState<string[]>([]);

    useEffect(() => {
        if (!open) return;
        setNewEndDate("");
        setContractDate(todayIso());
        setRows([blankLine(0)]);
        setCheques([blankChequeRow(0)]);
        setErrors([]);
    }, [open]);

    const totals = totalsOf(rows, chargeTypes);
    const chequeTotal = cheques.reduce((s, c) => round2(s + (c.amount || 0)), 0);
    const matches = Math.abs(round2(chequeTotal - totals.inclVat)) < 0.005;
    const chequeRows = cheques.map(stripKey);
    const chequeRowErrors = chequeRowsErrors(chequeRows);
    /**
     * A row that has not been filled in yet is not a mistake, so the untouched
     * default does not open the dialog in red. The gate below still counts it —
     * the button is dead until the row is real — but "amount must be greater
     * than zero" is only said about a row the operator has started on.
     */
    const shownRowErrors = chequeRowErrors.map((errs, i) =>
        cheques[i]?.amount ? errs : errs.filter(e => e.code !== "amountPositive"),
    );
    const { rest } = splitLineErrors(errors);

    const patchCheque = (key: number, next: Partial<ChequeDraft>) =>
        setCheques(cs => cs.map(c => (c.key === key ? { ...c, ...next } : c)));

    const submit = async () => {
        setBusy(true);
        setErrors([]);
        try {
            const res = await leaseApi.extend(lease.id, {
                newEndDate,
                contractDate: contractDate || null,
                lines: toInputs(rows),
                cheques: chequeRows,
            });
            onExtended(res);
        } catch (e) {
            setErrors(e instanceof ApiError ? [e.message] : [t("extendFailed")]);
        } finally {
            setBusy(false);
        }
    };

    return (
        <LeaseDialog
            open={open}
            title={t("extend")}
            onClose={onClose}
            onConfirm={submit}
            confirmText={t("extend")}
            cancelText={t("cancel")}
            confirmDisabled={
                !newEndDate ||
                newEndDate <= lease.endDate ||
                !matches ||
                !linesAreValid(rows) ||
                !chequeRowsAreValid(chequeRows)
            }
            busy={busy}
            confirmTestId="extend-lease-confirm"
            width="xl"
        >
            <div className="space-y-4">
                <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
                    <div>
                        <label className={label} htmlFor="extend-current-end">{t("currentEndDate")}</label>
                        <input id="extend-current-end" readOnly className={`${field} opacity-70`} value={lease.endDate} />
                    </div>
                    <div>
                        <label className={label} htmlFor="extend-new-end">{t("newEndDate")}</label>
                        <input
                            id="extend-new-end"
                            data-testid="extend-new-end-date"
                            type="date"
                            min={lease.endDate}
                            className={field}
                            value={newEndDate}
                            onChange={e => setNewEndDate(e.target.value)}
                        />
                    </div>
                    <div>
                        <label className={label} htmlFor="extend-contract-date">{t("contractDate")}</label>
                        <input
                            id="extend-contract-date"
                            type="date"
                            className={field}
                            value={contractDate}
                            onChange={e => setContractDate(e.target.value)}
                        />
                    </div>
                </div>

                <section>
                    <h4 className="text-[11px] font-semibold text-muted uppercase tracking-wider mb-2">{t("extensionLines")}</h4>
                    <LeaseLinesGrid
                        lines={rows}
                        chargeTypes={chargeTypes}
                        propertyId={lease.propertyId}
                        editable
                        onChange={setRows}
                        errors={errors}
                    />
                </section>

                <section>
                    <h4 className="text-[11px] font-semibold text-muted uppercase tracking-wider mb-2">{t("extensionCheques")}</h4>
                    <div className="bg-surface border border-border rounded-xl overflow-x-auto">
                        <table className="w-full min-w-[720px]" data-testid="extend-cheque-grid">
                            <thead>
                                <tr className="bg-input/50">
                                    <th className={`${td} text-start text-[10px] font-semibold text-muted uppercase`}>{t("sno")}</th>
                                    <th className={`${td} text-start text-[10px] font-semibold text-muted uppercase`}>{t("postingDate")}</th>
                                    <th className={`${td} text-start text-[10px] font-semibold text-muted uppercase`}>{t("chequeNo")}</th>
                                    <th className={`${td} text-start text-[10px] font-semibold text-muted uppercase`}>{t("chequeDate")}</th>
                                    <th className={`${td} text-start text-[10px] font-semibold text-muted uppercase`}>{t("payeeBank")}</th>
                                    <th className={`${td} text-start text-[10px] font-semibold text-muted uppercase`}>{t("debitAccount")}</th>
                                    <th className={`${td} text-end text-[10px] font-semibold text-muted uppercase`}>{t("amount")}</th>
                                    <th className={`${td} text-start text-[10px] font-semibold text-muted uppercase`}>{t("chequeMode")}</th>
                                    <th className={td} />
                                </tr>
                            </thead>
                            <tbody>
                                {cheques.map((c, i) => (
                                    <tr key={c.key} className="border-t border-border">
                                        <td className={`${td} text-muted`}>{i + 1}</td>
                                        <td className={td}>
                                            <input
                                                type="date"
                                                aria-label={`${t("postingDate")} ${i + 1}`}
                                                className={field}
                                                value={c.postingDate ?? ""}
                                                onChange={e => patchCheque(c.key, { postingDate: e.target.value })}
                                            />
                                        </td>
                                        <td className={td}>
                                            <input
                                                aria-label={`${t("chequeNo")} ${i + 1}`}
                                                className={field}
                                                value={c.chequeNumber ?? ""}
                                                onChange={e => patchCheque(c.key, { chequeNumber: e.target.value })}
                                            />
                                        </td>
                                        <td className={td}>
                                            <input
                                                type="date"
                                                aria-label={`${t("chequeDate")} ${i + 1}`}
                                                className={field}
                                                value={c.chequeDate ?? ""}
                                                onChange={e => patchCheque(c.key, { chequeDate: e.target.value })}
                                            />
                                        </td>
                                        <td className={td}>
                                            <input
                                                aria-label={`${t("payeeBank")} ${i + 1}`}
                                                className={field}
                                                value={c.payeeBank ?? ""}
                                                onChange={e => patchCheque(c.key, { payeeBank: e.target.value })}
                                            />
                                        </td>
                                        <td className={td}>
                                            <SettlementAccountPicker
                                                value={c.debitAccountId ?? null}
                                                onChange={id => patchCheque(c.key, { debitAccountId: id })}
                                                propertyId={lease.propertyId}
                                                placeholder={t("debitAccount")}
                                            />
                                        </td>
                                        <td className={`${td} text-end`}>
                                            <NumberInput
                                                aria-label={`${t("amount")} ${i + 1}`}
                                                min={0}
                                                step={0.01}
                                                className={`${field} text-end tabular-nums`}
                                                value={c.amount}
                                                onChange={v => patchCheque(c.key, { amount: v })}
                                            />
                                        </td>
                                        <td className={td}>
                                            <select
                                                aria-label={`${t("chequeMode")} ${i + 1}`}
                                                className={field}
                                                value={c.mode ?? "PDC"}
                                                onChange={e => patchCheque(c.key, { mode: e.target.value as ChequeMode })}
                                            >
                                                {MODES.map(m => (
                                                    <option key={m} value={m}>
                                                        {t(`mode.${m}`)}
                                                    </option>
                                                ))}
                                            </select>
                                        </td>
                                        <td className={td}>
                                            <button
                                                type="button"
                                                aria-label={`${t("removeLine")} ${i + 1}`}
                                                onClick={() => setCheques(cs => cs.filter(x => x.key !== c.key))}
                                                className="p-1 rounded-md text-error hover:bg-error/10 cursor-pointer"
                                            >
                                                <Trash2 size={13} />
                                            </button>
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                        <div className="px-3 py-2 border-t border-border flex items-center justify-between gap-3">
                            <button
                                type="button"
                                data-testid="extend-add-cheque"
                                onClick={() =>
                                    setCheques(cs => [
                                        ...cs,
                                        blankChequeRow(cs.reduce((m, c) => Math.max(m, c.key), -1) + 1),
                                    ])
                                }
                                className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[11px] font-semibold border border-border text-foreground hover:bg-input/40 cursor-pointer"
                            >
                                <Plus size={12} /> {t("addCheque")}
                            </button>
                            <span
                                data-testid="extend-match"
                                data-match={matches ? "true" : "false"}
                                className={`text-[11px] font-semibold ${matches ? "text-success" : "text-error"}`}
                            >
                                {matches
                                    ? t("chequesMatch", { contract: fmtAmount(totals.inclVat) })
                                    : t("chequesMustEqual", {
                                          cheques: fmtAmount(chequeTotal),
                                          contract: fmtAmount(totals.inclVat),
                                      })}
                            </span>
                        </div>
                    </div>
                    {shownRowErrors.some(e => e.length > 0) && (
                        <ul className="mt-2 text-[11px] text-error space-y-0.5" data-testid="extend-cheque-row-errors">
                            {shownRowErrors.flatMap((errs, i) =>
                                errs.map(e => (
                                    <li key={`${i}-${e.code}`}>
                                        {tc(`rowError.${e.code}`, { row: i + 1, number: e.number ?? "" })}
                                    </li>
                                )),
                            )}
                        </ul>
                    )}
                </section>

                {rest.length > 0 && (
                    <ul className="text-[11px] text-error space-y-1" data-testid="extend-errors">
                        {rest.map((e, i) => (
                            <li key={i}>{e}</li>
                        ))}
                    </ul>
                )}
            </div>
        </LeaseDialog>
    );
}
