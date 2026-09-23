"use client";

import { useState, useEffect } from "react";
import { useTranslations, useLocale } from "next-intl";
import { fmtIsoDate } from "@/components/leases/leaseMath";
import { Plus, X, FileText, Calendar, DollarSign, Home, CheckCircle, Ban, AlertCircle, LayoutGrid, Columns3, Download, Sparkles, Loader2, RefreshCw, Pencil, List, Eye, Search, Upload, Trash2 } from "lucide-react";
import LeaseWizard from "./LeaseWizard";
import { Link, useRouter } from "@/i18n/routing";
import { Pagination } from "@/components/ui/Pagination";
import { useSession } from "next-auth/react";
import { hasPermission, type UserRole } from "@/lib/rbac";
import { cn } from "@/lib/utils";
import { formatCurrency, formatCurrencyCompact } from "@/lib/format";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import LeaseDialog from "@/components/leases/LeaseDialog";
import {
    ApiError, chequeApi, leaseApi,
    type LeaseChequeStats, type LeaseDetail, type LeaseStatus,
} from "@/lib/api/leasing";

type Lease = LeaseDetail;

/**
 * Every status the list can hold, RENEWED among them. A renewed contract is
 * not closed and not active — it has a successor — and leaving it out of the
 * filter hid the predecessor half of every renewal chain.
 */
const STATUSES: LeaseStatus[] = [
    "DRAFT", "PENDING_SIGNATURE", "ACTIVE", "NOTICE_GIVEN", "RENEWED", "TERMINATED", "EXPIRED", "CLOSED",
];

/**
 * `LeaseTerminationService.TERMINABLE`
 * (backend/src/main/java/com/datagami/rentaxis/core/service/lease/LeaseTerminationService.java:83),
 * the same set the detail page's Terminate link uses. Both list views read it,
 * so the list and the detail page can no longer disagree about one contract.
 */
const TERMINABLE: LeaseStatus[] = ["ACTIVE", "NOTICE_GIVEN"];

/** One row of the bulk-post run: what was attempted, and what came back. */
type PostResult = { leaseId: string; label: string; ok: boolean; message: string };

type Unit = {
    id: string;
    unitNumber: string;
    status: string;
    property?: { id: string; nameEn?: string; nameAr?: string; type?: string };
};

type Renter = {
    id: string;
    nameEn: string;
    nameAr: string;
};

type LeaseAttachment = {
    id: string;
    leaseId: string;
    name: string;
    fileUrl: string;
    fileType: string;
    fileSize: number;
    uploadedAt: string;
};

const BOARD_COLUMNS = [
    { key: "draft", labelKey: "draft", statuses: ["DRAFT"], color: "bg-muted" },
    { key: "pending", labelKey: "pendingSignature", statuses: ["PENDING_SIGNATURE"], color: "bg-warning" },
    { key: "active", labelKey: "active", statuses: ["ACTIVE", "NOTICE_GIVEN"], color: "bg-success" },
    { key: "closed", labelKey: "closed", statuses: ["RENEWED", "TERMINATED", "EXPIRED", "CLOSED"], color: "bg-error" },
];

