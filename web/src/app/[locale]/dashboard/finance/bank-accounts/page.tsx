"use client";

import { useState, useEffect } from "react";
import { useTranslations, useLocale } from "next-intl";
import { Landmark, Plus, Pencil, Trash2, X, Loader2 } from "lucide-react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { accountName } from "@/lib/api/ledger";

type Account = {
    id: string;
    code: string;
    name: string;
    nameAr?: string | null;
    accountType: string;
    accountSubType?: string;
    group?: boolean;
    active?: boolean;
};

/**
 * A bank account posts to a bank ledger account, so the picker offers only
 * active BANK-subtype leaves: never a group, a receivable, a PDC account or a
 * deactivated bank leaf (gap #66). The backend refuses anything else with a 400.
 */
const isBankLeaf = (a: Account) => a.accountSubType === "BANK" && !a.group && a.active !== false;

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
    const [formError, setFormError] = useState<string | null>(null);
    const [pageError, setPageError] = useState<string | null>(null);
    const [confirmDialog, setConfirmDialog] = useState<{
        title: string;
        description: string;
        confirmText: string;
        isDestructive: boolean;
        onConfirm: () => void;
    } | null>(null);

    useEffect(() => {
        fetchBankAccounts();
        fetchAccounts();
        fetchProperties();
    }, []);

    const fetchBankAccounts = async () => {
        try {
            const res = await fetch("/api/proxy/v1/bank-accounts");
            if (res.ok) {
                const data = await res.json();
                data.sort((a: any, b: any) => (a.id || '').localeCompare(b.id || ''));
                setBankAccounts(data);
            }
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
                setAccounts(data.filter(isBankLeaf));
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
        setFormError(null);
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
                bankName: formData.bankName,
                accountNumber: formData.accountNumber,
                iban: formData.iban,
                branchName: formData.branchName,
                currency: formData.currency,
                isDefault: formData.isDefault,
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
            } else {
                const errData = await res.json().catch(() => null);
                setFormError(errData?.message || t("saveFailed"));
            }
        } catch (err) {
            console.error(err);
            setFormError(t("saveFailed"));
        } finally {
            setSubmitting(false);
        }
    };

    const handleDelete = (ba: BankAccount) => {
        setConfirmDialog({
            title: t("deleteAccount"),
            description: t("confirmDelete"),
            confirmText: t("delete"),
            isDestructive: true,
            onConfirm: async () => {
                setConfirmDialog(null);
                setPageError(null);
                try {
                    const res = await fetch(`/api/proxy/v1/bank-accounts/${ba.id}`, {
                        method: "DELETE",
                    });
                    if (res.ok) {
                        fetchBankAccounts();
                    } else {
                        const errData = await res.json().catch(() => null);
                        setPageError(errData?.message || t("deleteFailed"));
                    }
                } catch (err) {
                    console.error(err);
                    setPageError(t("deleteFailed"));
                }
            },
        });
    };

    const legacyLink =
        editingAccount?.coaAccount && !accounts.some((a) => a.id === editingAccount.coaAccount?.id)
            ? editingAccount.coaAccount
            : null;

    const propertyName = (p: Property | null) => {
        if (!p) return "\u2014";
        return locale === "ar" ? p.nameAr || p.nameEn : p.nameEn || p.nameAr;
    };

    return (
        <div>
            {/* Header */}
            <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 mb-10">
                <div>
                    <h1 className="text-xl font-bold text-foreground tracking-tight mb-1 flex items-center gap-2">
                        <Landmark size={20} className="text-primary" />
                        {t("title")}
                    </h1>
                    <p className="text-xs text-muted font-medium">
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

            {/* Page-level errors (e.g. failed delete) */}
            {pageError && (
                <div
                    role="alert"
                    className="mb-6 bg-error/10 border border-error/20 text-error text-xs font-medium rounded-lg p-3"
                >
                    {pageError}
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
            {!loading && bankAccounts.length === 0 && (
                <div className="text-center py-24 bg-background border border-dashed border-border rounded-xl flex flex-col items-center">
                    <div className="w-16 h-16 bg-surface rounded-xl flex items-center justify-center text-muted shadow-sm mb-6">
                        <Landmark size={28} />
                    </div>
                    <h3 className="text-sm font-bold text-foreground mb-1">
                        {t("noBankAccounts")}
                    </h3>
                    <p className="text-xs text-muted font-medium">
                        {t("addBankAccountDesc")}
                    </p>
                </div>
            )}

            {/* Bank Accounts Table */}
            {!loading && bankAccounts.length > 0 && (
                <div className="bg-surface border border-border rounded-xl overflow-hidden shadow-sm">
                    <div className="overflow-x-auto">
                        <table className="w-full">
                            <thead>
                                <tr className="bg-input/70">
                                    <th className="text-start px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">
                                        {t("bankName")}
                                    </th>
                                    <th className="text-start px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">
                                        {t("accountNumber")}
                                    </th>
                                    <th className="text-start px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">
                                        {t("iban")}
                                    </th>
                                    <th className="text-start px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">
                                        {t("branchName")}
                                    </th>
                                    <th className="text-start px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">
                                        {t("property")}
                                    </th>
                                    <th className="text-start px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">
                                        {t("coaAccount")}
                                    </th>
                                    <th className="text-start px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">
                                        {t("isDefault")}
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
                                {bankAccounts.map((ba) => (
                                    <tr
                                        key={ba.id}
                                        className="hover:bg-input/30 transition-colors"
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
                                                ? `${ba.coaAccount.code} - ${accountName(ba.coaAccount, locale)}`
                                                : "\u2014"}
                                        </td>
                                        <td className="px-5 py-3">
                                            {ba.isDefault && (
                                                <span className="bg-info/10 text-info border border-info/20 px-2.5 py-1 rounded-full text-[10px] font-bold">
                                                    {t("isDefault")}
                                                </span>
                                            )}
                                        </td>
                                        <td className="px-5 py-3">
                                            {ba.active ? (
                                                <span className="bg-success/10 text-success border border-success/20 px-2.5 py-1 rounded-full text-[10px] font-bold">
                                                    {t("active")}
                                                </span>
                                            ) : (
                                                <span className="bg-input text-muted border border-border px-2.5 py-1 rounded-full text-[10px] font-bold">
                                                    {t("inactive")}
                                                </span>
                                            )}
                                        </td>
                                        <td className="px-5 py-3">
                                            <div className="flex items-center gap-2">
                                                <button
                                                    onClick={() => openEditModal(ba)}
                                                    className="p-1.5 text-muted hover:text-primary rounded-lg hover:bg-primary/5 transition-all cursor-pointer"
                                                    aria-label={t("editAccount")}
                                                >
                                                    <Pencil size={14} />
                                                </button>
                                                <button
                                                    onClick={() => handleDelete(ba)}
                                                    className="p-1.5 text-muted hover:text-error rounded-lg hover:bg-error/10 transition-all cursor-pointer"
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
                    <div className="bg-surface rounded-xl p-8 w-full max-w-2xl shadow-2xl border border-border relative max-h-[80vh] overflow-y-auto">
                        <button
                            onClick={() => {
                                setShowModal(false);
                                setEditingAccount(null);
                            }}
                            className="absolute end-6 top-6 p-2 text-muted hover:text-foreground cursor-pointer transition-all duration-200 rounded-lg"
                            aria-label={t("close")}
                        >
                            <X size={18} />
                        </button>
                        <h2 className="text-lg font-bold text-foreground mb-1">
                            {editingAccount ? t("editAccount") : t("addAccount")}
                        </h2>
                        <p className="text-xs text-muted mb-6">
                            {t("description")}
                        </p>

                        <form onSubmit={handleSubmit} className="grid grid-cols-2 gap-5">
                            <div>
                                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ms-1">
                                    {t("bankName")}
                                </label>
                                <input
                                    required
                                    className="w-full border border-border rounded-lg bg-surface p-3 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none"
                                    value={formData.bankName}
                                    onChange={(ev) =>
                                        setFormData({ ...formData, bankName: ev.target.value })
                                    }
                                />
                            </div>
                            <div>
                                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ms-1">
                                    {t("accountNumber")}
                                </label>
                                <input
                                    className="w-full border border-border rounded-lg bg-surface p-3 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none"
                                    value={formData.accountNumber}
                                    onChange={(ev) =>
                                        setFormData({ ...formData, accountNumber: ev.target.value })
                                    }
                                />
                            </div>
                            <div>
                                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ms-1">
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
                            <div>
                                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ms-1">
                                    {t("branchName")}
                                </label>
                                <input
                                    className="w-full border border-border rounded-lg bg-surface p-3 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none"
                                    value={formData.branchName}
                                    onChange={(ev) =>
                                        setFormData({ ...formData, branchName: ev.target.value })
                                    }
                                />
                            </div>
                            <div>
                                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ms-1">
                                    {t("currency")}
                                </label>
                                <input
                                    className="w-full border border-border rounded-lg bg-surface p-3 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none"
                                    value={formData.currency}
                                    onChange={(ev) =>
                                        setFormData({ ...formData, currency: ev.target.value })
                                    }
                                />
                            </div>
                            <div>
                                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ms-1">
                                    {t("property")}
                                </label>
                                <select
                                    className="w-full border border-border rounded-lg bg-surface p-3 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none"
                                    value={formData.propertyId}
                                    onChange={(ev) =>
                                        setFormData({ ...formData, propertyId: ev.target.value })
                                    }
                                >
                                    <option value="">{t("selectProperty")}</option>
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
                                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ms-1">
                                    {t("coaAccount")}
                                </label>
                                <select
                                    className="w-full border border-border rounded-lg bg-surface p-3 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none"
                                    value={formData.coaAccountId}
                                    onChange={(ev) =>
                                        setFormData({ ...formData, coaAccountId: ev.target.value })
                                    }
                                >
                                    <option value="">{t("selectAccount")}</option>
                                    {/*
                                      * Keep a row's current link visible on edit even if it predates
                                      * the filter (linked under the old ASSET-wide picker, or since
                                      * deactivated). Saving it unchanged is accepted — the server only
                                      * validates a change of link — but it is marked so the operator
                                      * knows it is not a bank account (PR #340 review I-2).
                                      */}
                                    {legacyLink && (
                                        <option value={legacyLink.id} data-testid="bank-account-legacy-link">
                                            {legacyLink.code} - {accountName(legacyLink, locale)} {t("legacyLinkSuffix")}
                                        </option>
                                    )}
                                    {accounts.map((a) => (
                                        <option key={a.id} value={a.id}>
                                            {a.code} - {accountName(a, locale)}
                                        </option>
                                    ))}
                                </select>
                                {legacyLink && formData.coaAccountId === legacyLink.id && (
                                    <p className="text-[11px] text-warning mt-1.5 ms-1" data-testid="bank-account-legacy-link-hint">
                                        {t("legacyLinkHint")}
                                    </p>
                                )}
                            </div>
                            <div className="flex items-center gap-3 pt-5">
                                <label className="flex items-center gap-2 cursor-pointer">
                                    <input
                                        type="checkbox"
                                        className="rounded border-border"
                                        checked={formData.isDefault}
                                        onChange={(ev) =>
                                            setFormData({
                                                ...formData,
                                                isDefault: ev.target.checked,
                                            })
                                        }
                                    />
                                    <span className="text-xs font-bold text-muted">
                                        {t("isDefault")}
                                    </span>
                                </label>
                            </div>
                            {formError && (
                                <div
                                    role="alert"
                                    className="col-span-2 bg-error/10 border border-error/20 text-error text-xs font-medium rounded-lg p-3"
                                >
                                    {formError}
                                </div>
                            )}
                            <div className="col-span-2 flex justify-end gap-3 mt-2">
                                <button
                                    type="button"
                                    onClick={() => {
                                        setShowModal(false);
                                        setEditingAccount(null);
                                    }}
                                    className="px-6 py-3 bg-input text-muted border border-border rounded-xl text-xs font-bold"
                                >
                                    {t("cancel")}
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
