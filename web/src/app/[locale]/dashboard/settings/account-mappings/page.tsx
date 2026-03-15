"use client";

import { useState, useEffect } from "react";
import { useSession } from "next-auth/react";
import {
    GitBranch, Save, Loader2, Check, AlertCircle
} from "lucide-react";
import { cn } from "@/lib/utils";
import { canConfigureGateway } from "@/lib/rbac";
import type { UserRole } from "@/lib/rbac";

type Account = {
    id: string;
    code: string;
    name: string;
    accountType: string;
};

type AccountMappingDTO = {
    id: string;
    transactionNature: string;
    debitAccountId: string;
    debitAccountCode: string;
    debitAccountName: string;
    creditAccountId: string;
    creditAccountCode: string;
    creditAccountName: string;
};

type MappingRow = {
    transactionNature: string;
    debitAccountId: string;
    creditAccountId: string;
};

const NATURE_LABELS: Record<string, { label: string; description: string; defaultDebit: string; defaultCredit: string }> = {
    RENT_PAYMENT_CLEARED: {
        label: "Rent Payment Cleared",
        description: "When a cheque clears or online payment succeeds",
        defaultDebit: "A-02-02 (Bank Accounts)",
        defaultCredit: "C-01-01 (Rental Income)",
    },
    SECURITY_DEPOSIT_RECEIVED: {
        label: "Security Deposit Received",
        description: "When a security deposit is collected from a tenant",
        defaultDebit: "A-02-02 (Bank Accounts)",
        defaultCredit: "B-01-02 (Security Deposits)",
    },
    SECURITY_DEPOSIT_REFUNDED: {
        label: "Security Deposit Refunded",
        description: "When a security deposit is returned to a tenant",
        defaultDebit: "B-01-02 (Security Deposits)",
        defaultCredit: "A-02-02 (Bank Accounts)",
    },
    CHEQUE_BOUNCED: {
        label: "Cheque Bounced",
        description: "When a previously cleared cheque bounces",
        defaultDebit: "C-01-01 (Rental Income)",
        defaultCredit: "A-02-02 (Bank Accounts)",
    },
};

