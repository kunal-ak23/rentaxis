"use client";

import { useState, useEffect, useCallback } from "react";
import { useLocale, useTranslations } from "next-intl";
import { Link } from "@/i18n/routing";
import { Pagination } from "@/components/ui/Pagination";
import { useSession } from "next-auth/react";
import { hasPermission, type UserRole } from "@/lib/rbac";
import { cn } from "@/lib/utils";
import { businessTodayIso } from "@/lib/businessDate";
import { fmtIsoDate } from "@/components/leases/leaseMath";
import {
    Plus, X, Search, Loader2, Eye, Upload, Wrench, BarChart3,
} from "lucide-react";

// ── Types ──────────────────────────────────────────────────────────────────

type Ticket = {
    id: string;
    /** "TKT-yy/n" (#20); absent only on rows written outside the service. */
    reference?: string | null;
    title: string;
    description: string;
    status: string;
    priority: string;
    category: string;
    propertyId: string;
    propertyName: string;
    unitId: string;
    unitNumber: string;
    reporterName: string;
    assigneeName: string | null;
    onBehalfOf: string | null;
    /** #19: the renter the ticket was logged for; null on legacy free-text rows. */
    onBehalfOfRenterId?: string | null;
    createdAt: string;
    updatedAt: string;
};

// GET /api/v1/properties returns each property wrapped in a portfolio-summary
// row (PropertyStatsDTO: property + assignedManagers + occupancy stats), not a
// flat property object. Accessing p.id/p.nameEn directly yielded undefined —
// the dropdown rendered blank options.
type Property = {
    property: {
        id: string;
        nameEn: string;
    };
};

type Unit = {
    id: string;
    unitNumber: string;
    property?: { id: string; nameEn?: string; nameAr?: string };
};

// ── Badge Maps ─────────────────────────────────────────────────────────────

const PRIORITY_COLORS: Record<string, string> = {
    LOW: "bg-input text-muted",
    MEDIUM: "bg-info/10 text-info",
    HIGH: "bg-warning/10 text-warning",
    URGENT: "bg-error/10 text-error",
};

const STATUS_COLORS: Record<string, string> = {
    OPEN: "bg-warning/10 text-warning",
    ASSIGNED: "bg-info/10 text-info",
    IN_PROGRESS: "bg-primary/10 text-primary",
    RESOLVED: "bg-success/10 text-success",
    CLOSED: "bg-input text-muted",
    REOPENED: "bg-error/10 text-error",
};

const PRIORITIES = ["LOW", "MEDIUM", "HIGH", "URGENT"];

const CATEGORIES = [
    "PLUMBING",
    "ELECTRICAL",
    "HVAC",
    "APPLIANCE",
    "STRUCTURAL",
    "PEST_CONTROL",
    "CLEANING",
    "SECURITY",
    "OTHER",
];

// ── Page Component ─────────────────────────────────────────────────────────

