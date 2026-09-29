"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { useTranslations } from "next-intl";
import { useSession } from "next-auth/react";
import { Check, KeyRound, Loader2, Plus, Search, ShieldCheck, X } from "lucide-react";
import { cn } from "@/lib/utils";
import SideDrawer from "@/components/ui/SideDrawer";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Pagination } from "@/components/ui/Pagination";
import { LoadFailedState } from "@/components/ui/PageStates";
import { businessTodayIso } from "@/lib/businessDate";
import {
    ErrorLine, GatePassPage, StatusBadge, ViewToggle, inputClass, labelClass, pageOf, primaryButton, secondaryButton, useDateTime,
} from "@/components/gatepass/shared";
import {
    ApiError, cancelPass, createPass, currentContracts, decideResidentApproval, errorText, fetchMyPasses,
    fetchResidentApprovals, localInstant, toE164,
    type GatePass, type GatePassStatus, type GatePassType, type MyContract, type WalkInPass,
} from "@/lib/api/gatepass";

type TabId = "passes" | "visitors";
const STATUSES: GatePassStatus[] = ["PENDING_APPROVAL", "ACTIVE", "USED", "EXPIRED", "CANCELLED"];
const CANCELLABLE = new Set<GatePassStatus>(["PENDING_APPROVAL", "ACTIVE"]);

/**
 * The tenant's gate passes: request one for a visitor, follow its status, show
 * the gate code, cancel it; and decide the walk-in visitors a guard has raised
 * at their door. Every call here is the tenant's own (`/gatepass/mine`,
 * `/resident-approvals`) — the server scopes them to the signed-in tenant.
 */
export default function TenantGatePassesPage() {
    return (
        <GatePassPage permission="canRequestGatePasses">
            <TenantGatePasses />
        </GatePassPage>
    );
}

