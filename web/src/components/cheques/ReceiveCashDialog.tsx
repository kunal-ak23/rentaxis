"use client";

import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import LeaseDialog from "@/components/leases/LeaseDialog";
import SettlementAccountPicker from "@/components/finance/SettlementAccountPicker";
import { NumberInput } from "@/components/ui/NumberInput";
import { todayIso } from "@/components/leases/leaseMath";
import { leaseTakesNewRows } from "@/components/cheques/registerActions";
import { ApiError, chequeApi, leaseApi, type Cheque, type ChequeMode, type LeaseDetail } from "@/lib/api/leasing";

/**
 * A cash or transfer receipt taken at the counter against a lease already on
 * the books — the register's own "Cash receipt" button. This is a NEW row
 * (`POST /cheques/lease/{leaseId}/cash-receipt` creates and receives it in
 * one call), unlike the register's per-row "Receive" action, which confirms
 * an EXISTING REGISTERED CASH/TRANSFER row.
 *
 * Because it creates a row, the lease it is taken against has to be one that
 * still grows instalments: `ChequeService.POSTED` ({ACTIVE, NOTICE_GIVEN,
 * RENEWED}), not the wider `COLLECTABLE` that merely lets an existing row move.
 * The search therefore filters the page it gets back — `GET /leases/paged`
 * takes a single-valued `status`, so three statuses cannot be asked for in one
 * request — and says how many matches it dropped rather than silently shortening
 * the list.
 *
 * When opened with `initialLeaseId` (the lease page's own "Cash receipt"
 * entry point, via `?leaseId=`), the lease step is skipped — but the same rule
 * is checked, because a link is as capable of naming an ended contract as a
 * search is.
 */

const RECEIPT_MODES: ChequeMode[] = ["CASH", "TRANSFER"];

const field =
    "w-full bg-input border border-border rounded-lg px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200";
const label = "block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5";

type Props = {
    open: boolean;
    initialLeaseId?: string | null;
    onClose: () => void;
    onDone: (cheque: Cheque) => void;
};