export default function AccountMappingsPage() {
    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;

    const [accounts, setAccounts] = useState<Account[]>([]);
    const [natures, setNatures] = useState<string[]>([]);
    const [mappings, setMappings] = useState<MappingRow[]>([]);
    const [existingMappings, setExistingMappings] = useState<AccountMappingDTO[]>([]);
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [saved, setSaved] = useState(false);
    const [error, setError] = useState("");

    useEffect(() => {
        Promise.all([
            fetch("/api/proxy/v1/finance/accounts").then(r => r.ok ? r.json() : []),
            fetch("/api/proxy/v1/finance/account-mappings/natures").then(r => r.ok ? r.json() : []),
            fetch("/api/proxy/v1/finance/account-mappings").then(r => r.ok ? r.json() : []),
        ]).then(([accountsData, naturesData, mappingsData]) => {
            setAccounts(accountsData);
            setNatures(naturesData);
            setExistingMappings(mappingsData);

            // Initialize mapping rows from natures, pre-filling with existing mappings
            const rows: MappingRow[] = naturesData.map((nature: string) => {
                const existing = mappingsData.find((m: AccountMappingDTO) => m.transactionNature === nature);
                return {
                    transactionNature: nature,
                    debitAccountId: existing?.debitAccountId || "",
                    creditAccountId: existing?.creditAccountId || "",
                };
            });
            setMappings(rows);
        }).catch(err => {
            console.error(err);
            setError("Failed to load data");
        }).finally(() => setLoading(false));
    }, []);

    const updateMapping = (index: number, field: "debitAccountId" | "creditAccountId", value: string) => {
        setMappings(prev => {
            const next = [...prev];
            next[index] = { ...next[index], [field]: value };
            return next;
        });
        setSaved(false);
    };

    const handleSave = async () => {
        setSaving(true);
        setError("");
        setSaved(false);

        // Only save rows that have both debit and credit selected
        const toSave = mappings.filter(m => m.debitAccountId && m.creditAccountId);

        if (toSave.length === 0) {
            setError("Please select at least one complete mapping (both debit and credit accounts).");
            setSaving(false);
            return;
        }

        try {
            const res = await fetch("/api/proxy/v1/finance/account-mappings/bulk", {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(toSave),
            });
            if (res.ok) {
                const updated = await res.json();
                setExistingMappings(updated);
                setSaved(true);
                setTimeout(() => setSaved(false), 3000);
            } else {
                setError("Failed to save mappings. Please try again.");
            }
        } catch (err) {
            console.error(err);
            setError("Failed to save mappings.");
        } finally {
            setSaving(false);
        }
    };

    if (userRole && !canConfigureGateway(userRole)) {
        return (
            <div className="text-center py-16 text-xs text-muted font-medium">
                You do not have permission to configure account mappings.
            </div>
        );
    }

    // Group accounts by type for the select dropdown
    const accountsByType: Record<string, Account[]> = {};
    accounts.forEach(a => {
        if (!accountsByType[a.accountType]) accountsByType[a.accountType] = [];
        accountsByType[a.accountType].push(a);
    });
    const typeOrder = ["ASSET", "LIABILITY", "INCOME", "EXPENSE", "EQUITY"];

    return (
        <div>
            <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 mb-10">
                <div>
                    <h1 className="text-xl font-bold text-foreground tracking-tight mb-1 flex items-center gap-2">
                        <GitBranch size={20} className="text-primary" />
                        Account Mappings
                    </h1>
                    <p className="text-xs text-muted font-medium">
                        Configure which Chart of Accounts codes are used for automated transactions.
                    </p>
                </div>
                <button
                    onClick={handleSave}
                    disabled={saving}
                    className={cn(
                        "flex items-center gap-2 px-6 py-2.5 rounded-full text-xs font-bold transition-all duration-200 shadow-lg active:scale-95 cursor-pointer focus:ring-2 focus:ring-primary/20 focus:outline-none disabled:opacity-50 disabled:cursor-not-allowed",
                        saved
                            ? "bg-emerald-500 text-white shadow-emerald-500/20"
                            : "bg-primary text-primary-foreground hover:bg-primary/90 shadow-primary/10"
                    )}
                >
                    {saving ? (
                        <Loader2 size={14} className="animate-spin" />
                    ) : saved ? (
                        <Check size={14} />
                    ) : (
                        <Save size={14} />
                    )}
                    {saved ? "Saved" : "Save Mappings"}
                </button>
            </div>

            {error && (
                <div className="mb-6 flex items-center gap-2 text-xs font-medium text-red-600 bg-red-50 border border-red-100 rounded-xl p-3">
                    <AlertCircle size={14} />
                    {error}
                </div>
            )}

            {/* Loading skeleton */}
            {loading && (
                <div className="space-y-4">
                    {[1, 2, 3, 4].map(i => (
                        <div key={i} className="animate-pulse bg-surface border border-border rounded-xl p-6">
                            <div className="h-4 w-48 bg-input rounded mb-3" />
                            <div className="h-3 w-72 bg-background rounded mb-6" />
                            <div className="grid grid-cols-2 gap-4">
                                <div className="h-10 bg-background rounded-xl" />
                                <div className="h-10 bg-background rounded-xl" />
                            </div>
                        </div>
                    ))}
                </div>
            )}

            {/* Mapping cards */}
            {!loading && (
                <div className="space-y-4">
                    {mappings.map((mapping, index) => {
                        const meta = NATURE_LABELS[mapping.transactionNature];
                        const isConfigured = mapping.debitAccountId && mapping.creditAccountId;
                        const wasExisting = existingMappings.some(m => m.transactionNature === mapping.transactionNature);

                        return (
                            <div
                                key={mapping.transactionNature}
                                className={cn(
                                    "bg-surface border rounded-xl p-5 hover:shadow-md transition-all duration-200",
                                    isConfigured ? "border-emerald-200/60 shadow-sm" : "border-border"
                                )}
                            >
                                <div className="flex items-start justify-between mb-4">
                                    <div>
                                        <div className="flex items-center gap-2">
                                            <h3 className="text-sm font-bold text-foreground tracking-tight">
                                                {meta?.label || mapping.transactionNature}
                                            </h3>
                                            {wasExisting && (
                                                <span className="text-[9px] font-bold uppercase tracking-wider text-emerald-600 bg-emerald-50 px-2 py-0.5 rounded-full">
                                                    Configured
                                                </span>
                                            )}
                                        </div>
                                        <p className="text-[11px] text-muted font-medium mt-0.5">
                                            {meta?.description}
                                        </p>
                                    </div>
                                </div>

                                <div className="grid md:grid-cols-2 gap-4">
                                    <div>
                                        <label className="block text-[10px] font-bold text-muted uppercase tracking-wider mb-1.5 ml-1">
                                            Debit Account (Money In)
                                        </label>
                                        <select
                                            className="w-full border border-border rounded-lg bg-surface text-foreground p-3 text-xs cursor-pointer focus:ring-2 focus:ring-primary/20 focus:outline-none transition-all duration-200"
                                            value={mapping.debitAccountId}
                                            onChange={e => updateMapping(index, "debitAccountId", e.target.value)}
                                        >
                                            <option value="">
                                                {meta ? `Default: ${meta.defaultDebit}` : "Select account..."}
                                            </option>
                                            {typeOrder.map(type => (
                                                accountsByType[type] && (
                                                    <optgroup key={type} label={type}>
                                                        {accountsByType[type].map(a => (
                                                            <option key={a.id} value={a.id}>
                                                                {a.code} — {a.name}
                                                            </option>
                                                        ))}
                                                    </optgroup>
                                                )
                                            ))}
                                        </select>
                                    </div>
                                    <div>
                                        <label className="block text-[10px] font-bold text-muted uppercase tracking-wider mb-1.5 ml-1">
                                            Credit Account (Money Out)
                                        </label>
                                        <select
                                            className="w-full border border-border rounded-lg bg-surface text-foreground p-3 text-xs cursor-pointer focus:ring-2 focus:ring-primary/20 focus:outline-none transition-all duration-200"
                                            value={mapping.creditAccountId}
                                            onChange={e => updateMapping(index, "creditAccountId", e.target.value)}
                                        >
                                            <option value="">
                                                {meta ? `Default: ${meta.defaultCredit}` : "Select account..."}
                                            </option>
                                            {typeOrder.map(type => (
                                                accountsByType[type] && (
                                                    <optgroup key={type} label={type}>
                                                        {accountsByType[type].map(a => (
                                                            <option key={a.id} value={a.id}>
                                                                {a.code} — {a.name}
                                                            </option>
                                                        ))}
                                                    </optgroup>
                                                )
                                            ))}
                                        </select>
                                    </div>
                                </div>

                                {!isConfigured && !wasExisting && (
                                    <p className="text-[10px] text-muted font-medium mt-3 ml-1">
                                        Leave empty to use default mapping: {meta?.defaultDebit} → {meta?.defaultCredit}
                                    </p>
                                )}
                            </div>
                        );
                    })}
                </div>
            )}

            {!loading && mappings.length === 0 && (
                <div className="text-center py-16 text-xs text-muted font-medium">
                    No transaction types available. Please check your backend configuration.
                </div>
            )}
        </div>
    );
}
