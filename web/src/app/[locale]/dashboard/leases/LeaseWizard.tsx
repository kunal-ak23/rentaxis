"use client";

import { useState, useEffect, useMemo, useCallback, useRef } from "react";
import { useRouter } from "@/i18n/routing";
import { useTranslations } from "next-intl";
import { ArrowLeft, ArrowRight, X, Check, Loader2, Sparkles, AlertTriangle, Building2, User, Calendar, DollarSign, CreditCard, FileText } from "lucide-react";
import { cn } from "@/lib/utils";
import { formatCurrency, formatDate } from "@/lib/format";
import PaymentScheduleEditor from "./PaymentScheduleEditor";
import ChequeScanner from "@/components/cheques/ChequeScanner";
import BulkChequeUploadFlow from "@/components/cheques/BulkChequeUploadFlow";
import { SearchableSelect } from "@/components/ui/SearchableSelect";
import { useLeasePartyOptions } from "@/hooks/useLeasePartyOptions";
import { NumberInput } from "@/components/ui/NumberInput";

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
type InstallmentDistribution = "UNIFORM" | "FIRST_LARGER" | "LAST_LARGER" | "FIRST_AND_LAST_LARGER";
type ChargeRow = { name: string; amount: number; vatApplicable: boolean; frequency: ChargeFrequency };

type Unit = {
    id: string;
    unitNumber: string;
    status: string;
    /** Asking rent per month, set on the unit. May be absent, null or 0. */
    expectedRent?: number | null;
    property?: { id: string; nameEn?: string; nameAr?: string; type?: string };
};

type Renter = {
    id: string;
    nameEn: string;
    nameAr: string;
    email?: string;
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
    installmentDistribution: InstallmentDistribution;
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
    installmentDistribution: "LAST_LARGER",
};

