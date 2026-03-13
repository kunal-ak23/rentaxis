"use client";

import { useState, useEffect } from "react";
import { useTranslations, useLocale } from "next-intl";
import { Landmark, Plus, Pencil, Trash2, X, Loader2 } from "lucide-react";

type Account = {
    id: string;
    code: string;
    name: string;
    accountType: string;
    accountSubType?: string;
};

type Property = {
    id: string;
    nameEn: string;
    nameAr: string;
};

type PropertyStats = {
    property: Property;
};

type BankAccount = {
    id: string;
    bankName: string;
    accountNumber: string;
    iban: string;
    branchName: string;
    currency: string;
    property: Property | null;
    coaAccount: Account | null;
    isDefault: boolean;
    active: boolean;
    notes: string;
};

const emptyForm = {
    bankName: "",
    accountNumber: "",
    iban: "",
    branchName: "",
    currency: "AED",
    propertyId: "",
    coaAccountId: "",
    isDefault: false,
    notes: "",
};

export default function BankAccountsPage() {
    const t = useTranslations("BankAccounts");
    const locale = useLocale();
    const [bankAccounts, setBankAccounts] = useState<BankAccount[]>([]);
    const [accounts, setAccounts] = useState<Account[]>([]);
    const [properties, setProperties] = useState<PropertyStats[]>([]);
    const [loading, setLoading] = useState(true);
    const [showModal, setShowModal] = useState(false);
    const [editingAccount, setEditingAccount] = useState<BankAccount | null>(null);
    const [submitting, setSubmitting] = useState(false);
    const [formData, setFormData] = useState(emptyForm);

    useEffect(() => {
        fetchBankAccounts();
        fetchAccounts();
        fetchProperties();
    }, []);

    const fetchBankAccounts = async () => {
        try {
            const res = await fetch("/api/proxy/v1/bank-accounts");
            if (res.ok) setBankAccounts(await res.json());
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
                setAccounts(data.filter((a) => a.accountType === "ASSET"));
            }
        } catch (err) {
            console.error(err);
        }
    };

    const fetchProperties = async () => {
        try {
            const res = await fetch("/api/proxy/v1/properties");
            if (res.ok) setProperties(await res.json());
        } catch (err) {
            console.error(err);
        }
    };

    const openAddModal = () => {
        setEditingAccount(null);
        setFormData(emptyForm);
        setShowModal(true);
    };

    const openEditModal = (ba: BankAccount) => {
        setEditingAccount(ba);
        setFormData({
            bankName: ba.bankName || "",
            accountNumber: ba.accountNumber || "",
            iban: ba.iban || "",
            branchName: ba.branchName || "",
            currency: ba.currency || "AED",
            propertyId: ba.property?.id || "",
            coaAccountId: ba.coaAccount?.id || "",
            isDefault: ba.isDefault,
            notes: ba.notes || "",
        });
        setShowModal(true);
    };

    const handleSubmit = async (ev: React.FormEvent) => {
        ev.preventDefault();
        setSubmitting(true);
        try {
            const body: Record<string, unknown> = {
                bankName: formData.bankName,
                accountNumber: formData.accountNumber,
                iban: formData.iban,
                branchName: formData.branchName,
                currency: formData.currency,
                isDefault: formData.isDefault,
                notes: formData.notes,
            };
            if (formData.propertyId) {
                body.property = { id: formData.propertyId };
            }
            if (formData.coaAccountId) {
                body.coaAccount = { id: formData.coaAccountId };
            }

            const url = editingAccount
                ? `/api/proxy/v1/bank-accounts/${editingAccount.id}`
                : "/api/proxy/v1/bank-accounts";
            const method = editingAccount ? "PUT" : "POST";

            const res = await fetch(url, {
                method,
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(body),
            });

            if (res.ok) {
                setShowModal(false);
                setEditingAccount(null);
                setFormData(emptyForm);
                fetchBankAccounts();
            }
        } catch (err) {
            console.error(err);
        } finally {
            setSubmitting(false);
        }
    };

    const handleDelete = async (ba: BankAccount) => {
        if (!window.confirm(t("confirmDelete"))) return;
        try {
            const res = await fetch(`/api/proxy/v1/bank-accounts/${ba.id}`, {
                method: "DELETE",
            });
            if (res.ok) fetchBankAccounts();
        } catch (err) {
            console.error(err);
        }
    };

    const propertyName = (p: Property | null) => {
        if (!p) return "\u2014";
        return locale === "ar" ? p.nameAr || p.nameEn : p.nameEn || p.nameAr;
    };

    return (
        <div>
            {/* Header */}
            <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 mb-10">
                <div>
                    <h1 className="text-xl font-black text-foreground tracking-tight mb-1 flex items-center gap-2">
                        <Landmark size={20} className="text-primary" />
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
                    {t("addAccount")}
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
            {!loading && bankAccounts.length === 0 && (
                <div className="text-center py-24 bg-gray-50 border border-dashed border-gray-200 rounded-[2.5rem] flex flex-col items-center">
                    <div className="w-16 h-16 bg-white rounded-2xl flex items-center justify-center text-gray-200 shadow-sm mb-6">
                        <Landmark size={28} />
                    </div>
                    <h3 className="text-sm font-black text-foreground mb-1">
                        {t("noBankAccounts")}
                    </h3>
                    <p className="text-xs text-gray-400 font-medium">
                        {t("addBankAccountDesc")}
                    </p>
                </div>
            )}

            {/* Bank Accounts Table */}
            {!loading && bankAccounts.length > 0 && (
                <div className="bg-white border border-border rounded-2xl overflow-hidden shadow-sm">
                    <div className="overflow-x-auto">
                        <table className="w-full">
                            <thead>
                                <tr className="border-b border-gray-100">
                                    <th className="text-left px-5 py-3.5 text-[10px] font-bold text-gray-400 uppercase tracking-wider">
                                        {t("bankName")}
                                    </th>
                                    <th className="text-left px-5 py-3.5 text-[10px] font-bold text-gray-400 uppercase tracking-wider">
                                        {t("accountNumber")}
                                    </th>
                                    <th className="text-left px-5 py-3.5 text-[10px] font-bold text-gray-400 uppercase tracking-wider">
                                        {t("iban")}
                                    </th>
                                    <th className="text-left px-5 py-3.5 text-[10px] font-bold text-gray-400 uppercase tracking-wider">
                                        {t("branchName")}
                                    </th>
                                    <th className="text-left px-5 py-3.5 text-[10px] font-bold text-gray-400 uppercase tracking-wider">
                                        {t("property")}
                                    </th>
                                    <th className="text-left px-5 py-3.5 text-[10px] font-bold text-gray-400 uppercase tracking-wider">
                                        {t("coaAccount")}
                                    </th>
                                    <th className="text-left px-5 py-3.5 text-[10px] font-bold text-gray-400 uppercase tracking-wider">
                                        {t("isDefault")}
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
                                {bankAccounts.map((ba) => (
                                    <tr
                                        key={ba.id}
                                        className="hover:bg-gray-50/50 transition-all duration-200"
                                    >
                                        <td className="px-5 py-3 text-xs text-foreground font-medium">
                                            {ba.bankName}
                                        </td>
                                        <td className="px-5 py-3 text-xs text-foreground font-medium">
                                            {ba.accountNumber || "\u2014"}
                                        </td>
                                        <td className="px-5 py-3 text-xs text-foreground font-medium">
                                            {ba.iban || "\u2014"}
                                        </td>
                                        <td className="px-5 py-3 text-xs text-foreground font-medium">
                                            {ba.branchName || "\u2014"}
                                        </td>
                                        <td className="px-5 py-3 text-xs text-foreground font-medium">
                                            {propertyName(ba.property)}
                                        </td>
                                        <td className="px-5 py-3 text-xs text-foreground font-medium">
                                            {ba.coaAccount
                                                ? `${ba.coaAccount.code} - ${ba.coaAccount.name}`
                                                : "\u2014"}
                                        </td>
                                        <td className="px-5 py-3">
                                            {ba.isDefault && (
                                                <span className="bg-blue-50 text-blue-700 px-2.5 py-1 rounded-full text-[10px] font-bold">
                                                    {t("isDefault")}
                                                </span>
                                            )}
                                        </td>
                                        <td className="px-5 py-3">
                                            {ba.active ? (
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
                                                    onClick={() => openEditModal(ba)}
                                                    className="p-1.5 text-gray-400 hover:text-primary rounded-lg hover:bg-primary/5 transition-all cursor-pointer"
                                                    aria-label={t("editAccount")}
                                                >
                                                    <Pencil size={14} />
                                                </button>
                                                <button
                                                    onClick={() => handleDelete(ba)}
                                                    className="p-1.5 text-gray-400 hover:text-red-500 rounded-lg hover:bg-red-50 transition-all cursor-pointer"
                                                    aria-label={t("deleteAccount")}
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
                                setEditingAccount(null);
                            }}
                            className="absolute right-6 top-6 p-2 text-gray-400 hover:text-gray-600 cursor-pointer transition-all duration-200 rounded-lg"
                            aria-label="Close"
                        >
                            <X size={18} />
                        </button>
                        <h2 className="text-lg font-black text-foreground mb-1">
                            {editingAccount ? t("editAccount") : t("addAccount")}
                        </h2>
                        <p className="text-xs text-gray-400 mb-6">
                            {t("description")}
                        </p>

                        <form onSubmit={handleSubmit} className="grid grid-cols-2 gap-5">
                            <div>
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">
                                    {t("bankName")}
                                </label>
                                <input
                                    required
                                    className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none"
                                    value={formData.bankName}
                                    onChange={(ev) =>
                                        setFormData({ ...formData, bankName: ev.target.value })
                                    }
                                />
                            </div>
                            <div>
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">
                                    {t("accountNumber")}
                                </label>
                                <input
                                    className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none"
                                    value={formData.accountNumber}
                                    onChange={(ev) =>
                                        setFormData({ ...formData, accountNumber: ev.target.value })
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
                                    {t("branchName")}
                                </label>
                                <input
                                    className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none"
                                    value={formData.branchName}
                                    onChange={(ev) =>
                                        setFormData({ ...formData, branchName: ev.target.value })
                                    }
                                />
                            </div>
                            <div>
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">
                                    {t("currency")}
                                </label>
                                <input
                                    className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none"
                                    value={formData.currency}
                                    onChange={(ev) =>
                                        setFormData({ ...formData, currency: ev.target.value })
                                    }
                                />
                            </div>
                            <div>
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">
                                    {t("property")}
                                </label>
                                <select
                                    className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none"
                                    value={formData.propertyId}
                                    onChange={(ev) =>
                                        setFormData({ ...formData, propertyId: ev.target.value })
                                    }
                                >
                                    <option value="">-- Select Property --</option>
                                    {properties.map((s) => (
                                        <option key={s.property.id} value={s.property.id}>
                                            {locale === "ar"
                                                ? s.property.nameAr || s.property.nameEn
                                                : s.property.nameEn || s.property.nameAr}
                                        </option>
                                    ))}
                                </select>
                            </div>
                            <div>
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">
                                    {t("coaAccount")}
                                </label>
                                <select
                                    className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none"
                                    value={formData.coaAccountId}
                                    onChange={(ev) =>
                                        setFormData({ ...formData, coaAccountId: ev.target.value })
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
                                        checked={formData.isDefault}
                                        onChange={(ev) =>
                                            setFormData({
                                                ...formData,
                                                isDefault: ev.target.checked,
                                            })
                                        }
                                    />
                                    <span className="text-xs font-bold text-gray-500">
                                        {t("isDefault")}
                                    </span>
                                </label>
                            </div>
                            <div className="col-span-2">
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">
                                    Notes
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
                                        setEditingAccount(null);
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
                                    {editingAccount ? t("editAccount") : t("addAccount")}
                                </button>
                            </div>
                        </form>
                    </div>
                </div>
            )}
        </div>
    );
}
