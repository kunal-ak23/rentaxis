"use client";

import { useState } from "react";
import { useTranslations } from "next-intl";
import { Download, Loader2 } from "lucide-react";
import { chequeApi, type RenterCheque } from "@/lib/api/leasing";
import { saveResponse } from "@/lib/download";
import { idsInYear, MAX_COMBINED_RECEIPTS, receiptYears } from "@/lib/receipts/receiptYears";

/**
 * A renter row that offers a receipt: cleared, and not banked by a previous owner
 * (the server refuses those). Renters may download whatever the lease's status.
 */
export function isReceiptable(r: Pick<RenterCheque, "status" | "settledBeforeAcquisition">): boolean {
    return r.status === "CLEARED" && !r.settledBeforeAcquisition;
}

const asReceiptRow = (r: RenterCheque) => ({ id: r.id, clearedAt: r.clearedAt, date: r.dueDate });
const receivedOn = (r: RenterCheque) => r.clearedAt ?? r.dueDate;

export type ReceiptPicker = {
    /** The contract the ticks belong to; "" when there is nothing to pick. */
    leaseId: string;
    contracts: { leaseId: string; label: string }[];
    /** Whether the row can be ticked now (receiptable and of the chosen contract). */
    tickable: (row: RenterCheque) => boolean;
    isPicked: (id: string) => boolean;
    pickedIds: string[];
    years: number[];
    year: string;
    overLimit: boolean;
    combining: boolean;
    error: string | null;
    hasReceipts: boolean;
    pickContract: (leaseId: string) => void;
    pickYear: (value: string) => void;
    toggle: (id: string) => void;
    download: () => Promise<void>;
};

/**
 * What the renter has ticked. One download is one contract: ticks live under the
 * chosen contract and are dropped when it changes, so ids of two leases are never sent.
 */
export function useReceiptPicker(rows: RenterCheque[], failedMessage: string): ReceiptPicker {
    const [chosenLease, setChosenLease] = useState("");
    const [picked, setPicked] = useState<ReadonlySet<string>>(new Set());
    const [year, setYear] = useState("");
    const [combining, setCombining] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const receiptable = rows.filter(isReceiptable);
    const contracts: { leaseId: string; label: string }[] = [];
    for (const r of receiptable) {
        if (!contracts.some(c => c.leaseId === r.leaseId)) {
            contracts.push({ leaseId: r.leaseId, label: [r.propertyName, r.unitIdentifier].filter(Boolean).join(" · ") });
        }
    }
    // Default: the contract of the most recent cleared row.
    const latest = receiptable.reduce<RenterCheque | null>(
        (best, r) => (!best || receivedOn(r) > receivedOn(best) ? r : best), null);
    const leaseId = contracts.some(c => c.leaseId === chosenLease) ? chosenLease : latest?.leaseId ?? "";

    const contractRows = receiptable.filter(r => r.leaseId === leaseId);
    const receiptRows = contractRows.map(asReceiptRow);
    const pickedIds = contractRows.filter(r => picked.has(r.id)).map(r => r.id);

    const reset = () => {
        setPicked(new Set());
        setYear("");
        setError(null);
    };

    return {
        leaseId,
        contracts,
        tickable: r => isReceiptable(r) && r.leaseId === leaseId,
        isPicked: id => picked.has(id),
        pickedIds,
        years: receiptYears(receiptRows),
        year,
        overLimit: pickedIds.length > MAX_COMBINED_RECEIPTS,
        combining,
        error,
        hasReceipts: receiptable.length > 0,
        pickContract: value => {
            setChosenLease(value);
            reset();
        },
        pickYear: value => {
            setYear(value);
            setPicked(new Set(value ? idsInYear(receiptRows, Number(value)) : []));
            setError(null);
        },
        toggle: id => {
            const next = new Set(picked);
            if (next.has(id)) next.delete(id); else next.add(id);
            setPicked(next);
            setYear("");
        },
        download: async () => {
            if (pickedIds.length === 0 || pickedIds.length > MAX_COMBINED_RECEIPTS) return;
            setCombining(true);
            setError(null);
            try {
                const res = await chequeApi.combinedReceipt(pickedIds);
                if (!res.ok) {
                    const body = await res.json().catch(() => null) as { message?: string } | null;
                    setError(body?.message || failedMessage);
                    return;
                }
                await saveResponse(res, "receipts.pdf");
                setPicked(new Set());
                setYear("");
            } catch {
                setError(failedMessage);
            } finally {
                setCombining(false);
            }
        },
    };
}

/** The bar above the payment history: contract (when several), year, download. */
export default function RenterReceiptPicker({ picker }: { picker: ReceiptPicker }) {
    const t = useTranslations("OnlinePayments");
    if (!picker.hasReceipts) return null;
    const { pickedIds } = picker;
    return (
        <div className="mb-3" data-testid="combined-receipt-bar">
            <div className="flex flex-wrap items-center gap-3 text-[11px]">
                <span className="text-muted">{t("combinedReceiptHint")}</span>
                {picker.contracts.length > 1 && (
                    <select
                        data-testid="combined-receipt-contract"
                        aria-label={t("selectContract")}
                        value={picker.leaseId}
                        onChange={e => picker.pickContract(e.target.value)}
                        className="px-2 py-1.5 rounded-lg border border-border bg-surface text-[11px]"
                    >
                        {picker.contracts.map(c => <option key={c.leaseId} value={c.leaseId}>{c.label}</option>)}
                    </select>
                )}
                <select
                    data-testid="combined-receipt-year"
                    aria-label={t("selectYear")}
                    value={picker.year}
                    onChange={e => picker.pickYear(e.target.value)}
                    className="px-2 py-1.5 rounded-lg border border-border bg-surface text-[11px]"
                >
                    <option value="">{t("selectYear")}</option>
                    {picker.years.map(y => <option key={y} value={String(y)}>{y}</option>)}
                </select>
                {picker.overLimit && (
                    <span className="text-error" data-testid="combined-receipt-limit">
                        {t("combinedReceiptLimit", { max: MAX_COMBINED_RECEIPTS })}
                    </span>
                )}
                <button
                    type="button"
                    data-testid="combined-receipt-download"
                    disabled={pickedIds.length === 0 || picker.overLimit || picker.combining}
                    onClick={() => { void picker.download(); }}
                    className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg font-semibold bg-primary text-primary-foreground hover:bg-primary/90 cursor-pointer disabled:opacity-50"
                >
                    {picker.combining ? <Loader2 size={12} className="animate-spin" /> : <Download size={12} />}
                    {t("combinedReceipt", { count: pickedIds.length })}
                </button>
            </div>
            {picker.error && (
                <p className="mt-2 text-[11px] text-error" data-testid="combined-receipt-error">{picker.error}</p>
            )}
        </div>
    );
}
