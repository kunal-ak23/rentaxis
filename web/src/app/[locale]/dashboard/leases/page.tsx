"use client";

import { useState, useEffect } from "react";
import { useTranslations, useLocale } from "next-intl";
import { Plus, X, FileText, Calendar, DollarSign, Home, CheckCircle, Ban, AlertCircle, LayoutGrid, Columns3, Download, Sparkles, Loader2, RefreshCw, Pencil, List, Eye, Search, Upload } from "lucide-react";
import { Link, useRouter } from "@/i18n/routing";
import { Pagination } from "@/components/ui/Pagination";
import { useSession } from "next-auth/react";
import { hasPermission, type UserRole } from "@/lib/rbac";
import { cn } from "@/lib/utils";
import { formatCurrency, formatCurrencyCompact } from "@/lib/format";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";

type Lease = {
    id: string;
    unitId: string;
    renterId: string;
    unitIdentifier: string;
    renterName: string;
    startDate: string;
    endDate: string;
    status: string;
    rentAmount: number;
    monthlyRent: number | null;
    depositAmount: number;
    ejariNumber: string;
    paymentTerms: number;
    paymentMethod: string;
    depositPaymentMethod: string;
    paymentReferenceNumber: string;
    propertyId: string;
    propertyName: string;
    hasContract: boolean;
    contractNumber?: number | null;
    agreementDate?: string | null;
    adminFee?: number | null;
    parkingRemoteFee?: number | null;
    rentVatApplicable?: boolean | null;
    adminFeeVatApplicable?: boolean | null;
    securityDepositVatApplicable?: boolean | null;
    parkingRemoteVatApplicable?: boolean | null;
};

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

type PaymentStats = {
    leaseId: string;
    totalPayments: number;
    clearedPayments: number;
    pendingPayments: number;
    overduePayments: number;
    totalAmount: number;
    clearedAmount: number;
    overdueAmount: number;
};

const BOARD_COLUMNS = [
    { key: "draft", label: "Draft", statuses: ["DRAFT"], color: "bg-muted" },
    { key: "pending", label: "Pending Signature", statuses: ["PENDING_SIGNATURE"], color: "bg-warning" },
    { key: "active", label: "Active", statuses: ["ACTIVE", "NOTICE_GIVEN"], color: "bg-success" },
    { key: "closed", label: "Closed", statuses: ["TERMINATED", "EXPIRED", "CLOSED"], color: "bg-error" },
];

