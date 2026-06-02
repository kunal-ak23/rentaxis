"use client";

import { useState, useEffect, useMemo, useCallback } from "react";
import { useRouter } from "@/i18n/routing";
import { ArrowLeft, ArrowRight, X, Check, Loader2, Sparkles, AlertTriangle, Building2, User, Calendar, DollarSign, CreditCard, FileText } from "lucide-react";
import { cn } from "@/lib/utils";
import { formatCurrency } from "@/lib/format";
import PaymentScheduleEditor from "./PaymentScheduleEditor";
import ChequeScanner from "@/components/cheques/ChequeScanner";
import BulkChequeUploadFlow from "@/components/cheques/BulkChequeUploadFlow";

/**
 * Five-step wizard for creating a new draft lease.
 *
 * 1. Parties — unit, renter, agreement date
 * 2. Terms — dates, rent, deposit, ejari/payment ref
 * 3. Charges & VAT — other charges repeater (name/amount/frequency/VAT) + rent VAT toggle
 *    (charges default VAT-on when the unit's property is COMMERCIAL)
 * 4. Payment plan — # of cheques, default method, deposit method,
 *    optional booking-deposit subform
 * 5. Schedule & finalize — Save Draft creates the lease and the
 *    auto-generated schedule, then admin can adjust per-row
 *    dates / cheque / bank / method via PaymentScheduleEditor before
 *    optionally generating the contract.
 *
 * Edit on an existing draft keeps using the inline form on the leases
 * list page; this wizard is for the create path only.
 */

type ChargeFrequency = "ONE_TIME" | "PER_INSTALLMENT";
type ChargeRow = { name: string; amount: number; vatApplicable: boolean; frequency: ChargeFrequency };

type Unit = {
    id: string;
    unitNumber: string;
    status: string;
    property?: { id: string; nameEn?: string; nameAr?: string; type?: string };
};

type Renter = {
    id: string;
    nameEn: string;
    nameAr: string;
};

type WizardData = {
    unitId: string;
    renterId: string;
    agreementDate: string;
    startDate: string;
    endDate: string;
    rentAmount: number;
    depositAmount: number;
    ejariNumber: string;
    paymentReferenceNumber: string;
    paymentTerms: number;
    paymentMethod: string;
    depositPaymentMethod: string;
    rentVatApplicable: boolean;
    charges: ChargeRow[];
    bookingDepositOpen: boolean;
    bookingDeposit: { amount: number; chequeNumber: string; chequeDate: string; bankName: string; scannedAmount: number | null };
};

const initialData: WizardData = {
    unitId: "",
    renterId: "",
    agreementDate: "",
    startDate: "",
    endDate: "",
    rentAmount: 0,
    depositAmount: 0,
    ejariNumber: "",
    paymentReferenceNumber: "",
    paymentTerms: 4,
    paymentMethod: "CHEQUE",
    depositPaymentMethod: "CHEQUE",
    rentVatApplicable: false,
    charges: [],
    bookingDepositOpen: false,
    bookingDeposit: { amount: 0, chequeNumber: "", chequeDate: "", bankName: "", scannedAmount: null },
};

const STEPS = [
    { key: "parties", label: "Parties", icon: User },
    { key: "terms", label: "Terms", icon: Calendar },
    { key: "charges", label: "Charges & VAT", icon: DollarSign },
    { key: "plan", label: "Payment plan", icon: CreditCard },
    { key: "finalize", label: "Schedule & finalize", icon: FileText },
] as const;

type StepKey = typeof STEPS[number]["key"];

type Props = {
    open: boolean;
    units: Unit[];
    renters: Renter[];
    onClose: () => void;
    onCreated: () => void;
};