export default function LeasesPage() {
    const t = useTranslations("MasterData");
    const tl = useTranslations("Leasing");
    const locale = useLocale();
    const [leases, setLeases] = useState<Lease[]>([]);
    const [units, setUnits] = useState<Unit[]>([]);
    const [renters, setRenters] = useState<Renter[]>([]);
    const [wizardOpen, setWizardOpen] = useState(false);
    const [viewMode, setViewMode] = useState<'table' | 'cards' | 'board'>('table');
    const [currentPage, setCurrentPage] = useState(1);
    const [itemsPerPage, setItemsPerPage] = useState(25);
    const [totalItems, setTotalItems] = useState(0);
    const [searchQuery, setSearchQuery] = useState("");
    const [debouncedSearchQuery, setDebouncedSearchQuery] = useState("");
    const [statusFilter, setStatusFilter] = useState<LeaseStatus | "">("");
    const [selected, setSelected] = useState<Set<string>>(new Set());
    const [postResults, setPostResults] = useState<PostResult[] | null>(null);
    const [postProgress, setPostProgress] = useState<{ done: number; total: number } | null>(null);
    const [chequeStats, setChequeStats] = useState<Record<string, LeaseChequeStats>>({});
    const [chequeStatsLoading, setChequeStatsLoading] = useState(false);
    const [loading, setLoading] = useState(true);
    const [actionLoading, setActionLoading] = useState<string | null>(null);
    const [editingLeaseId, setEditingLeaseId] = useState<string | null>(null);
    const [attachments, setAttachments] = useState<LeaseAttachment[]>([]);
    const [uploadingDoc, setUploadingDoc] = useState(false);
    const [docName, setDocName] = useState("");
    const [docsLeaseId, setDocsLeaseId] = useState<string | null>(null); // Standalone docs modal

    // ConfirmDialog state
    const [confirmOpen, setConfirmOpen] = useState(false);
    const [confirmConfig, setConfirmConfig] = useState<{
        title: string;
        description: string;
        confirmText: string;
        isDestructive: boolean;
        onConfirm: () => void;
    }>({ title: "", description: "", confirmText: "", isDestructive: false, onConfirm: () => {} });

    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;
    // Reading and drafting are different permissions: an ACCOUNTANT reaches
    // this list to post the contracts on it but never drafts one, which is what
    // POST/PUT/DELETE /leases admit.
    const canViewLeases = hasPermission(userRole, 'canViewLeases');
    const canManageLeases = hasPermission(userRole, 'canManageLeases');
    const canPostLeases = hasPermission(userRole, 'canPostLeases');
    // The Terminate affordance opens the termination page, whose read gate is
    // LeaseController#previewTermination (SA/TA/ACCOUNTANT/PM, :273-274) — not
    // canManageLeases, which is the SA/TA draft-lease key and has nothing to do
    // with ending a contract. The page itself re-gates the button that posts the
    // journals on canTerminateLeases (:287-288).
    const canPreviewTermination = hasPermission(userRole, 'canPreviewTermination');

    useEffect(() => {
        fetchUnits();
        fetchRenters();
    }, []);

    useEffect(() => {
        const timer = setTimeout(() => {
            setDebouncedSearchQuery(searchQuery.trim());
        }, 350);
        return () => clearTimeout(timer);
    }, [searchQuery]);

    useEffect(() => {
        setCurrentPage(1);
    }, [debouncedSearchQuery, itemsPerPage]);

    useEffect(() => {
        fetchLeases();
    }, [currentPage, itemsPerPage, debouncedSearchQuery, statusFilter]);

    useEffect(() => {
        if (leases.length > 0) {
            fetchChequeStats(leases);
        }
    }, [leases]);

    // A status filter narrows what is on screen, so the page number it was
    // chosen on no longer means anything.
    useEffect(() => {
        setCurrentPage(1);
        setSelected(new Set());
    }, [statusFilter]);

    const fetchLeases = async () => {
        setLoading(true);
        try {
            const data = await leaseApi.paged({
                search: debouncedSearchQuery || undefined,
                status: statusFilter || undefined,
                page: Math.max(currentPage - 1, 0),
                size: itemsPerPage,
            });
            setLeases(data.content ?? []);
            setTotalItems(data.totalElements ?? 0);
        } catch (err) {
            console.error(err);
        } finally {
            setLoading(false);
        }
    };

    // Collection progress comes from the cheque register now: /v1/payments is
    // gone, and a lease's position is its cheques' -- how many have cleared and
    // how much is still due.
    const fetchChequeStats = async (allLeases: Lease[]) => {
        const ids = allLeases.filter(l => l.status === "ACTIVE" || l.status === "NOTICE_GIVEN").map(l => l.id);
        if (ids.length === 0) return;

        setChequeStatsLoading(true);
        try {
            const stats = await chequeApi.statsByLeases(ids);
            const map: Record<string, LeaseChequeStats> = {};
            for (const s of stats) map[s.leaseId] = s;
            setChequeStats(map);
        } catch (err) {
            console.error(err);
        } finally {
            setChequeStatsLoading(false);
        }
    };

    /**
     * Post the selected drafts one at a time, and report each outcome on its
     * own row.
     *
     * Sequential, not parallel: each post writes journals and takes an entry
     * number from the same tenant-wide sequence, and one 400 must not stop the
     * rest of the batch from being attempted. The result list is the point --
     * "3 of 7 posted" with no word on which three is not an answer an
     * accountant can act on.
     */
    const handleBulkPost = async () => {
        const targets = filteredLeases.filter(l => selected.has(l.id) && l.status === "DRAFT");
        if (targets.length === 0) return;
        setPostResults(null);
        setPostProgress({ done: 0, total: targets.length });
        const results: PostResult[] = [];
        for (const lease of targets) {
            const label = `${t("unit")} ${lease.unitIdentifier ?? ""} — ${lease.renterName ?? ""}`.trim();
            try {
                const res = await leaseApi.post(lease.id);
                results.push({ leaseId: lease.id, label, ok: true, message: res.tcoEntryNumber });
            } catch (e) {
                results.push({
                    leaseId: lease.id,
                    label,
                    ok: false,
                    message: e instanceof ApiError ? e.message : tl("postFailed"),
                });
            }
            setPostProgress({ done: results.length, total: targets.length });
        }
        setPostProgress(null);
        setPostResults(results);
        setSelected(new Set());
        fetchLeases();
    };

    const toggleSelected = (id: string) =>
        setSelected(prev => {
            const next = new Set(prev);
            if (next.has(id)) next.delete(id); else next.add(id);
            return next;
        });

    const fetchUnits = async () => {
        try {
            const res = await fetch("/api/proxy/v1/units");
            if (res.ok) {
                const data = await res.json();
                setUnits(data.filter((u: Unit) => u.status === 'VACANT'));
            }
        } catch (err) {
            console.error(err);
        }
    };

    const fetchRenters = async () => {
        try {
            const res = await fetch("/api/proxy/v1/renters");
            if (res.ok) setRenters(await res.json());
        } catch (err) {
            console.error(err);
        }
    };

    const handleEditDraft = (lease: Lease) => {
        // The detail page is the single edit surface — it has the metadata
        // editor (mirroring all the inline form fields), the schedule editor,
        // attachments, documents, and contract-generation in one place.
        router.push(`/dashboard/leases/${lease.id}`);
    };

    const handleDeleteDraft = (id: string) => {
        setConfirmConfig({
            title: t("deleteDraftTitle"),
            description: "This will permanently remove the draft lease, its payment schedule, attachments, and history. This action cannot be undone.",
            confirmText: t("delete"),
            isDestructive: true,
            onConfirm: async () => {
                setActionLoading(`delete-${id}`);
                try {
                    const res = await fetch(`/api/proxy/v1/leases/${id}`, { method: "DELETE" });
                    if (res.ok) {
                        fetchLeases();
                    } else if (res.status === 403) {
                        alert(t("noPermissionDeleteLease"));
                    } else {
                        let detail: string | null = null;
                        try {
                            const body = await res.json();
                            detail = body?.message || body?.error || null;
                        } catch {}
                        alert(detail || t("failedDeleteDraft"));
                    }
                } catch (err) {
                    console.error(err);
                    alert("Network error while deleting the draft. Check your connection and try again.");
                } finally {
                    setActionLoading(null);
                    setConfirmOpen(false);
                }
            }
        });
        setConfirmOpen(true);
    };

    // PUT /leases/{id}/activate is gone. A contract used to become ACTIVE by a
    // status change with no journal behind it, so "active" and "on the books"
    // were two separate truths about the same contract. Post is the only path
    // now, and it lives on the detail page behind a dry run -- or here, in the
    // bulk run above.

    const router = useRouter();

    /**
     * Termination is a priced decision now, not a confirm dialog (spec §9.1):
     * `POST /leases/{id}/terminate` takes a date and a complete return/keep
     * answer for every uncleared instrument, so there is nothing a list row can
     * usefully send. It opens the termination page instead.
     *
     * What used to be here — `{notes: "…"}` posted straight at `/terminate`,
     * unreachable behind an early `return` — was already dead, and its body is
     * now a 400 ("A termination needs a date").
     */
    const handleTerminate = (id: string) => {
        router.push(`/dashboard/leases/${id}/terminate`);
    };

    const handleGenerateContract = async (id: string) => {
        setActionLoading(`generate-${id}`);
        try {
            const res = await fetch(`/api/proxy/v1/leases/${id}/generate-contract`, { method: "POST" });
            if (res.ok) {
                fetchLeases();
            } else if (res.status === 403) {
                alert("You don't have permission to generate a contract for this lease.");
            } else {
                let detail: string | null = null;
                try {
                    const body = await res.json();
                    detail = body?.message || body?.error || null;
                } catch {}
                alert(detail || t("failedGenerateContract"));
            }
        } catch (err) {
            console.error(err);
            alert("Network error while generating contract. Check your connection and try again.");
        } finally {
            setActionLoading(null);
        }
    };

    const handleDownloadContract = async (id: string) => {
        setActionLoading(`download-${id}`);
        try {
            const res = await fetch(`/api/proxy/v1/leases/${id}/documents`);
            if (res.ok) {
                const docs = await res.json();
                if (docs.length > 0) {
                    const pdfRes = await fetch(`/api/proxy/v1/leases/documents/${docs[0].id}/download`);
                    if (pdfRes.ok) {
                        const blob = await pdfRes.blob();
                        const url = URL.createObjectURL(blob);
                        const a = document.createElement('a');
                        a.href = url;
                        a.download = `contract-${id}.pdf`;
                        document.body.appendChild(a);
                        a.click();
                        document.body.removeChild(a);
                        URL.revokeObjectURL(url);
                    }
                }
            }
        } catch (err) {
            console.error(err);
        } finally {
            setActionLoading(null);
        }
    };

    const openDocsModal = (leaseId: string) => {
        setDocsLeaseId(leaseId);
        setDocName("");
        fetchAttachments(leaseId);
    };

    const closeDocsModal = () => {
        setDocsLeaseId(null);
        setAttachments([]);
        setDocName("");
    };

    const fetchAttachments = async (leaseId: string) => {
        try {
            const res = await fetch(`/api/proxy/v1/leases/${leaseId}/attachments`);
            if (res.ok) setAttachments(await res.json());
        } catch {}
    };

    const handleDocUpload = async (leaseId: string, file: File) => {
        if (!docName.trim()) return;
        setUploadingDoc(true);
        try {
            const formData = new FormData();
            formData.append("file", file);
            formData.append("name", docName);
            const res = await fetch(`/api/upload?path=/api/v1/leases/${leaseId}/attachments`, {
                method: "POST",
                body: formData,
            });
            if (res.ok) {
                setDocName("");
                fetchAttachments(leaseId);
            }
        } catch {} finally {
            setUploadingDoc(false);
        }
    };

    const handleDocDelete = async (attachmentId: string, leaseId: string) => {
        try {
            const res = await fetch(`/api/proxy/v1/leases/attachments/${attachmentId}`, { method: "DELETE" });
            if (res.ok) fetchAttachments(leaseId);
        } catch {}
    };

    const handleDocDownload = async (attachmentId: string, fileName: string) => {
        const res = await fetch(`/api/proxy/v1/leases/attachments/${attachmentId}/download`);
        if (res.ok) {
            const blob = await res.blob();
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = fileName;
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            URL.revokeObjectURL(url);
        }
    };

    const getRenterDisplayName = (r: Renter) => {
        if (locale === 'ar' && r.nameAr) return r.nameAr;
        return r.nameEn;
    };

    // #79: a PENDING_SIGNATURE lease the renter has already accepted is ready to
    // post; without this the list cannot tell it from one still waiting on them.
    const acceptedBadge = (lease: Lease) =>
        lease.status === "PENDING_SIGNATURE" && lease.renterAcceptedAt ? (
            <span data-testid="lease-renter-accepted" className="mt-1 inline-flex items-center px-2 py-0.5 rounded-full text-[9px] font-semibold bg-success/10 text-success border border-success/20">
                {tl("acceptedByRenter", { date: fmtIsoDate(lease.renterAcceptedAt, locale) })}
            </span>
        ) : null;

    const getStatusColor = (status: string) => {
        switch (status) {
            case 'ACTIVE': return 'bg-success/10 text-success border border-success/20';
            case 'DRAFT': return 'bg-input text-muted border border-border';
            case 'PENDING_SIGNATURE': return 'bg-warning/10 text-warning border border-warning/20';
            case 'NOTICE_GIVEN': return 'bg-warning/10 text-warning border border-warning/20';
            case 'TERMINATED': return 'bg-error/10 text-error border border-error/20';
            case 'EXPIRED': return 'bg-warning/10 text-warning border border-warning/20';
            case 'CLOSED': return 'bg-input text-muted border border-border';
            default: return 'bg-info/10 text-info border border-info/20';
        }
    };

    const renderChequeProgress = (leaseId: string) => {
        const stats = chequeStats[leaseId];

        if (chequeStatsLoading && !stats) {
            return (
                <div className="mt-3 pt-3 border-t border-border">
                    <div className="h-1.5 bg-input rounded-full overflow-hidden animate-pulse" />
                    <div className="mt-1.5 h-3 w-24 bg-input rounded animate-pulse" />
                </div>
            );
        }

        if (!stats) return null;

        const progressPercent = stats.total > 0 ? Math.round((stats.cleared / stats.total) * 100) : 0;

        return (
            <div className="mt-3 pt-3 border-t border-border">
                <div className="h-1.5 bg-input rounded-full overflow-hidden">
                    <div className="h-full bg-success rounded-full transition-all duration-500" style={{ width: `${progressPercent}%` }} />
                </div>
                <div className="flex items-center gap-2 mt-1.5">
                    <span className="text-xs font-medium text-muted">
                        {stats.cleared}/{stats.total} {t("paymentsProgress")}
                    </span>
                    {stats.bounced > 0 && (
                        <span className="inline-flex items-center px-1.5 py-0.5 rounded-full text-[9px] font-bold bg-error/10 text-error border border-error/20">
                            {stats.bounced} {t("overduePayments")}
                        </span>
                    )}
                </div>
            </div>
        );
    };

    const renderSkeletonCard = (compact = false) => (
        <div className={cn("bg-surface rounded-xl p-6 border border-border animate-pulse", compact && "p-4")}>
            <div className="flex items-center gap-3 mb-4">
                <div className={cn("bg-input rounded-xl", compact ? "w-8 h-8" : "w-10 h-10")} />
                <div className="flex-1">
                    <div className="h-3.5 bg-input rounded w-24 mb-1.5" />
                    <div className="h-2.5 bg-input rounded w-16" />
                </div>
                {!compact && <div className="h-5 bg-input rounded-full w-20" />}
            </div>
            <div className="space-y-3 mb-4">
                <div className={cn("bg-input rounded-xl border border-border", compact ? "h-10" : "h-14")} />
                <div className="h-4 bg-input rounded w-48 mx-1" />
            </div>
            <div className="border-t border-border pt-4">
                <div className="h-9 bg-input rounded-xl" />
            </div>
        </div>
    );

    const renderLeaseCard = (lease: Lease, compact = false) => (
        <div
            key={lease.id}
            role="button"
            tabIndex={0}
            aria-label={`${t("unit")} ${lease.unitIdentifier} — ${lease.renterName}`}
            onClick={() => router.push(`/dashboard/leases/${lease.id}`)}
            onKeyDown={(e) => {
                if (e.key === "Enter" || e.key === " ") {
                    e.preventDefault();
                    router.push(`/dashboard/leases/${lease.id}`);
                }
            }}
            className={cn("bg-surface rounded-xl p-5 border border-border hover:shadow-md hover:border-primary/40 transition-all duration-200 flex flex-col justify-between cursor-pointer focus:outline-none focus:ring-2 focus:ring-primary/30", compact && "p-4")}>
            <div>
                <div className="flex justify-between items-start mb-4">
                    <div className="flex items-center gap-3">
                        <div className={cn("w-10 h-10 bg-primary/10 rounded-xl flex items-center justify-center text-primary border border-primary/20", compact && "w-8 h-8 rounded-lg")}>
                            <FileText size={compact ? 14 : 18} />
                        </div>
                        <div>
                            <h3 className={cn("font-bold text-foreground tracking-tight", compact ? "text-xs" : "text-sm")}>{t("unit")} {lease.unitIdentifier}</h3>
                            <p className="text-[10px] font-bold text-muted">{lease.renterName}</p>
                        </div>
                    </div>
                    {!compact && (
                        <div className="flex flex-col items-end gap-1">
                            <span className={cn("inline-flex items-center px-2.5 py-1 rounded-full text-[9px] font-bold uppercase tracking-widest border", getStatusColor(lease.status))}>
                                {tl(`leaseStatus.${lease.status}`)}
                            </span>
                            {acceptedBadge(lease)}
                        </div>
                    )}
                </div>

                <div className={cn("space-y-3", compact ? "mb-3" : "mb-6")}>
                    <div className={cn("flex justify-between items-center bg-input/70 rounded-xl border border-border", compact ? "p-2" : "p-3")}>
                        <div className="flex items-center gap-2">
                            <DollarSign size={14} className="text-muted" />
                            <span className="text-[10px] font-semibold text-muted uppercase tracking-[0.15em]">{t("rentSummary")}</span>
                        </div>
                        <span className={cn("font-bold text-foreground text-right tabular-nums", compact ? "text-[10px]" : "text-xs")}>
                            {formatCurrencyCompact(lease.contractValue ?? 0)}
                            {!compact && <><br /><span className="text-[9px] text-muted font-medium">({lease.paymentTerms} {t("cheques")})</span></>}
                        </span>
                    </div>
                    <div className="flex items-center gap-3 px-1">
                        <Calendar size={14} className="text-muted" />
                        <span className="text-xs font-medium text-foreground">
                            {new Date(lease.startDate).toLocaleDateString()} &rarr; {new Date(lease.endDate).toLocaleDateString()}
                        </span>
                    </div>
                    {!compact && lease.ejariNumber && (
                        <div className="flex items-center gap-3 px-1">
                            <Home size={14} className="text-muted" />
                            <span className="text-xs font-medium text-foreground">Ejari: {lease.ejariNumber}</span>
                        </div>
                    )}
                </div>

                {!compact && (lease.status === 'ACTIVE' || lease.status === 'NOTICE_GIVEN') && renderChequeProgress(lease.id)}
            </div>

            {/*
              The card's action bar used to hang off `canManageLeases` alone, so
              every button inside it inherited the SA/TA draft-lease key —
              including Terminate, which belongs to canPreviewTermination, and
              Post, which belongs to canPostLeases. Each button now carries its
              own gate and the bar shows when any of them would.
            */}
            {(canManageLeases || canPostLeases || canPreviewTermination) && (
                <div onClick={(e) => e.stopPropagation()} className={cn("flex gap-2 border-t border-border mt-auto", compact ? "pt-3 flex-wrap" : "pt-4")}>
                    {lease.status === 'DRAFT' && canManageLeases && (
                        <button
                            data-testid={`lease-card-edit-${lease.id}`}
                            onClick={() => handleEditDraft(lease)}
                            className="flex items-center justify-center gap-2 bg-input text-foreground hover:bg-input/80 py-2.5 px-3 rounded-xl text-xs font-bold transition-all duration-200 cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none"
                        >
                            <Pencil size={14} />
                            {t("edit")}
                        </button>
                    )}
                    {lease.status === 'DRAFT' && canManageLeases && (
                        <button
                            onClick={() => handleDeleteDraft(lease.id)}
                            disabled={actionLoading === `delete-${lease.id}`}
                            title={t("deleteDraft")}
                            className="flex items-center justify-center gap-2 bg-error/10 text-error hover:bg-error/20 py-2.5 px-3 rounded-xl text-xs font-bold transition-all duration-200 cursor-pointer focus:ring-2 focus:ring-error/30 focus:outline-none disabled:opacity-50"
                        >
                            {actionLoading === `delete-${lease.id}` ? <Loader2 size={14} className="animate-spin" /> : <Trash2 size={14} />}
                        </button>
                    )}
                    {lease.status === 'DRAFT' && !lease.hasContract && canManageLeases && (
                        <button
                            onClick={() => handleGenerateContract(lease.id)}
                            disabled={actionLoading === `generate-${lease.id}`}
                            className="flex-1 flex items-center justify-center gap-2 bg-blue-50 text-blue-700 hover:bg-blue-100 py-2.5 rounded-xl text-xs font-bold transition-all duration-200 cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none disabled:opacity-50"
                        >
                            {actionLoading === `generate-${lease.id}` ? <Loader2 size={14} className="animate-spin" /> : <Sparkles size={14} />}
                            {t("generateContract")}
                        </button>
                    )}
                    {(lease.status === 'DRAFT' || lease.status === 'PENDING_SIGNATURE') && canPostLeases && (
                        <button
                            onClick={() => router.push(`/dashboard/leases/${lease.id}`)}
                            className="flex-1 flex items-center justify-center gap-2 bg-green-50 text-green-700 hover:bg-green-100 py-2.5 rounded-xl text-xs font-bold transition-all duration-200 cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none"
                        >
                            <CheckCircle size={14} />
                            {tl("postLease")}
                        </button>
                    )}
                    {lease.status === 'PENDING_SIGNATURE' && canManageLeases && (
                        <>
                            <button
                                onClick={() => handleDownloadContract(lease.id)}
                                disabled={actionLoading === `download-${lease.id}`}
                                className="flex-1 flex items-center justify-center gap-2 bg-blue-50 text-blue-700 hover:bg-blue-100 py-2.5 rounded-xl text-xs font-bold transition-all duration-200 cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none disabled:opacity-50"
                            >
                                {actionLoading === `download-${lease.id}` ? <Loader2 size={14} className="animate-spin" /> : <Download size={14} />}
                                {t("downloadContract")}
                            </button>
                            <button
                                onClick={() => handleGenerateContract(lease.id)}
                                disabled={actionLoading === `generate-${lease.id}`}
                                className="flex items-center justify-center gap-1.5 bg-amber-50 text-amber-700 hover:bg-amber-100 py-2.5 px-3 rounded-xl text-xs font-bold transition-all duration-200 cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none disabled:opacity-50"
                                title={t("regenerateContract")}
                            >
                                {actionLoading === `generate-${lease.id}` ? <Loader2 size={14} className="animate-spin" /> : <RefreshCw size={14} />}
                            </button>
                        </>
                    )}
                    {TERMINABLE.includes(lease.status) && canPreviewTermination && (
                        <button
                            data-testid={`lease-card-terminate-${lease.id}`}
                            onClick={() => handleTerminate(lease.id)}
                            className="flex-1 flex items-center justify-center gap-2 bg-red-50 text-red-600 hover:bg-red-100 py-2.5 rounded-xl text-xs font-bold transition-all duration-200 cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none"
                        >
                            <Ban size={14} />
                            {t("terminate")}
                        </button>
                    )}
                    <button
                        onClick={() => openDocsModal(lease.id)}
                        className="flex-1 flex items-center justify-center gap-2 bg-input text-foreground hover:bg-border py-2.5 rounded-xl text-xs font-bold transition-all duration-200 cursor-pointer"
                    >
                        <FileText size={14} />
                        {t("docs")}
                    </button>
                </div>
            )}
        </div>
    );

    // `status` is a server-side param on GET /leases/paged, so a filter change
    // is a re-fetch, not a re-slice — which is why it resets to page 1: the
    // page number it was chosen on no longer means anything against the new,
    // narrower total.
    if (userRole && !canViewLeases) {
        return (
            <div className="text-center py-24" data-testid="leases-access-denied">
                <p className="text-sm text-muted">{tl("accessDenied")}</p>
            </div>
        );
    }

    // `leases` is already the server's filtered page — `status` rode along on
    // the request above, so there is nothing left to filter client-side.
    const filteredLeases = leases;
    const selectableDrafts = filteredLeases.filter(l => l.status === "DRAFT");

    return (
        <div>
            <div className="flex flex-col gap-4 mb-10">
                <div>
                    <h1 className="text-xl font-bold text-foreground tracking-tight mb-1">
                        {t("leases")}
                    </h1>
                    <p className="text-xs text-muted font-medium">
                        {t("manageLeases")}
                    </p>
                </div>
                <div className="flex flex-col md:flex-row md:flex-wrap md:items-center justify-between gap-3">
                    <div className="relative">
                        <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-muted" />
                        <input
                            type="text"
                            placeholder={t("search")}
                            value={searchQuery}
                            onChange={(e) => setSearchQuery(e.target.value)}
                            className="pl-9 pr-4 py-2 bg-surface border border-border rounded-lg text-sm text-foreground placeholder:text-muted/50 focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none w-64 transition-all"
                        />
                    </div>
                    <div className="flex flex-wrap items-center gap-3">
                    <select
                        aria-label={t("status")}
                        data-testid="lease-status-filter"
                        value={statusFilter}
                        onChange={(e) => setStatusFilter(e.target.value as LeaseStatus | "")}
                        className="bg-surface border border-border rounded-lg px-3 py-2 text-xs text-foreground cursor-pointer focus:ring-2 focus:ring-primary/20 focus:outline-none"
                    >
                        <option value="">{tl("allStatuses")}</option>
                        {STATUSES.map((st) => (
                            <option key={st} value={st}>{tl(`leaseStatus.${st}`)}</option>
                        ))}
                    </select>
                    <div className="flex items-center bg-input rounded-lg p-0.5 border border-border">
                        <button
                            data-testid="lease-view-table"
                            onClick={() => { setViewMode('table'); setCurrentPage(1); }}
                            className={cn(
                                "px-3 py-1.5 rounded-md text-xs font-medium transition-all cursor-pointer flex items-center gap-1.5",
                                viewMode === 'table' ? "bg-surface text-foreground shadow-sm border border-border" : "text-muted hover:text-foreground"
                            )}
                        >
                            <List size={13} />
                            {t("table")}
                        </button>
                        <button
                            data-testid="lease-view-cards"
                            onClick={() => { setViewMode('cards'); setCurrentPage(1); }}
                            className={cn(
                                "px-3 py-1.5 rounded-md text-xs font-medium transition-all cursor-pointer flex items-center gap-1.5",
                                viewMode === 'cards' ? "bg-surface text-foreground shadow-sm border border-border" : "text-muted hover:text-foreground"
                            )}
                        >
                            <LayoutGrid size={13} />
                            {t("cards")}
                        </button>
                        <button
                            data-testid="lease-view-board"
                            onClick={() => setViewMode('board')}
                            className={cn(
                                "px-3 py-1.5 rounded-md text-xs font-medium transition-all cursor-pointer flex items-center gap-1.5",
                                viewMode === 'board' ? "bg-surface text-foreground shadow-sm border border-border" : "text-muted hover:text-foreground"
                            )}
                        >
                            <Columns3 size={13} />
                            {t("board")}
                        </button>
                    </div>
                    {canPostLeases && selected.size > 0 && (
                        <button
                            onClick={handleBulkPost}
                            disabled={!!postProgress}
                            data-testid="bulk-post"
                            className="flex items-center gap-2 bg-accent text-accent-foreground px-4 py-2 rounded-lg text-xs font-semibold hover:brightness-110 transition-all duration-200 cursor-pointer disabled:opacity-50 focus:ring-2 focus:ring-primary/30 focus:outline-none"
                        >
                            {postProgress ? <Loader2 size={14} className="animate-spin" /> : <CheckCircle size={14} />}
                            {postProgress
                                ? tl("bulkPostRunning", { done: postProgress.done, total: postProgress.total })
                                : tl("bulkPost", { n: selected.size })}
                        </button>
                    )}
                    {canManageLeases && (
                        <button
                            onClick={() => { setEditingLeaseId(null); setWizardOpen(true); }}
                            className="flex items-center gap-2 bg-primary text-primary-foreground px-4 py-2 rounded-lg text-xs font-semibold hover:opacity-90 transition-all duration-200 cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none"
                        >
                            <Plus size={14} />
                            {t("draftLease")}
                        </button>
                    )}
                    </div>
                </div>
            </div>

            {wizardOpen && (
                <LeaseWizard
                    open={wizardOpen}
                    units={units}
                    renters={renters}
                    onClose={() => setWizardOpen(false)}
                    onCreated={() => { fetchLeases(); fetchUnits(); }}
                />
            )}


            {/* Skeleton Loading */}
            {loading && (
                <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-6">
                    {[1, 2, 3, 4, 5, 6].map(i => (
                        <div key={i}>{renderSkeletonCard()}</div>
                    ))}
                </div>
            )}

            {/* Table View */}
            {!loading && viewMode === 'table' && leases.length > 0 && (() => {
                return (
                    <>
                        <div className="bg-surface rounded-xl border border-border overflow-hidden">
                            <table className="w-full">
                                <thead>
                                    <tr className="bg-input/50">
                                        {canPostLeases && (
                                            <th className="px-4 py-3 w-8">
                                                <input
                                                    type="checkbox"
                                                    aria-label={tl("bulkPost", { n: selectableDrafts.length })}
                                                    data-testid="bulk-post-select-all"
                                                    disabled={selectableDrafts.length === 0}
                                                    checked={selectableDrafts.length > 0 && selectableDrafts.every(l => selected.has(l.id))}
                                                    onChange={e =>
                                                        setSelected(e.target.checked ? new Set(selectableDrafts.map(l => l.id)) : new Set())
                                                    }
                                                />
                                            </th>
                                        )}
                                        <th className="text-start px-4 py-3 text-[11px] font-semibold text-muted uppercase tracking-wider">{t("unit")}</th>
                                        <th className="text-left px-4 py-3 text-[11px] font-semibold text-muted uppercase tracking-wider">{t("renter")}</th>
                                        <th className="text-left px-4 py-3 text-[11px] font-semibold text-muted uppercase tracking-wider">{t("property")}</th>
                                        <th className="text-left px-4 py-3 text-[11px] font-semibold text-muted uppercase tracking-wider">{t("startDate")}</th>
                                        <th className="text-left px-4 py-3 text-[11px] font-semibold text-muted uppercase tracking-wider">{t("endDate")}</th>
                                        <th className="text-end px-4 py-3 text-[11px] font-semibold text-muted uppercase tracking-wider">{tl("contractValue")}</th>
                                        <th className="text-start px-4 py-3 text-[11px] font-semibold text-muted uppercase tracking-wider">{tl("chain")}</th>
                                        <th className="text-center px-4 py-3 text-[11px] font-semibold text-muted uppercase tracking-wider">{t("status")}</th>
                                        <th className="text-center px-4 py-3 text-[11px] font-semibold text-muted uppercase tracking-wider">{t("actions")}</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {filteredLeases.map(lease => {
                                        return (
                                            <tr
                                                key={lease.id}
                                                data-testid={`lease-row-${lease.id}`}
                                                onClick={() => router.push(`/dashboard/leases/${lease.id}`)}
                                                className="border-b border-border hover:bg-input/30 transition-colors cursor-pointer"
                                            >
                                                {canPostLeases && (
                                                    <td className="px-4 py-3" onClick={(e) => e.stopPropagation()}>
                                                        {lease.status === 'DRAFT' && (
                                                            <input
                                                                type="checkbox"
                                                                aria-label={`${t("unit")} ${lease.unitIdentifier}`}
                                                                data-testid={`bulk-post-select-${lease.id}`}
                                                                checked={selected.has(lease.id)}
                                                                onChange={() => toggleSelected(lease.id)}
                                                            />
                                                        )}
                                                    </td>
                                                )}
                                                <td className="px-4 py-3 text-xs font-medium text-foreground">{lease.unitIdentifier}</td>
                                                <td className="px-4 py-3 text-xs text-foreground">{lease.renterName}</td>
                                                <td className="px-4 py-3 text-xs text-muted">{lease.propertyName}</td>
                                                <td className="px-4 py-3 text-xs text-foreground tabular-nums">{new Date(lease.startDate).toLocaleDateString()}</td>
                                                <td className="px-4 py-3 text-xs text-foreground tabular-nums">{new Date(lease.endDate).toLocaleDateString()}</td>
                                                <td className="px-4 py-3 text-xs font-medium text-foreground text-end tabular-nums">{formatCurrency(lease.contractValue ?? 0)}</td>
                                                <td className="px-4 py-3 text-[10px] text-muted font-mono">
                                                    {lease.chainId ? lease.chainId.slice(0, 8) : "—"}
                                                    {lease.renewedFromLeaseId && (
                                                        <span className="ms-1 text-primary">&#8599;</span>
                                                    )}
                                                </td>
                                                <td className="px-4 py-3 text-center">
                                                    <span className={cn("inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-semibold", getStatusColor(lease.status))}>
                                                        {tl(`leaseStatus.${lease.status}`)}
                                                    </span>
                                                    {acceptedBadge(lease)}
                                                </td>
                                                <td className="px-4 py-3 text-center" onClick={(e) => e.stopPropagation()}>
                                                    <div className="flex items-center justify-center gap-1.5">
                                                        {lease.status === 'DRAFT' && canManageLeases && (
                                                            <button
                                                                onClick={() => handleEditDraft(lease)}
                                                                className="inline-flex items-center gap-1 px-2 py-1 rounded-md text-[10px] font-medium bg-input text-foreground hover:bg-input/80 transition-colors cursor-pointer"
                                                            >
                                                                <Pencil size={11} /> {t("edit")}
                                                            </button>
                                                        )}
                                                        {lease.status === 'DRAFT' && canManageLeases && (
                                                            <button
                                                                onClick={() => handleDeleteDraft(lease.id)}
                                                                disabled={actionLoading === `delete-${lease.id}`}
                                                                title={t("deleteDraft")}
                                                                className="inline-flex items-center gap-1 px-2 py-1 rounded-md text-[10px] font-medium bg-error/10 text-error hover:bg-error/20 transition-colors cursor-pointer disabled:opacity-50"
                                                            >
                                                                {actionLoading === `delete-${lease.id}` ? <Loader2 size={11} className="animate-spin" /> : <Trash2 size={11} />}
                                                            </button>
                                                        )}
                                                        {lease.status === 'DRAFT' && !lease.hasContract && canManageLeases && (
                                                            <button
                                                                onClick={() => handleGenerateContract(lease.id)}
                                                                disabled={actionLoading === `generate-${lease.id}`}
                                                                className="inline-flex items-center gap-1 px-2 py-1 rounded-md text-[10px] font-medium bg-blue-50 text-blue-700 hover:bg-blue-100 transition-colors cursor-pointer disabled:opacity-50"
                                                            >
                                                                {actionLoading === `generate-${lease.id}` ? <Loader2 size={11} className="animate-spin" /> : <Sparkles size={11} />} {t("generate")}
                                                            </button>
                                                        )}
                                                        {(lease.status === 'DRAFT' || lease.status === 'PENDING_SIGNATURE') && canPostLeases && (
                                                            <Link
                                                                href={`/dashboard/leases/${lease.id}`}
                                                                className="inline-flex items-center gap-1 px-2 py-1 rounded-md text-[10px] font-medium bg-green-50 text-green-700 hover:bg-green-100 transition-colors"
                                                            >
                                                                <CheckCircle size={11} /> {tl("postLease")}
                                                            </Link>
                                                        )}
                                                        {lease.status === 'PENDING_SIGNATURE' && canManageLeases && (
                                                            <button
                                                                onClick={() => handleDownloadContract(lease.id)}
                                                                disabled={actionLoading === `download-${lease.id}`}
                                                                className="inline-flex items-center gap-1 px-2 py-1 rounded-md text-[10px] font-medium bg-blue-50 text-blue-700 hover:bg-blue-100 transition-colors cursor-pointer disabled:opacity-50"
                                                            >
                                                                {actionLoading === `download-${lease.id}` ? <Loader2 size={11} className="animate-spin" /> : <Download size={11} />} PDF
                                                            </button>
                                                        )}
                                                        {TERMINABLE.includes(lease.status) && canPreviewTermination && (
                                                            <button
                                                                data-testid={`lease-list-terminate-${lease.id}`}
                                                                onClick={() => handleTerminate(lease.id)}
                                                                className="inline-flex items-center gap-1 px-2 py-1 rounded-md text-[10px] font-medium bg-red-50 text-red-600 hover:bg-red-100 transition-colors cursor-pointer"
                                                            >
                                                                <Ban size={11} /> {t("terminate")}
                                                            </button>
                                                        )}
                                                        <button
                                                            onClick={() => openDocsModal(lease.id)}
                                                            className="inline-flex items-center gap-1 px-2 py-1 rounded-md text-[10px] font-medium bg-input text-foreground hover:bg-border transition-colors cursor-pointer"
                                                        >
                                                            <FileText size={11} /> {t("docs")}
                                                        </button>
                                                        <Link
                                                            href={`/dashboard/leases/${lease.id}`}
                                                            className="inline-flex items-center gap-1 px-2 py-1 rounded-md text-[10px] font-medium text-primary hover:bg-primary/10 transition-colors"
                                                        >
                                                            {t("view")}
                                                        </Link>
                                                    </div>
                                                </td>
                                            </tr>
                                        );
                                    })}
                                </tbody>
                            </table>
                        </div>
                        <Pagination
                            currentPage={currentPage}
                            totalItems={totalItems}
                            itemsPerPage={itemsPerPage}
                            onPageChange={setCurrentPage}
                            onItemsPerPageChange={(n) => { setItemsPerPage(n); setCurrentPage(1); }}
                        />
                    </>
                );
            })()}

            {/* Cards View */}
            {!loading && viewMode === 'cards' && leases.length > 0 && (() => {
                return (
                    <>
                        <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-6">
                            {filteredLeases.map(lease => renderLeaseCard(lease))}
                        </div>
                        <Pagination
                            currentPage={currentPage}
                            totalItems={totalItems}
                            itemsPerPage={itemsPerPage}
                            onPageChange={setCurrentPage}
                            onItemsPerPageChange={(n) => { setItemsPerPage(n); setCurrentPage(1); }}
                        />
                    </>
                );
            })()}

            {/* Board View */}
            {!loading && viewMode === 'board' && (
                <>
                    <div className="grid grid-cols-2 lg:grid-cols-4 gap-4 pb-4">
                        {BOARD_COLUMNS.map(col => {
                            const columnLeases = filteredLeases.filter(l => col.statuses.includes(l.status));
                            return (
                                <div key={col.key} className="min-w-0">
                                    <div className="flex items-center gap-2 mb-4 px-2">
                                        <div className={cn("w-2.5 h-2.5 rounded-full", col.color)} />
                                        <h3 className="text-xs font-bold text-foreground uppercase tracking-widest">{t(col.labelKey)}</h3>
                                        <span className="ml-auto text-[10px] font-bold text-muted bg-input px-2 py-0.5 rounded-full">{columnLeases.length}</span>
                                    </div>
                                    <div className="space-y-3 min-h-[200px] bg-background rounded-xl p-3 border border-border">
                                        {columnLeases.map(lease => renderLeaseCard(lease, true))}
                                        {columnLeases.length === 0 && (
                                            <div className="text-center py-8 text-[10px] text-muted font-bold uppercase tracking-widest">
                                                {t("noLeases")}
                                            </div>
                                        )}
                                    </div>
                                </div>
                            );
                        })}
                    </div>
                    <Pagination
                        currentPage={currentPage}
                        totalItems={totalItems}
                        itemsPerPage={itemsPerPage}
                        onPageChange={setCurrentPage}
                        onItemsPerPageChange={(n) => { setItemsPerPage(n); setCurrentPage(1); }}
                    />
                </>
            )}

            {!loading && leases.length === 0 && (
                <div className="text-center py-24 bg-background border border-dashed border-border rounded-xl flex flex-col items-center">
                    <div className="w-16 h-16 bg-surface rounded-xl flex items-center justify-center text-muted shadow-sm mb-6">
                        <AlertCircle size={32} />
                    </div>
                    <p className="text-sm font-bold text-muted mb-6 uppercase tracking-widest">
                        {t("noLeasesFound")}
                    </p>
                    {canManageLeases && (
                        <button onClick={() => { setEditingLeaseId(null); setWizardOpen(true); }} className="text-xs font-bold text-foreground border-b-2 border-primary pb-0.5 hover:text-primary transition-all duration-200 cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none">
                            {t("draftALease")}
                        </button>
                    )}
                </div>
            )}

            {/* Standalone Documents Modal */}
            {docsLeaseId && !editingLeaseId && (
                <div className="fixed inset-0 bg-foreground/40 backdrop-blur-sm flex items-center justify-center p-4 z-[100]">
                    <div className="bg-surface rounded-xl p-6 max-w-lg w-full shadow-2xl border border-border relative max-h-[80vh] overflow-y-auto">
                        <button
                            onClick={closeDocsModal}
                            aria-label={t("close")}
                            className="absolute right-4 top-4 p-2 text-muted hover:text-foreground transition-all cursor-pointer rounded-lg"
                        >
                            <X size={18} />
                        </button>
                        <h2 className="text-lg font-bold text-foreground mb-1">{t("supportingDocuments")}</h2>
                        <p className="text-xs text-muted mb-5">{t("supportingDocumentsDesc")}</p>

                        {/* Existing attachments */}
                        {attachments.length > 0 && (
                            <div className="space-y-2 mb-4">
                                {attachments.map(doc => (
                                    <div key={doc.id} className="flex items-center justify-between bg-input/50 rounded-lg px-3 py-2.5 border border-border">
                                        <div className="flex items-center gap-2 min-w-0">
                                            <FileText size={14} className="text-muted shrink-0" />
                                            <div className="min-w-0">
                                                <p className="text-xs font-medium text-foreground truncate">{doc.name}</p>
                                                <p className="text-[10px] text-muted">{doc.fileType} &bull; {(doc.fileSize / 1024).toFixed(0)} KB</p>
                                            </div>
                                        </div>
                                        <div className="flex items-center gap-2 shrink-0">
                                            <button onClick={() => handleDocDownload(doc.id, doc.name)} className="text-[10px] font-semibold text-primary hover:text-primary/80 cursor-pointer">{t("download")}</button>
                                            <button onClick={() => handleDocDelete(doc.id, docsLeaseId)} className="text-[10px] font-semibold text-error hover:text-error/80 cursor-pointer">{t("delete")}</button>
                                        </div>
                                    </div>
                                ))}
                            </div>
                        )}

                        {attachments.length === 0 && (
                            <div className="text-center py-6 text-muted mb-4">
                                <FileText size={24} className="mx-auto mb-2 opacity-40" />
                                <p className="text-xs">{t("noDocumentsYet")}</p>
                            </div>
                        )}

                        {/* Upload new */}
                        <div className="pt-3 border-t border-border">
                            <p className="text-[10px] text-muted mb-2">{t("documentsHint")}</p>
                            <div className="flex items-center gap-2">
                                <input
                                    type="text"
                                    value={docName}
                                    onChange={(e) => setDocName(e.target.value)}
                                    placeholder={t("documentNamePlaceholder")}
                                    className="flex-1 border border-border rounded-lg bg-surface px-3 py-2 text-xs text-foreground placeholder:text-muted/50 focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none"
                                />
                                <label className={cn(
                                    "flex items-center gap-1.5 px-3 py-2 rounded-lg text-xs font-semibold transition-colors shrink-0",
                                    docName.trim() && !uploadingDoc
                                        ? "bg-primary text-primary-foreground hover:bg-primary/90 cursor-pointer"
                                        : "bg-input text-muted cursor-not-allowed"
                                )}>
                                    {uploadingDoc ? <Loader2 size={12} className="animate-spin" /> : <Upload size={12} />}
                                    Attach File
                                    <input
                                        type="file"
                                        className="hidden"
                                        disabled={!docName.trim() || uploadingDoc}
                                        onChange={(e) => {
                                            const file = e.target.files?.[0];
                                            if (file && docsLeaseId) handleDocUpload(docsLeaseId, file);
                                            if (e.target) e.target.value = "";
                                        }}
                                    />
                                </label>
                            </div>
                        </div>
                    </div>
                </div>
            )}

            {/*
                The bulk run's outcome, row by row. "3 of 7 posted" with no word
                on which three is not an answer an accountant can act on, so a
                success carries the TCO number it was given and a failure carries
                the backend's own message.
            */}
            <LeaseDialog
                open={!!postResults}
                title={tl("bulkPostResults")}
                onClose={() => setPostResults(null)}
                onConfirm={() => setPostResults(null)}
                confirmText={tl("close")}
                cancelText={t("cancel")}
                confirmTestId="bulk-post-results-close"
                width="lg"
            >
                <ul className="space-y-2 text-xs" data-testid="bulk-post-results">
                    {(postResults ?? []).map((r) => (
                        <li
                            key={r.leaseId}
                            data-testid={`bulk-post-result-${r.leaseId}`}
                            data-ok={r.ok ? "true" : "false"}
                            className={cn(
                                "flex items-start justify-between gap-3 rounded-lg border px-3 py-2",
                                r.ok ? "border-success/30 bg-success/5" : "border-error/30 bg-error/5",
                            )}
                        >
                            <span className="text-foreground">{r.label}</span>
                            <span className={cn("text-end font-semibold", r.ok ? "text-success" : "text-error")}>
                                {r.ok ? tl("postedBanner", { tco: r.message }) : r.message}
                            </span>
                        </li>
                    ))}
                </ul>
            </LeaseDialog>

            {/* Confirmation Dialog */}
            <ConfirmDialog
                isOpen={confirmOpen}
                onClose={() => setConfirmOpen(false)}
                onConfirm={confirmConfig.onConfirm}
                title={confirmConfig.title}
                description={confirmConfig.description}
                confirmText={confirmConfig.confirmText}
                cancelText={t("cancel")}
                isDestructive={confirmConfig.isDestructive}
            />
        </div>
    );
}
