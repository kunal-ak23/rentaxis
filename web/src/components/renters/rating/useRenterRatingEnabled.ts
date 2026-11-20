"use client";

import { useSession } from "next-auth/react";
import { useTenantFeatures } from "@/hooks/useTenantFeatures";
import { hasPermission, type UserRole } from "@/lib/rbac";

/** The premium rating is staff-only: flag on for the org AND a viewing role. */
export function useRenterRatingEnabled(): boolean {
    const { isEnabled } = useTenantFeatures();
    const { data: session } = useSession();
    const role = session?.user?.role as UserRole | undefined;
    return isEnabled("RENTER_RATING") && hasPermission(role, "canViewRenterRatings");
}
