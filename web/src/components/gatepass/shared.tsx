"use client";

import type { ReactNode } from "react";
import NextLink from "next/link";
import { useLocale, useTranslations } from "next-intl";
import { usePathname } from "next/navigation";
import { useSession } from "next-auth/react";
import { LayoutGrid, List, Loader2, ShieldOff } from "lucide-react";
import { cn } from "@/lib/utils";
import { useTenantFeatures } from "@/hooks/useTenantFeatures";
import { hasPermission, type Permission, type UserRole } from "@/lib/rbac";
import { AccessDeniedState } from "@/components/ui/PageStates";
import type { GatePassStatus } from "@/lib/api/gatepass";

export const STATUS_CLASSES: Record<GatePassStatus, string> = {
    PENDING_APPROVAL: "bg-warning/10 text-warning border border-warning/20",
    ACTIVE: "bg-success/10 text-success border border-success/20",
    USED: "bg-info/10 text-info border border-info/20",
    EXPIRED: "bg-input text-muted border border-border",
    CANCELLED: "bg-error/10 text-error border border-error/20",
};

export function StatusBadge({ status }: { status: GatePassStatus }) {
    const t = useTranslations("GatePass");
    return (
        <span
            data-testid={`status-${status}`}
            className={cn("inline-block px-2 py-0.5 rounded-md text-[10px] font-semibold whitespace-nowrap", STATUS_CLASSES[status] ?? "bg-input text-muted")}
        >
            {t(`status.${status}`)}
        </span>
    );
}

/** `ar-AE` / `en-GB`, as the report page formats its timestamps. */
export function useDateTime() {
    const locale = useLocale();
    const tag = locale === "ar" ? "ar-AE" : "en-GB";
    return {
        dateTime: (iso: string | null | undefined) =>
            iso ? new Date(iso).toLocaleString(tag, { dateStyle: "medium", timeStyle: "short" }) : "—",
        date: (iso: string | null | undefined) =>
            iso ? new Date(iso).toLocaleDateString(tag, { dateStyle: "medium" }) : "—",
    };
}

export const inputClass =
    "w-full border border-border rounded-lg bg-surface px-3 py-2 text-sm text-foreground focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none";
export const labelClass = "block text-[11px] font-semibold text-muted uppercase tracking-wider mb-1";
export const primaryButton =
    "cursor-pointer inline-flex items-center justify-center gap-2 bg-primary text-primary-foreground px-4 py-2 rounded-lg text-xs font-semibold hover:opacity-90 transition-all disabled:opacity-50 disabled:cursor-not-allowed";
export const secondaryButton =
    "cursor-pointer inline-flex items-center justify-center gap-2 border border-border text-foreground px-3 py-2 rounded-lg text-xs font-semibold hover:bg-input transition-all disabled:opacity-50 disabled:cursor-not-allowed";

/** A visible error line under a form or above a list — never a silent failure. */
export function ErrorLine({ message, testId = "gatepass-error" }: { message: string | null; testId?: string }) {
    if (!message) return null;
    return (
        <p role="alert" data-testid={testId} className="text-xs text-error bg-error/5 border border-error/20 rounded-lg px-3 py-2">
            {message}
        </p>
    );
}

/**
 * The page-level gate every working gate-pass screen sits behind: the role
 * first (the layout guard already refuses a wrong role; this keeps the page
 * itself honest when rendered alone), then the organisation's GATEPASS flag,
 * like the Meetings and Listings pages it sits next to in the nav. The report
 * page does not use this — it stays role-only (ruling 2026-09-25).
 */
