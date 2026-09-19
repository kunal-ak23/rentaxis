"use client";

import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import LeaseDialog from "./LeaseDialog";
import LeaseLinesGrid from "./LeaseLinesGrid";
import { linesAreValid, splitLineErrors, toInputs, toRows, type LineRow } from "./leaseMath";
import {
    ApiError,
    leaseApi,
    type ChargeType,
    type Cheque,
    type LeaseDetail,
    type PostLeaseResponse,
} from "@/lib/api/leasing";

/**
 * Replace a posted lease's lines: the server reverses the current TCO with a
 * TCR and posts a fresh TCO in its place.
 *
 * The door is only open while every cheque is still REGISTERED. Once one has
 * been banked, the money has started moving against a contract value the
 * amendment would change underneath it, so the backend refuses — and so does
 * this, with the reason on the button rather than as a 400 after the
 * accountant has retyped every line.
 */

type Props = {
    open: boolean;
    lease: LeaseDetail;
    cheques: Cheque[];
    chargeTypes: ChargeType[];
    onClose: () => void;
    onAmended: (res: PostLeaseResponse) => void;
};

/** The precondition, stated the same way the backend states it. */
export function amendBlockedBy(cheques: Cheque[]): Cheque | null {
    return cheques.find(c => c.status !== "REGISTERED") ?? null;
}

export default function AmendLinesDialog({ open, lease, cheques, chargeTypes, onClose, onAmended }: Props) {
    const t = useTranslations("Leasing");
    const tc = useTranslations("Cheques");
    const [rows, setRows] = useState<LineRow[]>(() => toRows(lease.lines));
    const [reason, setReason] = useState("");
    const [busy, setBusy] = useState(false);
    const [errors, setErrors] = useState<string[]>([]);

    useEffect(() => {
        if (open) {
            setRows(toRows(lease.lines));
            setReason("");
            setErrors([]);
        }
    }, [open, lease.lines]);

    const blocker = amendBlockedBy(cheques);
    const { rest } = splitLineErrors(errors);

    const submit = async () => {
        setBusy(true);
        setErrors([]);
        try {
            const res = await leaseApi.amendLines(lease.id, { lines: toInputs(rows), reason: reason.trim() });
            onAmended(res);
        } catch (e) {
            setErrors(e instanceof ApiError ? [e.message] : [t("amendFailed")]);
        } finally {
            setBusy(false);
        }
    };

    return (
        <LeaseDialog
            open={open}
            title={t("amendLines")}
            onClose={onClose}
            onConfirm={submit}
            confirmText={t("amendLines")}
            cancelText={t("cancel")}
            confirmDisabled={!!blocker || !reason.trim() || !linesAreValid(rows)}
            busy={busy}
            confirmTestId="amend-lines-confirm"
            width="xl"
        >
            <div className="space-y-3">
                {blocker && (
                    <p className="text-[11px] text-error bg-error/10 border border-error/20 rounded-lg px-3 py-2" data-testid="amend-blocked">
                        {t("amendBlockedCheques", {
                            cheque: blocker.chequeNumber || String(blocker.seqNo),
                            status: tc(`status.${blocker.status}`),
                        })}
                    </p>
                )}

                <LeaseLinesGrid
                    lines={rows}
                    chargeTypes={chargeTypes}
                    propertyId={lease.propertyId}
                    editable={!blocker}
                    onChange={setRows}
                    errors={errors}
                />

                <div>
                    <label htmlFor="amend-reason" className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5">
                        {t("amendReason")}
                    </label>
                    <textarea
                        id="amend-reason"
                        data-testid="amend-reason"
                        rows={2}
                        disabled={!!blocker}
                        value={reason}
                        onChange={e => setReason(e.target.value)}
                        className="w-full bg-input border border-border rounded-lg px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none resize-none disabled:opacity-60"
                    />
                </div>

                {rest.length > 0 && (
                    <ul className="text-[11px] text-error space-y-1" data-testid="amend-errors">
                        {rest.map((e, i) => (
                            <li key={i}>{e}</li>
                        ))}
                    </ul>
                )}
            </div>
        </LeaseDialog>
    );
}