function TenantGatePasses() {
    const t = useTranslations("GatePass");
    const { status: sessionStatus } = useSession();
    const { dateTime } = useDateTime();

    const [tab, setTab] = useState<TabId>("passes");
    const [passes, setPasses] = useState<GatePass[]>([]);
    const [contracts, setContracts] = useState<MyContract[]>([]);
    const [visitors, setVisitors] = useState<WalkInPass[]>([]);
    const [loading, setLoading] = useState(true);
    const [loadFailed, setLoadFailed] = useState(false);
    const [visitorsError, setVisitorsError] = useState<string | null>(null);

    const [statusFilter, setStatusFilter] = useState<"" | GatePassStatus>("");
    const [search, setSearch] = useState("");
    const [viewMode, setViewMode] = useState<"table" | "cards">("table");
    const [page, setPage] = useState(1);
    const [perPage, setPerPage] = useState(10);

    const [formOpen, setFormOpen] = useState(false);
    const [detail, setDetail] = useState<GatePass | null>(null);
    const [toCancel, setToCancel] = useState<GatePass | null>(null);
    const [cancelling, setCancelling] = useState(false);
    const [actionError, setActionError] = useState<string | null>(null);
    const [deciding, setDeciding] = useState<string | null>(null);

    const loadVisitors = useCallback(async () => {
        setVisitorsError(null);
        try {
            const fetched = await fetchResidentApprovals();
            setVisitors(fetched);
        } catch (err) {
            // A tenant with no tenant profile answers 404 here; that is "no visitors", not a failure.
            if (err instanceof ApiError && err.status === 404) setVisitors([]);
            else setVisitorsError(t("visitorsLoadError"));
        }
    }, [t]);

    const load = useCallback(async () => {
        setLoading(true);
        setLoadFailed(false);
        try {
            const [mine, leasesRes] = await Promise.all([fetchMyPasses(), fetch("/api/proxy/v1/leases/my-leases")]);
            setPasses(mine);
            setContracts(leasesRes.ok ? await leasesRes.json() : []);
        } catch {
            setLoadFailed(true);
        } finally {
            setLoading(false);
        }
        await loadVisitors();
    }, [loadVisitors]);

    useEffect(() => {
        if (sessionStatus === "authenticated") load();
    }, [sessionStatus, load]);

    const unitLabel = useCallback((unitId: string) => {
        const c = contracts.find(x => x.unitId === unitId);
        return c?.unitIdentifier ?? "—";
    }, [contracts]);

    const filtered = useMemo(() => {
        const q = search.trim().toLowerCase();
        return passes.filter(p => (!statusFilter || p.status === statusFilter)
            && (!q || p.guestName.toLowerCase().includes(q) || p.guestPhone.includes(q)));
    }, [passes, statusFilter, search]);
    const visible = pageOf(filtered, page, perPage);

    const doCancel = async () => {
        if (!toCancel) return;
        setCancelling(true);
        setActionError(null);
        try {
            const updated = await cancelPass(toCancel.id);
            setPasses(ps => ps.map(p => (p.id === updated.id ? updated : p)));
            if (detail?.id === updated.id) setDetail(updated);
            setToCancel(null);
        } catch (err) {
            setActionError(errorText(err, t("cancelError")));
            setToCancel(null);
        } finally {
            setCancelling(false);
        }
    };

    const decide = async (visitor: WalkInPass, approved: boolean) => {
        setDeciding(visitor.id);
        setVisitorsError(null);
        try {
            await decideResidentApproval(visitor.id, approved);
            setVisitors(vs => vs.filter(v => v.id !== visitor.id));
        } catch (err) {
            setVisitorsError(errorText(err, t("decideError")));
            await loadVisitors();
        } finally {
            setDeciding(null);
        }
    };

    if (loading) {
        return (
            <div className="flex items-center justify-center py-24">
                <Loader2 className="w-6 h-6 animate-spin text-primary opacity-60" />
            </div>
        );
    }
    if (loadFailed) return <LoadFailedState onRetry={load} message={t("myPassesLoadError")} />;

    return (
        <div>
            <div className="flex items-start justify-between mb-5 gap-3 flex-wrap">
                <div>
                    <h1 className="text-lg font-bold text-foreground">{t("tenantTitle")}</h1>
                    <p className="text-xs text-muted mt-0.5">{t("tenantDescription")}</p>
                </div>
                <button type="button" className={primaryButton} onClick={() => setFormOpen(true)} data-testid="gatepass-new">
                    <Plus size={14} /> {t("requestPass")}
                </button>
            </div>

            <div className="flex gap-1 border-b border-border mb-5" role="tablist">
                {(["passes", "visitors"] as TabId[]).map(id => (
                    <button
                        key={id}
                        type="button"
                        role="tab"
                        aria-selected={tab === id}
                        data-testid={`gatepass-tab-${id}`}
                        onClick={() => setTab(id)}
                        className={cn(
                            "cursor-pointer px-3 py-2 text-xs font-semibold border-b-2 -mb-px whitespace-nowrap",
                            tab === id ? "border-primary text-foreground" : "border-transparent text-muted hover:text-foreground",
                        )}
                    >
                        {id === "passes" ? t("tabMyPasses") : t("tabVisitors")}
                        {id === "visitors" && visitors.length > 0 && (
                            <span className="ms-1.5 inline-block min-w-4 px-1 rounded-full bg-warning/15 text-warning text-[10px]">{visitors.length}</span>
                        )}
                    </button>
                ))}
            </div>

            {tab === "passes" ? (
                <>
                    <ErrorLine message={actionError} />
                    <div className="flex flex-wrap items-center gap-2 mb-4 mt-2">
                        <div className="relative flex-1 min-w-[180px] max-w-xs">
                            <Search size={14} className="absolute start-3 top-1/2 -translate-y-1/2 text-muted" />
                            <input
                                type="search"
                                value={search}
                                onChange={e => { setSearch(e.target.value); setPage(1); }}
                                placeholder={t("searchGuest")}
                                aria-label={t("searchGuest")}
                                className={cn(inputClass, "ps-9 text-xs")}
                            />
                        </div>
                        <select
                            aria-label={t("statusFilter")}
                            value={statusFilter}
                            onChange={e => { setStatusFilter(e.target.value as "" | GatePassStatus); setPage(1); }}
                            className={cn(inputClass, "w-auto text-xs")}
                            data-testid="gatepass-status-filter"
                        >
                            <option value="">{t("allStatuses")}</option>
                            {STATUSES.map(s => <option key={s} value={s}>{t(`status.${s}`)}</option>)}
                        </select>
                        <ViewToggle value={viewMode} onChange={setViewMode} />
                    </div>

                    {filtered.length === 0 ? (
                        <div className="bg-surface rounded-xl border border-border text-center py-14 text-muted" data-testid="gatepass-empty">
                            <ShieldCheck size={28} className="mx-auto mb-3 opacity-40" />
                            <p className="text-xs">{passes.length === 0 ? t("noPassesYet") : t("noMatches")}</p>
                        </div>
                    ) : viewMode === "table" ? (
                        <div className="bg-surface rounded-xl border border-border overflow-x-auto">
                            <table className="w-full" data-testid="gatepass-table">
                                <thead>
                                    <tr className="bg-input/50">
                                        {[t("colGuestName"), t("colUnit"), t("colPassType"), t("colValidity"), t("colStatus"), ""].map((h, i) => (
                                            <th key={i} className="px-4 py-2.5 text-[11px] font-semibold text-muted uppercase tracking-wider text-start whitespace-nowrap">{h}</th>
                                        ))}
                                    </tr>
                                </thead>
                                <tbody>
                                    {visible.map(p => (
                                        <tr key={p.id} className="border-b border-border hover:bg-input/30">
                                            <td className="px-4 py-2.5 text-xs">
                                                <button type="button" onClick={() => setDetail(p)} className="font-medium text-foreground hover:text-primary cursor-pointer text-start">
                                                    {p.guestName}
                                                </button>
                                                <div className="text-[11px] text-muted" dir="ltr">{p.guestPhone}</div>
                                            </td>
                                            <td className="px-4 py-2.5 text-xs text-muted">{unitLabel(p.unitId)}</td>
                                            <td className="px-4 py-2.5 text-xs text-muted whitespace-nowrap">{t(p.passType)}</td>
                                            <td className="px-4 py-2.5 text-xs text-muted whitespace-nowrap tabular-nums">
                                                {dateTime(p.validFrom)}<br />{dateTime(p.validTo)}
                                            </td>
                                            <td className="px-4 py-2.5"><StatusBadge status={p.status} /></td>
                                            <td className="px-4 py-2.5 text-end whitespace-nowrap">
                                                <button type="button" onClick={() => setDetail(p)} className="text-xs text-primary font-semibold cursor-pointer me-3">{t("view")}</button>
                                                {CANCELLABLE.has(p.status) && (
                                                    <button type="button" onClick={() => setToCancel(p)} className="text-xs text-error font-semibold cursor-pointer" data-testid={`cancel-${p.id}`}>
                                                        {t("cancelPass")}
                                                    </button>
                                                )}
                                            </td>
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                        </div>
                    ) : (
                        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
                            {visible.map(p => (
                                <div key={p.id} className="bg-surface rounded-xl border border-border p-4">
                                    <div className="flex items-start justify-between gap-2 mb-2">
                                        <div>
                                            <p className="text-sm font-semibold text-foreground">{p.guestName}</p>
                                            <p className="text-[11px] text-muted" dir="ltr">{p.guestPhone}</p>
                                        </div>
                                        <StatusBadge status={p.status} />
                                    </div>
                                    <p className="text-xs text-muted">{t("colUnit")}: {unitLabel(p.unitId)} · {t(p.passType)}</p>
                                    <p className="text-[11px] text-muted mt-1 tabular-nums">{dateTime(p.validFrom)} → {dateTime(p.validTo)}</p>
                                    <div className="flex gap-2 mt-3">
                                        <button type="button" onClick={() => setDetail(p)} className={secondaryButton}>{t("view")}</button>
                                        {CANCELLABLE.has(p.status) && (
                                            <button type="button" onClick={() => setToCancel(p)} className={cn(secondaryButton, "text-error")}>{t("cancelPass")}</button>
                                        )}
                                    </div>
                                </div>
                            ))}
                        </div>
                    )}
                    {filtered.length > 0 && (
                        <div className="mt-2">
                            <Pagination
                                currentPage={page}
                                totalItems={filtered.length}
                                itemsPerPage={perPage}
                                onPageChange={setPage}
                                onItemsPerPageChange={n => { setPerPage(n); setPage(1); }}
                            />
                        </div>
                    )}
                </>
            ) : (
                <VisitorsTab visitors={visitors} error={visitorsError} deciding={deciding} onDecide={decide} onRefresh={loadVisitors} />
            )}

            <SideDrawer open={formOpen} onClose={() => setFormOpen(false)} title={t("newPassTitle")} testId="gatepass-form-drawer" closeLabel={t("close")}>
                {formOpen && (
                    <PassForm
                        contracts={contracts}
                        onCreated={p => {
                            setPasses(ps => [p, ...ps]);
                            setFormOpen(false);
                            setDetail(p);
                        }}
                    />
                )}
            </SideDrawer>

            <SideDrawer open={!!detail} onClose={() => setDetail(null)} title={t("passDetailTitle")} testId="gatepass-detail-drawer" closeLabel={t("close")}>
                {detail && <PassDetail pass={detail} unit={unitLabel(detail.unitId)} onCancel={() => setToCancel(detail)} />}
            </SideDrawer>

            <ConfirmDialog
                isOpen={!!toCancel}
                onClose={() => setToCancel(null)}
                onConfirm={doCancel}
                title={t("cancelConfirmTitle")}
                description={toCancel ? t("cancelConfirmBody", { guest: toCancel.guestName }) : undefined}
                confirmText={t("cancelPass")}
                cancelText={t("keepPass")}
                isDestructive
                isLoading={cancelling}
                confirmTestId="gatepass-cancel-confirm"
            />
        </div>
    );
}

function PassDetail({ pass, unit, onCancel }: { pass: GatePass; unit: string; onCancel: () => void }) {
    const t = useTranslations("GatePass");
    const { dateTime } = useDateTime();
    // The code opens the gate only while the pass is ACTIVE; a pending pass has one
    // already, but showing it as if usable would invite a visitor who is turned away.
    const live = pass.status === "ACTIVE";
    return (
        <div className="space-y-4" data-testid="gatepass-detail">
            <div className="flex items-center justify-between gap-2">
                <div>
                    <p className="text-base font-bold text-foreground">{pass.guestName}</p>
                    <p className="text-xs text-muted" dir="ltr">{pass.guestPhone}</p>
                </div>
                <StatusBadge status={pass.status} />
            </div>
            {pass.numericCode && pass.status !== "CANCELLED" && pass.status !== "EXPIRED" && pass.status !== "USED" && (
                <div className={cn("rounded-xl border p-4 text-center", live ? "border-success/30 bg-success/5" : "border-warning/30 bg-warning/5")}>
                    <p className="text-[11px] font-semibold text-muted uppercase tracking-wider mb-1 flex items-center justify-center gap-1.5">
                        <KeyRound size={12} /> {t("gateCode")}
                    </p>
                    <p className="text-3xl font-bold tracking-[0.3em] text-foreground tabular-nums" dir="ltr" data-testid="gatepass-code">{pass.numericCode}</p>
                    <p className="text-[11px] text-muted mt-2">{live ? t("codeLiveHint") : t("codePendingHint")}</p>
                </div>
            )}
            <dl className="grid grid-cols-2 gap-3 text-xs">
                <Field label={t("colUnit")} value={unit} />
                <Field label={t("colPassType")} value={t(pass.passType)} />
                <Field label={t("validFrom")} value={dateTime(pass.validFrom)} />
                <Field label={t("validTo")} value={dateTime(pass.validTo)} />
                <Field label={t("colPurpose")} value={pass.purpose || "—"} />
                <Field label={t("colVehicle")} value={pass.vehicleNumber || "—"} ltr />
            </dl>
            <p className="text-[11px] text-muted">{t("shareOnlyWithVisitor")}</p>
            {CANCELLABLE.has(pass.status) && (
                <button type="button" onClick={onCancel} className={cn(secondaryButton, "text-error w-full")}>
                    <X size={14} /> {t("cancelPass")}
                </button>
            )}
        </div>
    );
}

function Field({ label, value, ltr }: { label: string; value: string; ltr?: boolean }) {
    return (
        <div>
            <dt className="text-[10px] font-semibold text-muted uppercase tracking-wider">{label}</dt>
            <dd className="text-foreground mt-0.5" dir={ltr ? "ltr" : undefined}>{value}</dd>
        </div>
    );
}

function PassForm({ contracts, onCreated }: { contracts: MyContract[]; onCreated: (p: GatePass) => void }) {
    const t = useTranslations("GatePass");
    const today = businessTodayIso();
    const current = useMemo(() => currentContracts(contracts, today), [contracts, today]);

    const [unitId, setUnitId] = useState(current.length === 1 ? current[0].unitId : "");
    const [guestName, setGuestName] = useState("");
    const [guestPhone, setGuestPhone] = useState("");
    const [purpose, setPurpose] = useState("");
    const [vehicle, setVehicle] = useState("");
    const [passType, setPassType] = useState<GatePassType>("SINGLE_USE");
    const [visitDate, setVisitDate] = useState(today);
    const [fromTime, setFromTime] = useState("09:00");
    const [toTime, setToTime] = useState("18:00");
    const [firstDay, setFirstDay] = useState(today);
    const [lastDay, setLastDay] = useState("");
    const [error, setError] = useState<string | null>(null);
    const [submitting, setSubmitting] = useState(false);

    if (current.length === 0) {
        return (
            <div className="text-center py-8" data-testid="gatepass-no-contract">
                <ShieldCheck size={32} className="mx-auto mb-3 text-muted opacity-50" />
                <p className="text-sm font-semibold text-foreground">{t("noCurrentContractTitle")}</p>
                <p className="text-xs text-muted mt-2">{t("noCurrentContractBody")}</p>
            </div>
        );
    }

    const submit = async (e: React.FormEvent) => {
        e.preventDefault();
        setError(null);
        if (!unitId) return setError(t("errChooseUnit"));
        if (!guestName.trim()) return setError(t("errGuestName"));
        const phone = toE164(guestPhone);
        if (!phone) return setError(t("errPhone"));
        let validFrom: string;
        let validTo: string;
        if (passType === "SINGLE_USE") {
            if (!visitDate) return setError(t("errVisitDate"));
            if (toTime <= fromTime) return setError(t("errEndAfterStart"));
            validFrom = localInstant(visitDate, fromTime);
            validTo = localInstant(visitDate, toTime);
        } else {
            if (!firstDay || !lastDay) return setError(t("errRecurringDays"));
            if (lastDay < firstDay) return setError(t("errLastDayAfterFirst"));
            validFrom = localInstant(firstDay, "00:00");
            validTo = new Date(new Date(localInstant(lastDay, "23:59")).getTime() + 59_000).toISOString();
        }
        if (new Date(validTo).getTime() <= Date.now()) return setError(t("errWindowPast"));
        setSubmitting(true);
        try {
            const created = await createPass({
                unitId, guestName: guestName.trim(), guestPhone: phone,
                purpose: purpose.trim() || undefined, vehicleNumber: vehicle.trim() || undefined,
                passType, validFrom, validTo,
            });
            onCreated(created);
        } catch (err) {
            // 404 is the server's "that unit is not on a current contract of yours".
            setError(err instanceof ApiError && err.status === 404 ? t("errNotCurrentContract") : errorText(err, t("createError")));
        } finally {
            setSubmitting(false);
        }
    };

    return (
        <form onSubmit={submit} className="space-y-4" noValidate data-testid="gatepass-form">
            {current.length > 1 && (
                <div>
                    <label htmlFor="gp-unit" className={labelClass}>{t("colUnit")}</label>
                    <select id="gp-unit" value={unitId} onChange={e => setUnitId(e.target.value)} className={inputClass}>
                        <option value="">{t("chooseUnit")}</option>
                        {current.map(c => (
                            <option key={c.id} value={c.unitId}>{[c.unitIdentifier, c.propertyName].filter(Boolean).join(" · ")}</option>
                        ))}
                    </select>
                </div>
            )}
            {current.length === 1 && (
                <p className="text-xs text-muted">{t("forUnit", { unit: [current[0].unitIdentifier, current[0].propertyName].filter(Boolean).join(" · ") })}</p>
            )}
            <div>
                <label htmlFor="gp-name" className={labelClass}>{t("guestName")}</label>
                <input id="gp-name" value={guestName} maxLength={160} onChange={e => setGuestName(e.target.value)} className={inputClass} />
            </div>
            <div>
                <label htmlFor="gp-phone" className={labelClass}>{t("guestPhone")}</label>
                <input id="gp-phone" type="tel" dir="ltr" value={guestPhone} maxLength={32} placeholder="+971 50 123 4567" onChange={e => setGuestPhone(e.target.value)} className={inputClass} />
                <p className="text-[11px] text-muted mt-1">{t("phoneHint")}</p>
            </div>
            <div>
                <label htmlFor="gp-purpose" className={labelClass}>{t("purposeOptional")}</label>
                <input id="gp-purpose" value={purpose} maxLength={240} onChange={e => setPurpose(e.target.value)} className={inputClass} />
            </div>
            <div>
                <label htmlFor="gp-vehicle" className={labelClass}>{t("vehicleOptional")}</label>
                <input id="gp-vehicle" dir="ltr" value={vehicle} maxLength={32} onChange={e => setVehicle(e.target.value)} className={inputClass} />
            </div>
            <fieldset>
                <legend className={labelClass}>{t("colPassType")}</legend>
                <div className="grid grid-cols-2 gap-2">
                    {(["SINGLE_USE", "RECURRING"] as GatePassType[]).map(pt => (
                        <label key={pt} className={cn("cursor-pointer border rounded-lg px-3 py-2 text-xs", passType === pt ? "border-primary bg-primary/5 text-foreground" : "border-border text-muted")}>
                            <input type="radio" name="gp-type" value={pt} checked={passType === pt} onChange={() => setPassType(pt)} className="me-2" />
                            {pt === "SINGLE_USE" ? t("singleVisit") : t("RECURRING")}
                        </label>
                    ))}
                </div>
                <p className="text-[11px] text-muted mt-2">{passType === "SINGLE_USE" ? t("singleExplainer") : t("recurringExplainer")}</p>
            </fieldset>
            {passType === "SINGLE_USE" ? (
                <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
                    <div>
                        <label htmlFor="gp-date" className={labelClass}>{t("visitDate")}</label>
                        <input id="gp-date" type="date" min={today} value={visitDate} onChange={e => setVisitDate(e.target.value)} className={inputClass} />
                    </div>
                    <div>
                        <label htmlFor="gp-from" className={labelClass}>{t("from")}</label>
                        <input id="gp-from" type="time" value={fromTime} onChange={e => setFromTime(e.target.value)} className={inputClass} />
                    </div>
                    <div>
                        <label htmlFor="gp-until" className={labelClass}>{t("until")}</label>
                        <input id="gp-until" type="time" value={toTime} onChange={e => setToTime(e.target.value)} className={inputClass} />
                    </div>
                </div>
            ) : (
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                    <div>
                        <label htmlFor="gp-first" className={labelClass}>{t("firstDay")}</label>
                        <input id="gp-first" type="date" min={today} value={firstDay} onChange={e => setFirstDay(e.target.value)} className={inputClass} />
                    </div>
                    <div>
                        <label htmlFor="gp-last" className={labelClass}>{t("lastDay")}</label>
                        <input id="gp-last" type="date" min={firstDay || today} value={lastDay} onChange={e => setLastDay(e.target.value)} className={inputClass} />
                    </div>
                </div>
            )}
            <ErrorLine message={error} testId="gatepass-form-error" />
            <button type="submit" disabled={submitting} className={cn(primaryButton, "w-full")} data-testid="gatepass-submit">
                {submitting && <Loader2 size={14} className="animate-spin" />}
                {passType === "RECURRING" ? t("requestPass") : t("createPass")}
            </button>
        </form>
    );
}

function VisitorsTab({ visitors, error, deciding, onDecide, onRefresh }: {
    visitors: WalkInPass[];
    error: string | null;
    deciding: string | null;
    onDecide: (v: WalkInPass, approved: boolean) => void;
    onRefresh: () => void;
}) {
    const t = useTranslations("GatePass");
    const { dateTime } = useDateTime();
    return (
        <div className="space-y-3">
            <div className="flex items-center justify-between gap-2 flex-wrap">
                <p className="text-xs text-muted">{t("visitorsHint")}</p>
                <button type="button" onClick={onRefresh} className={secondaryButton}>{t("refresh")}</button>
            </div>
            <ErrorLine message={error} />
            {visitors.length === 0 ? (
                <div className="bg-surface rounded-xl border border-border text-center py-14 text-muted" data-testid="visitors-empty">
                    <ShieldCheck size={28} className="mx-auto mb-3 opacity-40" />
                    <p className="text-xs">{t("noVisitorsWaiting")}</p>
                </div>
            ) : (
                <div className="bg-surface rounded-xl border border-border overflow-x-auto">
                    <table className="w-full" data-testid="visitors-table">
                        <thead>
                            <tr className="bg-input/50">
                                {[t("colVisitor"), t("colVisitorType"), t("colUnit"), t("colPurpose"), t("colVehicle"), t("colWaitingUntil"), ""].map((h, i) => (
                                    <th key={i} className="px-4 py-2.5 text-[11px] font-semibold text-muted uppercase tracking-wider text-start whitespace-nowrap">{h}</th>
                                ))}
                            </tr>
                        </thead>
                        <tbody>
                            {visitors.map(v => (
                                <tr key={v.id} className="border-b border-border">
                                    <td className="px-4 py-2.5 text-xs">
                                        <div className="flex items-center gap-2">
                                            {v.guestPhotoUrl && (
                                                // eslint-disable-next-line @next/next/no-img-element
                                                <img src={`/api/proxy/v1/gatepass/walk-in/${v.id}/photo`} alt={t("visitorPhotoAlt", { name: v.guestName })} className="w-9 h-9 rounded-full object-cover border border-border" />
                                            )}
                                            <div>
                                                <p className="font-medium text-foreground">{v.guestName}</p>
                                                <p className="text-[11px] text-muted" dir="ltr">{v.guestPhone}</p>
                                            </div>
                                        </div>
                                    </td>
                                    <td className="px-4 py-2.5 text-xs text-muted">{v.visitorType ? t(`visitorType.${v.visitorType}`) : "—"}</td>
                                    <td className="px-4 py-2.5 text-xs text-muted">{v.unitNumber ?? "—"}</td>
                                    <td className="px-4 py-2.5 text-xs text-muted">{v.purpose || "—"}</td>
                                    <td className="px-4 py-2.5 text-xs text-muted" dir="ltr">{v.vehicleNumber || "—"}</td>
                                    <td className="px-4 py-2.5 text-xs text-muted whitespace-nowrap">{dateTime(v.validTo)}</td>
                                    <td className="px-4 py-2.5 whitespace-nowrap text-end">
                                        <button type="button" disabled={deciding === v.id} onClick={() => onDecide(v, true)} className={cn(primaryButton, "me-2")} data-testid={`visitor-approve-${v.id}`}>
                                            <Check size={13} /> {t("approve")}
                                        </button>
                                        <button type="button" disabled={deciding === v.id} onClick={() => onDecide(v, false)} className={cn(secondaryButton, "text-error")} data-testid={`visitor-reject-${v.id}`}>
                                            <X size={13} /> {t("reject")}
                                        </button>
                                    </td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>
            )}
        </div>
    );
}
