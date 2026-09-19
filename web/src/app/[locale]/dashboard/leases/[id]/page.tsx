"use client";

import { useCallback, useEffect, useState } from "react";
import { useParams, useSearchParams } from "next/navigation";
import { useSession } from "next-auth/react";
import { useLocale, useTranslations } from "next-intl";
import { Link, useRouter } from "@/i18n/routing";
import {
    ArrowLeft, Ban, BookOpen, CalendarClock, CheckCircle, Download,
    FileText, Loader2, Mail, Phone, RefreshCw, Save, Sparkles, Trash2, Upload, User, Wrench, X,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { hasPermission, hasRole, type UserRole } from "@/lib/rbac";
import { formatCurrency } from "@/lib/format";
import { fmtAmount } from "@/lib/api/ledger";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import LeaseMetadataEditor from "../LeaseMetadataEditor";
import LeaseInteractionsPanel from "@/components/leases/LeaseInteractionsPanel";
import LeaseLinesGrid from "@/components/leases/LeaseLinesGrid";
import ChequeGrid, { toChequeRows, type ChequeRowAction } from "@/components/leases/ChequeGrid";
import ChequeActionDialog from "@/components/cheques/ChequeActionDialog";
import BulkChequeUploadFlow from "@/components/cheques/BulkChequeUploadFlow";
import PostLeaseDialog from "@/components/leases/PostLeaseDialog";
import AmendLinesDialog from "@/components/leases/AmendLinesDialog";
import RenewLeaseDialog from "@/components/leases/RenewLeaseDialog";
import ExtendLeaseDialog from "@/components/leases/ExtendLeaseDialog";
import LeaseJournalsTab from "@/components/leases/LeaseJournalsTab";
import LeasePenaltiesTab from "@/components/leases/LeasePenaltiesTab";
import { fmtIsoDate, toRows, totalsOf } from "@/components/leases/leaseMath";
import {
    ApiError, chargeTypeApi, leaseApi,
    type ChargeType, type Cheque, type LeaseDetail, type LeaseStatus,
} from "@/lib/api/leasing";

/**
 * One tenancy contract, in the shape the client's accountant reads it: a
 * header, the particulars grid, the cheque grid, and the journals it has
 * written.
 *
 * The old page's centre of gravity was a payment schedule that the backend no
 * longer has — `PUT /leases/{id}/payment-schedule`, `/v1/payments/*` and
 * `PUT /leases/{id}/activate` are all gone. A lease no longer becomes ACTIVE
 * by a status change with no journal behind it: Post is the only path, which
 * is why it sits in the action bar behind a dry run rather than as a green
 * "Activate" button.
 */

type Renter = { id: string; nameEn: string; nameAr: string; email: string; phone: string; primaryLanguage: string };
type Attachment = { id: string; name: string; fileUrl: string; fileType: string; fileSize: number; uploadedAt: string };
type Ticket = { id: string; title: string; status: string; priority: string; category: string; createdAt: string };
type Settlement = {
    id: string; status: string; depositAmount: number; totalDeductions: number;
    totalAdditions?: number; refundAmount: number; notes: string;
    settledBy: string; settledByName?: string; settledAt: string;
};

const STATUS_COLORS: Record<string, string> = {
    ACTIVE: "bg-success/10 text-success border-success/20",
    DRAFT: "bg-input text-muted border-border",
    PENDING_SIGNATURE: "bg-warning/10 text-warning border-warning/20",
    TERMINATED: "bg-error/10 text-error border-error/20",
    EXPIRED: "bg-warning/10 text-warning border-warning/20",
    CLOSED: "bg-input text-muted border-border",
    NOTICE_GIVEN: "bg-warning/10 text-warning border-warning/20",
    RENEWED: "bg-info/10 text-info border-info/20",
};

const TICKET_STATUS_COLORS: Record<string, string> = {
    OPEN: "bg-warning/10 text-warning", ASSIGNED: "bg-info/10 text-info",
    IN_PROGRESS: "bg-primary/10 text-primary", RESOLVED: "bg-success/10 text-success",
    CLOSED: "bg-input text-muted", REOPENED: "bg-error/10 text-error",
};

const TABS = ["overview", "journals", "recognition", "penalties", "contract", "maintenance", "documents", "interactions"] as const;
type Tab = typeof TABS[number];

const DRAFTING: LeaseStatus[] = ["DRAFT", "PENDING_SIGNATURE"];

export default function LeaseDetailPage() {
    const params = useParams();
    const searchParams = useSearchParams();
    const router = useRouter();
    const locale = useLocale();
    const leaseId = params.id as string;

    const t = useTranslations("Leasing");
    const tMaster = useTranslations("MasterData");
    const tBulkUpload = useTranslations("bulkChequeUpload");
    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;

    const canView = hasPermission(userRole, "canViewLeases");
    const canDraft = hasPermission(userRole, "canManageLeases");
    const canPost = hasPermission(userRole, "canPostLeases");
    const canRenew = hasPermission(userRole, "canRenewLeases");
    const canExtend = hasPermission(userRole, "canExtendLeases");
    const canCheques = hasPermission(userRole, "canManageCheques");
    const canGenerateContract = hasRole(userRole, ["SUPER_ADMIN", "TENANT_ADMIN"]);

    const [lease, setLease] = useState<LeaseDetail | null>(null);
    const [cheques, setCheques] = useState<Cheque[]>([]);
    const [chargeTypes, setChargeTypes] = useState<ChargeType[]>([]);
    const [renter, setRenter] = useState<Renter | null>(null);
    const [attachments, setAttachments] = useState<Attachment[]>([]);
    const [tickets, setTickets] = useState<Ticket[]>([]);
    const [settlement, setSettlement] = useState<Settlement | null>(null);
    const [loading, setLoading] = useState(true);
    const [banner, setBanner] = useState<string | null>(null);
    const [error, setError] = useState<string | null>(null);

    const [tab, setTab] = useState<Tab>("overview");
    const [postOpen, setPostOpen] = useState(false);
    const [amendOpen, setAmendOpen] = useState(false);
    const [renewOpen, setRenewOpen] = useState(false);
    const [extendOpen, setExtendOpen] = useState(false);
    const [deleteOpen, setDeleteOpen] = useState(false);
    const [chequeAction, setChequeAction] = useState<{ action: ChequeRowAction; cheque: Cheque } | null>(null);
    const [chequeBusy, setChequeBusy] = useState(false);
    const [chequeError, setChequeError] = useState<string | null>(null);
    const [bulkUploadOpen, setBulkUploadOpen] = useState(false);

    const [docName, setDocName] = useState("");
    const [uploadingDoc, setUploadingDoc] = useState(false);
    const [previewOpen, setPreviewOpen] = useState(false);
    const [previewBlobUrl, setPreviewBlobUrl] = useState<string | null>(null);
    const [previewLoading, setPreviewLoading] = useState(false);
    const [confirmSaving, setConfirmSaving] = useState(false);
    const [contractError, setContractError] = useState<string | null>(null);

    const loadLease = useCallback(async () => {
        setError(null);
        try {
            const detail = await leaseApi.get(leaseId);
            setLease(detail);
            setCheques(await leaseApi.cheques(leaseId));
            if (detail.renterId) {
                const r = await fetch(`/api/proxy/v1/renters/${detail.renterId}`);
                if (r.ok) setRenter(await r.json());
            }
            return detail;
        } catch (e) {
            setError(e instanceof ApiError ? e.message : t("notFound"));
            return null;
        }
    }, [leaseId, t]);

    const loadAttachments = useCallback(async () => {
        const res = await fetch(`/api/proxy/v1/leases/${leaseId}/attachments`);
        if (res.ok) setAttachments(await res.json());
    }, [leaseId]);

    useEffect(() => {
        if (!canView && userRole) {
            setLoading(false);
            return;
        }
        let cancelled = false;
        (async () => {
            const detail = await loadLease();
            await loadAttachments().catch(() => {});
            chargeTypeApi.list(true).then(list => {
                if (!cancelled) setChargeTypes(list);
            }).catch(() => {});
            if (detail?.unitId) {
                const res = await fetch(`/api/proxy/v1/tickets?unitId=${encodeURIComponent(detail.unitId)}`);
                if (res.ok && !cancelled) setTickets(await res.json());
            }
            if (detail && (detail.status === "TERMINATED" || detail.status === "CLOSED")) {
                const res = await fetch(`/api/proxy/v1/leases/${leaseId}/settlement`);
                if (res.ok && !cancelled) setSettlement(await res.json());
            }
            if (!cancelled) setLoading(false);
        })();
        return () => {
            cancelled = true;
        };
    }, [canView, userRole, leaseId, loadLease, loadAttachments]);

    // The wizard posts and then lands here; the TCO number rides in on the URL
    // so the banner can name it without a second round trip.
    useEffect(() => {
        const posted = searchParams?.get("posted");
        if (posted) setBanner(t("postedBanner", { tco: posted }));
    }, [searchParams, t]);

    const runCheques = async (fn: () => Promise<Cheque[]>) => {
        setChequeBusy(true);
        setChequeError(null);
        try {
            setCheques(await fn());
        } catch (e) {
            setChequeError(e instanceof ApiError ? e.message : t("saveFailed"));
        } finally {
            setChequeBusy(false);
        }
    };

    const handleChequeAction = async () => {
        setChequeAction(null);
        await loadLease();
    };

    const handleDelete = async () => {
        try {
            await fetch(`/api/proxy/v1/leases/${leaseId}`, { method: "DELETE" }).then(async res => {
                if (!res.ok) {
                    const body = await res.json().catch(() => ({}));
                    throw new ApiError(res.status, body?.message || t("deleteFailed"));
                }
            });
            router.push("/dashboard/leases");
        } catch (e) {
            setError(e instanceof ApiError ? e.message : t("deleteFailed"));
            setDeleteOpen(false);
        }
    };

    const handlePreviewContract = async () => {
        setPreviewLoading(true);
        setContractError(null);
        try {
            const res = await fetch(`/api/proxy/v1/leases/${leaseId}/generate-contract/preview`, { method: "POST" });
            if (res.ok) {
                const buf = await res.arrayBuffer();
                setPreviewBlobUrl(URL.createObjectURL(new Blob([buf], { type: "application/pdf" })));
                setPreviewOpen(true);
            } else {
                const body = await res.json().catch(() => ({}));
                setContractError(body?.message || body?.error || tMaster("failedGenerateContract"));
            }
        } catch {
            setContractError(tMaster("failedGenerateContract"));
        } finally {
            setPreviewLoading(false);
        }
    };

    const closePreview = () => {
        setPreviewOpen(false);
        if (previewBlobUrl) {
            URL.revokeObjectURL(previewBlobUrl);
            setPreviewBlobUrl(null);
        }
    };

    const handleConfirmContract = async () => {
        setConfirmSaving(true);
        try {
            const res = await fetch(`/api/proxy/v1/leases/${leaseId}/generate-contract`, { method: "POST" });
            if (res.ok) {
                closePreview();
                await loadLease();
            } else {
                const body = await res.json().catch(() => ({}));
                setContractError(body?.message || tMaster("failedGenerateContract"));
            }
        } finally {
            setConfirmSaving(false);
        }
    };

    const handleDocUpload = async (file: File) => {
        if (!docName.trim()) return;
        setUploadingDoc(true);
        try {
            const fd = new FormData();
            fd.append("file", file);
            fd.append("name", docName);
            const res = await fetch(`/api/upload?path=/api/v1/leases/${leaseId}/attachments`, { method: "POST", body: fd });
            if (res.ok) {
                setDocName("");
                await loadAttachments();
            }
        } finally {
            setUploadingDoc(false);
        }
    };

    const downloadBlob = async (url: string, name: string) => {
        const res = await fetch(url);
        if (!res.ok) return;
        const blob = await res.blob();
        const href = URL.createObjectURL(blob);
        const a = document.createElement("a");
        a.href = href;
        a.download = name;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(href);
    };

    if (userRole && !canView) {
        return (
            <div className="text-center py-24">
                <p className="text-sm text-muted">{t("accessDenied")}</p>
            </div>
        );
    }

    if (loading) {
        return (
            <div className="flex items-center justify-center py-24">
                <Loader2 className="w-6 h-6 animate-spin text-primary opacity-60" />
            </div>
        );
    }

    if (!lease) {
        return (
            <div className="text-center py-24">
                <p className="text-sm text-muted">{error ?? t("notFound")}</p>
                <Link href="/dashboard/leases" className="text-xs text-primary font-semibold mt-2 inline-block">
                    {t("backToLeases")}
                </Link>
            </div>
        );
    }

    const drafting = DRAFTING.includes(lease.status);
    const posted = !!lease.postedAt;
    const readOnly = lease.status === "RENEWED";
    const lineRows = toRows(lease.lines);
    const totals = totalsOf(lineRows, chargeTypes);

    return (
        <>
            <div className="flex flex-col gap-[18px]">
                {/* ── Header ─────────────────────────────────────────── */}
                <div className="flex items-start gap-5 flex-wrap">
                    <Link href="/dashboard/leases" className="p-2 rounded-lg hover:bg-input transition-colors text-muted hover:text-foreground">
                        <ArrowLeft size={18} />
                    </Link>
                    <div className="flex-1 min-w-[240px]">
                        <div className="flex items-center gap-3 flex-wrap">
                            <h1 className="mb-0 font-serif text-[26px] font-semibold tracking-tight">
                                {t("unit")} {lease.unitIdentifier}
                            </h1>
                            <span
                                data-testid="lease-status"
                                className={cn("px-2.5 py-1 rounded-lg text-[10px] font-semibold border", STATUS_COLORS[lease.status] ?? "bg-input text-muted border-border")}
                            >
                                {t(`leaseStatus.${lease.status}`)}
                            </span>
                            {lease.displayContractNumber && (
                                <span data-testid="lease-contract-number" className="px-2.5 py-1 rounded-lg text-[10px] font-semibold bg-primary/10 text-primary border border-primary/20">
                                    {t("contractNumber")} {lease.displayContractNumber}
                                </span>
                            )}
                        </div>
                        <p className="text-[12.5px] text-[var(--ink-500)]">{lease.propertyName}</p>
                        <p className="text-sm text-muted">{lease.renterName}</p>
                        <div className="flex items-center gap-3 flex-wrap mt-1 text-[11px]">
                            {lease.renewedFromLeaseId && (
                                <Link href={`/dashboard/leases/${lease.renewedFromLeaseId}`} className="text-primary hover:underline" data-testid="lease-renewed-from">
                                    {t("renewFrom", { number: lease.renewedFromLeaseId.slice(0, 8) })}
                                </Link>
                            )}
                            {posted && lease.postingJournalId && (
                                <Link href={`/dashboard/finance/journals/${lease.postingJournalId}`} className="text-primary hover:underline" data-testid="lease-posting-journal">
                                    {t("viewJournal")} · {t("postedAt", { date: fmtIsoDate(lease.postedAt, locale) })}
                                </Link>
                            )}
                        </div>
                    </div>

                    {/* ── Action bar ─────────────────────────────────── */}
                    <div className="flex items-center gap-2 flex-wrap" data-testid="lease-actions">
                        {drafting && canPost && (
                            <button
                                onClick={() => setPostOpen(true)}
                                data-testid="lease-post"
                                className="flex items-center gap-2 bg-accent text-accent-foreground px-4 py-2 rounded-lg text-xs font-semibold hover:brightness-110 transition-all cursor-pointer"
                            >
                                <CheckCircle size={14} /> {t("postLease")}
                            </button>
                        )}
                        {drafting && !canPost && (
                            <span className="text-[11px] text-muted" data-testid="lease-needs-accountant">
                                {t("savedAsDraftNeedsAccountant")}
                            </span>
                        )}
                        {lease.status === "ACTIVE" && canPost && (
                            <button
                                onClick={() => setAmendOpen(true)}
                                data-testid="lease-amend"
                                className="flex items-center gap-2 bg-input text-foreground border border-border px-4 py-2 rounded-lg text-xs font-semibold hover:bg-border transition-all cursor-pointer"
                            >
                                <RefreshCw size={14} /> {t("amendLines")}
                            </button>
                        )}
                        {lease.status === "ACTIVE" && canRenew && (
                            <button
                                onClick={() => setRenewOpen(true)}
                                data-testid="lease-renew"
                                className="flex items-center gap-2 bg-input text-foreground border border-border px-4 py-2 rounded-lg text-xs font-semibold hover:bg-border transition-all cursor-pointer"
                            >
                                <Sparkles size={14} /> {t("renew")}
                            </button>
                        )}
                        {lease.status === "ACTIVE" && canExtend && (
                            <button
                                onClick={() => setExtendOpen(true)}
                                data-testid="lease-extend"
                                className="flex items-center gap-2 bg-input text-foreground border border-border px-4 py-2 rounded-lg text-xs font-semibold hover:bg-border transition-all cursor-pointer"
                            >
                                <CalendarClock size={14} /> {t("extend")}
                            </button>
                        )}
                        {posted && (
                            <Link
                                href={`/dashboard/finance/tenant-ledger?renterId=${lease.renterId}&leaseId=${lease.id}`}
                                data-testid="lease-ledger"
                                className="flex items-center gap-2 bg-input text-foreground border border-border px-4 py-2 rounded-lg text-xs font-semibold hover:bg-border transition-all"
                            >
                                <BookOpen size={14} /> {t("ledger")}
                            </Link>
                        )}
                        {lease.hasContract && (
                            <button
                                onClick={() => downloadBlob(`/api/proxy/v1/leases/${leaseId}/documents`, `contract-${leaseId.slice(0, 8)}.pdf`)}
                                className="flex items-center gap-2 bg-primary text-primary-foreground px-4 py-2 rounded-lg text-xs font-semibold hover:bg-primary/90 transition-all cursor-pointer"
                            >
                                <Download size={14} /> {tMaster("downloadContract")}
                            </button>
                        )}
                        {(lease.status === "ACTIVE" || lease.status === "NOTICE_GIVEN") && canDraft && (
                            <Link
                                href={`/dashboard/leases/${leaseId}/settlement`}
                                data-testid="lease-terminate"
                                className="flex items-center gap-2 bg-error text-white px-4 py-2 rounded-lg text-xs font-semibold hover:bg-error/90 transition-all"
                            >
                                <Ban size={14} /> {t("terminate")}
                            </Link>
                        )}
                        {drafting && canDraft && (
                            <button
                                onClick={() => setDeleteOpen(true)}
                                data-testid="lease-delete"
                                title={t("deleteDraft")}
                                className="flex items-center gap-2 bg-error/10 text-error border border-error/30 px-3 py-2 rounded-lg text-xs font-semibold hover:bg-error/20 transition-all cursor-pointer"
                            >
                                <Trash2 size={14} />
                            </button>
                        )}
                    </div>
                </div>

                {banner && (
                    <p className="rounded-xl bg-success/10 border border-success/30 px-4 py-2.5 text-xs text-success" data-testid="lease-banner">
                        {banner}
                    </p>
                )}
                {error && (
                    <p className="rounded-xl bg-error/10 border border-error/30 px-4 py-2.5 text-xs text-error" data-testid="lease-error">
                        {error}
                    </p>
                )}
                {readOnly && (
                    <p className="rounded-xl bg-info/10 border border-info/30 px-4 py-2.5 text-xs text-info" data-testid="lease-read-only">
                        {t("readOnlyRenewed")}
                    </p>
                )}

                {/* ── Ribbon ─────────────────────────────────────────── */}
                <div className="bg-surface border border-border rounded-[var(--radius-lg)] p-5 grid grid-cols-2 md:grid-cols-5 gap-4">
                    <Ribbon label={t("startDate")} value={fmtIsoDate(lease.startDate, locale)} />
                    <Ribbon label={t("endDate")} value={fmtIsoDate(lease.endDate, locale)} />
                    <Ribbon label={t("contractValue")} value={fmtAmount(lease.contractValue ?? totals.net)} />
                    <Ribbon label={t("contractValueInclVat")} value={fmtAmount(totals.inclVat)} />
                    <Ribbon label={t("paymentTerms")} value={String(cheques.length || lease.paymentTerms || 0)} />
                </div>

                {/* ── Tabs ───────────────────────────────────────────── */}
                <div className="flex gap-1 border-b border-border overflow-x-auto">
                    {TABS.map(key => (
                        <button
                            key={key}
                            onClick={() => setTab(key)}
                            data-testid={`lease-tab-${key}`}
                            className={cn(
                                "px-3.5 py-2.5 text-[13.5px] font-medium -mb-px whitespace-nowrap cursor-pointer transition-colors",
                                tab === key
                                    ? "text-foreground font-semibold border-b-2 border-[var(--gold-500)]"
                                    : "text-[var(--ink-500)] hover:text-foreground",
                            )}
                        >
                            {tabLabel(key)}
                        </button>
                    ))}
                </div>

                {tab === "overview" && (
                    <div className="space-y-6">
                        {drafting && canDraft && !readOnly && (
                            <LeaseMetadataEditor
                                lease={lease}
                                chargeTypes={chargeTypes}
                                onSaved={async () => {
                                    await loadLease();
                                }}
                            />
                        )}

                        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
                            <div className="space-y-6">
                                <Card title={t("contractNumber")} icon={<FileText size={13} />}>
                                    <Detail label={t("contractDate")} value={fmtIsoDate(lease.contractDate, locale)} />
                                    <Detail label={t("agreementDate")} value={fmtIsoDate(lease.agreementDate, locale)} />
                                    <Detail label={t("gracePeriodDays")} value={String(lease.gracePeriodDays ?? 0)} />
                                    <Detail label={t("firstDueDate")} value={fmtIsoDate(lease.firstDueDate, locale)} />
                                    <Detail label={t("paymentMethod")} value={lease.paymentMethod ?? "—"} />
                                    <Detail label={t("ejariNumber")} value={lease.ejariNumber || "—"} />
                                </Card>

                                {renter && (
                                    <Card title={t("renter")} icon={<User size={13} />}>
                                        <Detail label={t("renter")} value={renter.nameEn} />
                                        {renter.nameAr && <Detail label={t("renter")} value={renter.nameAr} rtl />}
                                        <Detail label="" icon={<Mail size={10} />} value={renter.email || "—"} />
                                        <Detail label="" icon={<Phone size={10} />} value={renter.phone || "—"} />
                                    </Card>
                                )}
                            </div>

                            <div className="lg:col-span-2 space-y-6">
                                <LeaseLinesGrid lines={lineRows} chargeTypes={chargeTypes} editable={false} />

                                <div className="space-y-2">
                                    <ChequeGrid
                                        cheques={cheques}
                                        editable={drafting && canCheques && !readOnly}
                                        onChange={setCheques}
                                        onGenerate={req => runCheques(() => leaseApi.generateCheques(leaseId, req))}
                                        onGenerateNumbers={n => runCheques(() => leaseApi.generateChequeNumbers(leaseId, n))}
                                        propertyId={lease.propertyId}
                                        contractValueInclVat={totals.inclVat}
                                        defaultInstallments={lease.paymentTerms ?? 4}
                                        defaultFirstDueDate={lease.firstDueDate ?? lease.startDate}
                                        busy={chequeBusy}
                                        error={chequeError}
                                        onRowAction={canCheques ? (cheque, action) => setChequeAction({ action, cheque }) : undefined}
                                    />
                                    {drafting && canCheques && !readOnly && cheques.length > 0 && (
                                        <button
                                            type="button"
                                            data-testid="lease-save-cheques"
                                            onClick={() => runCheques(() => leaseApi.saveCheques(leaseId, toChequeRows(cheques)))}
                                            disabled={chequeBusy}
                                            className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[11px] font-semibold border border-border text-foreground hover:bg-input/40 cursor-pointer disabled:opacity-50"
                                        >
                                            <Save size={12} /> {t("saveCheques")}
                                        </button>
                                    )}
                                    {!drafting && canCheques && cheques.some(c => c.status === "REGISTERED" && c.mode === "PDC") && (
                                        <button
                                            type="button"
                                            data-testid="lease-bulk-upload-cheques"
                                            onClick={() => setBulkUploadOpen(true)}
                                            className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[11px] font-semibold border border-border text-foreground hover:bg-input/40 cursor-pointer"
                                        >
                                            <Upload size={12} /> {tBulkUpload("entryButton")}
                                        </button>
                                    )}
                                </div>
                            </div>
                        </div>
                    </div>
                )}

                {tab === "journals" && (
                    <LeaseJournalsTab leaseId={leaseId} renterId={lease.renterId} renterName={lease.renterName} />
                )}

                {tab === "recognition" && (
                    <div className="bg-surface border border-border rounded-xl px-5 py-8 text-center" data-testid="lease-recognition">
                        <p className="text-xs text-muted">{t("recognitionPlaceholder")}</p>
                    </div>
                )}

                {tab === "penalties" && <LeasePenaltiesTab leaseId={leaseId} userRole={userRole} />}

                {tab === "contract" && (
                    <div className="bg-surface rounded-xl border border-border overflow-hidden">
                        <div className="px-5 py-3.5 border-b border-border bg-[var(--sand-50)]">
                            <h2 className="text-xs font-semibold text-muted uppercase tracking-wider flex items-center gap-2">
                                <FileText size={13} /> {tMaster("contractNumber")}
                            </h2>
                        </div>
                        <div className="px-5 py-4 space-y-4">
                            <div className="flex flex-wrap gap-3">
                                {canGenerateContract && (
                                    <button
                                        onClick={handlePreviewContract}
                                        disabled={previewLoading}
                                        className="inline-flex items-center gap-2 px-4 py-2 rounded-xl text-sm font-semibold border border-primary text-primary hover:bg-primary/5 transition-colors cursor-pointer disabled:opacity-50"
                                    >
                                        {previewLoading ? <Loader2 size={14} className="animate-spin" /> : <FileText size={14} />}
                                        {tMaster("generatePreview")}
                                    </button>
                                )}
                                <Link
                                    href={`/dashboard/leases/${leaseId}/settlement`}
                                    className="inline-flex items-center gap-2 px-4 py-2 rounded-xl text-sm font-semibold border border-border text-foreground hover:bg-input transition-colors"
                                >
                                    {tMaster("viewSettlement")}
                                </Link>
                            </div>
                            {contractError && <p className="text-xs text-error">{contractError}</p>}
                            {lease.ejariNumber && (
                                <p className="text-xs text-muted">
                                    <span className="font-medium text-foreground">{t("ejariNumber")}:</span> {lease.ejariNumber}
                                </p>
                            )}
                        </div>
                    </div>
                )}

                {tab === "maintenance" && (
                    <div className="bg-surface rounded-xl border border-border">
                        <div className="px-5 py-3.5 border-b border-border flex items-center justify-between">
                            <h2 className="text-xs font-semibold text-muted uppercase tracking-wider flex items-center gap-2">
                                <Wrench size={13} /> {tMaster("maintenanceTickets")}
                            </h2>
                            <Link href="/dashboard/tickets" className="text-[10px] font-semibold text-primary hover:text-primary/80">
                                {tMaster("viewAll")}
                            </Link>
                        </div>
                        <div className="p-4">
                            {tickets.length > 0 ? (
                                <div className="space-y-2">
                                    {tickets.map(tk => (
                                        <Link
                                            key={tk.id}
                                            href={`/dashboard/tickets/${tk.id}`}
                                            className="flex items-center justify-between bg-input/50 rounded-lg px-3 py-2 border border-border hover:bg-input transition-colors"
                                        >
                                            <div className="min-w-0 flex-1">
                                                <p className="text-xs font-medium text-foreground truncate">{tk.title}</p>
                                                <p className="text-[10px] text-muted">{tk.category} · {fmtIsoDate(tk.createdAt, locale)}</p>
                                            </div>
                                            <span className={cn("px-2 py-0.5 rounded-md text-[9px] font-semibold ms-3", TICKET_STATUS_COLORS[tk.status] ?? "bg-input text-muted")}>
                                                {tk.status.replace("_", " ")}
                                            </span>
                                        </Link>
                                    ))}
                                </div>
                            ) : (
                                <p className="text-xs text-muted text-center py-6">{tMaster("noTicketsForUnit")}</p>
                            )}
                        </div>
                    </div>
                )}

                {tab === "documents" && (
                    <div className="bg-surface rounded-xl border border-border">
                        <div className="px-5 py-3.5 border-b border-border">
                            <h2 className="text-xs font-semibold text-muted uppercase tracking-wider flex items-center gap-2">
                                <FileText size={13} /> {tMaster("supportingDocuments")}
                            </h2>
                        </div>
                        <div className="p-4">
                            {attachments.length > 0 ? (
                                <div className="space-y-2 mb-4">
                                    {attachments.map(doc => (
                                        <div key={doc.id} className="flex items-center justify-between bg-input/50 rounded-lg px-3 py-2 border border-border">
                                            <div className="min-w-0">
                                                <p className="text-xs font-medium text-foreground truncate">{doc.name}</p>
                                                <p className="text-[10px] text-muted">{(doc.fileSize / 1024).toFixed(0)} KB</p>
                                            </div>
                                            <div className="flex items-center gap-2 shrink-0">
                                                <button
                                                    onClick={() => downloadBlob(`/api/proxy/v1/leases/attachments/${doc.id}/download`, doc.name)}
                                                    aria-label={tMaster("download")}
                                                    className="p-1 text-primary hover:text-primary/80 cursor-pointer"
                                                >
                                                    <Download size={13} />
                                                </button>
                                                <button
                                                    onClick={async () => {
                                                        await fetch(`/api/proxy/v1/leases/attachments/${doc.id}`, { method: "DELETE" });
                                                        await loadAttachments();
                                                    }}
                                                    aria-label={tMaster("delete")}
                                                    className="p-1 text-error hover:text-error/80 cursor-pointer"
                                                >
                                                    <Trash2 size={13} />
                                                </button>
                                            </div>
                                        </div>
                                    ))}
                                </div>
                            ) : (
                                <p className="text-xs text-muted text-center py-3">{tMaster("noDocumentsYet")}</p>
                            )}
                            <div className="flex items-center gap-2 pt-2 border-t border-border">
                                <input
                                    type="text"
                                    value={docName}
                                    onChange={e => setDocName(e.target.value)}
                                    placeholder={tMaster("documentNamePlaceholder")}
                                    className="flex-1 border border-border rounded-lg bg-surface px-3 py-1.5 text-xs text-foreground focus:ring-2 focus:ring-primary/20 focus:outline-none"
                                />
                                <label
                                    className={cn(
                                        "flex items-center gap-1 px-2.5 py-1.5 rounded-lg text-[10px] font-semibold transition-colors shrink-0",
                                        docName.trim() && !uploadingDoc ? "bg-primary text-primary-foreground cursor-pointer" : "bg-input text-muted cursor-not-allowed",
                                    )}
                                >
                                    {uploadingDoc ? <Loader2 size={11} className="animate-spin" /> : <Upload size={11} />}
                                    {tMaster("attachFile")}
                                    <input
                                        type="file"
                                        className="hidden"
                                        disabled={!docName.trim() || uploadingDoc}
                                        onChange={e => {
                                            const f = e.target.files?.[0];
                                            if (f) handleDocUpload(f);
                                            if (e.target) e.target.value = "";
                                        }}
                                    />
                                </label>
                            </div>
                        </div>
                    </div>
                )}

                {tab === "interactions" && <LeaseInteractionsPanel leaseId={leaseId} />}

                {settlement && settlement.status !== "DRAFT" && (
                    <div className="bg-surface rounded-[var(--radius-lg)] border border-border px-5 py-4 space-y-2">
                        <h2 className="text-xs font-semibold text-muted uppercase tracking-wider">{tMaster("settlementSummary")}</h2>
                        <Detail label={tMaster("securityDeposit")} value={formatCurrency(settlement.depositAmount)} />
                        <Detail label={tMaster("totalDeductions")} value={formatCurrency(settlement.totalDeductions)} />
                        <Detail label={tMaster("refundToRenter")} value={formatCurrency(settlement.refundAmount)} />
                        <Link href={`/dashboard/leases/${leaseId}/settlement`} className="text-[10px] font-semibold text-primary hover:underline">
                            {tMaster("viewSettlement")}
                        </Link>
                    </div>
                )}
            </div>

            {/* ── Dialogs ────────────────────────────────────────────── */}
            <PostLeaseDialog
                open={postOpen}
                lease={lease}
                onClose={() => setPostOpen(false)}
                onPosted={async res => {
                    setPostOpen(false);
                    setBanner(t("postedBanner", { tco: res.tcoEntryNumber }));
                    await loadLease();
                }}
            />

            <AmendLinesDialog
                open={amendOpen}
                lease={lease}
                cheques={cheques}
                chargeTypes={chargeTypes}
                onClose={() => setAmendOpen(false)}
                onAmended={async () => {
                    setAmendOpen(false);
                    await loadLease();
                }}
            />

            <RenewLeaseDialog
                open={renewOpen}
                lease={lease}
                chargeTypes={chargeTypes}
                onClose={() => setRenewOpen(false)}
                onRenewed={successor => {
                    setRenewOpen(false);
                    router.push(`/dashboard/leases/${successor.id}`);
                }}
            />

            <ExtendLeaseDialog
                open={extendOpen}
                lease={lease}
                chargeTypes={chargeTypes}
                onClose={() => setExtendOpen(false)}
                onExtended={async () => {
                    setExtendOpen(false);
                    await loadLease();
                }}
            />

            <ChequeActionDialog
                action={chequeAction?.action ?? null}
                cheque={chequeAction?.cheque ?? null}
                propertyId={lease.propertyId}
                onClose={() => setChequeAction(null)}
                onDone={handleChequeAction}
            />

            {bulkUploadOpen && (
                <BulkChequeUploadFlow
                    leaseId={leaseId}
                    rows={cheques}
                    onClose={() => setBulkUploadOpen(false)}
                    onSuccess={async () => {
                        setBulkUploadOpen(false);
                        await loadLease();
                    }}
                />
            )}

            <ConfirmDialog
                isOpen={deleteOpen}
                onClose={() => setDeleteOpen(false)}
                onConfirm={handleDelete}
                title={t("deleteDraft")}
                description={t("deleteDraftConfirm")}
                confirmText={t("deleteDraft")}
                cancelText={t("cancel")}
                isDestructive
            />

            {previewOpen && previewBlobUrl && (
                <div className="fixed inset-0 z-50 flex flex-col bg-black/70">
                    <div className="flex items-center justify-between bg-surface border-b border-border px-5 py-3 shrink-0">
                        <h3 className="text-sm font-semibold text-foreground flex items-center gap-2">
                            <Sparkles size={15} className="text-primary" /> {tMaster("generatePreview")}
                        </h3>
                        <div className="flex items-center gap-2">
                            <button onClick={closePreview} className="px-4 py-2 rounded-lg text-xs font-semibold text-muted hover:bg-input border border-border cursor-pointer">
                                {t("cancel")}
                            </button>
                            <button
                                onClick={handleConfirmContract}
                                disabled={confirmSaving}
                                className="flex items-center gap-2 px-4 py-2 rounded-lg text-xs font-semibold bg-primary text-primary-foreground hover:bg-primary/90 cursor-pointer disabled:opacity-50"
                            >
                                {confirmSaving ? <Loader2 size={12} className="animate-spin" /> : <CheckCircle size={12} />}
                                {tMaster("confirmAndSave")}
                            </button>
                            <button onClick={closePreview} aria-label={t("close")} className="p-1.5 text-muted hover:text-foreground cursor-pointer ms-1">
                                <X size={16} />
                            </button>
                        </div>
                    </div>
                    {contractError && (
                        <div className="bg-error/10 border-b border-error/20 px-5 py-2 shrink-0">
                            <p className="text-xs text-error">{contractError}</p>
                        </div>
                    )}
                    <div className="flex-1 overflow-hidden bg-input">
                        <object data={previewBlobUrl} type="application/pdf" className="w-full h-full" aria-label={tMaster("generatePreview")}>
                            <div className="text-center p-8">
                                <a href={previewBlobUrl} target="_blank" rel="noreferrer" className="text-xs font-semibold text-primary">
                                    {tMaster("generatePreview")}
                                </a>
                            </div>
                        </object>
                    </div>
                </div>
            )}
        </>
    );

    function tabLabel(key: Tab): string {
        switch (key) {
            case "overview": return t("overviewTab");
            case "journals": return t("journalsTab");
            case "recognition": return t("recognitionSchedule");
            case "penalties": return t("penaltiesTab");
            case "contract": return tMaster("contractNumber");
            case "maintenance": return tMaster("maintenanceTickets");
            case "documents": return tMaster("supportingDocuments");
            case "interactions": return tMaster("interactions");
        }
    }
}

function Ribbon({ label, value }: { label: string; value: string }) {
    return (
        <div>
            <p className="text-[10px] font-semibold uppercase tracking-[0.08em] text-[var(--ink-500)]">{label}</p>
            <p className="text-sm font-semibold text-foreground tabular-nums">{value}</p>
        </div>
    );
}

function Card({ title, icon, children }: { title: string; icon: React.ReactNode; children: React.ReactNode }) {
    return (
        <div className="bg-surface rounded-xl border border-border">
            <div className="px-5 py-3.5 border-b border-border">
                <h2 className="text-xs font-semibold text-muted uppercase tracking-wider flex items-center gap-2">
                    {icon} {title}
                </h2>
            </div>
            <div className="px-5 py-3 space-y-3">{children}</div>
        </div>
    );
}

function Detail({ label, value, icon, rtl }: { label: string; value: string; icon?: React.ReactNode; rtl?: boolean }) {
    return (
        <div className="flex justify-between items-center gap-3">
            <span className="text-xs text-muted flex items-center gap-1">{icon}{label}</span>
            <span className="text-xs font-medium text-foreground tabular-nums" dir={rtl ? "rtl" : undefined}>
                {value}
            </span>
        </div>
    );
}
