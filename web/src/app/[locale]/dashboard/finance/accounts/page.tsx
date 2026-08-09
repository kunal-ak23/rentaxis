"use client";

import { useState, useEffect, useCallback, useRef } from "react";
import { useTranslations } from "next-intl";
import { useLocale } from "next-intl";
import {
    BookOpen, Plus, X, ChevronRight, ChevronDown,
    Pencil, Trash2, Upload, List, GitBranch,
    Loader2, FileSpreadsheet, Sparkles
} from "lucide-react";
import { cn } from "@/lib/utils";

type AccountType = "ASSET" | "LIABILITY" | "INCOME" | "EXPENSE" | "EQUITY";

type AccountSubType =
    | "FIXED_ASSET" | "BANK" | "CASH" | "RECEIVABLE" | "PDC_RECEIVABLE" | "OTHER_ASSET"
    | "PAYABLE" | "ADVANCE" | "DEPOSIT_HELD" | "PDC_PAYABLE" | "OTHER_LIABILITY"
    | "RENTAL_INCOME" | "OTHER_INCOME"
    | "DIRECT_EXPENSE" | "INDIRECT_EXPENSE" | "SALARY_EXPENSE"
    | "CAPITAL" | "RETAINED_EARNINGS";

type Account = {
    id: string;
    code: string;
    name: string;
    nameEn: string | null;
    nameAr: string | null;
    accountType: AccountType;
    accountSubType: AccountSubType | null;
    parentCode: string | null;
    description: string | null;
    system: boolean;
    group: boolean;
    active: boolean;
    hierarchyLevel: number;
    displayOrder: number;
};

const TYPE_ORDER: AccountType[] = ["ASSET", "LIABILITY", "INCOME", "EXPENSE", "EQUITY"];

const TYPE_BADGE: Record<AccountType, string> = {
    ASSET: "bg-emerald-50 text-emerald-700",
    LIABILITY: "bg-rose-50 text-rose-700",
    INCOME: "bg-blue-50 text-blue-700",
    EXPENSE: "bg-amber-50 text-amber-700",
    EQUITY: "bg-violet-50 text-violet-700",
};

const SUB_TYPES_BY_TYPE: Record<AccountType, AccountSubType[]> = {
    ASSET: ["FIXED_ASSET", "BANK", "CASH", "RECEIVABLE", "PDC_RECEIVABLE", "OTHER_ASSET"],
    LIABILITY: ["PAYABLE", "ADVANCE", "DEPOSIT_HELD", "PDC_PAYABLE", "OTHER_LIABILITY"],
    INCOME: ["RENTAL_INCOME", "OTHER_INCOME"],
    EXPENSE: ["DIRECT_EXPENSE", "INDIRECT_EXPENSE", "SALARY_EXPENSE"],
    EQUITY: ["CAPITAL", "RETAINED_EARNINGS"],
};

const EMPTY_FORM = {
    code: "",
    nameEn: "",
    nameAr: "",
    accountType: "ASSET" as AccountType,
    accountSubType: "" as string,
    parentCode: "",
    description: "",
    group: false,
};

