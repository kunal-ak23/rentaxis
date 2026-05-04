/**
 * RBAC permission utilities for RentAxis
 * Central source of truth for role-based access control in the frontend
 */

export type UserRole = 'SUPER_ADMIN' | 'TENANT_ADMIN' | 'PROPERTY_MANAGER' | 'TENANT_USER' | 'RENTER';

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
} as const;

export type Permission = keyof typeof PERMISSIONS;

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
 * Get human-readable role label
 */
export function getRoleLabel(role: UserRole | string): string {
    const labels: Record<string, string> = {
        SUPER_ADMIN: 'System Admin',
        TENANT_ADMIN: 'Tenant Admin',
        PROPERTY_MANAGER: 'Property Manager',
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
