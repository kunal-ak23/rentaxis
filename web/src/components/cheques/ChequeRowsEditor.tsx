"use client";

import { useTranslations } from "next-intl";
import { Plus, Trash2 } from "lucide-react";
import { NumberInput } from "@/components/ui/NumberInput";
import SettlementAccountPicker from "@/components/finance/SettlementAccountPicker";
import { fmtAmount } from "@/lib/api/ledger";
import { round2, todayIso } from "@/components/leases/leaseMath";
import { TYPEABLE_MODES, chequeRowsErrors } from "@/components/cheques/chequeRowRules";
import type { ChequeMode, ChequeRowInput } from "@/lib/api/leasing";
import { chequeGridHandlers, type GridField } from "./chequeGridKeys";

const GRID_FIELDS: GridField[] = ["postingDate", "chequeNumber", "chequeDate", "payeeBank", "debitAccountId", "amount", "mode"];

/**
 * The cheque rows that pay for a charge added to a posted lease — an
 * extension's or an addendum's. They register the moment the dialog posts, so
 * the grid shows whether Σ rows equals what the lines charge (VAT included),
 * the same figure the server compares.
 */

const field =
    "w-full bg-input border border-border rounded-lg px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200";
const td = "px-2 py-1.5 text-xs";
const MODES: ChequeMode[] = TYPEABLE_MODES;

/** A row of the grid. `key` is React's, not the server's. */
export type ChequeDraft = ChequeRowInput & { key: number };

/**
 * A PDC needs the date written on it (ChequeRowRules.java:140-141), so the
 * date it is expected on starts equal to its posting date rather than empty.
 */
export function blankChequeRow(key: number): ChequeDraft {
    const today = todayIso();
    return { key, amount: 0, mode: "PDC", postingDate: today, chequeDate: today };
}

export function stripKey(row: ChequeDraft): ChequeRowInput {
    const copy: Partial<ChequeDraft> = { ...row };
    delete copy.key;
    return copy as ChequeRowInput;
}

export function chequeTotalOf(rows: ChequeDraft[]): number {
    return rows.reduce((s, c) => round2(s + (c.amount || 0)), 0);
}

type Props = {
    rows: ChequeDraft[];
    onChange: (rows: ChequeDraft[]) => void;
    propertyId: string | null;
    /** What the lines charge including VAT; Σ rows must equal it. */
    expectedTotal: number;
    /** `extend` keeps the test ids ExtendLeaseDialog's tests already use. */
    testIdPrefix: string;
};

