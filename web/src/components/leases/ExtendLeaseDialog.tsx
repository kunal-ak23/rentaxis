"use client";

import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { AlertTriangle } from "lucide-react";
import LeaseDialog from "./LeaseDialog";
import LeaseLinesGrid from "./LeaseLinesGrid";
import ChequeRowsEditor, { blankChequeRow, chequeTotalOf, stripKey, type ChequeDraft } from "@/components/cheques/ChequeRowsEditor";
import { blankLine, linesAreValid, round2, splitLineErrors, toInputs, todayIso, totalsOf, type LineRow } from "./leaseMath";
import { chequeRowsAreValid } from "@/components/cheques/chequeRowRules";
import { ApiError, leaseApi, type ChargeType, type LeaseDetail, type PostLeaseResponse } from "@/lib/api/leasing";
import { formatDate } from "@/lib/format";
import { serverText } from "@/components/finance/bankrec/serverText";
import { CONFIRM_TERM_YEARS, MAX_TERM_YEARS, termExceedsYears, termYears } from "@/lib/leaseTerm";

/**
 * Push the end date out and charge for the extra months.
 *
 * Unlike a renewal this posts immediately — a further TCO for the new lines
 * only, plus a PDR per new cheque — which is why the two totals have to agree
 * before the button is live. The backend refuses a mismatch; catching it here
 * saves the accountant a round trip, and the figure shown is the same
 * VAT-inclusive one the server compares.
 */

const field =
    "w-full bg-input border border-border rounded-lg px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200";
const label = "block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5";

type Props = {
    open: boolean;
    lease: LeaseDetail;
    chargeTypes: ChargeType[];
    onClose: () => void;
    onExtended: (res: PostLeaseResponse) => void;
};

