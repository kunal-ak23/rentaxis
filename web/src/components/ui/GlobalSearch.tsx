"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { useRouter } from "next/navigation";
import { Building2, FileText, Loader2, ReceiptText, Search, X } from "lucide-react";
import { useTranslations } from "next-intl";
import { chequeApi } from "@/lib/api/leasing";
import type { UserRole } from "@/lib/rbac";

type SearchResult = {
    id: string;
    kind: "LEASE" | "PAYMENT" | "TENANT";
    title: string;
    subtitle: string;
    href: string;
};

type LeaseHit = {
    id: string;
    unitIdentifier: string | null;
    renterName: string | null;
    propertyName: string | null;
    status: string;
};

type ChequeHit = {
    id: string;
    chequeNumber: string | null;
    renterName: string | null;
    unitIdentifier: string | null;
    propertyName: string | null;
    status: string;
    seqNo: number;
};

type TenantHit = { id: string; name: string; status?: string };

const SEARCH_ROLES: UserRole[] = ["SUPER_ADMIN", "TENANT_ADMIN", "PROPERTY_MANAGER"];

export default function GlobalSearch({ role, locale }: { role?: UserRole; locale: string }) {
    const router = useRouter();
    const inputRef = useRef<HTMLInputElement>(null);
    const [open, setOpen] = useState(false);
    const [query, setQuery] = useState("");
    const [results, setResults] = useState<SearchResult[]>([]);
    const [loading, setLoading] = useState(false);
    const [failed, setFailed] = useState(false);
    const supported = role != null && SEARCH_ROLES.includes(role);
    const t = useTranslations("GlobalSearch");
    const placeholder = role === "SUPER_ADMIN"
        ? t("placeholderSuperAdmin")
        : t("placeholder");

    useEffect(() => {
        if (!supported) return;
        const onKeyDown = (event: KeyboardEvent) => {
            if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k") {
                event.preventDefault();
                setOpen(true);
            } else if (event.key === "Escape") {
                setOpen(false);
            }
        };
        document.addEventListener("keydown", onKeyDown);
        return () => document.removeEventListener("keydown", onKeyDown);
    }, [supported]);

    useEffect(() => {
        if (!open) return;
        requestAnimationFrame(() => inputRef.current?.focus());
    }, [open]);

    // The overlay is portalled to <body>, which only exists after mount.
    const [mounted, setMounted] = useState(false);
    useEffect(() => setMounted(true), []);

    useEffect(() => {
        const token = query.trim().toLowerCase();
        if (!open || token.length < 2 || !role) {
            return;
        }

        const controller = new AbortController();
        const timer = window.setTimeout(async () => {
            setLoading(true);
            setFailed(false);
            const safeJson = async (url: string): Promise<unknown | null> => {
                try {
                    const response = await fetch(url, { signal: controller.signal });
                    return response.ok ? await response.json() : null;
                } catch {
                    return null;
                }
            };

            const encoded = encodeURIComponent(query.trim());
            const [leasePayload, chequePayload, tenantPayload] = await Promise.all([
                safeJson(`/api/proxy/v1/leases/paged?search=${encoded}&page=0&size=6`),
                // Server-side search across the whole cheque register. Fetching a
                // fixed window and filtering here instead would silently miss
                // anything outside it — an older cheque would render "No results"
                // rather than an error.
                chequeApi.list({ search: query.trim(), page: 0, size: 6 }).catch(() => null),
                role === "SUPER_ADMIN" ? safeJson("/api/proxy/admin/tenants") : Promise.resolve(null),
            ]);
            if (controller.signal.aborted) return;

            const leasePage = leasePayload as { content?: LeaseHit[] } | null;
            const tenantRows = tenantPayload as TenantHit[] | null;
            const next: SearchResult[] = [];

            for (const lease of leasePage?.content ?? []) {
                next.push({
                    id: lease.id,
                    kind: "LEASE",
                    title: [lease.unitIdentifier, lease.renterName].filter(Boolean).join(" · ") || t("leaseFallback"),
                    subtitle: [lease.propertyName, lease.status].filter(Boolean).join(" · "),
                    href: `/${locale}/dashboard/leases/${lease.id}`,
                });
            }

            // Already filtered and capped server-side.
            const chequeRows = (chequePayload?.content ?? []) as ChequeHit[];
            for (const cheque of chequeRows) {
                next.push({
                    id: cheque.id,
                    kind: "PAYMENT",
                    title: cheque.chequeNumber
                        ? t("cheque", { number: cheque.chequeNumber })
                        : t("installment", { number: cheque.seqNo }),
                    subtitle: [cheque.renterName, cheque.unitIdentifier, cheque.status]
                        .filter(Boolean).join(" · "),
                    href: `/${locale}/dashboard/collections?tab=all&search=${encoded}`,
                });
            }

            for (const tenant of (tenantRows ?? []).filter((item) =>
                item.name.toLowerCase().includes(token),
            ).slice(0, 6)) {
                next.push({
                    id: tenant.id,
                    kind: "TENANT",
                    title: tenant.name,
                    subtitle: [tenant.status, tenant.id].filter(Boolean).join(" · "),
                    href: `/${locale}/superadmin/tenants?search=${encodeURIComponent(tenant.name)}`,
                });
            }

            setResults(next);
            // Only claim "no matches" when every source actually answered. If a
            // source errored and we found nothing, saying "no results" is the
            // same silent wrong answer this component was fixed to stop telling:
            // the user cannot tell "it isn't there" from "we couldn't look".
            const anySourceFailed = leasePayload == null || chequePayload == null
                || (role === "SUPER_ADMIN" && tenantPayload == null);
            setFailed(anySourceFailed && next.length === 0);
            setLoading(false);
        }, 250);

        return () => {
            window.clearTimeout(timer);
            controller.abort();
        };
    }, [locale, open, query, role]);

    const grouped = useMemo(() => ({
        leases: results.filter((item) => item.kind === "LEASE"),
        payments: results.filter((item) => item.kind === "PAYMENT"),
        tenants: results.filter((item) => item.kind === "TENANT"),
    }), [results]);

    if (!supported) {
        return <div className="flex-1" />;
    }

    const choose = (result: SearchResult) => {
        setOpen(false);
        setQuery("");
        router.push(result.href);
    };

    const changeQuery = (value: string) => {
        setQuery(value);
        setResults([]);
        setFailed(false);
        setLoading(value.trim().length >= 2);
    };

    const resultGroup = (label: string, items: SearchResult[], icon: React.ReactNode) => {
        if (items.length === 0) return null;
        return (
            <div>
                <p className="px-3 py-1.5 text-[10px] font-semibold uppercase tracking-wider text-muted flex items-center gap-1.5">
                    {icon}{label}
                </p>
                {items.map((item) => (
                    <button
                        key={`${item.kind}-${item.id}`}
                        type="button"
                        onClick={() => choose(item)}
                        className="w-full text-left px-3 py-2.5 rounded-lg hover:bg-input focus:bg-input focus:outline-none"
                    >
                        <span className="block text-sm font-medium text-foreground">{item.title}</span>
                        <span className="block text-xs text-muted truncate">{item.subtitle}</span>
                    </button>
                ))}
            </div>
        );
    };

    return (
        <div className="flex-1 max-w-[420px]" data-tour="topbar-search">
            <button
                type="button"
                onClick={() => setOpen(true)}
                aria-label={placeholder}
                aria-keyshortcuts="Meta+K Control+K"
                className="hidden lg:flex w-full items-center gap-2 px-3 h-9 bg-[var(--sand-100)] border border-border rounded-[var(--radius)] hover:border-primary/40 transition-colors cursor-pointer"
            >
                <Search size={14} className="text-[var(--ink-500)] shrink-0" />
                <span className="text-[13px] text-[var(--ink-500)] flex-1 min-w-0 truncate whitespace-nowrap text-start">{placeholder}</span>
                <kbd className="text-[11px] text-[var(--ink-500)] px-1.5 py-0.5 border border-border rounded font-mono">⌘K</kbd>
            </button>

            {/*
              * Portalled to <body> on purpose. TopHeader is a flex child with
              * z-30, which makes it a stacking context, so a z-[100] overlay
              * rendered inside it can still only reach 30 against its siblings —
              * and MvpSidebar sits at z-40, painting straight over the palette.
              */}
            {open && mounted && createPortal(
                <div className="fixed inset-0 z-[100] bg-black/30 flex items-start justify-center pt-[12vh] px-4" onMouseDown={() => setOpen(false)}>
                    <section
                        role="dialog"
                        aria-modal="true"
                        aria-label={t("dialogLabel")}
                        onMouseDown={(event) => event.stopPropagation()}
                        className="w-full max-w-2xl bg-surface border border-border rounded-xl shadow-2xl overflow-hidden"
                    >
                        <div className="h-14 px-4 flex items-center gap-3 border-b border-border">
                            {loading ? <Loader2 size={18} className="text-primary animate-spin" /> : <Search size={18} className="text-muted" />}
                            <input
                                ref={inputRef}
                                value={query}
                                onChange={(event) => changeQuery(event.target.value)}
                                aria-label={t("inputLabel")}
                                placeholder={placeholder}
                                className="flex-1 bg-transparent outline-none text-sm text-foreground placeholder:text-muted"
                            />
                            <button type="button" aria-label={t("close")} onClick={() => setOpen(false)} className="p-1.5 rounded-md hover:bg-input text-muted">
                                <X size={16} />
                            </button>
                        </div>
                        <div className="max-h-[55vh] overflow-y-auto p-2">
                            {query.trim().length < 2 ? (
                                <p className="px-3 py-8 text-center text-sm text-muted">{t("minChars")}</p>
                            ) : failed ? (
                                <p className="px-3 py-8 text-center text-sm text-error">{t("unavailable")}</p>
                            ) : !loading && results.length === 0 ? (
                                <p className="px-3 py-8 text-center text-sm text-muted">{t("noResults")}</p>
                            ) : (
                                <div className="space-y-2">
                                    {resultGroup(t("groupLeases"), grouped.leases, <FileText size={12} />)}
                                    {resultGroup(t("groupPayments"), grouped.payments, <ReceiptText size={12} />)}
                                    {resultGroup(t("groupTenants"), grouped.tenants, <Building2 size={12} />)}
                                </div>
                            )}
                        </div>
                    </section>
                </div>,
                document.body,
            )}
        </div>
    );
}