export default function LeaseWizard({ open, units, renters, onClose, onCreated }: Props) {
    const router = useRouter();
    const [stepIdx, setStepIdx] = useState(0);
    const [data, setData] = useState<WizardData>(initialData);
    const [savedLeaseId, setSavedLeaseId] = useState<string | null>(null);
    const [savedLeaseStatus, setSavedLeaseStatus] = useState<string | null>(null);
    const [submitting, setSubmitting] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [bulkOpen, setBulkOpen] = useState(false);
    const [wizardSchedules, setWizardSchedules] = useState<any[]>([]);
    const [scheduleRefreshKey, setScheduleRefreshKey] = useState(0);

    const reset = useCallback(() => {
        setStepIdx(0);
        setData(initialData);
        setSavedLeaseId(null);
        setSavedLeaseStatus(null);
        setSubmitting(false);
        setError(null);
        setBulkOpen(false);
        setWizardSchedules([]);
        setScheduleRefreshKey(0);
    }, []);

    const loadWizardSchedules = async (leaseId: string) => {
        const res = await fetch(`/api/proxy/v1/payments/lease/${leaseId}`);
        if (res.ok) setWizardSchedules(await res.json());
    };

    useEffect(() => {
        if (open) {
            // re-initialize on open (so closing and reopening starts fresh)
            reset();
        }
    }, [open, reset]);

    const selectedUnit = useMemo(() => units.find((u) => u.id === data.unitId), [units, data.unitId]);
    const selectedRenter = useMemo(() => renters.find((r) => r.id === data.renterId), [renters, data.renterId]);
    const isCommercial = selectedUnit?.property?.type === "COMMERCIAL";

    const update = (patch: Partial<WizardData>) => {
        setData((prev) => ({ ...prev, ...patch }));
        setError(null);
    };

    const onPickUnit = (unitId: string) => {
        const u = units.find((x) => x.id === unitId);
        const commercial = u?.property?.type === "COMMERCIAL";
        // Pick up commercial-VAT defaults the moment the unit is chosen.
        update({
            unitId,
            rentVatApplicable: commercial,
        });
    };

    const updateCharge = (i: number, patch: Partial<ChargeRow>) =>
        setData((prev) => ({ ...prev, charges: prev.charges.map((c, j) => (j === i ? { ...c, ...patch } : c)) }));

    // --- per-step validation -----------------------------------------------
    const stepError = (idx: number): string | null => {
        switch (STEPS[idx].key) {
            case "parties":
                if (!data.unitId) return "Select a unit";
                if (!data.renterId) return "Select a renter";
                return null;
            case "terms":
                if (!data.startDate || !data.endDate) return "Lease start and end date are required";
                if (new Date(data.endDate) <= new Date(data.startDate)) return "End date must be after start date";
                if (!data.rentAmount || data.rentAmount <= 0) return "Monthly rent must be greater than 0";
                if (data.depositAmount < 0) return "Deposit cannot be negative";
                return null;
            case "charges": {
                for (const c of data.charges) {
                    if (!c.name.trim()) return "Each charge must have a name";
                    if (c.amount < 0) return "Charge amounts cannot be negative";
                }
                return null;
            }
            case "plan":
                if (!data.paymentTerms || data.paymentTerms < 1) return "Number of cheques must be at least 1";
                if (data.bookingDepositOpen) {
                    if (data.bookingDeposit.amount <= 0) return "Booking deposit amount must be greater than 0 (or close the section)";
                }
                return null;
            default:
                return null;
        }
    };

    const goNext = () => {
        const e = stepError(stepIdx);
        if (e) { setError(e); return; }
        setError(null);
        setStepIdx((i) => Math.min(i + 1, STEPS.length - 1));
    };

    const goBack = () => {
        setError(null);
        setStepIdx((i) => Math.max(i - 1, 0));
    };

    // --- save draft --------------------------------------------------------
    const handleSaveDraft = async (): Promise<{ id: string; status: string } | null> => {
        setSubmitting(true);
        setError(null);
        try {
            // rentAmount = total rent across the whole lease tenure (monthly × months),
            // not monthly × paymentTerms. paymentTerms is the cheque count and is
            // independent of the rent total — the backend splits the total rent
            // evenly across paymentTerms installments distributed over the tenure.
            const monthsBetween = (() => {
                const s = new Date(data.startDate);
                const e = new Date(data.endDate);
                // End date is the inclusive last day of tenancy, so +1: Jun→Dec = 7,
                // Jan→Dec = 12. Matches backend DateMath.monthsInclusive.
                const months = (e.getFullYear() - s.getFullYear()) * 12 + (e.getMonth() - s.getMonth()) + 1;
                return Math.max(months, 1);
            })();
            const body: Record<string, unknown> = {
                unitId: data.unitId,
                renterId: data.renterId,
                startDate: data.startDate,
                endDate: data.endDate,
                rentAmount: data.rentAmount * monthsBetween,
                monthlyRent: data.rentAmount,
                depositAmount: data.depositAmount,
                ejariNumber: data.ejariNumber || null,
                paymentTerms: data.paymentTerms,
                paymentMethod: data.paymentMethod,
                depositPaymentMethod: data.depositPaymentMethod,
                paymentReferenceNumber: data.paymentReferenceNumber || null,
                agreementDate: data.agreementDate || null,
                rentVatApplicable: data.rentVatApplicable,
                charges: data.charges,
            };
            if (data.bookingDepositOpen && data.bookingDeposit.amount > 0) {
                body.bookingDeposit = {
                    amount: data.bookingDeposit.amount,
                    chequeNumber: data.bookingDeposit.chequeNumber || null,
                    chequeDate: data.bookingDeposit.chequeDate || null,
                    bankName: data.bookingDeposit.bankName || null,
                };
            }
            const res = await fetch("/api/proxy/v1/leases", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(body),
            });
            if (res.ok) {
                const created = await res.json();
                setSavedLeaseId(created.id);
                setSavedLeaseStatus(created.status || "DRAFT");
                onCreated();
                return { id: created.id, status: created.status || "DRAFT" };
            }
            let detail: string | null = null;
            try { const j = await res.json(); detail = j?.message || j?.error || null; } catch { /* ignore */ }
            setError(detail || `Failed to save draft (HTTP ${res.status})`);
            return null;
        } catch (e) {
            console.error(e);
            setError("Network error while saving the draft");
            return null;
        } finally {
            setSubmitting(false);
        }
    };

    if (!open) return null;

    const currentStep = STEPS[stepIdx];
    const onLastStep = stepIdx === STEPS.length - 1;

    return (
        <div className="fixed inset-0 z-50 flex items-stretch justify-center bg-black/60 p-0 sm:p-6">
            <div className="bg-surface w-full max-w-4xl rounded-none sm:rounded-2xl border border-border shadow-2xl overflow-hidden flex flex-col">
                {/* Header + stepper */}
                <div className="flex items-center justify-between px-6 py-4 border-b border-border shrink-0">
                    <div>
                        <h2 className="text-sm font-semibold text-foreground flex items-center gap-2">
                            <Sparkles size={14} className="text-primary" /> New Lease — {currentStep.label}
                        </h2>
                        <p className="text-[11px] text-muted mt-0.5">Step {stepIdx + 1} of {STEPS.length}</p>
                    </div>
                    <button onClick={onClose} className="p-2 rounded-lg text-muted hover:bg-input hover:text-foreground" aria-label="Close wizard">
                        <X size={16} />
                    </button>
                </div>

                <div className="px-6 py-3 border-b border-border bg-input/30 shrink-0">
                    <div className="flex items-center gap-1.5 overflow-x-auto">
                        {STEPS.map((s, i) => {
                            const Icon = s.icon;
                            const done = i < stepIdx || (onLastStep && i === stepIdx && !!savedLeaseId);
                            const active = i === stepIdx;
                            return (
                                <div key={s.key} className="flex items-center gap-1.5">
                                    <div className={cn(
                                        "flex items-center gap-1.5 px-2.5 py-1 rounded-full text-[11px] font-semibold transition-colors",
                                        done ? "bg-success/15 text-success" :
                                        active ? "bg-primary/15 text-primary" :
                                        "bg-input text-muted",
                                    )}>
                                        {done ? <Check size={11} /> : <Icon size={11} />}
                                        <span className="whitespace-nowrap">{i + 1}. {s.label}</span>
                                    </div>
                                    {i < STEPS.length - 1 && <span className="text-muted/50">›</span>}
                                </div>
                            );
                        })}
                    </div>
                </div>

                {/* Body */}
                <div className="flex-1 overflow-y-auto px-6 py-5">
                    {currentStep.key === "parties" && (
                        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                            <Field label="Unit *">
                                <select
                                    value={data.unitId}
                                    onChange={(e) => onPickUnit(e.target.value)}
                                    className="w-full bg-input border border-border p-3 rounded-xl text-xs"
                                >
                                    <option value="">— Select a unit —</option>
                                    {units.filter(u => u.status === "VACANT" || u.id === data.unitId).map((u) => (
                                        <option key={u.id} value={u.id}>
                                            {u.unitNumber} • {u.property?.nameEn || "—"} {u.property?.type === "COMMERCIAL" ? "[Commercial]" : ""}
                                        </option>
                                    ))}
                                </select>
                                {selectedUnit && (
                                    <p className="text-[11px] text-muted mt-1.5 flex items-center gap-1">
                                        <Building2 size={11} /> {selectedUnit.property?.nameEn} • {selectedUnit.property?.type || "RESIDENTIAL"}
                                    </p>
                                )}
                            </Field>
                            <Field label="Renter *">
                                <select
                                    value={data.renterId}
                                    onChange={(e) => update({ renterId: e.target.value })}
                                    className="w-full bg-input border border-border p-3 rounded-xl text-xs"
                                >
                                    <option value="">— Select a renter —</option>
                                    {renters.map((r) => (
                                        <option key={r.id} value={r.id}>{r.nameEn}{r.nameAr ? ` (${r.nameAr})` : ""}</option>
                                    ))}
                                </select>
                            </Field>
                            <Field label="Agreement date" hint="Defaults to today on contract generation">
                                <input
                                    type="date"
                                    value={data.agreementDate}
                                    onChange={(e) => update({ agreementDate: e.target.value })}
                                    className="w-full bg-input border border-border p-3 rounded-xl text-xs"
                                />
                            </Field>
                        </div>
                    )}

                    {currentStep.key === "terms" && (
                        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                            <Field label="Start date *">
                                <input type="date" value={data.startDate} onChange={(e) => update({ startDate: e.target.value })}
                                    className="w-full bg-input border border-border p-3 rounded-xl text-xs" />
                            </Field>
                            <Field label="End date *">
                                <input type="date" value={data.endDate} onChange={(e) => update({ endDate: e.target.value })}
                                    className="w-full bg-input border border-border p-3 rounded-xl text-xs" />
                            </Field>
                            <Field label="Monthly rent (AED) *">
                                <input type="number" min={0} step={0.01} value={data.rentAmount}
                                    onChange={(e) => update({ rentAmount: Number(e.target.value) })}
                                    className="w-full bg-input border border-border p-3 rounded-xl text-xs" />
                            </Field>
                            <Field label="Security deposit (AED)">
                                <input type="number" min={0} step={0.01} value={data.depositAmount}
                                    onChange={(e) => update({ depositAmount: Number(e.target.value) })}
                                    className="w-full bg-input border border-border p-3 rounded-xl text-xs" />
                            </Field>
                            <Field label="Ejari #">
                                <input type="text" value={data.ejariNumber} onChange={(e) => update({ ejariNumber: e.target.value })}
                                    className="w-full bg-input border border-border p-3 rounded-xl text-xs" />
                            </Field>
                            <Field label="Payment reference #">
                                <input type="text" value={data.paymentReferenceNumber} onChange={(e) => update({ paymentReferenceNumber: e.target.value })}
                                    className="w-full bg-input border border-border p-3 rounded-xl text-xs" />
                            </Field>
                        </div>
                    )}

                    {currentStep.key === "charges" && (
                        <div className="space-y-5">
                            <div className="flex items-center justify-between">
                                <h3 className="text-xs font-semibold">Other charges</h3>
                                <button type="button"
                                    onClick={() => setData((prev) => ({ ...prev, charges: [...prev.charges, { name: "", amount: 0, vatApplicable: isCommercial, frequency: "ONE_TIME" as ChargeFrequency }] }))}
                                    className="rounded border border-border px-2 py-1 text-xs">+ Add charge</button>
                            </div>
                            {data.charges.length === 0 && <p className="text-[11px] text-muted">No extra charges. Add admin fee, parking, maintenance, etc.</p>}
                            {data.charges.map((c, i) => (
                                <div key={i} className="grid grid-cols-1 md:grid-cols-[1fr_120px_120px_110px_32px] gap-2 items-end">
                                    <Field label="Name"><input type="text" value={c.name}
                                        onChange={(e) => updateCharge(i, { name: e.target.value })}
                                        className="w-full bg-input border border-border p-2 rounded-lg text-xs" /></Field>
                                    <Field label="Amount (AED)"><input type="number" min={0} step={0.01} value={c.amount}
                                        onChange={(e) => updateCharge(i, { amount: Number(e.target.value) })}
                                        className="w-full bg-input border border-border p-2 rounded-lg text-xs" /></Field>
                                    <Field label="Frequency">
                                        <select value={c.frequency} onChange={(e) => updateCharge(i, { frequency: e.target.value as ChargeFrequency })}
                                            className="w-full bg-input border border-border p-2 rounded-lg text-xs">
                                            <option value="ONE_TIME">One-time</option>
                                            <option value="PER_INSTALLMENT">Per installment</option>
                                        </select>
                                    </Field>
                                    <VatToggle label="VAT" value={c.vatApplicable} onChange={(v) => updateCharge(i, { vatApplicable: v })} />
                                    <button type="button" onClick={() => setData((prev) => ({ ...prev, charges: prev.charges.filter((_, j) => j !== i) }))}
                                        className="rounded border border-border p-2 text-xs">✕</button>
                                </div>
                            ))}
                            <VatToggle label="Rent VAT" value={data.rentVatApplicable} onChange={(v) => update({ rentVatApplicable: v })} />
                        </div>
                    )}

                    {currentStep.key === "plan" && (
                        <div className="space-y-5">
                            <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
                                <Field label="Number of installments *" hint="Rent will be split equally across this many payments">
                                    <input type="number" min={1} max={36} value={data.paymentTerms}
                                        onChange={(e) => update({ paymentTerms: Number(e.target.value) })}
                                        className="w-full bg-input border border-border p-3 rounded-xl text-xs" />
                                </Field>
                                <Field label="Default payment method">
                                    <select value={data.paymentMethod} onChange={(e) => update({ paymentMethod: e.target.value })}
                                        className="w-full bg-input border border-border p-3 rounded-xl text-xs">
                                        <option value="CHEQUE">Cheque</option>
                                        <option value="BANK_TRANSFER">Bank Transfer</option>
                                        <option value="ONLINE">Online</option>
                                        <option value="CASH">Cash</option>
                                    </select>
                                </Field>
                                <Field label="Deposit payment method">
                                    <select value={data.depositPaymentMethod} onChange={(e) => update({ depositPaymentMethod: e.target.value })}
                                        className="w-full bg-input border border-border p-3 rounded-xl text-xs">
                                        <option value="CHEQUE">Cheque</option>
                                        <option value="BANK_TRANSFER">Bank Transfer</option>
                                        <option value="ONLINE">Online</option>
                                        <option value="CASH">Cash</option>
                                    </select>
                                </Field>
                            </div>

                            <div className="border border-border rounded-xl p-4 bg-input/30">
                                <label className="flex items-center gap-2 text-xs font-semibold text-foreground cursor-pointer">
                                    <input
                                        type="checkbox"
                                        checked={data.bookingDepositOpen}
                                        onChange={(e) => update({ bookingDepositOpen: e.target.checked })}
                                    />
                                    Include a booking deposit (received before lease start)
                                </label>
                                {data.bookingDepositOpen && (
                                    <div className="grid grid-cols-1 md:grid-cols-2 gap-3 mt-3">
                                        <div className="md:col-span-2">
                                            <ChequeScanner
                                                onExtracted={(result) =>
                                                    update({
                                                        bookingDeposit: {
                                                            ...data.bookingDeposit,
                                                            chequeNumber: result.chequeNumber ?? data.bookingDeposit.chequeNumber,
                                                            chequeDate: result.chequeDate ?? data.bookingDeposit.chequeDate,
                                                            bankName: result.bankName ?? data.bookingDeposit.bankName,
                                                            scannedAmount: result.amount ?? null,
                                                        },
                                                    })
                                                }
                                            />
                                        </div>
                                        <Field label="Amount (AED) *">
                                            <input type="number" min={0} step={0.01} value={data.bookingDeposit.amount}
                                                onChange={(e) => update({ bookingDeposit: { ...data.bookingDeposit, amount: Number(e.target.value) } })}
                                                className="w-full bg-surface border border-border p-3 rounded-xl text-xs" />
                                            {data.bookingDeposit.scannedAmount != null && data.bookingDeposit.scannedAmount !== data.bookingDeposit.amount && (
                                                <button type="button"
                                                    onClick={() => update({ bookingDeposit: { ...data.bookingDeposit, amount: data.bookingDeposit.scannedAmount! } })}
                                                    className="mt-1 inline-flex items-center gap-1 rounded-full border border-primary/40 bg-primary/10 px-2 py-0.5 text-[11px] text-primary">
                                                    From cheque: AED {data.bookingDeposit.scannedAmount} · Apply
                                                </button>
                                            )}
                                        </Field>
                                        <Field label="Cheque number">
                                            <input type="text" value={data.bookingDeposit.chequeNumber}
                                                onChange={(e) => update({ bookingDeposit: { ...data.bookingDeposit, chequeNumber: e.target.value } })}
                                                className="w-full bg-surface border border-border p-3 rounded-xl text-xs" />
                                        </Field>
                                        <Field label="Cheque date">
                                            <input type="date" value={data.bookingDeposit.chequeDate}
                                                onChange={(e) => update({ bookingDeposit: { ...data.bookingDeposit, chequeDate: e.target.value } })}
                                                className="w-full bg-surface border border-border p-3 rounded-xl text-xs" />
                                        </Field>
                                        <Field label="Bank">
                                            <input type="text" value={data.bookingDeposit.bankName}
                                                onChange={(e) => update({ bookingDeposit: { ...data.bookingDeposit, bankName: e.target.value } })}
                                                className="w-full bg-surface border border-border p-3 rounded-xl text-xs" />
                                        </Field>
                                    </div>
                                )}
                            </div>
                        </div>
                    )}

                    {currentStep.key === "finalize" && (
                        <div className="space-y-5">
                            {!savedLeaseId ? (
                                <>
                                    <div className="rounded-xl border border-border bg-input/30 p-4">
                                        <h3 className="text-xs font-semibold text-foreground mb-3">Review draft</h3>
                                        <dl className="grid grid-cols-1 md:grid-cols-2 gap-x-6 gap-y-2 text-[11px]">
                                            <Summary label="Unit" value={selectedUnit ? `${selectedUnit.unitNumber} • ${selectedUnit.property?.nameEn}` : "—"} />
                                            <Summary label="Renter" value={selectedRenter?.nameEn || "—"} />
                                            <Summary label="Period" value={`${data.startDate || "—"} → ${data.endDate || "—"}`} />
                                            <Summary label="Monthly rent" value={formatCurrency(data.rentAmount)} />
                                            <Summary label="Deposit" value={formatCurrency(data.depositAmount)} />
                                            <Summary label="Other charges" value={data.charges.length > 0 ? data.charges.map((c) => `${c.name} (${c.frequency === "ONE_TIME" ? "one-time" : "per installment"})`).join(", ") : "None"} />
                                            <Summary label="Installments" value={`${data.paymentTerms} × ${data.paymentMethod}`} />
                                            <Summary label="Rent VAT" value={data.rentVatApplicable ? "VAT 5%" : "Exempt"} />
                                            {data.bookingDepositOpen && (
                                                <Summary label="Booking deposit" value={`${formatCurrency(data.bookingDeposit.amount)} • ${data.bookingDeposit.bankName || "—"}`} />
                                            )}
                                        </dl>
                                    </div>
                                    <div className="text-[11px] text-muted">
                                        Saving will create the draft lease and auto-generate the installment schedule. You can then adjust per-row dates, cheque numbers, banks, and methods before generating the contract.
                                    </div>
                                </>
                            ) : (
                                <>
                                    <div className="rounded-xl bg-success/10 border border-success/30 p-3 text-[11px] text-success">
                                        Draft lease created. Adjust the schedule below — change dates, cheque numbers, banks, or per-row methods. Click <strong>Save schedule</strong> to persist edits.
                                    </div>
                                    <div className="flex items-center justify-end">
                                        <button
                                            type="button"
                                            onClick={async () => { await loadWizardSchedules(savedLeaseId); setBulkOpen(true); }}
                                            className="rounded border border-border px-3 py-1 text-xs"
                                        >
                                            Bulk upload cheques
                                        </button>
                                    </div>
                                    {bulkOpen && savedLeaseId && (
                                        <BulkChequeUploadFlow
                                            leaseId={savedLeaseId}
                                            schedules={wizardSchedules.map(p => ({
                                                id: p.id,
                                                installmentNumber: p.installmentNumber,
                                                dueDate: p.dueDate,
                                                amount: p.amount,
                                                status: p.status,
                                            }))}
                                            onSuccess={() => { setBulkOpen(false); setScheduleRefreshKey(k => k + 1); }}
                                            onClose={() => setBulkOpen(false)}
                                        />
                                    )}
                                    <PaymentScheduleEditor
                                        leaseId={savedLeaseId}
                                        leaseStatus={savedLeaseStatus || "DRAFT"}
                                        canManage={true}
                                        refreshKey={scheduleRefreshKey}
                                    />
                                </>
                            )}
                        </div>
                    )}
                </div>

                {/* Footer */}
                <div className="flex items-center justify-between px-6 py-4 border-t border-border bg-surface shrink-0">
                    {error ? (
                        <span className="inline-flex items-center gap-1.5 text-[11px] text-error">
                            <AlertTriangle size={12} /> {error}
                        </span>
                    ) : <span />}
                    <div className="flex items-center gap-2">
                        {!onLastStep && (
                            <>
                                <button onClick={goBack} disabled={stepIdx === 0}
                                    className="px-4 py-2 rounded-lg text-xs font-semibold border border-border text-foreground hover:bg-input/40 disabled:opacity-40 cursor-pointer">
                                    <span className="inline-flex items-center gap-1.5"><ArrowLeft size={12} /> Back</span>
                                </button>
                                <button onClick={goNext}
                                    className="px-4 py-2 rounded-lg text-xs font-semibold bg-primary text-primary-foreground hover:bg-primary/90 cursor-pointer">
                                    <span className="inline-flex items-center gap-1.5">Next <ArrowRight size={12} /></span>
                                </button>
                            </>
                        )}
                        {onLastStep && !savedLeaseId && (
                            <>
                                <button onClick={goBack}
                                    className="px-4 py-2 rounded-lg text-xs font-semibold border border-border text-foreground hover:bg-input/40 cursor-pointer">
                                    <span className="inline-flex items-center gap-1.5"><ArrowLeft size={12} /> Back</span>
                                </button>
                                <button onClick={handleSaveDraft} disabled={submitting}
                                    className="px-4 py-2 rounded-lg text-xs font-semibold bg-primary text-primary-foreground hover:bg-primary/90 disabled:opacity-50 cursor-pointer">
                                    <span className="inline-flex items-center gap-1.5">
                                        {submitting ? <Loader2 size={12} className="animate-spin" /> : <Check size={12} />} Save draft
                                    </span>
                                </button>
                            </>
                        )}
                        {onLastStep && savedLeaseId && (
                            <>
                                <button
                                    onClick={() => { router.push(`/dashboard/leases/${savedLeaseId}`); onClose(); }}
                                    className="px-4 py-2 rounded-lg text-xs font-semibold border border-border text-foreground hover:bg-input/40 cursor-pointer"
                                >
                                    Open lease detail
                                </button>
                                <button
                                    onClick={() => { router.push(`/dashboard/leases/${savedLeaseId}?action=generate-contract`); onClose(); }}
                                    className="px-4 py-2 rounded-lg text-xs font-semibold bg-primary text-primary-foreground hover:bg-primary/90 cursor-pointer"
                                >
                                    <span className="inline-flex items-center gap-1.5">
                                        <Sparkles size={12} /> Generate contract
                                    </span>
                                </button>
                                <button onClick={onClose}
                                    className="px-4 py-2 rounded-lg text-xs font-semibold border border-border text-muted hover:bg-input/40 cursor-pointer">
                                    Done
                                </button>
                            </>
                        )}
                    </div>
                </div>
            </div>
        </div>
    );
}

