"use client";

import { useState, useEffect, useRef, useCallback } from "react";
import { useSession } from "next-auth/react";
import Cookies from "js-cookie";
import { useRouter, usePathname } from "next/navigation";
import { useTranslations } from "next-intl";
import { Check, ChevronsUpDown } from "lucide-react";
import { cn } from "@/lib/utils";
import { hasPermission, type UserRole } from "@/lib/rbac";

type Tenant = { id: string; name: string };

export function TenantSwitcher({ isCollapsed }: { isCollapsed: boolean }) {
    // The switcher had no useTranslations at all, so its entire UI — including
    // the organization name row shown on every dashboard page — stayed English
    // in the Arabic locale.
    const t = useTranslations("TenantSwitcher");
    const { data: session } = useSession();
    const router = useRouter();
    const pathname = usePathname();
    const [tenants, setTenants] = useState<Tenant[]>([]);
    const [isOpen, setIsOpen] = useState(false);
    const [activeTenant, setActiveTenant] = useState<Tenant | null>(null);
    const [tenantQuery, setTenantQuery] = useState("");

    const userExt = session?.user;
    const userRole = userExt?.role as UserRole | undefined;
    const canSwitch = hasPermission(userRole, 'canSwitchTenants');

    const fetchTenants = useCallback(async () => {
        try {
            // Use the /me/tenants endpoint which is role-aware
            const res = await fetch("/api/proxy/auth/me/tenants");
            if (res.ok) {
                const data = await res.json();
                setTenants(data);

                // Prefer the saved context, then the signed-in user's tenant,
                // and finally the first role-authorized membership.
                const preferredTenantId = Cookies.get("active_tenant_id") || userExt?.tenantId;
                const selected = data.find((t: Tenant) => t.id === preferredTenantId) || data[0];
                if (selected) {
                    setActiveTenant(selected);
                    if (Cookies.get("active_tenant_id") !== selected.id) {
                        Cookies.set("active_tenant_id", selected.id, { path: "/" });
                        router.refresh();
                    }
                }
            }
        } catch (e) {
            console.error(e);
        }
    }, [router, userExt]);

    useEffect(() => {
        // The tenant endpoint is role-aware: super admins receive the full
        // switchable list, while managers and renters receive their own
        // membership. Fetch it for every authenticated role so the footer can
        // display the active organisation instead of a generic placeholder.
        if (!userExt) return;
        const timer = window.setTimeout(() => {
            void fetchTenants();
        }, 0);
        return () => window.clearTimeout(timer);
    }, [fetchTenants, userExt]);

    const handleSelect = (tenant: Tenant | null) => {
        setActiveTenant(tenant);
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
            const rect = buttonRef.current.getBoundingClientRect();
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
                aria-label={canSwitch ? t("switchOrganization") : t("currentOrganization")}
                className={cn(
                    "w-full flex items-center justify-between gap-2 p-2 rounded-[var(--radius)] border border-border bg-[var(--sand-100)] transition-all duration-150 focus:outline-none focus:ring-2 focus:ring-[var(--gold-500)]/30",
                    isCollapsed ? "justify-center" : "",
                    canSwitch ? "cursor-pointer hover:bg-[var(--sand-200)]" : "cursor-default"
                )}
            >
                {isCollapsed ? (
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
                        <div className="flex flex-col items-start text-left flex-1 min-w-0 leading-tight">
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
                    <ChevronsUpDown size={14} className="text-[var(--ink-500)] shrink-0" />
                )}
            </button>

            {/* Dropdown Menu */}
            {isOpen && canSwitch && !isCollapsed && (
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
                                        Global System View
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
