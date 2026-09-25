"use client";

import { useCallback, useEffect, useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import { Receipt, X } from "lucide-react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { serverText } from "@/components/finance/bankrec/serverText";
import { ApiError } from "@/lib/api/facilities";
import { fmtAmount } from "@/lib/api/ledger";
import { ticketChargesApi, type TicketCharges } from "@/lib/api/ticketCharges";

const field = "bg-input border border-border rounded-lg px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:outline-none";
const btn = "px-3 py-1.5 rounded-lg text-xs font-semibold border border-border hover:bg-input disabled:opacity-50";

/**
 * F14-49: the ticket's vendor bill (a posted purchase invoice linked here) and the
 * recharge to the renter — a maintenance-recharge charge on the lease, defaulting
 * to the bill's net amount, approved in the penalties queue. Never automatic.
 */
export default function TicketChargesCard({ ticketId }: { ticketId: string }) {
    const t = useTranslations("TicketCharges");
    const tCommon = useTranslations("Common");
    const ar = useLocale() === "ar";
    const [data, setData] = useState<TicketCharges | null>(null);
    const [pick, setPick] = useState("");
    const [open, setOpen] = useState(false);
    const [amount, setAmount] = useState(0);
    const [vat, setVat] = useState<"auto" | "yes" | "no">("auto");
    const [error, setError] = useState<string | null>(null);
    const [busy, setBusy] = useState(false);

    const load = useCallback(async () => {
        try {
            const d = await ticketChargesApi.get(ticketId);
            // A caller without finance access (or an old server) gets no card, not a crash.
            setData(d && Array.isArray(d.bills) && Array.isArray(d.recharges) ? d : null);
        } catch { setData(null); }
    }, [ticketId]);
    useEffect(() => { load(); }, [load]);

    const run = async (fn: () => Promise<TicketCharges>) => {
        setBusy(true);
        setError(null);
        try {
            setData(await fn());
            setOpen(false);
            setPick("");
        } catch (e) {
            setError(e instanceof ApiError ? serverText(tCommon, e) || e.message : tCommon("loadFailed"));
        } finally {
            setBusy(false);
        }
    };

    if (!data) return null;
    return (
        <div className="bg-surface rounded-xl border border-border p-5" data-testid="ticket-charges">
            <h3 className="text-xs font-semibold text-muted uppercase tracking-wider mb-3 flex items-center gap-2">
                <Receipt size={13} />{t("title")}
            </h3>
            <p className="text-[11px] font-semibold text-muted mb-1">{t("bills")}</p>
            {data.bills.length === 0 ? <p className="text-xs text-muted mb-2">{t("noBills")}</p> : (
                <ul className="text-xs space-y-1 mb-2">
                    {data.bills.map(b => (
                        <li key={b.voucherId} className="flex items-center gap-2" data-testid="ticket-bill">
                            <bdi dir="ltr">{b.invoiceNumber ?? b.voucherNumber}</bdi>
                            <span className="text-muted">{ar && b.vendorAr ? b.vendorAr : b.vendor}</span>
                            <span className="ms-auto tabular-nums"><bdi dir="ltr">{fmtAmount(b.net)}</bdi></span>
                            <button type="button" aria-label={t("unlink")} onClick={() => run(() => ticketChargesApi.unlink(ticketId, b.voucherId))}>
                                <X size={12} />
                            </button>
                        </li>
                    ))}
                </ul>
            )}
            {data.candidates.length > 0 && (
                // F15-23: the select may shrink (min-w-0) so the Link button stays inside the card.
                <div className="flex items-center gap-2 mb-3 min-w-0">
                    <select className={`${field} flex-1 min-w-0 w-full truncate`} value={pick} onChange={e => setPick(e.target.value)} data-testid="ticket-bill-pick">
                        <option value="">{t("pickBill")}</option>
                        {data.candidates.map(b => (
                            <option key={b.voucherId} value={b.voucherId}>
                                {(b.invoiceNumber ?? b.voucherNumber) + " · " + (b.vendor ?? "") + " · " + fmtAmount(b.net)}
                            </option>
                        ))}
                    </select>
                    <button type="button" className={`${btn} shrink-0`} disabled={!pick || busy} data-testid="ticket-bill-link"
                            onClick={() => run(() => ticketChargesApi.link(ticketId, pick))}>{t("link")}</button>
                </div>
            )}
            <p className="text-[11px] font-semibold text-muted mb-1">{t("recharges")}</p>
            {data.recharges.length === 0 ? <p className="text-xs text-muted">{t("noRecharges")}</p> : (
                <ul className="text-xs space-y-1">
                    {data.recharges.map(r => (
                        <li key={r.id} className="flex items-center gap-2" data-testid="ticket-recharge">
                            <span>{t(`status.${r.status}`)}</span>
                            <span className="ms-auto tabular-nums">
                                <bdi dir="ltr">{fmtAmount(r.amount)}</bdi>
                                {r.vatable && <span className="text-muted"> {t("plusVat")}</span>}
                            </span>
                        </li>
                    ))}
                </ul>
            )}
            {data.leaseId && (
                <button type="button" className={`${btn} mt-3`} data-testid="ticket-recharge-open"
                        onClick={() => { setAmount(data.billsNet); setVat("auto"); setOpen(true); }}>{t("recharge")}</button>
            )}
            {error && <p className="mt-2 text-xs text-error" role="alert">{error}</p>}
            <ConfirmDialog
                isOpen={open}
                onClose={() => setOpen(false)}
                isLoading={busy}
                title={t("recharge")}
                description={t("rechargeDesc")}
                confirmText={t("recharge")}
                cancelText={t("cancel")}
                confirmTestId="ticket-recharge-confirm"
                confirmDisabled={!(amount > 0)}
                onConfirm={() => run(() => ticketChargesApi.recharge(ticketId, { amount, vatable: vat === "auto" ? null : vat === "yes" }))}
            >
                <div className="grid gap-2">
                    <input type="number" className={field} min={0} step={0.01} value={amount} aria-label={t("amount")}
                           onChange={e => setAmount(Number(e.target.value))} data-testid="ticket-recharge-amount" />
                    <select className={field} value={vat} onChange={e => setVat(e.target.value as "auto" | "yes" | "no")} aria-label={t("vat")}>
                        <option value="auto">{t("vatAuto")}</option>
                        <option value="yes">{t("vatYes")}</option>
                        <option value="no">{t("vatNo")}</option>
                    </select>
                </div>
            </ConfirmDialog>
        </div>
    );
}
