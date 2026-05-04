"use client";

import { useState, useEffect, useRef } from "react";
import { useSession } from "next-auth/react";
import Cookies from "js-cookie";
import { useRouter, usePathname } from "next/navigation";
import { Check, ChevronsUpDown } from "lucide-react";
import { cn } from "@/lib/utils";
import { hasPermission, type UserRole } from "@/lib/rbac";

type Tenant = { id: string; name: string };

export function TenantSwitcher({ isCollapsed }: { isCollapsed: boolean }) {
    const { data: session } = useSession();
    const router = useRouter();
    const pathname = usePathname();
    const [tenants, setTenants] = useState<Tenant[]>([]);
    const [isOpen, setIsOpen] = useState(false);
    const [activeTenant, setActiveTenant] = useState<Tenant | null>(null);

    const userExt = session?.user;
    const userRole = userExt?.role as UserRole | undefined;
    const canSwitch = hasPermission(userRole, 'canSwitchTenants');

    useEffect(() => {
        if (canSwitch) {
            fetchTenants();
        }

        // Initialize from cookie or default
        const savedTenantId = Cookies.get("active_tenant_id");
        if (savedTenantId && tenants.length > 0) {
            const found = tenants.find(t => t.id === savedTenantId);
            if (found) setActiveTenant(found);
        } else if (!canSwitch && userExt?.tenantId) {
            // For non-switching roles, they only have one tenant
            Cookies.set("active_tenant_id", userExt.tenantId, { path: "/" });
        }
    }, [canSwitch, tenants.length, userExt?.tenantId]);

    const fetchTenants = async () => {
        try {
            // Use the /me/tenants endpoint which is role-aware
            const res = await fetch("/api/proxy/auth/me/tenants");
            if (res.ok) {
                const data = await res.json();
                setTenants(data);

                // Set initial active tenant if not set
                const savedTenantId = Cookies.get("active_tenant_id");
                if (savedTenantId) {
                    const found = data.find((t: Tenant) => t.id === savedTenantId);
                    if (found) {
                        setActiveTenant(found);
                    } else if (data.length > 0) {
                        // Saved tenant not in list, select first available
                        setActiveTenant(data[0]);
                        Cookies.set("active_tenant_id", data[0].id, { path: "/" });
                        router.refresh();
                    }
                } else if (data.length > 0) {
                    setActiveTenant(data[0]);
                    Cookies.set("active_tenant_id", data[0].id, { path: "/" });
                    router.refresh();
                }
            }
        } catch (e) {
            console.error(e);
        }
    };

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
    const [dropdownPos, setDropdownPos] = useState({ top: 0, left: 0, width: 0 });

    const openDropdown = () => {
        if (!canSwitch) return;
        if (buttonRef.current) {
            const rect = buttonRef.current.getBoundingClientRect();
            setDropdownPos({ top: rect.bottom + 8, left: rect.left, width: rect.width });
        }
        setIsOpen(!isOpen);
    };

    if (!userExt?.tenantId && userRole !== 'SUPER_ADMIN') {
        // No tenant context — render nothing
        return null;
    }

    const isSuperAdmin = userRole === 'SUPER_ADMIN';
    const orgName = isSuperAdmin && !activeTenant ? "Global View" : activeTenant?.name || "—";
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
                aria-label={canSwitch ? "Switch organization" : "Current organization"}
                className={cn(
                    "w-full flex items-center justify-between gap-2 p-2 rounded-[--radius] border border-border bg-[--sand-100] transition-all duration-150 focus:outline-none focus:ring-2 focus:ring-[--gold-500]/30",
                    isCollapsed ? "justify-center" : "",
                    canSwitch ? "cursor-pointer hover:bg-[--sand-200]" : "cursor-default"
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
                            <span className="text-[12px] font-semibold text-[--ink-900] truncate w-full">
                                {orgName}
                            </span>
                            <span className="text-[10px] text-[--ink-500] truncate w-full">
                                {isSuperAdmin ? "Administering" : "Organization"}
                            </span>
                        </div>
                    </div>
                )}

                {!isCollapsed && canSwitch && (
                    <ChevronsUpDown size={14} className="text-[--ink-500] shrink-0" />
                )}
            </button>

            {/* Dropdown Menu */}
            {isOpen && canSwitch && !isCollapsed && (
                <>
                    <div className="fixed inset-0 z-40" onClick={() => setIsOpen(false)} />
                    <div
                        className="fixed bg-surface rounded-xl shadow-xl border border-border z-50 overflow-hidden"
                        style={{ top: dropdownPos.top, left: dropdownPos.left, width: dropdownPos.width }}
                    >
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

                            {tenants.map((t) => (
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
                            {tenants.length === 0 && (
                                <div className="p-3 text-center text-xs text-muted font-medium">
                                    No tenants available
                                </div>
                            )}
                        </div>
                    </div>
                </>
            )}
        </div>
    );
}
