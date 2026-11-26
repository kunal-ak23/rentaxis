"use client";

import { useSession } from "next-auth/react";
import { useTenantFeatures } from "@/hooks/useTenantFeatures";
import { hasPermission, type UserRole } from "@/lib/rbac";

/** Ranges are staff-only: flag on for the org AND a viewing role. */
export function useRentalIndexEnabled(): boolean {
    const { isEnabled } = useTenantFeatures();
    const { data: session } = useSession();
    const role = session?.user?.role as UserRole | undefined;
    return isEnabled("RENTAL_INDEX") && hasPermission(role, "canViewRentalIndex");
}

/** The property tab shows for Dubai properties only (the index is DLD's). */
export function rentalIndexTabVisible(enabled: boolean, emirate: string | null | undefined): boolean {
    return enabled && emirate === "DUBAI";
}
