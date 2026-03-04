"use client";

import { useState, useEffect } from "react";
import { useTranslations } from "next-intl";
import { FileText, Calendar, DollarSign, Home, CheckCircle, XCircle, Download, Clock, AlertCircle, CreditCard } from "lucide-react";
import { useSession } from "next-auth/react";
import { cn } from "@/lib/utils";
import { Link } from "@/i18n/routing";

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
    propertyName: string;
    hasContract: boolean;
};

export default function RenterPortalPage() {
    const t = useTranslations("MasterData");
    const tPayments = useTranslations("OnlinePayments");
    const [leases, setLeases] = useState<Lease[]>([]);
    const [loading, setLoading] = useState(true);
    const [pendingPaymentsCount, setPendingPaymentsCount] = useState(0);
    const { data: session } = useSession();

    useEffect(() => {
        fetchMyLeases();
        fetchPendingPayments();
    }, []);

    const fetchMyLeases = async () => {
        try {
            const res = await fetch("/api/proxy/v1/leases/my-leases");
            if (res.ok) setLeases(await res.json());
        } catch (err) {
            console.error(err);
        } finally {
            setLoading(false);
        }
    };

    const fetchPendingPayments = async () => {
        try {
            const res = await fetch("/api/proxy/v1/online-payments/my-payments");
            if (res.ok) {
                const payments = await res.json();
                const pending = payments.filter(
                    (p: any) => p.status === "PENDING" || p.status === "ONLINE_PENDING"
                );
                setPendingPaymentsCount(pending.length);
            }
        } catch (err) {
            console.error(err);
        }
    };

    const handleAccept = async (id: string) => {
        if (!confirm(t("confirmAccept"))) return;
        try {
            const res = await fetch(`/api/proxy/v1/leases/${id}/accept`, { method: "PUT" });
            if (res.ok) fetchMyLeases();
        } catch (err) {
            console.error(err);
        }
    };

    const handleReject = async (id: string) => {
        if (!confirm(t("confirmReject"))) return;
        try {
            const res = await fetch(`/api/proxy/v1/leases/${id}/reject`, { method: "PUT" });
            if (res.ok) fetchMyLeases();
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

    const getStatusColor = (status: string) => {
        switch (status) {
            case 'ACTIVE': return 'bg-green-100 text-green-700 border-green-200';
            case 'DRAFT': return 'bg-gray-100 text-gray-700 border-gray-200';
            case 'PENDING_SIGNATURE': return 'bg-yellow-100 text-yellow-700 border-yellow-200';
            case 'TERMINATED': return 'bg-red-100 text-red-700 border-red-200';
            case 'EXPIRED': return 'bg-orange-100 text-orange-700 border-orange-200';
            default: return 'bg-blue-100 text-blue-700 border-blue-200';
        }
    };

    const userName = (session?.user as any)?.name || "Renter";

    if (loading) return <div className="p-8">Loading...</div>;

    return (
        <div className="p-8 max-w-5xl mx-auto">
            <div className="mb-10">
                <h1 className="text-xl font-black text-foreground tracking-tight mb-1">
                    {t("welcomeRenter")}, {userName}
                </h1>
                <p className="text-xs text-gray-500 font-medium">
                    {t("renterPortalDesc")}
                </p>
            </div>

            {/* My Payments Quick Link Card */}
            <Link href="/dashboard/renter-portal/payments">
                <div className="bg-white rounded-[2rem] p-6 shadow-sm border border-gray-100 hover:shadow-md transition-all mb-6 cursor-pointer">
                    <div className="flex items-center justify-between">
                        <div className="flex items-center gap-4">
                            <div className="w-12 h-12 bg-blue-50 rounded-2xl flex items-center justify-center text-blue-600 border border-blue-100">
                                <CreditCard size={22} />
                            </div>
                            <div>
                                <h3 className="text-sm font-black text-foreground tracking-tight">
                                    {tPayments("myPayments")}
                                </h3>
                                <p className="text-[10px] font-bold text-gray-400">
                                    {tPayments("description")}
                                </p>
                            </div>
                        </div>
                        {pendingPaymentsCount > 0 && (
                            <span className="inline-flex items-center px-3 py-1.5 rounded-full text-[9px] font-bold uppercase tracking-widest border bg-red-50 text-red-600 border-red-200">
                                {pendingPaymentsCount} {tPayments("paymentsDue")}
                            </span>
                        )}
                    </div>
                </div>
            </Link>

            <div className="space-y-6">
                {leases.map(lease => (
                    <div key={lease.id} className="bg-white rounded-[2rem] p-6 shadow-sm border border-gray-100 hover:shadow-md transition-all">
                        <div className="flex justify-between items-start mb-6">
                            <div className="flex items-center gap-4">
                                <div className="w-12 h-12 bg-primary/10 rounded-2xl flex items-center justify-center text-primary border border-primary/20">
                                    <FileText size={22} />
                                </div>
                                <div>
                                    <h3 className="text-sm font-black text-foreground tracking-tight">
                                        {t("unit")} {lease.unitIdentifier}
                                    </h3>
                                    <p className="text-[10px] font-bold text-gray-400">{lease.propertyName}</p>
                                </div>
                            </div>
                            <span className={cn("inline-flex items-center px-3 py-1.5 rounded-full text-[9px] font-bold uppercase tracking-widest border", getStatusColor(lease.status))}>
                                {lease.status.replace('_', ' ')}
                            </span>
                        </div>

                        <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-6">
                            <div className="bg-gray-50 rounded-xl p-3 border border-gray-100/50">
                                <div className="flex items-center gap-2 mb-1">
                                    <DollarSign size={12} className="text-gray-400" />
                                    <span className="text-[9px] font-bold text-gray-400 uppercase">Rent</span>
                                </div>
                                <p className="text-sm font-black text-foreground">AED {lease.rentAmount.toLocaleString()}</p>
                            </div>
                            <div className="bg-gray-50 rounded-xl p-3 border border-gray-100/50">
                                <div className="flex items-center gap-2 mb-1">
                                    <Calendar size={12} className="text-gray-400" />
                                    <span className="text-[9px] font-bold text-gray-400 uppercase">Start</span>
                                </div>
                                <p className="text-xs font-bold text-foreground">{new Date(lease.startDate).toLocaleDateString()}</p>
                            </div>
                            <div className="bg-gray-50 rounded-xl p-3 border border-gray-100/50">
                                <div className="flex items-center gap-2 mb-1">
                                    <Calendar size={12} className="text-gray-400" />
                                    <span className="text-[9px] font-bold text-gray-400 uppercase">End</span>
                                </div>
                                <p className="text-xs font-bold text-foreground">{new Date(lease.endDate).toLocaleDateString()}</p>
                            </div>
                            {lease.ejariNumber && (
                                <div className="bg-gray-50 rounded-xl p-3 border border-gray-100/50">
                                    <div className="flex items-center gap-2 mb-1">
                                        <Home size={12} className="text-gray-400" />
                                        <span className="text-[9px] font-bold text-gray-400 uppercase">Ejari</span>
                                    </div>
                                    <p className="text-xs font-bold text-foreground">{lease.ejariNumber}</p>
                                </div>
                            )}
                        </div>

                        <div className="flex gap-3 border-t border-gray-100 pt-4">
                            {lease.status === 'DRAFT' && (
                                <div className="flex items-center gap-2 text-xs text-gray-400 font-medium">
                                    <Clock size={14} />
                                    {t("awaitingContract")}
                                </div>
                            )}
                            {lease.status === 'PENDING_SIGNATURE' && (
                                <>
                                    <button
                                        onClick={() => handleDownloadContract(lease.id)}
                                        className="flex items-center gap-2 bg-blue-50 text-blue-700 hover:bg-blue-100 px-4 py-2.5 rounded-xl text-xs font-bold transition-colors"
                                    >
                                        <Download size={14} />
                                        {t("downloadContract")}
                                    </button>
                                    <button
                                        onClick={() => handleAccept(lease.id)}
                                        className="flex items-center gap-2 bg-green-50 text-green-700 hover:bg-green-100 px-4 py-2.5 rounded-xl text-xs font-bold transition-colors"
                                    >
                                        <CheckCircle size={14} />
                                        {t("acceptLease")}
                                    </button>
                                    <button
                                        onClick={() => handleReject(lease.id)}
                                        className="flex items-center gap-2 bg-red-50 text-red-600 hover:bg-red-100 px-4 py-2.5 rounded-xl text-xs font-bold transition-colors"
                                    >
                                        <XCircle size={14} />
                                        {t("rejectLease")}
                                    </button>
                                </>
                            )}
                            {lease.status === 'ACTIVE' && lease.hasContract && (
                                <button
                                    onClick={() => handleDownloadContract(lease.id)}
                                    className="flex items-center gap-2 bg-blue-50 text-blue-700 hover:bg-blue-100 px-4 py-2.5 rounded-xl text-xs font-bold transition-colors"
                                >
                                    <Download size={14} />
                                    {t("downloadContract")}
                                </button>
                            )}
                        </div>
                    </div>
                ))}
            </div>

            {leases.length === 0 && (
                <div className="text-center py-24 bg-gray-50 border border-dashed border-gray-200 rounded-[2.5rem] flex flex-col items-center">
                    <div className="w-16 h-16 bg-white rounded-2xl flex items-center justify-center text-gray-200 shadow-sm mb-6">
                        <AlertCircle size={32} />
                    </div>
                    <p className="text-sm font-bold text-gray-400 mb-2 uppercase tracking-widest">
                        {t("noLeasesFound")}
                    </p>
                    <p className="text-xs text-gray-400">
                        Your leases will appear here once your landlord creates them.
                    </p>
                </div>
            )}
        </div>
    );
}