export default function ChequeRowsEditor({ rows, onChange, propertyId, expectedTotal, testIdPrefix }: Props) {
    const t = useTranslations("Leasing");
    const tc = useTranslations("Cheques");
    const total = chequeTotalOf(rows);
    const matches = Math.abs(round2(total - expectedTotal)) < 0.005;
    const errors = chequeRowsErrors(rows.map(stripKey));
    /** An untouched default row is not a mistake yet; its "amount must be positive" waits. */
    const shown = errors.map((errs, i) => (rows[i]?.amount ? errs : errs.filter(e => e.code !== "amountPositive")));
    const patch = (key: number, next: Partial<ChequeDraft>) =>
        onChange(rows.map(c => (c.key === key ? { ...c, ...next } : c)));
    const nextKey = () => rows.reduce((m, c) => Math.max(m, c.key), -1) + 1;
    // Keyboard-first entry (scale #19); a pasted block longer than the grid adds rows.
    const gridKeys = chequeGridHandlers<ChequeDraft>({
        rows, fields: GRID_FIELDS, onChange, modeLabel: m => t(`mode.${m}`),
        addRow: i => blankChequeRow(nextKey() + i),
    });

    return (
        <>
            <div className="bg-surface border border-border rounded-xl overflow-x-auto">
                <table className="w-full min-w-[720px]" data-testid={`${testIdPrefix}-cheque-grid`} onKeyDown={gridKeys.onKeyDown} onPaste={gridKeys.onPaste}>
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
                        {rows.map((c, i) => (
                            <tr key={c.key} data-grid-row={i} className="border-t border-border">
                                <td className={`${td} text-muted`}>{i + 1}</td>
                                <td className={td} data-grid-field="postingDate">
                                    <input
                                        type="date"
                                        aria-label={`${t("postingDate")} ${i + 1}`}
                                        className={field}
                                        value={c.postingDate ?? ""}
                                        onChange={e => patch(c.key, { postingDate: e.target.value })}
                                    />
                                </td>
                                <td className={td} data-grid-field="chequeNumber">
                                    <input
                                        aria-label={`${t("chequeNo")} ${i + 1}`}
                                        className={field}
                                        value={c.chequeNumber ?? ""}
                                        onChange={e => patch(c.key, { chequeNumber: e.target.value })}
                                    />
                                </td>
                                <td className={td} data-grid-field="chequeDate">
                                    <input
                                        type="date"
                                        aria-label={`${t("chequeDate")} ${i + 1}`}
                                        className={field}
                                        value={c.chequeDate ?? ""}
                                        onChange={e => patch(c.key, { chequeDate: e.target.value })}
                                    />
                                </td>
                                <td className={td} data-grid-field="payeeBank">
                                    <input
                                        aria-label={`${t("payeeBank")} ${i + 1}`}
                                        className={field}
                                        value={c.payeeBank ?? ""}
                                        onChange={e => patch(c.key, { payeeBank: e.target.value })}
                                    />
                                </td>
                                <td className={td} data-grid-field="debitAccountId">
                                    <SettlementAccountPicker
                                        value={c.debitAccountId ?? null}
                                        onChange={id => patch(c.key, { debitAccountId: id })}
                                        propertyId={propertyId}
                                        placeholder={t("debitAccount")}
                                    />
                                </td>
                                <td className={`${td} text-end`} data-grid-field="amount">
                                    <NumberInput
                                        aria-label={`${t("amount")} ${i + 1}`}
                                        min={0}
                                        step={0.01}
                                        className={`${field} text-end tabular-nums`}
                                        value={c.amount}
                                        onChange={v => patch(c.key, { amount: v })}
                                    />
                                </td>
                                <td className={td} data-grid-field="mode">
                                    <select
                                        aria-label={`${t("chequeMode")} ${i + 1}`}
                                        className={field}
                                        value={c.mode ?? "PDC"}
                                        onChange={e => patch(c.key, { mode: e.target.value as ChequeMode })}
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
                                        onClick={() => onChange(rows.filter(x => x.key !== c.key))}
                                        className="p-1 rounded-md text-error hover:bg-error/10 cursor-pointer"
                                    >
                                        <Trash2 size={13} />
                                    </button>
                                </td>
                            </tr>
                        ))}
                    </tbody>
                </table>
                <p className="px-3 pt-2 text-[10.5px] text-muted" data-testid={`${testIdPrefix}-grid-keys-hint`}>{tc("gridKeysHint")}</p>
                <div className="px-3 py-2 border-t border-border flex items-center justify-between gap-3">
                    <button
                        type="button"
                        data-testid={`${testIdPrefix}-add-cheque`}
                        onClick={() =>
                            onChange([
                                ...rows,
                                blankChequeRow(rows.reduce((m, c) => Math.max(m, c.key), -1) + 1),
                            ])
                        }
                        className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[11px] font-semibold border border-border text-foreground hover:bg-input/40 cursor-pointer"
                    >
                        <Plus size={12} /> {t("addCheque")}
                    </button>
                    <span
                        data-testid={`${testIdPrefix}-match`}
                        data-match={matches ? "true" : "false"}
                        className={`text-[11px] font-semibold ${matches ? "text-success" : "text-error"}`}
                    >
                        {matches
                            ? t("chequesMatch", { contract: fmtAmount(expectedTotal) })
                            : t("chequesMustEqual", {
                                  cheques: fmtAmount(total),
                                  contract: fmtAmount(expectedTotal),
                              })}
                    </span>
                </div>
            </div>
            {shown.some(e => e.length > 0) && (
                <ul className="mt-2 text-[11px] text-error space-y-0.5" data-testid={`${testIdPrefix}-cheque-row-errors`}>
                    {shown.flatMap((errs, i) =>
                        errs.map(e => (
                            <li key={`${i}-${e.code}`}>
                                {tc(`rowError.${e.code}`, { row: i + 1, number: e.number ?? "" })}
                            </li>
                        )),
                    )}
                </ul>
            )}
        </>
    );
}
