"use client";

import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { serverText } from "@/components/finance/bankrec/serverText";
import AccountPicker from "@/components/finance/AccountPicker";
import LeaseDialog from "@/components/leases/LeaseDialog";
import { NumberInput } from "@/components/ui/NumberInput";
import { businessTodayIso } from "@/lib/businessDate";
import { ApiError, penaltyApi, type PenaltyAssessment } from "@/lib/api/leasing";

/**
 * "Add journal voucher": a one-off charge on a contract — extra parking, a
 * replacement key — credited to an income account finance picks.
 *
 * `POST /penalties/lease/{id}/journal-voucher` posts it at once as a JV (Dr the
 * lease's receivable / Cr the account) and adds a CASH collection row, so the
 * renter pays it and gets a receipt like any other charge. It lists with the
 * contract's charges and is reversed from there.
 */

const field = "w-full bg-input border border-border rounded-lg px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none";
const label = "block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1";

type Props = {
    open: boolean;
    leaseId: string;
    propertyId?: string | null;
    onClose: () => void;
    onPosted: (p: PenaltyAssessment) => void;
};

export default function JournalVoucherDialog({ open, leaseId, propertyId, onClose, onPosted }: Props) {
    const t = useTranslations("Cheques");
    const tl = useTranslations("Leasing");
    const tCommon = useTranslations("Common");
    const [accountId, setAccountId] = useState<string | null>(null);
    const [amount, setAmount] = useState(0);
    const [entryDate, setEntryDate] = useState(businessTodayIso());
    const [narration, setNarration] = useState("");
    const [vat, setVat] = useState<"no" | "yes">("no");
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        if (open) {
            setAccountId(null);
            setAmount(0);
            setEntryDate(businessTodayIso());
            setNarration("");
            setVat("no");
            setError(null);
        }
    }, [open]);

    const invalid = !accountId || amount <= 0 || !entryDate || !narration.trim();

    const submit = async () => {
        if (!accountId) return;
        setBusy(true);
        setError(null);
        try {
            const posted = await penaltyApi.journalVoucher(leaseId, {
                entryDate, accountId, amount, narration: narration.trim(), vatable: vat === "yes",
            });
            onPosted(posted);
        } catch (e) {
            setError(e instanceof ApiError ? serverText(tCommon, e) || e.message : t("actionFailed"));
        } finally {
            setBusy(false);
        }
    };

    return (
        <LeaseDialog
            open={open}
            title={tl("journalVoucher")}
            onClose={onClose}
            onConfirm={submit}
            confirmText={tl("journalVoucher")}
            cancelText={tl("cancel")}
            confirmDisabled={invalid}
            busy={busy}
            confirmTestId="journal-voucher-confirm"
        >
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <div className="sm:col-span-2" data-testid="journal-voucher-account">
                    <span className={label}>{tl("journalVoucherAccount")}</span>
                    <AccountPicker value={accountId} onChange={setAccountId} accountType="INCOME" propertyId={propertyId ?? null} />
                </div>
                <div>
                    <label className={label} htmlFor="journal-voucher-amount">{tl("amount")}</label>
                    <NumberInput id="journal-voucher-amount" money className={`${field} text-end tabular-nums`} value={amount} onChange={setAmount} />
                </div>
                <div>
                    <label className={label} htmlFor="journal-voucher-vat">{t("chargeVat")}</label>
                    <select id="journal-voucher-vat" className={field} value={vat} onChange={e => setVat(e.target.value as "no" | "yes")}>
                        <option value="no">{t("chargeVatNo")}</option>
                        <option value="yes">{t("chargeVatYes")}</option>
                    </select>
                </div>
                <div>
                    <label className={label} htmlFor="journal-voucher-date">{tl("journalVoucherDate")}</label>
                    <input id="journal-voucher-date" type="date" max={businessTodayIso()} className={field} value={entryDate} onChange={e => setEntryDate(e.target.value)} />
                </div>
                <div className="sm:col-span-2">
                    <label className={label} htmlFor="journal-voucher-narration">{tl("narration")}</label>
                    <input id="journal-voucher-narration" className={field} value={narration}
                        placeholder={tl("journalVoucherNarrationPlaceholder")} onChange={e => setNarration(e.target.value)} />
                </div>
            </div>
            <p className="mt-3 text-[11px] text-muted">{tl("journalVoucherHint")}</p>
            {error && <p role="alert" data-testid="journal-voucher-error" className="mt-3 text-[11px] text-error bg-error/10 rounded-lg px-3 py-2">{error}</p>}
        </LeaseDialog>
    );
}
