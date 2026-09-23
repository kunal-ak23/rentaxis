"use client";

import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import LeaseDialog from "./LeaseDialog";
import LeaseLinesGrid from "./LeaseLinesGrid";
import ChequeRowsEditor, { blankChequeRow, chequeTotalOf, stripKey, type ChequeDraft } from "@/components/cheques/ChequeRowsEditor";
import { blankLine, linesAreValid, round2, splitLineErrors, toInputs, todayIso, totalsOf, type LineRow } from "./leaseMath";
import { chequeRowsAreValid } from "@/components/cheques/chequeRowRules";
import { ApiError, leaseApi, type AddendumResponse, type ChargeType, type LeaseDetail } from "@/lib/api/leasing";

/**
 * Add a charge to a posted lease mid-term — a parking bay, a storage room — as
 * a numbered addendum. It posts immediately: a further TCO for the new lines
 * only and a PDR per new cheque, so the totals must agree before the button is
 * live. The end date does not move; a rent line runs from the effective date to
 * the lease's end. Ejari may be left blank and recorded later.
 */

const field =
    "w-full bg-input border border-border rounded-lg px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200";
const label = "block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5";

type Props = {
    open: boolean;
    lease: LeaseDetail;
    chargeTypes: ChargeType[];
    onClose: () => void;
    onAdded: (res: AddendumResponse) => void;
};

export default function AddChargeDialog({ open, lease, chargeTypes, onClose, onAdded }: Props) {
    const t = useTranslations("Leasing");
    const [effectiveFrom, setEffectiveFrom] = useState("");
    const [contractDate, setContractDate] = useState(todayIso());
    const [ejariNumber, setEjariNumber] = useState("");
    const [reason, setReason] = useState("");
    const [rows, setRows] = useState<LineRow[]>([]);
    const [cheques, setCheques] = useState<ChequeDraft[]>([]);
    const [busy, setBusy] = useState(false);
    const [errors, setErrors] = useState<string[]>([]);

    useEffect(() => {
        if (!open) return;
        setEffectiveFrom("");
        setContractDate(todayIso());
        setEjariNumber("");
        setReason("");
        setRows([blankLine(0)]);
        setCheques([blankChequeRow(0)]);
        setErrors([]);
    }, [open]);

    const totals = totalsOf(rows, chargeTypes);
    const matches = Math.abs(round2(chequeTotalOf(cheques) - totals.inclVat)) < 0.005;
    const chequeRows = cheques.map(stripKey);
    const inTenancy = !!effectiveFrom && effectiveFrom >= lease.startDate && effectiveFrom <= lease.endDate;
    const { rest } = splitLineErrors(errors);

    const submit = async () => {
        setBusy(true);
        setErrors([]);
        try {
            const res = await leaseApi.addCharge(lease.id, {
                effectiveFrom,
                contractDate: contractDate || null,
                ejariNumber: ejariNumber.trim() || null,
                reason: reason.trim() || null,
                lines: toInputs(rows),
                cheques: chequeRows,
            });
            onAdded(res);
        } catch (e) {
            setErrors(e instanceof ApiError ? [e.message] : [t("addChargeFailed")]);
        } finally {
            setBusy(false);
        }
    };

    return (
        <LeaseDialog
            open={open}
            title={t("addCharge")}
            onClose={onClose}
            onConfirm={submit}
            confirmText={t("addCharge")}
            cancelText={t("cancel")}
            confirmDisabled={!inTenancy || !matches || !linesAreValid(rows) || !chequeRowsAreValid(chequeRows)}
            busy={busy}
            confirmTestId="add-charge-confirm"
            width="xl"
        >
            <div className="space-y-4">
                <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                    <div>
                        <label className={label} htmlFor="add-charge-effective-from">{t("effectiveFrom")}</label>
                        <input id="add-charge-effective-from" data-testid="add-charge-effective-from" type="date"
                               min={lease.startDate} max={lease.endDate} className={field}
                               value={effectiveFrom} onChange={e => setEffectiveFrom(e.target.value)} />
                    </div>
                    <div>
                        <label className={label} htmlFor="add-charge-contract-date">{t("contractDate")}</label>
                        <input id="add-charge-contract-date" type="date" className={field}
                               value={contractDate} onChange={e => setContractDate(e.target.value)} />
                    </div>
                    <div>
                        <label className={label} htmlFor="add-charge-ejari">{t("ejariNumberOptional")}</label>
                        <input id="add-charge-ejari" className={field}
                               value={ejariNumber} onChange={e => setEjariNumber(e.target.value)} />
                    </div>
                    <div>
                        <label className={label} htmlFor="add-charge-reason">{t("addendumReason")}</label>
                        <input id="add-charge-reason" className={field}
                               value={reason} onChange={e => setReason(e.target.value)} />
                    </div>
                </div>

                <section>
                    <h4 className="text-[11px] font-semibold text-muted uppercase tracking-wider mb-2">{t("addendumLines")}</h4>
                    <LeaseLinesGrid lines={rows} chargeTypes={chargeTypes} propertyId={lease.propertyId}
                                    editable onChange={setRows} errors={errors} />
                </section>

                <section>
                    <h4 className="text-[11px] font-semibold text-muted uppercase tracking-wider mb-2">{t("addendumCheques")}</h4>
                    <ChequeRowsEditor rows={cheques} onChange={setCheques} propertyId={lease.propertyId}
                                      expectedTotal={totals.inclVat} testIdPrefix="add-charge" />
                </section>

                {rest.length > 0 && (
                    <ul className="text-[11px] text-error space-y-1" data-testid="add-charge-errors">
                        {rest.map((e, i) => <li key={i}>{e}</li>)}
                    </ul>
                )}
            </div>
        </LeaseDialog>
    );
}
