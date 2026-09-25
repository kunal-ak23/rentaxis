"use client";

import { useState, useEffect, useRef } from "react";
import { useSession } from "next-auth/react";
import Cookies from "js-cookie";
import { useRouter, usePathname } from "next/navigation";
import { useTranslations } from "next-intl";
import { Check, ChevronsUpDown } from "lucide-react";
import { cn } from "@/lib/utils";
import { hasPermission, type UserRole } from "@/lib/rbac";
import { useMyOrgs } from "@/components/nav/orgStore";

type Tenant = { id: string; name: string };

/**
 * The organisation switcher. Since PR #363 R1 it is ONE instance, in the top
 * header at every width (`responsive`: the name shows from xl up, the
 * initials alone below, and the menu opens either way). Its list comes from
 * the session-wide cache in orgStore, shared with the panel's read-only name.
 */
export function TenantSwitcher({ isCollapsed, responsive = false }: { isCollapsed: boolean; responsive?: boolean }) {
    // The switcher had no useTranslations at all, so its entire UI — including
    // the organization name row shown on every dashboard page — stayed English
    // in the Arabic locale.
    const t = useTranslations("TenantSwitcher");
    const { data: session } = useSession();
    const router = useRouter();
    const pathname = usePathname();
    const [isOpen, setIsOpen] = useState(false);
    const [tenantQuery, setTenantQuery] = useState("");
    const { orgs, active } = useMyOrgs();
    const tenants: Tenant[] = orgs ?? [];
    // An explicit pick (incl. SA's global view) wins until the hard reload it triggers.
    const [picked, setPicked] = useState<{ tenant: Tenant | null } | null>(null);
    const activeTenant = picked ? picked.tenant : active;

    const userExt = session?.user;
    const userRole = userExt?.role as UserRole | undefined;
    const canSwitch = hasPermission(userRole, 'canSwitchTenants');

    // Persist the resolved organisation as the request context once, as the
    // old per-mount fetch did (the proxy reads the cookie).
    useEffect(() => {
        if (!active) return;
        if (Cookies.get("active_tenant_id") !== active.id) {
            Cookies.set("active_tenant_id", active.id, { path: "/" });
            router.refresh();
        }
    }, [active, router]);

    const handleSelect = (tenant: Tenant | null) => {
        setPicked({ tenant });
        if (tenant) {
            Cookies.set("active_tenant_id", tenant.id, { path: "/" });
        } else {
            Cookies.remove("active_tenant_id", { path: "/" });
        }
        setIsOpen(false);
        // Hard navigate to dashboard to reload all data with new tenant context
        const locale = pathname.startsWith('/ar') ? 'ar' : 'en';
        window.location.href = `/${locale}/dashboard`;
    };

    const buttonRef = useRef<HTMLButtonElement>(null);
    const [dropdownPos, setDropdownPos] = useState<{
        left: number;
        width: number;
        top?: number;
        bottom?: number;
    }>({ left: 0, width: 0 });

    const openDropdown = () => {
        if (!canSwitch) return;
        if (buttonRef.current) {
            const r = buttonRef.current.getBoundingClientRect();
            // At least 260px wide (the header button can be just the initials),
            // kept inside the viewport in both directions.
            const width = Math.max(r.width, 260);
            const left = Math.min(Math.max(8, r.left), Math.max(8, window.innerWidth - width - 8));
            const rect = { top: r.top, bottom: r.bottom, left, width };
            // Estimate dropdown height: max-h-[200px] + 1px borders + 4px padding ≈ 210px.
            // If there isn't room below, anchor by `bottom` so the menu grows upward.
            const ESTIMATED_HEIGHT = 220;
            const spaceBelow = window.innerHeight - rect.bottom;
            const openUpward = spaceBelow < ESTIMATED_HEIGHT;
            setDropdownPos(
                openUpward
                    ? {
                          bottom: window.innerHeight - rect.top + 8,
                          left: rect.left,
                          width: rect.width,
                      }
                    : {
                          top: rect.bottom + 8,
                          left: rect.left,
                          width: rect.width,
                      }
            );
        }
        if (isOpen) setTenantQuery("");
        setIsOpen(!isOpen);
    };

    const filteredTenants = tenantQuery.trim()
        ? tenants.filter((tenant) => tenant.name.toLowerCase().includes(tenantQuery.trim().toLowerCase()))
        : tenants;

    if (!userExt?.tenantId && userRole !== 'SUPER_ADMIN') {
        // No tenant context — render nothing
        return null;
    }

    const isSuperAdmin = userRole === 'SUPER_ADMIN';
    const orgName = isSuperAdmin && !activeTenant ? t("globalView") : activeTenant?.name || "—";
    const orgInitials = (orgName || "")
        .split(/\s+/)
        .slice(0, 2)
        .map((s) => s[0]?.toUpperCase() ?? "")
        .join("") || "OR";

    return (
        <div className="relative">
            <button
                ref={buttonRef}
                onClick={openDropdown}
                data-tour="tenant-switcher"
                data-testid="org-switcher-button"
                aria-expanded={canSwitch ? isOpen : undefined}
                aria-label={canSwitch ? t("switchOrganization") : t("currentOrganization")}
                // Below xl the button shows initials only; the name is still readable on hover (PR #363 follow-up).
                title={orgName}
                className={cn(
                    "w-full flex items-center justify-between gap-2 p-2 rounded-[var(--radius)] border border-border bg-[var(--sand-100)] transition-all duration-150 focus:outline-none focus:ring-2 focus:ring-[var(--gold-500)]/30",
                    isCollapsed && !responsive ? "justify-center" : "",
                    responsive && "p-1 xl:p-2",
                    canSwitch ? "cursor-pointer hover:bg-[var(--sand-200)]" : "cursor-default"
                )}
            >
                {isCollapsed && !responsive ? (
                    <div
                        className="w-8 h-8 rounded-full flex items-center justify-center text-[11px] font-semibold"
                        style={{ background: 'var(--ink-900)', color: 'var(--gold-500)' }}
                    >
                        {orgInitials}
                    </div>
                ) : (
                    <div className="flex items-center gap-2 overflow-hidden flex-1 min-w-0">
                        <div
                            className="w-8 h-8 rounded-full flex items-center justify-center text-[11px] font-semibold shrink-0"
                            style={{ background: 'var(--ink-900)', color: 'var(--gold-500)' }}
                        >
                            {orgInitials}
                        </div>
                        <div className={cn("flex-col items-start text-start flex-1 min-w-0 leading-tight", responsive ? "hidden xl:flex" : "flex")}>
                            <span className="text-[12px] font-semibold text-[var(--ink-900)] truncate w-full">
                                {orgName}
                            </span>
                            <span className="text-[10px] text-[var(--ink-500)] truncate w-full">
                                {isSuperAdmin ? t("administering") : t("organization")}
                            </span>
                        </div>
                    </div>
                )}

                {!isCollapsed && canSwitch && (
                    <ChevronsUpDown size={14} className={cn("text-[var(--ink-500)] shrink-0", responsive && "hidden xl:block")} />
                )}
            </button>

            {/* Dropdown Menu */}
            {isOpen && canSwitch && (!isCollapsed || responsive) && (
                <>
                    <div className="fixed inset-0 z-40" onClick={() => setIsOpen(false)} />
                    <div
                        className="fixed bg-surface rounded-xl shadow-xl border border-border z-50 overflow-hidden"
                        style={{
                            top: dropdownPos.top,
                            bottom: dropdownPos.bottom,
                            left: dropdownPos.left,
                            width: dropdownPos.width,
                        }}
                    >
                        {isSuperAdmin && (
                            <div className="p-2 border-b border-border">
                                <input
                                    aria-label={t("searchOrganizations")}
                                    placeholder={t("searchOrganizationsPlaceholder")}
                                    value={tenantQuery}
                                    onChange={(event) => setTenantQuery(event.target.value)}
                                    onClick={(event) => event.stopPropagation()}
                                    className="w-full rounded-lg border border-border bg-input px-2.5 py-2 text-xs text-foreground placeholder:text-muted focus:outline-none focus:ring-2 focus:ring-primary/20"
                                />
                            </div>
                        )}
                        <div className="max-h-[200px] overflow-y-auto p-1">
                            {isSuperAdmin && (
                                <>
                                    <button
                                        onClick={() => handleSelect(null)}
                                        className={cn(
                                            "w-full flex items-center justify-between p-2.5 rounded-lg text-xs font-bold transition-all duration-200 cursor-pointer focus:outline-none focus:ring-2 focus:ring-primary/20",
                                            !activeTenant ? "bg-primary/5 text-primary" : "text-foreground hover:bg-input"
                                        )}
                                    >
                                        {t("globalView")}
                                        {!activeTenant && <Check size={14} className="text-primary" />}
                                    </button>
                                    <div className="h-px bg-border my-1 mx-2" />
                                </>
                            )}

                            {filteredTenants.map((t) => (
                                <button
                                    key={t.id}
                                    onClick={() => handleSelect(t)}
                                    className={cn(
                                        "w-full flex items-center justify-between p-2.5 rounded-lg text-xs font-bold transition-all duration-200 cursor-pointer focus:outline-none focus:ring-2 focus:ring-primary/20",
                                        activeTenant?.id === t.id ? "bg-primary/5 text-primary" : "text-foreground hover:bg-input"
                                    )}
                                >
                                    <span className="truncate">{t.name}</span>
                                    {activeTenant?.id === t.id && <Check size={14} className="text-primary shrink-0" />}
                                </button>
                            ))}
                            {filteredTenants.length === 0 && (
                                <div className="p-3 text-center text-xs text-muted font-medium">
                                    {tenants.length === 0 ? t("noTenantsAvailable") : t("noMatchingOrganizations")}
                                </div>
                            )}
                        </div>
                    </div>
                </>
            )}
        </div>
    );
}