// ── Small presentational helpers ────────────────────────────────────────────

function Field({ label, hint, children }: { label: string; hint?: string; children: React.ReactNode }) {
    return (
        <div>
            <label className="block text-[10px] font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">{label}</label>
            {children}
            {hint && <p className="text-[10px] text-muted mt-1 ml-1">{hint}</p>}
        </div>
    );
}

function VatToggle({ label, value, onChange }: { label: string; value: boolean; onChange: (v: boolean) => void }) {
    return (
        <label className="flex items-center justify-between gap-3 px-3 py-2 rounded-lg border border-border bg-input/30 cursor-pointer">
            <span className="text-xs text-foreground">{label}</span>
            <span className="flex items-center gap-2">
                <span className={cn("text-[10px] font-semibold", value ? "text-primary" : "text-muted")}>
                    {value ? "VAT 5%" : "Exempt"}
                </span>
                <input
                    type="checkbox"
                    checked={value}
                    onChange={(e) => onChange(e.target.checked)}
                    className="w-4 h-4"
                />
            </span>
        </label>
    );
}

function Summary({ label, value }: { label: string; value: string }) {
    return (
        <div className="flex items-baseline gap-2">
            <dt className="text-muted shrink-0">{label}:</dt>
            <dd className="text-foreground font-medium truncate" title={value}>{value}</dd>
        </div>
    );
}
