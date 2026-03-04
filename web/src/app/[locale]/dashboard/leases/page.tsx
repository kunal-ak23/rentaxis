"use client";

import { useState, useEffect } from "react";
import { useTranslations, useLocale } from "next-intl";
import { Plus, X, FileText, Calendar, DollarSign, Home, CheckCircle, Ban, AlertCircle, LayoutGrid, Columns3, Download, Sparkles } from "lucide-react";
import { useSession } from "next-auth/react";
import { hasPermission, type UserRole } from "@/lib/rbac";
import { cn } from "@/lib/utils";

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
    depositAmount: number;
    ejariNumber: string;
    paymentTerms: number;
    propertyId: string;
    propertyName: string;
    hasContract: boolean;
};

type Unit = {
    id: string;
    unitNumber: string;
    status: string;
};

type Renter = {
    id: string;
    nameEn: string;
    nameAr: string;
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
    { key: "draft", label: "Draft", statuses: ["DRAFT"], color: "bg-gray-500" },
    { key: "pending", label: "Pending Signature", statuses: ["PENDING_SIGNATURE"], color: "bg-yellow-500" },
    { key: "active", label: "Active", statuses: ["ACTIVE", "NOTICE_GIVEN"], color: "bg-green-500" },
    { key: "closed", label: "Closed", statuses: ["TERMINATED", "EXPIRED", "CLOSED"], color: "bg-red-500" },
];

