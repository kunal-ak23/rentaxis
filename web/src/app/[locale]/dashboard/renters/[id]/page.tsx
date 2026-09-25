"use client";

import { useEffect, useMemo, useState } from "react";
import { useParams } from "next/navigation";
import { useSession } from "next-auth/react";
import { useLocale, useTranslations } from "next-intl";
import { Link } from "@/i18n/routing";
import { ArrowLeft, BookOpen, ChevronDown, Loader2, Mail, Phone, Languages } from "lucide-react";
import { cn } from "@/lib/utils";
import { hasPermission, type UserRole } from "@/lib/rbac";
import { formatCurrency } from "@/lib/format";
import { fmtIsoDate } from "@/components/leases/leaseMath";
import { ResendInviteButton } from "@/components/users/ResendInviteButton";
import { ApiError, chequeApi, leaseApi, type Cheque, type LeaseChequeStats, type LeaseStatus } from "@/lib/api/leasing";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";

/**
 * One renter, for staff (#8): their profile, their contracts, the cheques on
 * those contracts, their tickets, and the way into their ledger.
 *
 * Built on `GET /renters/{id}`, `GET /renters/{id}/leases` (tenant-scoped, and
 * narrowed to a property manager's own buildings by LeaseAccessPolicy),
 * one `POST /cheques/stats-by-leases` for every contract's cheque figures (scale
 * spec #14 — it used to read `GET /leases/{id}/cheques` for every contract up
 * front), and `GET /tickets?renterId=`, the caller's own ticket scope narrowed
 * to this renter on the server. A contract's cheque rows load when opened.
 */

/** The stats endpoint takes at most this many leases per call (ChequeQueryService.MAX_STATS_LEASES). */
const STATS_CHUNK = 200;

