"use client";

import { Fragment } from "react";
import { useLocale, useTranslations } from "next-intl";
import { Plus, Trash2 } from "lucide-react";
import AccountPicker from "@/components/finance/AccountPicker";
import { NumberInput } from "@/components/ui/NumberInput";
import { fmtAmount } from "@/lib/api/ledger";
import type { ChargeType } from "@/lib/api/leasing";
import {
    accountTypeFor,
    behaviourOf,
    blankLine,
    netOf,
    splitLineErrors,
    totalsOf,
    vatOf,
    type LineRow,
} from "./leaseMath";

/**
 * The particulars grid of a tenancy contract, in the layout the client's
 * accountant reads in PACT RevenU:
 *
 *   SNO | Particulars | Credit A/c | Amount | Discount | After Discount | Narration | VAT
 *
 * "After Discount" is computed and read-only — it is the figure the ledger
 * posts and the one the cheques collect, so it is never a second place to type
 * a number that could disagree with Amount − Discount.
 */

const th = "text-start px-2.5 py-2 text-[10px] font-semibold text-muted uppercase tracking-wider whitespace-nowrap";
const thNum = `${th} text-end`;
const td = "px-2.5 py-1.5 text-xs align-top";
const tdNum = `${td} text-end tabular-nums`;
const field =
    "w-full bg-input border border-border rounded-lg px-2 py-1.5 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200";
const numField = `${field} text-end tabular-nums`;

type Props = {
    lines: LineRow[];
    chargeTypes: ChargeType[];
    /** Narrows the account picker to tenant-wide accounts plus this property's. */
    propertyId?: string | null;
    editable: boolean;
    onChange?: (rows: LineRow[]) => void;
    /**
     * Raw validation strings from the server. Anything shaped
     * `Line 2 (ADMIN_FEE): …` is shown against that row; anything else is
     * ignored here and belongs in the caller's banner, because a grid is the
     * wrong place to render a complaint about the whole contract. Callers
     * split the list with `splitLineErrors` and pass the whole thing here.
     */
    errors?: string[];
    /**
     * The lease header's "Rent carries VAT" flag (#54). When given, picking a
     * RENT-behaviour charge type sets the row's VAT box from it instead of the
     * charge type's catalogue default, which describes the charge, not this
     * contract. Every grid that edits a lease's lines passes it; a caller with
     * no lease (none today) leaves it out and gets the catalogue default.
     */
    rentVat?: boolean;
};