export function GatePassPage({ permission, children }: { permission: Permission; children: ReactNode }) {
    const t = useTranslations("GatePass");
    const { data: session, status } = useSession();
    const role = session?.user?.role as UserRole | undefined;
    const { isEnabled, loaded } = useTenantFeatures();

    if (status === "loading" || (status === "authenticated" && hasPermission(role, permission) && !loaded)) {
        return (
            <div className="flex items-center justify-center py-24" data-testid="gatepass-loading">
                <Loader2 className="w-6 h-6 animate-spin text-primary opacity-60" />
            </div>
        );
    }
    if (!hasPermission(role, permission)) return <AccessDeniedState />;
    if (!isEnabled("GATEPASS")) {
        return (
            <div className="max-w-4xl mx-auto py-16" data-testid="gatepass-feature-off">
                <div className="bg-surface rounded-xl p-12 shadow-sm border border-border text-center">
                    <ShieldOff size={48} className="mx-auto text-muted mb-4" />
                    <h2 className="text-lg font-bold text-foreground mb-2">{t("featureOffTitle")}</h2>
                    <p className="text-sm text-muted max-w-md mx-auto">{t("featureOffBody")}</p>
                </div>
            </div>
        );
    }
    return <>{children}</>;
}

type Tab = { href: string; key: string; permission: Permission; flagged: boolean };

const STAFF_TABS: Tab[] = [
    { href: "/dashboard/gatepass", key: "tabReport", permission: "canViewGatePassReport", flagged: false },
    { href: "/dashboard/gatepass/approvals", key: "tabApprovals", permission: "canApproveGatePasses", flagged: true },
    { href: "/dashboard/gatepass/gate", key: "tabGate", permission: "canWorkGate", flagged: true },
    { href: "/dashboard/gatepass/settings", key: "tabSettings", permission: "canManageGatePolicy", flagged: true },
];

/**
 * The strip across the staff gate-pass pages: Report · Approvals · Gate desk ·
 * Policy & visitors, each shown only to a role its page admits (and, for the
 * working screens, only with the GATEPASS flag on). Hidden when a role has one
 * page or none, so it never offers a single tab.
 */
export function GatePassTabs() {
    const t = useTranslations("GatePass");
    const locale = useLocale();
    const pathname = usePathname() ?? "";
    const { data: session } = useSession();
    const role = session?.user?.role as UserRole | undefined;
    const { isEnabled } = useTenantFeatures();
    const tabs = STAFF_TABS.filter(tab => hasPermission(role, tab.permission) && (!tab.flagged || isEnabled("GATEPASS")));
    if (tabs.length < 2) return null;
    const path = pathname.replace(/^\/(en|ar)(?=\/|$)/, "");
    return (
        <nav className="flex gap-1 border-b border-border mb-5 overflow-x-auto" aria-label={t("tabsLabel")} data-testid="gatepass-tabs">
            {tabs.map(tab => {
                const active = path === tab.href;
                return (
                    <NextLink
                        key={tab.href}
                        href={`/${locale}${tab.href}`}
                        aria-current={active ? "page" : undefined}
                        className={cn(
                            "px-3 py-2 text-xs font-semibold whitespace-nowrap border-b-2 -mb-px transition-colors",
                            active ? "border-primary text-foreground" : "border-transparent text-muted hover:text-foreground",
                        )}
                    >
                        {t(tab.key)}
                    </NextLink>
                );
            })}
        </nav>
    );
}

/** Client-side paging for the lists the API returns whole. */
export function pageOf<T>(rows: T[], page: number, size: number): T[] {
    return rows.slice((page - 1) * size, page * size);
}

export function ViewToggle({ value, onChange }: { value: "table" | "cards"; onChange: (v: "table" | "cards") => void }) {
    const t = useTranslations("GatePass");
    return (
        <div className="flex items-center bg-input rounded-lg p-0.5 border border-border">
            {(["table", "cards"] as const).map(v => (
                <button
                    key={v}
                    type="button"
                    onClick={() => onChange(v)}
                    aria-pressed={value === v}
                    className={cn(
                        "px-3 py-1.5 rounded-md text-xs font-medium cursor-pointer flex items-center gap-1.5",
                        value === v ? "bg-surface text-foreground shadow-sm border border-border" : "text-muted hover:text-foreground",
                    )}
                >
                    {v === "table" ? <List size={13} /> : <LayoutGrid size={13} />} {v === "table" ? t("viewTable") : t("viewCards")}
                </button>
            ))}
        </div>
    );
}
