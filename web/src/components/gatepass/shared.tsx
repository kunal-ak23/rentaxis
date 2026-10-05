"use client";

import type { ReactNode } from "react";
import NextLink from "next/link";
import { useLocale, useTranslations } from "next-intl";
import { usePathname } from "next/navigation";
import { useSession } from "next-auth/react";
import { LayoutGrid, List, Loader2 } from "lucide-react";
import { cn } from "@/lib/utils";
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

/**
 * The gate's time zone. Every property is in the UAE, passes are created in UAE
 * time (`localInstant`) and the expected-today board is Asia/Dubai, so a pass is
 * shown in Asia/Dubai too — never the browser's zone, which turned a resident
 * travelling abroad's 18:00–23:00 visit into 19:30–00:30 on their own list.
 */
export const GATE_TIME_ZONE = "Asia/Dubai";

/** `ar-AE` / `en-GB`, as the report page formats its timestamps, in the gate's zone. */
export function useDateTime() {
    const locale = useLocale();
    const tag = locale === "ar" ? "ar-AE" : "en-GB";
    return {
        dateTime: (iso: string | null | undefined) =>
            iso ? new Date(iso).toLocaleString(tag, { dateStyle: "medium", timeStyle: "short", timeZone: GATE_TIME_ZONE }) : "—",
        date: (iso: string | null | undefined) =>
            iso ? new Date(iso).toLocaleDateString(tag, { dateStyle: "medium", timeZone: GATE_TIME_ZONE }) : "—",
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
 * alone (the layout guard already refuses a wrong role; this keeps the page
 * itself honest when rendered alone). Deliberately NOT the organisation's
 * GATEPASS flag — the same call as the report (ruling 2026-09-25): the flag
 * defaults off, the backend and the mobile apps do not enforce it, and
 * organisations use gate passes without it. Gating here would hand their
 * guards a dead end while the mobile app works.
 */
export function GatePassPage({ permission, children }: { permission: Permission; children: ReactNode }) {
    const { data: session, status } = useSession();
    const role = session?.user?.role as UserRole | undefined;

    if (status === "loading") {
        return (
            <div className="flex items-center justify-center py-24" data-testid="gatepass-loading">
                <Loader2 className="w-6 h-6 animate-spin text-primary opacity-60" />
            </div>
        );
    }
    if (!hasPermission(role, permission)) return <AccessDeniedState />;
    return <>{children}</>;
}

type Tab = { href: string; key: string; permission: Permission };

const STAFF_TABS: Tab[] = [
    { href: "/dashboard/gatepass", key: "tabReport", permission: "canViewGatePassReport" },
    { href: "/dashboard/gatepass/approvals", key: "tabApprovals", permission: "canApproveGatePasses" },
    { href: "/dashboard/gatepass/gate", key: "tabGate", permission: "canWorkGate" },
    { href: "/dashboard/gatepass/settings", key: "tabSettings", permission: "canManageGatePolicy" },
];

/**
 * The strip across the staff gate-pass pages: Report · Approvals · Gate desk ·
 * Policy & visitors, each shown only to a role its page admits. Hidden when a
 * role has one page or none, so it never offers a single tab.
 */
export function GatePassTabs() {
    const t = useTranslations("GatePass");
    const locale = useLocale();
    const pathname = usePathname() ?? "";
    const { data: session } = useSession();
    const role = session?.user?.role as UserRole | undefined;
    const tabs = STAFF_TABS.filter(tab => hasPermission(role, tab.permission));
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
