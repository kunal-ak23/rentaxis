"use client";

import { useState, useEffect, useRef } from "react";
import { useSession } from "next-auth/react";
import Cookies from "js-cookie";
import { useRouter, usePathname } from "next/navigation";
import { Building2, Check, ChevronsUpDown } from "lucide-react";
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

    if (!userExt?.tenantId && userRole !== 'SUPER_ADMIN') return null;
    if (!canSwitch) return null; // Hide for single-tenant users (renters, tenant users, etc.)

    const isSuperAdmin = userRole === 'SUPER_ADMIN';

    return (
        <div className="relative px-3">
            <button
                ref={buttonRef}
                onClick={openDropdown}
                aria-label="Switch organization"
                className={cn(
                    "w-full flex items-center justify-between bg-input border border-border p-2 rounded-xl transition-all duration-200 hover:bg-input/80 focus:outline-none focus:ring-2 focus:ring-primary/20",
                    isCollapsed ? "justify-center" : "",
                    !canSwitch && "cursor-default hover:bg-input",
                    canSwitch && "cursor-pointer"
                )}
            >
                {isCollapsed ? (
                    <div className="w-8 h-8 rounded-lg bg-surface shadow-sm border border-border/50 flex items-center justify-center">
                        <Building2 size={14} className="text-primary" />
                    </div>
                ) : (
                    <div className="flex items-center gap-3 overflow-hidden">
                        <div className="w-8 h-8 rounded-lg bg-surface shadow-sm border border-border/50 flex items-center justify-center shrink-0">
                            <Building2 size={14} className="text-primary" />
                        </div>
                        <div className="flex flex-col items-start truncate overflow-hidden text-left relative z-10 w-full" style={{ maxWidth: '140px' }}>
                            <span className="text-[10px] font-bold text-muted uppercase tracking-widest leading-none mb-0.5">
                                {isSuperAdmin ? "Administering" : "Organization"}
                            </span>
                            <span className="text-xs font-bold text-foreground truncate w-full">
                                {isSuperAdmin && !activeTenant ? "Global View" : activeTenant?.name || "Select Tenant"}
                            </span>
                        </div>
                    </div>
                )}

                {!isCollapsed && canSwitch && (
                    <ChevronsUpDown size={14} className="text-muted shrink-0" />
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