const STEPS = [
    { key: "parties", labelKey: "stepParties", icon: User },
    { key: "terms", labelKey: "stepTerms", icon: Calendar },
    { key: "charges", labelKey: "stepCharges", icon: DollarSign },
    { key: "plan", labelKey: "stepPaymentPlan", icon: CreditCard },
    { key: "finalize", labelKey: "stepSchedule", icon: FileText },
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
    const t = useTranslations("LeaseWizard");
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

    // Preview state for Payment Plan step
    type PreviewLine = { installmentNumber: number; dueDate: string; amount: number };
    const [previewLines, setPreviewLines] = useState<PreviewLine[] | null>(null);
    const [previewError, setPreviewError] = useState<string | null>(null);
    const [previewLoading, setPreviewLoading] = useState(false);
    const previewReqRef = useRef(0);

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
        setPreviewLines(null);
        setPreviewError(null);
        setPreviewLoading(false);
        previewReqRef.current = 0;
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

    // Letting a unit below its asking rent is allowed — landlords discount for
    // long tenancies, quick occupancy or a difficult unit — so this only warns.
    // Both figures are per month: the wizard's rent field is monthly, and so is
    // Unit.expectedRent.
    const rentShortfall = useMemo(() => {
        const expected = selectedUnit?.expectedRent;
        if (typeof expected !== "number" || !(expected > 0)) return null;
        if (!(data.rentAmount > 0) || data.rentAmount >= expected) return null;
        return { expected, entered: data.rentAmount, gap: expected - data.rentAmount };
    }, [selectedUnit, data.rentAmount]);

    const { unitOptions, renterOptions } = useLeasePartyOptions(units, renters, data.unitId);

    // Debounced live preview — fires when on the "plan" step and all required fields are valid
    useEffect(() => {
        const propertyId = selectedUnit?.property?.id;
        if (
            stepIdx !== 3 ||  // only run on the "plan" step (index 3)
            !propertyId ||
            !data.startDate ||
            !data.endDate ||
            !data.rentAmount || data.rentAmount <= 0 ||
            !data.paymentTerms || data.paymentTerms < 1
        ) {
            setPreviewLines(null);
            setPreviewError(null);
            setPreviewLoading(false);
            return;
        }

        setPreviewLoading(true);
        const reqId = ++previewReqRef.current;

        const timer = setTimeout(async () => {
            const params = new URLSearchParams({
                propertyId,
                startDate: data.startDate,
                endDate: data.endDate,
                monthlyRent: String(data.rentAmount),
                paymentTerms: String(data.paymentTerms),
                depositAmount: String(data.depositAmount || 0),
                strategy: data.installmentDistribution,
            });

            try {
                const res = await fetch(`/api/proxy/v1/payments/preview?${params.toString()}`);
                if (previewReqRef.current !== reqId) return; // stale response — discard

                if (res.ok) {
                    const json = await res.json();
                    setPreviewLines(json.lines ?? []);
                    setPreviewError(null);
                } else {
                    let msg = `Error ${res.status}`;
                    try { const j = await res.json(); msg = j?.error || msg; } catch { /* ignore */ }
                    setPreviewLines(null);
                    setPreviewError(msg);
                }
            } catch {
                if (previewReqRef.current !== reqId) return;
                setPreviewLines(null);
                setPreviewError(t("errPreviewNetwork"));
            } finally {
                if (previewReqRef.current === reqId) setPreviewLoading(false);
            }
        }, 300);

        return () => clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [
        stepIdx,
        selectedUnit?.property?.id,
        data.startDate,
        data.endDate,
        data.rentAmount,
        data.paymentTerms,
        data.depositAmount,
        data.installmentDistribution,
    ]);
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
                if (!data.unitId) return t("errSelectUnit");
                if (!data.renterId) return t("errSelectRenter");
                return null;
            case "terms":
                if (!data.startDate || !data.endDate) return t("errDatesRequired");
                if (new Date(data.endDate) <= new Date(data.startDate)) return t("errEndAfterStart");
                if (!data.rentAmount || data.rentAmount <= 0) return t("errRentPositive");
                if (data.depositAmount < 0) return t("errDepositNonNegative");
                return null;
            case "charges": {
                for (const c of data.charges) {
                    if (!c.name.trim()) return t("errChargeName");
                    if (c.amount < 0) return t("errChargeNonNegative");
                }
                return null;
            }
            case "plan":
                if (!data.paymentTerms || data.paymentTerms < 1) return t("errChequesAtLeastOne");
                if (data.bookingDepositOpen) {
                    if (data.bookingDeposit.amount <= 0) return t("errBookingDepositPositive");
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
                installmentDistribution: data.installmentDistribution,
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
            setError(t("errSaveNetwork"));
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
                            <Sparkles size={14} className="text-primary" /> {t("titlePrefix")} — {t(currentStep.labelKey)}
                        </h2>
                        <p className="text-[11px] text-muted mt-0.5">{t("stepCounter", { current: stepIdx + 1, total: STEPS.length })}</p>
                    </div>
                    <button onClick={onClose} className="p-2 rounded-lg text-muted hover:bg-input hover:text-foreground" aria-label={t("closeWizard")}>
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
                                        <span className="whitespace-nowrap">{i + 1}. {t(s.labelKey)}</span>
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
                            <Field label={t("unitRequired")}>
                                <SearchableSelect
                                    options={unitOptions}
                                    value={data.unitId}
                                    onChange={onPickUnit}
                                    placeholder={t("selectUnitPlaceholder")}
                                    searchPlaceholder={t("searchPropertyOrUnit")}
                                />
                                {selectedUnit && (
                                    <p className="text-[11px] text-muted mt-1.5 flex items-center gap-1">
                                        <Building2 size={11} /> {selectedUnit.property?.nameEn} • {selectedUnit.property?.type || "RESIDENTIAL"}
                                    </p>
                                )}
                            </Field>
                            <Field label={t("renterRequired")}>
                                <SearchableSelect
                                    options={renterOptions}
                                    value={data.renterId}
                                    onChange={(renterId) => update({ renterId })}
                                    placeholder={t("selectRenterPlaceholder")}
                                    searchPlaceholder={t("searchNameOrEmail")}
                                />
                            </Field>
                            <Field label={t("agreementDate")} hint={t("agreementDateHint")}>
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
                            <Field label={t("startDateRequired")}>
                                <input type="date" value={data.startDate} onChange={(e) => update({ startDate: e.target.value })}
                                    className="w-full bg-input border border-border p-3 rounded-xl text-xs" />
                            </Field>
                            <Field label={t("endDateRequired")}>
                                <input type="date" value={data.endDate} onChange={(e) => update({ endDate: e.target.value })}
                                    className="w-full bg-input border border-border p-3 rounded-xl text-xs" />
                            </Field>
                            <Field label={t("monthlyRentRequired")}>
                                <NumberInput min={0} step={0.01} value={data.rentAmount}
                                    onChange={(v) => update({ rentAmount: v })}
                                    className="w-full bg-input border border-border p-3 rounded-xl text-xs" />
                                {rentShortfall && (
                                    <p data-testid="below-expected-rent"
                                        className="mt-1.5 flex items-start gap-1.5 text-[11px] text-warning">
                                        <AlertTriangle size={12} className="mt-px shrink-0" />
                                        <span>
                                            {t("belowExpectedRent", {
                                                expected: formatCurrency(rentShortfall.expected),
                                                entered: formatCurrency(rentShortfall.entered),
                                                gap: formatCurrency(rentShortfall.gap),
                                            })}
                                        </span>
                                    </p>
                                )}
                            </Field>
                            <Field label={t("securityDepositAed")}>
                                <NumberInput min={0} step={0.01} value={data.depositAmount}
                                    onChange={(v) => update({ depositAmount: v })}
                                    className="w-full bg-input border border-border p-3 rounded-xl text-xs" />
                            </Field>
                            <Field label={t("ejariNumber")}>
                                <input type="text" value={data.ejariNumber} onChange={(e) => update({ ejariNumber: e.target.value })}
                                    className="w-full bg-input border border-border p-3 rounded-xl text-xs" />
                            </Field>
                            <Field label={t("paymentReference")}>
                                <input type="text" value={data.paymentReferenceNumber} onChange={(e) => update({ paymentReferenceNumber: e.target.value })}
                                    className="w-full bg-input border border-border p-3 rounded-xl text-xs" />
                            </Field>
                        </div>
                    )}

                    {currentStep.key === "charges" && (
                        <div className="space-y-5">
                            <div className="flex items-center justify-between">
                                <h3 className="text-xs font-semibold">{t("otherCharges")}</h3>
                                <button type="button"
                                    onClick={() => setData((prev) => ({ ...prev, charges: [...prev.charges, { name: "", amount: 0, vatApplicable: isCommercial, frequency: "ONE_TIME" as ChargeFrequency }] }))}
                                    className="rounded border border-border px-2 py-1 text-xs">+ Add charge</button>
                            </div>
                            {data.charges.length === 0 && <p className="text-[11px] text-muted">{t("noExtraCharges")}</p>}
                            {data.charges.map((c, i) => (
                                <div key={i} className="grid grid-cols-1 md:grid-cols-[1fr_120px_120px_110px_32px] gap-2 items-end">
                                    <Field label={t("chargeName")}><input type="text" value={c.name}
                                        onChange={(e) => updateCharge(i, { name: e.target.value })}
                                        className="w-full bg-input border border-border p-2 rounded-lg text-xs" /></Field>
                                    <Field label={t("amountAed")}><NumberInput min={0} step={0.01} value={c.amount}
                                        onChange={(v) => updateCharge(i, { amount: v })}
                                        className="w-full bg-input border border-border p-2 rounded-lg text-xs" /></Field>
                                    <Field label={t("frequency")}>
                                        <select value={c.frequency} onChange={(e) => updateCharge(i, { frequency: e.target.value as ChargeFrequency })}
                                            className="w-full bg-input border border-border p-2 rounded-lg text-xs">
                                            <option value="ONE_TIME">{t("oneTime")}</option>
                                            <option value="PER_INSTALLMENT">{t("perInstallment")}</option>
                                        </select>
                                    </Field>
                                    <VatToggle label={t("vat")} value={c.vatApplicable} onChange={(v) => updateCharge(i, { vatApplicable: v })} />
                                    <button type="button" onClick={() => setData((prev) => ({ ...prev, charges: prev.charges.filter((_, j) => j !== i) }))}
                                        className="rounded border border-border p-2 text-xs">✕</button>
                                </div>
                            ))}
                            <VatToggle label={t("rentVat")} value={data.rentVatApplicable} onChange={(v) => update({ rentVatApplicable: v })} />
                        </div>
                    )}

                    {currentStep.key === "plan" && (
                        <div className="space-y-5">
                            <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-4 gap-4">
                                <Field label={t("installmentsRequired")} hint={t("installmentsHint")}>
                                    <NumberInput min={1} max={36} value={data.paymentTerms}
                                        onChange={(v) => update({ paymentTerms: v })}
                                        className="w-full bg-input border border-border p-3 rounded-xl text-xs" />
                                </Field>
                                <Field label={t("remainderDistribution")} hint={t("remainderHint")}>
                                    <select value={data.installmentDistribution}
                                        onChange={(e) => update({ installmentDistribution: e.target.value as InstallmentDistribution })}
                                        className="w-full bg-input border border-border p-3 rounded-xl text-xs">
                                        <option value="UNIFORM">{t("uniform")}</option>
                                        <option value="FIRST_LARGER">{t("firstLarger")}</option>
                                        <option value="LAST_LARGER">{t("lastLarger")}</option>
                                        <option value="FIRST_AND_LAST_LARGER">{t("bothLarger")}</option>
                                    </select>
                                </Field>
                                <Field label={t("defaultPaymentMethod")}>
                                    <select value={data.paymentMethod} onChange={(e) => update({ paymentMethod: e.target.value })}
                                        className="w-full bg-input border border-border p-3 rounded-xl text-xs">
                                        <option value="CHEQUE">{t("methodCheque")}</option>
                                        <option value="BANK_TRANSFER">{t("methodBankTransfer")}</option>
                                        <option value="ONLINE">{t("methodOnline")}</option>
                                        <option value="CASH">{t("methodCash")}</option>
                                    </select>
                                </Field>
                                <Field label={t("depositPaymentMethod")}>
                                    <select value={data.depositPaymentMethod} onChange={(e) => update({ depositPaymentMethod: e.target.value })}
                                        className="w-full bg-input border border-border p-3 rounded-xl text-xs">
                                        <option value="CHEQUE">{t("methodCheque")}</option>
                                        <option value="BANK_TRANSFER">{t("methodBankTransfer")}</option>
                                        <option value="ONLINE">{t("methodOnline")}</option>
                                        <option value="CASH">{t("methodCash")}</option>
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
                                    {t("includeBookingDeposit")}
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
                                        <Field label={t("amountAedRequired")}>
                                            <NumberInput min={0} step={0.01} value={data.bookingDeposit.amount}
                                                onChange={(v) => update({ bookingDeposit: { ...data.bookingDeposit, amount: v } })}
                                                className="w-full bg-surface border border-border p-3 rounded-xl text-xs" />
                                            {data.bookingDeposit.scannedAmount != null && data.bookingDeposit.scannedAmount !== data.bookingDeposit.amount && (
                                                <button type="button"
                                                    onClick={() => update({ bookingDeposit: { ...data.bookingDeposit, amount: data.bookingDeposit.scannedAmount! } })}
                                                    className="mt-1 inline-flex items-center gap-1 rounded-full border border-primary/40 bg-primary/10 px-2 py-0.5 text-[11px] text-primary">
                                                    From cheque: AED {data.bookingDeposit.scannedAmount} · Apply
                                                </button>
                                            )}
                                        </Field>
                                        <Field label={t("chequeNumber")}>
                                            <input type="text" value={data.bookingDeposit.chequeNumber}
                                                onChange={(e) => update({ bookingDeposit: { ...data.bookingDeposit, chequeNumber: e.target.value } })}
                                                className="w-full bg-surface border border-border p-3 rounded-xl text-xs" />
                                        </Field>
                                        <Field label={t("chequeDate")}>
                                            <input type="date" value={data.bookingDeposit.chequeDate}
                                                onChange={(e) => update({ bookingDeposit: { ...data.bookingDeposit, chequeDate: e.target.value } })}
                                                className="w-full bg-surface border border-border p-3 rounded-xl text-xs" />
                                        </Field>
                                        <Field label={t("bank")}>
                                            <input type="text" value={data.bookingDeposit.bankName}
                                                onChange={(e) => update({ bookingDeposit: { ...data.bookingDeposit, bankName: e.target.value } })}
                                                className="w-full bg-surface border border-border p-3 rounded-xl text-xs" />
                                        </Field>
                                    </div>
                                )}
                            </div>

                            {/* Live installment preview */}
                            <div className="border border-border rounded-xl p-4 bg-input/30">
                                <h3 className="text-xs font-semibold text-foreground mb-3">{t("installmentPreview")}</h3>
                                {previewLoading && (
                                    <div className="flex items-center gap-2 text-[11px] text-muted py-2">
                                        <Loader2 size={12} className="animate-spin" /> Calculating…
                                    </div>
                                )}
                                {!previewLoading && previewError && (
                                    <p className="text-[11px] text-error">{previewError}</p>
                                )}
                                {!previewLoading && !previewError && !previewLines && (
                                    <p className="text-[11px] text-muted">Fill in dates, monthly rent, and installment count above to see a preview.</p>
                                )}
                                {!previewLoading && !previewError && previewLines && (() => {
                                    const perInstallmentChargeTotal = data.charges
                                        .filter((c) => c.frequency === "PER_INSTALLMENT")
                                        .reduce((sum, c) => sum + c.amount * (c.vatApplicable ? 1.05 : 1), 0);
                                    return (
                                        <table className="w-full text-[11px]">
                                            <thead>
                                                <tr className="text-muted border-b border-border">
                                                    <th className="text-left py-1 pr-3 font-semibold">#</th>
                                                    <th className="text-left py-1 pr-3 font-semibold">{t("dueDate")}</th>
                                                    <th className="text-right py-1 font-semibold">{t("amount")}</th>
                                                </tr>
                                            </thead>
                                            <tbody>
                                                {previewLines.map((line) => (
                                                    <tr key={line.installmentNumber} className="border-b border-border/50">
                                                        <td className="py-1 pr-3 text-muted">{line.installmentNumber}</td>
                                                        <td className="py-1 pr-3">{formatDate(line.dueDate)}</td>
                                                        <td className="py-1 text-right font-medium">{formatCurrency(line.amount + perInstallmentChargeTotal)}</td>
                                                    </tr>
                                                ))}
                                                {data.charges.filter((c) => c.frequency === "ONE_TIME").map((c, i) => (
                                                    <tr key={`ot-${i}`} className="border-b border-border/50 text-muted">
                                                        <td className="py-1 pr-3" colSpan={2}>{c.name || t("oneTimeCharge")}</td>
                                                        <td className="py-1 text-right">{formatCurrency(c.amount * (c.vatApplicable ? 1.05 : 1))}</td>
                                                    </tr>
                                                ))}
                                                {data.depositAmount > 0 && (
                                                    <tr className="text-muted">
                                                        <td className="py-1 pr-3" colSpan={2}>{t("securityDeposit")}</td>
                                                        <td className="py-1 text-right">{formatCurrency(data.depositAmount)}</td>
                                                    </tr>
                                                )}
                                            </tbody>
                                        </table>
                                    );
                                })()}
                            </div>
                        </div>
                    )}

                    {currentStep.key === "finalize" && (
                        <div className="space-y-5">
                            {!savedLeaseId ? (
                                <>
                                    <div className="rounded-xl border border-border bg-input/30 p-4">
                                        <h3 className="text-xs font-semibold text-foreground mb-3">{t("reviewDraft")}</h3>
                                        <dl className="grid grid-cols-1 md:grid-cols-2 gap-x-6 gap-y-2 text-[11px]">
                                            <Summary label={t("unit")} value={selectedUnit ? `${selectedUnit.unitNumber} • ${selectedUnit.property?.nameEn}` : "—"} />
                                            <Summary label={t("renter")} value={selectedRenter?.nameEn || "—"} />
                                            <Summary label={t("period")} value={`${data.startDate || "—"} → ${data.endDate || "—"}`} />
                                            <Summary label={t("monthlyRent")} value={formatCurrency(data.rentAmount)} />
                                            <Summary label={t("deposit")} value={formatCurrency(data.depositAmount)} />
                                            <Summary label={t("otherCharges")} value={data.charges.length > 0 ? data.charges.map((c) => `${c.name} (${c.frequency === "ONE_TIME" ? t("oneTime") : t("perInstallment")})`).join(", ") : t("none")} />
                                            <Summary label={t("installments")} value={`${data.paymentTerms} × ${data.paymentMethod}`} />
                                            <Summary label={t("rentVat")} value={data.rentVatApplicable ? t("vatFivePercent") : t("exempt")} />
                                            {data.bookingDepositOpen && (
                                                <Summary label={t("bookingDeposit")} value={`${formatCurrency(data.bookingDeposit.amount)} • ${data.bookingDeposit.bankName || "—"}`} />
                                            )}
                                        </dl>
                                    </div>
                                    <div className="text-[11px] text-muted">
                                        {t("saveHint")}
                                    </div>
                                </>
                            ) : (
                                <>
                                    <div className="rounded-xl bg-success/10 border border-success/30 p-3 text-[11px] text-success">
                                        {/* t.rich keeps the sentence whole for translators rather than
                                            splitting it around the bold run, which does not reorder
                                            cleanly into Arabic. */}
                                        {t.rich("draftCreatedBanner", {
                                            b: (chunks) => <strong>{chunks}</strong>,
                                        })}
                                    </div>
                                    <div className="flex items-center justify-end">
                                        <button
                                            type="button"
                                            onClick={async () => { await loadWizardSchedules(savedLeaseId); setBulkOpen(true); }}
                                            className="rounded border border-border px-3 py-1 text-xs"
                                        >
                                            {t("bulkUploadCheques")}
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
                                                isCharge: p.isCharge,
                                                isSecurityDeposit: p.isSecurityDeposit,
                                                isBookingDeposit: p.isBookingDeposit,
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
                                    <span className="inline-flex items-center gap-1.5"><ArrowLeft size={12} /> {t("back")}</span>
                                </button>
                                <button onClick={goNext}
                                    className="px-4 py-2 rounded-lg text-xs font-semibold bg-primary text-primary-foreground hover:bg-primary/90 cursor-pointer">
                                    <span className="inline-flex items-center gap-1.5">{t("next")} <ArrowRight size={12} /></span>
                                </button>
                            </>
                        )}
                        {onLastStep && !savedLeaseId && (
                            <>
                                <button onClick={goBack}
                                    className="px-4 py-2 rounded-lg text-xs font-semibold border border-border text-foreground hover:bg-input/40 cursor-pointer">
                                    <span className="inline-flex items-center gap-1.5"><ArrowLeft size={12} /> {t("back")}</span>
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
                                    {t("openLeaseDetail")}
                                </button>
                                <button
                                    onClick={() => { router.push(`/dashboard/leases/${savedLeaseId}?action=generate-contract`); onClose(); }}
                                    className="px-4 py-2 rounded-lg text-xs font-semibold bg-primary text-primary-foreground hover:bg-primary/90 cursor-pointer"
                                >
                                    <span className="inline-flex items-center gap-1.5">
                                        <Sparkles size={12} /> {t("generateContract")}
                                    </span>
                                </button>
                                <button onClick={onClose}
                                    className="px-4 py-2 rounded-lg text-xs font-semibold border border-border text-muted hover:bg-input/40 cursor-pointer">
                                    {t("done")}
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