export default function TicketsPage() {
    const t = useTranslations("Tickets");
    const locale = useLocale();
    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;

    // Data
    const [tickets, setTickets] = useState<Ticket[]>([]);
    const [properties, setProperties] = useState<Property[]>([]);
    const [units, setUnits] = useState<Unit[]>([]);
    const [loading, setLoading] = useState(true);

    // Filters
    const [searchQuery, setSearchQuery] = useState("");
    const [statusFilter, setStatusFilter] = useState("ALL");
    const [priorityFilter, setPriorityFilter] = useState("ALL");

    // Pagination
    const [currentPage, setCurrentPage] = useState(1);
    const [itemsPerPage, setItemsPerPage] = useState(25);

    // Create modal
    const [showForm, setShowForm] = useState(false);
    const [submitting, setSubmitting] = useState(false);
    const [createError, setCreateError] = useState<string | null>(null);
    const [form, setForm] = useState({
        title: "",
        description: "",
        propertyId: "",
        unitId: "",
        category: "OTHER",
        priority: "MEDIUM",
        onBehalfOfRenterId: "",
        reportedDate: businessTodayIso(),
    });
    const [attachmentFiles, setAttachmentFiles] = useState<File[]>([]);
    const [renterLeases, setRenterLeases] = useState<{ id: string; propertyId: string; propertyName: string; unitId: string; unitIdentifier: string }[]>([]);
    const isRenter = userRole === "RENTER";
    // Status, priority and category arrive as enum codes; an unknown code falls
    // back to its readable form rather than a raw key path.
    const enumLabel = (group: "status" | "priority" | "category", code: string) =>
        t.has(`${group}.${code}`) ? t(`${group}.${code}`) : code.replace(/_/g, " ");

    // ── Fetch data ──────────────────────────────────────────────────────

    const fetchTickets = useCallback(async () => {
        try {
            const res = await fetch("/api/proxy/v1/tickets");
            if (res.ok) setTickets(await res.json());
        } catch { /* ignore */ }
    }, []);

    const fetchProperties = useCallback(async () => {
        if (isRenter) return; // Renters use their leases instead
        try {
            const res = await fetch("/api/proxy/v1/properties");
            if (res.ok) setProperties(await res.json());
        } catch { /* ignore */ }
    }, [isRenter]);

    // #19: the org's renters, for the "on behalf of" picker. Staff only; the
    // endpoint is not open to a renter, and a renter reports for themselves.
    const [renters, setRenters] = useState<{ id: string; nameEn: string; nameAr: string | null; phone: string | null }[]>([]);
    const fetchRenters = useCallback(async () => {
        if (isRenter) return;
        try {
            const res = await fetch("/api/proxy/v1/renters");
            if (res.ok) setRenters(await res.json());
        } catch { /* ignore */ }
    }, [isRenter]);

    const fetchUnits = useCallback(async () => {
        if (isRenter) return; // Renters use their leases instead
        try {
            const res = await fetch("/api/proxy/v1/units");
            if (res.ok) setUnits(await res.json());
        } catch { /* ignore */ }
    }, [isRenter]);

    const fetchRenterLeases = useCallback(async () => {
        if (!isRenter) return;
        try {
            const res = await fetch("/api/proxy/v1/leases/my-leases");
            if (res.ok) {
                const leases = await res.json();
                const active = leases.filter((l: any) => l.status === "ACTIVE");
                setRenterLeases(active.map((l: any) => ({
                    id: l.id,
                    propertyId: l.propertyId,
                    propertyName: l.propertyName,
                    unitId: l.unitId,
                    unitIdentifier: l.unitIdentifier,
                })));
            }
        } catch {}
    }, [isRenter]);

    useEffect(() => {
        Promise.all([fetchTickets(), fetchProperties(), fetchUnits(), fetchRenterLeases(), fetchRenters()]).finally(() => setLoading(false));
    }, [fetchTickets, fetchProperties, fetchUnits, fetchRenterLeases, fetchRenters]);

    // ── Filtering ───────────────────────────────────────────────────────

    const filtered = tickets.filter((t) => {
        if (statusFilter !== "ALL" && t.status !== statusFilter) return false;
        if (priorityFilter !== "ALL" && t.priority !== priorityFilter) return false;
        if (searchQuery) {
            const q = searchQuery.toLowerCase();
            // #20: the reference is what a caller quotes over the phone, so it
            // is searchable, with or without the "TKT-" prefix.
            if (
                !t.title.toLowerCase().includes(q) &&
                !(t.description ?? "").toLowerCase().includes(q) &&
                !(t.reference ?? "").toLowerCase().includes(q)
            )
                return false;
        }
        return true;
    });

    const totalItems = filtered.length;
    const paginated = filtered.slice(
        (currentPage - 1) * itemsPerPage,
        currentPage * itemsPerPage,
    );

    // Units filtered by selected property
    const filteredUnits = form.propertyId
        ? units.filter((u) => u.property?.id === form.propertyId)
        : units;

    // ── Create ticket ───────────────────────────────────────────────────

    const handleCreate = async () => {
        // The backend requires a property (POST /v1/tickets rejects a missing
        // propertyId), so don't submit without one.
        if (!form.title.trim() || !form.propertyId) return;
        setSubmitting(true);
        setCreateError(null);
        try {
            const res = await fetch("/api/proxy/v1/tickets", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({
                    title: form.title,
                    description: form.description,
                    propertyId: form.propertyId,
                    unitId: form.unitId || undefined,
                    category: form.category,
                    priority: form.priority,
                    onBehalfOfRenterId: form.onBehalfOfRenterId || undefined,
                    reportedDate: form.reportedDate || undefined,
                }),
            });
            if (res.ok) {
                const created = await res.json();
                // Upload attachments if any
                for (const file of attachmentFiles) {
                    const formData = new FormData();
                    formData.append("file", file);
                    formData.append("name", file.name);
                    await fetch(`/api/upload?path=/api/v1/tickets/${created.id}/attachments`, {
                        method: "POST",
                        body: formData,
                    });
                }
                setShowForm(false);
                setForm({ title: "", description: "", propertyId: "", unitId: "", category: "OTHER", priority: "MEDIUM", onBehalfOfRenterId: "", reportedDate: businessTodayIso() });
                setAttachmentFiles([]);
                fetchTickets();
            } else {
                const errData = await res.json().catch(() => null);
                setCreateError(errData?.message || errData?.error || t("createFailed"));
            }
        } catch {
            setCreateError(t("createFailed"));
        } finally {
            setSubmitting(false);
        }
    };

    // ── Loading state ───────────────────────────────────────────────────

    if (loading) {
        return (
            <div className="flex items-center justify-center py-24">
                <Loader2 className="w-6 h-6 animate-spin text-primary opacity-60" />
            </div>
        );
    }

    // ── Render ──────────────────────────────────────────────────────────

    return (
        <div>
            {/* Header */}
            <div className="flex items-center justify-between mb-6">
                <div>
                    <h1 className="text-lg font-bold text-foreground">{t("title")}</h1>
                    <p className="text-xs text-muted mt-0.5">
                        {t("subtitle")}
                    </p>
                </div>
                <div className="flex items-center gap-3">
                    {!isRenter && (
                        <Link
                            href="/dashboard/tickets/reports"
                            className="flex items-center gap-2 border border-border text-foreground px-4 py-2 rounded-lg text-xs font-semibold hover:bg-input transition-all"
                        >
                            <BarChart3 size={14} /> {t("reports")}
                        </Link>
                    )}
                    <button
                        onClick={() => {
                            // Auto-fill for renter with single lease
                            if (isRenter && renterLeases.length === 1) {
                                setForm(f => ({ ...f, propertyId: renterLeases[0].propertyId, unitId: renterLeases[0].unitId }));
                            }
                            setCreateError(null);
                            setShowForm(true);
                        }}
                        className="flex items-center gap-2 bg-primary text-primary-foreground px-4 py-2 rounded-lg text-xs font-semibold hover:bg-primary/90 transition-all cursor-pointer"
                    >
                        <Plus size={14} /> {t("createTicket")}
                    </button>
                </div>
            </div>

            {/* Filters */}
            <div className="bg-surface rounded-xl border border-border p-4 mb-6">
                <div className="flex flex-wrap items-center gap-3">
                    {/* Search */}
                    <div className="relative flex-1 min-w-[200px]">
                        <Search size={14} className="absolute start-3 top-1/2 -translate-y-1/2 text-muted" />
                        <input
                            type="text"
                            placeholder={t("searchPlaceholder")}
                            value={searchQuery}
                            onChange={(e) => { setSearchQuery(e.target.value); setCurrentPage(1); }}
                            className="w-full ps-9 pe-3 py-2 border border-border rounded-lg bg-surface text-xs text-foreground placeholder:text-muted/50 focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none"
                        />
                    </div>

                    {/* Status filter */}
                    <select
                        value={statusFilter}
                        onChange={(e) => { setStatusFilter(e.target.value); setCurrentPage(1); }}
                        className="border border-border rounded-lg bg-surface px-3 py-2 text-xs text-foreground focus:ring-2 focus:ring-primary/20 focus:outline-none cursor-pointer"
                    >
                        <option value="ALL">{t("allStatuses")}</option>
                        {["OPEN", "ASSIGNED", "IN_PROGRESS", "RESOLVED", "CLOSED", "REOPENED"].map((s) => (
                            <option key={s} value={s}>{enumLabel("status", s)}</option>
                        ))}
                    </select>

                    {/* Priority filter */}
                    <select
                        value={priorityFilter}
                        onChange={(e) => { setPriorityFilter(e.target.value); setCurrentPage(1); }}
                        className="border border-border rounded-lg bg-surface px-3 py-2 text-xs text-foreground focus:ring-2 focus:ring-primary/20 focus:outline-none cursor-pointer"
                    >
                        <option value="ALL">{t("allPriorities")}</option>
                        {PRIORITIES.map((p) => (
                            <option key={p} value={p}>{enumLabel("priority", p)}</option>
                        ))}
                    </select>
                </div>
            </div>

            {/* Table */}
            <div className="bg-surface rounded-xl border border-border overflow-hidden">
                <div className="overflow-x-auto">
                    <table className="w-full">
                        <thead>
                            <tr className="bg-input/50">
                                <th className="px-4 py-2.5 text-start text-[11px] font-semibold text-muted uppercase tracking-wider">{t("colId")}</th>
                                <th className="px-4 py-2.5 text-start text-[11px] font-semibold text-muted uppercase tracking-wider">{t("colTitle")}</th>
                                <th className="px-4 py-2.5 text-start text-[11px] font-semibold text-muted uppercase tracking-wider">{t("colProperty")}</th>
                                <th className="px-4 py-2.5 text-start text-[11px] font-semibold text-muted uppercase tracking-wider">{t("colUnit")}</th>
                                <th className="px-4 py-2.5 text-center text-[11px] font-semibold text-muted uppercase tracking-wider">{t("colPriority")}</th>
                                <th className="px-4 py-2.5 text-center text-[11px] font-semibold text-muted uppercase tracking-wider">{t("colStatus")}</th>
                                <th className="px-4 py-2.5 text-start text-[11px] font-semibold text-muted uppercase tracking-wider">{t("colAssignedTo")}</th>
                                <th className="px-4 py-2.5 text-start text-[11px] font-semibold text-muted uppercase tracking-wider">{t("colCreated")}</th>
                                <th className="px-4 py-2.5 text-center text-[11px] font-semibold text-muted uppercase tracking-wider">{t("colActions")}</th>
                            </tr>
                        </thead>
                        <tbody>
                            {paginated.map((ticket) => (
                                <tr key={ticket.id} className="border-b border-border hover:bg-input/30 transition-colors">
                                    <td className="px-4 py-2.5 text-xs text-foreground font-mono font-semibold whitespace-nowrap" data-testid="ticket-reference">
                                        {/* The cell keeps the page direction so it lines up with its
                                            header in Arabic; only the reference is isolated LTR. */}
                                        <bdi dir="ltr">{ticket.reference ?? ticket.id.substring(0, 8)}</bdi>
                                    </td>
                                    <td className="px-4 py-2.5 max-w-[200px]">
                                        <div className="text-xs font-medium text-foreground truncate">{ticket.title}</div>
                                        {ticket.onBehalfOf && (
                                            <div className="text-[10px] text-muted truncate">
                                                {ticket.onBehalfOfRenterId ? (
                                                    <Link href={`/dashboard/renters/${ticket.onBehalfOfRenterId}`} className="hover:text-primary hover:underline" data-testid="ticket-on-behalf-of-link">
                                                        {t("onBehalfOfLabel", { name: ticket.onBehalfOf })}
                                                    </Link>
                                                ) : t("onBehalfOfLabel", { name: ticket.onBehalfOf })}
                                            </div>
                                        )}
                                    </td>
                                    <td className="px-4 py-2.5 text-xs text-muted">
                                        {ticket.propertyName || "—"}
                                    </td>
                                    <td className="px-4 py-2.5 text-xs text-muted">
                                        {ticket.unitNumber || "—"}
                                    </td>
                                    <td className="px-4 py-2.5 text-center">
                                        <span className={cn(
                                            "px-2 py-0.5 rounded-md text-[9px] font-semibold",
                                            PRIORITY_COLORS[ticket.priority] || "bg-input text-muted",
                                        )}>
                                            {enumLabel("priority", ticket.priority)}
                                        </span>
                                    </td>
                                    <td className="px-4 py-2.5 text-center">
                                        <span className={cn(
                                            "px-2 py-0.5 rounded-md text-[9px] font-semibold",
                                            STATUS_COLORS[ticket.status] || "bg-input text-muted",
                                        )}>
                                            {enumLabel("status", ticket.status)}
                                        </span>
                                    </td>
                                    <td className="px-4 py-2.5 text-xs text-muted">
                                        {ticket.assigneeName || "—"}
                                    </td>
                                    <td className="px-4 py-2.5 text-xs text-muted tabular-nums">
                                        {fmtIsoDate(ticket.createdAt, locale)}
                                    </td>
                                    <td className="px-4 py-2.5 text-center">
                                        <Link
                                            href={`/dashboard/tickets/${ticket.id}`}
                                            className="inline-flex items-center gap-1 text-[10px] font-semibold text-primary hover:text-primary/80"
                                        >
                                            <Eye size={12} /> {t("view")}
                                        </Link>
                                    </td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                    {paginated.length === 0 && (
                        <div className="text-center py-12 text-muted">
                            <Wrench size={28} className="mx-auto mb-3 opacity-40" />
                            <p className="text-xs">{t("noTickets")}</p>
                            <p className="text-[10px] mt-1 text-muted/60">
                                {t("noTicketsHint")}
                            </p>
                        </div>
                    )}
                </div>

                <div className="px-4 pb-3">
                    <Pagination
                        currentPage={currentPage}
                        totalItems={totalItems}
                        itemsPerPage={itemsPerPage}
                        onPageChange={setCurrentPage}
                        onItemsPerPageChange={(n) => { setItemsPerPage(n); setCurrentPage(1); }}
                    />
                </div>
            </div>

            {/* ── Create Ticket Modal ─────────────────────────────────────── */}
            {showForm && (
                <div className="fixed inset-0 z-50 flex items-center justify-center">
                    <div className="absolute inset-0 bg-black/40" onClick={() => setShowForm(false)} />
                    <div className="relative bg-surface rounded-xl border border-border shadow-xl w-full max-w-lg mx-4 max-h-[90vh] overflow-y-auto">
                        {/* Modal header */}
                        <div className="flex items-center justify-between px-6 py-4 border-b border-border">
                            <h2 className="text-sm font-bold text-foreground">{t("createTicket")}</h2>
                            <button onClick={() => setShowForm(false)} className="p-1 text-muted hover:text-foreground cursor-pointer">
                                <X size={16} />
                            </button>
                        </div>

                        <div className="px-6 py-5 space-y-4">
                            {/* Title */}
                            <div>
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5">{t("fieldTitle")} *</label>
                                <input
                                    type="text"
                                    value={form.title}
                                    onChange={(e) => setForm({ ...form, title: e.target.value })}
                                    placeholder={t("titlePlaceholder")}
                                    className="w-full border border-border rounded-lg bg-surface px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none"
                                />
                            </div>

                            {/* Description */}
                            <div>
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5">{t("description")}</label>
                                <textarea
                                    value={form.description}
                                    onChange={(e) => setForm({ ...form, description: e.target.value })}
                                    placeholder={t("descriptionPlaceholder")}
                                    rows={4}
                                    className="w-full border border-border rounded-lg bg-surface px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none resize-none"
                                />
                            </div>

                            {/* Property & Unit row */}
                            {isRenter && renterLeases.length === 1 ? (
                                /* Single lease renter — auto-filled, read-only */
                                <div className="bg-input/50 rounded-lg px-4 py-3 border border-border">
                                    <p className="text-[10px] font-semibold text-muted uppercase tracking-wider mb-1">{t("propertyAndUnit")}</p>
                                    <p className="text-xs font-medium text-foreground">{t("propertyUnitOption", { property: renterLeases[0].propertyName, unit: renterLeases[0].unitIdentifier })}</p>
                                </div>
                            ) : isRenter && renterLeases.length > 1 ? (
                                /* Multi-lease renter — pick from their leases */
                                <div>
                                    <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5">{t("selectUnitLabel")}</label>
                                    <select
                                        value={form.unitId}
                                        onChange={(e) => {
                                            const lease = renterLeases.find(l => l.unitId === e.target.value);
                                            setForm({ ...form, unitId: e.target.value, propertyId: lease?.propertyId || "" });
                                        }}
                                        className="w-full border border-border rounded-lg bg-surface px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:outline-none cursor-pointer"
                                    >
                                        <option value="">{t("selectYourUnit")}</option>
                                        {renterLeases.map((l) => (
                                            <option key={l.unitId} value={l.unitId}>{t("propertyUnitOption", { property: l.propertyName, unit: l.unitIdentifier })}</option>
                                        ))}
                                    </select>
                                </div>
                            ) : (
                                /* Admin/PM — full property + unit dropdowns */
                                <div className="grid grid-cols-2 gap-3">
                                    <div>
                                        <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5">{t("property")} *</label>
                                        <select
                                            value={form.propertyId}
                                            onChange={(e) => setForm({ ...form, propertyId: e.target.value, unitId: "" })}
                                            className="w-full border border-border rounded-lg bg-surface px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:outline-none cursor-pointer"
                                        >
                                            <option value="">{t("selectProperty")}</option>
                                            {properties.map((p) => (
                                                <option key={p.property.id} value={p.property.id}>{p.property.nameEn}</option>
                                            ))}
                                        </select>
                                    </div>
                                    <div>
                                        <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5">{t("unit")}</label>
                                        <select
                                            value={form.unitId}
                                            onChange={(e) => setForm({ ...form, unitId: e.target.value })}
                                            className="w-full border border-border rounded-lg bg-surface px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:outline-none cursor-pointer"
                                        >
                                            <option value="">{t("selectUnit")}</option>
                                            {filteredUnits.map((u) => (
                                                <option key={u.id} value={u.id}>{u.unitNumber}</option>
                                            ))}
                                        </select>
                                    </div>
                                </div>
                            )}

                            {/* On behalf of (staff only) — #19: a renter picked from the org's
                                renters, stored by id, so a phoned-in complaint reaches that
                                renter's record instead of living as free text. */}
                            {!isRenter && (
                                <div>
                                    <label htmlFor="ticket-on-behalf-of" className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5">{t("onBehalfOfRenter")}</label>
                                    <select
                                        id="ticket-on-behalf-of"
                                        value={form.onBehalfOfRenterId || ""}
                                        onChange={(e) => setForm({ ...form, onBehalfOfRenterId: e.target.value })}
                                        className="w-full border border-border rounded-lg bg-surface px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:outline-none"
                                    >
                                        <option value="">{t("onBehalfOfNone")}</option>
                                        {renters.map((r) => (
                                            <option key={r.id} value={r.id}>
                                                {(locale === "ar" && r.nameAr) ? r.nameAr : r.nameEn}{r.phone ? ` · ${r.phone}` : ""}
                                            </option>
                                        ))}
                                    </select>
                                    <p className="text-[10px] text-muted mt-1">{t("onBehalfOfHint")}</p>
                                </div>
                            )}

                            {/* Reported date — for a complaint taken by phone and logged later */}
                            <div>
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5">{t("reportedOn")}</label>
                                <input
                                    type="date"
                                    value={form.reportedDate}
                                    max={businessTodayIso()}
                                    onChange={(e) => setForm({ ...form, reportedDate: e.target.value })}
                                    className="w-full border border-border rounded-lg bg-surface px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:outline-none"
                                />
                            </div>

                            {/* Category & Priority row */}
                            <div className="grid grid-cols-2 gap-3">
                                <div>
                                    <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5">{t("categoryLabel")}</label>
                                    <select
                                        value={form.category}
                                        onChange={(e) => setForm({ ...form, category: e.target.value })}
                                        className="w-full border border-border rounded-lg bg-surface px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:outline-none cursor-pointer"
                                    >
                                        {CATEGORIES.map((c) => (
                                            <option key={c} value={c}>{enumLabel("category", c)}</option>
                                        ))}
                                    </select>
                                </div>
                                <div>
                                    <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5">{t("priorityLabel")}</label>
                                    <select
                                        value={form.priority}
                                        onChange={(e) => setForm({ ...form, priority: e.target.value })}
                                        className="w-full border border-border rounded-lg bg-surface px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:outline-none cursor-pointer"
                                    >
                                        {PRIORITIES.map((p) => (
                                            <option key={p} value={p}>{enumLabel("priority", p)}</option>
                                        ))}
                                    </select>
                                </div>
                            </div>

                            {/* File upload */}
                            <div>
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5">{t("attachmentsLabel")}</label>
                                <label className="flex items-center justify-center gap-2 border-2 border-dashed border-border rounded-lg px-4 py-4 cursor-pointer hover:border-primary/40 transition-colors">
                                    <Upload size={14} className="text-muted" />
                                    <span className="text-xs text-muted">{t("clickToAttach")}</span>
                                    <input
                                        type="file"
                                        multiple
                                        className="hidden"
                                        accept="image/*,video/*,.pdf"
                                        onChange={(e) => {
                                            if (e.target.files) {
                                                setAttachmentFiles((prev) => [...prev, ...Array.from(e.target.files!)]);
                                            }
                                            if (e.target) e.target.value = "";
                                        }}
                                    />
                                </label>
                                {attachmentFiles.length > 0 && (
                                    <div className="mt-2 space-y-1">
                                        {attachmentFiles.map((f, i) => (
                                            <div key={i} className="flex items-center justify-between bg-input/50 rounded-lg px-3 py-1.5 text-xs">
                                                <span className="truncate text-foreground">{f.name}</span>
                                                <button
                                                    onClick={() => setAttachmentFiles((prev) => prev.filter((_, idx) => idx !== i))}
                                                    className="text-muted hover:text-error shrink-0 ms-2 cursor-pointer"
                                                >
                                                    <X size={12} />
                                                </button>
                                            </div>
                                        ))}
                                    </div>
                                )}
                            </div>
                        </div>

                        {/* Modal footer */}
                        <div className="flex items-center justify-end gap-3 px-6 py-4 border-t border-border">
                            {createError && (
                                <p className="flex-1 text-xs text-error">{createError}</p>
                            )}
                            <button
                                onClick={() => setShowForm(false)}
                                className="px-4 py-2 rounded-lg text-xs font-semibold text-muted hover:text-foreground hover:bg-input transition-colors cursor-pointer"
                            >
                                {t("cancel")}
                            </button>
                            <button
                                onClick={handleCreate}
                                disabled={submitting || !form.title.trim() || !form.propertyId}
                                className={cn(
                                    "flex items-center gap-2 px-4 py-2 rounded-lg text-xs font-semibold transition-all cursor-pointer",
                                    submitting || !form.title.trim() || !form.propertyId
                                        ? "bg-input text-muted cursor-not-allowed"
                                        : "bg-primary text-primary-foreground hover:bg-primary/90",
                                )}
                            >
                                {submitting && <Loader2 size={12} className="animate-spin" />}
                                {t("createTicket")}
                            </button>
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
}
