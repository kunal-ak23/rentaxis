"use client";

import { useState, useEffect } from "react";
import { useTranslations, useLocale } from "next-intl";
import { UserCog, Plus, Pencil, Trash2, X, Loader2, Users, Banknote } from "lucide-react";

type Property = {
    id: string;
    nameEn: string;
    nameAr: string;
};

type PropertyStats = {
    property: Property;
};

type Account = {
    id: string;
    code: string;
    name: string;
    accountType: string;
};

type Staff = {
    id: string;
    nameEn: string;
    nameAr: string;
    employeeId: string;
    designation: string;
    department: string;
    monthlySalary: number;
    joinDate: string;
    phone: string;
    emiratesId: string;
    passportNumber: string;
    property: Property | null;
    salaryAccount: Account | null;
    active: boolean;
};

const emptyForm = {
    nameEn: "",
    nameAr: "",
    employeeId: "",
    designation: "",
    department: "",
    monthlySalary: 0,
    joinDate: "",
    phone: "",
    emiratesId: "",
    passportNumber: "",
    propertyId: "",
    salaryAccountId: "",
    active: true,
};

export default function StaffPage() {
    const t = useTranslations("Staff");
    const locale = useLocale();
    const [staff, setStaff] = useState<Staff[]>([]);
    const [properties, setProperties] = useState<PropertyStats[]>([]);
    const [accounts, setAccounts] = useState<Account[]>([]);
    const [loading, setLoading] = useState(true);
    const [showModal, setShowModal] = useState(false);
    const [editingStaff, setEditingStaff] = useState<Staff | null>(null);
    const [submitting, setSubmitting] = useState(false);
    const [formData, setFormData] = useState(emptyForm);
    const [filterPropertyId, setFilterPropertyId] = useState("");

    useEffect(() => {
        fetchStaff();
        fetchProperties();
        fetchAccounts();
    }, []);

    const fetchStaff = async () => {
        try {
            const res = await fetch("/api/proxy/v1/staff");
            if (res.ok) setStaff(await res.json());
        } catch (err) {
            console.error(err);
        } finally {
            setLoading(false);
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

    const fetchAccounts = async () => {
        try {
            const res = await fetch("/api/proxy/v1/finance/accounts");
            if (res.ok) {
                const data: Account[] = await res.json();
                setAccounts(data.filter((a) => a.accountType === "EXPENSE"));
            }
        } catch (err) {
            console.error(err);
        }
    };

    const openAddModal = () => {
        setEditingStaff(null);
        setFormData(emptyForm);
        setShowModal(true);
    };

    const openEditModal = (member: Staff) => {
        setEditingStaff(member);
        setFormData({
            nameEn: member.nameEn || "",
            nameAr: member.nameAr || "",
            employeeId: member.employeeId || "",
            designation: member.designation || "",
            department: member.department || "",
            monthlySalary: member.monthlySalary || 0,
            joinDate: member.joinDate || "",
            phone: member.phone || "",
            emiratesId: member.emiratesId || "",
            passportNumber: member.passportNumber || "",
            propertyId: member.property?.id || "",
            salaryAccountId: member.salaryAccount?.id || "",
            active: member.active,
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
                employeeId: formData.employeeId,
                designation: formData.designation,
                department: formData.department,
                monthlySalary: formData.monthlySalary,
                joinDate: formData.joinDate,
                phone: formData.phone,
                emiratesId: formData.emiratesId,
                passportNumber: formData.passportNumber,
                active: formData.active,
            };
            if (formData.propertyId) {
                body.property = { id: formData.propertyId };
            }
            if (formData.salaryAccountId) {
                body.salaryAccount = { id: formData.salaryAccountId };
            }

            const url = editingStaff
                ? `/api/proxy/v1/staff/${editingStaff.id}`
                : "/api/proxy/v1/staff";
            const method = editingStaff ? "PUT" : "POST";

            const res = await fetch(url, {
                method,
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(body),
            });

            if (res.ok) {
                setShowModal(false);
                setEditingStaff(null);
                setFormData(emptyForm);
                fetchStaff();
            }
        } catch (err) {
            console.error(err);
        } finally {
            setSubmitting(false);
        }
    };

    const handleDelete = async (member: Staff) => {
        if (!window.confirm(t("confirmDelete"))) return;
        try {
            const res = await fetch(`/api/proxy/v1/staff/${member.id}`, {
                method: "DELETE",
            });
            if (res.ok) fetchStaff();
        } catch (err) {
            console.error(err);
        }
    };

    const staffName = (s: Staff) =>
        locale === "ar" ? s.nameAr || s.nameEn : s.nameEn || s.nameAr;

    const propertyName = (p: Property | null) => {
        if (!p) return "\u2014";
        return locale === "ar" ? p.nameAr || p.nameEn : p.nameEn || p.nameAr;
    };

    const filteredStaff = filterPropertyId
        ? staff.filter((s) => s.property?.id === filterPropertyId)
        : staff;

    const totalSalary = filteredStaff.reduce((sum, s) => sum + (s.monthlySalary || 0), 0);

    return (
        <div>
            {/* Header */}
            <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 mb-10">
                <div>
                    <h1 className="text-xl font-black text-foreground tracking-tight mb-1 flex items-center gap-2">
                        <UserCog size={20} className="text-primary" />
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
                    {t("addStaff")}
                </button>
            </div>

            {/* Summary Cards */}
            {!loading && staff.length > 0 && (
                <div className="grid grid-cols-2 gap-4 mb-8">
                    <div className="bg-white border border-border rounded-2xl p-5 shadow-sm flex items-center gap-4">
                        <div className="w-12 h-12 rounded-xl bg-primary/10 flex items-center justify-center">
                            <Users size={20} className="text-primary" />
                        </div>
                        <div>
                            <div className="text-lg font-black text-foreground">
                                {filteredStaff.length}
                            </div>
                            <div className="text-[10px] font-bold text-gray-400 uppercase">
                                {t("staffCount")}
                            </div>
                        </div>
                    </div>
                    <div className="bg-white border border-border rounded-2xl p-5 shadow-sm flex items-center gap-4">
                        <div className="w-12 h-12 rounded-xl bg-primary/10 flex items-center justify-center">
                            <Banknote size={20} className="text-primary" />
                        </div>
                        <div>
                            <div className="text-lg font-black text-foreground">
                                {totalSalary.toLocaleString(undefined, { minimumFractionDigits: 2 })} AED
                            </div>
                            <div className="text-[10px] font-bold text-gray-400 uppercase">
                                {t("totalSalaryBill")}
                            </div>
                        </div>
                    </div>
                </div>
            )}

            {/* Property Filter */}
            {!loading && staff.length > 0 && (
                <div className="mb-6">
                    <select
                        className="bg-input border border-border p-2.5 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none"
                        value={filterPropertyId}
                        onChange={(ev) => setFilterPropertyId(ev.target.value)}
                    >
                        <option value="">All Properties</option>
                        {properties.map((s) => (
                            <option key={s.property.id} value={s.property.id}>
                                {propertyName(s.property)}
                            </option>
                        ))}
                    </select>
                </div>
            )}

            {/* Loading Skeleton */}
            {loading && (
                <div className="space-y-3 animate-pulse">
                    {[1, 2, 3, 4].map((i) => (
                        <div key={i} className="bg-gray-200 rounded-2xl h-16" />
                    ))}
                </div>
            )}

            {/* Empty State */}
            {!loading && filteredStaff.length === 0 && (
                <div className="text-center py-24 bg-gray-50 border border-dashed border-gray-200 rounded-[2.5rem] flex flex-col items-center">
                    <div className="w-16 h-16 bg-white rounded-2xl flex items-center justify-center text-gray-200 shadow-sm mb-6">
                        <UserCog size={28} />
                    </div>
                    <h3 className="text-sm font-black text-foreground mb-1">
                        {t("noStaffFound")}
                    </h3>
                    <p className="text-xs text-gray-400 font-medium">
                        {t("addStaffDesc")}
                    </p>
                </div>
            )}

            {/* Staff Table */}
            {!loading && filteredStaff.length > 0 && (
                <div className="bg-white border border-border rounded-2xl overflow-hidden shadow-sm">
                    <div className="overflow-x-auto">
                        <table className="w-full">
                            <thead>
                                <tr className="border-b border-gray-100">
                                    <th className="text-left px-5 py-3.5 text-[10px] font-bold text-gray-400 uppercase tracking-wider">
                                        {locale === "ar" ? t("nameAr") : t("nameEn")}
                                    </th>
                                    <th className="text-left px-5 py-3.5 text-[10px] font-bold text-gray-400 uppercase tracking-wider">
                                        {t("employeeId")}
                                    </th>
                                    <th className="text-left px-5 py-3.5 text-[10px] font-bold text-gray-400 uppercase tracking-wider">
                                        {t("designation")}
                                    </th>
                                    <th className="text-left px-5 py-3.5 text-[10px] font-bold text-gray-400 uppercase tracking-wider">
                                        {t("property")}
                                    </th>
                                    <th className="text-left px-5 py-3.5 text-[10px] font-bold text-gray-400 uppercase tracking-wider">
                                        {t("monthlySalary")}
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
                                {filteredStaff.map((member) => (
                                    <tr
                                        key={member.id}
                                        className="hover:bg-gray-50/50 transition-all duration-200"
                                    >
                                        <td className="px-5 py-3 text-xs text-foreground font-medium">
                                            {staffName(member)}
                                        </td>
                                        <td className="px-5 py-3 text-xs text-foreground font-medium">
                                            {member.employeeId || "\u2014"}
                                        </td>
                                        <td className="px-5 py-3 text-xs text-foreground font-medium">
                                            {member.designation || "\u2014"}
                                        </td>
                                        <td className="px-5 py-3 text-xs text-foreground font-medium">
                                            {member.property
                                                ? propertyName(member.property)
                                                : "\u2014"}
                                        </td>
                                        <td className="px-5 py-3 text-xs text-foreground font-medium">
                                            {member.monthlySalary
                                                ? member.monthlySalary.toLocaleString(undefined, { minimumFractionDigits: 2 }) + " AED"
                                                : "\u2014"}
                                        </td>
                                        <td className="px-5 py-3">
                                            {member.active ? (
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
                                                    onClick={() => openEditModal(member)}
                                                    className="p-1.5 text-gray-400 hover:text-primary rounded-lg hover:bg-primary/5 transition-all cursor-pointer"
                                                    aria-label={t("editStaff")}
                                                >
                                                    <Pencil size={14} />
                                                </button>
                                                <button
                                                    onClick={() => handleDelete(member)}
                                                    className="p-1.5 text-gray-400 hover:text-red-500 rounded-lg hover:bg-red-50 transition-all cursor-pointer"
                                                    aria-label={t("deleteStaff")}
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
                                setEditingStaff(null);
                            }}
                            className="absolute right-6 top-6 p-2 text-gray-400 hover:text-gray-600 cursor-pointer transition-all duration-200 rounded-lg"
                            aria-label="Close"
                        >
                            <X size={18} />
                        </button>
                        <h2 className="text-lg font-black text-foreground mb-1">
                            {editingStaff ? t("editStaff") : t("addStaff")}
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
                                    {t("employeeId")}
                                </label>
                                <input
                                    className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none"
                                    value={formData.employeeId}
                                    onChange={(ev) =>
                                        setFormData({ ...formData, employeeId: ev.target.value })
                                    }
                                />
                            </div>
                            <div>
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">
                                    {t("designation")}
                                </label>
                                <input
                                    className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none"
                                    value={formData.designation}
                                    onChange={(ev) =>
                                        setFormData({ ...formData, designation: ev.target.value })
                                    }
                                />
                            </div>
                            <div>
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">
                                    {t("department")}
                                </label>
                                <input
                                    className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none"
                                    value={formData.department}
                                    onChange={(ev) =>
                                        setFormData({ ...formData, department: ev.target.value })
                                    }
                                />
                            </div>
                            <div>
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">
                                    {t("monthlySalary")}
                                </label>
                                <input
                                    type="number"
                                    step="0.01"
                                    min="0"
                                    className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none"
                                    value={formData.monthlySalary}
                                    onChange={(ev) =>
                                        setFormData({ ...formData, monthlySalary: parseFloat(ev.target.value) || 0 })
                                    }
                                />
                            </div>
                            <div>
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">
                                    {t("joinDate")}
                                </label>
                                <input
                                    type="date"
                                    className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none"
                                    value={formData.joinDate}
                                    onChange={(ev) =>
                                        setFormData({ ...formData, joinDate: ev.target.value })
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
                                    {t("emiratesId")}
                                </label>
                                <input
                                    className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none"
                                    value={formData.emiratesId}
                                    onChange={(ev) =>
                                        setFormData({ ...formData, emiratesId: ev.target.value })
                                    }
                                />
                            </div>
                            <div>
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">
                                    {t("passportNumber")}
                                </label>
                                <input
                                    className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none"
                                    value={formData.passportNumber}
                                    onChange={(ev) =>
                                        setFormData({ ...formData, passportNumber: ev.target.value })
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
                                            {propertyName(s.property)}
                                        </option>
                                    ))}
                                </select>
                            </div>
                            <div>
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">
                                    {t("salaryAccount")}
                                </label>
                                <select
                                    className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none"
                                    value={formData.salaryAccountId}
                                    onChange={(ev) =>
                                        setFormData({ ...formData, salaryAccountId: ev.target.value })
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
                                            setFormData({ ...formData, active: ev.target.checked })
                                        }
                                    />
                                    <span className="text-xs font-bold text-gray-500">
                                        {t("active")}
                                    </span>
                                </label>
                            </div>
                            <div className="col-span-2 flex justify-end gap-3 mt-2">
                                <button
                                    type="button"
                                    onClick={() => {
                                        setShowModal(false);
                                        setEditingStaff(null);
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
                                    {editingStaff ? t("editStaff") : t("addStaff")}
                                </button>
                            </div>
                        </form>
                    </div>
                </div>
            )}
        </div>
    );
}
