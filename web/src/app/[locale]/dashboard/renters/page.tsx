"use client";

import { Suspense, useState, useEffect, useRef, useCallback } from "react";
import { useTranslations, useLocale } from "next-intl";
import { Plus, X, User, Mail, Phone, List, LayoutGrid, Search, MailCheck } from "lucide-react";
import { ResendInviteButton } from "@/components/users/ResendInviteButton";
import { Link } from "@/i18n/routing";
import { useSession } from "next-auth/react";
import { hasPermission, type UserRole } from "@/lib/rbac";
import { cn } from "@/lib/utils";
import { ApiError, throwIfNotOk } from "@/lib/api/facilities";
import { Pagination } from "@/components/ui/Pagination";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";
import { useUrlState } from "@/hooks/useUrlState";
import type { Page } from "@/lib/api/ledger";

type Renter = {
    id: string;
    nameEn: string;
    nameAr: string;
    email: string;
    phone: string;
    primaryLanguage: string;
    userId?: string | null;
    /** True while the portal invite is unused (#7); the API never returns a password. */
    invitePending?: boolean;
    inviteExpiresAt?: string | null;
};

export default function RentersPage() {
    return (
        <Suspense fallback={null}>
            <RentersPageInner />
        </Suspense>
    );
}

function RentersPageInner() {
    const t = useTranslations("MasterData");
    const tCommon = useTranslations("Common");
    const tInv = useTranslations("Invites");
    const languageLabel = (code: string | null | undefined) =>
        code && t.has(`language${code}`) ? t(`language${code}`) : (code ?? "—");
    const locale = useLocale();
    const [renters, setRenters] = useState<Renter[]>([]);
    const [totalItems, setTotalItems] = useState(0);
    const [loading, setLoading] = useState(true);
    const [loadError, setLoadError] = useState<string | null>(null);
    const [showForm, setShowForm] = useState(false);
    const { data: session } = useSession();

    const userRole = session?.user?.role as UserRole | undefined;
    const canManageRenters = hasPermission(userRole, 'canManageRenters');

    const [viewMode, setViewMode] = useState<"table" | "cards">("table");

    // Scale P1-3: search, page and size live in the URL (bookmarkable, same
    // pattern as the Tickets/Contracts lists) via the shared `useUrlState`
    // hook — the URL's page is 1-based, the API's `page` is 0-based.
    const [q, setQ] = useUrlState("q", "");
    const [pageParam, setPageParam] = useUrlState("page", "1");
    const [sizeParam, setSizeParam] = useUrlState("size", "25");
    // A negative or zero page (a hand-edited or otherwise malformed bookmark,
    // e.g. `?page=-3`) is clamped to 1 rather than passed through to the API.
    const currentPage = Math.max(1, parseInt(pageParam, 10) || 1);
    const itemsPerPage = parseInt(sizeParam, 10) || 25;

    // R1 P1-1: the box shows `draftSearch` immediately while typing; 300 ms
    // after the last keystroke it is written to the URL (which also resets
    // the page to 1) and `draftSearch` hands control back to the URL value.
    // The committed value is trimmed — "leak " or "  " must not reach the URL
    // or the API as a search with trailing/only whitespace.
    const [draftSearch, setDraftSearch] = useState<string | null>(null);
    const searchQuery = draftSearch ?? q;
    useEffect(() => {
        if (draftSearch === null || draftSearch.trim() === q) return;
        const timer = setTimeout(() => {
            setQ(draftSearch.trim());
            setPageParam("1");
            setDraftSearch(null);
        }, 300);
        return () => clearTimeout(timer);
    }, [draftSearch, q, setQ, setPageParam]);

    const [formData, setFormData] = useState({
        nameEn: "",
        nameAr: "",
        email: "",
        phone: "",
        primaryLanguage: "EN",
        createPortalAccount: true
    });

    // R1 P1-2: a request counter so a slow, older response (a stale search or
    // page) can never overwrite a newer one — same guard as the Tickets and
    // Contracts lists.
    const fetchSeq = useRef(0);
    const fetchRenters = useCallback(async () => {
        const seq = ++fetchSeq.current;
        const isCurrent = () => seq === fetchSeq.current;
        try {
            const sp = new URLSearchParams();
            if (q) sp.set("q", q);
            sp.set("page", String(currentPage - 1));
            sp.set("size", String(itemsPerPage));
            const res = await fetch(`/api/proxy/v1/renters/paged?${sp.toString()}`);
            if (!isCurrent()) return;
            if (res.ok) {
                const page: Page<Renter> = await res.json();
                if (!isCurrent()) return;
                const totalElements = page.totalElements ?? 0;
                // Controller ruling (Scale PR B2 task 7): a bookmarked or
                // now-stale URL page beyond the last page for this query
                // (rows exist, but this page came back empty) clamps to the
                // last page and refetches, instead of rendering a blank list.
                const totalPages = Math.max(1, Math.ceil(totalElements / itemsPerPage));
                if ((page.content?.length ?? 0) === 0 && totalElements > 0 && currentPage > totalPages) {
                    setPageParam(String(totalPages));
                    return;
                }
                setRenters(page.content ?? []);
                setTotalItems(totalElements);
                setLoadError(null);
            } else {
                // A non-2xx used to leave the state at its initial empty
                // value, so a failed request rendered as "nothing here".
                setLoadError(tCommon("loadFailedRenters"));
                setRenters([]);
                setTotalItems(0);
            }
        } catch (err) {
            if (isCurrent()) console.error(err);
        } finally {
            if (isCurrent()) setLoading(false);
        }
    }, [q, currentPage, itemsPerPage, tCommon, setPageParam]);

    useEffect(() => {
        fetchRenters();
    }, [fetchRenters]);

    // #7: after creating a renter we confirm the emailed invite. There is no
    // password to show: the backend neither generates nor returns one.
    // Why the renter has (or has no) portal invite, so the notice never gives a
    // reason that is not the real one (web review M3).
    type InviteOutcome = "invited" | "optedOut" | "noEmail" | "notInvited";
    const [inviteNotice, setInviteNotice] = useState<{ email: string; outcome: InviteOutcome } | null>(null);
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

            const outcome: InviteOutcome = data.invitePending ? "invited"
                : !formData.createPortalAccount ? "optedOut"
                : !formData.email.trim() ? "noEmail"
                : "notInvited";
            setInviteNotice({ email: formData.email, outcome });

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
            setFormError(err instanceof ApiError ? err.message : t("genericError"));
        }
    };

    const getRenterDisplayName = (r: Renter) => {
        if (locale === 'ar' && r.nameAr) return r.nameAr;
        return r.nameEn;
    };

    // The search, page and page size are now all applied server-side
    // (`GET /renters/paged`) — `renters` is already this page's rows, and
    // `totalItems` is the server's total for the current `q`.
    const paginatedItems = renters;
    // Fix round 1: `totalItems === 0` alone doesn't say *why* the list is
    // empty — no renters exist at all, or this search just has no matches.
    // Gating the "you haven't added any renters yet" CTA on that alone told
    // an admin searching for someone who exists that nobody does, and
    // offered to create a duplicate. `q` (the committed URL search, not the
    // in-flight `draftSearch`) tells them apart.
    const hasSearch = q !== "";
    const showFirstRunEmptyState = !hasSearch && totalItems === 0;
    const showNoSearchResults = hasSearch && totalItems === 0;

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

    const reload = () => {
        setLoadError(null);
        fetchRenters();
    };

    return (
        <div>
            {loadError && <LoadErrorBanner message={loadError} onRetry={reload} />}
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
                        <Search size={14} className="absolute start-3 top-1/2 -translate-y-1/2 text-muted" />
                        <input
                            type="text"
                            placeholder={t("search")}
                            value={searchQuery}
                            onChange={(e) => setDraftSearch(e.target.value)}
                            className="ps-9 pe-4 py-2 bg-surface border border-border rounded-lg text-sm text-foreground placeholder:text-muted/50 focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none w-64 transition-all"
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
                                <List size={13} /> {t("table")}
                            </button>
                            <button
                                onClick={() => setViewMode("cards")}
                                className={cn(
                                    "px-3 py-1.5 rounded-md text-xs font-medium transition-all cursor-pointer flex items-center gap-1.5",
                                    viewMode === "cards" ? "bg-surface text-foreground shadow-sm border border-border" : "text-muted hover:text-foreground"
                                )}
                            >
                                <LayoutGrid size={13} /> {t("cards")}
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
                        <button onClick={() => setShowForm(false)} aria-label={t("close")} className="cursor-pointer absolute end-6 top-6 p-2 text-muted hover:text-foreground transition-all duration-200 focus:ring-2 focus:ring-primary/30 focus:outline-none rounded-lg"><X size={18} /></button>
                        <h2 className="text-lg font-bold mb-1">{t("addRenter")}</h2>
                        <p className="text-xs text-muted mb-8 font-medium">{t("createRenterProfile")}</p>
                        <form onSubmit={handleSubmit} className="grid grid-cols-2 gap-5">
                            <div className="col-span-1">
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ms-1">{t("nameEn")}</label>
                                <input required placeholder="John Doe" className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none" value={formData.nameEn} onChange={ev => setFormData({ ...formData, nameEn: ev.target.value })} />
                            </div>
                            <div className="col-span-1">
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ms-1">{t("nameAr")}</label>
                                <input dir="rtl" placeholder="جون دو" className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none" value={formData.nameAr} onChange={ev => setFormData({ ...formData, nameAr: ev.target.value })} />
                            </div>
                            <div className="col-span-1">
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ms-1">{t("email")}</label>
                                <input type="email" placeholder="john@example.com" className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none" value={formData.email} onChange={ev => setFormData({ ...formData, email: ev.target.value })} />
                            </div>
                            <div className="col-span-1">
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ms-1">{t("phone")}</label>
                                <input placeholder="+971 50 123 4567" className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none" value={formData.phone} onChange={ev => setFormData({ ...formData, phone: ev.target.value })} />
                            </div>
                            <div className="col-span-1">
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ms-1">{t("preferredLanguage")}</label>
                                <select className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none" value={formData.primaryLanguage} onChange={ev => setFormData({ ...formData, primaryLanguage: ev.target.value })}>
                                    <option value="EN">{t("languageEN")}</option>
                                    <option value="AR">{t("languageAR")}</option>
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
                                            <th className="px-5 py-3 text-start text-[11px] font-semibold text-muted uppercase tracking-wider">{t("name")}</th>
                                            <th className="px-5 py-3 text-start text-[11px] font-semibold text-muted uppercase tracking-wider">{t("email")}</th>
                                            <th className="px-5 py-3 text-start text-[11px] font-semibold text-muted uppercase tracking-wider">{t("phone")}</th>
                                            <th className="px-5 py-3 text-start text-[11px] font-semibold text-muted uppercase tracking-wider">{t("language")}</th>
                                            <th className="px-5 py-3 text-end text-[11px] font-semibold text-muted uppercase tracking-wider">{t("actions")}</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        {paginatedItems.map(r => (
                                            <tr key={r.id} className="border-b border-border hover:bg-input/30 transition-colors">
                                                <td className="px-5 py-3.5 text-sm text-foreground">
                                                    <Link href={`/dashboard/renters/${r.id}`} className="font-medium hover:text-primary hover:underline">{getRenterDisplayName(r)}</Link>
                                                    {r.nameAr && locale !== 'ar' && <div className="text-[10px] text-muted">{r.nameAr}</div>}
                                                    {r.nameEn && locale === 'ar' && <div className="text-[10px] text-muted">{r.nameEn}</div>}
                                                </td>
                                                <td className="px-5 py-3.5 text-sm text-foreground">{r.email || t("noEmailProvided")}</td>
                                                <td className="px-5 py-3.5 text-sm text-foreground">{r.phone || t("noPhoneProvided")}</td>
                                                <td className="px-5 py-3.5">
                                                    <span className="inline-flex items-center justify-center px-2 py-0.5 rounded text-[9px] font-bold bg-input text-muted border border-border tracking-wider">
                                                        {languageLabel(r.primaryLanguage)}
                                                    </span>
                                                </td>
                                                <td className="px-5 py-3.5 text-end">
                                                    <div className="flex items-center justify-end gap-3">
                                                        {canManageRenters && r.invitePending && r.userId && (
                                                            <ResendInviteButton userId={r.userId} onSent={fetchRenters} />
                                                        )}
                                                        <Link href={`/dashboard/renters/${r.id}`} className="text-xs font-semibold text-primary hover:underline">
                                                            {t("view")}
                                                        </Link>
                                                    </div>
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
                                            <h3 className="text-sm font-bold text-foreground tracking-tight">
                                                <Link href={`/dashboard/renters/${r.id}`} className="hover:text-primary hover:underline">{getRenterDisplayName(r)}</Link>
                                            </h3>
                                            {r.nameAr && locale !== 'ar' && <p className="text-[10px] text-muted font-bold mb-1">{r.nameAr}</p>}
                                            {r.nameEn && locale === 'ar' && <p className="text-[10px] text-muted font-bold mb-1">{r.nameEn}</p>}
                                            <span className="inline-flex items-center justify-center px-2 py-0.5 rounded text-[9px] font-bold bg-input text-muted border border-border tracking-wider">
                                                {languageLabel(r.primaryLanguage)}
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
                        onPageChange={(p) => setPageParam(String(p))}
                        onItemsPerPageChange={(n) => { setSizeParam(String(n)); setPageParam("1"); }}
                    />
                </>
            )}

            {/* A search with no matches is not the same fact as "no renters
                exist yet" — this keeps the search box (above) editable and
                does not offer to create a renter who may already exist. */}
            {showNoSearchResults && !showForm && (
                <div className="text-center py-16 bg-background border border-dashed border-border rounded-xl flex flex-col items-center" data-testid="renters-no-search-results">
                    <div className="w-16 h-16 bg-surface rounded-xl flex items-center justify-center text-muted shadow-sm mb-6">
                        <Search size={28} />
                    </div>
                    <p className="text-sm font-bold text-muted uppercase tracking-widest">
                        {t("noRentersMatchSearch")}
                    </p>
                </div>
            )}

            {showFirstRunEmptyState && !showForm && (
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
            {/* Invite confirmation (#7) — replaces the old plaintext-credentials modal */}
            {inviteNotice && (
                <div className="fixed inset-0 bg-foreground/40 backdrop-blur-sm flex items-center justify-center p-4 z-[100]">
                    <div role="dialog" aria-modal="true" className="bg-surface rounded-xl p-8 max-w-md w-full shadow-2xl border border-border relative">
                        <div className="w-10 h-10 bg-primary/10 rounded-lg flex items-center justify-center text-primary mb-4">
                            <MailCheck size={18} />
                        </div>
                        <h2 className="text-lg font-bold text-foreground mb-2">
                            {inviteNotice.outcome === "invited" ? tInv("sentTitle") : tInv("savedTitle")}
                        </h2>
                        <p className="text-sm text-muted mb-6">
                            {inviteNotice.outcome === "invited" ? tInv("sentBody", { email: inviteNotice.email })
                                : inviteNotice.outcome === "optedOut" ? tInv("noPortalOptedOutBody")
                                : inviteNotice.outcome === "noEmail" ? tInv("noPortalBody")
                                : tInv("noInviteBody")}
                        </p>
                        <button
                            onClick={() => setInviteNotice(null)}
                            className="w-full bg-primary text-primary-foreground py-2.5 rounded-lg text-sm font-semibold hover:bg-primary/90 transition-all cursor-pointer"
                        >
                            {tInv("done")}
                        </button>
                    </div>
                </div>
            )}
        </div>
    );
}
