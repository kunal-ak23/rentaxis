"use client";

import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import LeaseDialog from "./LeaseDialog";
import AccountPicker from "@/components/finance/AccountPicker";
import { NumberInput } from "@/components/ui/NumberInput";
import { fmtAmount } from "@/lib/api/ledger";
import { todayIso } from "./leaseMath";
import {
    ApiError,
    chequeApi,
    type Cheque,
    type ChequeFailureReason,
} from "@/lib/api/leasing";
import type { ChequeRowAction } from "./ChequeGrid";

/**
 * Deposit, clear, bounce, receive, correct or replace one cheque, without
 * leaving the lease.
 *
 * Task 15 builds the register, which is where these live for the whole
 * portfolio; this is the same six actions against a single row, because the
 * accountant works a contract at a time and sending them to a portfolio-wide
 * worklist to bank one cheque would be the long way round.
 *
 * A bounce carries its failure reason — the backend 400s without one — so the
 * reason is a required field here rather than a default the operator never
 * sees.
 */

const field =
    "w-full bg-input border border-border rounded-lg px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200";
const label = "block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5";

const FAILURE_REASONS: ChequeFailureReason[] = ["BOUNCE", "SIGNATURE_MISMATCH", "ACCOUNT_CLOSED"];

type Props = {
    action: ChequeRowAction | null;
    cheque: Cheque | null;
    propertyId?: string | null;
    onClose: () => void;
    onDone: () => void;
};

export default function ChequeActionDialog({ action, cheque, propertyId, onClose, onDone }: Props) {
    const t = useTranslations("Cheques");
    const tl = useTranslations("Leasing");

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

    useEffect(() => {
        if (!action || !cheque) return;
        setDate(todayIso());
        setNotes("");
        setFailureReason("BOUNCE");
        setDebitAccountId(cheque.debitAccountId);
        setChequeNumber(cheque.chequeNumber ?? "");
        setChequeDate((cheque.chequeDate ?? "").slice(0, 10));
        setPayeeBank(cheque.payeeBank ?? "");
        setAmount(cheque.amount);
        setError(null);
    }, [action, cheque]);

    if (!action || !cheque) return null;

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
                    await chequeApi.receive(cheque.id, { date, notes: notes || null });
                    break;
                case "bounce":
                    await chequeApi.bounce(cheque.id, { date, notes: notes || null, failureReason });
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
                                postingDate: date,
                                chequeNumber: chequeNumber || null,
                                chequeDate: chequeDate || null,
                                payeeBank: payeeBank || null,
                                debitAccountId,
                                amount,
                                mode: cheque.mode,
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

    const title = `${t(action)} — ${cheque.chequeNumber || `#${cheque.seqNo}`} · ${fmtAmount(cheque.amount)}`;

    return (
        <LeaseDialog
            open
            title={title}
            onClose={onClose}
            onConfirm={submit}
            confirmText={t(action)}
            cancelText={tl("cancel")}
            busy={busy}
            destructive={action === "bounce"}
            confirmDisabled={action === "replace" && amount <= 0}
            confirmTestId={`cheque-${action}-confirm`}
        >
            <div className="space-y-3">
                {action !== "details" && (
                    <div>
                        <label className={label} htmlFor="cheque-action-date">
                            {action === "bounce" ? t("bounceDate") : t("depositDate")}
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
                        <AccountPicker
                            value={debitAccountId}
                            onChange={setDebitAccountId}
                            leafOnly
                            propertyId={propertyId}
                            placeholder={tl("debitAccount")}
                        />
                    </div>
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
