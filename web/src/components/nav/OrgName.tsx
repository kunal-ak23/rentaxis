"use client";
import { useSession } from "next-auth/react";
import { useTranslations } from "next-intl";
import { useMyOrgs } from "./orgStore";

/**
 * The section panel's read-only organisation name. Switching lives in the
 * header's TenantSwitcher, the one switcher on the page (PR #363 R1).
 */
export function OrgName() {
    const t = useTranslations("TenantSwitcher");
    const { data: session } = useSession();
    const { active } = useMyOrgs();
    const isSuperAdmin = session?.user?.role === "SUPER_ADMIN";
    if (!session?.user?.tenantId && !isSuperAdmin) return null;
    const name = active?.name ?? (isSuperAdmin ? t("globalView") : "—");
    return (
        <div data-testid="panel-org-name" className="min-w-0 px-1 leading-tight">
            <div className="truncate text-[12px] font-semibold text-[var(--ink-900)]">{name}</div>
            <div className="truncate text-[10px] text-[var(--ink-500)]">{isSuperAdmin ? t("administering") : t("organization")}</div>
        </div>
    );
}
