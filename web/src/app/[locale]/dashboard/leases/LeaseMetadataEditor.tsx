"use client";

import { useEffect, useState, useMemo } from "react";
import { Loader2, Save, RefreshCw, AlertTriangle, CheckCircle2, ChevronRight } from "lucide-react";
import { cn } from "@/lib/utils";

/**
 * Inline metadata editor for a DRAFT lease — replaces the modal "Edit Lease"
 * form that used to live on the leases list page. Sends PUT /api/v1/leases/{id}
 * with the same shape the create endpoint accepts (CreateLeaseDTO), which the
 * backend's updateDraftLease handles. Editing a non-DRAFT lease is rejected by
 * the backend; the editor only mounts for DRAFT.
 *
 * Field parity with the previous inline form:
 *   - unitId, renterId
 *   - startDate, endDate
 *   - rentAmount (monthly) + rentVatApplicable
 *   - depositAmount
 *   - ejariNumber, paymentReferenceNumber
 *   - paymentMethod, paymentTerms, depositPaymentMethod
 *   - agreementDate
 *   - charges (dynamic repeater: name / amount / frequency / VAT)
 *   - bookingDeposit (collapsible add-form for leases without one yet)
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

type Lease = {
    id: string;
    unitId: string;
    renterId: string;
    startDate: string;
    endDate: string;
    rentAmount?: number | null;
    monthlyRent?: number | null;
    depositAmount?: number | null;
    ejariNumber?: string | null;
    paymentTerms?: number | null;
    paymentMethod?: string | null;
    depositPaymentMethod?: string | null;
    paymentReferenceNumber?: string | null;
    agreementDate?: string | null;
    rentVatApplicable?: boolean | null;
    charges?: ChargeRow[] | null;
    status: string;
    hasBookingDeposit?: boolean;
};

type Props = {
    lease: Lease;
    onSaved?: () => void;
    className?: string;
};

type FormShape = {
    unitId: string;
    renterId: string;
    startDate: string;
    endDate: string;
    rentAmount: number;          // monthly
    depositAmount: number;
    ejariNumber: string;
    paymentTerms: number;
    paymentMethod: string;
    depositPaymentMethod: string;
    paymentReferenceNumber: string;
    agreementDate: string;
    rentVatApplicable: boolean;
    charges: ChargeRow[];
};

type BookingDepositShape = {
    amount: number;
    chequeNumber: string;
    chequeDate: string;
    bankName: string;
};

const METHOD_OPTIONS = [
    { value: "CHEQUE", label: "Cheque" },
    { value: "BANK_TRANSFER", label: "Bank Transfer" },
    { value: "ONLINE", label: "Online Payment" },
    { value: "CASH", label: "Cash" },
];

function leaseToForm(lease: Lease): FormShape {
    return {
        unitId: lease.unitId,
        renterId: lease.renterId,
        startDate: (lease.startDate || "").substring(0, 10),
        endDate: (lease.endDate || "").substring(0, 10),
        rentAmount: lease.monthlyRent ?? lease.rentAmount ?? 0,
        depositAmount: lease.depositAmount ?? 0,
        ejariNumber: lease.ejariNumber ?? "",
        paymentTerms: lease.paymentTerms ?? 1,
        paymentMethod: lease.paymentMethod ?? "CHEQUE",
        depositPaymentMethod: lease.depositPaymentMethod ?? "CHEQUE",
        paymentReferenceNumber: lease.paymentReferenceNumber ?? "",
        agreementDate: (lease.agreementDate ?? "").substring(0, 10),
        rentVatApplicable: !!lease.rentVatApplicable,
        charges: lease.charges ?? [],
    };
}

export default function LeaseMetadataEditor({ lease, onSaved, className }: Props) {
    const [units, setUnits] = useState<Unit[]>([]);
    const [renters, setRenters] = useState<Renter[]>([]);
    const [form, setForm] = useState<FormShape>(() => leaseToForm(lease));
    const [bookingOpen, setBookingOpen] = useState(false);
    const [bookingDeposit, setBookingDeposit] = useState<BookingDepositShape>({ amount: 0, chequeNumber: "", chequeDate: "", bankName: "" });
    const [hasExistingBookingDeposit, setHasExistingBookingDeposit] = useState(false);
    const [collapsed, setCollapsed] = useState(true);
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [saved, setSaved] = useState(false);

    const editable = lease.status === "DRAFT";

    useEffect(() => {
        // Reload units + renters whenever the editor mounts. Same endpoints as
        // the leases list page's fetchUnits / fetchRenters.
        let cancelled = false;
        (async () => {
            try {
                const [uRes, rRes, sRes] = await Promise.all([
                    fetch("/api/proxy/v1/units"),
                    fetch("/api/proxy/v1/renters"),
                    // Lease-scoped (full, unpaginated) list — the tenant-wide
                    // `/payments?leaseId=` is paginated and ignores leaseId, so the
                    // booking-deposit row could be off-page and missed here.
                    fetch(`/api/proxy/v1/payments/lease/${lease.id}`),
                ]);
                if (cancelled) return;
                if (uRes.ok) setUnits(await uRes.json());
                if (rRes.ok) setRenters(await rRes.json());
                if (sRes.ok) {
                    const list = await sRes.json();
                    const rows = Array.isArray(list) ? list : (list.content ?? []);
                    setHasExistingBookingDeposit(rows.some((p: { leaseId: string; isBookingDeposit?: boolean }) =>
                        p.leaseId === lease.id && p.isBookingDeposit === true));
                }
            } catch (e) {
                console.error(e);
            }
        })();
        return () => { cancelled = true; };
    }, [lease.id]);

    useEffect(() => {
        // Re-seed the form whenever the lease prop changes (e.g. after parent
        // re-fetches following a save).
        setForm(leaseToForm(lease));
    }, [lease]);

    const selectedUnit = useMemo(() => units.find(u => u.id === form.unitId), [units, form.unitId]);

    const onPickUnit = (unitId: string) => {
        const u = units.find((x) => x.id === unitId);
        const commercial = u?.property?.type === "COMMERCIAL";
        // Carry over commercial-VAT default for rent when the unit changes.
        setForm((prev) => ({
            ...prev,
            unitId,
            rentVatApplicable: commercial,
        }));
    };

    const updateCharge = (i: number, patch: Partial<ChargeRow>) =>
        setForm((prev) => ({ ...prev, charges: prev.charges.map((c, j) => (j === i ? { ...c, ...patch } : c)) }));

    const handleSave = async () => {
        if (!editable) return;
        setSaving(true);
        setError(null);
        setSaved(false);
        try {
            // Compute total rent across the tenure for the rentAmount field
            // (backend keeps both monthlyRent and rentAmount in sync).
            const monthsBetween = (() => {
                const s = new Date(form.startDate);
                const e = new Date(form.endDate);
                // End date is the inclusive last day of tenancy, so +1: Jun→Dec = 7,
                // Jan→Dec = 12. Matches backend DateMath.monthsInclusive.
                const months = (e.getFullYear() - s.getFullYear()) * 12 + (e.getMonth() - s.getMonth()) + 1;
                return Math.max(months, 1);
            })();

            const body: Record<string, unknown> = {
                unitId: form.unitId,
                renterId: form.renterId,
                startDate: form.startDate,
                endDate: form.endDate,
                rentAmount: form.rentAmount * monthsBetween,
                monthlyRent: form.rentAmount,
                depositAmount: form.depositAmount,
                ejariNumber: form.ejariNumber || null,
                paymentTerms: form.paymentTerms,
                paymentMethod: form.paymentMethod,
                depositPaymentMethod: form.depositPaymentMethod,
                paymentReferenceNumber: form.paymentReferenceNumber || null,
                agreementDate: form.agreementDate || null,
                rentVatApplicable: form.rentVatApplicable,
                charges: form.charges,
            };
            if (bookingOpen && bookingDeposit.amount > 0) {
                body.bookingDeposit = {
                    amount: bookingDeposit.amount,
                    chequeNumber: bookingDeposit.chequeNumber || null,
                    chequeDate: bookingDeposit.chequeDate || null,
                    bankName: bookingDeposit.bankName || null,
                };
            }
            const res = await fetch(`/api/proxy/v1/leases/${lease.id}`, {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(body),
            });
            if (res.ok) {
                setSaved(true);
                setBookingOpen(false);
                setBookingDeposit({ amount: 0, chequeNumber: "", chequeDate: "", bankName: "" });
                if (onSaved) onSaved();
            } else if (res.status === 403) {
                setError("You don't have permission to edit this lease.");
            } else {
                let detail: string | null = null;
                try { const j = await res.json(); detail = j?.message || j?.error || null; } catch { /* ignore */ }
                setError(detail || `Failed to save lease (HTTP ${res.status})`);
            }
        } catch (e) {
            console.error(e);
            setError("Network error while saving lease");
        } finally {
            setSaving(false);
        }
    };

    if (!editable) {
        return null;  // Non-DRAFT leases — no editor mounted.
    }

    return (
        <div className={cn("bg-surface rounded-xl border border-border", className)}>
            <button
                onClick={() => setCollapsed((c) => !c)}
                className="w-full flex items-center justify-between px-5 py-3.5 border-b border-border cursor-pointer hover:bg-input/20"
            >
                <div className="flex items-center gap-2">
                    <ChevronRight size={14} className={cn("text-muted transition-transform", !collapsed && "rotate-90")} />
                    <h2 className="text-xs font-semibold text-muted uppercase tracking-wider">Edit lease details</h2>
                    <span className="ml-2 px-2 py-0.5 rounded-full text-[9px] font-semibold bg-amber-50 text-amber-700 border border-amber-200">
                        Editable while DRAFT
                    </span>
                </div>
                {saved && !collapsed && <CheckCircle2 size={14} className="text-success" />}
            </button>
            {!collapsed && (
                <div className="px-5 py-5 space-y-5">
                    <div className="rounded-xl bg-amber-50 border border-amber-200 px-4 py-3 text-[11px] text-amber-800 flex items-start gap-2">
                        <AlertTriangle size={14} className="shrink-0 mt-0.5" />
                        <div>
                            <strong>Heads up:</strong> Saving changes here will <strong>regenerate the payment schedule</strong>.
                            Any pending installment edits below (cheque numbers, dates, banks, custom amounts) will be replaced
                            with freshly-distributed values. Already-collected rows and any booking deposit you've added stay untouched.
                        </div>
                    </div>
                    <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                        <Field label="Unit *">
                            <select required value={form.unitId} onChange={(e) => onPickUnit(e.target.value)} className="w-full bg-input border border-border p-3 rounded-xl text-xs">
                                <option value="">— Select a unit —</option>
                                {units.filter(u => u.status === "VACANT" || u.id === form.unitId).map(u => (
                                    <option key={u.id} value={u.id}>{u.property?.nameEn ? `${u.property.nameEn} — ` : ""}Unit {u.unitNumber} {u.property?.type === "COMMERCIAL" ? "[Commercial]" : ""}</option>
                                ))}
                            </select>
                            {selectedUnit?.property && (
                                <p className="text-[11px] text-muted mt-1">{selectedUnit.property.nameEn} • {selectedUnit.property.type || "RESIDENTIAL"}</p>
                            )}
                        </Field>
                        <Field label="Renter *">
                            <select required value={form.renterId} onChange={(e) => setForm({ ...form, renterId: e.target.value })} className="w-full bg-input border border-border p-3 rounded-xl text-xs">
                                <option value="">— Select a renter —</option>
                                {renters.map(r => <option key={r.id} value={r.id}>{r.nameEn}{r.nameAr ? ` (${r.nameAr})` : ""}</option>)}
                            </select>
                        </Field>
                        <Field label="Start date *">
                            <input required type="date" value={form.startDate} onChange={(e) => setForm({ ...form, startDate: e.target.value })} className="w-full bg-input border border-border p-3 rounded-xl text-xs" />
                        </Field>
                        <Field label="End date *">
                            <input required type="date" value={form.endDate} onChange={(e) => setForm({ ...form, endDate: e.target.value })} className="w-full bg-input border border-border p-3 rounded-xl text-xs" />
                        </Field>
                        <Field label="Monthly rent (AED) *">
                            <div className="flex items-center gap-2">
                                <input required type="number" min={0} step={0.01} value={form.rentAmount} onChange={(e) => setForm({ ...form, rentAmount: Number(e.target.value) })} className="flex-1 bg-input border border-border p-3 rounded-xl text-xs" />
                                <VatToggle value={form.rentVatApplicable} onChange={(v) => setForm({ ...form, rentVatApplicable: v })} />
                            </div>
                        </Field>
                        <Field label="Security deposit (AED)">
                            <input type="number" min={0} step={0.01} value={form.depositAmount} onChange={(e) => setForm({ ...form, depositAmount: Number(e.target.value) })} className="w-full bg-input border border-border p-3 rounded-xl text-xs" />
                        </Field>
                        <Field label="Ejari #">
                            <input value={form.ejariNumber} onChange={(e) => setForm({ ...form, ejariNumber: e.target.value })} className="w-full bg-input border border-border p-3 rounded-xl text-xs" placeholder="EJAR-12345" />
                        </Field>
                        <Field label="Payment reference #">
                            <input value={form.paymentReferenceNumber} onChange={(e) => setForm({ ...form, paymentReferenceNumber: e.target.value })} className="w-full bg-input border border-border p-3 rounded-xl text-xs" placeholder="REF-12345" />
                        </Field>
                        <Field label="Default payment method">
                            <select value={form.paymentMethod} onChange={(e) => setForm({ ...form, paymentMethod: e.target.value })} className="w-full bg-input border border-border p-3 rounded-xl text-xs">
                                {METHOD_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
                            </select>
                        </Field>
                        <Field label="Number of installments *" hint="Splits the total rent equally across this many payments">
                            <input type="number" min={1} max={36} value={form.paymentTerms || 1} onChange={(e) => setForm({ ...form, paymentTerms: Math.max(1, Number(e.target.value) || 1) })} className="w-full bg-input border border-border p-3 rounded-xl text-xs" />
                        </Field>
                        <Field label="Deposit payment method">
                            <select value={form.depositPaymentMethod} onChange={(e) => setForm({ ...form, depositPaymentMethod: e.target.value })} className="w-full bg-input border border-border p-3 rounded-xl text-xs">
                                {METHOD_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
                            </select>
                        </Field>
                        <Field label="Agreement date">
                            <input type="date" value={form.agreementDate} onChange={(e) => setForm({ ...form, agreementDate: e.target.value })} className="w-full bg-input border border-border p-3 rounded-xl text-xs" />
                        </Field>
                    </div>

                    <div className="space-y-3">
                        <div className="flex items-center justify-between">
                            <h3 className="text-xs font-semibold">Other charges</h3>
                            <button type="button"
                                onClick={() => setForm((prev) => ({ ...prev, charges: [...prev.charges, { name: "", amount: 0, vatApplicable: selectedUnit?.property?.type === "COMMERCIAL", frequency: "ONE_TIME" as ChargeFrequency }] }))}
                                className="rounded border border-border px-2 py-1 text-xs">+ Add charge</button>
                        </div>
                        {form.charges.length === 0 && <p className="text-[11px] text-muted">No extra charges. Add admin fee, parking, maintenance, etc.</p>}
                        {form.charges.map((c, i) => (
                            <div key={i} className="grid grid-cols-1 md:grid-cols-[1fr_120px_120px_auto_32px] gap-2 items-end">
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
                                <VatToggle value={c.vatApplicable} onChange={(v) => updateCharge(i, { vatApplicable: v })} />
                                <button type="button" onClick={() => setForm((prev) => ({ ...prev, charges: prev.charges.filter((_, j) => j !== i) }))}
                                    className="rounded border border-border p-2 text-xs">✕</button>
                            </div>
                        ))}
                    </div>

                    {hasExistingBookingDeposit ? (
                        <div className="border border-border rounded-xl p-4 bg-input/20 text-[11px] text-muted">
                            <strong className="text-foreground">Booking deposit:</strong> already recorded. Edit its
                            cheque number, date, bank, and amount in the <em>Payment Schedule</em> table below
                            (the row tagged <span className="px-1 py-0.5 rounded bg-input text-foreground font-mono">B</span>).
                        </div>
                    ) : (
                        <div className="border border-border rounded-xl p-4 bg-input/20">
                            <button
                                type="button"
                                onClick={() => setBookingOpen((o) => !o)}
                                className="flex items-center gap-2 text-[11px] font-semibold text-muted uppercase tracking-wider hover:text-foreground cursor-pointer"
                            >
                                <ChevronRight size={11} className={cn("transition-transform", bookingOpen && "rotate-90")} />
                                Add booking deposit
                                {bookingDeposit.amount > 0 && <span className="text-primary font-bold ml-1">({bookingDeposit.amount} AED)</span>}
                            </button>
                            {bookingOpen && (
                                <div className="grid grid-cols-1 md:grid-cols-2 gap-3 mt-3">
                                    <Field label="Amount (AED)">
                                        <input type="number" min={0} step={0.01} value={bookingDeposit.amount || ""} onChange={(e) => setBookingDeposit((b) => ({ ...b, amount: Number(e.target.value) }))} className="w-full bg-surface border border-border p-2.5 rounded-lg text-xs" />
                                    </Field>
                                    <Field label="Cheque number">
                                        <input value={bookingDeposit.chequeNumber} onChange={(e) => setBookingDeposit((b) => ({ ...b, chequeNumber: e.target.value }))} className="w-full bg-surface border border-border p-2.5 rounded-lg text-xs" />
                                    </Field>
                                    <Field label="Cheque date">
                                        <input type="date" value={bookingDeposit.chequeDate} onChange={(e) => setBookingDeposit((b) => ({ ...b, chequeDate: e.target.value }))} className="w-full bg-surface border border-border p-2.5 rounded-lg text-xs" />
                                    </Field>
                                    <Field label="Bank">
                                        <input value={bookingDeposit.bankName} onChange={(e) => setBookingDeposit((b) => ({ ...b, bankName: e.target.value }))} className="w-full bg-surface border border-border p-2.5 rounded-lg text-xs" />
                                    </Field>
                                </div>
                            )}
                        </div>
                    )}

                    <div className="flex items-center gap-2">
                        <button
                            onClick={handleSave}
                            disabled={saving}
                            className="inline-flex items-center gap-2 px-4 py-2 rounded-lg text-xs font-semibold bg-primary text-primary-foreground hover:bg-primary/90 disabled:opacity-50 cursor-pointer"
                        >
                            {saving ? <Loader2 size={12} className="animate-spin" /> : <Save size={12} />}
                            Save changes
                        </button>
                        <button
                            onClick={() => setForm(leaseToForm(lease))}
                            disabled={saving}
                            className="inline-flex items-center gap-2 px-4 py-2 rounded-lg text-xs font-semibold border border-border text-foreground hover:bg-input/40 cursor-pointer"
                        >
                            <RefreshCw size={12} /> Reset
                        </button>
                        {error && <span className="inline-flex items-center gap-1 text-[11px] text-error"><AlertTriangle size={12} /> {error}</span>}
                        {saved && !error && <span className="inline-flex items-center gap-1 text-[11px] text-success"><CheckCircle2 size={12} /> Saved</span>}
                    </div>
                </div>
            )}
        </div>
    );
}

function Field({ label, hint, children }: { label: string; hint?: string; children: React.ReactNode }) {
    return (
        <div>
            <label className="block text-[10px] font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">{label}</label>
            {children}
            {hint && <p className="text-[10px] text-muted mt-1 ml-1">{hint}</p>}
        </div>
    );
}

function VatToggle({ value, onChange }: { value: boolean; onChange: (v: boolean) => void }) {
    return (
        <label className="flex items-center gap-1.5 cursor-pointer shrink-0">
            <input type="checkbox" className="rounded" checked={value} onChange={(e) => onChange(e.target.checked)} />
            <span className="text-[10px] font-semibold text-muted whitespace-nowrap">VAT</span>
        </label>
    );
}
