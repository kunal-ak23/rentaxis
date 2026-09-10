"use client";

import { useEffect, useState } from "react";
import { Loader2, Save, RefreshCw, AlertTriangle, CheckCircle2 } from "lucide-react";
import { cn } from "@/lib/utils";
import { formatCurrency } from "@/lib/format";
import ChequeScanner, { type ChequeScannerResult } from "@/components/cheques/ChequeScanner";

/**
 * Editable payment-schedule table for a DRAFT or PENDING_SIGNATURE lease.
 * Admins can adjust due date, amount, payment method, and the cheque/transfer
 * details per row before the lease is finalized. After Activate, the lease
 * service rejects edits with a BusinessRuleViolationException.
 */

type PaymentMethod = "CHEQUE" | "BANK_TRANSFER" | "CASH" | "ONLINE";

type ScheduleRow = {
    id: string;
    leaseId: string;
    installmentNumber: number;
    dueDate: string;
    amount: number;
    status: string;
    paymentMethod: PaymentMethod | string | null;
    chequeNumber: string | null;
    chequeDate: string | null;
    bankName: string | null;
    payerName: string | null;
    chequeImageUrl: string | null;
    chequeImageBlobPath: string | null;
    chequeImageUploadedAt: string | null;
    purposeLabel: string | null;
    isBookingDeposit?: boolean;
    isSecurityDeposit?: boolean;
    isCharge?: boolean;
};

type Props = {
    leaseId: string;
    leaseStatus: string;        // gates editability
    canManage: boolean;         // RBAC gate
    onSaved?: () => void;       // called after a successful save
    className?: string;
    /** Bump this number from the parent to force a re-fetch (e.g. after the
     *  metadata editor regenerates the schedule). */
    refreshKey?: number;
};

const METHOD_OPTIONS: { value: PaymentMethod; label: string }[] = [
    { value: "CHEQUE", label: "Cheque" },
    { value: "BANK_TRANSFER", label: "Bank Transfer" },
    { value: "ONLINE", label: "Online" },
    { value: "CASH", label: "Cash" },
];

const EDITABLE_STATUSES = new Set(["DRAFT", "PENDING_SIGNATURE"]);

function normalizeMethod(m: string | null | undefined): PaymentMethod {
    const v = (m || "").toUpperCase();
    if (v === "CHEQUE" || v === "BANK_TRANSFER" || v === "CASH" || v === "ONLINE") return v;
    return "CHEQUE";
}

function rowLabel(r: ScheduleRow): string {
    if (r.purposeLabel) return r.purposeLabel;
    if (r.isSecurityDeposit) return "Security Deposit";
    if (r.isBookingDeposit) return "Booking Deposit";
    if (r.isCharge) return "Charge";
    return `Installment ${r.installmentNumber}`;
}

function clientValidate(rows: ScheduleRow[]): { ok: boolean; firstError?: string } {
    for (const r of rows) {
        const m = normalizeMethod(r.paymentMethod);
        const label = rowLabel(r);
        if (!r.dueDate) return { ok: false, firstError: `${label}: due date is required` };
        if (r.amount == null || Number.isNaN(r.amount) || r.amount < 0)
            return { ok: false, firstError: `${label}: amount must be a non-negative number` };
        // CHEQUE rows deliberately require nothing beyond due date and amount.
        //
        // A payment plan is drawn up when the lease is signed, but the renter
        // hands over cheques on their own schedule — often a couple up front
        // and the rest later. Requiring cheque #, date and bank on every CHEQUE
        // row meant a plan could not be saved at all until every cheque was
        // physically in hand, and since CHEQUE is the default method the only
        // way out was to mislabel the outstanding rows as CASH. That recorded
        // the wrong payment method to work around a validation rule.
        //
        // A CHEQUE row with blank details now means "cheque expected, not yet
        // received", which is a real state the business has. Details are filled
        // in as cheques arrive. Completeness is enforced where it actually
        // matters — clearing a cheque still requires its details.
        if ((m === "BANK_TRANSFER" || m === "ONLINE") && (!r.bankName || !r.chequeDate))
            return { ok: false, firstError: `${label}: ${m === "ONLINE" ? "online" : "transfer"} rows need bank and date` };
    }
    return { ok: true };
}