export default function ExtendLeaseDialog({ open, lease, chargeTypes, onClose, onExtended }: Props) {
    const t = useTranslations("Leasing");
    const tCommon = useTranslations("Common");
    const [newEndDate, setNewEndDate] = useState("");
    const [contractDate, setContractDate] = useState(todayIso());
    const [rows, setRows] = useState<LineRow[]>([]);
    const [cheques, setCheques] = useState<ChequeDraft[]>([]);
    const [busy, setBusy] = useState(false);
    const [errors, setErrors] = useState<string[]>([]);
    /**
     * forms2 Finding 2: same guard as `RenewLeaseDialog`, checked against the
     * SAME two dates the backend uses — the lease's original start, not the
     * extension's own span (`LeaseRenewalService.requireSaneTerm(lease.getStartDate(),
     * r.newEndDate())`).
     */
    const [termAck, setTermAck] = useState<string | null>(null);

    useEffect(() => {
        if (!open) return;
        setNewEndDate("");
        setContractDate(todayIso());
        setRows([blankLine(0)]);
        setCheques([blankChequeRow(0)]);
        setErrors([]);
        setTermAck(null);
    }, [open]);

    const totals = totalsOf(rows, chargeTypes);
    const matches = Math.abs(round2(chequeTotalOf(cheques) - totals.inclVat)) < 0.005;
    const chequeRows = cheques.map(stripKey);
    const { rest } = splitLineErrors(errors);
    const endDateNotAfterCurrent = !!newEndDate && newEndDate <= lease.endDate;

    const termTooLong = !!newEndDate && !endDateNotAfterCurrent
        && termExceedsYears(lease.startDate, newEndDate, MAX_TERM_YEARS);
    const termKey = `${lease.startDate}|${newEndDate}`;
    const termNeedsConfirm = !termTooLong && !!newEndDate && !endDateNotAfterCurrent
        && termExceedsYears(lease.startDate, newEndDate, CONFIRM_TERM_YEARS) && termAck !== termKey;

    const submit = async () => {
        setBusy(true);
        setErrors([]);
        try {
            const res = await leaseApi.extend(lease.id, {
                newEndDate,
                contractDate: contractDate || null,
                lines: toInputs(rows),
                cheques: chequeRows,
            });
            onExtended(res);
        } catch (e) {
            // Coded refusals (e.g. lease.extendIntoLock, break-it R4 money4 F5) in the user's language.
            setErrors(e instanceof ApiError ? [serverText(tCommon, e) || e.message] : [t("extendFailed")]);
        } finally {
            setBusy(false);
        }
    };

    return (
        <LeaseDialog
            open={open}
            title={t("extend")}
            onClose={onClose}
            onConfirm={submit}
            confirmText={t("extend")}
            cancelText={t("cancel")}
            confirmDisabled={
                !newEndDate ||
                newEndDate <= lease.endDate ||
                !matches ||
                !linesAreValid(rows) ||
                !chequeRowsAreValid(chequeRows) ||
                termTooLong ||
                termNeedsConfirm
            }
            busy={busy}
            confirmTestId="extend-lease-confirm"
            width="xl"
        >
            <div className="space-y-4">
                <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
                    <div>
                        <label className={label} htmlFor="extend-current-end">{t("currentEndDate")}</label>
                        <input id="extend-current-end" readOnly className={`${field} opacity-70`} value={formatDate(lease.endDate)} />
                    </div>
                    <div>
                        <label className={label} htmlFor="extend-new-end">{t("newEndDate")}</label>
                        <input
                            id="extend-new-end"
                            data-testid="extend-new-end-date"
                            type="date"
                            min={lease.endDate}
                            className={field}
                            value={newEndDate}
                            onChange={e => setNewEndDate(e.target.value)}
                        />
                        {endDateNotAfterCurrent && (
                            <p className="mt-1 text-[11px] text-error" data-testid="extend-new-end-date-error">
                                {t("extendEndDateNotAfterCurrent")}
                            </p>
                        )}
                    </div>
                    <div>
                        <label className={label} htmlFor="extend-contract-date">{t("contractDate")}</label>
                        <input
                            id="extend-contract-date"
                            type="date"
                            className={field}
                            value={contractDate}
                            onChange={e => setContractDate(e.target.value)}
                        />
                    </div>
                </div>

                {termTooLong && (
                    <p role="alert" data-testid="extend-term-too-long" className="text-[11px] font-semibold text-error">
                        {t("errTermTooLong", { max: MAX_TERM_YEARS })}
                    </p>
                )}
                {termNeedsConfirm && (
                    <span className="inline-flex flex-wrap items-center gap-2 text-[11px] text-warning" role="alert"
                        data-testid="extend-long-term-confirm">
                        <AlertTriangle size={12} /> {t("longTermConfirm", { years: termYears(lease.startDate, newEndDate) })}
                        <button type="button" data-testid="extend-long-term-continue"
                            onClick={() => setTermAck(termKey)}
                            className="px-2.5 py-1 rounded-md border border-warning/40 font-semibold text-foreground hover:bg-warning/10 cursor-pointer">
                            {t("longTermContinue")}
                        </button>
                    </span>
                )}

                <section>
                    <h4 className="text-[11px] font-semibold text-muted uppercase tracking-wider mb-2">{t("extensionLines")}</h4>
                    <LeaseLinesGrid
                        lines={rows}
                        chargeTypes={chargeTypes}
                        propertyId={lease.propertyId}
                        editable
                        onChange={setRows}
                        errors={errors}
                        rentVat={!!lease.rentVatApplicable}
                    />
                </section>

                <section>
                    <h4 className="text-[11px] font-semibold text-muted uppercase tracking-wider mb-2">{t("extensionCheques")}</h4>
                    <ChequeRowsEditor
                        rows={cheques}
                        onChange={setCheques}
                        propertyId={lease.propertyId}
                        expectedTotal={totals.inclVat}
                        testIdPrefix="extend"
                    />
                </section>

                {rest.length > 0 && (
                    <ul className="text-[11px] text-error space-y-1" data-testid="extend-errors">
                        {rest.map((e, i) => (
                            <li key={i}>{e}</li>
                        ))}
                    </ul>
                )}
            </div>
        </LeaseDialog>
    );
}
