"use client";

import { useCallback, useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { Ban } from "lucide-react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { serverText } from "@/components/finance/bankrec/serverText";
import { ApiError } from "@/lib/api/facilities";
import { fmtAmount, ledgerApi, type Account } from "@/lib/api/ledger";
import { badDebtsApi, type BadDebtItem, type BadDebtWriteOff } from "@/lib/api/badDebts";

const field = "bg-input border border-border rounded-lg px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:outline-none";
const btn = "px-3 py-1.5 rounded-lg text-xs font-semibold border border-border hover:bg-input disabled:opacity-50";
const today = () => new Date().toISOString().slice(0, 10);

type Pending = { kind: "approve" | "reject" | "reverse" | "recover"; w: BadDebtWriteOff } | null;

/**
 * F14-38: write off a renter's unrecoverable balance on this lease. Finance picks
 * the open items (unpaid rows, bounced cheques) and proposes; an organisation
 * admin approves (the items close; Dr bad debts / Cr receivable), rejects or
 * reverses; money recovered later is recorded against the write-off.
 */
export default function BadDebtCard({ leaseId, canApprove }: { leaseId: string; canApprove: boolean }) {
    const t = useTranslations("BadDebts");
    const tCommon = useTranslations("Common");
    const [items, setItems] = useState<BadDebtItem[]>([]);
    const [writeOffs, setWriteOffs] = useState<BadDebtWriteOff[]>([]);
    const [picked, setPicked] = useState<Set<string>>(new Set());
    const [reason, setReason] = useState("");
    const [date, setDate] = useState(today());
    const [error, setError] = useState<string | null>(null);
    const [busy, setBusy] = useState(false);
    const [pending, setPending] = useState<Pending>(null);
    const [note, setNote] = useState("");
    const [recovery, setRecovery] = useState({ amount: 0, date: today(), accountId: "" });
    const [banks, setBanks] = useState<Account[]>([]);

    const load = useCallback(async () => {
        try {
            const [c, w] = await Promise.all([badDebtsApi.candidates(leaseId, date), badDebtsApi.forLease(leaseId)]);
            setItems(c);
            setWriteOffs(w);
        } catch {
            setItems([]);
        }
    }, [leaseId, date]);

    useEffect(() => { load(); }, [load]);

    const run = async (fn: () => Promise<unknown>) => {
        setBusy(true);
        setError(null);
        try {
            await fn();
            setPending(null);
            setNote("");
            setPicked(new Set());
            setReason("");
            await load();
        } catch (e) {
            setError(e instanceof ApiError ? serverText(tCommon, e) || e.message : tCommon("loadFailed"));
        } finally {
            setBusy(false);
        }
    };

    const openRecover = async (w: BadDebtWriteOff) => {
        if (banks.length === 0) {
            try {
                setBanks((await ledgerApi.accounts.list()).filter(a => !a.group && a.accountType === "ASSET"
                    && (a.accountSubType === "BANK" || a.accountSubType === "CASH")));
            } catch { /* the select stays empty */ }
        }
        setRecovery({ amount: w.amount - w.recovered, date: today(), accountId: "" });
        setPending({ kind: "recover", w });
    };

    if (items.length === 0 && writeOffs.length === 0) return null;
    const total = items.filter(i => picked.has(i.chequeId)).reduce((s, i) => s + i.amount, 0);

    return (
        <div className="bg-surface border border-border rounded-xl p-4 mt-4" data-testid="bad-debt-card">
            <h3 className="text-sm font-bold flex items-center gap-2 mb-3"><Ban size={15} className="text-error" />{t("title")}</h3>
            {items.length > 0 && (
                <div className="space-y-2">
                    <p className="text-xs text-muted">{t("openItems")}</p>
                    {items.map(i => (
                        <label key={i.chequeId} className="flex items-center gap-2 text-xs">
                            <input type="checkbox" checked={picked.has(i.chequeId)} data-testid={`bd-item-${i.seqNo}`}
                                   onChange={e => {
                                       const next = new Set(picked);
                                       if (e.target.checked) next.add(i.chequeId); else next.delete(i.chequeId);
                                       setPicked(next);
                                   }} />
                            <span>#{i.seqNo} · <bdi dir="ltr">{i.date}</bdi> · {t(`itemStatus.${i.status === "BOUNCED" ? "BOUNCED" : "UNPAID"}`)}</span>
                            <span className="ms-auto tabular-nums"><bdi dir="ltr">{fmtAmount(i.amount)}</bdi></span>
                        </label>
                    ))}
                    <div className="flex flex-wrap items-end gap-2 pt-2">
                        <input className={`${field} flex-1 min-w-[12rem]`} placeholder={t("reason")} value={reason}
                               onChange={e => setReason(e.target.value)} data-testid="bd-reason" />
                        <input type="date" className={field} value={date} onChange={e => setDate(e.target.value)} />
                        <button type="button" className={btn} disabled={busy || picked.size === 0 || !reason.trim()} data-testid="bd-propose"
                                onClick={() => run(() => badDebtsApi.propose({ leaseId, chequeIds: [...picked], date, reason }))}>
                            {t("propose", { amount: fmtAmount(total) })}
                        </button>
                    </div>
                </div>
            )}
            {writeOffs.length > 0 && (
                <ul className="mt-4 space-y-2" data-testid="bd-list">
                    {writeOffs.map(w => (
                        <li key={w.id} className="border border-border rounded-lg p-3 text-xs">
                            <div className="flex flex-wrap items-center gap-2">
                                <span className="font-semibold">{t(`status.${w.status}`)}</span>
                                <span className="tabular-nums"><bdi dir="ltr">{fmtAmount(w.amount)}</bdi></span>
                                <span className="text-muted"><bdi dir="ltr">{w.writeOffDate}</bdi> · {w.reason}</span>
                                {w.recovered > 0 && <span className="text-success">{t("recovered", { amount: fmtAmount(w.recovered) })}</span>}
                                <span className="ms-auto flex gap-2">
                                    {canApprove && w.status === "PROPOSED" && (
                                        <>
                                            <button type="button" className={btn} data-testid="bd-approve" onClick={() => setPending({ kind: "approve", w })}>{t("approve")}</button>
                                            <button type="button" className={btn} onClick={() => setPending({ kind: "reject", w })}>{t("reject")}</button>
                                        </>
                                    )}
                                    {w.status === "WRITTEN_OFF" && w.recovered < w.amount && (
                                        <button type="button" className={btn} onClick={() => openRecover(w)}>{t("recover")}</button>
                                    )}
                                    {canApprove && w.status === "WRITTEN_OFF" && w.recovered === 0 && (
                                        <button type="button" className={btn} onClick={() => setPending({ kind: "reverse", w })}>{t("reverse")}</button>
                                    )}
                                </span>
                            </div>
                            {w.vatLease && <p className="mt-1 text-warning">{t("vatNote")}</p>}
                        </li>
                    ))}
                </ul>
            )}
            {error && <p className="mt-2 text-xs text-error" role="alert">{error}</p>}

            <ConfirmDialog
                isOpen={pending !== null}
                onClose={() => setPending(null)}
                isLoading={busy}
                isDestructive={pending?.kind !== "recover"}
                title={pending ? t(`confirm.${pending.kind}`) : ""}
                description={pending ? fmtAmount(pending.w.amount) : ""}
                confirmText={pending ? t(pending.kind) : ""}
                cancelText={t("cancel")}
                confirmTestId="bd-confirm"
                confirmDisabled={(pending?.kind === "reject" || pending?.kind === "reverse") && !note.trim()
                    || (pending?.kind === "recover" && (!recovery.accountId || recovery.amount <= 0))}
                onConfirm={() => {
                    if (!pending) return;
                    const w = pending.w;
                    if (pending.kind === "approve") run(() => badDebtsApi.approve(w.id, note));
                    if (pending.kind === "reject") run(() => badDebtsApi.reject(w.id, note));
                    if (pending.kind === "reverse") run(() => badDebtsApi.reverse(w.id, today(), note));
                    if (pending.kind === "recover") run(() => badDebtsApi.recover(w.id, recovery));
                }}
            >
                {pending?.kind === "recover" ? (
                    <div className="grid gap-2">
                        <input type="number" className={field} value={recovery.amount} min={0} step={0.01}
                               onChange={e => setRecovery({ ...recovery, amount: Number(e.target.value) })} aria-label={t("amount")} />
                        <input type="date" className={field} value={recovery.date} onChange={e => setRecovery({ ...recovery, date: e.target.value })} />
                        <select className={field} value={recovery.accountId} onChange={e => setRecovery({ ...recovery, accountId: e.target.value })}
                                aria-label={t("account")}>
                            <option value="">{t("account")}</option>
                            {banks.map(b => <option key={b.id} value={b.id}>{b.code} · {b.name}</option>)}
                        </select>
                    </div>
                ) : (
                    <input className={`${field} w-full`} placeholder={t("note")} value={note} onChange={e => setNote(e.target.value)}
                           data-testid="bd-note" />
                )}
            </ConfirmDialog>
        </div>
    );
}
