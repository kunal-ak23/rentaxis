"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { useTranslations } from "next-intl";
import { useSession } from "next-auth/react";
import { Check, Loader2, RefreshCw, Search, ShieldCheck, X } from "lucide-react";
import { cn } from "@/lib/utils";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Pagination } from "@/components/ui/Pagination";
import { LoadFailedState } from "@/components/ui/PageStates";
import {
    ErrorLine, GatePassPage, GatePassTabs, ViewToggle, inputClass, pageOf, primaryButton, secondaryButton, useDateTime,
} from "@/components/gatepass/shared";
import { decideApproval, errorText, fetchApprovals, type GatePassSummary, type GatePassType } from "@/lib/api/gatepass";

/**
 * Passes waiting for a decision — recurring passes a tenant raised for a maid,
 * a driver or a regular visitor. One queue for every approver; the server
 * decides whose rows it is: an admin sees the organisation, a property manager
 * their buildings, a guard the properties they are posted to. Walk-ins never
 * appear here — the resident decides those.
 */
export default function GatePassApprovalsPage() {
    return (
        <GatePassPage permission="canApproveGatePasses">
            <Approvals />
        </GatePassPage>
    );
}

type Pending = { pass: GatePassSummary; approved: boolean };

function Approvals() {
    const t = useTranslations("GatePass");
    const { status: sessionStatus } = useSession();
    const { dateTime } = useDateTime();

    const [rows, setRows] = useState<GatePassSummary[]>([]);
    const [loading, setLoading] = useState(true);
    const [loadFailed, setLoadFailed] = useState(false);
    const [actionError, setActionError] = useState<string | null>(null);
    const [notice, setNotice] = useState<string | null>(null);
    const [pending, setPending] = useState<Pending | null>(null);
    const [deciding, setDeciding] = useState(false);

    const [propertyId, setPropertyId] = useState("");
    const [passType, setPassType] = useState<"" | GatePassType>("");
    const [search, setSearch] = useState("");
    const [viewMode, setViewMode] = useState<"table" | "cards">("table");
    const [page, setPage] = useState(1);
    const [perPage, setPerPage] = useState(10);

    const load = useCallback(async () => {
        setLoading(true);
        setLoadFailed(false);
        try {
            const fetched = await fetchApprovals();
            setRows(fetched);
        } catch {
            setLoadFailed(true);
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => {
        if (sessionStatus === "authenticated") load();
    }, [sessionStatus, load]);

    const properties = useMemo(() => {
        const m = new Map<string, string>();
        for (const r of rows) m.set(r.propertyId, r.propertyName ?? r.propertyId.slice(0, 8));
        return [...m.entries()].sort((a, b) => a[1].localeCompare(b[1]));
    }, [rows]);

    const filtered = useMemo(() => {
        const q = search.trim().toLowerCase();
        return rows.filter(r => (!propertyId || r.propertyId === propertyId)
            && (!passType || r.passType === passType)
            && (!q || r.guestName.toLowerCase().includes(q) || (r.unitNumber ?? "").toLowerCase().includes(q) || r.guestPhone.includes(q)));
    }, [rows, propertyId, passType, search]);
    // Clamped: deciding the last row of the last page must not leave an empty page.
    const shownPage = Math.min(page, Math.max(1, Math.ceil(filtered.length / perPage)));
    const visible = pageOf(filtered, shownPage, perPage);

    const confirm = async () => {
        if (!pending) return;
        setDeciding(true);
        setActionError(null);
        setNotice(null);
        try {
            const decided = await decideApproval(pending.pass.id, pending.approved);
            setRows(rs => rs.filter(r => r.id !== decided.id));
            setNotice(pending.approved ? t("approvedNotice", { guest: decided.guestName }) : t("rejectedNotice", { guest: decided.guestName }));
        } catch (err) {
            setActionError(errorText(err, t("decideError"), t));
            // Someone else may have decided it first; show the queue as it now stands.
            await load();
        } finally {
            setDeciding(false);
            setPending(null);
        }
    };

    const actions = (r: GatePassSummary) => (
        <div className="flex gap-2 justify-end">
            <button type="button" className={primaryButton} onClick={() => setPending({ pass: r, approved: true })} data-testid={`approve-${r.id}`}>
                <Check size={13} /> {t("approve")}
            </button>
            <button type="button" className={cn(secondaryButton, "text-error")} onClick={() => setPending({ pass: r, approved: false })} data-testid={`reject-${r.id}`}>
                <X size={13} /> {t("reject")}
            </button>
        </div>
    );

    return (
        <div>
            <GatePassTabs />
            <div className="flex items-start justify-between mb-5 gap-3 flex-wrap">
                <div>
                    <h1 className="text-lg font-bold text-foreground">{t("approvalsTitle")}</h1>
                    <p className="text-xs text-muted mt-0.5">{t("approvalsDescription")}</p>
                </div>
                <button type="button" onClick={load} className={secondaryButton}><RefreshCw size={13} /> {t("refresh")}</button>
            </div>

            {loading ? (
                <div className="flex items-center justify-center py-24"><Loader2 className="w-6 h-6 animate-spin text-primary opacity-60" /></div>
            ) : loadFailed ? (
                <LoadFailedState onRetry={load} message={t("approvalsLoadError")} />
            ) : (
                <>
                    <div className="space-y-2 mb-3">
                        <ErrorLine message={actionError} />
                        {notice && <p role="status" className="text-xs text-success bg-success/5 border border-success/20 rounded-lg px-3 py-2">{notice}</p>}
                    </div>
                    <div className="flex flex-wrap items-center gap-2 mb-4">
                        <div className="relative flex-1 min-w-[180px] max-w-xs">
                            <Search size={14} className="absolute start-3 top-1/2 -translate-y-1/2 text-muted" />
                            <input type="search" value={search} placeholder={t("searchGuestOrUnit")} aria-label={t("searchGuestOrUnit")}
                                onChange={e => { setSearch(e.target.value); setPage(1); }} className={cn(inputClass, "ps-9 text-xs")} />
                        </div>
                        <select aria-label={t("property")} value={propertyId} onChange={e => { setPropertyId(e.target.value); setPage(1); }}
                            className={cn(inputClass, "w-auto text-xs")} data-testid="approvals-property-filter">
                            <option value="">{t("allProperties")}</option>
                            {properties.map(([id, name]) => <option key={id} value={id}>{name}</option>)}
                        </select>
                        <select aria-label={t("colPassType")} value={passType} onChange={e => { setPassType(e.target.value as "" | GatePassType); setPage(1); }}
                            className={cn(inputClass, "w-auto text-xs")} data-testid="approvals-type-filter">
                            <option value="">{t("allPassTypes")}</option>
                            <option value="SINGLE_USE">{t("SINGLE_USE")}</option>
                            <option value="RECURRING">{t("RECURRING")}</option>
                        </select>
                        <ViewToggle value={viewMode} onChange={setViewMode} />
                        <span className="text-xs text-muted tabular-nums">{t("waitingCount", { count: filtered.length })}</span>
                    </div>

                    {filtered.length === 0 ? (
                        <div className="bg-surface rounded-xl border border-border text-center py-14 text-muted" data-testid="approvals-empty">
                            <ShieldCheck size={28} className="mx-auto mb-3 opacity-40" />
                            <p className="text-xs">{rows.length === 0 ? t("noApprovals") : t("noMatches")}</p>
                        </div>
                    ) : viewMode === "table" ? (
                        <div className="bg-surface rounded-xl border border-border overflow-x-auto">
                            <table className="w-full" data-testid="approvals-table">
                                <thead>
                                    <tr className="bg-input/50">
                                        {[t("colGuestName"), t("colProperty"), t("colUnit"), t("colPassType"), t("colValidity"), t("colVehicle"), t("colPurpose"), t("colRequestedAt"), ""].map((h, i) => (
                                            <th key={i} className="px-4 py-2.5 text-[11px] font-semibold text-muted uppercase tracking-wider text-start whitespace-nowrap">{h}</th>
                                        ))}
                                    </tr>
                                </thead>
                                <tbody>
                                    {visible.map(r => (
                                        <tr key={r.id} className="border-b border-border hover:bg-input/30">
                                            <td className="px-4 py-2.5 text-xs">
                                                <p className="font-medium text-foreground">{r.guestName}</p>
                                                <p className="text-[11px] text-muted" dir="ltr">{r.guestPhone}</p>
                                            </td>
                                            <td className="px-4 py-2.5 text-xs text-muted">{r.propertyName ?? "—"}</td>
                                            <td className="px-4 py-2.5 text-xs text-muted">{r.unitNumber ?? "—"}</td>
                                            <td className="px-4 py-2.5 text-xs text-muted whitespace-nowrap">{t(r.passType)}</td>
                                            <td className="px-4 py-2.5 text-xs text-muted whitespace-nowrap tabular-nums">{dateTime(r.validFrom)}<br />{dateTime(r.validTo)}</td>
                                            <td className="px-4 py-2.5 text-xs text-muted" dir="ltr">{r.vehicleNumber || "—"}</td>
                                            <td className="px-4 py-2.5 text-xs text-muted max-w-[180px] truncate" title={r.purpose ?? undefined}>{r.purpose || "—"}</td>
                                            <td className="px-4 py-2.5 text-xs text-muted whitespace-nowrap">{dateTime(r.createdAt)}</td>
                                            <td className="px-4 py-2.5">{actions(r)}</td>
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                        </div>
                    ) : (
                        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
                            {visible.map(r => (
                                <div key={r.id} className="bg-surface rounded-xl border border-border p-4 space-y-1.5">
                                    <p className="text-sm font-semibold text-foreground">{r.guestName}</p>
                                    <p className="text-[11px] text-muted" dir="ltr">{r.guestPhone}</p>
                                    <p className="text-xs text-muted">{[r.propertyName, r.unitNumber].filter(Boolean).join(" · ")} · {t(r.passType)}</p>
                                    <p className="text-[11px] text-muted tabular-nums">{dateTime(r.validFrom)} – {dateTime(r.validTo)}</p>
                                    {r.purpose && <p className="text-[11px] text-muted">{r.purpose}</p>}
                                    <div className="pt-2">{actions(r)}</div>
                                </div>
                            ))}
                        </div>
                    )}
                    {filtered.length > 0 && (
                        <div className="mt-2">
                            <Pagination currentPage={shownPage} totalItems={filtered.length} itemsPerPage={perPage} onPageChange={setPage}
                                onItemsPerPageChange={n => { setPerPage(n); setPage(1); }} />
                        </div>
                    )}
                </>
            )}

            <ConfirmDialog
                isOpen={!!pending}
                onClose={() => setPending(null)}
                onConfirm={confirm}
                title={pending?.approved ? t("approveConfirmTitle") : t("rejectConfirmTitle")}
                description={pending ? t(pending.approved ? "approveConfirmBody" : "rejectConfirmBody", {
                    guest: pending.pass.guestName, unit: pending.pass.unitNumber ?? "—",
                }) : undefined}
                confirmText={pending?.approved ? t("approve") : t("reject")}
                cancelText={t("back")}
                isDestructive={pending ? !pending.approved : false}
                isLoading={deciding}
                confirmTestId="approval-confirm"
            />
        </div>
    );
}
