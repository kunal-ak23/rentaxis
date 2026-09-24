"use client";

import { useState, useEffect } from "react";
import { useTranslations, useLocale } from "next-intl";
import { accountName } from "@/lib/api/ledger";
import { Users, Plus, Pencil, Trash2, X, Loader2, Package, Search, AlertCircle, BookOpen } from "lucide-react";
import { Link } from "@/i18n/routing";
import { Pagination } from "@/components/ui/Pagination";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { ApiError, throwIfNotOk } from "@/lib/api/facilities";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";

type Account = {
    id: string;
    code: string;
    name: string;
    nameAr?: string | null;
    accountType: string;
};

type Vendor = {
    id: string;
    nameEn: string;
    nameAr: string;
    tradeLicenseNumber: string;
    trn: string;
    email: string;
    phone: string;
    contactPerson: string;
    address: string;
    bankName: string;
    bankAccountNumber: string;
    iban: string;
    payableAccount: Account | null;
    notes: string;
    active: boolean;
};

const emptyForm = {
    nameEn: "",
    nameAr: "",
    tradeLicenseNumber: "",
    trn: "",
    email: "",
    phone: "",
    contactPerson: "",
    address: "",
    bankName: "",
    bankAccountNumber: "",
    iban: "",
    notes: "",
    active: true,
};

