"use client";

import { useEffect, useMemo, useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import { BellRing, CalendarClock, Flag, Hash, Loader2, Phone, Sparkles, Undo2 } from "lucide-react";
import { Link, useRouter } from "@/i18n/routing";
import { cn } from "@/lib/utils";
import { Pagination } from "@/components/ui/Pagination";
import ActionsMenu, { type ActionsMenuItem } from "@/components/ui/ActionsMenu";
import RenewLeaseDialog from "@/components/leases/RenewLeaseDialog";
import ExtendLeaseDialog from "@/components/leases/ExtendLeaseDialog";
import GiveNoticeDialog from "@/components/leases/GiveNoticeDialog";
import { fmtIsoDate } from "@/components/leases/leaseMath";
import { serverText } from "@/components/finance/bankrec/serverText";
import { formatCurrency } from "@/lib/format";
import { useLatestRequest } from "@/hooks/useLatestRequest";
import { isAbortError } from "@/lib/api/abort";
import {
    ApiError, chargeTypeApi, leaseApi, renewalMarkApi, terminationApi,
    type ChargeType, type GiveNoticeInput, type LeaseDetail,
} from "@/lib/api/leasing";
import { QUEUE_STAGES, hasDraftRenewal, isBulkRenewable, isRenewable, monthRange, stageQuery, type QueueStage } from "@/lib/leases/renewalSeason";
import BulkRenewDialog from "./BulkRenewDialog";
import BulkChequeNumbersDialog from "./BulkChequeNumbersDialog";
import NotRenewingDialog from "./NotRenewingDialog";
import ContactRenterDialog from "./ContactRenterDialog";

export interface QueueFilters { month: string; stage: QueueStage; propertyId: string; buildingId: string; floor: string; search: string }

/** The most contracts one bulk renew takes from "Select all" (a busy month is ~700; done in two passes). */
const SELECT_ALL_CAP = 500;

/**
 * The renewal season queue (scale #16): contracts ending in a month, by stage, nearest expiry
 * first, with the row actions an operations manager works them with — Renew (the single renew
 * dialog), Extend, Not renewing (notice), Contact — and bulk renew for the selection.
 *
 * Sorted by expiry date rather than creation date (the list rule's documented exception):
 * the queue is worked in the order the contracts run out.
 */
export default function RenewalQueue({ filters, onStage, perms }: {
    filters: QueueFilters;
    onStage: (stage: QueueStage) => void;
    perms: { renew: boolean; extend: boolean; notice: boolean };
}) {
    const t = useTranslations("RenewalSeason");
    const tl = useTranslations("Leasing");
    const tCommon = useTranslations("Common");
    const locale = useLocale();
    const router = useRouter();
    const [rows, setRows] = useState<LeaseDetail[]>([]);
    const [total, setTotal] = useState(0);
    const [counts, setCounts] = useState<Partial<Record<QueueStage, number>>>({});
    const [page, setPage] = useState(1);
    const [size, setSize] = useState(25);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [selected, setSelected] = useState<Map<string, LeaseDetail>>(new Map());
    const [selectingAll, setSelectingAll] = useState(false);
    const [chargeTypes, setChargeTypes] = useState<ChargeType[]>([]);
    const [bulkOpen, setBulkOpen] = useState(false);
    const [renewing, setRenewing] = useState<LeaseDetail | null>(null);
    const [extending, setExtending] = useState<LeaseDetail | null>(null);
    const [noticeFor, setNoticeFor] = useState<LeaseDetail | null>(null);
    const [noticeBusy, setNoticeBusy] = useState(false);
    const [noticeError, setNoticeError] = useState<string | null>(null);
    const [contacting, setContacting] = useState<LeaseDetail | null>(null);
    const [marking, setMarking] = useState<LeaseDetail | null>(null);
    const [numbering, setNumbering] = useState(false);
    const [reloadKey, setReloadKey] = useState(0);
    const begin = useLatestRequest();
    const beginCounts = useLatestRequest();

    const { from, to } = monthRange(filters.month);
    const common = useMemo(() => ({
        endDateFrom: from, endDateTo: to,
        propertyId: filters.propertyId || undefined, buildingId: filters.buildingId || undefined,
        floor: filters.floor || undefined, search: filters.search || undefined,
    }), [from, to, filters.propertyId, filters.buildingId, filters.floor, filters.search]);

    const read = (stage: QueueStage, p: number, s: number) =>
        leaseApi.paged({ ...common, ...stageQuery(stage), sort: "endDate,asc", page: p, size: s });

    useEffect(() => {
        chargeTypeApi.list(true).then(setChargeTypes).catch(() => {});
    }, []);

    // The list of the stage being worked.
    useEffect(() => {
        const { isCurrent } = begin();
        setLoading(true);
        setError(null);
        read(filters.stage, page - 1, size)
            .then(data => {
                if (!isCurrent()) return;
                setRows(data.content ?? []);
                setTotal(data.totalElements ?? 0);
            })
            .catch(e => {
                if (isAbortError(e) || !isCurrent()) return;
                setError(e instanceof ApiError ? e.message : t("loadFailed"));
            })
            .finally(() => { if (isCurrent()) setLoading(false); });
        // eslint-disable-next-line react-hooks/exhaustive-deps -- read() closes over common, listed
    }, [common, filters.stage, page, size, reloadKey]);

    // Every stage's count: size=1 reads, so nothing here grows with the portfolio.
    useEffect(() => {
        const { isCurrent } = beginCounts();
        Promise.all(QUEUE_STAGES.map(st => read(st, 0, 1).then(p => [st, p.totalElements ?? 0] as const)))
            .then(pairs => { if (isCurrent()) setCounts(Object.fromEntries(pairs)); })
            .catch(() => {});
        // eslint-disable-next-line react-hooks/exhaustive-deps -- read() closes over common, listed
    }, [common, reloadKey]);

    useEffect(() => {
        setPage(1);
        setSelected(new Map());
    }, [common, filters.stage]);

    const reload = () => setReloadKey(k => k + 1);
    // The drafted stage selects renewal drafts for cheque numbering; every other stage selects
    // contracts for bulk renew.
    const drafted = filters.stage === "drafted";
    const canSelect = (l: LeaseDetail) => (drafted ? hasDraftRenewal(l) : isBulkRenewable(l));
    const selectable = rows.filter(canSelect);
    const pageAllSelected = selectable.length > 0 && selectable.every(r => selected.has(r.id));
    const toggle = (l: LeaseDetail) => setSelected(prev => {
        const next = new Map(prev);
        if (next.has(l.id)) next.delete(l.id); else next.set(l.id, l);
        return next;
    });
    const togglePage = () => setSelected(prev => {
        const next = new Map(prev);
        selectable.forEach(r => (pageAllSelected ? next.delete(r.id) : next.set(r.id, r)));
        return next;
    });
    const selectAll = async () => {
        setSelectingAll(true);
        try {
            const next = new Map<string, LeaseDetail>();
            for (let p = 0; next.size < SELECT_ALL_CAP; p++) {
                const data = await read(filters.stage, p, 100);
                (data.content ?? []).filter(canSelect).forEach(l => { if (next.size < SELECT_ALL_CAP) next.set(l.id, l); });
                if ((data.content ?? []).length < 100) break;
            }
            setSelected(next);
        } catch {
            setError(t("loadFailed"));
        } finally {
            setSelectingAll(false);
        }
    };

    const giveNotice = async (input: GiveNoticeInput) => {
        if (!noticeFor) return;
        setNoticeBusy(true);
        setNoticeError(null);
        try {
            await terminationApi.notice(noticeFor.id, input);
            setNoticeFor(null);
            reload();
        } catch (e) {
            setNoticeError(e instanceof ApiError ? e.message : t("batchFailed"));
        } finally {
            setNoticeBusy(false);
        }
    };

    const undoMark = async (l: LeaseDetail) => {
        setError(null);
        try {
            await renewalMarkApi.undo(l.id);
            reload();
        } catch (e) {
            setError(e instanceof ApiError ? (serverText(tCommon, e) || e.message) : t("batchFailed"));
        }
    };

    const actions = (l: LeaseDetail, where: "row" | "card" = "row"): ActionsMenuItem[] => {
        const q = where === "card" ? "queue-card" : "queue";
        const items: ActionsMenuItem[] = [];
        if (perms.renew && isRenewable(l)) items.push({ id: "renew", label: t("actionRenew"), testId: `${q}-renew-${l.id}`, icon: Sparkles, onSelect: () => setRenewing(l) });
        if (perms.extend && l.status === "ACTIVE" && !l.renewedToLeaseId) items.push({ id: "extend", label: t("actionExtend"), testId: `${q}-extend-${l.id}`, icon: CalendarClock, onSelect: () => setExtending(l) });
        const continued = !!l.renewedToLeaseId || !!l.transferredToLeaseId;
        // PR C ruling 1: "Not renewing" is a soft, reversible mark; giving notice is its own act.
        if (perms.renew && !continued && !l.notRenewingAt && ["ACTIVE", "NOTICE_GIVEN", "EXPIRED"].includes(l.status))
            items.push({ id: "notRenewing", label: t("actionNotRenewing"), testId: `${q}-not-renewing-${l.id}`, icon: Flag, onSelect: () => setMarking(l) });
        if (perms.renew && l.notRenewingAt)
            items.push({ id: "undoNotRenewing", label: t("actionUndoNotRenewing"), testId: `${q}-undo-not-renewing-${l.id}`, icon: Undo2, onSelect: () => undoMark(l) });
        if (perms.notice && l.status === "ACTIVE" && !continued) items.push({ id: "giveNotice", label: t("actionGiveNotice"), testId: `${q}-give-notice-${l.id}`, icon: BellRing, onSelect: () => { setNoticeError(null); setNoticeFor(l); } });
        items.push({ id: "contact", label: t("actionContact"), testId: `${q}-contact-${l.id}`, icon: Phone, onSelect: () => setContacting(l) });
        return items;
    };

    const stateOf = (l: LeaseDetail): { text: string; tone: string } => {
        if (l.renewedToLeaseId) return { text: t("stateDrafted", { status: tl(`leaseStatus.${l.renewedToStatus ?? "DRAFT"}`) }), tone: "text-primary" };
        if (l.transferredToLeaseId) return { text: t("stateTransfer"), tone: "text-muted" };
        if (l.notRenewingAt) return {
            text: t("stateMarked", { name: l.notRenewingByName ?? "—", date: fmtIsoDate(l.notRenewingAt, locale) })
                + (l.notRenewingNote ? ` · ${l.notRenewingNote}` : ""),
            tone: "text-warning",
        };
        if (l.status === "NOTICE_GIVEN") return { text: t("stateOnNotice"), tone: "text-warning" };
        if (l.status === "RENEWED") return { text: t("stateRenewed"), tone: "text-success" };
        if (l.status === "EXPIRED") return { text: t("stateHoldover"), tone: "text-error" };
        return { text: t("stateTodo"), tone: "text-[var(--ink-600)]" };
    };

    const monthLabel = new Date(`${filters.month}-01T00:00:00Z`).toLocaleDateString(locale === "ar" ? "ar-AE" : "en-GB", { month: "long", year: "numeric", timeZone: "UTC" });

    return (
        <div className="space-y-4" data-testid="renewal-queue">
            <h2 className="font-serif text-[20px] font-semibold" data-testid="renewal-queue-headline">
                {t("headline", { month: monthLabel, count: counts.all ?? 0 })}
            </h2>
            <div className="flex flex-wrap gap-2" role="tablist" aria-label={t("stages")}>
                {QUEUE_STAGES.map(st => (
                    <button key={st} type="button" role="tab" aria-selected={filters.stage === st} onClick={() => onStage(st)}
                        data-testid={`renewal-stage-${st}`}
                        className={cn("px-3 py-1.5 rounded-full text-xs font-medium border transition-colors",
                            filters.stage === st ? "bg-primary text-primary-foreground border-primary" : "bg-surface border-border text-[var(--ink-600)] hover:border-primary")}>
                        {t(`stage.${st}`)} <span className="tabular-nums opacity-80">{counts[st] ?? "…"}</span>
                    </button>
                ))}
            </div>

            <div className="flex flex-wrap items-center gap-3">
                {perms.renew && selectable.length > 0 && (
                    <label className="flex items-center gap-2 text-xs">
                        <input type="checkbox" checked={pageAllSelected} onChange={togglePage} data-testid="renewal-select-page" />
                        {t("selectPage")}
                    </label>
                )}
                {perms.renew && total > 0 && (filters.stage === "todo" || filters.stage === "all" || drafted) && (
                    <button type="button" onClick={selectAll} disabled={selectingAll} className="text-xs font-semibold text-primary hover:underline disabled:opacity-50"
                        data-testid="renewal-select-all">
                        {selectingAll ? <Loader2 size={12} className="animate-spin inline" /> : t("selectAllRenewable", { n: Math.min(total, SELECT_ALL_CAP) })}
                    </button>
                )}
                {selected.size > 0 && (
                    <>
                        <span className="text-xs text-muted" data-testid="renewal-selected-count">{t("selectedCount", { n: selected.size })}</span>
                        <button type="button" onClick={() => setSelected(new Map())} className="text-xs text-muted hover:underline">{t("clearSelection")}</button>
                        {drafted ? (
                            <button type="button" onClick={() => setNumbering(true)} data-testid="renewal-number-cheques"
                                className="ms-auto flex items-center gap-2 bg-accent text-accent-foreground px-4 py-2 rounded-lg text-xs font-semibold hover:brightness-110">
                                <Hash size={14} />{t("numberCheques", { n: selected.size })}
                            </button>
                        ) : (
                            <button type="button" onClick={() => setBulkOpen(true)} data-testid="renewal-bulk-renew"
                                className="ms-auto flex items-center gap-2 bg-accent text-accent-foreground px-4 py-2 rounded-lg text-xs font-semibold hover:brightness-110">
                                <Sparkles size={14} />{t("bulkRenew", { n: selected.size })}
                            </button>
                        )}
                    </>
                )}
            </div>
            {error && <p className="text-xs text-error" role="alert">{error}</p>}

            <section className="bg-surface border border-border rounded-[var(--radius-lg)] overflow-hidden">
                {/* Table on wide screens, cards at phone width (390 px): the same rows and actions. */}
                <div className="hidden md:block overflow-x-auto">
                    <table className="w-full text-xs" data-testid="renewal-queue-table">
                        <thead className="bg-input text-muted text-[10px] uppercase tracking-wider">
                            <tr>
                                <th className="p-2 w-8"><span className="sr-only">{t("select")}</span></th>
                                <th className="p-2 text-start">{t("colEnds")}</th>
                                <th className="p-2 text-start">{tl("unit")}</th>
                                <th className="p-2 text-start">{t("colTenant")}</th>
                                <th className="p-2 text-start">{t("colProperty")}</th>
                                <th className="p-2 text-end">{t("colRent")}</th>
                                <th className="p-2 text-start">{t("colState")}</th>
                                <th className="p-2 text-end"><span className="sr-only">{t("actions")}</span></th>
                            </tr>
                        </thead>
                        <tbody className="divide-y divide-border">
                            {loading && rows.length === 0 && <tr><td colSpan={8} className="p-6 text-center text-muted">{t("loading")}</td></tr>}
                            {!loading && rows.length === 0 && <tr><td colSpan={8} className="p-6 text-center text-muted" data-testid="renewal-queue-empty">{t("emptyStage")}</td></tr>}
                            {rows.map(l => {
                                const s = stateOf(l);
                                return (
                                    <tr key={l.id} data-testid={`renewal-row-${l.id}`}>
                                        <td className="p-2">
                                            {perms.renew && canSelect(l) && (
                                                <input type="checkbox" checked={selected.has(l.id)} onChange={() => toggle(l)} aria-label={t("selectRow", { unit: l.unitIdentifier ?? "" })} />
                                            )}
                                        </td>
                                        <td className="p-2 whitespace-nowrap">{fmtIsoDate(l.endDate, locale)}</td>
                                        <td className="p-2 font-medium"><Link href={`/dashboard/leases/${l.id}`} className="hover:underline">{l.unitIdentifier ?? "—"}</Link></td>
                                        <td className="p-2">{l.renterName ?? "—"}</td>
                                        <td className="p-2">{l.propertyName ?? "—"}</td>
                                        <td className="p-2 text-end tabular-nums"><bdi dir="ltr">{formatCurrency(l.currentRentAmount ?? l.rentAmount)}</bdi></td>
                                        <td className={cn("p-2", s.tone)}>
                                            {l.renewedToLeaseId ? <Link href={`/dashboard/leases/${l.renewedToLeaseId}`} className="hover:underline">{s.text}</Link> : s.text}
                                        </td>
                                        <td className="p-2 text-end">
                                            <ActionsMenu label={t("actions")} items={actions(l)} variant="icon" testId={`renewal-actions-${l.id}`} triggerTestId={`renewal-actions-trigger-${l.id}`} />
                                        </td>
                                    </tr>
                                );
                            })}
                        </tbody>
                    </table>
                </div>
                <ul className="md:hidden divide-y divide-border" data-testid="renewal-queue-cards">
                    {!loading && rows.length === 0 && <li className="p-6 text-center text-xs text-muted">{t("emptyStage")}</li>}
                    {rows.map(l => {
                        const s = stateOf(l);
                        return (
                            <li key={l.id} className="p-3 flex gap-3 items-start" data-testid={`renewal-card-${l.id}`}>
                                {perms.renew && canSelect(l)
                                    ? <input type="checkbox" className="mt-1" checked={selected.has(l.id)} onChange={() => toggle(l)} aria-label={t("selectRow", { unit: l.unitIdentifier ?? "" })} />
                                    : <span className="w-[13px]" />}
                                <div className="flex-1 min-w-0 text-xs space-y-0.5">
                                    <p className="font-semibold"><Link href={`/dashboard/leases/${l.id}`}>{l.unitIdentifier ?? "—"}</Link> · <span className="font-normal">{l.renterName ?? "—"}</span></p>
                                    <p className="text-muted truncate">{l.propertyName ?? "—"}</p>
                                    <p>{t("endsOn", { date: fmtIsoDate(l.endDate, locale) })} · <bdi dir="ltr">{formatCurrency(l.currentRentAmount ?? l.rentAmount)}</bdi></p>
                                    <p className={s.tone}>{s.text}</p>
                                </div>
                                <ActionsMenu label={t("actions")} items={actions(l, "card")} variant="icon" testId={`renewal-card-actions-${l.id}`} triggerTestId={`renewal-card-actions-trigger-${l.id}`} />
                            </li>
                        );
                    })}
                </ul>
                {total > size && (
                    <div className="p-3 border-t border-border">
                        <Pagination currentPage={page} totalItems={total} itemsPerPage={size} onPageChange={setPage}
                            onItemsPerPageChange={n => { setSize(n); setPage(1); }} itemsPerPageOptions={[25, 50, 100]} />
                    </div>
                )}
            </section>

            {bulkOpen && (
                <BulkRenewDialog open targets={[...selected.values()]} chargeTypes={chargeTypes}
                    onClose={() => { setBulkOpen(false); setSelected(new Map()); }} onDone={reload} />
            )}
            {renewing && (
                <RenewLeaseDialog open lease={renewing} chargeTypes={chargeTypes} onClose={() => setRenewing(null)}
                    onRenewed={successor => { setRenewing(null); router.push(`/dashboard/leases/${successor.id}`); }} />
            )}
            {extending && (
                <ExtendLeaseDialog open lease={extending} chargeTypes={chargeTypes} onClose={() => setExtending(null)}
                    onExtended={() => { setExtending(null); reload(); }} />
            )}
            <GiveNoticeDialog open={!!noticeFor} busy={noticeBusy} error={noticeError} onClose={() => setNoticeFor(null)}
                onConfirm={giveNotice} minDate={noticeFor?.contractDate ?? noticeFor?.startDate ?? null} />
            <ContactRenterDialog lease={contacting} onClose={() => setContacting(null)} />
            {marking && (
                <NotRenewingDialog lease={marking} onClose={() => setMarking(null)} onMarked={() => { setMarking(null); reload(); }} />
            )}
            {numbering && (
                <BulkChequeNumbersDialog
                    targets={[...selected.values()].filter(hasDraftRenewal).map(l => ({
                        draftId: l.renewedToLeaseId!, predecessorContract: l.displayContractNumber ?? null,
                        unitIdentifier: l.unitIdentifier ?? null, renterName: l.renterName ?? null,
                    }))}
                    onClose={() => { setNumbering(false); setSelected(new Map()); reload(); }}
                />
            )}
        </div>
    );
}