export default function PaymentScheduleEditor({ leaseId, leaseStatus, canManage, onSaved, className, refreshKey }: Props) {
    const [rows, setRows] = useState<ScheduleRow[]>([]);
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [saved, setSaved] = useState(false);
    const [dirty, setDirty] = useState(false);
    const [extractedFields, setExtractedFields] = useState<Set<string>>(new Set());
    const [scannedAmounts, setScannedAmounts] = useState<Record<string, number | null>>({});

    const editable = canManage && EDITABLE_STATUSES.has(leaseStatus);

    const fetchRows = async () => {
        setLoading(true);
        setError(null);
        try {
            // Lease-scoped endpoint returns the FULL schedule for one lease as a
            // plain List (no pagination). The tenant-wide `/payments?leaseId=` is
            // paginated and ignores leaseId, so a lease's rows could fall outside
            // the first page and silently vanish from the editor.
            const res = await fetch(`/api/proxy/v1/payments/lease/${leaseId}`);
            if (res.ok) {
                const data = await res.json();
                const list = Array.isArray(data) ? data : (data.content ?? []);
                const mine: ScheduleRow[] = list
                    .filter((p: ScheduleRow) => p.leaseId === leaseId)
                    .sort((a: ScheduleRow, b: ScheduleRow) => {
                        // Non-rent rows (booking deposit, security deposit, charges) sort last;
                        // regular rent installments sort first by installment number ascending.
                        const aNon = !!a.isBookingDeposit || !!a.isSecurityDeposit || !!a.isCharge;
                        const bNon = !!b.isBookingDeposit || !!b.isSecurityDeposit || !!b.isCharge;
                        if (aNon !== bNon) return aNon ? 1 : -1;
                        return (a.installmentNumber ?? 0) - (b.installmentNumber ?? 0);
                    });
                setRows(mine);
                setDirty(false);
                setExtractedFields(new Set());
            } else {
                setError("Failed to load payment schedule");
            }
        } catch (e) {
            console.error(e);
            setError("Network error loading payment schedule");
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        fetchRows();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [leaseId, refreshKey]);

    const updateRow = (id: string, patch: Partial<ScheduleRow>) => {
        setRows((prev) => prev.map((r) => (r.id === id ? { ...r, ...patch } : r)));
        const keys = Object.keys(patch);
        if (keys.length > 0) {
            setExtractedFields((prev) => {
                const next = new Set(prev);
                for (const k of keys) next.delete(`${id}:${k}`);
                return next;
            });
        }
        setDirty(true);
        setSaved(false);
        setError(null);
    };

    const handleSave = async () => {
        const v = clientValidate(rows);
        if (!v.ok) {
            setError(v.firstError ?? "Validation failed");
            return;
        }
        setSaving(true);
        setError(null);
        setSaved(false);
        try {
            const body = {
                rows: rows.map((r) => ({
                    scheduleId: r.id,
                    dueDate: r.dueDate,
                    amount: r.amount,
                    paymentMethod: normalizeMethod(r.paymentMethod),
                    chequeNumber: r.chequeNumber || null,
                    chequeDate: r.chequeDate || null,
                    bankName: r.bankName || null,
                    chequeImageUrl: r.chequeImageUrl || null,
                    chequeImageBlobPath: r.chequeImageBlobPath || null,
                    chequeImageUploadedAt: r.chequeImageUploadedAt || null,
                })),
            };
            const res = await fetch(`/api/proxy/v1/leases/${leaseId}/payment-schedule`, {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(body),
            });
            if (res.ok) {
                setSaved(true);
                setDirty(false);
                if (onSaved) onSaved();
                // Re-fetch so we display whatever the backend persisted (e.g.
                // null-trimmed fields, normalized method casing).
                await fetchRows();
            } else if (res.status === 403) {
                setError("You don't have permission to edit this payment schedule.");
            } else {
                let detail: string | null = null;
                try {
                    const data = await res.json();
                    detail = data?.message || data?.error || null;
                } catch { /* ignore */ }
                setError(detail || "Failed to save payment schedule");
            }
        } catch (e) {
            console.error(e);
            setError("Network error while saving payment schedule");
        } finally {
            setSaving(false);
        }
    };

    if (loading) {
        return (
            <div className={cn("flex items-center gap-2 text-xs text-muted py-4", className)}>
                <Loader2 size={14} className="animate-spin" /> Loading payment schedule…
            </div>
        );
    }

    if (rows.length === 0) {
        return (
            <div className={cn("text-xs text-muted py-4", className)}>
                No payment schedule rows yet.
            </div>
        );
    }

    return (
        <div className={cn("flex flex-col gap-3", className)}>
            {!editable && (
                <p className="text-[11px] text-muted italic">
                    Schedule is locked — lease status is <strong>{leaseStatus}</strong>.
                </p>
            )}

            <div className="overflow-x-auto rounded-xl border border-border">
                <table className="w-full text-xs">
                    <thead className="bg-input/40 text-muted">
                        <tr className="text-left">
                            <th className="px-3 py-2 font-semibold">#</th>
                            <th className="px-3 py-2 font-semibold">Purpose</th>
                            <th className="px-3 py-2 font-semibold">Due Date</th>
                            <th className="px-3 py-2 font-semibold">Cheque / Payment Date</th>
                            <th className="px-3 py-2 font-semibold">Method</th>
                            <th className="px-3 py-2 font-semibold">Unique ID</th>
                            <th className="px-3 py-2 font-semibold">Bank</th>
                            <th className="px-3 py-2 font-semibold text-right">Amount</th>
                            <th className="px-3 py-2 font-semibold">Status</th>
                        </tr>
                    </thead>
                    <tbody>
                        {rows.map((r) => {
                            const m = normalizeMethod(r.paymentMethod);
                            // Whether the bank column applies at all — it is disabled for
                            // CASH. Not a validation flag: cheque details are optional, so
                            // an applicable-but-empty bank field is a legitimate state.
                            const bankApplies = m === "CHEQUE" || m === "BANK_TRANSFER" || m === "ONLINE";
                            const paymentDateRequired = m !== "CASH";
                            const paymentDateLabel = m === "CHEQUE" ? "Cheque date"
                                : m === "BANK_TRANSFER" ? "Transfer date"
                                : m === "ONLINE" ? "Online payment date"
                                : "Receipt date (optional)";
                            const rowDisabled = !editable || r.status !== "PENDING";
                            const markExtracted = (field: string) => extractedFields.has(`${r.id}:${field}`);
                            const onExtracted = (data: ChequeScannerResult) => {
                                const patch: Partial<ScheduleRow> = {
                                    chequeImageUrl: data.imageUrl,
                                    chequeImageBlobPath: data.imageBlobPath,
                                    chequeImageUploadedAt: data.imageUploadedAt,
                                };
                                if (typeof data.chequeNumber !== "undefined") patch.chequeNumber = data.chequeNumber ?? null;
                                if (typeof data.bankName !== "undefined") patch.bankName = data.bankName ?? null;
                                if (typeof data.payerName !== "undefined") patch.payerName = data.payerName ?? null;
                                if (typeof data.chequeDate !== "undefined") patch.chequeDate = data.chequeDate ?? null;
                                setRows((prev) => prev.map((row) => (row.id === r.id ? { ...row, ...patch } : row)));
                                setExtractedFields((prev) => {
                                    const next = new Set(prev);
                                    if (typeof data.chequeNumber !== "undefined") next.add(`${r.id}:chequeNumber`);
                                    if (typeof data.bankName !== "undefined") next.add(`${r.id}:bankName`);
                                    if (typeof data.payerName !== "undefined") next.add(`${r.id}:payerName`);
                                    if (typeof data.chequeDate !== "undefined") next.add(`${r.id}:chequeDate`);
                                    return next;
                                });
                                if (typeof data.amount !== "undefined") {
                                    setScannedAmounts(prev => ({ ...prev, [r.id]: data.amount ?? null }));
                                }
                                setDirty(true);
                                setSaved(false);
                                setError(null);
                            };
                            return (
                                <tr key={r.id} className="border-t border-border align-top">
                                    <td className="px-3 py-2 tabular-nums">{r.isBookingDeposit ? "B" : r.isSecurityDeposit ? "S" : r.isCharge ? "C" : r.installmentNumber}</td>
                                    <td className="px-3 py-2 text-muted">{r.purposeLabel || (r.isSecurityDeposit ? "Security Deposit" : r.isBookingDeposit ? "Booking Deposit" : "")}</td>
                                    <td className="px-3 py-2">
                                        <input
                                            type="date"
                                            value={r.dueDate?.substring(0, 10) || ""}
                                            onChange={(e) => updateRow(r.id, { dueDate: e.target.value })}
                                            disabled={rowDisabled}
                                            title="Due date — when this installment is owed by the tenant"
                                            className="border border-border rounded px-2 py-1 text-xs bg-surface disabled:bg-input/40 disabled:cursor-not-allowed"
                                        />
                                    </td>
                                    <td className="px-3 py-2">
                                        <input
                                            type="date"
                                            value={r.chequeDate?.substring(0, 10) || ""}
                                            onChange={(e) => updateRow(r.id, { chequeDate: e.target.value })}
                                            disabled={rowDisabled}
                                            title={paymentDateLabel}
                                            className={cn(
                                                "border border-border rounded px-2 py-1 text-xs bg-surface disabled:bg-input/40 disabled:cursor-not-allowed",
                                                m === "CASH" && "opacity-60"
                                            )}
                                        />
                                        {paymentDateRequired
                                            ? <div className="text-[10px] text-muted mt-0.5">{paymentDateLabel.replace(" date", "")}</div>
                                            : <div className="text-[10px] text-muted mt-0.5 italic">optional for cash</div>}
                                    </td>
                                    <td className="px-3 py-2">
                                        <select
                                            value={m}
                                            onChange={(e) => updateRow(r.id, { paymentMethod: e.target.value as PaymentMethod })}
                                            disabled={rowDisabled}
                                            className="border border-border rounded px-2 py-1 text-xs bg-surface disabled:bg-input/40 disabled:cursor-not-allowed"
                                        >
                                            {METHOD_OPTIONS.map((o) => (
                                                <option key={o.value} value={o.value}>{o.label}</option>
                                            ))}
                                        </select>
                                    </td>
                                    <td className="px-3 py-2">
                                        {(() => {
                                            const isCash = m === "CASH";
                                            const placeholder = m === "CHEQUE" ? "Cheque number"
                                                : m === "BANK_TRANSFER" ? "Transfer reference"
                                                : m === "ONLINE" ? "Payment reference"
                                                : "Notes (e.g., handed to ___ on ___)";
                                            const subLabel = m === "CHEQUE" ? "cheque #"
                                                : m === "BANK_TRANSFER" ? "ref no"
                                                : m === "ONLINE" ? "ref no"
                                                : "notes";
                                            return (
                                                <>
                                                    <input
                                                        type="text"
                                                        value={r.chequeNumber || ""}
                                                        onChange={(e) => updateRow(r.id, { chequeNumber: e.target.value })}
                                                        disabled={rowDisabled}
                                                        placeholder={placeholder}
                                                        className={cn(
                                                            "border border-border rounded px-2 py-1 text-xs bg-surface disabled:bg-input/40 disabled:cursor-not-allowed",
                                                            isCash ? "w-64" : "w-44"
                                                        )}
                                                    />
                                                    {markExtracted("chequeNumber") && (
                                                        <div className="text-[10px] text-emerald-600 mt-0.5">✨ Extracted</div>
                                                    )}
                                                    <div className="text-[10px] text-muted mt-0.5">{subLabel}</div>
                                                    <div className="mt-1">
                                                        <ChequeScanner onExtracted={onExtracted} disabled={rowDisabled} />
                                                    </div>
                                                </>
                                            );
                                        })()}
                                    </td>
                                    <td className="px-3 py-2">
                                        <input
                                            type="text"
                                            value={r.bankName || ""}
                                            onChange={(e) => updateRow(r.id, { bankName: e.target.value })}
                                            disabled={rowDisabled || !bankApplies}
                                            placeholder={bankApplies ? "—" : "n/a"}
                                            className="border border-border rounded px-2 py-1 text-xs bg-surface w-32 disabled:bg-input/40 disabled:cursor-not-allowed"
                                        />
                                        {markExtracted("bankName") && (
                                            <div className="text-[10px] text-emerald-600 mt-0.5">✨ Extracted</div>
                                        )}
                                    </td>
                                    <td className="px-3 py-2 text-right">
                                        <input
                                            type="number"
                                            step="0.01"
                                            min="0"
                                            value={Number.isFinite(r.amount) ? r.amount : 0}
                                            onChange={(e) => updateRow(r.id, { amount: Number(e.target.value) })}
                                            disabled={rowDisabled}
                                            className="border border-border rounded px-2 py-1 text-xs bg-surface w-28 text-right tabular-nums disabled:bg-input/40 disabled:cursor-not-allowed"
                                        />
                                        <div className="text-[10px] text-muted mt-0.5">{formatCurrency(r.amount || 0)}</div>
                                        {scannedAmounts[r.id] != null && Math.round(scannedAmounts[r.id]! * 100) !== Math.round(r.amount * 100) && (
                                            <span className="ml-2 rounded-full border border-amber-300 bg-amber-50 px-2 py-0.5 text-[11px] text-amber-800">
                                                Cheque {formatCurrency(scannedAmounts[r.id]!)} ≠ {formatCurrency(r.amount)}
                                            </span>
                                        )}
                                    </td>
                                    <td className="px-3 py-2 text-muted">{r.status}</td>
                                </tr>
                            );
                        })}
                    </tbody>
                </table>
            </div>

            {editable && (
                <div className="flex items-center gap-2">
                    <button
                        onClick={handleSave}
                        disabled={!dirty || saving}
                        className="inline-flex items-center gap-2 px-4 py-2 rounded-lg text-xs font-semibold bg-primary text-primary-foreground hover:bg-primary/90 disabled:opacity-50 transition-colors cursor-pointer"
                    >
                        {saving ? <Loader2 size={12} className="animate-spin" /> : <Save size={12} />}
                        Save schedule
                    </button>
                    <button
                        onClick={fetchRows}
                        disabled={saving}
                        className="inline-flex items-center gap-2 px-4 py-2 rounded-lg text-xs font-semibold border border-border text-foreground hover:bg-input/40 transition-colors cursor-pointer"
                    >
                        <RefreshCw size={12} /> Reset
                    </button>
                    {error && (
                        <span className="inline-flex items-center gap-1 text-[11px] text-error">
                            <AlertTriangle size={12} /> {error}
                        </span>
                    )}
                    {saved && !dirty && !error && (
                        <span className="inline-flex items-center gap-1 text-[11px] text-success">
                            <CheckCircle2 size={12} /> Saved
                        </span>
                    )}
                </div>
            )}
        </div>
    );
}
