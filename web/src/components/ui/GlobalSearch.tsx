"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { Building2, FileText, Loader2, ReceiptText, Search, X } from "lucide-react";
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

type PaymentHit = {
    id: string;
    chequeNumber: string | null;
    renterName: string | null;
    unitIdentifier: string | null;
    propertyName: string | null;
    status: string;
    installmentNumber: number | null;
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
    const placeholder = role === "SUPER_ADMIN"
        ? "Search leases, tenants, cheques…"
        : "Search leases, renters, cheques…";

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
            const [leasePayload, paymentPayload, tenantPayload] = await Promise.all([
                safeJson(`/api/proxy/v1/leases/paged?search=${encoded}&page=0&size=6`),
                safeJson("/api/proxy/v1/payments?page=0&size=100"),
                role === "SUPER_ADMIN" ? safeJson("/api/proxy/admin/tenants") : Promise.resolve(null),
            ]);
            if (controller.signal.aborted) return;

            const leasePage = leasePayload as { content?: LeaseHit[] } | null;
            const paymentPage = paymentPayload as { content?: PaymentHit[] } | PaymentHit[] | null;
            const tenantRows = tenantPayload as TenantHit[] | null;
            const next: SearchResult[] = [];

            for (const lease of leasePage?.content ?? []) {
                next.push({
                    id: lease.id,
                    kind: "LEASE",
                    title: [lease.unitIdentifier, lease.renterName].filter(Boolean).join(" · ") || "Lease",
                    subtitle: [lease.propertyName, lease.status].filter(Boolean).join(" · "),
                    href: `/${locale}/dashboard/leases/${lease.id}`,
                });
            }

            const paymentRows = Array.isArray(paymentPage) ? paymentPage : paymentPage?.content ?? [];
            for (const payment of paymentRows.filter((item) =>
                [item.chequeNumber, item.renterName, item.unitIdentifier, item.propertyName]
                    .some((value) => value?.toLowerCase().includes(token)),
            ).slice(0, 6)) {
                next.push({
                    id: payment.id,
                    kind: "PAYMENT",
                    title: payment.chequeNumber
                        ? `Cheque ${payment.chequeNumber}`
                        : `Installment ${payment.installmentNumber ?? ""}`.trim(),
                    subtitle: [payment.renterName, payment.unitIdentifier, payment.status]
                        .filter(Boolean).join(" · "),
                    href: `/${locale}/dashboard/finance/payments?renterName=${encodeURIComponent(payment.renterName ?? "")}`,
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
            setFailed(leasePayload == null && paymentPayload == null
                && (role !== "SUPER_ADMIN" || tenantPayload == null));
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
                <span className="text-[13px] text-[var(--ink-500)] flex-1 text-left">{placeholder}</span>
                <kbd className="text-[11px] text-[var(--ink-500)] px-1.5 py-0.5 border border-border rounded font-mono">⌘K</kbd>
            </button>

            {open && (
                <div className="fixed inset-0 z-[100] bg-black/30 flex items-start justify-center pt-[12vh] px-4" onMouseDown={() => setOpen(false)}>
                    <section
                        role="dialog"
                        aria-modal="true"
                        aria-label="Global search"
                        onMouseDown={(event) => event.stopPropagation()}
                        className="w-full max-w-2xl bg-surface border border-border rounded-xl shadow-2xl overflow-hidden"
                    >
                        <div className="h-14 px-4 flex items-center gap-3 border-b border-border">
                            {loading ? <Loader2 size={18} className="text-primary animate-spin" /> : <Search size={18} className="text-muted" />}
                            <input
                                ref={inputRef}
                                value={query}
                                onChange={(event) => changeQuery(event.target.value)}
                                aria-label="Search RentAxis"
                                placeholder={placeholder}
                                className="flex-1 bg-transparent outline-none text-sm text-foreground placeholder:text-muted"
                            />
                            <button type="button" aria-label="Close search" onClick={() => setOpen(false)} className="p-1.5 rounded-md hover:bg-input text-muted">
                                <X size={16} />
                            </button>
                        </div>
                        <div className="max-h-[55vh] overflow-y-auto p-2">
                            {query.trim().length < 2 ? (
                                <p className="px-3 py-8 text-center text-sm text-muted">Type at least two characters to search.</p>
                            ) : failed ? (
                                <p className="px-3 py-8 text-center text-sm text-error">Search is temporarily unavailable.</p>
                            ) : !loading && results.length === 0 ? (
                                <p className="px-3 py-8 text-center text-sm text-muted">No matching leases, cheques, or organizations.</p>
                            ) : (
                                <div className="space-y-2">
                                    {resultGroup("Leases", grouped.leases, <FileText size={12} />)}
                                    {resultGroup("Cheques and payments", grouped.payments, <ReceiptText size={12} />)}
                                    {resultGroup("Organizations", grouped.tenants, <Building2 size={12} />)}
                                </div>
                            )}
                        </div>
                    </section>
                </div>
            )}
        </div>
    );
}