export default function LeaseLinesGrid({
    lines,
    chargeTypes,
    propertyId,
    editable,
    onChange,
    errors,
    rentVat,
}: Props) {
    const t = useTranslations("Leasing");
    const locale = useLocale();
    const totals = totalsOf(lines, chargeTypes);
    const { bySeq } = splitLineErrors(errors ?? []);

    const emit = (rows: LineRow[]) => onChange?.(rows);

    const patch = (key: number, next: Partial<LineRow>) =>
        emit(lines.map(l => (l.key === key ? { ...l, ...next } : l)));

    /**
     * Choosing a charge type carries its defaults onto the row: VAT from the
     * catalogue's `vatApplicableDefault` (for a RENT type, from the lease's
     * `rentVat` flag when the caller passes one), and — on a row that has not been
     * pointed at an account yet — nothing, because the server fills the
     * account in from the property's role mapping when the draft is saved and
     * hands it back on the line. Overwriting an account the user chose by hand
     * would silently undo a deliberate override.
     */
    const pickType = (row: LineRow, chargeTypeId: string) => {
        const type = chargeTypes.find(c => c.id === chargeTypeId);
        const vatApplicable = !type
            ? row.vatApplicable
            : type.behaviour === "RENT" && rentVat !== undefined
              ? rentVat
              : type.vatApplicableDefault;
        patch(row.key, { chargeTypeId, vatApplicable, vatTouched: false });
    };

    const addRow = () => {
        const nextKey = lines.reduce((m, l) => Math.max(m, l.key), -1) + 1;
        emit([...lines, blankLine(nextKey)]);
    };

    const removeRow = (key: number) => emit(lines.filter(l => l.key !== key));

    return (
        <div className="bg-surface border border-border rounded-xl overflow-hidden shadow-sm min-w-0" data-testid="lease-lines-grid">
            <div className="overflow-x-auto">
                <table className="w-full min-w-full md:min-w-[860px]">
                    <thead>
                        <tr className="bg-input/50">
                            <th className={th}>{t("sno")}</th>
                            <th className={th}>{t("particulars")}</th>
                            <th className={th}>{t("creditAccount")}</th>
                            <th className={thNum}>{t("grossAmount")}</th>
                            <th className={thNum}>{t("discount")}</th>
                            <th className={thNum}>{t("afterDiscount")}</th>
                            <th className={th}>{t("narration")}</th>
                            <th className={th}>{t("vat")}</th>
                            {editable && <th className={th} aria-label={t("removeLine")} />}
                        </tr>
                    </thead>
                    <tbody>
                        {lines.map((row, i) => {
                            const behaviour = behaviourOf(row, chargeTypes);
                            const net = netOf(row);
                            const overDiscount = (row.discountAmount || 0) > (row.grossAmount || 0);
                            const rowErrors = bySeq.get(i + 1) ?? [];
                            const type = chargeTypes.find(c => c.id === row.chargeTypeId);
                            return (
                                <Fragment key={row.key}>
                                <tr
                                    data-testid={`lease-line-row-${i}`}
                                    className="border-t border-border hover:bg-input/20"
                                >
                                    <td className={`${td} text-muted tabular-nums`}>{i + 1}</td>
                                    <td className={td}>
                                        {editable ? (
                                            <select
                                                aria-label={`${t("particulars")} ${i + 1}`}
                                                data-testid={`lease-line-type-${i}`}
                                                className={field}
                                                value={row.chargeTypeId ?? ""}
                                                onChange={e => pickType(row, e.target.value)}
                                            >
                                                <option value="">{t("selectChargeType")}</option>
                                                {chargeTypes.map(c => (
                                                    <option key={c.id} value={c.id}>
                                                        {c.nameEn}
                                                    </option>
                                                ))}
                                            </select>
                                        ) : (
                                            <span className="text-foreground">
                                                {locale === "ar"
                                                    ? row.chargeTypeNameAr || row.chargeTypeName || type?.nameAr || type?.nameEn || "—"
                                                    : row.chargeTypeName || type?.nameEn || "—"}
                                            </span>
                                        )}
                                    </td>
                                    <td className={td}>
                                        {editable ? (
                                            <AccountPicker
                                                value={row.creditAccountId}
                                                onChange={id => patch(row.key, { creditAccountId: id })}
                                                accountType={accountTypeFor(behaviour)}
                                                leafOnly
                                                propertyId={propertyId}
                                                placeholder={t("creditAccount")}
                                            />
                                        ) : (
                                            <span className="text-foreground">
                                                {row.creditAccountCode ? (
                                                    <>
                                                        <span className="font-mono text-muted me-2">{row.creditAccountCode}</span>
                                                        {locale === "ar"
                                                            ? row.creditAccountNameAr || row.creditAccountName
                                                            : row.creditAccountName}
                                                    </>
                                                ) : (
                                                    "—"
                                                )}
                                            </span>
                                        )}
                                    </td>
                                    <td className={tdNum}>
                                        {editable ? (
                                            <NumberInput
                                                aria-label={`${t("grossAmount")} ${i + 1}`}
                                                data-testid={`lease-line-amount-${i}`}
                                                min={0}
                                                step={0.01}
                                                className={numField}
                                                value={row.grossAmount}
                                                onChange={v => patch(row.key, { grossAmount: v })}
                                            />
                                        ) : (
                                            fmtAmount(row.grossAmount || 0)
                                        )}
                                    </td>
                                    <td className={tdNum}>
                                        {editable ? (
                                            <NumberInput
                                                aria-label={`${t("discount")} ${i + 1}`}
                                                data-testid={`lease-line-discount-${i}`}
                                                min={0}
                                                step={0.01}
                                                className={numField}
                                                value={row.discountAmount}
                                                onChange={v => patch(row.key, { discountAmount: v })}
                                            />
                                        ) : (
                                            fmtAmount(row.discountAmount || 0)
                                        )}
                                    </td>
                                    <td className={`${tdNum} font-semibold`} data-testid={`lease-line-net-${i}`}>
                                        {fmtAmount(net)}
                                    </td>
                                    <td className={td}>
                                        {editable ? (
                                            <input
                                                aria-label={`${t("narration")} ${i + 1}`}
                                                className={field}
                                                value={row.narration}
                                                onChange={e => patch(row.key, { narration: e.target.value })}
                                            />
                                        ) : (
                                            <span className="text-muted">{row.narration || "—"}</span>
                                        )}
                                    </td>
                                    <td className={td}>
                                        <label className="flex items-center gap-1.5">
                                            <input
                                                type="checkbox"
                                                aria-label={`${t("vat")} ${i + 1}`}
                                                data-testid={`lease-line-vat-${i}`}
                                                disabled={!editable || behaviour === "DEPOSIT"}
                                                checked={row.vatApplicable && behaviour !== "DEPOSIT"}
                                                onChange={e => patch(row.key, { vatApplicable: e.target.checked, vatTouched: true })}
                                            />
                                            <span className="tabular-nums text-muted">
                                                {fmtAmount(vatOf(row, chargeTypes))}
                                            </span>
                                        </label>
                                    </td>
                                    {editable && (
                                        <td className={td}>
                                            <button
                                                type="button"
                                                onClick={() => removeRow(row.key)}
                                                aria-label={`${t("removeLine")} ${i + 1}`}
                                                className="p-1 rounded-md text-error hover:bg-error/10 cursor-pointer"
                                            >
                                                <Trash2 size={13} />
                                            </button>
                                        </td>
                                    )}
                                </tr>
                                {(overDiscount || rowErrors.length > 0) && (
                                    <tr className="bg-error/5">
                                        <td className={`${td} text-[11px] text-error`} colSpan={editable ? 9 : 8}>
                                            <ul data-testid={`lease-line-errors-${i}`} className="space-y-0.5">
                                                {overDiscount && <li>{t("discountExceedsAmount", { n: i + 1 })}</li>}
                                                {rowErrors.map((e, j) => (
                                                    <li key={j}>{e}</li>
                                                ))}
                                            </ul>
                                        </td>
                                    </tr>
                                )}
                                </Fragment>
                            );
                        })}
                        {lines.length === 0 && (
                            <tr>
                                <td className={`${td} text-muted text-center py-6`} colSpan={editable ? 9 : 8}>
                                    {t("noLines")}
                                </td>
                            </tr>
                        )}
                    </tbody>
                    <tfoot>
                        <tr className="bg-input/40 font-semibold border-t-2 border-border" data-testid="lease-lines-totals">
                            <td className={td} colSpan={3}>
                                {t("totals")}
                            </td>
                            <td className={tdNum} data-testid="lease-lines-total-gross">{fmtAmount(totals.gross)}</td>
                            <td className={tdNum} data-testid="lease-lines-total-discount">{fmtAmount(totals.discount)}</td>
                            <td className={tdNum} data-testid="lease-lines-total-net">{fmtAmount(totals.net)}</td>
                            <td className={td} />
                            <td className={tdNum} data-testid="lease-lines-total-vat">{fmtAmount(totals.vat)}</td>
                            {editable && <td className={td} />}
                        </tr>
                        <tr className="bg-warning/10 font-bold border-t border-border">
                            <td className={td} colSpan={5}>
                                {t("contractValueInclVat")}
                            </td>
                            <td className={tdNum} data-testid="lease-lines-contract-value">{fmtAmount(totals.inclVat)}</td>
                            <td className={td} colSpan={editable ? 3 : 2} />
                        </tr>
                    </tfoot>
                </table>
            </div>
            {editable && (
                <div className="px-3 py-2.5 border-t border-border">
                    <button
                        type="button"
                        onClick={addRow}
                        data-testid="lease-lines-add"
                        className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[11px] font-semibold border border-border text-foreground hover:bg-input/40 cursor-pointer"
                    >
                        <Plus size={12} /> {t("addLine")}
                    </button>
                </div>
            )}
        </div>
    );
}