export default function LeasesPage() {
    const t = useTranslations("MasterData");
    const locale = useLocale();
    const [leases, setLeases] = useState<Lease[]>([]);
    const [units, setUnits] = useState<Unit[]>([]);
    const [renters, setRenters] = useState<Renter[]>([]);
    const [showForm, setShowForm] = useState(false);
    const [viewMode, setViewMode] = useState<'table' | 'cards' | 'board'>('table');
    const [currentPage, setCurrentPage] = useState(1);
    const [itemsPerPage, setItemsPerPage] = useState(25);
    const [totalItems, setTotalItems] = useState(0);
    const [searchQuery, setSearchQuery] = useState("");
    const [debouncedSearchQuery, setDebouncedSearchQuery] = useState("");
    const [paymentStatsMap, setPaymentStatsMap] = useState<Record<string, PaymentStats>>({});
    const [paymentStatsLoading, setPaymentStatsLoading] = useState(false);
    const [loading, setLoading] = useState(true);
    const [submitting, setSubmitting] = useState(false);
    const [actionLoading, setActionLoading] = useState<string | null>(null);
    const [paymentPreview, setPaymentPreview] = useState<{
        lines: { installmentNumber: number; dueDate: string; periodStart: string; periodEnd: string; amount: number; proRata: boolean }[];
        totalAmount: number;
        totalPayments: number;
        dueDayOfMonth: number;
        defaultPaymentMethod: string;
    } | null>(null);
    const [previewLoading, setPreviewLoading] = useState(false);
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
    const canManageLeases = hasPermission(userRole, 'canManageLeases');

    const [formData, setFormData] = useState({
        unitId: "",
        renterId: "",
        startDate: "",
        endDate: "",
        rentAmount: 0,
        depositAmount: 0,
        ejariNumber: "",
        paymentTerms: 1,
        paymentMethod: "CHEQUE",
        depositPaymentMethod: "CHEQUE",
        paymentReferenceNumber: "",
        // New fields for M11
        agreementDate: "",
        adminFee: 0,
        parkingRemoteFee: 0,
        rentVatApplicable: false,
        adminFeeVatApplicable: false,
        securityDepositVatApplicable: false,
        parkingRemoteVatApplicable: false,
    });

    // Booking deposit section (collapsed by default)
    const [bookingDepositOpen, setBookingDepositOpen] = useState(false);
    const [bookingDeposit, setBookingDeposit] = useState({
        amount: 0,
        chequeNumber: "",
        chequeDate: "",
        bankName: "",
    });

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
    }, [currentPage, itemsPerPage, debouncedSearchQuery]);

    useEffect(() => {
        if (leases.length > 0) {
            fetchPaymentStats(leases);
        }
    }, [leases]);

    useEffect(() => {
        if (!formData.startDate || !formData.endDate || !formData.rentAmount || !formData.unitId) {
            setPaymentPreview(null);
            return;
        }
        const selectedUnit = units.find(u => u.id === formData.unitId);
        const propertyId = selectedUnit?.property?.id;
        if (!propertyId) {
            setPaymentPreview(null);
            return;
        }

        const timer = setTimeout(() => {
            const params = new URLSearchParams({
                propertyId,
                startDate: formData.startDate,
                endDate: formData.endDate,
                monthlyRent: String(formData.rentAmount),
            });

            setPreviewLoading(true);
            fetch(`/api/proxy/v1/payments/preview?${params}`)
                .then(res => res.ok ? res.json() : null)
                .then(data => {
                    setPaymentPreview(data);
                    if (data) {
                        setFormData(prev => ({
                            ...prev,
                            paymentMethod: data.defaultPaymentMethod || 'CHEQUE',
                            paymentTerms: data.totalPayments || prev.paymentTerms,
                        }));
                    }
                })
                .catch(() => setPaymentPreview(null))
                .finally(() => setPreviewLoading(false));
        }, 500);

        return () => clearTimeout(timer);
    }, [formData.startDate, formData.endDate, formData.rentAmount, formData.unitId, units]);

    const fetchLeases = async () => {
        setLoading(true);
        try {
            const params = new URLSearchParams();
            params.set("page", String(Math.max(currentPage - 1, 0)));
            params.set("size", String(itemsPerPage));
            if (debouncedSearchQuery) params.set("search", debouncedSearchQuery);

            const res = await fetch(`/api/proxy/v1/leases/paged?${params.toString()}`);
            if (res.ok) {
                const data = await res.json();
                if (Array.isArray(data)) {
                    setLeases(data);
                    setTotalItems(data.length);
                } else {
                    setLeases(data.content ?? []);
                    setTotalItems(data.totalElements ?? 0);
                }
            }
        } catch (err) {
            console.error(err);
        } finally {
            setLoading(false);
        }
    };

    const fetchPaymentStats = async (allLeases: Lease[]) => {
        const activeLeaseIds = allLeases
            .filter(l => l.status === "ACTIVE" || l.status === "NOTICE_GIVEN")
            .map(l => l.id);
        if (activeLeaseIds.length === 0) return;

        setPaymentStatsLoading(true);
        try {
            const res = await fetch("/api/proxy/v1/payments/stats-by-leases", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(activeLeaseIds),
            });
            if (res.ok) {
                const stats: PaymentStats[] = await res.json();
                const map: Record<string, PaymentStats> = {};
                for (const s of stats) {
                    map[s.leaseId] = s;
                }
                setPaymentStatsMap(map);
            }
        } catch (err) {
            console.error(err);
        } finally {
            setPaymentStatsLoading(false);
        }
    };

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
        setPaymentPreview(null);
        const monthlyRent = lease.monthlyRent || lease.rentAmount;

        setEditingLeaseId(lease.id);
        fetchAttachments(lease.id);
        setBookingDepositOpen(false);
        setBookingDeposit({ amount: 0, chequeNumber: "", chequeDate: "", bankName: "" });
        setFormData({
            unitId: lease.unitId,
            renterId: lease.renterId,
            startDate: lease.startDate,
            endDate: lease.endDate,
            rentAmount: monthlyRent,
            depositAmount: lease.depositAmount,
            ejariNumber: lease.ejariNumber || "",
            paymentTerms: lease.paymentTerms || 1,
            paymentMethod: lease.paymentMethod || "CHEQUE",
            depositPaymentMethod: lease.depositPaymentMethod || "CHEQUE",
            paymentReferenceNumber: lease.paymentReferenceNumber || "",
            agreementDate: lease.agreementDate ?? "",
            adminFee: lease.adminFee ?? 0,
            parkingRemoteFee: lease.parkingRemoteFee ?? 0,
            rentVatApplicable: lease.rentVatApplicable ?? false,
            adminFeeVatApplicable: lease.adminFeeVatApplicable ?? false,
            securityDepositVatApplicable: lease.securityDepositVatApplicable ?? false,
            parkingRemoteVatApplicable: lease.parkingRemoteVatApplicable ?? false,
        });
        setShowForm(true);
    };

    const handleSubmit = async (ev: React.FormEvent) => {
        ev.preventDefault();
        setSubmitting(true);
        try {
            // Send both total rent (from preview) and monthly rent to backend
            const submitData: Record<string, unknown> = {
                ...formData,
                monthlyRent: formData.rentAmount,
                rentAmount: paymentPreview ? paymentPreview.totalAmount : formData.rentAmount,
                agreementDate: formData.agreementDate || null,
                adminFee: formData.adminFee || 0,
                parkingRemoteFee: formData.parkingRemoteFee || 0,
            };
            // Include bookingDeposit if amount > 0
            if (bookingDeposit.amount > 0) {
                submitData.bookingDeposit = {
                    amount: bookingDeposit.amount,
                    chequeNumber: bookingDeposit.chequeNumber || null,
                    chequeDate: bookingDeposit.chequeDate || null,
                    bankName: bookingDeposit.bankName || null,
                };
            }
            const url = editingLeaseId
                ? `/api/proxy/v1/leases/${editingLeaseId}`
                : "/api/proxy/v1/leases";
            const method = editingLeaseId ? "PUT" : "POST";
            const res = await fetch(url, {
                method,
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(submitData)
            });
            if (res.ok) {
                setShowForm(false);
                setEditingLeaseId(null);
                fetchLeases();
                fetchUnits();
                setFormData({
                    unitId: "", renterId: "", startDate: "", endDate: "",
                    rentAmount: 0, depositAmount: 0, ejariNumber: "", paymentTerms: 1,
                    paymentMethod: "CHEQUE", depositPaymentMethod: "CHEQUE", paymentReferenceNumber: "",
                    agreementDate: "", adminFee: 0, parkingRemoteFee: 0,
                    rentVatApplicable: false, adminFeeVatApplicable: false,
                    securityDepositVatApplicable: false, parkingRemoteVatApplicable: false,
                });
                setBookingDeposit({ amount: 0, chequeNumber: "", chequeDate: "", bankName: "" });
                setBookingDepositOpen(false);
            }
        } catch (err) {
            console.error(err);
        } finally {
            setSubmitting(false);
        }
    };

    const handleActivate = (id: string) => {
        setConfirmConfig({
            title: t("activateLease"),
            description: t("confirmActivate"),
            confirmText: t("activate"),
            isDestructive: false,
            onConfirm: async () => {
                setActionLoading('activate');
                try {
                    const res = await fetch(`/api/proxy/v1/leases/${id}/activate`, { method: "PUT" });
                    if (res.ok) fetchLeases();
                } catch (err) {
                    console.error(err);
                } finally {
                    setActionLoading(null);
                    setConfirmOpen(false);
                }
            }
        });
        setConfirmOpen(true);
    };

    const router = useRouter();

    const handleTerminate = (id: string) => {
        // Redirect to lease detail page where the full settlement modal is available
        router.push(`/dashboard/leases/${id}?action=terminate`);
        return;
        // Legacy direct terminate (kept for reference)
        setConfirmConfig({
            title: t("terminateLease"),
            description: t("confirmTerminate"),
            confirmText: t("terminate"),
            isDestructive: true,
            onConfirm: async () => {
                setActionLoading('terminate');
                try {
                    const res = await fetch(`/api/proxy/v1/leases/${id}/terminate`, {
                        method: "POST",
                        headers: { "Content-Type": "application/json" },
                        body: JSON.stringify({ notes: "Quick termination from list view" }),
                    });
                    if (res.ok) fetchLeases();
                } catch (err) {
                    console.error(err);
                } finally {
                    setActionLoading(null);
                    setConfirmOpen(false);
                }
            }
        });
        setConfirmOpen(true);
    };

    const handleUnitChange = (unitId: string) => {
        const selectedUnit = units.find(u => u.id === unitId);
        // Auto-set VAT toggles if property is COMMERCIAL
        const isCommercial = selectedUnit?.property?.type === "COMMERCIAL";
        setFormData(prev => ({
            ...prev,
            unitId,
            rentVatApplicable: isCommercial,
            adminFeeVatApplicable: isCommercial,
            securityDepositVatApplicable: isCommercial,
            parkingRemoteVatApplicable: isCommercial,
        }));
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
                alert(detail || "Failed to generate contract. Please try again.");
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

    const renderPaymentProgress = (leaseId: string) => {
        const stats = paymentStatsMap[leaseId];

        if (paymentStatsLoading && !stats) {
            return (
                <div className="mt-3 pt-3 border-t border-border">
                    <div className="h-1.5 bg-input rounded-full overflow-hidden animate-pulse" />
                    <div className="mt-1.5 h-3 w-24 bg-input rounded animate-pulse" />
                </div>
            );
        }

        if (!stats) return null;

        const progressPercent = stats.totalPayments > 0
            ? Math.round((stats.clearedPayments / stats.totalPayments) * 100)
            : 0;

        return (
            <div className="mt-3 pt-3 border-t border-border">
                <div className="h-1.5 bg-input rounded-full overflow-hidden">
                    <div
                        className="h-full bg-success rounded-full transition-all duration-500"
                        style={{ width: `${progressPercent}%` }}
                    />
                </div>
                <div className="flex items-center gap-2 mt-1.5">
                    <span className="text-xs font-medium text-muted">
                        {stats.clearedPayments}/{stats.totalPayments} {t("paymentsProgress")}
                    </span>
                    {stats.overduePayments > 0 && (
                        <span className="inline-flex items-center px-1.5 py-0.5 rounded-full text-[9px] font-bold bg-error/10 text-error border border-error/20">
                            {stats.overduePayments} {t("overduePayments")}
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
        <div key={lease.id} className={cn("bg-surface rounded-xl p-5 border border-border hover:shadow-md transition-all duration-200 flex flex-col justify-between", compact && "p-4")}>
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
                        <span className={cn("inline-flex items-center px-2.5 py-1 rounded-full text-[9px] font-bold uppercase tracking-widest border", getStatusColor(lease.status))}>
                            {lease.status.replace('_', ' ')}
                        </span>
                    )}
                </div>

                <div className={cn("space-y-3", compact ? "mb-3" : "mb-6")}>
                    <div className={cn("flex justify-between items-center bg-input/70 rounded-xl border border-border", compact ? "p-2" : "p-3")}>
                        <div className="flex items-center gap-2">
                            <DollarSign size={14} className="text-muted" />
                            <span className="text-[10px] font-semibold text-muted uppercase tracking-[0.15em]">{t("rentSummary")}</span>
                        </div>
                        <span className={cn("font-bold text-foreground text-right tabular-nums", compact ? "text-[10px]" : "text-xs")}>
                            {formatCurrencyCompact(lease.rentAmount)}
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

                {!compact && (lease.status === 'ACTIVE' || lease.status === 'NOTICE_GIVEN') && renderPaymentProgress(lease.id)}
            </div>

            {canManageLeases && (
                <div className={cn("flex gap-2 border-t border-border mt-auto", compact ? "pt-3 flex-wrap" : "pt-4")}>
                    {lease.status === 'DRAFT' && (
                        <button
                            onClick={() => handleEditDraft(lease)}
                            className="flex items-center justify-center gap-2 bg-input text-foreground hover:bg-input/80 py-2.5 px-3 rounded-xl text-xs font-bold transition-all duration-200 cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none"
                        >
                            <Pencil size={14} />
                            {t("edit")}
                        </button>
                    )}
                    {lease.status === 'DRAFT' && !lease.hasContract && (
                        <button
                            onClick={() => handleGenerateContract(lease.id)}
                            disabled={actionLoading === `generate-${lease.id}`}
                            className="flex-1 flex items-center justify-center gap-2 bg-blue-50 text-blue-700 hover:bg-blue-100 py-2.5 rounded-xl text-xs font-bold transition-all duration-200 cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none disabled:opacity-50"
                        >
                            {actionLoading === `generate-${lease.id}` ? <Loader2 size={14} className="animate-spin" /> : <Sparkles size={14} />}
                            {t("generateContract")}
                        </button>
                    )}
                    {lease.status === 'DRAFT' && lease.hasContract && (
                        <button
                            onClick={() => handleActivate(lease.id)}
                            className="flex-1 flex items-center justify-center gap-2 bg-green-50 text-green-700 hover:bg-green-100 py-2.5 rounded-xl text-xs font-bold transition-all duration-200 cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none"
                        >
                            <CheckCircle size={14} />
                            {t("activate")}
                        </button>
                    )}
                    {lease.status === 'PENDING_SIGNATURE' && (
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
                                title="Regenerate contract"
                            >
                                {actionLoading === `generate-${lease.id}` ? <Loader2 size={14} className="animate-spin" /> : <RefreshCw size={14} />}
                            </button>
                            <button
                                onClick={() => handleActivate(lease.id)}
                                className="flex-1 flex items-center justify-center gap-2 bg-green-50 text-green-700 hover:bg-green-100 py-2.5 rounded-xl text-xs font-bold transition-all duration-200 cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none"
                            >
                                <CheckCircle size={14} />
                                {t("activate")}
                            </button>
                        </>
                    )}
                    {lease.status === 'ACTIVE' && (
                        <button
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
                        Docs
                    </button>
                </div>
            )}
        </div>
    );

    const filteredLeases = leases;

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
                <div className="flex flex-col md:flex-row md:items-center justify-between gap-3">
                    <div className="relative">
                        <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-muted" />
                        <input
                            type="text"
                            placeholder="Search..."
                            value={searchQuery}
                            onChange={(e) => setSearchQuery(e.target.value)}
                            className="pl-9 pr-4 py-2 bg-surface border border-border rounded-lg text-sm text-foreground placeholder:text-muted/50 focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none w-64 transition-all"
                        />
                    </div>
                    <div className="flex items-center gap-3">
                    <div className="flex items-center bg-input rounded-lg p-0.5 border border-border">
                        <button
                            onClick={() => { setViewMode('table'); setCurrentPage(1); }}
                            className={cn(
                                "px-3 py-1.5 rounded-md text-xs font-medium transition-all cursor-pointer flex items-center gap-1.5",
                                viewMode === 'table' ? "bg-surface text-foreground shadow-sm border border-border" : "text-muted hover:text-foreground"
                            )}
                        >
                            <List size={13} />
                            Table
                        </button>
                        <button
                            onClick={() => { setViewMode('cards'); setCurrentPage(1); }}
                            className={cn(
                                "px-3 py-1.5 rounded-md text-xs font-medium transition-all cursor-pointer flex items-center gap-1.5",
                                viewMode === 'cards' ? "bg-surface text-foreground shadow-sm border border-border" : "text-muted hover:text-foreground"
                            )}
                        >
                            <LayoutGrid size={13} />
                            Cards
                        </button>
                        <button
                            onClick={() => setViewMode('board')}
                            className={cn(
                                "px-3 py-1.5 rounded-md text-xs font-medium transition-all cursor-pointer flex items-center gap-1.5",
                                viewMode === 'board' ? "bg-surface text-foreground shadow-sm border border-border" : "text-muted hover:text-foreground"
                            )}
                        >
                            <Columns3 size={13} />
                            Board
                        </button>
                    </div>
                    {canManageLeases && (
                        <button
                            onClick={() => setShowForm(true)}
                            className="flex items-center gap-2 bg-primary text-primary-foreground px-4 py-2 rounded-lg text-xs font-semibold hover:opacity-90 transition-all duration-200 cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none"
                        >
                            <Plus size={14} />
                            {t("draftLease")}
                        </button>
                    )}
                    </div>
                </div>
            </div>

            {showForm && (
                <div className="fixed inset-0 bg-foreground/40 backdrop-blur-sm flex items-center justify-center p-4 z-[100] overflow-y-auto">
                    <div className="bg-surface rounded-xl p-8 max-w-2xl w-full shadow-2xl border border-border relative my-8">
                        <button onClick={() => { setShowForm(false); setEditingLeaseId(null); setAttachments([]); setDocName(""); }} aria-label="Close form" className="absolute right-6 top-6 p-2 text-muted hover:text-foreground cursor-pointer transition-colors duration-200 focus:ring-2 focus:ring-primary/30 focus:outline-none rounded-lg"><X size={18} /></button>
                        <h2 className="text-lg font-bold mb-1">{editingLeaseId ? t("editLease") : t("draftNewLease")}</h2>
                        <p className="text-xs text-muted mb-8 font-medium">{editingLeaseId ? t("editLeaseDesc") : t("draftNewLeaseDesc")}</p>
                        <form onSubmit={handleSubmit} className="grid grid-cols-2 gap-5">
                            <div className="col-span-1">
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">{t("selectUnit")}</label>
                                <select required className="w-full bg-input border border-border p-3 rounded-xl text-xs cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none" value={formData.unitId} onChange={ev => handleUnitChange(ev.target.value)}>
                                    <option value="">{t("chooseVacantUnit")}</option>
                                    {units.map(u => <option key={u.id} value={u.id}>{u.property?.nameEn ? `${u.property.nameEn} — ` : ""}{t("unit")} {u.unitNumber}</option>)}
                                </select>
                            </div>
                            <div className="col-span-1">
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">{t("selectRenter")}</label>
                                <select required className="w-full bg-input border border-border p-3 rounded-xl text-xs cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none" value={formData.renterId} onChange={ev => setFormData({ ...formData, renterId: ev.target.value })}>
                                    <option value="">{t("chooseRenter")}</option>
                                    {renters.map(r => <option key={r.id} value={r.id}>{getRenterDisplayName(r)}</option>)}
                                </select>
                            </div>
                            <div className="col-span-1">
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">{t("startDate")}</label>
                                <input required type="date" className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none" value={formData.startDate} onChange={ev => setFormData({ ...formData, startDate: ev.target.value })} />
                            </div>
                            <div className="col-span-1">
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">{t("endDate")}</label>
                                <input required type="date" className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none" value={formData.endDate} onChange={ev => setFormData({ ...formData, endDate: ev.target.value })} />
                            </div>
                            <div className="col-span-1">
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">Monthly Rent (AED)</label>
                                <div className="flex items-center gap-2">
                                    <input required type="number" min="0" placeholder="5000" className="flex-1 bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none" value={formData.rentAmount || ''} onChange={ev => setFormData({ ...formData, rentAmount: Number(ev.target.value) })} />
                                    <label className="flex items-center gap-1.5 cursor-pointer shrink-0">
                                        <input type="checkbox" className="rounded" checked={formData.rentVatApplicable} onChange={ev => setFormData({ ...formData, rentVatApplicable: ev.target.checked })} />
                                        <span className="text-[10px] font-semibold text-muted whitespace-nowrap">{t("vatApplicable")}</span>
                                    </label>
                                </div>
                            </div>
                            <div className="col-span-1">
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">{t("securityDeposit")}</label>
                                <div className="flex items-center gap-2">
                                    <input required type="number" min="0" placeholder="2500" className="flex-1 bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none" value={formData.depositAmount || ''} onChange={ev => setFormData({ ...formData, depositAmount: Number(ev.target.value) })} />
                                    <label className="flex items-center gap-1.5 cursor-pointer shrink-0">
                                        <input type="checkbox" className="rounded" checked={formData.securityDepositVatApplicable} onChange={ev => setFormData({ ...formData, securityDepositVatApplicable: ev.target.checked })} />
                                        <span className="text-[10px] font-semibold text-muted whitespace-nowrap">{t("vatApplicable")}</span>
                                    </label>
                                </div>
                            </div>
                            <div className="col-span-1">
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">{t("ejariNumber")}</label>
                                <input placeholder="EJAR-12345" className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none" value={formData.ejariNumber} onChange={ev => setFormData({ ...formData, ejariNumber: ev.target.value })} />
                            </div>
                            <div className="col-span-1">
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">{t("paymentMethod")}</label>
                                <select className="w-full bg-input border border-border p-3 rounded-xl text-xs cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none" value={formData.paymentMethod} onChange={ev => setFormData({ ...formData, paymentMethod: ev.target.value })}>
                                    <option value="CHEQUE">Cheque</option>
                                    <option value="ONLINE">Online Payment</option>
                                </select>
                            </div>
                            <div className="col-span-1">
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">{t("paymentTerms")}</label>
                                <div className="w-full bg-input border border-border p-3 rounded-xl text-xs text-muted font-medium">
                                    {paymentPreview ? `${paymentPreview.totalPayments} ${formData.paymentMethod === 'CHEQUE' ? 'Cheques' : 'Payments'}` : 'Select dates & rent amount'}
                                </div>
                            </div>
                            <div className="col-span-1">
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">{t("depositPaymentMethod")}</label>
                                <select className="w-full bg-input border border-border p-3 rounded-xl text-xs cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none" value={formData.depositPaymentMethod} onChange={ev => setFormData({ ...formData, depositPaymentMethod: ev.target.value })}>
                                    <option value="CHEQUE">Cheque</option>
                                    <option value="ONLINE">Online Payment</option>
                                </select>
                            </div>
                            <div className="col-span-1">
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">{t("paymentReference")}</label>
                                <input placeholder="REF-12345" className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none" value={formData.paymentReferenceNumber} onChange={ev => setFormData({ ...formData, paymentReferenceNumber: ev.target.value })} />
                            </div>
                            {/* Agreement Date */}
                            <div className="col-span-1">
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">{t("agreementDate")}</label>
                                <input type="date" className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none" value={formData.agreementDate} onChange={ev => setFormData({ ...formData, agreementDate: ev.target.value })} />
                            </div>
                            {/* Admin Fee */}
                            <div className="col-span-1">
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">{t("adminFee")} (AED)</label>
                                <div className="flex items-center gap-2">
                                    <input type="number" min="0" placeholder="0" className="flex-1 bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none" value={formData.adminFee || ''} onChange={ev => setFormData({ ...formData, adminFee: Number(ev.target.value) })} />
                                    <label className="flex items-center gap-1.5 cursor-pointer shrink-0">
                                        <input type="checkbox" className="rounded" checked={formData.adminFeeVatApplicable} onChange={ev => setFormData({ ...formData, adminFeeVatApplicable: ev.target.checked })} />
                                        <span className="text-[10px] font-semibold text-muted whitespace-nowrap">{t("vatApplicable")}</span>
                                    </label>
                                </div>
                            </div>
                            {/* Parking Remote Fee */}
                            <div className="col-span-1">
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">{t("parkingRemoteFee")} (AED)</label>
                                <div className="flex items-center gap-2">
                                    <input type="number" min="0" placeholder="0" className="flex-1 bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none" value={formData.parkingRemoteFee || ''} onChange={ev => setFormData({ ...formData, parkingRemoteFee: Number(ev.target.value) })} />
                                    <label className="flex items-center gap-1.5 cursor-pointer shrink-0">
                                        <input type="checkbox" className="rounded" checked={formData.parkingRemoteVatApplicable} onChange={ev => setFormData({ ...formData, parkingRemoteVatApplicable: ev.target.checked })} />
                                        <span className="text-[10px] font-semibold text-muted whitespace-nowrap">{t("vatApplicable")}</span>
                                    </label>
                                </div>
                            </div>
                            {/* Booking Deposit (collapsible) */}
                            <div className="col-span-2">
                                <button
                                    type="button"
                                    onClick={() => setBookingDepositOpen(o => !o)}
                                    className="flex items-center gap-2 text-[10px] font-semibold text-muted uppercase tracking-[0.15em] hover:text-foreground transition-colors cursor-pointer"
                                >
                                    <span className={cn("transition-transform", bookingDepositOpen ? "rotate-90" : "rotate-0")}>▶</span>
                                    {t("bookingDeposit")}
                                    {bookingDeposit.amount > 0 && <span className="text-primary font-bold ml-1">({bookingDeposit.amount} AED)</span>}
                                </button>
                                {bookingDepositOpen && (
                                    <div className="mt-3 grid grid-cols-2 gap-3 border border-border rounded-xl p-4 bg-input/20">
                                        <div className="col-span-1">
                                            <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5 ml-1">Amount (AED)</label>
                                            <input type="number" min="0" placeholder="0" className="w-full bg-input border border-border p-2.5 rounded-lg text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none" value={bookingDeposit.amount || ''} onChange={ev => setBookingDeposit(b => ({ ...b, amount: Number(ev.target.value) }))} />
                                        </div>
                                        <div className="col-span-1">
                                            <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5 ml-1">Cheque Number</label>
                                            <input placeholder="CH-001" className="w-full bg-input border border-border p-2.5 rounded-lg text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none" value={bookingDeposit.chequeNumber} onChange={ev => setBookingDeposit(b => ({ ...b, chequeNumber: ev.target.value }))} />
                                        </div>
                                        <div className="col-span-1">
                                            <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5 ml-1">Cheque Date</label>
                                            <input type="date" className="w-full bg-input border border-border p-2.5 rounded-lg text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none" value={bookingDeposit.chequeDate} onChange={ev => setBookingDeposit(b => ({ ...b, chequeDate: ev.target.value }))} />
                                        </div>
                                        <div className="col-span-1">
                                            <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5 ml-1">Bank Name</label>
                                            <input placeholder="e.g. Emirates NBD" className="w-full bg-input border border-border p-2.5 rounded-lg text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none" value={bookingDeposit.bankName} onChange={ev => setBookingDeposit(b => ({ ...b, bankName: ev.target.value }))} />
                                        </div>
                                    </div>
                                )}
                            </div>
                            {/* Payment Schedule Preview */}
                            {paymentPreview && paymentPreview.lines.length > 0 && (
                                <div className="col-span-2 mt-2">
                                    <label className="block text-[10px] font-semibold text-muted uppercase tracking-[0.15em] mb-2 ml-1">
                                        Payment Schedule Preview
                                    </label>
                                    <div className="border border-border rounded-xl overflow-hidden max-h-72 overflow-y-auto">
                                        <table className="w-full">
                                            <thead className="sticky top-0">
                                                <tr className="bg-input/70 border-b border-border">
                                                    <th className="text-left px-4 py-2 text-[11px] font-semibold text-muted uppercase tracking-wider">#</th>
                                                    <th className="text-left px-4 py-2 text-[11px] font-semibold text-muted uppercase tracking-wider">Due Date</th>
                                                    <th className="text-left px-4 py-2 text-[11px] font-semibold text-muted uppercase tracking-wider">Period</th>
                                                    <th className="text-right px-4 py-2 text-[11px] font-semibold text-muted uppercase tracking-wider">Amount (AED)</th>
                                                    <th className="text-center px-4 py-2 text-[11px] font-semibold text-muted uppercase tracking-wider">Type</th>
                                                </tr>
                                            </thead>
                                            <tbody className="divide-y divide-border">
                                                {paymentPreview.lines.map((line, i) => (
                                                    <tr key={i} className="hover:bg-input/30 transition-colors">
                                                        <td className="px-4 py-2 text-xs text-muted">{line.installmentNumber}</td>
                                                        <td className="px-4 py-2 text-xs font-medium">{line.dueDate}</td>
                                                        <td className="px-4 py-2 text-[10px] text-muted">{line.periodStart} &rarr; {line.periodEnd}</td>
                                                        <td className="px-4 py-2 text-right">
                                                            <input
                                                                type="number"
                                                                step="0.01"
                                                                className="w-28 text-right bg-input border border-border p-1.5 rounded-lg text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none"
                                                                value={line.amount}
                                                                onChange={(ev) => {
                                                                    const updated = { ...paymentPreview };
                                                                    updated.lines = [...updated.lines];
                                                                    updated.lines[i] = { ...updated.lines[i], amount: Number(ev.target.value) };
                                                                    updated.totalAmount = updated.lines.reduce((s, l) => s + l.amount, 0);
                                                                    setPaymentPreview(updated);
                                                                }}
                                                            />
                                                        </td>
                                                        <td className="px-4 py-2 text-center">
                                                            {line.proRata ? (
                                                                <span className="text-[9px] bg-warning/10 text-warning px-2 py-0.5 rounded-full font-bold border border-warning/20">Pro-rata</span>
                                                            ) : (
                                                                <span className="text-[9px] bg-input text-muted px-2 py-0.5 rounded-full font-bold border border-border">Full</span>
                                                            )}
                                                        </td>
                                                    </tr>
                                                ))}
                                            </tbody>
                                            <tfoot>
                                                <tr className="bg-input/70 border-t-2 border-border">
                                                    <td colSpan={3} className="px-4 py-2 text-xs font-bold uppercase">Total</td>
                                                    <td className="px-4 py-2 text-right text-xs font-bold text-primary tabular-nums">
                                                        {formatCurrency(paymentPreview.totalAmount)}
                                                    </td>
                                                    <td className="px-4 py-2 text-center text-[10px] text-muted">{paymentPreview.totalPayments} payments</td>
                                                </tr>
                                            </tfoot>
                                        </table>
                                    </div>
                                </div>
                            )}
                            {previewLoading && (
                                <div className="col-span-2 flex items-center gap-2 text-xs text-muted">
                                    <Loader2 size={14} className="animate-spin" />
                                    Calculating payment schedule...
                                </div>
                            )}
                            <div className="col-span-2 flex justify-end gap-3 mt-4">
                                <button type="button" onClick={() => setShowForm(false)} className="px-6 py-3 text-xs font-bold text-muted cursor-pointer hover:text-foreground transition-colors duration-200 focus:ring-2 focus:ring-primary/30 focus:outline-none rounded-xl">{t("cancel")}</button>
                                <button type="submit" disabled={submitting || !paymentPreview} className="px-8 py-3 bg-primary text-primary-foreground rounded-xl text-xs font-bold cursor-pointer hover:opacity-90 transition-all duration-200 focus:ring-2 focus:ring-primary/30 focus:outline-none disabled:opacity-50 flex items-center gap-2">
                                    {submitting && <Loader2 size={14} className="animate-spin" />}
                                    {editingLeaseId ? t("saveChanges") : t("draftLease")}
                                </button>
                            </div>
                        </form>

                        {/* Supporting Documents */}
                        {editingLeaseId && (
                            <div className="mt-6 pt-6 border-t border-border">
                                <h4 className="text-[11px] font-semibold text-muted uppercase tracking-wider mb-3">Supporting Documents</h4>

                                {/* Existing attachments */}
                                {attachments.length > 0 && (
                                    <div className="space-y-2 mb-3">
                                        {attachments.map(doc => (
                                            <div key={doc.id} className="flex items-center justify-between bg-input/50 rounded-lg px-3 py-2 border border-border">
                                                <div className="flex items-center gap-2">
                                                    <FileText size={14} className="text-muted" />
                                                    <div>
                                                        <p className="text-xs font-medium text-foreground">{doc.name}</p>
                                                        <p className="text-[10px] text-muted">{doc.fileType} &bull; {(doc.fileSize / 1024).toFixed(0)} KB</p>
                                                    </div>
                                                </div>
                                                <div className="flex items-center gap-2">
                                                    <button onClick={() => handleDocDownload(doc.id, doc.name)} className="text-[10px] font-semibold text-primary hover:text-primary/80 cursor-pointer">Download</button>
                                                    <button onClick={() => handleDocDelete(doc.id, editingLeaseId)} className="text-[10px] font-semibold text-error hover:text-error/80 cursor-pointer">Delete</button>
                                                </div>
                                            </div>
                                        ))}
                                    </div>
                                )}

                                {/* Upload new */}
                                <div className="flex items-center gap-2">
                                    <input
                                        type="text"
                                        value={docName}
                                        onChange={(e) => setDocName(e.target.value)}
                                        placeholder="Document name (e.g. Emirates ID)"
                                        className="flex-1 border border-border rounded-lg bg-surface px-3 py-2 text-xs text-foreground placeholder:text-muted/50 focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none"
                                    />
                                    <label className={cn(
                                        "flex items-center gap-1.5 px-3 py-2 bg-primary/10 text-primary rounded-lg text-xs font-semibold hover:bg-primary/20 transition-colors cursor-pointer",
                                        (!docName.trim() || uploadingDoc) && "opacity-50 cursor-not-allowed"
                                    )}>
                                        {uploadingDoc ? <Loader2 size={12} className="animate-spin" /> : <Upload size={12} />}
                                        Attach
                                        <input
                                            type="file"
                                            className="hidden"
                                            disabled={!docName.trim() || uploadingDoc}
                                            onChange={(e) => {
                                                const file = e.target.files?.[0];
                                                if (file && editingLeaseId) handleDocUpload(editingLeaseId, file);
                                                e.target.value = "";
                                            }}
                                        />
                                    </label>
                                </div>
                            </div>
                        )}
                    </div>
                </div>
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
                                        <th className="text-left px-4 py-3 text-[11px] font-semibold text-muted uppercase tracking-wider">Unit</th>
                                        <th className="text-left px-4 py-3 text-[11px] font-semibold text-muted uppercase tracking-wider">Renter</th>
                                        <th className="text-left px-4 py-3 text-[11px] font-semibold text-muted uppercase tracking-wider">Property</th>
                                        <th className="text-left px-4 py-3 text-[11px] font-semibold text-muted uppercase tracking-wider">Start Date</th>
                                        <th className="text-left px-4 py-3 text-[11px] font-semibold text-muted uppercase tracking-wider">End Date</th>
                                        <th className="text-right px-4 py-3 text-[11px] font-semibold text-muted uppercase tracking-wider">Monthly Rent (AED)</th>
                                        <th className="text-center px-4 py-3 text-[11px] font-semibold text-muted uppercase tracking-wider">Status</th>
                                        <th className="text-center px-4 py-3 text-[11px] font-semibold text-muted uppercase tracking-wider">Actions</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {filteredLeases.map(lease => {
                                        const monthlyRent = lease.monthlyRent || lease.rentAmount;
                                        return (
                                            <tr key={lease.id} className="border-b border-border hover:bg-input/30 transition-colors">
                                                <td className="px-4 py-3 text-xs font-medium text-foreground">{lease.unitIdentifier}</td>
                                                <td className="px-4 py-3 text-xs text-foreground">{lease.renterName}</td>
                                                <td className="px-4 py-3 text-xs text-muted">{lease.propertyName}</td>
                                                <td className="px-4 py-3 text-xs text-foreground tabular-nums">{new Date(lease.startDate).toLocaleDateString()}</td>
                                                <td className="px-4 py-3 text-xs text-foreground tabular-nums">{new Date(lease.endDate).toLocaleDateString()}</td>
                                                <td className="px-4 py-3 text-xs font-medium text-foreground text-right tabular-nums">{formatCurrency(monthlyRent)}</td>
                                                <td className="px-4 py-3 text-center">
                                                    <span className={cn("inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-semibold", getStatusColor(lease.status))}>
                                                        {lease.status.replace('_', ' ')}
                                                    </span>
                                                </td>
                                                <td className="px-4 py-3 text-center">
                                                    <div className="flex items-center justify-center gap-1.5">
                                                        {lease.status === 'DRAFT' && canManageLeases && (
                                                            <button
                                                                onClick={() => handleEditDraft(lease)}
                                                                className="inline-flex items-center gap-1 px-2 py-1 rounded-md text-[10px] font-medium bg-input text-foreground hover:bg-input/80 transition-colors cursor-pointer"
                                                            >
                                                                <Pencil size={11} /> Edit
                                                            </button>
                                                        )}
                                                        {lease.status === 'DRAFT' && !lease.hasContract && canManageLeases && (
                                                            <button
                                                                onClick={() => handleGenerateContract(lease.id)}
                                                                disabled={actionLoading === `generate-${lease.id}`}
                                                                className="inline-flex items-center gap-1 px-2 py-1 rounded-md text-[10px] font-medium bg-blue-50 text-blue-700 hover:bg-blue-100 transition-colors cursor-pointer disabled:opacity-50"
                                                            >
                                                                {actionLoading === `generate-${lease.id}` ? <Loader2 size={11} className="animate-spin" /> : <Sparkles size={11} />} Generate
                                                            </button>
                                                        )}
                                                        {(lease.status === 'DRAFT' && lease.hasContract || lease.status === 'PENDING_SIGNATURE') && canManageLeases && (
                                                            <button
                                                                onClick={() => handleActivate(lease.id)}
                                                                className="inline-flex items-center gap-1 px-2 py-1 rounded-md text-[10px] font-medium bg-green-50 text-green-700 hover:bg-green-100 transition-colors cursor-pointer"
                                                            >
                                                                <CheckCircle size={11} /> Activate
                                                            </button>
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
                                                        {lease.status === 'ACTIVE' && canManageLeases && (
                                                            <button
                                                                onClick={() => handleTerminate(lease.id)}
                                                                className="inline-flex items-center gap-1 px-2 py-1 rounded-md text-[10px] font-medium bg-red-50 text-red-600 hover:bg-red-100 transition-colors cursor-pointer"
                                                            >
                                                                <Ban size={11} /> Terminate
                                                            </button>
                                                        )}
                                                        <button
                                                            onClick={() => openDocsModal(lease.id)}
                                                            className="inline-flex items-center gap-1 px-2 py-1 rounded-md text-[10px] font-medium bg-input text-foreground hover:bg-border transition-colors cursor-pointer"
                                                        >
                                                            <FileText size={11} /> Docs
                                                        </button>
                                                        <Link
                                                            href={`/dashboard/leases/${lease.id}`}
                                                            className="inline-flex items-center gap-1 px-2 py-1 rounded-md text-[10px] font-medium text-primary hover:bg-primary/10 transition-colors"
                                                        >
                                                            View
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
                                        <h3 className="text-xs font-bold text-foreground uppercase tracking-widest">{col.label}</h3>
                                        <span className="ml-auto text-[10px] font-bold text-muted bg-input px-2 py-0.5 rounded-full">{columnLeases.length}</span>
                                    </div>
                                    <div className="space-y-3 min-h-[200px] bg-background rounded-xl p-3 border border-border">
                                        {columnLeases.map(lease => renderLeaseCard(lease, true))}
                                        {columnLeases.length === 0 && (
                                            <div className="text-center py-8 text-[10px] text-muted font-bold uppercase tracking-widest">
                                                No leases
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

            {!loading && leases.length === 0 && !showForm && (
                <div className="text-center py-24 bg-background border border-dashed border-border rounded-xl flex flex-col items-center">
                    <div className="w-16 h-16 bg-surface rounded-xl flex items-center justify-center text-muted shadow-sm mb-6">
                        <AlertCircle size={32} />
                    </div>
                    <p className="text-sm font-bold text-muted mb-6 uppercase tracking-widest">
                        {t("noLeasesFound")}
                    </p>
                    {canManageLeases && (
                        <button onClick={() => setShowForm(true)} className="text-xs font-bold text-foreground border-b-2 border-primary pb-0.5 hover:text-primary transition-all duration-200 cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none">
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
                            aria-label="Close"
                            className="absolute right-4 top-4 p-2 text-muted hover:text-foreground transition-all cursor-pointer rounded-lg"
                        >
                            <X size={18} />
                        </button>
                        <h2 className="text-lg font-bold text-foreground mb-1">Supporting Documents</h2>
                        <p className="text-xs text-muted mb-5">Upload and manage documents for this lease.</p>

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
                                            <button onClick={() => handleDocDownload(doc.id, doc.name)} className="text-[10px] font-semibold text-primary hover:text-primary/80 cursor-pointer">Download</button>
                                            <button onClick={() => handleDocDelete(doc.id, docsLeaseId)} className="text-[10px] font-semibold text-error hover:text-error/80 cursor-pointer">Delete</button>
                                        </div>
                                    </div>
                                ))}
                            </div>
                        )}

                        {attachments.length === 0 && (
                            <div className="text-center py-6 text-muted mb-4">
                                <FileText size={24} className="mx-auto mb-2 opacity-40" />
                                <p className="text-xs">No documents attached yet.</p>
                            </div>
                        )}

                        {/* Upload new */}
                        <div className="pt-3 border-t border-border">
                            <p className="text-[10px] text-muted mb-2">Enter a document name, then click Attach to upload a file.</p>
                            <div className="flex items-center gap-2">
                                <input
                                    type="text"
                                    value={docName}
                                    onChange={(e) => setDocName(e.target.value)}
                                    placeholder="e.g. Emirates ID, Trade License, Agreement"
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
