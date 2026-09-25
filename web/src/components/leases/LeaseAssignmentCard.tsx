"use client";

import { useCallback, useEffect, useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import { UserRoundCog } from "lucide-react";
import { serverText } from "@/components/finance/bankrec/serverText";
import { fmtAmount } from "@/lib/api/ledger";
import { fmtIsoDate } from "./leaseMath";
import { ApiError, leaseApi, type LeaseAssignment, type LeaseDetail } from "@/lib/api/leasing";

/**
 * F14-39: hand the lease to another renter — the death of the tenant, a company
 * novation. Same unit, same lease number, deposit and cheques. Drafting writes
 * nothing to the ledger and shows what the post will move (the outgoing renter's
 * receivable, PDCs, deposit and unearned rent on this lease) and anything overdue,
 * which the incoming renter must be confirmed to take on. Posting is a finance act.
 */

const field =
    "w-full bg-input border border-border rounded-lg px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none";
const label = "block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5";
const btn = "px-3 py-1.5 rounded-lg text-[11px] font-semibold border border-border cursor-pointer disabled:opacity-50";

type Props = {
    lease: LeaseDetail;
    canDraft: boolean;
    canPost: boolean;
    onChanged: () => void;
};

export default function LeaseAssignmentCard({ lease, canDraft, canPost, onChanged }: Props) {
    const t = useTranslations("Leasing");
    const tCommon = useTranslations("Common");
    const locale = useLocale();
    const [items, setItems] = useState<LeaseAssignment[]>([]);
    const [renters, setRenters] = useState<{ id: string; nameEn: string; nameAr?: string | null }[]>([]);
    const [open, setOpen] = useState(false);
    const [toRenterId, setToRenterId] = useState("");
    const [effectiveDate, setEffectiveDate] = useState("");
    const [reason, setReason] = useState("");
    const [takeOver, setTakeOver] = useState(false);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const load = useCallback(async () => {
        try {
            setItems(await leaseApi.assignments(lease.id));
        } catch {
            setItems([]);
        }
    }, [lease.id]);

    useEffect(() => { load(); }, [load]);

    const fail = (e: unknown) => setError(e instanceof ApiError ? serverText(tCommon, e) || e.message : t("assignment.failed"));

    const startDraft = async () => {
        setOpen(true);
        setError(null);
        if (renters.length === 0) {
            try {
                setRenters((await leaseApi.renterOptions()).filter(r => r.id !== lease.renterId));
            } catch (e) {
                fail(e);
            }
        }
    };

    const draftIt = async () => {
        setBusy(true);
        setError(null);
        try {
            await leaseApi.draftAssignment(lease.id, { toRenterId, effectiveDate, reason: reason.trim(), takeOverOverdue: takeOver });
            setOpen(false);
            await load();
            onChanged();
        } catch (e) {
            fail(e);
        } finally {
            setBusy(false);
        }
    };

    const act = async (fn: () => Promise<unknown>) => {
        setBusy(true);
        setError(null);
        try {
            await fn();
            await load();
            onChanged();
        } catch (e) {
            fail(e);
        } finally {
            setBusy(false);
        }
    };

    const draft = items.find(a => a.status === "DRAFT");
    const posted = items.filter(a => a.status === "POSTED");
    const assignable = (lease.status === "ACTIVE" || lease.status === "NOTICE_GIVEN") && !!lease.postedAt;
    if (!assignable && posted.length === 0) return null;
    const bdi = (v: number) => <bdi dir="ltr">{fmtAmount(v)}</bdi>;

    return (
        <section data-testid="lease-assignment" className="bg-surface border border-border rounded-xl p-3 space-y-3">
            <div className="flex items-center justify-between gap-2">
                <h3 className="text-[11px] font-semibold text-muted uppercase tracking-wider inline-flex items-center gap-1.5">
                    <UserRoundCog size={13} /> {t("assignment.title")}
                </h3>
                {assignable && canDraft && !draft && !open && (
                    <button type="button" className={btn} data-testid="assignment-start" onClick={startDraft}>
                        {t("assignment.start")}
                    </button>
                )}
            </div>

            {posted.map(a => (
                <p key={a.id} className="text-xs text-muted" data-testid={`assignment-posted-${a.id}`}>
                    {t("assignment.posted", { from: a.fromRenterName ?? "—", to: a.toRenterName ?? "—",
                        date: fmtIsoDate(a.effectiveDate, locale), journal: a.journalNumber ?? "—" })}
                </p>
            ))}

            {open && (
                <div className="grid grid-cols-1 md:grid-cols-2 gap-3" data-testid="assignment-form">
                    <div>
                        <label className={label} htmlFor="assignment-renter">{t("assignment.toRenter")}</label>
                        <select id="assignment-renter" data-testid="assignment-renter" className={field}
                                value={toRenterId} onChange={e => setToRenterId(e.target.value)}>
                            <option value="">{t("assignment.chooseRenter")}</option>
                            {renters.map(r => (
                                <option key={r.id} value={r.id}>{locale === "ar" ? r.nameAr || r.nameEn : r.nameEn}</option>
                            ))}
                        </select>
                    </div>
                    <div>
                        <label className={label} htmlFor="assignment-date">{t("effectiveFrom")}</label>
                        <input id="assignment-date" data-testid="assignment-date" type="date" className={field}
                               min={lease.startDate} max={lease.endDate}
                               value={effectiveDate} onChange={e => setEffectiveDate(e.target.value)} />
                    </div>
                    <div className="md:col-span-2">
                        <label className={label} htmlFor="assignment-reason">{t("assignment.reason")}</label>
                        <input id="assignment-reason" data-testid="assignment-reason" className={field}
                               placeholder={t("assignment.reasonHint")}
                               value={reason} onChange={e => setReason(e.target.value)} />
                    </div>
                    <label className="md:col-span-2 inline-flex items-center gap-2 text-xs cursor-pointer">
                        <input type="checkbox" data-testid="assignment-take-over" checked={takeOver}
                               onChange={e => setTakeOver(e.target.checked)} />
                        {t("assignment.takeOver")}
                    </label>
                    <div className="md:col-span-2 flex gap-2">
                        <button type="button" className={btn} data-testid="assignment-draft"
                                disabled={busy || !toRenterId || !effectiveDate || !reason.trim()} onClick={draftIt}>
                            {t("assignment.draft")}
                        </button>
                        <button type="button" className={btn} onClick={() => setOpen(false)}>{t("cancel")}</button>
                    </div>
                </div>
            )}

            {draft && (
                <div className="space-y-2 text-xs" data-testid="assignment-draft-card">
                    <p className="font-semibold">
                        {t("assignment.pending", { from: draft.fromRenterName ?? "—", to: draft.toRenterName ?? "—",
                            date: fmtIsoDate(draft.effectiveDate, locale) })}
                    </p>
                    {draft.reason && <p className="text-muted">{draft.reason}</p>}
                    <div>
                        <div className={label}>{t("assignment.moves")}</div>
                        <ul className="space-y-0.5 tabular-nums" data-testid="assignment-balances">
                            {draft.balances.map((b, i) => (
                                <li key={`${b.accountId}-${i}`} className="flex justify-between gap-3">
                                    <span>{b.accountCode} {locale === "ar" ? b.accountNameAr || b.accountName : b.accountName}</span>
                                    <span>{b.amount >= 0 ? t.rich("assignment.dr", { amount: fmtAmount(b.amount), n: c => <bdi dir="ltr">{c}</bdi> })
                                        : t.rich("assignment.cr", { amount: fmtAmount(-b.amount), n: c => <bdi dir="ltr">{c}</bdi> })}</span>
                                </li>
                            ))}
                        </ul>
                        <p className="text-muted mt-1">{t("assignment.cheques", { count: draft.chequesMoving })}</p>
                    </div>
                    {draft.overdue.length > 0 && (
                        <div className="text-warning" data-testid="assignment-overdue">
                            <div className={label}>{t("assignment.overdue")}</div>
                            <ul>
                                {draft.overdue.map(o => (
                                    <li key={o.chequeId}>
                                        #{o.seqNo} {o.chequeNumber ?? ""} · {o.chequeDate ? fmtIsoDate(o.chequeDate, locale) : "—"} · {bdi(o.amount)}
                                    </li>
                                ))}
                            </ul>
                            {draft.takeOverOverdue && <p>{t("assignment.takenOver")}</p>}
                        </div>
                    )}
                    <div className="flex gap-2">
                        {canPost && (
                            <button type="button" className={btn} data-testid="assignment-post" disabled={busy}
                                    onClick={() => act(() => leaseApi.postAssignment(lease.id, draft.id, draft.takeOverOverdue))}>
                                {t("assignment.post")}
                            </button>
                        )}
                        {canDraft && (
                            <button type="button" className={btn} data-testid="assignment-delete" disabled={busy}
                                    onClick={() => act(() => leaseApi.cancelAssignment(lease.id, draft.id))}>
                                {t("assignment.delete")}
                            </button>
                        )}
                    </div>
                </div>
            )}

            {error && <p role="alert" className="text-[11px] text-error" data-testid="assignment-error">{error}</p>}
        </section>
    );
}