export default function AccountsPage() {
    const t = useTranslations("Finance");
    const locale = useLocale();
    const isAr = locale === "ar";

    const [accounts, setAccounts] = useState<Account[]>([]);
    const [loading, setLoading] = useState(true);
    const [submitting, setSubmitting] = useState(false);
    const [seeding, setSeeding] = useState(false);

    // View & filters
    const [viewMode, setViewMode] = useState<"tree" | "flat">("tree");
    const [filterType, setFilterType] = useState<AccountType | "">("");
    const [activeOnly, setActiveOnly] = useState(false);

    // Modals
    const [showAddModal, setShowAddModal] = useState(false);
    const [showEditModal, setShowEditModal] = useState(false);
    const [showImportModal, setShowImportModal] = useState(false);
    const [showDeleteConfirm, setShowDeleteConfirm] = useState<string | null>(null);

    // Forms
    const [formData, setFormData] = useState(EMPTY_FORM);
    const [editId, setEditId] = useState<string | null>(null);
    // Backend {message} surfaced inside the open modal (add/edit/import)…
    const [formError, setFormError] = useState<string | null>(null);
    // …and at page level for modal-less actions (delete, seed).
    const [pageError, setPageError] = useState<string | null>(null);

    // Tree expand state
    const [expandedCodes, setExpandedCodes] = useState<Set<string>>(new Set());
    // Flat view expand state
    const [expandedTypes, setExpandedTypes] = useState<Set<string>>(new Set(TYPE_ORDER));

    // Import
    const [importFile, setImportFile] = useState<File | null>(null);
    const [importing, setImporting] = useState(false);
    const [dragOver, setDragOver] = useState(false);
    const fileInputRef = useRef<HTMLInputElement>(null);

    const fetchAccounts = useCallback(async () => {
        try {
            const res = await fetch("/api/proxy/v1/finance/accounts");
            if (res.ok) {
                const data = await res.json();
                setAccounts(data);
            }
        } catch (err) {
            console.error(err);
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => {
        fetchAccounts();
    }, [fetchAccounts]);

    const displayName = (a: Account) => {
        if (isAr) return a.nameAr || a.name;
        return a.nameEn || a.name;
    };

    // ── Filtering ──
    const filtered = accounts.filter(a => {
        if (filterType && a.accountType !== filterType) return false;
        if (activeOnly && !a.active) return false;
        return true;
    });

    // ── Tree helpers ──
    const buildTree = () => {
        const sorted = [...filtered].sort((a, b) => {
            const ti = TYPE_ORDER.indexOf(a.accountType) - TYPE_ORDER.indexOf(b.accountType);
            if (ti !== 0) return ti;
            if (a.displayOrder !== b.displayOrder) return a.displayOrder - b.displayOrder;
            return a.code.localeCompare(b.code);
        });

        const childrenMap: Record<string, Account[]> = {};
        const roots: Account[] = [];

        for (const acc of sorted) {
            if (acc.parentCode) {
                if (!childrenMap[acc.parentCode]) childrenMap[acc.parentCode] = [];
                childrenMap[acc.parentCode].push(acc);
            } else {
                roots.push(acc);
            }
        }

        return { roots, childrenMap };
    };

    const toggleExpand = (code: string) => {
        setExpandedCodes(prev => {
            const next = new Set(prev);
            if (next.has(code)) next.delete(code);
            else next.add(code);
            return next;
        });
    };

    const toggleType = (type: string) => {
        setExpandedTypes(prev => {
            const next = new Set(prev);
            if (next.has(type)) next.delete(type);
            else next.add(type);
            return next;
        });
    };

    const expandAll = () => {
        const codes = new Set(accounts.filter(a => a.group).map(a => a.code));
        setExpandedCodes(codes);
    };

    // ── Flat grouping ──
    const groupedByType: Record<string, Account[]> = {};
    for (const a of filtered) {
        if (!groupedByType[a.accountType]) groupedByType[a.accountType] = [];
        groupedByType[a.accountType].push(a);
    }

    // ── CRUD ──
    const handleSeedDefaults = async () => {
        setSeeding(true);
        setPageError(null);
        try {
            const res = await fetch("/api/proxy/v1/finance/accounts/seed", { method: "POST" });
            if (res.ok) {
                await fetchAccounts();
                expandAll();
            } else {
                const errData = await res.json().catch(() => null);
                setPageError(errData?.message || "Failed to seed default accounts");
            }
        } catch (err) {
            console.error(err);
            setPageError("Failed to seed default accounts");
        } finally {
            setSeeding(false);
        }
    };

    const handleCreate = async (ev: React.FormEvent) => {
        ev.preventDefault();
        setSubmitting(true);
        setFormError(null);
        try {
            const body: Record<string, unknown> = {
                code: formData.code,
                nameEn: formData.nameEn,
                nameAr: formData.nameAr,
                name: formData.nameEn,
                accountType: formData.accountType,
                description: formData.description || null,
                parentCode: formData.parentCode || null,
                group: formData.group,
            };
            if (formData.accountSubType) body.accountSubType = formData.accountSubType;

            const res = await fetch("/api/proxy/v1/finance/accounts", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(body),
            });
            if (res.ok) {
                setShowAddModal(false);
                setFormData(EMPTY_FORM);
                fetchAccounts();
            } else {
                const errData = await res.json().catch(() => null);
                setFormError(errData?.message || "Failed to create account");
            }
        } catch (err) {
            console.error(err);
            setFormError("Failed to create account");
        } finally {
            setSubmitting(false);
        }
    };

    const handleUpdate = async (ev: React.FormEvent) => {
        ev.preventDefault();
        if (!editId) return;
        setSubmitting(true);
        setFormError(null);
        try {
            // Only the fields AccountService.updateAccount actually applies.
            // code/accountType/parentCode/group are immutable after creation
            // (the backend ignores them) and are disabled in the edit form.
            // active/displayOrder are passed through unchanged — omitting them
            // would reset the stored values to the deserialized defaults.
            const current = accounts.find(a => a.id === editId);
            const body: Record<string, unknown> = {
                nameEn: formData.nameEn,
                nameAr: formData.nameAr,
                name: formData.nameEn,
                description: formData.description || null,
                accountSubType: formData.accountSubType || null,
                active: current?.active ?? true,
                displayOrder: current?.displayOrder ?? 0,
            };

            const res = await fetch(`/api/proxy/v1/finance/accounts/${editId}`, {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(body),
            });
            if (res.ok) {
                setShowEditModal(false);
                setEditId(null);
                setFormData(EMPTY_FORM);
                fetchAccounts();
            } else {
                const errData = await res.json().catch(() => null);
                setFormError(errData?.message || "Failed to update account");
            }
        } catch (err) {
            console.error(err);
            setFormError("Failed to update account");
        } finally {
            setSubmitting(false);
        }
    };

    const handleDelete = async (id: string) => {
        setPageError(null);
        try {
            const res = await fetch(`/api/proxy/v1/finance/accounts/${id}`, { method: "DELETE" });
            if (res.ok) {
                setShowDeleteConfirm(null);
                fetchAccounts();
            } else {
                const errData = await res.json().catch(() => null);
                setShowDeleteConfirm(null);
                setPageError(errData?.message || "Failed to delete account");
            }
        } catch (err) {
            console.error(err);
            setShowDeleteConfirm(null);
            setPageError("Failed to delete account");
        }
    };

    const openEdit = (account: Account) => {
        setFormData({
            code: account.code,
            nameEn: account.nameEn || account.name,
            nameAr: account.nameAr || "",
            accountType: account.accountType,
            accountSubType: account.accountSubType || "",
            parentCode: account.parentCode || "",
            description: account.description || "",
            group: account.group,
        });
        setEditId(account.id);
        setFormError(null);
        setShowEditModal(true);
    };

    const handleImport = async () => {
        if (!importFile) return;
        setImporting(true);
        setFormError(null);
        try {
            const fd = new FormData();
            fd.append("file", importFile);
            const res = await fetch("/api/proxy/v1/finance/accounts/import", {
                method: "POST",
                body: fd,
            });
            if (res.ok) {
                setShowImportModal(false);
                setImportFile(null);
                fetchAccounts();
            } else {
                const errData = await res.json().catch(() => null);
                setFormError(errData?.message || "Failed to import accounts");
            }
        } catch (err) {
            console.error(err);
            setFormError("Failed to import accounts");
        } finally {
            setImporting(false);
        }
    };

    const handleDrop = (ev: React.DragEvent) => {
        ev.preventDefault();
        setDragOver(false);
        const file = ev.dataTransfer.files?.[0];
        if (file) setImportFile(file);
    };

    // ── Account form (shared between add/edit) ──
    // In edit mode code/accountType/parentCode/group are disabled: the backend's
    // updateAccount deliberately ignores them, so offering editable inputs would
    // silently drop the changes.
    const renderAccountForm = (onSubmit: (ev: React.FormEvent) => void, title: string, isEdit = false) => (
        <form onSubmit={onSubmit} className="grid grid-cols-2 gap-5">
            <div className="col-span-1">
                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">{t("code")}</label>
                <input
                    required
                    disabled={isEdit}
                    placeholder="e.g. A-01"
                    className="w-full border border-border rounded-lg bg-surface p-3 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200 disabled:opacity-60 disabled:cursor-not-allowed"
                    value={formData.code}
                    onChange={ev => setFormData({ ...formData, code: ev.target.value })}
                />
            </div>
            <div className="col-span-1">
                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">{t("accountType")}</label>
                <select
                    disabled={isEdit}
                    className="w-full border border-border rounded-lg bg-surface p-3 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200 disabled:opacity-60 disabled:cursor-not-allowed"
                    value={formData.accountType}
                    onChange={ev => setFormData({ ...formData, accountType: ev.target.value as AccountType, accountSubType: "" })}
                >
                    {TYPE_ORDER.map(type => <option key={type} value={type}>{type}</option>)}
                </select>
            </div>
            <div className="col-span-1">
                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">{t("nameEn")}</label>
                <input
                    required
                    placeholder="Account name in English"
                    className="w-full border border-border rounded-lg bg-surface p-3 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200"
                    value={formData.nameEn}
                    onChange={ev => setFormData({ ...formData, nameEn: ev.target.value })}
                />
            </div>
            <div className="col-span-1">
                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">{t("nameAr")}</label>
                <input
                    placeholder="اسم الحساب بالعربي"
                    dir="rtl"
                    className="w-full border border-border rounded-lg bg-surface p-3 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200"
                    value={formData.nameAr}
                    onChange={ev => setFormData({ ...formData, nameAr: ev.target.value })}
                />
            </div>
            <div className="col-span-1">
                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">{t("subType")}</label>
                <select
                    className="w-full border border-border rounded-lg bg-surface p-3 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200"
                    value={formData.accountSubType}
                    onChange={ev => setFormData({ ...formData, accountSubType: ev.target.value })}
                >
                    <option value="">--</option>
                    {SUB_TYPES_BY_TYPE[formData.accountType]?.map(st => (
                        <option key={st} value={st}>{st.replace(/_/g, " ")}</option>
                    ))}
                </select>
            </div>
            <div className="col-span-1">
                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">{t("parent")}</label>
                <select
                    disabled={isEdit}
                    className="w-full border border-border rounded-lg bg-surface p-3 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200 disabled:opacity-60 disabled:cursor-not-allowed"
                    value={formData.parentCode}
                    onChange={ev => setFormData({ ...formData, parentCode: ev.target.value })}
                >
                    <option value="">-- None --</option>
                    {accounts
                        .filter(a => a.accountType === formData.accountType && a.code !== formData.code)
                        .map(a => (
                            <option key={a.id} value={a.code}>{a.code} - {displayName(a)}</option>
                        ))}
                </select>
            </div>
            <div className="col-span-2">
                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">{t("description")}</label>
                <textarea
                    placeholder="Optional description"
                    className="w-full border border-border rounded-lg bg-surface p-3 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200 h-20 resize-none"
                    value={formData.description}
                    onChange={ev => setFormData({ ...formData, description: ev.target.value })}
                />
            </div>
            <div className="col-span-2">
                <label className={cn("flex items-center gap-2", isEdit ? "opacity-60 cursor-not-allowed" : "cursor-pointer")}>
                    <input
                        type="checkbox"
                        disabled={isEdit}
                        className="rounded border-border"
                        checked={formData.group}
                        onChange={ev => setFormData({ ...formData, group: ev.target.checked })}
                    />
                    <span className="text-xs font-bold text-muted">{t("isGroup")}</span>
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
                        setShowAddModal(false);
                        setShowEditModal(false);
                        setFormData(EMPTY_FORM);
                        setEditId(null);
                    }}
                    className="px-6 py-3 bg-input text-muted rounded-lg text-xs font-bold cursor-pointer transition-all duration-200 focus:ring-2 focus:ring-primary/20 focus:outline-none"
                >
                    {t("cancel")}
                </button>
                <button
                    type="submit"
                    disabled={submitting}
                    className="px-8 py-3 bg-primary text-primary-foreground rounded-lg text-xs font-bold cursor-pointer transition-all duration-200 hover:bg-primary/90 focus:ring-2 focus:ring-primary/20 focus:outline-none disabled:opacity-50 flex items-center gap-2"
                >
                    {submitting && <Loader2 size={14} className="animate-spin" />}
                    {title === t("editAccount") ? t("accountUpdated").replace("Account updated", "Save") || "Save" : t("create")}
                </button>
            </div>
        </form>
    );

    // ── Tree row renderer (recursive) ──
    const renderTreeRow = (account: Account, childrenMap: Record<string, Account[]>, depth: number) => {
        const children = childrenMap[account.code] || [];
        const hasChildren = children.length > 0;
        const isExpanded = expandedCodes.has(account.code);

        return (
            <div key={account.id}>
                <div
                    className={cn(
                        "flex items-center justify-between px-5 py-3 hover:bg-input/30 transition-all duration-200 border-b border-border",
                        !account.active && "opacity-50"
                    )}
                    style={{ paddingLeft: `${20 + depth * 24}px` }}
                >
                    <div className="flex items-center gap-3 min-w-0 flex-1">
                        {/* Expand/collapse or spacer */}
                        {hasChildren || account.group ? (
                            <button
                                onClick={() => toggleExpand(account.code)}
                                className="p-0.5 text-muted hover:text-foreground cursor-pointer transition-all duration-200 focus:outline-none flex-shrink-0"
                            >
                                {isExpanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
                            </button>
                        ) : (
                            <span className="w-[18px] flex-shrink-0" />
                        )}

                        <span className={cn("text-[10px] font-bold uppercase tracking-widest px-2.5 py-1 rounded-lg flex-shrink-0", TYPE_BADGE[account.accountType])}>
                            {account.code}
                        </span>

                        <div className="min-w-0">
                            <p className={cn("text-xs font-bold text-foreground truncate", account.group && "font-bold")}>
                                {displayName(account)}
                            </p>
                            {account.description && (
                                <p className="text-[10px] text-muted mt-0.5 truncate">{account.description}</p>
                            )}
                        </div>
                    </div>

                    <div className="flex items-center gap-2 flex-shrink-0">
                        {account.accountSubType && (
                            <span className="text-[9px] font-bold text-muted uppercase hidden md:inline">
                                {account.accountSubType.replace(/_/g, " ")}
                            </span>
                        )}
                        {account.group && (
                            <span className="text-[8px] font-bold text-primary bg-primary/5 px-2 py-0.5 rounded-full uppercase tracking-wider">Group</span>
                        )}
                        {account.system && (
                            <span className="text-[8px] font-bold text-muted bg-input px-2 py-0.5 rounded-full uppercase tracking-wider">System</span>
                        )}
                        {!account.system && (
                            <div className="flex items-center gap-1 ml-2">
                                <button
                                    onClick={() => openEdit(account)}
                                    className="p-1.5 text-muted hover:text-primary cursor-pointer transition-all duration-200 rounded-lg hover:bg-primary/5 focus:outline-none"
                                    title={t("editAccount")}
                                >
                                    <Pencil size={13} />
                                </button>
                                <button
                                    onClick={() => setShowDeleteConfirm(account.id)}
                                    className="p-1.5 text-muted hover:text-rose-500 cursor-pointer transition-all duration-200 rounded-lg hover:bg-rose-50 focus:outline-none"
                                    title={t("deleteAccount")}
                                >
                                    <Trash2 size={13} />
                                </button>
                            </div>
                        )}
                    </div>
                </div>
                {isExpanded && children.map(child => renderTreeRow(child, childrenMap, depth + 1))}
            </div>
        );
    };

    const { roots, childrenMap } = buildTree();

    return (
        <div>
            {/* ── Page Header ── */}
            <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 mb-10">
                <div data-tour="accounts-header">
                    <h1 className="text-xl font-bold text-foreground tracking-tight mb-1 flex items-center gap-2">
                        <BookOpen size={20} className="text-primary" />
                        {t("chartOfAccounts")}
                    </h1>
                    <p className="text-xs text-muted font-medium">
                        {t("chartOfAccountsDesc")}
                    </p>
                </div>
                <div className="flex gap-3 flex-wrap">
                    {accounts.length === 0 && !loading && (
                        <button
                            onClick={handleSeedDefaults}
                            disabled={seeding}
                            className="flex items-center gap-2 bg-gradient-to-r from-primary to-blue-500 text-white px-5 py-2.5 rounded-full text-xs font-bold hover:opacity-90 transition-all duration-200 shadow-lg shadow-primary/20 active:scale-95 cursor-pointer focus:ring-2 focus:ring-primary/20 focus:outline-none disabled:opacity-50"
                        >
                            {seeding ? <Loader2 size={14} className="animate-spin" /> : <Sparkles size={14} />}
                            {t("seedDefaults")}
                        </button>
                    )}
                    <button
                        onClick={() => { setFormError(null); setShowImportModal(true); }}
                        className="flex items-center gap-2 bg-surface text-foreground border border-border px-5 py-2.5 rounded-full text-xs font-bold hover:bg-input transition-all duration-200 shadow-sm active:scale-95 cursor-pointer focus:ring-2 focus:ring-primary/20 focus:outline-none"
                    >
                        <Upload size={14} />
                        {t("importAccounts")}
                    </button>
                    <button
                        onClick={() => { setFormData(EMPTY_FORM); setFormError(null); setShowAddModal(true); }}
                        className="flex items-center gap-2 bg-primary text-primary-foreground px-5 py-2.5 rounded-full text-xs font-bold hover:opacity-90 transition-all duration-200 shadow-lg shadow-primary/10 active:scale-95 cursor-pointer focus:ring-2 focus:ring-primary/20 focus:outline-none"
                    >
                        <Plus size={14} />
                        {t("addAccount")}
                    </button>
                </div>
            </div>

            {/* ── Page-level errors (e.g. failed delete/seed) ── */}
            {pageError && (
                <div
                    role="alert"
                    className="mb-6 bg-error/10 border border-error/20 text-error text-xs font-medium rounded-lg p-3"
                >
                    {pageError}
                </div>
            )}

            {/* ── View Toggle + Filters Bar ── */}
            <div className="mb-6 bg-surface border border-border rounded-xl p-4 shadow-sm flex flex-col md:flex-row md:items-center gap-4">
                {/* View toggle */}
                <div className="flex bg-input rounded-full p-0.5">
                    <button
                        onClick={() => setViewMode("tree")}
                        className={cn(
                            "flex items-center gap-1.5 px-3.5 py-2 rounded-full text-[10px] font-bold uppercase tracking-wider transition-all duration-200 cursor-pointer",
                            viewMode === "tree"
                                ? "bg-surface text-foreground shadow-sm"
                                : "text-muted hover:text-foreground"
                        )}
                    >
                        <GitBranch size={12} />
                        {t("treeView")}
                    </button>
                    <button
                        onClick={() => setViewMode("flat")}
                        className={cn(
                            "flex items-center gap-1.5 px-3.5 py-2 rounded-full text-[10px] font-bold uppercase tracking-wider transition-all duration-200 cursor-pointer",
                            viewMode === "flat"
                                ? "bg-surface text-foreground shadow-sm"
                                : "text-muted hover:text-foreground"
                        )}
                    >
                        <List size={12} />
                        {t("flatView")}
                    </button>
                </div>

                {/* Account type filter */}
                <div>
                    <select
                        className="border border-border rounded-lg bg-surface p-2.5 text-xs cursor-pointer focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200"
                        value={filterType}
                        onChange={ev => setFilterType(ev.target.value as AccountType | "")}
                    >
                        <option value="">{t("allTypes")}</option>
                        {TYPE_ORDER.map(type => <option key={type} value={type}>{type}</option>)}
                    </select>
                </div>

                {/* Active only toggle */}
                <label className="flex items-center gap-2 cursor-pointer">
                    <input
                        type="checkbox"
                        className="rounded border-border"
                        checked={activeOnly}
                        onChange={ev => setActiveOnly(ev.target.checked)}
                    />
                    <span className="text-xs font-bold text-muted">{t("activeOnly")}</span>
                </label>

                <div className="ml-auto text-[10px] text-muted font-medium">
                    {filtered.length} accounts
                </div>
            </div>

            {/* ── Add Account Modal ── */}
            {showAddModal && (
                <div className="fixed inset-0 bg-gray-900/40 backdrop-blur-sm flex items-center justify-center p-4 z-[100]">
                    <div className="bg-surface rounded-xl p-8 max-w-xl w-full shadow-2xl border border-border relative max-h-[90vh] overflow-y-auto">
                        <button
                            onClick={() => { setShowAddModal(false); setFormData(EMPTY_FORM); }}
                            aria-label="Close modal"
                            className="absolute right-6 top-6 p-2 text-muted hover:text-foreground cursor-pointer transition-all duration-200 focus:ring-2 focus:ring-primary/20 focus:outline-none rounded-lg"
                        >
                            <X size={18} />
                        </button>
                        <h2 className="text-lg font-bold mb-1">{t("addAccount")}</h2>
                        <p className="text-xs text-muted mb-8 font-medium">Create a new account in the chart of accounts.</p>
                        {renderAccountForm(handleCreate, t("addAccount"))}
                    </div>
                </div>
            )}

            {/* ── Edit Account Modal ── */}
            {showEditModal && (
                <div className="fixed inset-0 bg-gray-900/40 backdrop-blur-sm flex items-center justify-center p-4 z-[100]">
                    <div className="bg-surface rounded-xl p-8 max-w-xl w-full shadow-2xl border border-border relative max-h-[90vh] overflow-y-auto">
                        <button
                            onClick={() => { setShowEditModal(false); setEditId(null); setFormData(EMPTY_FORM); }}
                            aria-label="Close modal"
                            className="absolute right-6 top-6 p-2 text-muted hover:text-foreground cursor-pointer transition-all duration-200 focus:ring-2 focus:ring-primary/20 focus:outline-none rounded-lg"
                        >
                            <X size={18} />
                        </button>
                        <h2 className="text-lg font-bold mb-1">{t("editAccount")}</h2>
                        <p className="text-xs text-muted mb-8 font-medium">Update the account details.</p>
                        {renderAccountForm(handleUpdate, t("editAccount"), true)}
                    </div>
                </div>
            )}

            {/* ── Import Modal ── */}
            {showImportModal && (
                <div className="fixed inset-0 bg-gray-900/40 backdrop-blur-sm flex items-center justify-center p-4 z-[100]">
                    <div className="bg-surface rounded-xl p-8 max-w-xl w-full shadow-2xl border border-border relative">
                        <button
                            onClick={() => { setShowImportModal(false); setImportFile(null); }}
                            aria-label="Close modal"
                            className="absolute right-6 top-6 p-2 text-muted hover:text-foreground cursor-pointer transition-all duration-200 focus:ring-2 focus:ring-primary/20 focus:outline-none rounded-lg"
                        >
                            <X size={18} />
                        </button>
                        <h2 className="text-lg font-bold mb-1">{t("importAccounts")}</h2>
                        <p className="text-xs text-muted mb-8 font-medium">{t("importFromFile")}</p>

                        <div
                            onDrop={handleDrop}
                            onDragOver={ev => { ev.preventDefault(); setDragOver(true); }}
                            onDragLeave={() => setDragOver(false)}
                            onClick={() => fileInputRef.current?.click()}
                            className={cn(
                                "border-2 border-dashed rounded-xl p-12 text-center cursor-pointer transition-all duration-200",
                                dragOver ? "border-primary bg-primary/5" : "border-border bg-input hover:border-border"
                            )}
                        >
                            <input
                                ref={fileInputRef}
                                type="file"
                                accept=".csv,.xlsx"
                                className="hidden"
                                onChange={ev => {
                                    const file = ev.target.files?.[0];
                                    if (file) setImportFile(file);
                                }}
                            />
                            <Upload size={32} className="mx-auto text-muted mb-4" />
                            <p className="text-xs font-bold text-muted mb-1">{t("dragDropFile")}</p>
                            <p className="text-[10px] text-muted">{t("supportedFormats")}</p>
                            {importFile && (
                                <div className="mt-4 inline-flex items-center gap-2 bg-surface border border-border px-3 py-2 rounded-xl">
                                    <FileSpreadsheet size={14} className="text-primary" />
                                    <span className="text-xs font-bold text-foreground">{importFile.name}</span>
                                    <button
                                        onClick={ev => { ev.stopPropagation(); setImportFile(null); }}
                                        className="p-0.5 text-muted hover:text-foreground cursor-pointer"
                                    >
                                        <X size={12} />
                                    </button>
                                </div>
                            )}
                        </div>

                        {formError && (
                            <div
                                role="alert"
                                className="mt-6 bg-error/10 border border-error/20 text-error text-xs font-medium rounded-lg p-3"
                            >
                                {formError}
                            </div>
                        )}

                        <div className="flex justify-end gap-3 mt-6">
                            <button
                                type="button"
                                onClick={() => { setShowImportModal(false); setImportFile(null); }}
                                className="px-6 py-3 bg-input text-muted rounded-lg text-xs font-bold cursor-pointer transition-all duration-200 focus:ring-2 focus:ring-primary/20 focus:outline-none"
                            >
                                {t("cancel")}
                            </button>
                            <button
                                onClick={handleImport}
                                disabled={!importFile || importing}
                                className="px-8 py-3 bg-primary text-primary-foreground rounded-lg text-xs font-bold cursor-pointer transition-all duration-200 hover:bg-primary/90 focus:ring-2 focus:ring-primary/20 focus:outline-none disabled:opacity-50 flex items-center gap-2"
                            >
                                {importing && <Loader2 size={14} className="animate-spin" />}
                                {t("importAccounts")}
                            </button>
                        </div>
                    </div>
                </div>
            )}

            {/* ── Delete Confirm Dialog ── */}
            {showDeleteConfirm && (
                <div className="fixed inset-0 bg-gray-900/40 backdrop-blur-sm flex items-center justify-center p-4 z-[100]">
                    <div className="bg-surface rounded-xl p-8 max-w-md w-full shadow-2xl border border-border relative">
                        <h2 className="text-lg font-bold mb-2">{t("deleteAccount")}</h2>
                        <p className="text-xs text-muted mb-8">{t("confirmDeleteAccount")}</p>
                        <div className="flex justify-end gap-3">
                            <button
                                onClick={() => setShowDeleteConfirm(null)}
                                className="px-6 py-3 bg-input text-muted rounded-lg text-xs font-bold cursor-pointer transition-all duration-200 focus:ring-2 focus:ring-primary/20 focus:outline-none"
                            >
                                {t("cancel")}
                            </button>
                            <button
                                onClick={() => handleDelete(showDeleteConfirm)}
                                className="px-8 py-3 bg-error text-white rounded-lg text-xs font-bold cursor-pointer transition-all duration-200 hover:opacity-90 focus:ring-2 focus:ring-rose-300 focus:outline-none flex items-center gap-2"
                            >
                                <Trash2 size={14} />
                                {t("deleteAccount")}
                            </button>
                        </div>
                    </div>
                </div>
            )}

            {/* ── Skeleton Loading ── */}
            {loading && (
                <div className="bg-surface border border-border rounded-xl overflow-hidden shadow-sm">
                    <div className="animate-pulse">
                        <div className="h-12 bg-input border-b border-border" />
                        {[1, 2, 3, 4, 5, 6, 7, 8].map(i => (
                            <div key={i} className="flex gap-4 px-5 py-4 border-b border-border">
                                <div className="h-3 w-4 bg-input rounded" />
                                <div className="h-3 w-16 bg-input rounded" />
                                <div className="h-3 w-40 bg-input rounded" />
                                <div className="h-3 w-20 bg-input rounded" />
                            </div>
                        ))}
                    </div>
                </div>
            )}

            {/* ── Tree View ── */}
            {!loading && viewMode === "tree" && filtered.length > 0 && (
                <div className="bg-surface border border-border rounded-xl overflow-hidden shadow-sm">
                    {/* Table header */}
                    <div className="flex items-center justify-between px-5 py-3.5 border-b border-border bg-input/30">
                        <div className="flex items-center gap-6">
                            <span className="text-[11px] font-semibold text-muted uppercase tracking-wider">{t("code")}</span>
                            <span className="text-[11px] font-semibold text-muted uppercase tracking-wider">{t("accountName")}</span>
                        </div>
                        <div className="flex items-center gap-4">
                            <span className="text-[11px] font-semibold text-muted uppercase tracking-wider hidden md:inline">{t("subType")}</span>
                            <span className="text-[11px] font-semibold text-muted uppercase tracking-wider">{t("actions")}</span>
                        </div>
                    </div>
                    {roots.map(account => renderTreeRow(account, childrenMap, 0))}
                </div>
            )}

            {/* ── Flat View (grouped by type) ── */}
            {!loading && viewMode === "flat" && filtered.length > 0 && (
                <div className="space-y-4">
                    {TYPE_ORDER.map(type => {
                        const items = groupedByType[type] || [];
                        if (items.length === 0) return null;
                        const isExpanded = expandedTypes.has(type);

                        return (
                            <div key={type} className="bg-surface border border-border rounded-xl overflow-hidden shadow-sm">
                                <button
                                    onClick={() => toggleType(type)}
                                    className="w-full flex items-center justify-between px-5 py-4 hover:bg-input/30 transition-all duration-200 cursor-pointer focus:outline-none"
                                >
                                    <div className="flex items-center gap-3">
                                        <span className={cn("inline-flex items-center px-2.5 py-1 rounded-full text-[10px] font-bold", TYPE_BADGE[type])}>
                                            {type}
                                        </span>
                                        <span className="text-xs font-bold text-foreground">{type.charAt(0) + type.slice(1).toLowerCase()}</span>
                                        <span className="text-[10px] text-muted font-medium">{items.length} accounts</span>
                                    </div>
                                    {isExpanded ? <ChevronDown size={16} className="text-muted" /> : <ChevronRight size={16} className="text-muted" />}
                                </button>
                                {isExpanded && (
                                    <div className="border-t border-border">
                                        <table className="w-full">
                                            <thead>
                                                <tr className="border-b border-border">
                                                    <th className="text-left px-5 py-2.5 text-[11px] font-semibold text-muted uppercase tracking-wider">{t("code")}</th>
                                                    <th className="text-left px-5 py-2.5 text-[11px] font-semibold text-muted uppercase tracking-wider">{t("accountName")}</th>
                                                    <th className="text-left px-5 py-2.5 text-[11px] font-semibold text-muted uppercase tracking-wider hidden md:table-cell">{t("subType")}</th>
                                                    <th className="text-left px-5 py-2.5 text-[11px] font-semibold text-muted uppercase tracking-wider hidden md:table-cell">{t("parent")}</th>
                                                    <th className="text-right px-5 py-2.5 text-[11px] font-semibold text-muted uppercase tracking-wider">{t("actions")}</th>
                                                </tr>
                                            </thead>
                                            <tbody className="divide-y divide-border">
                                                {items
                                                    .sort((a, b) => a.displayOrder - b.displayOrder || a.code.localeCompare(b.code))
                                                    .map(account => (
                                                        <tr key={account.id} className={cn("hover:bg-input/30 transition-all duration-200", !account.active && "opacity-50")}>
                                                            <td className="px-5 py-3">
                                                                <span className={cn("text-[10px] font-bold uppercase tracking-widest px-2.5 py-1 rounded-lg", TYPE_BADGE[account.accountType])}>
                                                                    {account.code}
                                                                </span>
                                                            </td>
                                                            <td className="px-5 py-3">
                                                                <p className={cn("text-xs font-bold text-foreground", account.group && "font-bold")}>
                                                                    {displayName(account)}
                                                                </p>
                                                                {account.description && (
                                                                    <p className="text-[10px] text-muted mt-0.5">{account.description}</p>
                                                                )}
                                                            </td>
                                                            <td className="px-5 py-3 hidden md:table-cell">
                                                                <span className="text-[10px] text-muted">
                                                                    {account.accountSubType?.replace(/_/g, " ") || "—"}
                                                                </span>
                                                            </td>
                                                            <td className="px-5 py-3 hidden md:table-cell">
                                                                {account.parentCode ? (
                                                                    <span className="text-[10px] font-bold text-muted">
                                                                        {account.parentCode}
                                                                    </span>
                                                                ) : (
                                                                    <span className="text-[10px] text-muted">—</span>
                                                                )}
                                                            </td>
                                                            <td className="px-5 py-3 text-right">
                                                                <div className="flex items-center justify-end gap-1">
                                                                    {account.group && (
                                                                        <span className="text-[8px] font-bold text-primary bg-primary/5 px-2 py-0.5 rounded-full uppercase tracking-wider mr-1">Group</span>
                                                                    )}
                                                                    {account.system ? (
                                                                        <span className="text-[8px] font-bold text-muted bg-input px-2 py-0.5 rounded-full uppercase tracking-wider">System</span>
                                                                    ) : (
                                                                        <>
                                                                            <button
                                                                                onClick={() => openEdit(account)}
                                                                                className="p-1.5 text-muted hover:text-primary cursor-pointer transition-all duration-200 rounded-lg hover:bg-primary/5 focus:outline-none"
                                                                                title={t("editAccount")}
                                                                            >
                                                                                <Pencil size={13} />
                                                                            </button>
                                                                            <button
                                                                                onClick={() => setShowDeleteConfirm(account.id)}
                                                                                className="p-1.5 text-muted hover:text-rose-500 cursor-pointer transition-all duration-200 rounded-lg hover:bg-rose-50 focus:outline-none"
                                                                                title={t("deleteAccount")}
                                                                            >
                                                                                <Trash2 size={13} />
                                                                            </button>
                                                                        </>
                                                                    )}
                                                                </div>
                                                            </td>
                                                        </tr>
                                                    ))}
                                            </tbody>
                                        </table>
                                    </div>
                                )}
                            </div>
                        );
                    })}
                </div>
            )}

            {/* ── Empty State ── */}
            {filtered.length === 0 && !loading && (
                <div className="text-center py-24 bg-input border border-dashed border-border rounded-xl flex flex-col items-center">
                    <div className="w-16 h-16 bg-surface rounded-xl flex items-center justify-center text-muted shadow-sm mb-6">
                        <FileSpreadsheet size={32} />
                    </div>
                    <p className="text-sm font-bold text-muted mb-2 uppercase tracking-widest">{t("noAccountsFound")}</p>
                    <p className="text-xs text-muted mb-6">{t("seedAccountsDesc")}</p>
                    {accounts.length === 0 && (
                        <button
                            onClick={handleSeedDefaults}
                            disabled={seeding}
                            className="text-xs font-bold text-primary border-b-2 border-primary pb-0.5 hover:opacity-70 transition-all duration-200 cursor-pointer focus:ring-2 focus:ring-primary/20 focus:outline-none disabled:opacity-50"
                        >
                            {t("seedDefaults")}
                        </button>
                    )}
                </div>
            )}
        </div>
    );
}