export default function VendorsPage() {
    const t = useTranslations("Vendors");
    const tCommon = useTranslations("Common");
    const locale = useLocale();
    const [vendors, setVendors] = useState<Vendor[]>([]);
    const [loading, setLoading] = useState(true);
    const [loadError, setLoadError] = useState<string | null>(null);
    const [showModal, setShowModal] = useState(false);
    const [editingVendor, setEditingVendor] = useState<Vendor | null>(null);
    const [submitting, setSubmitting] = useState(false);
    const [formData, setFormData] = useState(emptyForm);
    const [searchQuery, setSearchQuery] = useState("");
    const [currentPage, setCurrentPage] = useState(1);
    const [itemsPerPage, setItemsPerPage] = useState(25);
    const [confirmDialog, setConfirmDialog] = useState<{
        title: string;
        description: string;
        confirmText: string;
        isDestructive: boolean;
        onConfirm: () => void;
    } | null>(null);
    const [formError, setFormError] = useState<string | null>(null);
    const [errorBanner, setErrorBanner] = useState<string | null>(null);

    useEffect(() => {
        fetchVendors();
    }, []);

    const fetchVendors = async () => {
        try {
            const res = await fetch("/api/proxy/v1/vendors");
            if (res.ok) {
                const data = await res.json();
                data.sort((a: any, b: any) => (a.id || '').localeCompare(b.id || ''));
                setVendors(data);
            } else {
                // A non-2xx used to leave the state at its initial empty
                // value, so a failed request rendered as "nothing here".
                setLoadError(tCommon("loadFailedVendors"));
            }
        } catch (err) {
            console.error(err);
        } finally {
            setLoading(false);
        }
    };

    const openAddModal = () => {
        setEditingVendor(null);
        setFormData(emptyForm);
        setFormError(null);
        setShowModal(true);
    };

    const openEditModal = (vendor: Vendor) => {
        setEditingVendor(vendor);
        setFormData({
            nameEn: vendor.nameEn || "",
            nameAr: vendor.nameAr || "",
            tradeLicenseNumber: vendor.tradeLicenseNumber || "",
            trn: vendor.trn || "",
            email: vendor.email || "",
            phone: vendor.phone || "",
            contactPerson: vendor.contactPerson || "",
            address: vendor.address || "",
            bankName: vendor.bankName || "",
            bankAccountNumber: vendor.bankAccountNumber || "",
            iban: vendor.iban || "",
            notes: vendor.notes || "",
            active: vendor.active,
        });
        setFormError(null);
        setShowModal(true);
    };

    const handleSubmit = async (ev: React.FormEvent) => {
        ev.preventDefault();
        setSubmitting(true);
        setFormError(null);
        try {
            const body: Record<string, unknown> = {
                nameEn: formData.nameEn,
                nameAr: formData.nameAr,
                tradeLicenseNumber: formData.tradeLicenseNumber,
                trn: formData.trn,
                email: formData.email,
                phone: formData.phone,
                contactPerson: formData.contactPerson,
                address: formData.address,
                bankName: formData.bankName,
                bankAccountNumber: formData.bankAccountNumber,
                iban: formData.iban,
                notes: formData.notes,
                active: formData.active,
            };

            const url = editingVendor
                ? `/api/proxy/v1/vendors/${editingVendor.id}`
                : "/api/proxy/v1/vendors";
            const method = editingVendor ? "PUT" : "POST";

            const res = await fetch(url, {
                method,
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(body),
            });

            await throwIfNotOk(res);
            setShowModal(false);
            setEditingVendor(null);
            setFormData(emptyForm);
            fetchVendors();
        } catch (err) {
            console.error(err);
            setFormError(err instanceof ApiError ? err.message : t("saveFailed"));
        } finally {
            setSubmitting(false);
        }
    };

    const showErrorBanner = (message: string) => {
        setErrorBanner(message);
        setTimeout(() => setErrorBanner(null), 5000);
    };

    const handleDelete = (vendor: Vendor) => {
        setConfirmDialog({
            title: t("deleteVendor"),
            description: t("confirmDelete"),
            confirmText: t("delete"),
            isDestructive: true,
            onConfirm: async () => {
                setConfirmDialog(null);
                try {
                    const res = await fetch(`/api/proxy/v1/vendors/${vendor.id}`, {
                        method: "DELETE",
                    });
                    await throwIfNotOk(res);
                    fetchVendors();
                } catch (err) {
                    console.error(err);
                    showErrorBanner(err instanceof ApiError ? err.message : t("deleteFailed"));
                }
            },
        });
    };

    const vendorName = (v: Vendor) =>
        locale === "ar" ? v.nameAr || v.nameEn : v.nameEn || v.nameAr;

    const filteredVendors = searchQuery
        ? vendors.filter((v) => {
            const q = searchQuery.toLowerCase();
            return (
                (v.nameEn || "").toLowerCase().includes(q) ||
                (v.nameAr || "").toLowerCase().includes(q) ||
                (v.contactPerson || "").toLowerCase().includes(q) ||
                (v.email || "").toLowerCase().includes(q) ||
                (v.phone || "").toLowerCase().includes(q) ||
                (v.trn || "").toLowerCase().includes(q)
            );
        })
        : vendors;

    const paginatedVendors = filteredVendors.slice((currentPage - 1) * itemsPerPage, currentPage * itemsPerPage);

    const reload = () => {
        setLoadError(null);
        fetchVendors();
    };

    return (
        <div>
            {loadError && <LoadErrorBanner message={loadError} onRetry={reload} />}
            {/* Header */}
            <div className="flex items-center justify-between mb-8">
                <div>
                    <h1 className="mb-1">{t("title")}</h1>
                    <p className="text-sm text-muted">{t("description")}</p>
                </div>
                <div className="flex items-center gap-3">
                    <div className="relative">
                        <Search size={14} className="absolute start-3 top-1/2 -translate-y-1/2 text-muted" />
                        <input
                            type="text"
                            placeholder={t("search")}
                            value={searchQuery}
                            onChange={(e) => { setSearchQuery(e.target.value); setCurrentPage(1); }}
                            className="ps-9 pe-4 py-2 bg-surface border border-border rounded-lg text-sm text-foreground placeholder:text-muted/50 focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none w-64 transition-all"
                        />
                    </div>
                    <button
                        onClick={openAddModal}
                        className="bg-primary text-primary-foreground px-4 py-2 rounded-lg text-xs font-semibold hover:bg-primary/90 transition-all cursor-pointer flex items-center gap-2"
                    >
                        <Plus size={14} />
                        {t("addVendor")}
                    </button>
                </div>
            </div>

            {/* Loading Skeleton */}
            {loading && (
                <div className="space-y-3 animate-pulse">
                    {[1, 2, 3, 4].map((i) => (
                        <div key={i} className="bg-input rounded-xl h-16" />
                    ))}
                </div>
            )}

            {/* Empty State */}
            {!loading && filteredVendors.length === 0 && (
                <div className="text-center py-24 bg-background border border-dashed border-border rounded-xl flex flex-col items-center">
                    <div className="w-16 h-16 bg-surface rounded-xl flex items-center justify-center text-muted shadow-sm mb-6">
                        <Package size={28} />
                    </div>
                    <h3 className="text-sm font-bold text-foreground mb-1">
                        {t("noVendorsFound")}
                    </h3>
                    <p className="text-xs text-muted font-medium">
                        {t("createVendorProfile")}
                    </p>
                </div>
            )}

            {/* Vendors Table */}
            {!loading && filteredVendors.length > 0 && (
                <div className="bg-surface border border-border rounded-xl overflow-hidden shadow-sm">
                    <div className="overflow-x-auto">
                        <table className="w-full">
                            <thead>
                                <tr className="bg-input/50">
                                    <th className="text-start px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">
                                        {locale === "ar" ? t("nameAr") : t("nameEn")}
                                    </th>
                                    <th className="text-start px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">
                                        {t("trn")}
                                    </th>
                                    <th className="text-start px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">
                                        {t("contactPerson")}
                                    </th>
                                    <th className="text-start px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">
                                        {t("phone")}
                                    </th>
                                    <th className="text-start px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">
                                        {t("payableAccount")}
                                    </th>
                                    <th className="text-start px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">
                                        {t("status")}
                                    </th>
                                    <th className="text-start px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">
                                        {t("actions")}
                                    </th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-border">
                                {paginatedVendors.map((vendor) => (
                                    <tr
                                        key={vendor.id}
                                        className="hover:bg-input/30 transition-colors"
                                    >
                                        <td className="px-5 py-3 text-xs text-foreground font-medium">
                                            {vendorName(vendor)}
                                        </td>
                                        <td className="px-5 py-3 text-xs text-foreground font-medium">
                                            {vendor.trn || "\u2014"}
                                        </td>
                                        <td className="px-5 py-3 text-xs text-foreground font-medium">
                                            {vendor.contactPerson || "\u2014"}
                                        </td>
                                        <td className="px-5 py-3 text-xs text-foreground font-medium">
                                            {vendor.phone || "\u2014"}
                                        </td>
                                        <td className="px-5 py-3 text-xs text-foreground font-medium">
                                            {vendor.payableAccount
                                                ? `${vendor.payableAccount.code} - ${accountName(vendor.payableAccount, locale)}`
                                                : "\u2014"}
                                        </td>
                                        <td className="px-5 py-3">
                                            {vendor.active ? (
                                                <span className="bg-success/10 text-success border border-success/20 px-2.5 py-1 rounded-lg text-[10px] font-bold">
                                                    {t("active")}
                                                </span>
                                            ) : (
                                                <span className="bg-input text-muted border border-border px-2.5 py-1 rounded-lg text-[10px] font-bold">
                                                    {t("inactive")}
                                                </span>
                                            )}
                                        </td>
                                        <td className="px-5 py-3">
                                            <div className="flex items-center gap-2">
                                                <Link
                                                    href={`/dashboard/finance/general-ledger?vendorId=${vendor.id}`}
                                                    className="p-1.5 text-muted hover:text-primary rounded-lg hover:bg-primary/5 transition-all cursor-pointer"
                                                    aria-label={t("viewLedger")}
                                                    title={t("viewLedger")}
                                                >
                                                    <BookOpen size={14} />
                                                </Link>
                                                <button
                                                    onClick={() => openEditModal(vendor)}
                                                    className="p-1.5 text-muted hover:text-primary rounded-lg hover:bg-primary/5 transition-all cursor-pointer"
                                                    aria-label={t("editVendor")}
                                                >
                                                    <Pencil size={14} />
                                                </button>
                                                <button
                                                    onClick={() => handleDelete(vendor)}
                                                    className="p-1.5 text-muted hover:text-error rounded-lg hover:bg-error/10 transition-all cursor-pointer"
                                                    aria-label={t("deleteVendor")}
                                                >
                                                    <Trash2 size={14} />
                                                </button>
                                            </div>
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                    <div className="px-5 pb-4">
                        <Pagination
                            currentPage={currentPage}
                            totalItems={filteredVendors.length}
                            itemsPerPage={itemsPerPage}
                            onPageChange={setCurrentPage}
                            onItemsPerPageChange={(n) => { setItemsPerPage(n); setCurrentPage(1); }}
                        />
                    </div>
                </div>
            )}

            {/* Add/Edit Modal */}
            {showModal && (
                <div className="fixed inset-0 bg-foreground/40 backdrop-blur-sm z-50 flex items-start justify-center pt-20">
                    <div className="bg-surface rounded-xl p-8 w-full max-w-2xl shadow-2xl border border-border relative max-h-[80vh] overflow-y-auto">
                        <button
                            onClick={() => {
                                setShowModal(false);
                                setEditingVendor(null);
                            }}
                            className="absolute end-6 top-6 p-2 text-muted hover:text-foreground cursor-pointer transition-all duration-200 rounded-lg"
                            aria-label={t("close")}
                        >
                            <X size={18} />
                        </button>
                        <h2 className="text-lg font-bold text-foreground mb-1">
                            {editingVendor ? t("editVendor") : t("addVendor")}
                        </h2>
                        <p className="text-xs text-muted mb-6">
                            {t("description")}
                        </p>

                        <form onSubmit={handleSubmit} className="grid grid-cols-2 gap-5">
                            <div>
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5 ms-1">
                                    {t("nameEn")}
                                </label>
                                <input
                                    required
                                    className="w-full border border-border rounded-lg bg-surface p-3 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none"
                                    value={formData.nameEn}
                                    onChange={(ev) =>
                                        setFormData({ ...formData, nameEn: ev.target.value })
                                    }
                                />
                            </div>
                            <div>
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5 ms-1">
                                    {t("nameAr")}
                                </label>
                                <input
                                    className="w-full border border-border rounded-lg bg-surface p-3 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none"
                                    value={formData.nameAr}
                                    onChange={(ev) =>
                                        setFormData({ ...formData, nameAr: ev.target.value })
                                    }
                                    dir="rtl"
                                />
                            </div>
                            <div>
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5 ms-1">
                                    {t("tradeLicense")}
                                </label>
                                <input
                                    className="w-full border border-border rounded-lg bg-surface p-3 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none"
                                    value={formData.tradeLicenseNumber}
                                    onChange={(ev) =>
                                        setFormData({
                                            ...formData,
                                            tradeLicenseNumber: ev.target.value,
                                        })
                                    }
                                />
                            </div>
                            <div>
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5 ms-1">
                                    {t("trn")}
                                </label>
                                <input
                                    className="w-full border border-border rounded-lg bg-surface p-3 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none"
                                    value={formData.trn}
                                    onChange={(ev) =>
                                        setFormData({ ...formData, trn: ev.target.value })
                                    }
                                />
                            </div>
                            <div>
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5 ms-1">
                                    {t("email")}
                                </label>
                                <input
                                    type="email"
                                    className="w-full border border-border rounded-lg bg-surface p-3 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none"
                                    value={formData.email}
                                    onChange={(ev) =>
                                        setFormData({ ...formData, email: ev.target.value })
                                    }
                                />
                            </div>
                            <div>
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5 ms-1">
                                    {t("phone")}
                                </label>
                                <input
                                    className="w-full border border-border rounded-lg bg-surface p-3 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none"
                                    value={formData.phone}
                                    onChange={(ev) =>
                                        setFormData({ ...formData, phone: ev.target.value })
                                    }
                                />
                            </div>
                            <div>
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5 ms-1">
                                    {t("contactPerson")}
                                </label>
                                <input
                                    className="w-full border border-border rounded-lg bg-surface p-3 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none"
                                    value={formData.contactPerson}
                                    onChange={(ev) =>
                                        setFormData({
                                            ...formData,
                                            contactPerson: ev.target.value,
                                        })
                                    }
                                />
                            </div>
                            <div>
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5 ms-1">
                                    {t("bankName")}
                                </label>
                                <input
                                    className="w-full border border-border rounded-lg bg-surface p-3 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none"
                                    value={formData.bankName}
                                    onChange={(ev) =>
                                        setFormData({ ...formData, bankName: ev.target.value })
                                    }
                                />
                            </div>
                            <div className="col-span-2">
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5 ms-1">
                                    {t("address")}
                                </label>
                                <textarea
                                    className="w-full border border-border rounded-lg bg-surface p-3 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none h-20 resize-none"
                                    value={formData.address}
                                    onChange={(ev) =>
                                        setFormData({ ...formData, address: ev.target.value })
                                    }
                                />
                            </div>
                            <div>
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5 ms-1">
                                    {t("accountNumber")}
                                </label>
                                <input
                                    className="w-full border border-border rounded-lg bg-surface p-3 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none"
                                    value={formData.bankAccountNumber}
                                    onChange={(ev) =>
                                        setFormData({
                                            ...formData,
                                            bankAccountNumber: ev.target.value,
                                        })
                                    }
                                />
                            </div>
                            <div>
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5 ms-1">
                                    {t("iban")}
                                </label>
                                <input
                                    className="w-full border border-border rounded-lg bg-surface p-3 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none"
                                    value={formData.iban}
                                    onChange={(ev) =>
                                        setFormData({ ...formData, iban: ev.target.value })
                                    }
                                />
                            </div>
                            {/* The payable account is created and remapped by the ledger
                                itself, so it is shown here but never chosen by hand —
                                repointing a vendor's payable mid-life orphans its posted
                                entries. */}
                            <div>
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5 ms-1">
                                    {t("payableAccount")}
                                </label>
                                <p className="w-full border border-border rounded-lg bg-input p-3 text-xs text-muted">
                                    {editingVendor?.payableAccount
                                        ? `${editingVendor.payableAccount.code} - ${accountName(editingVendor.payableAccount, locale)}`
                                        : t("payableAccountManaged")}
                                </p>
                            </div>
                            <div className="flex items-center gap-3 pt-5">
                                <label className="flex items-center gap-2 cursor-pointer">
                                    <input
                                        type="checkbox"
                                        className="rounded border-border"
                                        checked={formData.active}
                                        onChange={(ev) =>
                                            setFormData({
                                                ...formData,
                                                active: ev.target.checked,
                                            })
                                        }
                                    />
                                    <span className="text-xs font-bold text-muted">
                                        {t("active")}
                                    </span>
                                </label>
                            </div>
                            <div className="col-span-2">
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5 ms-1">
                                    {t("notes")}
                                </label>
                                <textarea
                                    className="w-full border border-border rounded-lg bg-surface p-3 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none h-20 resize-none"
                                    value={formData.notes}
                                    onChange={(ev) =>
                                        setFormData({ ...formData, notes: ev.target.value })
                                    }
                                />
                            </div>
                            {formError && (
                                <div className="col-span-2 flex items-center gap-2 bg-error/10 border border-error/30 text-error rounded-lg px-4 py-3 text-xs font-medium">
                                    <AlertCircle size={14} className="shrink-0" />
                                    <span>{formError}</span>
                                </div>
                            )}
                            <div className="col-span-2 flex justify-end gap-3 mt-2">
                                <button
                                    type="button"
                                    onClick={() => {
                                        setShowModal(false);
                                        setEditingVendor(null);
                                    }}
                                    className="bg-surface text-foreground border border-border px-4 py-2 rounded-lg text-xs font-semibold hover:bg-input transition-all cursor-pointer"
                                >
                                    {t("cancel")}
                                </button>
                                <button
                                    type="submit"
                                    disabled={submitting}
                                    className="bg-primary text-primary-foreground px-4 py-2 rounded-lg text-xs font-semibold hover:bg-primary/90 transition-all cursor-pointer disabled:opacity-50 flex items-center gap-2"
                                >
                                    {submitting && (
                                        <Loader2 size={14} className="animate-spin" />
                                    )}
                                    {editingVendor ? t("editVendor") : t("addVendor")}
                                </button>
                            </div>
                        </form>
                    </div>
                </div>
            )}

            <ConfirmDialog
                isOpen={confirmDialog !== null}
                onClose={() => setConfirmDialog(null)}
                onConfirm={confirmDialog?.onConfirm || (() => {})}
                title={confirmDialog?.title || ""}
                description={confirmDialog?.description}
                confirmText={confirmDialog?.confirmText || "Confirm"}
                isDestructive={confirmDialog?.isDestructive || false}
            />
            {errorBanner && (
                <div className="fixed bottom-6 left-1/2 -translate-x-1/2 z-[110] bg-error/10 border border-error/30 text-error px-4 py-2 rounded-lg text-xs font-semibold shadow-lg">
                    {errorBanner}
                </div>
            )}
        </div>
    );
}
