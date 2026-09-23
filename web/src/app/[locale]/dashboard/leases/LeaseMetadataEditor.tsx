"use client";

import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { AlertTriangle, CheckCircle2, ChevronRight, Loader2, RefreshCw, Save } from "lucide-react";
import { cn } from "@/lib/utils";
import { NumberInput } from "@/components/ui/NumberInput";
import LeaseLinesGrid from "@/components/leases/LeaseLinesGrid";
import { linesAreValid, splitLineErrors, toInputs, toRows, withRentVat, type LineRow } from "@/components/leases/leaseMath";
import {
    ApiError, leaseApi,
    type ChargeType, type DraftLeaseInput, type DraftPaymentMethod,
    type InstallmentDistribution, type LeaseDetail,
} from "@/lib/api/leasing";

/**
 * Editing a draft contract in place: its header fields and its particulars
 * grid, saved together through `PUT /leases/{id}`.
 *
 * Together, not separately — the API takes a whole `CreateLeaseDTO` including
 * `lines`, so a header-only save would have to resend the lines anyway, and
 * two save buttons over one endpoint is how a screen comes to send a stale
 * copy of whichever half the user did not touch.
 *
 * It no longer carries a monthly rent, a deposit or a charges repeater: those
 * three fields became rows of the grid. The wizard that used to collect them
 * and this editor are now the same shape, which is the point — an accountant
 * reading the contract back should see the layout they typed it into.
 *
 * Parties are fixed once the draft exists. Re-pointing a saved contract at a
 * different unit or renter is a new contract, not an edit, and the wizard is
 * where that starts.
 */

const field = "w-full bg-input border border-border p-2.5 rounded-lg text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none";
const labelCls = "block text-[10px] font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ms-1";

type Header = {
    startDate: string;
    endDate: string;
    contractDate: string;
    agreementDate: string;
    gracePeriodDays: number;
    paymentTerms: number;
    firstDueDate: string;
    installmentDistribution: InstallmentDistribution;
    paymentMethod: DraftPaymentMethod;
    depositPaymentMethod: DraftPaymentMethod;
    ejariNumber: string;
    paymentReferenceNumber: string;
    rentVatApplicable: boolean;
};

/** Only the two the draft endpoint admits — see `DraftPaymentMethod`. */
function asDraftMethod(v: string | null | undefined): DraftPaymentMethod {
    return v === "ONLINE" ? "ONLINE" : "CHEQUE";
}

function toHeader(lease: LeaseDetail): Header {
    return {
        startDate: (lease.startDate || "").slice(0, 10),
        endDate: (lease.endDate || "").slice(0, 10),
        contractDate: (lease.contractDate || "").slice(0, 10),
        agreementDate: (lease.agreementDate || "").slice(0, 10),
        gracePeriodDays: lease.gracePeriodDays ?? 0,
        paymentTerms: lease.paymentTerms ?? 1,
        firstDueDate: (lease.firstDueDate || "").slice(0, 10),
        installmentDistribution: lease.installmentDistribution ?? "LAST_LARGER",
        paymentMethod: asDraftMethod(lease.paymentMethod),
        depositPaymentMethod: asDraftMethod(lease.depositPaymentMethod),
        ejariNumber: lease.ejariNumber ?? "",
        paymentReferenceNumber: lease.paymentReferenceNumber ?? "",
        rentVatApplicable: !!lease.rentVatApplicable,
    };
}

type Props = {
    lease: LeaseDetail;
    chargeTypes: ChargeType[];
    /** Called with the saved lease so the page can re-read its cheque grid too. */
    onSaved?: (saved: LeaseDetail) => void;
    className?: string;
};

