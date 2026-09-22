"use client";

import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import LeaseDialog from "./LeaseDialog";
import LeaseLinesGrid from "./LeaseLinesGrid";
import { linesAreValid, splitLineErrors, toInputs, toRows, todayIso, type LineRow } from "./leaseMath";
import { ApiError, leaseApi, type ChargeType, type LeaseDetail } from "@/lib/api/leasing";

/**
 * Next year's contract, drafted from this one.
 *
 * The successor is a DRAFT — renewal writes no journals, which is why a
 * property manager may do it and why posting it is a separate, deliberate act.
 * Leaving "copy the lines" ticked sends no `lines` at all, which the backend
 * reads as "carry the predecessor's across"; untick it and the grid below is
 * what the new contract charges.
 */

const field =
    "w-full bg-input border border-border rounded-lg px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200";
const label = "block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5";

type Props = {
    open: boolean;
    lease: LeaseDetail;
    chargeTypes: ChargeType[];
    onClose: () => void;
    onRenewed: (successor: LeaseDetail) => void;
};

/** The day after the current term ends — the only start that leaves no gap. */
function dayAfter(iso: string): string {
    const [y, m, d] = iso.slice(0, 10).split("-").map(Number);
    if (!y || !m || !d) return "";
    const next = new Date(y, m - 1, d + 1);
    const pad = (n: number) => String(n).padStart(2, "0");
    return `${next.getFullYear()}-${pad(next.getMonth() + 1)}-${pad(next.getDate())}`;
}

/** A year on from a start date, ending the day before the anniversary. */
function yearFrom(iso: string): string {
    const [y, m, d] = iso.slice(0, 10).split("-").map(Number);
    if (!y || !m || !d) return "";
    const end = new Date(y + 1, m - 1, d - 1);
    const pad = (n: number) => String(n).padStart(2, "0");
    return `${end.getFullYear()}-${pad(end.getMonth() + 1)}-${pad(end.getDate())}`;
}

export default function RenewLeaseDialog({ open, lease, chargeTypes, onClose, onRenewed }: Props) {
    const t = useTranslations("Leasing");
    const [contractDate, setContractDate] = useState(todayIso());
    const [startDate, setStartDate] = useState("");
    const [endDate, setEndDate] = useState("");
    const [copyLines, setCopyLines] = useState(true);
    const [carryDeposit, setCarryDeposit] = useState(true);
    const [rows, setRows] = useState<LineRow[]>([]);
    const [busy, setBusy] = useState(false);
    const [errors, setErrors] = useState<string[]>([]);

    useEffect(() => {
        if (!open) return;
        const start = dayAfter(lease.endDate);
        setContractDate(todayIso());
        setStartDate(start);
        setEndDate(yearFrom(start));
        setCopyLines(true);
        setCarryDeposit(true);
        setRows(toRows(lease.lines));
        setErrors([]);
    }, [open, lease.endDate, lease.lines]);

    const { rest } = splitLineErrors(errors);

    const submit = async () => {
        setBusy(true);
        setErrors([]);
        try {
            const successor = await leaseApi.renew(lease.id, {
                contractDate: contractDate || null,
                startDate,
                endDate,
                lines: copyLines ? null : toInputs(rows),
                carryDepositForward: carryDeposit,
            });
            onRenewed(successor);
        } catch (e) {
            setErrors(e instanceof ApiError ? [e.message] : [t("renewFailed")]);
        } finally {
            setBusy(false);
        }
    };

    return (
        <LeaseDialog
            open={open}
            title={t("renew")}
            onClose={onClose}
            onConfirm={submit}
            confirmText={t("renew")}
            cancelText={t("cancel")}
            confirmDisabled={!startDate || !endDate || endDate <= startDate || (!copyLines && !linesAreValid(rows))}
            busy={busy}
            confirmTestId="renew-lease-confirm"
            width={copyLines ? "lg" : "xl"}
        >
            <div className="space-y-4">
                <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
                    <div>
                        <label className={label} htmlFor="renew-contract-date">{t("contractDate")}</label>
                        <input
                            id="renew-contract-date"
                            type="date"
                            className={field}
                            value={contractDate}
                            onChange={e => setContractDate(e.target.value)}
                        />
                    </div>
                    <div>
                        <label className={label} htmlFor="renew-start-date">{t("startDate")}</label>
                        <input
                            id="renew-start-date"
                            data-testid="renew-start-date"
                            type="date"
                            className={field}
                            value={startDate}
                            onChange={e => setStartDate(e.target.value)}
                        />
                    </div>
                    <div>
                        <label className={label} htmlFor="renew-end-date">{t("endDate")}</label>
                        <input
                            id="renew-end-date"
                            data-testid="renew-end-date"
                            type="date"
                            className={field}
                            value={endDate}
                            onChange={e => setEndDate(e.target.value)}
                        />
                    </div>
                </div>

                <label className="flex items-center gap-2 text-xs text-foreground">
                    <input
                        type="checkbox"
                        data-testid="renew-copy-lines"
                        checked={copyLines}
                        onChange={e => setCopyLines(e.target.checked)}
                    />
                    {t("copyLines")}
                </label>

                <label className="flex items-center gap-2 text-xs text-foreground">
                    <input
                        type="checkbox"
                        data-testid="renew-carry-deposit"
                        checked={carryDeposit}
                        onChange={e => setCarryDeposit(e.target.checked)}
                    />
                    {t("carryDepositForward")}
                </label>

                {!copyLines && (
                    <LeaseLinesGrid
                        lines={rows}
                        chargeTypes={chargeTypes}
                        propertyId={lease.propertyId}
                        editable
                        onChange={setRows}
                        errors={errors}
                    />
                )}

                {rest.length > 0 && (
                    <ul className="text-[11px] text-error space-y-1" data-testid="renew-errors">
                        {rest.map((e, i) => (
                            <li key={i}>{e}</li>
                        ))}
                    </ul>
                )}
            </div>
        </LeaseDialog>
    );
}
