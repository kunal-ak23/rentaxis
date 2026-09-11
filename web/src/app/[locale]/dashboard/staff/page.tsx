"use client";

import { useState, useEffect } from "react";
import { useTranslations, useLocale } from "next-intl";
import { UserCog, Plus, Pencil, Trash2, X, Loader2, Users, Banknote, Search } from "lucide-react";
import { formatCurrency, formatCurrencyCompact } from "@/lib/format";
import { Pagination } from "@/components/ui/Pagination";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";
import { NumberInput } from "@/components/ui/NumberInput";

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
    const tCommon = useTranslations("Common");
    const locale = useLocale();
    const [staff, setStaff] = useState<Staff[]>([]);
    const [properties, setProperties] = useState<PropertyStats[]>([]);
    const [accounts, setAccounts] = useState<Account[]>([]);
    const [loading, setLoading] = useState(true);
    const [loadError, setLoadError] = useState<string | null>(null);
    const [showModal, setShowModal] = useState(false);
    const [editingStaff, setEditingStaff] = useState<Staff | null>(null);
    const [submitting, setSubmitting] = useState(false);
    const [formData, setFormData] = useState(emptyForm);
    const [searchQuery, setSearchQuery] = useState("");
    const [filterPropertyId, setFilterPropertyId] = useState("");
    const [currentPage, setCurrentPage] = useState(1);
    const [itemsPerPage, setItemsPerPage] = useState(25);
    const [confirmDialog, setConfirmDialog] = useState<{
        title: string;
        description: string;
        confirmText: string;
        isDestructive: boolean;
        onConfirm: () => void;
    } | null>(null);

    useEffect(() => {
        fetchStaff();
        fetchProperties();
        fetchAccounts();
    }, []);

    const fetchStaff = async () => {
        try {
            const res = await fetch("/api/proxy/v1/staff");
            if (res.ok) {
                const data = await res.json();
                data.sort((a: any, b: any) => (a.id || '').localeCompare(b.id || ''));
                setStaff(data);
                setLoadError(null);
            } else {
                // Without this, a 401/403/500 left `staff` at its initial [] and
                // the page rendered exactly like "no staff records".
                setLoadError(tCommon("loadFailedStaff"));
            }
        } catch (err) {
            console.error(err);
            setLoadError(tCommon("loadFailedStaff"));
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

    const handleDelete = (member: Staff) => {
        setConfirmDialog({
            title: "Delete Staff Member",
            description: "Are you sure you want to delete this staff member?",
            confirmText: "Delete",
            isDestructive: true,
            onConfirm: async () => {
                setConfirmDialog(null);
                try {
                    const res = await fetch(`/api/proxy/v1/staff/${member.id}`, {
                        method: "DELETE",
                    });
                    if (res.ok) fetchStaff();
                } catch (err) {
                    console.error(err);
                }
            },
        });
    };

    const staffName = (s: Staff) =>
        locale === "ar" ? s.nameAr || s.nameEn : s.nameEn || s.nameAr;

    const propertyName = (p: Property | null) => {
        if (!p) return "\u2014";
        return locale === "ar" ? p.nameAr || p.nameEn : p.nameEn || p.nameAr;
    };

    const searchedStaff = searchQuery
        ? staff.filter((s) => {
            const q = searchQuery.toLowerCase();
            return (
                (s.nameEn || "").toLowerCase().includes(q) ||
                (s.nameAr || "").toLowerCase().includes(q) ||
                (s.designation || "").toLowerCase().includes(q) ||
                (s.property?.nameEn || "").toLowerCase().includes(q) ||
                (s.property?.nameAr || "").toLowerCase().includes(q)
            );
        })
        : staff;

    const filteredStaff = filterPropertyId
        ? searchedStaff.filter((s) => s.property?.id === filterPropertyId)
        : searchedStaff;

    const totalSalary = filteredStaff.reduce((sum, s) => sum + (s.monthlySalary || 0), 0);

    const paginatedStaff = filteredStaff.slice((currentPage - 1) * itemsPerPage, currentPage * itemsPerPage);

    const reload = () => {
        setLoadError(null);
        setLoading(true);
        fetchStaff();
        fetchProperties();
        fetchAccounts();
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
                        <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-muted" />
                        <input
                            type="text"
                            placeholder="Search..."
                            value={searchQuery}
                            onChange={(e) => { setSearchQuery(e.target.value); setCurrentPage(1); }}
                            className="pl-9 pr-4 py-2 bg-surface border border-border rounded-lg text-sm text-foreground placeholder:text-muted/50 focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none w-64 transition-all"
                        />
                    </div>
                    <button
                        onClick={openAddModal}
                        className="bg-primary text-primary-foreground px-4 py-2 rounded-lg text-xs font-semibold hover:bg-primary/90 transition-all cursor-pointer flex items-center gap-2"
                    >
                        <Plus size={14} />
                        {t("addStaff")}
                    </button>
                </div>
            </div>

            {/* Summary Cards */}
            {!loading && staff.length > 0 && (
                <div className="grid grid-cols-2 gap-4 mb-8">
                    <div className="bg-surface border border-border rounded-xl p-5 flex items-center gap-4 hover:shadow-md transition-all duration-200">
                        <div className="w-12 h-12 rounded-xl bg-primary/10 flex items-center justify-center">
                            <Users size={20} className="text-primary" />
                        </div>
                        <div>
                            <div className="text-lg font-bold text-foreground">
                                {filteredStaff.length}
                            </div>
                            <div className="text-xs font-semibold text-muted uppercase tracking-wider">
                                {t("staffCount")}
                            </div>
                        </div>
                    </div>
                    <div className="bg-surface border border-border rounded-xl p-5 flex items-center gap-4 hover:shadow-md transition-all duration-200">
                        <div className="w-12 h-12 rounded-xl bg-primary/10 flex items-center justify-center">
                            <Banknote size={20} className="text-primary" />
                        </div>
                        <div>
                            <div className="text-lg font-bold text-foreground tabular-nums">
                                {formatCurrencyCompact(totalSalary)}
                            </div>
                            <div className="text-xs font-semibold text-muted uppercase tracking-wider">
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
                        className="bg-input border border-border p-2.5 rounded-lg text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none"
                        value={filterPropertyId}
                        onChange={(ev) => { setFilterPropertyId(ev.target.value); setCurrentPage(1); }}
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
                        <div key={i} className="bg-input rounded-xl h-16" />
                    ))}
                </div>
            )}

            {/* Empty State */}
            {!loading && filteredStaff.length === 0 && (
                <div className="text-center py-24 bg-background border border-dashed border-border rounded-xl flex flex-col items-center">
                    <div className="w-16 h-16 bg-surface rounded-xl flex items-center justify-center text-muted shadow-sm mb-6">
                        <UserCog size={28} />
                    </div>
                    <h3 className="text-sm font-bold text-foreground mb-1">
                        {t("noStaffFound")}
                    </h3>
                    <p className="text-xs text-muted font-medium">
                        {t("addStaffDesc")}
                    </p>
                </div>
            )}

            {/* Staff Table */}
            {!loading && filteredStaff.length > 0 && (
                <div className="bg-surface border border-border rounded-xl overflow-hidden">
                    <div className="overflow-x-auto">
                        <table className="w-full">
                            <thead>
                                <tr className="bg-input/50">
                                    <th className="text-left px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">
                                        {locale === "ar" ? t("nameAr") : t("nameEn")}
                                    </th>
                                    <th className="text-left px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">
                                        {t("employeeId")}
                                    </th>
                                    <th className="text-left px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">
                                        {t("designation")}
                                    </th>
                                    <th className="text-left px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">
                                        {t("property")}
                                    </th>
                                    <th className="text-left px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">
                                        {t("monthlySalary")}
                                    </th>
                                    <th className="text-left px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">
                                        Status
                                    </th>
                                    <th className="text-left px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">
                                        Actions
                                    </th>
                                </tr>
                            </thead>
                            <tbody>
                                {paginatedStaff.map((member) => (
                                    <tr
                                        key={member.id}
                                        className="border-b border-border hover:bg-input/30 transition-colors"
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
                                        <td className="px-5 py-3 text-xs text-foreground font-medium tabular-nums">
                                            {member.monthlySalary
                                                ? formatCurrency(member.monthlySalary)
                                                : "\u2014"}
                                        </td>
                                        <td className="px-5 py-3">
                                            {member.active ? (
                                                <span className="bg-success/10 text-success px-2.5 py-1 rounded-lg text-[10px] font-bold border border-success/20">
                                                    {t("active")}
                                                </span>
                                            ) : (
                                                <span className="bg-input text-muted px-2.5 py-1 rounded-lg text-[10px] font-bold border border-border">
                                                    {t("inactive")}
                                                </span>
                                            )}
                                        </td>
                                        <td className="px-5 py-3">
                                            <div className="flex items-center gap-2">
                                                <button
                                                    onClick={() => openEditModal(member)}
                                                    className="p-1.5 text-muted hover:text-primary rounded-lg hover:bg-primary/5 transition-all cursor-pointer"
                                                    aria-label={t("editStaff")}
                                                >
                                                    <Pencil size={14} />
                                                </button>
                                                <button
                                                    onClick={() => handleDelete(member)}
                                                    className="p-1.5 text-muted hover:text-error rounded-lg hover:bg-error/10 transition-all cursor-pointer"
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
                    <div className="px-5 pb-4">
                        <Pagination
                            currentPage={currentPage}
                            totalItems={filteredStaff.length}
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
                                setEditingStaff(null);
                            }}
                            className="absolute right-6 top-6 p-2 text-muted hover:text-foreground cursor-pointer transition-all duration-200 rounded-lg"
                            aria-label="Close"
                        >
                            <X size={18} />
                        </button>
                        <h2 className="text-lg font-bold text-foreground mb-1">
                            {editingStaff ? t("editStaff") : t("addStaff")}
                        </h2>
                        <p className="text-xs text-muted mb-6">
                            {t("description")}
                        </p>

                        <form onSubmit={handleSubmit} className="grid grid-cols-2 gap-5">
                            <div>
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5 ml-1">
                                    {t("nameEn")}
                                </label>
                                <input
                                    required
                                    className="w-full bg-input border border-border p-3 rounded-lg text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none"
                                    value={formData.nameEn}
                                    onChange={(ev) =>
                                        setFormData({ ...formData, nameEn: ev.target.value })
                                    }
                                />
                            </div>
                            <div>
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5 ml-1">
                                    {t("nameAr")}
                                </label>
                                <input
                                    className="w-full bg-input border border-border p-3 rounded-lg text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none"
                                    value={formData.nameAr}
                                    onChange={(ev) =>
                                        setFormData({ ...formData, nameAr: ev.target.value })
                                    }
                                    dir="rtl"
                                />
                            </div>
                            <div>
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5 ml-1">
                                    {t("employeeId")}
                                </label>
                                <input
                                    className="w-full bg-input border border-border p-3 rounded-lg text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none"
                                    value={formData.employeeId}
                                    onChange={(ev) =>
                                        setFormData({ ...formData, employeeId: ev.target.value })
                                    }
                                />
                            </div>
                            <div>
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5 ml-1">
                                    {t("designation")}
                                </label>
                                <input
                                    className="w-full bg-input border border-border p-3 rounded-lg text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none"
                                    value={formData.designation}
                                    onChange={(ev) =>
                                        setFormData({ ...formData, designation: ev.target.value })
                                    }
                                />
                            </div>
                            <div>
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5 ml-1">
                                    {t("department")}
                                </label>
                                <input
                                    className="w-full bg-input border border-border p-3 rounded-lg text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none"
                                    value={formData.department}
                                    onChange={(ev) =>
                                        setFormData({ ...formData, department: ev.target.value })
                                    }
                                />
                            </div>
                            <div>
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5 ml-1">
                                    {t("monthlySalary")}
                                </label>
                                <NumberInput
                                    step="0.01"
                                    min="0"
                                    className="w-full bg-input border border-border p-3 rounded-lg text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none"
                                    value={formData.monthlySalary}
                                    onChange={(v) =>
                                        setFormData({ ...formData, monthlySalary: v })
                                    }
                                />
                            </div>
                            <div>
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5 ml-1">
                                    {t("joinDate")}
                                </label>
                                <input
                                    type="date"
                                    className="w-full bg-input border border-border p-3 rounded-lg text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none"
                                    value={formData.joinDate}
                                    onChange={(ev) =>
                                        setFormData({ ...formData, joinDate: ev.target.value })
                                    }
                                />
                            </div>
                            <div>
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5 ml-1">
                                    {t("phone")}
                                </label>
                                <input
                                    className="w-full bg-input border border-border p-3 rounded-lg text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none"
                                    value={formData.phone}
                                    onChange={(ev) =>
                                        setFormData({ ...formData, phone: ev.target.value })
                                    }
                                />
                            </div>
                            <div>
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5 ml-1">
                                    {t("emiratesId")}
                                </label>
                                <input
                                    className="w-full bg-input border border-border p-3 rounded-lg text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none"
                                    value={formData.emiratesId}
                                    onChange={(ev) =>
                                        setFormData({ ...formData, emiratesId: ev.target.value })
                                    }
                                />
                            </div>
                            <div>
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5 ml-1">
                                    {t("passportNumber")}
                                </label>
                                <input
                                    className="w-full bg-input border border-border p-3 rounded-lg text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none"
                                    value={formData.passportNumber}
                                    onChange={(ev) =>
                                        setFormData({ ...formData, passportNumber: ev.target.value })
                                    }
                                />
                            </div>
                            <div>
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5 ml-1">
                                    {t("property")}
                                </label>
                                <select
                                    className="w-full bg-input border border-border p-3 rounded-lg text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none"
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
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5 ml-1">
                                    {t("salaryAccount")}
                                </label>
                                <select
                                    className="w-full bg-input border border-border p-3 rounded-lg text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none"
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
                                        className="rounded border-border"
                                        checked={formData.active}
                                        onChange={(ev) =>
                                            setFormData({ ...formData, active: ev.target.checked })
                                        }
                                    />
                                    <span className="text-xs font-bold text-foreground">
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
                                    className="bg-surface text-foreground border border-border px-4 py-2 rounded-lg text-xs font-semibold hover:bg-input transition-all cursor-pointer"
                                >
                                    Cancel
                                </button>
                                <button
                                    type="submit"
                                    disabled={submitting}
                                    className="bg-primary text-primary-foreground px-4 py-2 rounded-lg text-xs font-semibold hover:bg-primary/90 transition-all cursor-pointer disabled:opacity-50 flex items-center gap-2"
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

            <ConfirmDialog
                isOpen={confirmDialog !== null}
                onClose={() => setConfirmDialog(null)}
                onConfirm={confirmDialog?.onConfirm || (() => {})}
                title={confirmDialog?.title || ""}
                description={confirmDialog?.description}
                confirmText={confirmDialog?.confirmText || "Confirm"}
                isDestructive={confirmDialog?.isDestructive || false}
            />
        </div>
    );
}
