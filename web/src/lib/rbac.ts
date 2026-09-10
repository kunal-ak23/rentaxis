/**
 * RBAC permission utilities for RentAxis
 * Central source of truth for role-based access control in the frontend
 */

export type UserRole = 'SUPER_ADMIN' | 'TENANT_ADMIN' | 'PROPERTY_MANAGER' | 'SECURITY_GUARD' | 'TENANT_USER' | 'RENTER';

export const PERMISSIONS = {
    canManageTenants: ['SUPER_ADMIN'] as UserRole[],
    canManageUsers: ['SUPER_ADMIN', 'TENANT_ADMIN'] as UserRole[],
    canCreateProperties: ['SUPER_ADMIN', 'TENANT_ADMIN'] as UserRole[],
    canViewProperties: ['SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER'] as UserRole[],
    canCreateUnits: ['SUPER_ADMIN', 'TENANT_ADMIN'] as UserRole[],
    canManageLeases: ['SUPER_ADMIN', 'TENANT_ADMIN'] as UserRole[],
    canManageRenters: ['SUPER_ADMIN', 'TENANT_ADMIN'] as UserRole[],
    canAccessFinance: ['SUPER_ADMIN', 'TENANT_ADMIN'] as UserRole[],
    canResolveIssues: ['SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER'] as UserRole[],
    canCreateIssues: ['SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER', 'TENANT_USER'] as UserRole[],
    canViewOwnPayments: ['TENANT_USER'] as UserRole[],
    canSwitchTenants: ['SUPER_ADMIN', 'TENANT_ADMIN'] as UserRole[],
    canViewRenterPortal: ['RENTER'] as UserRole[],
    canAcceptLeases: ['RENTER'] as UserRole[],
    canDownloadContracts: ['SUPER_ADMIN', 'TENANT_ADMIN', 'RENTER'] as UserRole[],
    canManageMeetings: ['SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER'] as UserRole[],
    canCreateMeetings: ['SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER', 'RENTER'] as UserRole[],
    // Deliberately excludes SUPER_ADMIN, unlike its neighbours. This mirrors
    // GatePassController#report's @PreAuthorize("hasAnyRole('TENANT_ADMIN','PROPERTY_MANAGER')")
    // exactly — the gate-pass module scopes every read to a tenant, so a SUPER_ADMIN
    // hitting it gets a 403, and offering the nav item would only surface that as a
    // broken page. Widen this only alongside the annotation.
    canViewGatePassReport: ['TENANT_ADMIN', 'PROPERTY_MANAGER'] as UserRole[],
    // Mirrors the amenities/parking/bookings controllers' @PreAuthorize
    // hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','PROPERTY_MANAGER'). Unlike
    // canViewGatePassReport, SUPER_ADMIN is deliberately included here because
    // the backend admits it.
    canManageFacilities: ['SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER'] as UserRole[],
    // Mirrors PromotionAdminController's @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')").
    // Promotions are tenant-wide (not scoped to a single property), so
    // PROPERTY_MANAGER is deliberately excluded, unlike canManageFacilities.
    canManagePromotions: ['SUPER_ADMIN', 'TENANT_ADMIN'] as UserRole[],
} as const;

export type Permission = keyof typeof PERMISSIONS;

/**
 * Role privilege ranking — lower number == more privileged.
 * Mirrors the backend hierarchy enforced in UserController.
 */
const ROLE_RANK: Record<UserRole, number> = {
    SUPER_ADMIN: 0,
    TENANT_ADMIN: 1,
    PROPERTY_MANAGER: 2,
    TENANT_USER: 3,
    RENTER: 4,
    SECURITY_GUARD: 5,
};

/**
 * Roles a user with the given role is allowed to create/assign: their own
 * level and anything below it. A TENANT_ADMIN therefore cannot provision a
 * SUPER_ADMIN. Mirrors the server-side check in UserController.
 */
export function assignableRoles(role: UserRole | undefined): UserRole[] {
    if (!role) return [];
    const callerRank = ROLE_RANK[role];
    return (Object.keys(ROLE_RANK) as UserRole[]).filter((r) => ROLE_RANK[r] >= callerRank);
}

/**
 * Check if a role has a specific permission
 */
export function hasPermission(role: UserRole | undefined, permission: Permission): boolean {
    if (!role) return false;
    return PERMISSIONS[permission].includes(role);
}

/**
 * Check if a role is in a list of allowed roles
 */
export function hasRole(role: UserRole | undefined, allowedRoles: UserRole[]): boolean {
    if (!role) return false;
    return allowedRoles.includes(role);
}

/**
 * Translation key for a role, for use as t(getRoleLabelKey(role)) with the
 * "Roles" namespace.
 *
 * getRoleLabel below is kept for non-React callers, but it returns English
 * unconditionally — the role sits under the user's name in the top bar on
 * every page, so in Arabic it was the last English text on the shell.
 */
export function getRoleLabelKey(role: UserRole | string): string {
    return String(role);
}

/**
 * Get human-readable role label. English only — prefer getRoleLabelKey with a
 * translator anywhere the string is rendered to a user.
 */
export function getRoleLabel(role: UserRole | string): string {
    const labels: Record<string, string> = {
        SUPER_ADMIN: 'System Admin',
        TENANT_ADMIN: 'Tenant Admin',
        PROPERTY_MANAGER: 'Property Manager',
        SECURITY_GUARD: 'Security Guard',
        TENANT_USER: 'Tenant',
        RENTER: 'Renter',
    };
    return labels[role] || role.replace(/_/g, ' ');
}

export function canViewPayments(role: UserRole): boolean {
    return ['SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER'].includes(role);
}

export function canManagePayments(role: UserRole): boolean {
    return ['SUPER_ADMIN', 'TENANT_ADMIN'].includes(role);
}

export function canPayOnline(role: UserRole): boolean {
    return ['RENTER'].includes(role);
}

export function canConfigureGateway(role: UserRole): boolean {
    return ['SUPER_ADMIN', 'TENANT_ADMIN'].includes(role);
}

export function canConfigureRentSettings(role: UserRole): boolean {
    return ['SUPER_ADMIN', 'TENANT_ADMIN'].includes(role);
}

export function canConfigureFines(role: UserRole): boolean {
    return ['SUPER_ADMIN', 'TENANT_ADMIN'].includes(role);
}