export default function ReceiveCashDialog({ open, initialLeaseId, onClose, onDone }: Props) {
    const t = useTranslations("Cheques");
    const tl = useTranslations("Leasing");

    const [query, setQuery] = useState("");
    const [results, setResults] = useState<LeaseDetail[]>([]);
    /** How many of the page's matches `POSTED` excluded — shown, never hidden. */
    const [filteredOut, setFilteredOut] = useState(0);
    const [searching, setSearching] = useState(false);
    const [lease, setLease] = useState<LeaseDetail | null>(null);
    const [mode, setMode] = useState<ChequeMode>("CASH");
    const [date, setDate] = useState(todayIso());
    const [amount, setAmount] = useState(0);
    const [narration, setNarration] = useState("");
    const [debitAccountId, setDebitAccountId] = useState<string | null>(null);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        if (!open) return;
        setMode("CASH");
        setDate(todayIso());
        setAmount(0);
        setNarration("");
        setDebitAccountId(null);
        setError(null);
        setQuery("");
        setResults([]);
        setFilteredOut(0);
        if (initialLeaseId) {
            leaseApi.get(initialLeaseId).then(setLease).catch(() => setLease(null));
        } else {
            setLease(null);
        }
    }, [open, initialLeaseId]);

    useEffect(() => {
        if (!open || lease || query.trim().length < 2) {
            setResults([]);
            setFilteredOut(0);
            return;
        }
        setSearching(true);
        const timer = window.setTimeout(() => {
            leaseApi
                .paged({ search: query.trim(), size: 8 })
                .then(page => {
                    const usable = page.content.filter(l => leaseTakesNewRows(l.status));
                    setResults(usable);
                    setFilteredOut(page.content.length - usable.length);
                })
                .catch(() => {
                    setResults([]);
                    setFilteredOut(0);
                })
                .finally(() => setSearching(false));
        }, 250);
        return () => window.clearTimeout(timer);
    }, [open, lease, query]);

    if (!open) return null;

    /**
     * A lease reached through `?leaseId=` gets the same rule the search does.
     * Null while `leaseApi.get` is still in flight, which is neither yes nor no.
     */
    const leaseTakesRow = lease == null ? null : leaseTakesNewRows(lease.status);

    const submit = async () => {
        if (!lease) return;
        setBusy(true);
        setError(null);
        try {
            const cheque = await chequeApi.cashReceipt(lease.id, {
                postingDate: date,
                // A counter receipt is taken and expected on the same day, and
                // the service requires both: cashReceipt() hands chequeDate
                // straight to receive(), which refuses a null with "a CASH
                // receipt needs the date it is expected on".
                chequeDate: date,
                amount,
                narration: narration || null,
                debitAccountId,
                mode,
            });
            onDone(cheque);
        } catch (e) {
            setError(e instanceof ApiError ? e.message : t("actionFailed"));
        } finally {
            setBusy(false);
        }
    };

    return (
        <LeaseDialog
            open={open}
            title={t("cashReceipt")}
            onClose={onClose}
            onConfirm={submit}
            confirmText={t("cashReceipt")}
            cancelText={tl("cancel")}
            busy={busy}
            confirmDisabled={!lease || leaseTakesRow !== true || amount <= 0}
            confirmTestId="cash-receipt-confirm"
        >
            <div className="space-y-3">
                {!lease ? (
                    <div>
                        <label className={label} htmlFor="cash-receipt-lease-search">{t("selectLease")}</label>
                        <input
                            id="cash-receipt-lease-search"
                            data-testid="cash-receipt-lease-search"
                            className={field}
                            placeholder={t("leaseSearchPlaceholder")}
                            value={query}
                            onChange={e => setQuery(e.target.value)}
                        />
                        {searching && <p className="text-[11px] text-muted mt-1">…</p>}
                        {!searching && filteredOut > 0 && (
                            <p className="text-[11px] text-muted mt-1" data-testid="cash-receipt-lease-filtered">
                                {t("leaseSearchFiltered", { count: filteredOut })}
                            </p>
                        )}
                        {results.length > 0 && (
                            <ul className="mt-1.5 max-h-48 overflow-auto border border-border rounded-lg divide-y divide-border">
                                {results.map(l => (
                                    <li key={l.id}>
                                        <button
                                            type="button"
                                            data-testid={`cash-receipt-lease-option-${l.id}`}
                                            className="w-full text-start px-3 py-2 text-xs hover:bg-input cursor-pointer"
                                            onClick={() => setLease(l)}
                                        >
                                            <span className="font-semibold">{l.unitIdentifier ?? "—"}</span>
                                            <span className="text-muted"> · {l.renterName ?? "—"} · {l.propertyName ?? "—"}</span>
                                        </button>
                                    </li>
                                ))}
                            </ul>
                        )}
                    </div>
                ) : (
                    <div className="rounded-lg border border-border px-3 py-2 flex items-center justify-between gap-2" data-testid="cash-receipt-selected-lease">
                        <span className="text-xs font-semibold">
                            {lease.unitIdentifier ?? "—"} · {lease.renterName ?? "—"}
                        </span>
                        {!initialLeaseId && (
                            <button
                                type="button"
                                className="text-[11px] text-primary hover:underline cursor-pointer"
                                onClick={() => setLease(null)}
                            >
                                {tl("close")}
                            </button>
                        )}
                    </div>
                )}

                {leaseTakesRow === false && lease && (
                    <p
                        role="alert"
                        data-testid="cash-receipt-not-posted"
                        className="rounded-lg bg-warning/10 border border-warning/30 px-3 py-2 text-[11px] text-warning"
                    >
                        {t("leaseNotPosted", { status: tl(`leaseStatus.${lease.status}`) })}
                    </p>
                )}

                <div className="grid grid-cols-2 gap-3">
                    <div>
                        <label className={label}>{tl("chequeMode")}</label>
                        <select
                            data-testid="cash-receipt-mode"
                            className={field}
                            value={mode}
                            onChange={e => setMode(e.target.value as ChequeMode)}
                        >
                            {RECEIPT_MODES.map(m => (
                                <option key={m} value={m}>{tl(`mode.${m}`)}</option>
                            ))}
                        </select>
                    </div>
                    <div>
                        <label className={label} htmlFor="cash-receipt-date">{t("receiptDate")}</label>
                        <input
                            id="cash-receipt-date"
                            data-testid="cash-receipt-date"
                            type="date"
                            className={field}
                            value={date}
                            onChange={e => setDate(e.target.value)}
                        />
                    </div>
                    <div>
                        <label className={label} htmlFor="cash-receipt-amount">{tl("amount")}</label>
                        <NumberInput
                            id="cash-receipt-amount"
                            data-testid="cash-receipt-amount"
                            min={0}
                            step={0.01}
                            className={`${field} text-end tabular-nums`}
                            value={amount}
                            onChange={setAmount}
                        />
                    </div>
                    <div>
                        <label className={label}>{tl("debitAccount")}</label>
                        <SettlementAccountPicker
                            value={debitAccountId}
                            onChange={setDebitAccountId}
                            propertyId={lease?.propertyId}
                            placeholder={tl("debitAccount")}
                        />
                    </div>
                </div>
                <div>
                    <label className={label} htmlFor="cash-receipt-narration">{tl("narration")}</label>
                    <input
                        id="cash-receipt-narration"
                        className={field}
                        value={narration}
                        onChange={e => setNarration(e.target.value)}
                    />
                </div>

                {error && (
                    <p className="text-[11px] text-error" data-testid="cash-receipt-error">{error}</p>
                )}
            </div>
        </LeaseDialog>
    );
}