export default function LeasesPage() {
    const t = useTranslations("MasterData");
    const locale = useLocale();
    const [leases, setLeases] = useState<Lease[]>([]);
    const [units, setUnits] = useState<Unit[]>([]);
    const [renters, setRenters] = useState<Renter[]>([]);
    const [showForm, setShowForm] = useState(false);
    const [viewMode, setViewMode] = useState<'grid' | 'board'>('grid');
    const [paymentStatsMap, setPaymentStatsMap] = useState<Record<string, PaymentStats>>({});
    const [paymentStatsLoading, setPaymentStatsLoading] = useState(false);

    const { data: session } = useSession();
    const userRole = (session?.user as any)?.role as UserRole | undefined;
    const canManageLeases = hasPermission(userRole, 'canManageLeases');

    const [formData, setFormData] = useState({
        unitId: "",
        renterId: "",
        startDate: "",
        endDate: "",
        rentAmount: 0,
        depositAmount: 0,
        ejariNumber: "",
        paymentTerms: 1
    });

    useEffect(() => {
        fetchLeases();
        fetchUnits();
        fetchRenters();
    }, []);

    useEffect(() => {
        if (leases.length > 0) {
            fetchPaymentStats(leases);
        }
    }, [leases]);

    const fetchLeases = async () => {
        try {
            const res = await fetch("/api/proxy/v1/leases");
            if (res.ok) setLeases(await res.json());
        } catch (err) {
            console.error(err);
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

    const handleSubmit = async (ev: React.FormEvent) => {
        ev.preventDefault();
        try {
            const res = await fetch("/api/proxy/v1/leases", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(formData)
            });
            if (res.ok) {
                setShowForm(false);
                fetchLeases();
                fetchUnits();
                setFormData({
                    unitId: "", renterId: "", startDate: "", endDate: "",
                    rentAmount: 0, depositAmount: 0, ejariNumber: "", paymentTerms: 1
                });
            }
        } catch (err) {
            console.error(err);
        }
    };

    const handleActivate = async (id: string) => {
        if (!confirm(t("confirmActivate"))) return;
        try {
            const res = await fetch(`/api/proxy/v1/leases/${id}/activate`, { method: "PUT" });
            if (res.ok) fetchLeases();
        } catch (err) {
            console.error(err);
        }
    };

    const handleTerminate = async (id: string) => {
        if (!confirm(t("confirmTerminate"))) return;
        try {
            const res = await fetch(`/api/proxy/v1/leases/${id}/terminate?notes=Early Termination UI`, { method: "POST" });
            if (res.ok) fetchLeases();
        } catch (err) {
            console.error(err);
        }
    };

    const handleGenerateContract = async (id: string) => {
        try {
            const res = await fetch(`/api/proxy/v1/leases/${id}/generate-contract`, { method: "POST" });
            if (res.ok) fetchLeases();
        } catch (err) {
            console.error(err);
        }
    };

    const handleDownloadContract = async (id: string) => {
        try {
            const res = await fetch(`/api/proxy/v1/leases/${id}/documents`);
            if (res.ok) {
                const docs = await res.json();
                if (docs.length > 0) {
                    window.open(`/api/proxy/v1/leases/documents/${docs[0].id}/download`, '_blank');
                }
            }
        } catch (err) {
            console.error(err);
        }
    };

    const getRenterDisplayName = (r: Renter) => {
        if (locale === 'ar' && r.nameAr) return r.nameAr;
        return r.nameEn;
    };

    const getStatusColor = (status: string) => {
        switch (status) {
            case 'ACTIVE': return 'bg-green-100 text-green-700 border-green-200';
            case 'DRAFT': return 'bg-gray-100 text-gray-700 border-gray-200';
            case 'PENDING_SIGNATURE': return 'bg-yellow-100 text-yellow-700 border-yellow-200';
            case 'NOTICE_GIVEN': return 'bg-orange-100 text-orange-700 border-orange-200';
            case 'TERMINATED': return 'bg-red-100 text-red-700 border-red-200';
            case 'EXPIRED': return 'bg-orange-100 text-orange-700 border-orange-200';
            case 'CLOSED': return 'bg-gray-100 text-gray-500 border-gray-200';
            default: return 'bg-blue-100 text-blue-700 border-blue-200';
        }
    };

    const renderPaymentProgress = (leaseId: string) => {
        const stats = paymentStatsMap[leaseId];

        if (paymentStatsLoading && !stats) {
            return (
                <div className="mt-3 pt-3 border-t border-gray-100">
                    <div className="h-1.5 bg-gray-100 rounded-full overflow-hidden animate-pulse" />
                    <div className="mt-1.5 h-3 w-24 bg-gray-100 rounded animate-pulse" />
                </div>
            );
        }

        if (!stats) return null;

        const progressPercent = stats.totalPayments > 0
            ? Math.round((stats.clearedPayments / stats.totalPayments) * 100)
            : 0;

        return (
            <div className="mt-3 pt-3 border-t border-gray-100">
                <div className="h-1.5 bg-gray-100 rounded-full overflow-hidden">
                    <div
                        className="h-full bg-green-500 rounded-full transition-all duration-500"
                        style={{ width: `${progressPercent}%` }}
                    />
                </div>
                <div className="flex items-center gap-2 mt-1.5">
                    <span className="text-xs font-medium text-gray-500">
                        {stats.clearedPayments}/{stats.totalPayments} {t("paymentsProgress")}
                    </span>
                    {stats.overduePayments > 0 && (
                        <span className="inline-flex items-center px-1.5 py-0.5 rounded-full text-[9px] font-bold bg-red-50 text-red-600 border border-red-100">
                            {stats.overduePayments} {t("overduePayments")}
                        </span>
                    )}
                </div>
            </div>
        );
    };

    const renderLeaseCard = (lease: Lease, compact = false) => (
        <div key={lease.id} className={cn("bg-white rounded-[2rem] p-6 shadow-sm border border-gray-100 hover:shadow-md transition-all flex flex-col justify-between", compact && "rounded-2xl p-4")}>
            <div>
                <div className="flex justify-between items-start mb-4">
                    <div className="flex items-center gap-3">
                        <div className={cn("w-10 h-10 bg-primary/10 rounded-xl flex items-center justify-center text-primary border border-primary/20", compact && "w-8 h-8 rounded-lg")}>
                            <FileText size={compact ? 14 : 18} />
                        </div>
                        <div>
                            <h3 className={cn("font-black text-foreground tracking-tight", compact ? "text-xs" : "text-sm")}>{t("unit")} {lease.unitIdentifier}</h3>
                            <p className="text-[10px] font-bold text-gray-400">{lease.renterName}</p>
                        </div>
                    </div>
                    {!compact && (
                        <span className={cn("inline-flex items-center px-2.5 py-1 rounded-full text-[9px] font-bold uppercase tracking-widest border", getStatusColor(lease.status))}>
                            {lease.status.replace('_', ' ')}
                        </span>
                    )}
                </div>

                <div className={cn("space-y-3", compact ? "mb-3" : "mb-6")}>
                    <div className={cn("flex justify-between items-center bg-gray-50 rounded-xl border border-gray-100/50", compact ? "p-2" : "p-3")}>
                        <div className="flex items-center gap-2">
                            <DollarSign size={14} className="text-gray-400" />
                            <span className="text-[10px] font-bold text-gray-400 uppercase">{t("rentSummary")}</span>
                        </div>
                        <span className={cn("font-black text-foreground text-right", compact ? "text-[10px]" : "text-xs")}>
                            AED {lease.rentAmount.toLocaleString()}
                            {!compact && <><br /><span className="text-[9px] text-gray-400 font-medium">({lease.paymentTerms} {t("cheques")})</span></>}
                        </span>
                    </div>
                    <div className="flex items-center gap-3 px-1">
                        <Calendar size={14} className="text-gray-400" />
                        <span className="text-xs font-medium text-gray-600">
                            {new Date(lease.startDate).toLocaleDateString()} &rarr; {new Date(lease.endDate).toLocaleDateString()}
                        </span>
                    </div>
                    {!compact && lease.ejariNumber && (
                        <div className="flex items-center gap-3 px-1">
                            <Home size={14} className="text-gray-400" />
                            <span className="text-xs font-medium text-gray-600">Ejari: {lease.ejariNumber}</span>
                        </div>
                    )}
                </div>

                {!compact && (lease.status === 'ACTIVE' || lease.status === 'NOTICE_GIVEN') && renderPaymentProgress(lease.id)}
            </div>

            {canManageLeases && (
                <div className={cn("flex gap-2 border-t border-gray-100 mt-auto", compact ? "pt-3 flex-wrap" : "pt-4")}>
                    {lease.status === 'DRAFT' && !lease.hasContract && (
                        <button
                            onClick={() => handleGenerateContract(lease.id)}
                            className="flex-1 flex items-center justify-center gap-2 bg-blue-50 text-blue-700 hover:bg-blue-100 py-2.5 rounded-xl text-xs font-bold transition-colors"
                        >
                            <Sparkles size={14} />
                            {t("generateContract")}
                        </button>
                    )}
                    {lease.status === 'DRAFT' && lease.hasContract && (
                        <button
                            onClick={() => handleActivate(lease.id)}
                            className="flex-1 flex items-center justify-center gap-2 bg-green-50 text-green-700 hover:bg-green-100 py-2.5 rounded-xl text-xs font-bold transition-colors"
                        >
                            <CheckCircle size={14} />
                            {t("activate")}
                        </button>
                    )}
                    {lease.status === 'PENDING_SIGNATURE' && (
                        <>
                            <button
                                onClick={() => handleDownloadContract(lease.id)}
                                className="flex-1 flex items-center justify-center gap-2 bg-blue-50 text-blue-700 hover:bg-blue-100 py-2.5 rounded-xl text-xs font-bold transition-colors"
                            >
                                <Download size={14} />
                                {t("downloadContract")}
                            </button>
                            <button
                                onClick={() => handleActivate(lease.id)}
                                className="flex-1 flex items-center justify-center gap-2 bg-green-50 text-green-700 hover:bg-green-100 py-2.5 rounded-xl text-xs font-bold transition-colors"
                            >
                                <CheckCircle size={14} />
                                {t("activate")}
                            </button>
                        </>
                    )}
                    {lease.status === 'ACTIVE' && (
                        <button
                            onClick={() => handleTerminate(lease.id)}
                            className="flex-1 flex items-center justify-center gap-2 bg-red-50 text-red-600 hover:bg-red-100 py-2.5 rounded-xl text-xs font-bold transition-colors"
                        >
                            <Ban size={14} />
                            {t("terminate")}
                        </button>
                    )}
                </div>
            )}
        </div>
    );

    return (
        <div>
            <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 mb-10">
                <div>
                    <h1 className="text-xl font-black text-foreground tracking-tight mb-1">
                        {t("leases")}
                    </h1>
                    <p className="text-xs text-gray-500 font-medium">
                        {t("manageLeases")}
                    </p>
                </div>
                <div className="flex items-center gap-3">
                    <div className="flex items-center bg-gray-100 rounded-xl p-1">
                        <button
                            onClick={() => setViewMode('grid')}
                            className={cn(
                                "flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[10px] font-bold transition-all",
                                viewMode === 'grid' ? "bg-white text-foreground shadow-sm" : "text-gray-400 hover:text-gray-600"
                            )}
                        >
                            <LayoutGrid size={12} />
                            {t("gridView")}
                        </button>
                        <button
                            onClick={() => setViewMode('board')}
                            className={cn(
                                "flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[10px] font-bold transition-all",
                                viewMode === 'board' ? "bg-white text-foreground shadow-sm" : "text-gray-400 hover:text-gray-600"
                            )}
                        >
                            <Columns3 size={12} />
                            {t("boardView")}
                        </button>
                    </div>
                    {canManageLeases && (
                        <button
                            onClick={() => setShowForm(true)}
                            className="flex items-center gap-2 bg-primary text-primary-foreground px-5 py-2.5 rounded-full text-xs font-bold hover:opacity-90 transition-all shadow-lg shadow-primary/10 active:scale-95"
                        >
                            <Plus size={14} />
                            {t("draftLease")}
                        </button>
                    )}
                </div>
            </div>

            {showForm && (
                <div className="fixed inset-0 bg-gray-900/40 backdrop-blur-sm flex items-center justify-center p-4 z-[100] overflow-y-auto">
                    <div className="bg-white rounded-3xl p-8 max-w-2xl w-full shadow-2xl border border-gray-100 relative my-8">
                        <button onClick={() => setShowForm(false)} className="absolute right-6 top-6 p-2 text-gray-400 hover:text-gray-600"><X size={18} /></button>
                        <h2 className="text-lg font-black mb-1">{t("draftNewLease")}</h2>
                        <p className="text-xs text-gray-400 mb-8 font-medium">{t("draftNewLeaseDesc")}</p>
                        <form onSubmit={handleSubmit} className="grid grid-cols-2 gap-5">
                            <div className="col-span-1">
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">{t("selectUnit")}</label>
                                <select required className="w-full bg-input border border-border p-3 rounded-xl text-xs" value={formData.unitId} onChange={ev => setFormData({ ...formData, unitId: ev.target.value })}>
                                    <option value="">{t("chooseVacantUnit")}</option>
                                    {units.map(u => <option key={u.id} value={u.id}>{t("unit")} {u.unitNumber}</option>)}
                                </select>
                            </div>
                            <div className="col-span-1">
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">{t("selectRenter")}</label>
                                <select required className="w-full bg-input border border-border p-3 rounded-xl text-xs" value={formData.renterId} onChange={ev => setFormData({ ...formData, renterId: ev.target.value })}>
                                    <option value="">{t("chooseRenter")}</option>
                                    {renters.map(r => <option key={r.id} value={r.id}>{getRenterDisplayName(r)}</option>)}
                                </select>
                            </div>
                            <div className="col-span-1">
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">{t("startDate")}</label>
                                <input required type="date" className="w-full bg-input border border-border p-3 rounded-xl text-xs" value={formData.startDate} onChange={ev => setFormData({ ...formData, startDate: ev.target.value })} />
                            </div>
                            <div className="col-span-1">
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">{t("endDate")}</label>
                                <input required type="date" className="w-full bg-input border border-border p-3 rounded-xl text-xs" value={formData.endDate} onChange={ev => setFormData({ ...formData, endDate: ev.target.value })} />
                            </div>
                            <div className="col-span-1">
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">{t("rentAmount")}</label>
                                <input required type="number" min="0" placeholder="50000" className="w-full bg-input border border-border p-3 rounded-xl text-xs" value={formData.rentAmount || ''} onChange={ev => setFormData({ ...formData, rentAmount: Number(ev.target.value) })} />
                            </div>
                            <div className="col-span-1">
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">{t("securityDeposit")}</label>
                                <input required type="number" min="0" placeholder="2500" className="w-full bg-input border border-border p-3 rounded-xl text-xs" value={formData.depositAmount || ''} onChange={ev => setFormData({ ...formData, depositAmount: Number(ev.target.value) })} />
                            </div>
                            <div className="col-span-1">
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">{t("ejariNumber")}</label>
                                <input placeholder="EJAR-12345" className="w-full bg-input border border-border p-3 rounded-xl text-xs" value={formData.ejariNumber} onChange={ev => setFormData({ ...formData, ejariNumber: ev.target.value })} />
                            </div>
                            <div className="col-span-1">
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">{t("paymentTerms")}</label>
                                <select className="w-full bg-input border border-border p-3 rounded-xl text-xs" value={formData.paymentTerms} onChange={ev => setFormData({ ...formData, paymentTerms: Number(ev.target.value) })}>
                                    <option value={1}>1 Cheque</option>
                                    <option value={2}>2 Cheques</option>
                                    <option value={4}>4 Cheques</option>
                                    <option value={6}>6 Cheques</option>
                                    <option value={12}>12 Cheques</option>
                                </select>
                            </div>
                            <div className="col-span-2 flex justify-end gap-3 mt-4">
                                <button type="button" onClick={() => setShowForm(false)} className="px-6 py-3 text-xs font-bold text-gray-500">{t("cancel")}</button>
                                <button type="submit" className="px-8 py-3 bg-primary text-primary-foreground rounded-xl text-xs font-bold">{t("draftLease")}</button>
                            </div>
                        </form>
                    </div>
                </div>
            )}

            {/* Grid View */}
            {viewMode === 'grid' && (
                <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-6">
                    {leases.map(lease => renderLeaseCard(lease))}
                </div>
            )}

            {/* Board View */}
            {viewMode === 'board' && (
                <div className="grid grid-cols-2 lg:grid-cols-4 gap-4 pb-4">
                    {BOARD_COLUMNS.map(col => {
                        const columnLeases = leases.filter(l => col.statuses.includes(l.status));
                        return (
                            <div key={col.key} className="min-w-0">
                                <div className="flex items-center gap-2 mb-4 px-2">
                                    <div className={cn("w-2.5 h-2.5 rounded-full", col.color)} />
                                    <h3 className="text-xs font-black text-foreground uppercase tracking-widest">{col.label}</h3>
                                    <span className="ml-auto text-[10px] font-bold text-gray-400 bg-gray-100 px-2 py-0.5 rounded-full">{columnLeases.length}</span>
                                </div>
                                <div className="space-y-3 min-h-[200px] bg-gray-50/50 rounded-2xl p-3 border border-gray-100/50">
                                    {columnLeases.map(lease => renderLeaseCard(lease, true))}
                                    {columnLeases.length === 0 && (
                                        <div className="text-center py-8 text-[10px] text-gray-300 font-bold uppercase tracking-widest">
                                            No leases
                                        </div>
                                    )}
                                </div>
                            </div>
                        );
                    })}
                </div>
            )}

            {leases.length === 0 && !showForm && (
                <div className="text-center py-24 bg-gray-50 border border-dashed border-gray-200 rounded-[2.5rem] flex flex-col items-center">
                    <div className="w-16 h-16 bg-white rounded-2xl flex items-center justify-center text-gray-200 shadow-sm mb-6">
                        <AlertCircle size={32} />
                    </div>
                    <p className="text-sm font-bold text-gray-400 mb-6 uppercase tracking-widest">
                        {t("noLeasesFound")}
                    </p>
                    {canManageLeases && (
                        <button onClick={() => setShowForm(true)} className="text-xs font-black text-foreground border-b-2 border-primary pb-0.5 hover:text-primary transition-all">
                            {t("draftALease")}
                        </button>
                    )}
                </div>
            )}
        </div>
    );
}
