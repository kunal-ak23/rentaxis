"use client";

import { useEffect, useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import LeaseDialog from "@/components/leases/LeaseDialog";
import SettlementAccountPicker from "@/components/finance/SettlementAccountPicker";
import { NumberInput } from "@/components/ui/NumberInput";
import { fmtAmount, ledgerApi } from "@/lib/api/ledger";
import { todayIso } from "@/components/leases/leaseMath";
import {
    ApiError,
    chequeApi,
    leaseApi,
    vatApi,
    type Cheque,
    type ChequeFailureReason,
    type SettlementOption,
    type SettlementTarget,
} from "@/lib/api/leasing";
import { vatMoveFor, type VatMove } from "./vatMove";
import type { RegisterAction } from "./registerActions";
import { chequeRowIsValid } from "./chequeRowRules";
import { chequeTitle } from "./chequeLabel";

/**
 * Deposit, receive, correct or cancel one cheque — the register's own
 * single-row actions that need nothing more than a date, a note and
 * (for deposit) an optional debit-account override.
 *
 * Task 14 built this against the lease page's own cheque grid (deposit /
 * clear / receive / bounce / details / a single-row replace). The register
 * (Task 15) reuses it for the same shapes rather than forking a second
 * dialog, and both screens now offer the same actions, from the same
 * `registerActionsFor` table — including `cancel`, which reverses the
 * registering journal and is gated separately (`canCancelCheques`, narrower
 * than `canManageCheques`). Bounce and a multi-row replace get their own
 * dedicated dialogs on the register (`BounceChequeDialog`,
 * `ReplaceChequeDialog`) — a bounce needs a clearer failure-reason +
 * debit-account-override story than fits here, and a replace can be more than
 * one instrument — but this dialog keeps handling both for the lease page's
 * simpler single-row case.
 */

const field =
    "w-full bg-input border border-border rounded-lg px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200";
const label = "block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5";

const FAILURE_REASONS: ChequeFailureReason[] = ["BOUNCE", "SIGNATURE_MISMATCH", "ACCOUNT_CLOSED"];

/**
 * Every row action this dialog can carry out: the register's whole set bar
 * `receipt`, which is a download rather than a form and is opened directly by
 * whichever screen offered it.
 */
export type ChequeAction = Exclude<RegisterAction, "receipt">;

type Props = {
    action: ChequeAction | null;
    cheque: Cheque | null;
    propertyId?: string | null;
    onClose: () => void;
    onDone: () => void;
};

export default function ChequeActionDialog({ action, cheque, propertyId, onClose, onDone }: Props) {
    const t = useTranslations("Cheques");
    const tl = useTranslations("Leasing");
    const locale = useLocale();
    const accountLabel = (o: SettlementOption) => {
        const name = locale === "ar" && o.nameAr ? o.nameAr : o.name;
        const kind = o.kind === "CASH" ? t("cashInHand") : o.bankAccount ?? t("bankAccountKind");
        return `${o.code ? o.code + " " : ""}${name} · ${kind}`;
    };

    const [date, setDate] = useState(todayIso());
    const [notes, setNotes] = useState("");
    const [failureReason, setFailureReason] = useState<ChequeFailureReason>("BOUNCE");
    const [debitAccountId, setDebitAccountId] = useState<string | null>(null);
    const [chequeNumber, setChequeNumber] = useState("");
    const [chequeDate, setChequeDate] = useState("");
    const [payeeBank, setPayeeBank] = useState("");
    const [amount, setAmount] = useState(0);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState<string | null>(null);
    // Cancelling a REGISTERED row with undeclared VAT: where that VAT goes.
    const [vatMove, setVatMove] = useState<VatMove | null>(null);
    const [moveVatTo, setMoveVatTo] = useState("");

    useEffect(() => {
        if (!action || !cheque) return;
        setDate(todayIso());
        setNotes("");
        setFailureReason("BOUNCE");
        setDebitAccountId(cheque.debitAccountId);
        // A replacement is a NEW instrument, so it starts without a number:
        // `ChequeService.takenNumbers` (:1096-1104) collects from every row of
        // the lease whatever its status, the bounced one included, so seeding
        // the bounced cheque's own number made "cheque number 100041 is already
        // used on this lease" the guaranteed answer.
        setChequeNumber(action === "replace" ? "" : cheque.chequeNumber ?? "");
        setChequeDate((cheque.chequeDate ?? "").slice(0, 10));
        setPayeeBank(cheque.payeeBank ?? "");
        setAmount(cheque.amount);
        setError(null);
        setVatMove(null);
        setMoveVatTo("");
    }, [action, cheque]);

    // The server refuses to cancel a row whose VAT is still to be declared unless
    // another pending instalment takes it (VatTaxPointService.beforeCancel), so the
    // dialog asks which one up front. A CONTRACT-timed lease has no schedule and
    // never gets here; a schedule the user may not read leaves the server to say so.
    useEffect(() => {
        if (action !== "cancel" || !cheque || cheque.status !== "REGISTERED") return;
        let live = true;
        Promise.all([
            vatApi.schedule(cheque.leaseId),
            leaseApi.cheques(cheque.leaseId),
            // The lock date only narrows the list; a failure to read it must not hide it.
            ledgerApi.fiscal.get().then(f => f.booksLockedThrough ?? null).catch(() => null),
        ])
            .then(([schedule, rows, lockedThrough]) => {
                if (!live) return;
                const move = vatMoveFor(cheque, rows, schedule, lockedThrough);
                setVatMove(move);
                setMoveVatTo(move?.defaultId ?? "");
            })
            .catch(() => {});
        return () => {
            live = false;
        };
    }, [action, cheque]);

    // Where a receipt (or clearing) posts, asked of the server (R1 P2-2/P2-3):
    // `target` is exactly the account a post with no override lands in — cash in
    // hand for a CASH row, the reconcilable bank leaf otherwise — and `options`
    // are the cash and bank-account leaves any staff role may choose. The dialog
    // shows the target and always sends the account shown, so what it displays is
    // what posts. A failed lookup is shown, never swallowed.
    const [settlement, setSettlement] = useState<SettlementTarget | null>(null);
    const [settlementError, setSettlementError] = useState<string | null>(null);
    useEffect(() => {
        if ((action !== "receive" && action !== "clear") || !cheque) return;
        let live = true;
        setSettlement(null);
        setSettlementError(null);
        chequeApi
            .settlementTarget(cheque.id)
            .then(res => {
                if (!live) return;
                setSettlement(res);
                if (action === "receive") setDebitAccountId(res.target?.id ?? null);
            })
            .catch(e => {
                if (live) setSettlementError(e instanceof ApiError ? e.message : t("settlementTargetFailed"));
            });
        return () => {
            live = false;
        };
    }, [action, cheque, t]);

    if (!action || !cheque) return null;

    /**
     * The replacement row exactly as it will be sent, judged by the client
     * mirror of `ChequeRowRules.validateRow` — so this dialog cannot offer a
     * Replace whose row the server refuses (a blank date on a PDC being the
     * one that used to get through).
     */
    const replacementRow = {
        chequeNumber: chequeNumber || null,
        chequeDate: chequeDate || null,
        amount,
        mode: cheque.mode,
    };

    const submit = async () => {
        setBusy(true);
        setError(null);
        try {
            switch (action) {
                case "deposit":
                    await chequeApi.deposit(cheque.id, { date, notes: notes || null, debitAccountId });
                    break;
                case "clear":
                    await chequeApi.clear(cheque.id, { date, notes: notes || null });
                    break;
                case "receive":
                    // The account shown is the account sent: the server's own target
                    // unless the user picked another (R1 P2-3).
                    await chequeApi.receive(cheque.id, { date, notes: notes || null, debitAccountId });
                    break;
                case "bounce":
                    await chequeApi.bounce(cheque.id, { date, notes: notes || null, failureReason });
                    break;
                case "cancel":
                    await chequeApi.cancel(cheque.id, { date, notes: notes || null }, vatMove ? moveVatTo || null : null);
                    break;
                case "releaseOnline":
                    await chequeApi.releaseOnline(cheque.id, { date, notes: notes || null });
                    break;
                case "details":
                    await chequeApi.updateDetails(cheque.id, {
                        id: cheque.id,
                        seqNo: cheque.seqNo,
                        postingDate: cheque.postingDate,
                        chequeNumber: chequeNumber || null,
                        chequeDate: chequeDate || null,
                        payeeBank: payeeBank || null,
                        payerName: cheque.payerName,
                        debitAccountId,
                        amount: cheque.amount,
                        narration: cheque.narration,
                        mode: cheque.mode,
                    });
                    break;
                case "replace":
                    await chequeApi.replace(cheque.id, {
                        date,
                        notes: notes || null,
                        replacements: [
                            {
                                ...replacementRow,
                                postingDate: date,
                                payeeBank: payeeBank || null,
                                debitAccountId,
                            },
                        ],
                    });
                    break;
            }
            onDone();
        } catch (e) {
            setError(e instanceof ApiError ? e.message : t("actionFailed"));
        } finally {
            setBusy(false);
        }
    };

    const title = chequeTitle(t(action), cheque, fmtAmount(cheque.amount));

    return (
        <LeaseDialog
            open
            title={title}
            onClose={onClose}
            onConfirm={submit}
            confirmText={t(action)}
            cancelText={tl("cancel")}
            busy={busy}
            destructive={action === "bounce" || action === "cancel"}
            confirmDisabled={
                (action === "replace" && !chequeRowIsValid(replacementRow))
                || (action === "cancel" && vatMove !== null && !moveVatTo)
            }
            confirmTestId={`cheque-${action}-confirm`}
        >
            <div className="space-y-3">
                {action !== "details" && (
                    <div>
                        <label className={label} htmlFor="cheque-action-date">
                            {/*
                              * Every non-bounce action used to read "Deposit Date",
                              * including Clear — where the field is not the deposit
                              * date at all but the value date of the CRT that moves
                              * the money into the bank. An accountant reading it as
                              * "when it went to the bank" was choosing which period
                              * the cash lands in without knowing.
                              */}
                            {action === "bounce" ? t("bounceDate")
                                : action === "clear" ? t("clearingDate")
                                : action === "receive" ? t("receiptDate")
                                : t("depositDate")}
                        </label>
                        <input
                            id="cheque-action-date"
                            data-testid="cheque-action-date"
                            type="date"
                            className={field}
                            value={date}
                            onChange={e => setDate(e.target.value)}
                        />
                    </div>
                )}

                {action === "cancel" && vatMove && (
                    <div data-testid="cheque-move-vat">
                        {vatMove.candidates.length === 0 ? (
                            <p className="text-[11px] text-error" data-testid="cheque-move-vat-none">
                                {t("moveVatToNone", { vat: fmtAmount(vatMove.pendingVat) })}
                            </p>
                        ) : (
                            <>
                                <label className={label} htmlFor="cheque-move-vat-to">
                                    {t("moveVatTo")}
                                </label>
                                <select
                                    id="cheque-move-vat-to"
                                    data-testid="cheque-move-vat-to"
                                    className={field}
                                    value={moveVatTo}
                                    onChange={e => setMoveVatTo(e.target.value)}
                                >
                                    {vatMove.candidates.map(c => (
                                        <option key={c.id} value={c.id}>
                                            {t("moveVatOption", {
                                                label: c.chequeNumber || `#${c.seqNo}`,
                                                date: (c.chequeDate ?? "").slice(0, 10),
                                                amount: fmtAmount(c.amount),
                                            })}
                                        </option>
                                    ))}
                                </select>
                                <p className="text-[11px] text-muted mt-1" data-testid="cheque-move-vat-hint">
                                    {t("moveVatToHint", { vat: fmtAmount(vatMove.pendingVat) })}
                                </p>
                            </>
                        )}
                    </div>
                )}

                {action === "releaseOnline" && (
                    <p className="text-[11px] text-muted" data-testid="release-online-hint">
                        {t("releaseOnlineHint")}
                    </p>
                )}

                {action === "bounce" && (
                    <div>
                        <label className={label} htmlFor="cheque-failure-reason">
                            {t("failureReason")}
                        </label>
                        <select
                            id="cheque-failure-reason"
                            data-testid="cheque-failure-reason"
                            className={field}
                            value={failureReason}
                            onChange={e => setFailureReason(e.target.value as ChequeFailureReason)}
                        >
                            {FAILURE_REASONS.map(r => (
                                <option key={r} value={r}>
                                    {t(`failureReasons.${r}`)}
                                </option>
                            ))}
                        </select>
                    </div>
                )}

                {action === "deposit" && (
                    <div>
                        <label className={label}>{tl("debitAccount")}</label>
                        <SettlementAccountPicker
                            value={debitAccountId}
                            onChange={setDebitAccountId}
                            propertyId={propertyId}
                            placeholder={tl("debitAccount")}
                        />
                    </div>
                )}

                {action === "receive" && (
                    <div>
                        <label className={label} htmlFor="cheque-receive-account">{t("receivedInto")}</label>
                        <select
                            id="cheque-receive-account"
                            data-testid="cheque-receive-account"
                            className={field}
                            value={debitAccountId ?? ""}
                            disabled={!settlement}
                            onChange={e => setDebitAccountId(e.target.value || null)}
                        >
                            {!settlement?.target && <option value="">{t("receivedIntoDefault")}</option>}
                            {(settlement?.options ?? []).map(o => (
                                <option key={o.id} value={o.id}>
                                    {accountLabel(o)}
                                </option>
                            ))}
                        </select>
                        <p className="text-[11px] text-muted mt-1" data-testid="cheque-received-into-hint">
                            {t("receivedIntoHint")}
                        </p>
                    </div>
                )}

                {action === "clear" && settlement?.target && (
                    <p className="text-xs text-muted" data-testid="cheque-cleared-into">
                        {t("clearedInto", { account: accountLabel(settlement.target) })}
                    </p>
                )}

                {settlementError && (action === "receive" || action === "clear") && (
                    <p className="text-xs text-danger" role="alert" data-testid="cheque-settlement-error">
                        {settlementError}
                    </p>
                )}

                {(action === "details" || action === "replace") && (
                    <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                        <div>
                            <label className={label} htmlFor="cheque-detail-number">{tl("chequeNo")}</label>
                            <input
                                id="cheque-detail-number"
                                data-testid="cheque-detail-number"
                                className={field}
                                value={chequeNumber}
                                onChange={e => setChequeNumber(e.target.value)}
                            />
                        </div>
                        <div>
                            <label className={label} htmlFor="cheque-detail-date">{tl("chequeDate")}</label>
                            <input
                                id="cheque-detail-date"
                                type="date"
                                className={field}
                                value={chequeDate}
                                onChange={e => setChequeDate(e.target.value)}
                            />
                        </div>
                        <div>
                            <label className={label} htmlFor="cheque-detail-bank">{tl("payeeBank")}</label>
                            <input
                                id="cheque-detail-bank"
                                className={field}
                                value={payeeBank}
                                onChange={e => setPayeeBank(e.target.value)}
                            />
                        </div>
                        {action === "replace" && (
                            <div>
                                <label className={label} htmlFor="cheque-detail-amount">{tl("amount")}</label>
                                <NumberInput
                                    id="cheque-detail-amount"
                                    min={0}
                                    step={0.01}
                                    className={`${field} text-end tabular-nums`}
                                    value={amount}
                                    onChange={setAmount}
                                />
                            </div>
                        )}
                    </div>
                )}

                {action !== "details" && (
                    <div>
                        <label className={label} htmlFor="cheque-action-notes">{tl("narration")}</label>
                        <input
                            id="cheque-action-notes"
                            className={field}
                            value={notes}
                            onChange={e => setNotes(e.target.value)}
                        />
                    </div>
                )}

                {error && (
                    <p className="text-[11px] text-error" data-testid="cheque-action-error">
                        {error}
                    </p>
                )}
            </div>
        </LeaseDialog>
    );
}
