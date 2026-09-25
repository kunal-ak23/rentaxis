"use client";

import { useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import { CheckCircle2, Hash, Loader2, TriangleAlert, Wand2 } from "lucide-react";
import SettlementAccountPicker from "@/components/finance/SettlementAccountPicker";
import { NumberInput } from "@/components/ui/NumberInput";
import { cn } from "@/lib/utils";
import { fmtAmount } from "@/lib/api/ledger";
import type {
    Cheque,
    ChequeMode,
    ChequeRowInput,
    ChequeStatus,
    GenerateChequesRequest,
    InstallmentDistribution,
    LeaseStatus,
} from "@/lib/api/leasing";
import { registerActionsFor, type RegisterAction } from "@/components/cheques/registerActions";
import { TYPEABLE_MODES, chequeRowsAreValid, chequeRowsErrors } from "@/components/cheques/chequeRowRules";
import { fmtIsoDate, round2 } from "./leaseMath";

/**
 * The cheque grid of a tenancy contract, in PACT's layout:
 *
 *   SNO | Posting Date | Cheque No | Date | Payee Bank | Debit A/c | Amount | Narration
 *
 * plus a Mode column, which PACT does not have because PACT only ever knew
 * about cheques. The footer compares Σ cheques against the contract value
 * INCLUDING VAT — that is the figure the server compares against, and a footer
 * that agreed with the lines but not with the server would send the accountant
 * looking for a rounding bug that is not there.
 */

const th = "text-start px-2.5 py-2 text-[10px] font-semibold text-muted uppercase tracking-wider whitespace-nowrap";
const thNum = `${th} text-end`;
const td = "px-2.5 py-1.5 text-xs";
const tdNum = `${td} text-end tabular-nums`;
const field =
    "w-full bg-input border border-border rounded-lg px-2 py-1.5 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200";
const numField = `${field} text-end tabular-nums`;

const STATUS_COLORS: Record<ChequeStatus, string> = {
    DRAFT: "bg-input text-muted",
    REGISTERED: "bg-info/10 text-info",
    DEPOSITED: "bg-primary/10 text-primary",
    CLEARED: "bg-success/10 text-success",
    BOUNCED: "bg-error/10 text-error",
    REPLACED: "bg-input text-muted",
    CANCELLED: "bg-input text-muted",
    RETURNED: "bg-warning/10 text-warning",
    ONLINE_PENDING: "bg-warning/10 text-warning",
    TRANSFERRED: "bg-input text-muted",
};

export type GenerateForm = {
    installments: number;
    firstDueDate: string;
    distribution: InstallmentDistribution;
    payeeBank: string;
    debitAccountId: string | null;
    foldDepositsAndFeesIntoFirst: boolean;
    mode: ChequeMode;
};

export function blankGenerateForm(
    installments: number,
    firstDueDate: string,
    distribution: InstallmentDistribution = "LAST_LARGER",
): GenerateForm {
    return {
        installments: installments || 1,
        firstDueDate: firstDueDate || "",
        distribution,
        payeeBank: "",
        debitAccountId: null,
        foldDepositsAndFeesIntoFirst: true,
        mode: "PDC",
    };
}

type Props = {
    cheques: Cheque[];
    /** Draft rows are typed in place; a posted lease's register is read-only here. */
    editable: boolean;
    onChange?: (rows: Cheque[]) => void;
    onGenerate?: (req: GenerateChequesRequest) => void;
    onGenerateNumbers?: (startingNumber: string) => void;
    defaultBankAccountId?: string | null;
    propertyId?: string | null;
    /** Σ of the lease's lines including VAT — what the grid must add up to. */
    contractValueInclVat: number;
    /**
     * Σ of the lines' VAT — what the rows' VAT column must add up to (spec
     * 2026-09-24 §1: each instalment declares its own share at its tax point).
     * The VAT column appears only when the contract, or some row, carries VAT.
     */
    contractVat?: number;
    defaultInstallments?: number;
    defaultFirstDueDate?: string | null;
    /** Installment distribution chosen in the lease's Terms step — see #46. */
    defaultDistribution?: InstallmentDistribution | null;
    busy?: boolean;
    error?: string | null;
    /** Row actions — only rendered when a handler is supplied and the user may act. */
    onRowAction?: (cheque: Cheque, action: RegisterAction) => void;
    /**
     * Cancelling reverses the registering journal, so it is gated by
     * `canCancelCheques` (SA/TA/ACCOUNTANT) rather than by the
     * `canManageCheques` that admits a property manager to the rest of the row.
     */
    canCancelCheques?: boolean;
    /**
     * The contract's own status. Every row action is a transition, and
     * `requireCollectable` gates all of them on `ChequeService.COLLECTABLE` — so
     * a grid that does not know its lease's status offers buttons the server
     * always refuses. Omitted means "caller cannot know", and the row's own
     * state machine answers alone.
     */
    leaseStatus?: LeaseStatus | null;
    /**
     * Whether this lease's settlement is FINALIZED — `requireSettlementUndisturbed`
     * withholds the reversal verbs once it is.
     */
    settlementFinalized?: boolean | null;
    /** Shown above the grid when the backend dropped the draft rows. */
    notice?: string | null;
};

export default function ChequeGrid({
    cheques,
    editable,
    onChange,
    onGenerate,
    onGenerateNumbers,
    defaultBankAccountId,
    propertyId,
    contractValueInclVat,
    contractVat = 0,
    defaultInstallments = 4,
    defaultFirstDueDate,
    defaultDistribution,
    busy,
    error,
    onRowAction,
    canCancelCheques = false,
    leaseStatus,
    settlementFinalized,
    notice,
}: Props) {
    const t = useTranslations("Leasing");
    const tc = useTranslations("Cheques");
    const tLedger = useTranslations("Ledger");
    const locale = useLocale();

    const [genOpen, setGenOpen] = useState(false);
    const [gen, setGen] = useState<GenerateForm>(() =>
        blankGenerateForm(defaultInstallments, defaultFirstDueDate ?? "", defaultDistribution ?? "LAST_LARGER"),
    );
    // The distribution follows the lease's own (`defaultDistribution`) until
    // the operator picks one here — the lease page keeps this grid mounted
    // across reloads, so a distribution changed on the lease must reach the
    // Generate form rather than stay frozen at the first render's value.
    const [distributionTouched, setDistributionTouched] = useState(false);
    // F15-04: likewise the instalment count follows the lease's (which drops to the
    // charged months when a rent-free window is saved) until the operator types one.
    const [installmentsTouched, setInstallmentsTouched] = useState(false);
    const installments = installmentsTouched ? gen.installments : (defaultInstallments || 1);
    const distribution: InstallmentDistribution = distributionTouched
        ? gen.distribution
        : defaultDistribution ?? "LAST_LARGER";
    const [numbersOpen, setNumbersOpen] = useState(false);
    const [startingNumber, setStartingNumber] = useState("");

    const total = cheques.reduce((s, c) => round2(s + (c.amount || 0)), 0);
    const matches = Math.abs(round2(total - contractValueInclVat)) < 0.005;
    // VAT per instalment (spec 2026-09-24 §1). A row with no figure yet (typed on
    // the grid, or its amount edited) is filled in pro rata by the server on save,
    // so the footer only checks the sum once every row has one.
    const showVat = contractVat > 0 || cheques.some(c => (c.vatAmount ?? 0) > 0);
    const vatPending = cheques.some(c => c.vatAmount === null || c.vatAmount === undefined);
    const vatTotal = cheques.reduce((s, c) => round2(s + (c.vatAmount ?? 0)), 0);
    const vatMatches = Math.abs(round2(vatTotal - contractVat)) < 0.005;

    const patch = (id: string, next: Partial<Cheque>) =>
        onChange?.(cheques.map(c => (c.id === id ? { ...c, ...next } : c)));

    /**
     * What `ChequeRowRules` would refuse, said here rather than as a 400 after
     * the accountant has typed the whole grid. The same predicate gates the
     * Save button on both screens that own one — see {@link draftRowsAreValid}.
     */
    const rowErrors = editable ? chequeRowsErrors(toChequeRows(cheques)) : [];
    const rowErrorLines = rowErrors.flatMap((errs, i) =>
        errs.map(e => ({
            key: `${i}-${e.code}`,
            text: tc(`rowError.${e.code}`, { row: i + 1, number: e.number ?? "" }),
        })),
    );

    /** One decision, made once and reused by the header, the cells and the colspans. */
    const actionsOf = (c: Cheque) =>
        registerActionsFor(c.status, c.mode, canCancelCheques, { status: leaseStatus, settlementFinalized }, c.ledgerSettled);

    const showActions = !editable && !!onRowAction && cheques.some(c => actionsOf(c).length > 0);
    const cols = 9 + (showVat ? 1 : 0) + (editable ? 0 : 1) + (showActions ? 1 : 0);

    return (
        <div className="bg-surface border border-border rounded-xl overflow-hidden shadow-sm" data-testid="cheque-grid">
            <div className="px-4 py-3 border-b border-border flex flex-wrap items-center justify-between gap-2">
                <h3 className="text-xs font-semibold text-muted uppercase tracking-wider">{t("chequeGrid")}</h3>
                {editable && (
                    <div className="flex items-center gap-2">
                        <button
                            type="button"
                            data-testid="cheque-grid-generate"
                            disabled={busy}
                            onClick={() => setGenOpen(o => !o)}
                            className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[11px] font-semibold border border-border text-foreground hover:bg-input/40 cursor-pointer disabled:opacity-50"
                        >
                            <Wand2 size={12} /> {t("generateCheques")}
                        </button>
                        <button
                            type="button"
                            data-testid="cheque-grid-numbers"
                            disabled={busy || cheques.length === 0}
                            onClick={() => setNumbersOpen(o => !o)}
                            className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[11px] font-semibold border border-border text-foreground hover:bg-input/40 cursor-pointer disabled:opacity-50"
                        >
                            <Hash size={12} /> {t("generateChequeNumbers")}
                        </button>
                    </div>
                )}
            </div>

            {notice && (
                <p className="px-4 py-2 text-[11px] text-warning bg-warning/10 border-b border-warning/20" data-testid="cheque-grid-notice">
                    {notice}
                </p>
            )}
            {error && (
                <p className="px-4 py-2 text-[11px] text-error bg-error/10 border-b border-error/20" data-testid="cheque-grid-error">
                    {error}
                </p>
            )}
            {rowErrorLines.length > 0 && (
                <ul
                    className="px-4 py-2 text-[11px] text-error bg-error/10 border-b border-error/20 space-y-0.5"
                    data-testid="cheque-grid-row-errors"
                >
                    {rowErrorLines.map(line => (
                        <li key={line.key}>{line.text}</li>
                    ))}
                </ul>
            )}

            {editable && genOpen && (
                <div className="px-4 py-3 border-b border-border bg-input/20 grid grid-cols-1 md:grid-cols-3 gap-3" data-testid="cheque-generate-form">
                    <Labelled label={t("installments")}>
                        <NumberInput
                            aria-label={t("installments")}
                            min={1}
                            max={36}
                            showZero
                            className={field}
                            value={installments}
                            onChange={v => {
                                setInstallmentsTouched(true);
                                setGen(g => ({ ...g, installments: v }));
                            }}
                        />
                    </Labelled>
                    <Labelled label={t("firstDueDate")}>
                        <input
                            type="date"
                            aria-label={t("firstDueDate")}
                            className={field}
                            value={gen.firstDueDate}
                            onChange={e => setGen(g => ({ ...g, firstDueDate: e.target.value }))}
                        />
                    </Labelled>
                    <Labelled label={t("distribution")}>
                        <select
                            aria-label={t("distribution")}
                            className={field}
                            value={distribution}
                            onChange={e => {
                                setDistributionTouched(true);
                                setGen(g => ({ ...g, distribution: e.target.value as InstallmentDistribution }));
                            }}
                        >
                            <option value="UNIFORM">{t("distributionUniform")}</option>
                            <option value="FIRST_LARGER">{t("distributionFirstLarger")}</option>
                            <option value="LAST_LARGER">{t("distributionLastLarger")}</option>
                            <option value="FIRST_AND_LAST_LARGER">{t("distributionBothLarger")}</option>
                        </select>
                    </Labelled>
                    <Labelled label={t("payeeBank")}>
                        <input
                            aria-label={t("payeeBank")}
                            className={field}
                            value={gen.payeeBank}
                            onChange={e => setGen(g => ({ ...g, payeeBank: e.target.value }))}
                        />
                    </Labelled>
                    <Labelled label={t("debitAccount")}>
                        <SettlementAccountPicker
                            value={gen.debitAccountId ?? defaultBankAccountId ?? null}
                            onChange={id => setGen(g => ({ ...g, debitAccountId: id }))}
                            propertyId={propertyId}
                            placeholder={t("debitAccount")}
                        />
                    </Labelled>
                    <Labelled label={t("mode.PDC")}>
                        <select
                            aria-label={t("chequeMode")}
                            className={field}
                            value={gen.mode}
                            onChange={e => setGen(g => ({ ...g, mode: e.target.value as ChequeMode }))}
                        >
                            {TYPEABLE_MODES.map(m => (
                                <option key={m} value={m}>
                                    {t(`mode.${m}`)}
                                </option>
                            ))}
                        </select>
                    </Labelled>
                    <label className="md:col-span-2 flex items-center gap-2 text-[11px] text-foreground">
                        <input
                            type="checkbox"
                            checked={gen.foldDepositsAndFeesIntoFirst}
                            onChange={e => setGen(g => ({ ...g, foldDepositsAndFeesIntoFirst: e.target.checked }))}
                        />
                        {t("foldIntoFirst")}
                    </label>
                    <div className="flex items-end">
                        <button
                            type="button"
                            data-testid="cheque-generate-confirm"
                            disabled={busy}
                            onClick={() => {
                                setGenOpen(false);
                                onGenerate?.({
                                    installments: installments || null,
                                    firstDueDate: gen.firstDueDate || null,
                                    distribution,
                                    payeeBank: gen.payeeBank || null,
                                    debitAccountId: gen.debitAccountId ?? defaultBankAccountId ?? null,
                                    foldDepositsAndFeesIntoFirst: gen.foldDepositsAndFeesIntoFirst,
                                    mode: gen.mode,
                                });
                            }}
                            className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[11px] font-semibold bg-primary text-primary-foreground hover:bg-primary/90 cursor-pointer disabled:opacity-50"
                        >
                            {busy ? <Loader2 size={12} className="animate-spin" /> : <Wand2 size={12} />}
                            {t("generateCheques")}
                        </button>
                    </div>
                </div>
            )}

            {editable && numbersOpen && (
                <div className="px-4 py-3 border-b border-border bg-input/20 flex flex-wrap items-end gap-3" data-testid="cheque-numbers-form">
                    <Labelled label={t("startingNumber")}>
                        <input
                            aria-label={t("startingNumber")}
                            className={field}
                            value={startingNumber}
                            onChange={e => setStartingNumber(e.target.value)}
                        />
                    </Labelled>
                    <button
                        type="button"
                        data-testid="cheque-numbers-confirm"
                        disabled={busy || !startingNumber.trim()}
                        onClick={() => {
                            setNumbersOpen(false);
                            onGenerateNumbers?.(startingNumber.trim());
                        }}
                        className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[11px] font-semibold bg-primary text-primary-foreground hover:bg-primary/90 cursor-pointer disabled:opacity-50"
                    >
                        <Hash size={12} /> {t("generateChequeNumbers")}
                    </button>
                </div>
            )}

            <div className="overflow-x-auto">
                <table className="w-full min-w-[960px]">
                    <thead>
                        <tr className="bg-input/50">
                            <th className={th}>{t("sno")}</th>
                            <th className={th}>{t("postingDate")}</th>
                            <th className={th}>{t("chequeNo")}</th>
                            <th className={th}>{t("chequeDate")}</th>
                            <th className={th}>{t("payeeBank")}</th>
                            <th className={th}>{t("debitAccount")}</th>
                            <th className={thNum}>{t("amount")}</th>
                            {showVat && <th className={thNum}>{t("vatColumn")}</th>}
                            <th className={th}>{t("narration")}</th>
                            <th className={th}>{t("chequeMode")}</th>
                            {!editable && <th className={th}>{tLedger("status")}</th>}
                            {showActions && <th className={th}>{t("actions")}</th>}
                        </tr>
                    </thead>
                    <tbody>
                        {cheques.map((c, i) => (
                            <tr key={c.id} data-testid={`cheque-row-${i}`} className="border-t border-border hover:bg-input/20">
                                <td className={`${td} text-muted tabular-nums`}>{c.seqNo ?? i + 1}</td>
                                <td className={td}>
                                    {editable ? (
                                        <input
                                            type="date"
                                            aria-label={`${t("postingDate")} ${i + 1}`}
                                            className={field}
                                            value={(c.postingDate ?? "").slice(0, 10)}
                                            onChange={e => patch(c.id, { postingDate: e.target.value })}
                                        />
                                    ) : (
                                        fmtIsoDate(c.postingDate, locale)
                                    )}
                                </td>
                                <td className={td}>
                                    {editable ? (
                                        <input
                                            aria-label={`${t("chequeNo")} ${i + 1}`}
                                            className={field}
                                            value={c.chequeNumber ?? ""}
                                            onChange={e => patch(c.id, { chequeNumber: e.target.value })}
                                        />
                                    ) : (
                                        c.chequeNumber || "—"
                                    )}
                                </td>
                                <td className={td}>
                                    {editable ? (
                                        <input
                                            type="date"
                                            aria-label={`${t("chequeDate")} ${i + 1}`}
                                            className={field}
                                            value={(c.chequeDate ?? "").slice(0, 10)}
                                            onChange={e => patch(c.id, { chequeDate: e.target.value })}
                                        />
                                    ) : (
                                        fmtIsoDate(c.chequeDate, locale)
                                    )}
                                </td>
                                <td className={td}>
                                    {editable ? (
                                        <input
                                            aria-label={`${t("payeeBank")} ${i + 1}`}
                                            className={field}
                                            value={c.payeeBank ?? ""}
                                            onChange={e => patch(c.id, { payeeBank: e.target.value })}
                                        />
                                    ) : (
                                        c.payeeBank || "—"
                                    )}
                                </td>
                                <td className={td}>
                                    {editable ? (
                                        <SettlementAccountPicker
                                            value={c.debitAccountId}
                                            onChange={id => patch(c.id, { debitAccountId: id })}
                                            propertyId={propertyId}
                                            placeholder={t("debitAccount")}
                                        />
                                    ) : (
                                        c.debitAccountName || "—"
                                    )}
                                </td>
                                <td className={tdNum}>
                                    {editable ? (
                                        <NumberInput
                                            aria-label={`${t("amount")} ${i + 1}`}
                                            min={0}
                                            step={0.01}
                                            className={numField}
                                            value={c.amount}
                                            // A new amount clears the row's VAT, so the
                                            // server re-spreads it pro rata on save.
                                            onChange={v => patch(c.id, { amount: v, vatAmount: null })}
                                        />
                                    ) : (
                                        fmtAmount(c.amount || 0)
                                    )}
                                </td>
                                {showVat && (
                                    <td className={tdNum} data-testid={`cheque-vat-${i}`}>
                                        {editable && c.status === "DRAFT" ? (
                                            <NumberInput
                                                // Remounted when the row flips between "auto" and a
                                                // figure, so an explicit 0 shows as 0 and "auto" as blank.
                                                key={`${c.id}-${c.vatAmount == null ? "auto" : "set"}`}
                                                aria-label={`${t("vatColumn")} ${i + 1}`}
                                                min={0}
                                                step={0.01}
                                                className={numField}
                                                placeholder={t("vatAuto")}
                                                showZero={c.vatAmount != null}
                                                value={c.vatAmount ?? 0}
                                                onChange={v => patch(c.id, { vatAmount: v })}
                                            />
                                        ) : c.vatAmount === null || c.vatAmount === undefined ? (
                                            <span className="text-muted">{t("vatAuto")}</span>
                                        ) : (
                                            fmtAmount(c.vatAmount)
                                        )}
                                    </td>
                                )}
                                <td className={td}>
                                    {editable ? (
                                        <input
                                            aria-label={`${t("narration")} ${i + 1}`}
                                            className={field}
                                            value={c.narration ?? ""}
                                            onChange={e => patch(c.id, { narration: e.target.value })}
                                        />
                                    ) : (
                                        <span className="text-muted">{c.narration || "—"}</span>
                                    )}
                                </td>
                                <td className={td}>
                                    {editable ? (
                                        <select
                                            aria-label={`${t("chequeMode")} ${i + 1}`}
                                            className={field}
                                            value={c.mode}
                                            onChange={e => patch(c.id, { mode: e.target.value as ChequeMode })}
                                        >
                                            {TYPEABLE_MODES.map(m => (
                                                <option key={m} value={m}>
                                                    {t(`mode.${m}`)}
                                                </option>
                                            ))}
                                        </select>
                                    ) : (
                                        t(`mode.${c.mode}`)
                                    )}
                                </td>
                                {!editable && (
                                    <td className={td}>
                                        <span
                                            data-testid={`cheque-status-${i}`}
                                            className={cn("px-2 py-0.5 rounded-md text-[9px] font-semibold", STATUS_COLORS[c.status])}
                                        >
                                            {tc(`status.${c.status}`)}
                                        </span>
                                        {c.ledgerSettled && (
                                            <span className="ms-1 text-[9px] text-muted" data-testid={`cheque-ledger-settled-${i}`}>
                                                {tc("ledgerSettled")}
                                            </span>
                                        )}
                                    </td>
                                )}
                                {showActions && (
                                    <td className={td}>
                                        <div className="flex items-center gap-1.5">
                                            {actionsOf(c).map(a => (
                                                <button
                                                    key={a}
                                                    type="button"
                                                    data-testid={`cheque-action-${a}-${i}`}
                                                    onClick={() => onRowAction?.(c, a)}
                                                    className="px-2 py-1 rounded-md text-[10px] font-bold bg-input text-foreground hover:bg-border transition-colors cursor-pointer"
                                                >
                                                    {tc(a)}
                                                </button>
                                            ))}
                                        </div>
                                    </td>
                                )}
                            </tr>
                        ))}
                        {cheques.length === 0 && (
                            <tr>
                                <td className={`${td} text-muted text-center py-6`} colSpan={cols}>
                                    {t("noCheques")}
                                </td>
                            </tr>
                        )}
                    </tbody>
                    <tfoot>
                        <tr className="bg-input/40 font-semibold border-t-2 border-border">
                            <td className={td} colSpan={6}>
                                {t("chequeTotal")}
                            </td>
                            <td className={tdNum} data-testid="cheque-grid-total">{fmtAmount(total)}</td>
                            {showVat && (
                                <td className={tdNum} data-testid="cheque-grid-vat-total">{fmtAmount(vatTotal)}</td>
                            )}
                            <td className={td} colSpan={cols - 7 - (showVat ? 1 : 0)} />
                        </tr>
                        <tr className={cn("font-bold border-t border-border", matches ? "bg-success/10" : "bg-error/10")}>
                            <td className={`${td} ${matches ? "text-success" : "text-error"}`} colSpan={cols}>
                                <span
                                    data-testid="cheque-grid-match"
                                    data-match={matches ? "true" : "false"}
                                    className="inline-flex items-center gap-1.5"
                                >
                                    {matches ? <CheckCircle2 size={13} /> : <TriangleAlert size={13} />}
                                    {matches
                                        ? t("chequesMatch", { contract: fmtAmount(contractValueInclVat) })
                                        : t("chequesMustEqual", {
                                              cheques: fmtAmount(total),
                                              contract: fmtAmount(contractValueInclVat),
                                          })}
                                </span>
                            </td>
                        </tr>
                        {/* A legacy lease declared its VAT on the contract date, so its
                            posted rows carry none — no footer to argue with it. */}
                        {showVat && (editable || vatTotal > 0) && (
                            <tr
                                className={cn(
                                    "font-bold border-t border-border",
                                    vatPending ? "bg-input/40" : vatMatches ? "bg-success/10" : "bg-error/10",
                                )}
                            >
                                <td
                                    className={`${td} ${vatPending ? "text-muted" : vatMatches ? "text-success" : "text-error"}`}
                                    colSpan={cols}
                                >
                                    <span
                                        data-testid="cheque-grid-vat-match"
                                        data-match={vatPending ? "pending" : vatMatches ? "true" : "false"}
                                        className="inline-flex items-center gap-1.5"
                                    >
                                        {vatPending ? null : vatMatches ? <CheckCircle2 size={13} /> : <TriangleAlert size={13} />}
                                        {vatPending
                                            ? t("vatPendingAllocation", { contract: fmtAmount(contractVat) })
                                            : t(vatMatches ? "vatMatches" : "vatMustEqual", {
                                                  rows: fmtAmount(vatTotal),
                                                  contract: fmtAmount(contractVat),
                                              })}
                                    </span>
                                    {/* The lines changed under a grid whose rows kept their
                                        old VAT: rather than retyping every row, hand them
                                        all back to the server's default, which spreads the
                                        contract's VAT again when the grid is saved (PR #348
                                        review P3-8). */}
                                    {editable && onChange && !vatPending && !vatMatches && (
                                        <button
                                            type="button"
                                            data-testid="cheque-grid-vat-respread"
                                            className="ms-3 underline font-semibold"
                                            onClick={() => onChange(cheques.map(c => ({ ...c, vatAmount: null })))}
                                        >
                                            {t("vatRespread")}
                                        </button>
                                    )}
                                </td>
                            </tr>
                        )}
                    </tfoot>
                </table>
            </div>
        </div>
    );
}

function Labelled({ label, children }: { label: string; children: React.ReactNode }) {
    return (
        <div>
            <span className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1">{label}</span>
            {children}
        </div>
    );
}

/**
 * Whether the grid as it stands can be saved — the client mirror of what
 * `ChequeRowRules.validateGrid` will check, so the Save button on the wizard
 * and on the lease page is dead for exactly the rows the server refuses.
 */
export function draftRowsAreValid(cheques: Cheque[]): boolean {
    return chequeRowsAreValid(toChequeRows(cheques));
}

/** The wire shape of the grid as it stands, for `PUT /leases/{id}/cheques`. */
export function toChequeRows(cheques: Cheque[]): ChequeRowInput[] {
    return cheques.map(c => ({
        id: c.id,
        seqNo: c.seqNo,
        postingDate: c.postingDate || null,
        chequeNumber: c.chequeNumber || null,
        chequeDate: c.chequeDate || null,
        payeeBank: c.payeeBank || null,
        payerName: c.payerName || null,
        debitAccountId: c.debitAccountId,
        amount: c.amount || 0,
        narration: c.narration || null,
        mode: c.mode,
        // Null asks the server for the pro-rata default (spec 2026-09-24 §1).
        vatAmount: c.vatAmount ?? null,
        rowKind: c.rowKind ?? null,
    }));
}
