"use client";

import { useState, useEffect } from "react";
import { useTranslations, useLocale } from "next-intl";
import { Plus, X, User, Mail, Phone, List, LayoutGrid, Search, Copy, Check } from "lucide-react";
import { useSession } from "next-auth/react";
import { hasPermission, type UserRole } from "@/lib/rbac";
import { cn } from "@/lib/utils";
import { ApiError, throwIfNotOk } from "@/lib/api/facilities";
import { Pagination } from "@/components/ui/Pagination";

type Renter = {
    id: string;
    nameEn: string;
    nameAr: string;
    email: string;
    phone: string;
    primaryLanguage: string;
};

export default function RentersPage() {
    const t = useTranslations("MasterData");
    const locale = useLocale();
    const [renters, setRenters] = useState<Renter[]>([]);
    const [loading, setLoading] = useState(true);
    const [showForm, setShowForm] = useState(false);
    const { data: session } = useSession();

    const userRole = session?.user?.role as UserRole | undefined;
    const canManageRenters = hasPermission(userRole, 'canManageRenters');

    const [viewMode, setViewMode] = useState<"table" | "cards">("table");
    const [currentPage, setCurrentPage] = useState(1);
    const [itemsPerPage, setItemsPerPage] = useState(25);
    const [searchQuery, setSearchQuery] = useState("");

    const [formData, setFormData] = useState({
        nameEn: "",
        nameAr: "",
        email: "",
        phone: "",
        primaryLanguage: "EN",
        createPortalAccount: true
    });

    useEffect(() => {
        fetchRenters();
    }, []);

    useEffect(() => {
        setCurrentPage(1);
    }, [renters]);

    const fetchRenters = async () => {
        try {
            const res = await fetch("/api/proxy/v1/renters");
            if (res.ok) {
                const data = await res.json();
                setRenters(data);
            }
        } catch (err) {
            console.error(err);
        } finally {
            setLoading(false);
        }
    };

    const [credentialsModal, setCredentialsModal] = useState<{ email: string; password: string } | null>(null);
    const [copied, setCopied] = useState(false);
    const [formError, setFormError] = useState<string | null>(null);

    const handleSubmit = async (ev: React.FormEvent) => {
        ev.preventDefault();
        setFormError(null);
        try {
            const res = await fetch("/api/proxy/v1/renters", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(formData)
            });
            // Surface backend failures (e.g. invalid email, duplicate portal
            // email aborting the transaction) instead of silently doing nothing.
            await throwIfNotOk(res);
            const data = await res.json();
            setShowForm(false);
            fetchRenters();

            // Show portal credentials if a portal account was created
            if (data.portalPassword) {
                setCredentialsModal({ email: formData.email, password: data.portalPassword });
            }

            setFormData({
                nameEn: "",
                nameAr: "",
                email: "",
                phone: "",
                primaryLanguage: "EN",
                createPortalAccount: true
            });
        } catch (err) {
            console.error(err);
            setFormError(err instanceof ApiError ? err.message : "Something went wrong. Please try again.");
        }
    };

    const getRenterDisplayName = (r: Renter) => {
        if (locale === 'ar' && r.nameAr) return r.nameAr;
        return r.nameEn;
    };

    const filteredRenters = renters.filter(r => {
        if (!searchQuery) return true;
        const q = searchQuery.toLowerCase();
        return (
            r.nameEn?.toLowerCase().includes(q) ||
            r.nameAr?.toLowerCase().includes(q) ||
            r.email?.toLowerCase().includes(q) ||
            r.phone?.toLowerCase().includes(q)
        );
    });
    const totalItems = filteredRenters.length;
    const paginatedItems = filteredRenters.slice((currentPage - 1) * itemsPerPage, currentPage * itemsPerPage);

    if (loading) {
        return (
            <div>
                <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 mb-10">
                    <div>
                        <div className="h-6 w-32 bg-input rounded-lg animate-pulse mb-2" />
                        <div className="h-4 w-56 bg-input rounded-lg animate-pulse" />
                    </div>
                    <div className="h-10 w-32 bg-input rounded-lg animate-pulse" />
                </div>
                <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
                    {[...Array(6)].map((_, i) => (
                        <div key={i} className="bg-surface rounded-xl p-5 border border-border">
                            <div className="flex items-start gap-4 mb-6">
                                <div className="w-12 h-12 bg-input rounded-xl animate-pulse shrink-0" />
                                <div className="flex-1">
                                    <div className="h-4 w-28 bg-input rounded-lg animate-pulse mb-2" />
                                    <div className="h-3 w-16 bg-input rounded animate-pulse" />
                                </div>
                            </div>
                            <div className="space-y-3">
                                <div className="flex items-center gap-3">
                                    <div className="w-4 h-4 bg-input rounded animate-pulse" />
                                    <div className="h-3 w-40 bg-input rounded animate-pulse" />
                                </div>
                                <div className="flex items-center gap-3">
                                    <div className="w-4 h-4 bg-input rounded animate-pulse" />
                                    <div className="h-3 w-32 bg-input rounded animate-pulse" />
                                </div>
                            </div>
                        </div>
                    ))}
                </div>
            </div>
        );
    }

    return (
        <div>
            <div className="flex flex-col gap-4 mb-10">
                <div>
                    <h1 className="text-xl font-bold text-foreground tracking-tight mb-1">
                        {t("renters")}
                    </h1>
                    <p className="text-xs text-muted font-medium">
                        {t("manageRenters")}
                    </p>
                </div>
                <div className="flex flex-col md:flex-row md:items-center justify-between gap-3">
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
                    <div className="flex items-center gap-3">
                        <div className="flex items-center bg-input rounded-lg p-0.5 border border-border">
                            <button
                                onClick={() => setViewMode("table")}
                                className={cn(
                                    "px-3 py-1.5 rounded-md text-xs font-medium transition-all cursor-pointer flex items-center gap-1.5",
                                    viewMode === "table" ? "bg-surface text-foreground shadow-sm border border-border" : "text-muted hover:text-foreground"
                                )}
                            >
                                <List size={13} /> Table
                            </button>
                            <button
                                onClick={() => setViewMode("cards")}
                                className={cn(
                                    "px-3 py-1.5 rounded-md text-xs font-medium transition-all cursor-pointer flex items-center gap-1.5",
                                    viewMode === "cards" ? "bg-surface text-foreground shadow-sm border border-border" : "text-muted hover:text-foreground"
                                )}
                            >
                                <LayoutGrid size={13} /> Cards
                            </button>
                        </div>
                        {canManageRenters && (
                            <button
                                onClick={() => { setFormError(null); setShowForm(true); }}
                                className="cursor-pointer flex items-center gap-2 bg-primary text-primary-foreground px-4 py-2 rounded-lg text-xs font-semibold hover:opacity-90 transition-all duration-200 active:scale-95 focus:ring-2 focus:ring-primary/30 focus:outline-none"
                            >
                                <Plus size={14} />
                                {t("addRenter")}
                            </button>
                        )}
                    </div>
                </div>
            </div>

            {showForm && (
                <div className="fixed inset-0 bg-foreground/40 backdrop-blur-sm flex items-center justify-center p-4 z-[100]">
                    <div className="bg-surface rounded-xl p-8 max-w-xl w-full shadow-2xl border border-border relative">
                        <button onClick={() => setShowForm(false)} aria-label="Close" className="cursor-pointer absolute right-6 top-6 p-2 text-muted hover:text-foreground transition-all duration-200 focus:ring-2 focus:ring-primary/30 focus:outline-none rounded-lg"><X size={18} /></button>
                        <h2 className="text-lg font-bold mb-1">{t("addRenter")}</h2>
                        <p className="text-xs text-muted mb-8 font-medium">{t("createRenterProfile")}</p>
                        <form onSubmit={handleSubmit} className="grid grid-cols-2 gap-5">
                            <div className="col-span-1">
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">{t("nameEn")}</label>
                                <input required placeholder="John Doe" className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none" value={formData.nameEn} onChange={ev => setFormData({ ...formData, nameEn: ev.target.value })} />
                            </div>
                            <div className="col-span-1">
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">{t("nameAr")}</label>
                                <input placeholder="جون دو" className="w-full bg-input border border-border p-3 rounded-xl text-xs text-right focus:ring-2 focus:ring-primary/30 focus:outline-none" value={formData.nameAr} onChange={ev => setFormData({ ...formData, nameAr: ev.target.value })} />
                            </div>
                            <div className="col-span-1">
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">{t("email")}</label>
                                <input type="email" placeholder="john@example.com" className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none" value={formData.email} onChange={ev => setFormData({ ...formData, email: ev.target.value })} />
                            </div>
                            <div className="col-span-1">
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">{t("phone")}</label>
                                <input placeholder="+971 50 123 4567" className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none" value={formData.phone} onChange={ev => setFormData({ ...formData, phone: ev.target.value })} />
                            </div>
                            <div className="col-span-1">
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">{t("preferredLanguage")}</label>
                                <select className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none" value={formData.primaryLanguage} onChange={ev => setFormData({ ...formData, primaryLanguage: ev.target.value })}>
                                    <option value="EN">English</option>
                                    <option value="AR">Arabic</option>
                                </select>
                            </div>
                            <div className="col-span-1 flex items-end">
                                <label className="flex items-center gap-3 cursor-pointer p-3">
                                    <input
                                        type="checkbox"
                                        checked={formData.createPortalAccount}
                                        onChange={ev => setFormData({ ...formData, createPortalAccount: ev.target.checked })}
                                        className="w-4 h-4 rounded border-border text-primary focus:ring-primary"
                                    />
                                    <span className="text-xs font-bold text-foreground">{t("createPortalAccount")}</span>
                                </label>
                            </div>
                            {formError && (
                                <div className="col-span-2 bg-error/10 border border-error/20 text-error text-xs font-medium rounded-lg px-3 py-2" role="alert">
                                    {formError}
                                </div>
                            )}
                            <div className="col-span-2 flex justify-end gap-3 mt-4">
                                <button type="button" onClick={() => setShowForm(false)} className="cursor-pointer px-6 py-3 text-xs font-bold text-muted hover:text-foreground transition-all duration-200 focus:ring-2 focus:ring-primary/30 focus:outline-none rounded-lg">{t("cancel")}</button>
                                <button type="submit" className="cursor-pointer px-8 py-3 bg-primary text-primary-foreground rounded-xl text-xs font-bold transition-all duration-200 focus:ring-2 focus:ring-primary/30 focus:outline-none">{t("create")}</button>
                            </div>
                        </form>
                    </div>
                </div>
            )}

            {renters.length > 0 && (
                <>
                    {viewMode === "table" ? (
                        <div className="bg-surface rounded-xl border border-border overflow-hidden">
                            <div className="overflow-x-auto">
                                <table className="w-full">
                                    <thead>
                                        <tr className="bg-input/50">
                                            <th className="px-5 py-3 text-start text-[11px] font-semibold text-muted uppercase tracking-wider">Name</th>
                                            <th className="px-5 py-3 text-start text-[11px] font-semibold text-muted uppercase tracking-wider">Email</th>
                                            <th className="px-5 py-3 text-start text-[11px] font-semibold text-muted uppercase tracking-wider">Phone</th>
                                            <th className="px-5 py-3 text-start text-[11px] font-semibold text-muted uppercase tracking-wider">Language</th>
                                            <th className="px-5 py-3 text-end text-[11px] font-semibold text-muted uppercase tracking-wider">Actions</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        {paginatedItems.map(r => (
                                            <tr key={r.id} className="border-b border-border hover:bg-input/30 transition-colors">
                                                <td className="px-5 py-3.5 text-sm text-foreground">
                                                    <div className="font-medium">{getRenterDisplayName(r)}</div>
                                                    {r.nameAr && locale !== 'ar' && <div className="text-[10px] text-muted">{r.nameAr}</div>}
                                                    {r.nameEn && locale === 'ar' && <div className="text-[10px] text-muted">{r.nameEn}</div>}
                                                </td>
                                                <td className="px-5 py-3.5 text-sm text-foreground">{r.email || t("noEmailProvided")}</td>
                                                <td className="px-5 py-3.5 text-sm text-foreground">{r.phone || t("noPhoneProvided")}</td>
                                                <td className="px-5 py-3.5">
                                                    <span className="inline-flex items-center justify-center px-2 py-0.5 rounded text-[9px] font-bold bg-input text-muted border border-border tracking-wider">
                                                        {r.primaryLanguage}
                                                    </span>
                                                </td>
                                                <td className="px-5 py-3.5 text-end">
                                                    <span className="text-xs font-semibold text-primary">View</span>
                                                </td>
                                            </tr>
                                        ))}
                                    </tbody>
                                </table>
                            </div>
                        </div>
                    ) : (
                        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
                            {paginatedItems.map(r => (
                                <div key={r.id} className="bg-surface rounded-xl p-5 border border-border hover:shadow-md transition-all duration-200 group">
                                    <div className="flex items-start gap-4 mb-6">
                                        <div className="w-12 h-12 bg-primary/10 rounded-xl flex items-center justify-center text-primary border border-primary/20 shrink-0">
                                            <User size={20} />
                                        </div>
                                        <div>
                                            <h3 className="text-sm font-bold text-foreground tracking-tight">{getRenterDisplayName(r)}</h3>
                                            {r.nameAr && locale !== 'ar' && <p className="text-[10px] text-muted font-bold mb-1">{r.nameAr}</p>}
                                            {r.nameEn && locale === 'ar' && <p className="text-[10px] text-muted font-bold mb-1">{r.nameEn}</p>}
                                            <span className="inline-flex items-center justify-center px-2 py-0.5 rounded text-[9px] font-bold bg-input text-muted border border-border tracking-wider">
                                                {r.primaryLanguage}
                                            </span>
                                        </div>
                                    </div>

                                    <div className="space-y-3">
                                        <div className="flex items-center gap-3">
                                            <Mail size={14} className="text-muted" />
                                            <span className="text-xs font-medium text-foreground truncate">{r.email || t("noEmailProvided")}</span>
                                        </div>
                                        <div className="flex items-center gap-3">
                                            <Phone size={14} className="text-muted" />
                                            <span className="text-xs font-medium text-foreground">{r.phone || t("noPhoneProvided")}</span>
                                        </div>
                                    </div>
                                </div>
                            ))}
                        </div>
                    )}

                    <Pagination
                        currentPage={currentPage}
                        totalItems={totalItems}
                        itemsPerPage={itemsPerPage}
                        onPageChange={setCurrentPage}
                        onItemsPerPageChange={(n) => { setItemsPerPage(n); setCurrentPage(1); }}
                    />
                </>
            )}

            {renters.length === 0 && !showForm && (
                <div className="text-center py-24 bg-background border border-dashed border-border rounded-xl flex flex-col items-center">
                    <div className="w-16 h-16 bg-surface rounded-xl flex items-center justify-center text-muted shadow-sm mb-6">
                        <User size={32} />
                    </div>
                    <p className="text-sm font-bold text-muted mb-6 uppercase tracking-widest">
                        {t("noRentersFound")}
                    </p>
                    {canManageRenters && (
                        <button onClick={() => { setFormError(null); setShowForm(true); }} className="cursor-pointer text-xs font-bold text-foreground border-b-2 border-primary pb-0.5 hover:text-primary transition-all duration-200 focus:ring-2 focus:ring-primary/30 focus:outline-none">
                            {t("addRenter")}
                        </button>
                    )}
                </div>
            )}
            {/* Portal Credentials Modal */}
            {credentialsModal && (
                <div className="fixed inset-0 bg-foreground/40 backdrop-blur-sm flex items-center justify-center p-4 z-[100]">
                    <div className="bg-surface rounded-xl p-8 max-w-md w-full shadow-2xl border border-border relative">
                        <h2 className="text-lg font-bold text-foreground mb-1">Portal Account Created</h2>
                        <p className="text-sm text-muted mb-6">Share these credentials with the renter so they can log in to the portal.</p>

                        <div className="bg-input rounded-lg p-4 space-y-3 mb-6 border border-border">
                            <div className="flex justify-between items-center">
                                <span className="text-xs font-semibold text-muted uppercase tracking-wider">Email</span>
                                <span className="text-sm font-medium text-foreground select-all">{credentialsModal.email}</span>
                            </div>
                            <div className="border-t border-border" />
                            <div className="flex justify-between items-center">
                                <span className="text-xs font-semibold text-muted uppercase tracking-wider">Password</span>
                                <div className="flex items-center gap-2">
                                    <span className="text-sm font-mono font-bold text-foreground select-all">{credentialsModal.password}</span>
                                    <button
                                        onClick={() => {
                                            navigator.clipboard.writeText(credentialsModal.password);
                                            setCopied(true);
                                            setTimeout(() => setCopied(false), 2000);
                                        }}
                                        className="p-1 rounded-md text-muted hover:text-foreground hover:bg-background transition-colors cursor-pointer"
                                        title="Copy to clipboard"
                                    >
                                        {copied ? <Check size={13} className="text-success" /> : <Copy size={13} />}
                                    </button>
                                </div>
                            </div>
                        </div>

                        <p className="text-[11px] text-warning mb-4">The renter should change their password after first login.</p>

                        <button
                            onClick={() => setCredentialsModal(null)}
                            className="w-full bg-primary text-primary-foreground py-2.5 rounded-lg text-sm font-semibold hover:bg-primary/90 transition-all cursor-pointer"
                        >
                            Done
                        </button>
                    </div>
                </div>
            )}
        </div>
    );
}
