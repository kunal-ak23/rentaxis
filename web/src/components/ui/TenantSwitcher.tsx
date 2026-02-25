"use client";

import { useState, useEffect } from "react";
import { useSession } from "next-auth/react";
import Cookies from "js-cookie";
import { useRouter } from "next/navigation";
import { Building2, Check, ChevronsUpDown } from "lucide-react";
import { cn } from "@/lib/utils";

type Tenant = { id: string; name: string };

export function TenantSwitcher({ isCollapsed }: { isCollapsed: boolean }) {
    const { data: session } = useSession();
    const router = useRouter();
    const [tenants, setTenants] = useState<Tenant[]>([]);
    const [isOpen, setIsOpen] = useState(false);
    const [activeTenant, setActiveTenant] = useState<Tenant | null>(null);

    const userExt = session?.user as any;
    const isSuperAdmin = userExt?.role === "SUPER_ADMIN";

    useEffect(() => {
        if (isSuperAdmin) {
            fetchTenants();
        }

        // Initialize from cookie or default
        const savedTenantId = Cookies.get("active_tenant_id");
        if (savedTenantId && tenants.length > 0) {
            const found = tenants.find(t => t.id === savedTenantId);
            if (found) setActiveTenant(found);
        } else if (!isSuperAdmin && userExt?.tenantId) {
            // For regular tenant users, they only have one tenant
            Cookies.set("active_tenant_id", userExt.tenantId, { path: "/" });
        }
    }, [isSuperAdmin, tenants.length, userExt?.tenantId]);

    const fetchTenants = async () => {
        try {
            const res = await fetch("/api/proxy/admin/tenants");
            if (res.ok) {
                const data = await res.json();
                setTenants(data);

                // Set initial active tenant if not set
                const savedTenantId = Cookies.get("active_tenant_id");
                if (!savedTenantId && data.length > 0) {
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
        router.refresh(); // Refresh to apply new context
    };

    if (!isSuperAdmin && !userExt?.tenantId) return null; // Defensive

    return (
        <div className="relative mb-6 px-3">
            <button
                onClick={() => isSuperAdmin && setIsOpen(!isOpen)}
                className={cn(
                    "w-full flex items-center justify-between bg-gray-50 border border-border p-2 rounded-xl transition-all hover:bg-gray-100",
                    isCollapsed ? "justify-center" : "",
                    !isSuperAdmin && "cursor-default hover:bg-gray-50"
                )}
            >
                {isCollapsed ? (
                    <div className="w-8 h-8 rounded-lg bg-white shadow-sm border border-border/50 flex items-center justify-center">
                        <Building2 size={14} className="text-primary" />
                    </div>
                ) : (
                    <div className="flex items-center gap-3 overflow-hidden">
                        <div className="w-8 h-8 rounded-lg bg-white shadow-sm border border-border/50 flex items-center justify-center shrink-0">
                            <Building2 size={14} className="text-primary" />
                        </div>
                        <div className="flex flex-col items-start truncate overflow-hidden text-left relative z-10 w-full" style={{ maxWidth: '140px' }}>
                            <span className="text-[10px] font-bold text-gray-400 uppercase tracking-widest leading-none mb-0.5">
                                {isSuperAdmin ? "Administering" : "Organization"}
                            </span>
                            <span className="text-xs font-black text-foreground truncate w-full">
                                {isSuperAdmin && !activeTenant ? "Global View" : activeTenant?.name || "Your Tenant"}
                            </span>
                        </div>
                    </div>
                )}

                {!isCollapsed && isSuperAdmin && (
                    <ChevronsUpDown size={14} className="text-gray-400 shrink-0" />
                )}
            </button>

            {/* Dropdown Menu */}
            {isOpen && isSuperAdmin && !isCollapsed && (
                <>
                    <div className="fixed inset-0 z-40" onClick={() => setIsOpen(false)} />
                    <div className="absolute top-full left-3 right-3 mt-2 bg-white rounded-xl shadow-xl border border-border z-50 overflow-hidden animate-in fade-in slide-in-from-top-2 duration-200">
                        <div className="max-h-[200px] overflow-y-auto p-1">
                            <button
                                onClick={() => handleSelect(null)}
                                className={cn(
                                    "w-full flex items-center justify-between p-2.5 rounded-lg text-xs font-bold transition-colors",
                                    !activeTenant ? "bg-primary/5 text-primary" : "text-gray-600 hover:bg-gray-50"
                                )}
                            >
                                Global System View
                                {!activeTenant && <Check size={14} className="text-primary" />}
                            </button>

                            <div className="h-px bg-border my-1 mx-2" />

                            {tenants.map((t) => (
                                <button
                                    key={t.id}
                                    onClick={() => handleSelect(t)}
                                    className={cn(
                                        "w-full flex items-center justify-between p-2.5 rounded-lg text-xs font-bold transition-colors",
                                        activeTenant?.id === t.id ? "bg-primary/5 text-primary" : "text-gray-600 hover:bg-gray-50"
                                    )}
                                >
                                    <span className="truncate">{t.name}</span>
                                    {activeTenant?.id === t.id && <Check size={14} className="text-primary shrink-0" />}
                                </button>
                            ))}
                            {tenants.length === 0 && (
                                <div className="p-3 text-center text-xs text-gray-400 font-medium">
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