async function statsFor(leaseIds: string[]): Promise<Map<string, LeaseChequeStats>> {
    const chunks: string[][] = [];
    for (let i = 0; i < leaseIds.length; i += STATS_CHUNK) chunks.push(leaseIds.slice(i, i + STATS_CHUNK));
    const parts = await Promise.all(chunks.map(c => chequeApi.statsByLeases(c)));
    return new Map(parts.flat().map(s => [s.leaseId, s]));
}

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
    const tInv = useTranslations("Invites");
    const { data: session, status: sessionStatus } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;
    const canView = hasPermission(userRole, "canViewLeases");
    const canManageRenters = hasPermission(userRole, "canManageRenters");
    const canSeeLedger = hasPermission(userRole, "canAccessFinance");

    const [renter, setRenter] = useState<Renter | null>(null);
    const [leases, setLeases] = useState<RenterLease[]>([]);
    const [stats, setStats] = useState<Map<string, LeaseChequeStats>>(new Map());
    const [tickets, setTickets] = useState<Ticket[]>([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    // A failed request is not "nothing here" (web review I2): the renter itself
    // (404 vs anything else), and each section, keep their own failure so the
    // page never shows an empty state, or an understated total, for a load that
    // did not happen.
    const [renterFailed, setRenterFailed] = useState(false);
    const [leasesFailed, setLeasesFailed] = useState(false);
    const [chequesFailed, setChequesFailed] = useState(false);
    const [ticketsFailed, setTicketsFailed] = useState(false);
    const [reloadKey, setReloadKey] = useState(0);
    const retry = () => setReloadKey(k => k + 1);

    // After a resend: re-read the renter alone, so the invite badge reflects the
    // new link without reloading (and re-spinning) the whole page.
    const refreshRenter = async () => {
        try {
            const r = await fetch(`/api/proxy/v1/renters/${encodeURIComponent(renterId)}`);
            if (r.ok) setRenter(await r.json());
        } catch {
            // The resend itself succeeded; a stale badge is not worth an error.
        }
    };

    useEffect(() => {
        // Wait for the session: the first render has no role yet, and loading
        // then would fetch everything once without a role and again with one
        // (web review M1) — and fire a RENTER's requests before refusing them.
        if (!userRole) {
            if (sessionStatus !== "loading") setLoading(false);
            return;
        }
        if (!canView) {
            setLoading(false);
            return;
        }
        let cancelled = false;
        setLoading(true);
        setError(null);
        setRenterFailed(false);
        setLeasesFailed(false);
        setChequesFailed(false);
        setTicketsFailed(false);
        (async () => {
            try {
                let r: Response;
                try {
                    r = await fetch(`/api/proxy/v1/renters/${encodeURIComponent(renterId)}`);
                } catch {
                    if (!cancelled) setRenterFailed(true);
                    return;
                }
                if (!r.ok) {
                    if (cancelled) return;
                    if (r.status === 403) setError(t("accessDenied"));
                    else if (r.status === 404) setError(t("notFound"));
                    else setRenterFailed(true);
                    return;
                }
                const loaded: Renter = await r.json();
                if (cancelled) return;
                setRenter(loaded);

                let ls: RenterLease[] = [];
                try {
                    const lr = await fetch(`/api/proxy/v1/renters/${encodeURIComponent(renterId)}/leases`);
                    if (!lr.ok) throw new Error(String(lr.status));
                    ls = await lr.json();
                } catch {
                    if (!cancelled) setLeasesFailed(true);
                }
                if (cancelled) return;
                setLeases(ls);

                if (ls.length > 0) {
                    try {
                        const byLease = await statsFor(ls.map(l => l.id));
                        if (!cancelled) setStats(byLease);
                    } catch {
                        if (!cancelled) {
                            setStats(new Map());
                            setChequesFailed(true);
                        }
                    }
                } else if (!cancelled) {
                    setStats(new Map());
                }

                // Filtered server-side (web review I3): tickets this renter
                // reported, raised on one of their contracts, or logged on their
                // behalf, inside the caller's own ticket scope. This used to pull
                // every ticket in the tenant and filter here.
                try {
                    const tr = await fetch(`/api/proxy/v1/tickets?renterId=${encodeURIComponent(loaded.id)}`);
                    if (!tr.ok) throw new Error(String(tr.status));
                    const mine: Ticket[] = await tr.json();
                    if (!cancelled) setTickets(mine);
                } catch {
                    if (!cancelled) setTicketsFailed(true);
                }
            } catch (e) {
                if (!cancelled) {
                    if (e instanceof ApiError) setError(e.message);
                    else setRenterFailed(true);
                }
            } finally {
                if (!cancelled) setLoading(false);
            }
        })();
        return () => {
            cancelled = true;
        };
    }, [renterId, userRole, canView, sessionStatus, t, reloadKey]);

    const summary = useMemo(() => {
        const all = [...stats.values()];
        const sum = (f: (s: LeaseChequeStats) => number) => Math.round(all.reduce((a, s) => a + (f(s) ?? 0), 0) * 100) / 100;
        return { count: sum(s => s.total), cleared: sum(s => s.clearedAmount), due: sum(s => s.dueAmount), bounced: sum(s => s.bounced) };
    }, [stats]);
    const leasesWithCheques = leases.filter(l => (stats.get(l.id)?.total ?? 0) > 0);
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
    if (!renter && renterFailed) {
        return (
            <div className="py-12">
                <LoadErrorBanner message={t("loadFailed")} onRetry={retry} />
                <Link href="/dashboard/renters" className="text-xs text-primary font-semibold">
                    {t("backToRenters")}
                </Link>
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

    // Cheques hang off contracts: if either load failed, every cheque figure is
    // a partial sum, and a partial "Outstanding" reads as a real one.
    const chequeFiguresIncomplete = leasesFailed || chequesFailed;
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
                        <ResendInviteButton userId={renter.userId} onSent={refreshRenter} />
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
            {/* Numbers and references are isolated LTR in a <bdi> while their cells
                and tiles keep the page direction, so in Arabic they still line up
                with their headers (web review I4). */}
            <div className="grid grid-cols-2 md:grid-cols-4 gap-3" data-testid="renter-summary">
                {[
                    { label: t("activeContracts"), value: leasesFailed ? "—" : String(activeLeases) },
                    { label: t("chequesCleared"), value: chequeFiguresIncomplete ? "—" : formatCurrency(summary.cleared) },
                    { label: t("chequesDueNow"), value: chequeFiguresIncomplete ? "—" : formatCurrency(summary.due) },
                    { label: t("chequesBounced"), value: chequeFiguresIncomplete ? "—" : String(summary.bounced) },
                ].map(k => (
                    <div key={k.label} className="bg-surface border border-border rounded-xl p-4">
                        <p className="text-[10px] font-semibold text-muted uppercase tracking-wider mb-1">{k.label}</p>
                        <p className="text-base font-semibold text-foreground tabular-nums"><bdi dir="ltr">{k.value}</bdi></p>
                    </div>
                ))}
            </div>

            {/* ── Contracts ──────────────────────────────────────── */}
            <section>
                <h2 className="text-sm font-bold text-foreground mb-3">{t("contracts")}</h2>
                {leasesFailed ? (
                    <LoadErrorBanner message={t("contractsLoadFailed")} onRetry={retry} className="mb-0" />
                ) : leases.length === 0 ? (
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
                                        <td className={cn(td, "text-end tabular-nums")}><bdi dir="ltr">{l.rentAmount != null ? formatCurrency(l.rentAmount) : "—"}</bdi></td>
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
                {chequeFiguresIncomplete && (
                    <LoadErrorBanner message={t("chequesLoadFailed")} onRetry={retry} className="mb-3" />
                )}
                {leasesWithCheques.length === 0 ? (
                    !chequeFiguresIncomplete && (
                        <p className="text-xs text-muted bg-surface border border-dashed border-border rounded-xl p-6 text-center">{t("noCheques")}</p>
                    )
                ) : (
                    <div className="space-y-2" data-testid="renter-cheques">
                        {leasesWithCheques.map(l => (
                            <ContractCheques key={l.id} lease={l} stats={stats.get(l.id)!}
                                initiallyOpen={leasesWithCheques.length === 1} />
                        ))}
                    </div>
                )}
            </section>

            {/* ── Tickets ────────────────────────────────────────── */}
            <section>
                <h2 className="text-sm font-bold text-foreground mb-3">{t("tickets")}</h2>
                {ticketsFailed ? (
                    <LoadErrorBanner message={t("ticketsLoadFailed")} onRetry={retry} className="mb-0" />
                ) : tickets.length === 0 ? (
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
                                            <Link href={`/dashboard/tickets/${tk.id}`} className="text-primary font-semibold hover:underline">
                                                <bdi dir="ltr">{tk.reference || tk.id.slice(0, 8)}</bdi>
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

/**
 * One contract's cheques inside the renter page: its figures from the batched
 * stats, its rows fetched only when opened (one contract → opened at once).
 */
function ContractCheques({ lease, stats, initiallyOpen }: { lease: RenterLease; stats: LeaseChequeStats; initiallyOpen: boolean }) {
    const t = useTranslations("RenterDetail");
    const tCheques = useTranslations("Cheques");
    const locale = useLocale();
    const [open, setOpen] = useState(initiallyOpen);
    const [rows, setRows] = useState<Cheque[] | null>(null);
    const [failed, setFailed] = useState(false);
    const [attempt, setAttempt] = useState(0);

    useEffect(() => {
        if (!open || rows !== null) return;
        let alive = true;
        leaseApi.cheques(lease.id)
            .then(cs => { if (alive) { setRows(cs); setFailed(false); } })
            .catch(() => { if (alive) setFailed(true); });
        return () => { alive = false; };
    }, [open, rows, lease.id, attempt]);

    const th = "px-4 py-2.5 text-start text-[11px] font-semibold text-muted uppercase tracking-wider";
    const td = "px-4 py-2.5 text-sm text-foreground";
    return (
        <div className="bg-surface rounded-xl border border-border" data-testid={`renter-cheques-${lease.id}`}>
            <button type="button" onClick={() => setOpen(o => !o)} aria-expanded={open}
                data-testid={`renter-cheques-toggle-${lease.id}`}
                className="w-full flex flex-wrap items-center justify-between gap-2 px-4 py-3 text-start cursor-pointer hover:bg-input/30 rounded-xl">
                <span className="text-sm font-semibold">
                    {lease.unitIdentifier ?? "—"}
                    <span className="ms-2 text-[11px] font-medium text-muted"><bdi dir="ltr">{lease.displayContractNumber || lease.id.slice(0, 8)}</bdi></span>
                </span>
                <span className="flex items-center gap-3 text-[11px] text-muted">
                    <span>{t("contractChequeStats", { cleared: stats.cleared, total: stats.total })}</span>
                    {stats.dueAmount > 0 && <span className="text-warning">{t("chequesDueNow")}: <bdi dir="ltr">{formatCurrency(stats.dueAmount)}</bdi></span>}
                    <span className="text-primary font-semibold">{open ? t("hideCheques") : t("showCheques")}</span>
                    <ChevronDown size={14} className={cn("transition-transform", open && "rotate-180")} />
                </span>
            </button>
            {open && (
                failed ? (
                    <div className="px-4 pb-3">
                        <LoadErrorBanner message={t("contractChequesFailed")} onRetry={() => { setFailed(false); setAttempt(a => a + 1); }} className="mb-0" />
                    </div>
                ) : rows === null ? (
                    <div className="flex justify-center py-4"><Loader2 className="w-4 h-4 animate-spin text-muted" /></div>
                ) : (
                    <div className="overflow-x-auto border-t border-border">
                        <table className="w-full">
                            <thead><tr className="bg-input/50">
                                <th className={th}>{t("chequeNumber")}</th>
                                <th className={th}>{t("chequeDate")}</th>
                                <th className={th}>{t("status")}</th>
                                <th className={cn(th, "text-end")}>{t("amount")}</th>
                            </tr></thead>
                            <tbody>
                                {rows.map(c => (
                                    <tr key={c.id} className="border-b border-border last:border-b-0">
                                        <td className={td}><bdi dir="ltr">{c.chequeNumber ?? "—"}</bdi></td>
                                        <td className={td}>{fmtIsoDate(c.chequeDate, locale)}</td>
                                        <td className={td}>{tCheques.has(`status.${c.status}`) ? tCheques(`status.${c.status}`) : c.status}</td>
                                        <td className={cn(td, "text-end tabular-nums")}><bdi dir="ltr">{formatCurrency(c.amount)}</bdi></td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                )
            )}
        </div>
    );
}