export default function LeaseMetadataEditor({ lease, chargeTypes, onSaved, className }: Props) {
    const t = useTranslations("Leasing");
    const [header, setHeader] = useState<Header>(() => toHeader(lease));
    const [rows, setRows] = useState<LineRow[]>(() => toRows(lease.lines));
    const [collapsed, setCollapsed] = useState(true);
    const [saving, setSaving] = useState(false);
    const [errors, setErrors] = useState<string[]>([]);
    const [saved, setSaved] = useState(false);

    const editable = lease.status === "DRAFT" || lease.status === "PENDING_SIGNATURE";

    useEffect(() => {
        setHeader(toHeader(lease));
        setRows(toRows(lease.lines));
    }, [lease]);

    const patch = (next: Partial<Header>) => {
        setHeader(prev => ({ ...prev, ...next }));
        setSaved(false);
    };

    // #54: the header's "Rent carries VAT" flag drives the RENT lines' VAT box,
    // as in the wizard — otherwise ticking it here saves a header that says
    // rent is taxed over RENT lines that each still say it is not, and the
    // server honours the lines. A RENT line whose box the operator ticked or
    // unticked by hand in this session keeps that choice (`vatTouched`); lines
    // read back from the server have no such memory and follow the header.
    // Picking a RENT charge in the grid takes the flag via `rentVat`.
    const setRentVat = (rentVatApplicable: boolean) => {
        patch({ rentVatApplicable });
        setRows(prev => withRentVat(prev, chargeTypes, rentVatApplicable));
    };

    const { rest: bannerErrors } = splitLineErrors(errors);

    const handleSave = async () => {
        if (rows.some(r => !r.chargeTypeId)) {
            setErrors([t("errLineNeedsType")]);
            return;
        }
        // The two checks above name the mistake an accountant makes most
        // often; `linesAreValid` — the same gate the wizard and the
        // amend/renew/extend dialogs read — is the backstop that also
        // catches a non-positive amount or a negative discount.
        if (!linesAreValid(rows)) {
            setErrors([t("errDiscountOverAmount")]);
            return;
        }
        setSaving(true);
        setErrors([]);
        setSaved(false);
        try {
            const body: DraftLeaseInput = {
                unitId: lease.unitId,
                renterId: lease.renterId,
                startDate: header.startDate,
                endDate: header.endDate,
                contractDate: header.contractDate || null,
                agreementDate: header.agreementDate || null,
                gracePeriodDays: header.gracePeriodDays,
                paymentTerms: header.paymentTerms,
                firstDueDate: header.firstDueDate || null,
                installmentDistribution: header.installmentDistribution,
                paymentMethod: header.paymentMethod,
                depositPaymentMethod: header.depositPaymentMethod,
                ejariNumber: header.ejariNumber || null,
                paymentReferenceNumber: header.paymentReferenceNumber || null,
                rentVatApplicable: header.rentVatApplicable,
                lines: toInputs(rows, { keepPeriods: false }),
            };
            const updated = await leaseApi.updateDraft(lease.id, body);
            setSaved(true);
            onSaved?.(updated);
        } catch (e) {
            setErrors(e instanceof ApiError ? [e.message] : [t("saveFailed")]);
        } finally {
            setSaving(false);
        }
    };

    if (!editable) return null;

    return (
        <div className={cn("bg-surface rounded-xl border border-border", className)} data-testid="lease-draft-editor">
            <button
                onClick={() => setCollapsed(c => !c)}
                className="w-full flex items-center justify-between px-5 py-3.5 border-b border-border cursor-pointer hover:bg-input/20"
            >
                <div className="flex items-center gap-2">
                    <ChevronRight size={14} className={cn("text-muted transition-transform", !collapsed && "rotate-90")} />
                    <h2 className="text-xs font-semibold text-muted uppercase tracking-wider">{t("editDraft")}</h2>
                </div>
                {saved && collapsed && <CheckCircle2 size={14} className="text-success" />}
            </button>

            {!collapsed && (
                <div className="px-5 py-5 space-y-5">
                    <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
                        <Field label={t("contractDate")}>
                            <input type="date" className={field} value={header.contractDate} onChange={e => patch({ contractDate: e.target.value })} />
                        </Field>
                        <Field label={`${t("startDate")} *`}>
                            <input type="date" data-testid="edit-start-date" className={field} value={header.startDate} onChange={e => patch({ startDate: e.target.value })} />
                        </Field>
                        <Field label={`${t("endDate")} *`}>
                            <input type="date" data-testid="edit-end-date" className={field} value={header.endDate} onChange={e => patch({ endDate: e.target.value })} />
                        </Field>
                        <Field label={t("agreementDate")}>
                            <input type="date" className={field} value={header.agreementDate} onChange={e => patch({ agreementDate: e.target.value })} />
                        </Field>
                        <Field label={t("gracePeriodDays")}>
                            <NumberInput showZero min={0} max={90} className={field} value={header.gracePeriodDays} onChange={v => patch({ gracePeriodDays: v })} />
                        </Field>
                        <Field label={t("paymentTerms")}>
                            <NumberInput showZero min={1} max={36} className={field} value={header.paymentTerms} onChange={v => patch({ paymentTerms: Math.max(1, v) })} />
                        </Field>
                        <Field label={t("firstDueDate")}>
                            <input type="date" className={field} value={header.firstDueDate} onChange={e => patch({ firstDueDate: e.target.value })} />
                        </Field>
                        <Field label={t("distribution")}>
                            <select className={field} value={header.installmentDistribution} onChange={e => patch({ installmentDistribution: e.target.value as InstallmentDistribution })}>
                                <option value="UNIFORM">{t("distributionUniform")}</option>
                                <option value="FIRST_LARGER">{t("distributionFirstLarger")}</option>
                                <option value="LAST_LARGER">{t("distributionLastLarger")}</option>
                                <option value="FIRST_AND_LAST_LARGER">{t("distributionBothLarger")}</option>
                            </select>
                        </Field>
                        <Field label={t("paymentMethod")}>
                            <select className={field} value={header.paymentMethod} onChange={e => patch({ paymentMethod: e.target.value as DraftPaymentMethod })}>
                                <option value="CHEQUE">{t("methodCheque")}</option>
                                <option value="ONLINE">{t("methodOnline")}</option>
                            </select>
                        </Field>
                        <Field label={t("depositPaymentMethod")}>
                            <select className={field} value={header.depositPaymentMethod} onChange={e => patch({ depositPaymentMethod: e.target.value as DraftPaymentMethod })}>
                                <option value="CHEQUE">{t("methodCheque")}</option>
                                <option value="ONLINE">{t("methodOnline")}</option>
                            </select>
                        </Field>
                        <Field label={t("ejariNumber")}>
                            <input className={field} value={header.ejariNumber} onChange={e => patch({ ejariNumber: e.target.value })} />
                        </Field>
                        <Field label={t("paymentReference")}>
                            <input className={field} value={header.paymentReferenceNumber} onChange={e => patch({ paymentReferenceNumber: e.target.value })} />
                        </Field>
                        <label className="flex items-end gap-2 text-xs text-foreground pb-2.5">
                            <input type="checkbox" data-testid="edit-rent-vat" checked={header.rentVatApplicable} onChange={e => setRentVat(e.target.checked)} />
                            {t("rentVat")}
                        </label>
                    </div>

                    <LeaseLinesGrid
                        lines={rows}
                        chargeTypes={chargeTypes}
                        propertyId={lease.propertyId}
                        editable
                        onChange={setRows}
                        errors={errors}
                        rentVat={header.rentVatApplicable}
                    />

                    {/*
                        Saving the lines drops the draft cheque grid server-side —
                        the rows were cut from the old figures and would otherwise
                        collect a total the contract no longer says.
                    */}
                    <p className="rounded-xl bg-warning/10 border border-warning/20 px-4 py-2.5 text-[11px] text-warning flex items-start gap-2">
                        <AlertTriangle size={13} className="shrink-0 mt-0.5" />
                        {t("chequesClearedNotice")}
                    </p>

                    {bannerErrors.length > 0 && (
                        <ul className="text-[11px] text-error space-y-1" data-testid="edit-errors">
                            {bannerErrors.map((e, i) => (
                                <li key={i}>{e}</li>
                            ))}
                        </ul>
                    )}

                    <div className="flex items-center gap-2">
                        <button
                            onClick={handleSave}
                            disabled={saving}
                            data-testid="lease-draft-save"
                            className="inline-flex items-center gap-2 px-4 py-2 rounded-lg text-xs font-semibold bg-primary text-primary-foreground hover:bg-primary/90 disabled:opacity-50 cursor-pointer"
                        >
                            {saving ? <Loader2 size={12} className="animate-spin" /> : <Save size={12} />}
                            {t("saveDraft")}
                        </button>
                        <button
                            onClick={() => {
                                setHeader(toHeader(lease));
                                setRows(toRows(lease.lines));
                                setErrors([]);
                                setSaved(false);
                            }}
                            disabled={saving}
                            className="inline-flex items-center gap-2 px-4 py-2 rounded-lg text-xs font-semibold border border-border text-foreground hover:bg-input/40 cursor-pointer"
                        >
                            <RefreshCw size={12} /> {t("cancel")}
                        </button>
                        {saved && bannerErrors.length === 0 && (
                            <span className="inline-flex items-center gap-1 text-[11px] text-success">
                                <CheckCircle2 size={12} /> {t("saved")}
                            </span>
                        )}
                    </div>
                </div>
            )}
        </div>
    );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
    return (
        <div>
            <label className={labelCls}>{label}</label>
            {children}
        </div>
    );
}
