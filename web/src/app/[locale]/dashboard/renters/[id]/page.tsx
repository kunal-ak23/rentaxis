"use client";

import { useEffect, useMemo, useState } from "react";
import { useParams } from "next/navigation";
import { useSession } from "next-auth/react";
import { useLocale, useTranslations } from "next-intl";
import { Link } from "@/i18n/routing";
import { ArrowLeft, BookOpen, Loader2, Mail, Phone, Languages } from "lucide-react";
import { cn } from "@/lib/utils";
import { hasPermission, type UserRole } from "@/lib/rbac";
import { formatCurrency } from "@/lib/format";
import { fmtIsoDate } from "@/components/leases/leaseMath";
import { ResendInviteButton } from "@/components/users/ResendInviteButton";
import { ApiError, leaseApi, type Cheque, type LeaseStatus } from "@/lib/api/leasing";
import { chequeSummary } from "@/components/renters/chequeSummary";

/**
 * One renter, for staff (#8): their profile, their contracts, the cheques on
 * those contracts, their tickets, and the way into their ledger.
 *
 * Built on endpoints that already existed plus one: `GET /renters/{id}`,
 * `GET /renters/{id}/leases` (tenant-scoped, and narrowed to a property
 * manager's own buildings by LeaseAccessPolicy), `GET /leases/{id}/cheques`
 * per contract, and the ticket list the caller can already see.
 */

type Renter = {
    id: string;
    nameEn: string;
    nameAr: string | null;
    email: string | null;
    phone: string | null;
    primaryLanguage: string | null;
    userId: string | null;
    invitePending?: boolean;
    inviteExpiresAt?: string | null;
};

type RenterLease = {
    id: string;
    unitIdentifier: string | null;
    propertyName: string | null;
    startDate: string;
    endDate: string;
    status: LeaseStatus;
    rentAmount: number | null;
    displayContractNumber?: string | null;
};

type Ticket = {
    id: string;
    reference?: string | null;
    title: string;
    status: string;
    priority: string | null;
    leaseId: string | null;
    reportedBy: string | null;
    onBehalfOfRenterId?: string | null;
    reportedDate: string | null;
    createdAt: string;
};

const LEASE_STATUS_COLORS: Record<string, string> = {
    ACTIVE: "bg-success/10 text-success border-success/20",
    DRAFT: "bg-input text-muted border-border",
    PENDING_SIGNATURE: "bg-warning/10 text-warning border-warning/20",
    TERMINATED: "bg-error/10 text-error border-error/20",
    EXPIRED: "bg-warning/10 text-warning border-warning/20",
    CLOSED: "bg-input text-muted border-border",
    NOTICE_GIVEN: "bg-warning/10 text-warning border-warning/20",
    RENEWED: "bg-info/10 text-info border-info/20",
};

const TICKET_STATUSES = ["OPEN", "ASSIGNED", "IN_PROGRESS", "RESOLVED", "CLOSED", "REOPENED"];

