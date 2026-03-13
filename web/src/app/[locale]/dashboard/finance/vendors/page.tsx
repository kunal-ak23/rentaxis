"use client";

import { useState, useEffect } from "react";
import { useTranslations, useLocale } from "next-intl";
import { Users, Plus, Pencil, Trash2, X, Loader2, Package } from "lucide-react";

type Account = {
    id: string;
    code: string;
    name: string;
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
    payableAccountId: "",
    notes: "",
    active: true,
};

export default function VendorsPage() {
    const t = useTranslations("Vendors");
    const locale = useLocale();
    const [vendors, setVendors] = useState<Vendor[]>([]);
    const [accounts, setAccounts] = useState<Account[]>([]);
    const [loading, setLoading] = useState(true);
    const [showModal, setShowModal] = useState(false);
    const [editingVendor, setEditingVendor] = useState<Vendor | null>(null);
    const [submitting, setSubmitting] = useState(false);
    const [formData, setFormData] = useState(emptyForm);

    useEffect(() => {
        fetchVendors();
        fetchAccounts();
    }, []);

    const fetchVendors = async () => {
        try {
            const res = await fetch("/api/proxy/v1/vendors");
            if (res.ok) setVendors(await res.json());
        } catch (err) {
            console.error(err);
        } finally {
            setLoading(false);
        }
    };

    const fetchAccounts = async () => {
        try {
            const res = await fetch("/api/proxy/v1/finance/accounts");
            if (res.ok) {
                const data: Account[] = await res.json();
                setAccounts(data.filter((a) => a.accountType === "LIABILITY"));
            }
        } catch (err) {
            console.error(err);
        }
    };

    const openAddModal = () => {
        setEditingVendor(null);
        setFormData(emptyForm);
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
            payableAccountId: vendor.payableAccount?.id || "",
            notes: vendor.notes || "",
            active: vendor.active,
        });
        setShowModal(true);
    };

    const handleSubmit = async (ev: React.FormEvent) => {
        ev.preventDefault();
        setSubmitting(true);
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
            if (formData.payableAccountId) {
                body.payableAccount = { id: formData.payableAccountId };
            }

            const url = editingVendor
                ? `/api/proxy/v1/vendors/${editingVendor.id}`
                : "/api/proxy/v1/vendors";
            const method = editingVendor ? "PUT" : "POST";

            const res = await fetch(url, {
                method,
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(body),
            });

            if (res.ok) {
                setShowModal(false);
                setEditingVendor(null);
                setFormData(emptyForm);
                fetchVendors();
            }
        } catch (err) {
            console.error(err);
        } finally {
            setSubmitting(false);
        }
    };

    const handleDelete = async (vendor: Vendor) => {
        if (!window.confirm(t("confirmDelete"))) return;
        try {
            const res = await fetch(`/api/proxy/v1/vendors/${vendor.id}`, {
                method: "DELETE",
            });
            if (res.ok) fetchVendors();
        } catch (err) {
            console.error(err);
        }
    };

    const vendorName = (v: Vendor) =>
        locale === "ar" ? v.nameAr || v.nameEn : v.nameEn || v.nameAr;

    return (
        <div>
            {/* Header */}
            <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 mb-10">
                <div>
                    <h1 className="text-xl font-black text-foreground tracking-tight mb-1 flex items-center gap-2">
                        <Users size={20} className="text-primary" />
                        {t("title")}
                    </h1>
                    <p className="text-xs text-gray-500 font-medium">
                        {t("description")}
                    </p>
                </div>
                <button
                    onClick={openAddModal}
                    className="px-5 py-2.5 bg-primary text-primary-foreground rounded-xl text-xs font-bold flex items-center gap-2 hover:opacity-90 transition-all shadow-md shadow-primary/20"
                >
                    <Plus size={14} />
                    {t("addVendor")}
                </button>
            </div>

            {/* Loading Skeleton */}
            {loading && (
                <div className="space-y-3 animate-pulse">
                    {[1, 2, 3, 4].map((i) => (
                        <div key={i} className="bg-gray-200 rounded-2xl h-16" />
                    ))}
                </div>
            )}

            {/* Empty State */}
            {!loading && vendors.length === 0 && (
                <div className="text-center py-24 bg-gray-50 border border-dashed border-gray-200 rounded-[2.5rem] flex flex-col items-center">
                    <div className="w-16 h-16 bg-white rounded-2xl flex items-center justify-center text-gray-200 shadow-sm mb-6">
                        <Package size={28} />
                    </div>
                    <h3 className="text-sm font-black text-foreground mb-1">
                        {t("noVendorsFound")}
                    </h3>
                    <p className="text-xs text-gray-400 font-medium">
                        {t("createVendorProfile")}
                    </p>
                </div>
            )}

            {/* Vendors Table */}
            {!loading && vendors.length > 0 && (
                <div className="bg-white border border-border rounded-2xl overflow-hidden shadow-sm">
                    <div className="overflow-x-auto">
                        <table className="w-full">
                            <thead>
                                <tr className="border-b border-gray-100">
                                    <th className="text-left px-5 py-3.5 text-[10px] font-bold text-gray-400 uppercase tracking-wider">
                                        {locale === "ar" ? t("nameAr") : t("nameEn")}
                                    </th>
                                    <th className="text-left px-5 py-3.5 text-[10px] font-bold text-gray-400 uppercase tracking-wider">
                                        {t("trn")}
                                    </th>
                                    <th className="text-left px-5 py-3.5 text-[10px] font-bold text-gray-400 uppercase tracking-wider">
                                        {t("contactPerson")}
                                    </th>
                                    <th className="text-left px-5 py-3.5 text-[10px] font-bold text-gray-400 uppercase tracking-wider">
                                        {t("phone")}
                                    </th>
                                    <th className="text-left px-5 py-3.5 text-[10px] font-bold text-gray-400 uppercase tracking-wider">
                                        {t("payableAccount")}
                                    </th>
                                    <th className="text-left px-5 py-3.5 text-[10px] font-bold text-gray-400 uppercase tracking-wider">
                                        Status
                                    </th>
                                    <th className="text-left px-5 py-3.5 text-[10px] font-bold text-gray-400 uppercase tracking-wider">
                                        Actions
                                    </th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-gray-50">
                                {vendors.map((vendor) => (
                                    <tr
                                        key={vendor.id}
                                        className="hover:bg-gray-50/50 transition-all duration-200"
                                    >
                                        <td className="px-5 py-3 text-xs text-foreground font-medium">
                                            {vendorName(vendor)}
                                        </td>
                                        <td className="px-5 py-3 text-xs text-foreground font-medium">
                                            {vendor.trn || "—"}
                                        </td>
                                        <td className="px-5 py-3 text-xs text-foreground font-medium">
                                            {vendor.contactPerson || "—"}
                                        </td>
                                        <td className="px-5 py-3 text-xs text-foreground font-medium">
                                            {vendor.phone || "—"}
                                        </td>
                                        <td className="px-5 py-3 text-xs text-foreground font-medium">
                                            {vendor.payableAccount
                                                ? `${vendor.payableAccount.code} - ${vendor.payableAccount.name}`
                                                : "—"}
                                        </td>
                                        <td className="px-5 py-3">
                                            {vendor.active ? (
                                                <span className="bg-emerald-50 text-emerald-700 px-2.5 py-1 rounded-full text-[10px] font-bold">
                                                    {t("active")}
                                                </span>
                                            ) : (
                                                <span className="bg-gray-100 text-gray-500 px-2.5 py-1 rounded-full text-[10px] font-bold">
                                                    {t("inactive")}
                                                </span>
                                            )}
                                        </td>
                                        <td className="px-5 py-3">
                                            <div className="flex items-center gap-2">
                                                <button
                                                    onClick={() => openEditModal(vendor)}
                                                    className="p-1.5 text-gray-400 hover:text-primary rounded-lg hover:bg-primary/5 transition-all cursor-pointer"
                                                    aria-label={t("editVendor")}
                                                >
                                                    <Pencil size={14} />
                                                </button>
                                                <button
                                                    onClick={() => handleDelete(vendor)}
                                                    className="p-1.5 text-gray-400 hover:text-red-500 rounded-lg hover:bg-red-50 transition-all cursor-pointer"
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
                </div>
            )}

            {/* Add/Edit Modal */}
            {showModal && (
                <div className="fixed inset-0 bg-gray-900/40 backdrop-blur-sm z-50 flex items-start justify-center pt-20">
                    <div className="bg-white rounded-3xl p-8 w-full max-w-2xl shadow-2xl border border-gray-100 relative max-h-[80vh] overflow-y-auto">
                        <button
                            onClick={() => {
                                setShowModal(false);
                                setEditingVendor(null);
                            }}
                            className="absolute right-6 top-6 p-2 text-gray-400 hover:text-gray-600 cursor-pointer transition-all duration-200 rounded-lg"
                            aria-label="Close"
                        >
                            <X size={18} />
                        </button>
                        <h2 className="text-lg font-black text-foreground mb-1">
                            {editingVendor ? t("editVendor") : t("addVendor")}
                        </h2>
                        <p className="text-xs text-gray-400 mb-6">
                            {t("description")}
                        </p>

                        <form onSubmit={handleSubmit} className="grid grid-cols-2 gap-5">
                            <div>
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">
                                    {t("nameEn")}
                                </label>
                                <input
                                    required
                                    className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none"
                                    value={formData.nameEn}
                                    onChange={(ev) =>
                                        setFormData({ ...formData, nameEn: ev.target.value })
                                    }
                                />
                            </div>
                            <div>
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">
                                    {t("nameAr")}
                                </label>
                                <input
                                    className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none"
                                    value={formData.nameAr}
                                    onChange={(ev) =>
                                        setFormData({ ...formData, nameAr: ev.target.value })
                                    }
                                    dir="rtl"
                                />
                            </div>
                            <div>
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">
                                    {t("tradeLicense")}
                                </label>
                                <input
                                    className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none"
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
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">
                                    {t("trn")}
                                </label>
                                <input
                                    className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none"
                                    value={formData.trn}
                                    onChange={(ev) =>
                                        setFormData({ ...formData, trn: ev.target.value })
                                    }
                                />
                            </div>
                            <div>
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">
                                    {t("email")}
                                </label>
                                <input
                                    type="email"
                                    className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none"
                                    value={formData.email}
                                    onChange={(ev) =>
                                        setFormData({ ...formData, email: ev.target.value })
                                    }
                                />
                            </div>
                            <div>
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">
                                    {t("phone")}
                                </label>
                                <input
                                    className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none"
                                    value={formData.phone}
                                    onChange={(ev) =>
                                        setFormData({ ...formData, phone: ev.target.value })
                                    }
                                />
                            </div>
                            <div>
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">
                                    {t("contactPerson")}
                                </label>
                                <input
                                    className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none"
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
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">
                                    {t("bankName")}
                                </label>
                                <input
                                    className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none"
                                    value={formData.bankName}
                                    onChange={(ev) =>
                                        setFormData({ ...formData, bankName: ev.target.value })
                                    }
                                />
                            </div>
                            <div className="col-span-2">
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">
                                    {t("address")}
                                </label>
                                <textarea
                                    className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none h-20 resize-none"
                                    value={formData.address}
                                    onChange={(ev) =>
                                        setFormData({ ...formData, address: ev.target.value })
                                    }
                                />
                            </div>
                            <div>
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">
                                    {t("accountNumber")}
                                </label>
                                <input
                                    className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none"
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
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">
                                    {t("iban")}
                                </label>
                                <input
                                    className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none"
                                    value={formData.iban}
                                    onChange={(ev) =>
                                        setFormData({ ...formData, iban: ev.target.value })
                                    }
                                />
                            </div>
                            <div>
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">
                                    {t("payableAccount")}
                                </label>
                                <select
                                    className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none"
                                    value={formData.payableAccountId}
                                    onChange={(ev) =>
                                        setFormData({
                                            ...formData,
                                            payableAccountId: ev.target.value,
                                        })
                                    }
                                >
                                    <option value="">-- Select Account --</option>
                                    {accounts.map((a) => (
                                        <option key={a.id} value={a.id}>
                                            {a.code} - {a.name}
                                        </option>
                                    ))}
                                </select>
                            </div>
                            <div className="flex items-center gap-3 pt-5">
                                <label className="flex items-center gap-2 cursor-pointer">
                                    <input
                                        type="checkbox"
                                        className="rounded border-gray-300"
                                        checked={formData.active}
                                        onChange={(ev) =>
                                            setFormData({
                                                ...formData,
                                                active: ev.target.checked,
                                            })
                                        }
                                    />
                                    <span className="text-xs font-bold text-gray-500">
                                        {t("active")}
                                    </span>
                                </label>
                            </div>
                            <div className="col-span-2">
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">
                                    {t("notes")}
                                </label>
                                <textarea
                                    className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none h-20 resize-none"
                                    value={formData.notes}
                                    onChange={(ev) =>
                                        setFormData({ ...formData, notes: ev.target.value })
                                    }
                                />
                            </div>
                            <div className="col-span-2 flex justify-end gap-3 mt-2">
                                <button
                                    type="button"
                                    onClick={() => {
                                        setShowModal(false);
                                        setEditingVendor(null);
                                    }}
                                    className="px-6 py-3 bg-gray-100 text-gray-500 rounded-xl text-xs font-bold"
                                >
                                    Cancel
                                </button>
                                <button
                                    type="submit"
                                    disabled={submitting}
                                    className="px-8 py-3 bg-primary text-primary-foreground rounded-xl text-xs font-bold hover:opacity-90 transition-all disabled:opacity-50 flex items-center gap-2"
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
        </div>
    );
}