export default function RenterDetailPage() {
    const params = useParams();
    const renterId = params.id as string;
    const locale = useLocale();
    const t = useTranslations("RenterDetail");
    const tLeasing = useTranslations("Leasing");
    const tCheques = useTranslations("Cheques");
    const tInv = useTranslations("Invites");
    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;
    const canView = hasPermission(userRole, "canViewLeases");
    const canManageRenters = hasPermission(userRole, "canManageRenters");
    const canSeeLedger = hasPermission(userRole, "canAccessFinance");

    const [renter, setRenter] = useState<Renter | null>(null);
    const [leases, setLeases] = useState<RenterLease[]>([]);
    const [cheques, setCheques] = useState<Cheque[]>([]);
    const [tickets, setTickets] = useState<Ticket[]>([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        if (userRole && !canView) {
            setLoading(false);
            return;
        }
        let cancelled = false;
        (async () => {
            try {
                const r = await fetch(`/api/proxy/v1/renters/${encodeURIComponent(renterId)}`);
                if (!r.ok) {
                    if (!cancelled) setError(r.status === 403 ? t("accessDenied") : t("notFound"));
                    return;
                }
                const loaded: Renter = await r.json();
                if (cancelled) return;
                setRenter(loaded);

                const lr = await fetch(`/api/proxy/v1/renters/${encodeURIComponent(renterId)}/leases`);
                const ls: RenterLease[] = lr.ok ? await lr.json() : [];
                if (cancelled) return;
                setLeases(ls);

                const perLease = await Promise.all(
                    ls.map(l => leaseApi.cheques(l.id).catch(() => [] as Cheque[])),
                );
                if (!cancelled) setCheques(perLease.flat());

                // The ticket list is already scoped to what the caller may see;
                // a ticket is this renter's when they reported it, it was raised
                // on one of their contracts, or it was logged on their behalf.
                const tr = await fetch("/api/proxy/v1/tickets");
                if (tr.ok && !cancelled) {
                    const leaseIds = new Set(ls.map(l => l.id));
                    const all: Ticket[] = await tr.json();
                    setTickets(all.filter(tk =>
                        (loaded.userId && tk.reportedBy === loaded.userId)
                        || (tk.leaseId && leaseIds.has(tk.leaseId))
                        || tk.onBehalfOfRenterId === loaded.id));
                }
            } catch (e) {
                if (!cancelled) setError(e instanceof ApiError ? e.message : t("notFound"));
            } finally {
                if (!cancelled) setLoading(false);
            }
        })();
        return () => {
            cancelled = true;
        };
    }, [renterId, userRole, canView, t]);

    const summary = useMemo(() => chequeSummary(cheques), [cheques]);
    const unitOf = useMemo(() => new Map(leases.map(l => [l.id, l.unitIdentifier ?? "—"])), [leases]);
    const activeLeases = leases.filter(l => l.status === "ACTIVE" || l.status === "NOTICE_GIVEN").length;

    if (userRole && !canView) {
        return <div className="text-center py-24"><p className="text-sm text-muted">{t("accessDenied")}</p></div>;
    }
    if (loading) {
        return (
            <div className="flex items-center justify-center py-24">
                <Loader2 className="w-6 h-6 animate-spin text-primary opacity-60" />
            </div>
        );
    }
    if (!renter) {
        return (
            <div className="text-center py-24">
                <p className="text-sm text-muted">{error ?? t("notFound")}</p>
                <Link href="/dashboard/renters" className="text-xs text-primary font-semibold mt-2 inline-block">
                    {t("backToRenters")}
                </Link>
            </div>
        );
    }

    const displayName = locale === "ar" && renter.nameAr ? renter.nameAr : renter.nameEn;
    const otherName = locale === "ar" ? renter.nameEn : renter.nameAr;
    const inviteExpired = !!renter.inviteExpiresAt && new Date(renter.inviteExpiresAt).getTime() < Date.now();
    const th = "px-4 py-3 text-start text-[11px] font-semibold text-muted uppercase tracking-wider";
    const td = "px-4 py-3 text-sm text-foreground";

    return (
        <div className="flex flex-col gap-6" data-testid="renter-detail">
            {/* ── Header ─────────────────────────────────────────── */}
            <div className="flex items-start gap-5 flex-wrap">
                <Link href="/dashboard/renters" aria-label={t("backToRenters")} className="p-2 rounded-lg hover:bg-input transition-colors text-muted hover:text-foreground">
                    <ArrowLeft size={18} className="rtl:rotate-180" />
                </Link>
                <div className="flex-1 min-w-[240px]">
                    <div className="flex items-center gap-3 flex-wrap">
                        <h1 className="mb-0 font-serif text-[26px] font-semibold tracking-tight">{displayName}</h1>
                        {renter.invitePending && (
                            <span data-testid="renter-invite-status" className="px-2.5 py-1 rounded-lg text-[10px] font-semibold border bg-warning/10 text-warning border-warning/20">
                                {inviteExpired ? tInv("expired") : tInv("pending")}
                            </span>
                        )}
                    </div>
                    {otherName && <p className="text-sm text-muted">{otherName}</p>}
                    <div className="flex items-center gap-4 flex-wrap mt-2 text-xs text-muted">
                        <span className="flex items-center gap-1.5"><Mail size={13} /> <span dir="ltr">{renter.email || t("noEmail")}</span></span>
                        <span className="flex items-center gap-1.5"><Phone size={13} /> <span dir="ltr">{renter.phone || t("noPhone")}</span></span>
                        {renter.primaryLanguage && (
                            <span className="flex items-center gap-1.5"><Languages size={13} /> {renter.primaryLanguage}</span>
                        )}
                    </div>
                </div>
                <div className="flex items-center gap-2 flex-wrap" data-testid="renter-actions">
                    {canManageRenters && renter.invitePending && renter.userId && (
                        <ResendInviteButton userId={renter.userId} />
                    )}
                    {canSeeLedger && (
                        <Link
                            href={`/dashboard/finance/tenant-ledger?renterId=${renter.id}`}
                            data-testid="renter-ledger"
                            className="flex items-center gap-2 bg-input text-foreground border border-border px-4 py-2 rounded-lg text-xs font-semibold hover:bg-border transition-all"
                        >
                            <BookOpen size={14} /> {t("ledger")}
                        </Link>
                    )}
                </div>
            </div>

            {/* ── Summary ────────────────────────────────────────── */}
            <div className="grid grid-cols-2 md:grid-cols-5 gap-3" data-testid="renter-summary">
                {[
                    { label: t("activeContracts"), value: String(activeLeases) },
                    { label: t("chequesTotal"), value: formatCurrency(summary.total) },
                    { label: t("chequesCleared"), value: formatCurrency(summary.cleared) },
                    { label: t("chequesOutstanding"), value: formatCurrency(summary.outstanding) },
                    { label: t("chequesBounced"), value: String(summary.bounced) },
                ].map(k => (
                    <div key={k.label} className="bg-surface border border-border rounded-xl p-4">
                        <p className="text-[10px] font-semibold text-muted uppercase tracking-wider mb-1">{k.label}</p>
                        <p className="text-base font-semibold text-foreground tabular-nums" dir="ltr">{k.value}</p>
                    </div>
                ))}
            </div>

            {/* ── Contracts ──────────────────────────────────────── */}
            <section>
                <h2 className="text-sm font-bold text-foreground mb-3">{t("contracts")}</h2>
                {leases.length === 0 ? (
                    <p className="text-xs text-muted bg-surface border border-dashed border-border rounded-xl p-6 text-center">{t("noContracts")}</p>
                ) : (
                    <div className="bg-surface rounded-xl border border-border overflow-x-auto">
                        <table className="w-full" data-testid="renter-leases">
                            <thead><tr className="bg-input/50">
                                <th className={th}>{t("contract")}</th>
                                <th className={th}>{t("unit")}</th>
                                <th className={th}>{t("term")}</th>
                                <th className={th}>{t("status")}</th>
                                <th className={cn(th, "text-end")}>{t("rent")}</th>
                            </tr></thead>
                            <tbody>
                                {leases.map(l => (
                                    <tr key={l.id} className="border-b border-border hover:bg-input/30 transition-colors">
                                        <td className={td}>
                                            <Link href={`/dashboard/leases/${l.id}`} className="text-primary font-semibold hover:underline">
                                                {l.displayContractNumber || l.id.slice(0, 8)}
                                            </Link>
                                        </td>
                                        <td className={td}>
                                            <div className="font-medium">{l.unitIdentifier ?? "—"}</div>
                                            <div className="text-[10px] text-muted">{l.propertyName}</div>
                                        </td>
                                        <td className={td}>{fmtIsoDate(l.startDate, locale)} – {fmtIsoDate(l.endDate, locale)}</td>
                                        <td className={td}>
                                            <span className={cn("px-2 py-0.5 rounded-lg text-[10px] font-semibold border", LEASE_STATUS_COLORS[l.status] ?? "bg-input text-muted border-border")}>
                                                {tLeasing(`leaseStatus.${l.status}`)}
                                            </span>
                                        </td>
                                        <td className={cn(td, "text-end tabular-nums")} dir="ltr">{l.rentAmount != null ? formatCurrency(l.rentAmount) : "—"}</td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                )}
            </section>

            {/* ── Cheques ────────────────────────────────────────── */}
            <section>
                <h2 className="text-sm font-bold text-foreground mb-3">
                    {t("cheques")} <span className="text-muted font-medium">· {t("chequeCount", { count: summary.count })}</span>
                </h2>
                {cheques.length === 0 ? (
                    <p className="text-xs text-muted bg-surface border border-dashed border-border rounded-xl p-6 text-center">{t("noCheques")}</p>
                ) : (
                    <div className="bg-surface rounded-xl border border-border overflow-x-auto">
                        <table className="w-full" data-testid="renter-cheques">
                            <thead><tr className="bg-input/50">
                                <th className={th}>{t("unit")}</th>
                                <th className={th}>{t("chequeNumber")}</th>
                                <th className={th}>{t("chequeDate")}</th>
                                <th className={th}>{t("status")}</th>
                                <th className={cn(th, "text-end")}>{t("amount")}</th>
                            </tr></thead>
                            <tbody>
                                {cheques.map(c => (
                                    <tr key={c.id} className="border-b border-border">
                                        <td className={td}>{unitOf.get(c.leaseId) ?? "—"}</td>
                                        <td className={td} dir="ltr">{c.chequeNumber ?? "—"}</td>
                                        <td className={td}>{fmtIsoDate(c.chequeDate, locale)}</td>
                                        <td className={td}>{tCheques.has(`status.${c.status}`) ? tCheques(`status.${c.status}`) : c.status}</td>
                                        <td className={cn(td, "text-end tabular-nums")} dir="ltr">{formatCurrency(c.amount)}</td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                )}
            </section>

            {/* ── Tickets ────────────────────────────────────────── */}
            <section>
                <h2 className="text-sm font-bold text-foreground mb-3">{t("tickets")}</h2>
                {tickets.length === 0 ? (
                    <p className="text-xs text-muted bg-surface border border-dashed border-border rounded-xl p-6 text-center">{t("noTickets")}</p>
                ) : (
                    <div className="bg-surface rounded-xl border border-border overflow-x-auto">
                        <table className="w-full" data-testid="renter-tickets">
                            <thead><tr className="bg-input/50">
                                <th className={th}>{t("reference")}</th>
                                <th className={th}>{t("ticketTitle")}</th>
                                <th className={th}>{t("reported")}</th>
                                <th className={th}>{t("status")}</th>
                            </tr></thead>
                            <tbody>
                                {tickets.map(tk => (
                                    <tr key={tk.id} className="border-b border-border hover:bg-input/30 transition-colors">
                                        <td className={td}>
                                            <Link href={`/dashboard/tickets/${tk.id}`} className="text-primary font-semibold hover:underline" dir="ltr">
                                                {tk.reference || tk.id.slice(0, 8)}
                                            </Link>
                                        </td>
                                        <td className={td}>{tk.title}</td>
                                        <td className={td}>{fmtIsoDate(tk.reportedDate ?? tk.createdAt, locale)}</td>
                                        <td className={td}>
                                            {TICKET_STATUSES.includes(tk.status) ? t(`ticketStatus.${tk.status}`) : tk.status}
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                )}
            </section>
        </div>
    );
}
