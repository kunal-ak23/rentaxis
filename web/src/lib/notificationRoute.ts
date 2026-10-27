/**
 * Where a notification's click goes. The bell (TopHeader) and the notifications
 * page used to hold the same table each; one function now serves both.
 *
 * A promotion ad's notification (PROMO_AD) has two audiences with two homes: the
 * administrators it asks to approve an ad review it under Promotions, and the
 * promoter it tells about a decision finds the ad in their own portal. Any other
 * role gets no destination (a promoter's page is refused them).
 */
export type NotificationTarget = {
    type: string;
    referenceType: string | null;
    referenceId: string | null;
};

export function notificationRoute(n: NotificationTarget, role: string | undefined): string | undefined {
    if (!(n.referenceType && n.referenceId)) return undefined;
    if (n.type === "LEASE_RENEWAL_REMINDER") return "/dashboard/renter-portal/renewals";
    switch (n.referenceType) {
        case "TICKET": return `/dashboard/tickets/${n.referenceId}`;
        case "LEASE": return `/dashboard/leases/${n.referenceId}`;
        case "PAYMENT": return "/dashboard/collections?tab=all";
        case "PROMO_AD":
            if (role === "TENANT_ADMIN" || role === "SUPER_ADMIN") return "/dashboard/promotions";
            if (role === "PROMOTER") return "/dashboard/promoter";
            return undefined;
        default: return undefined;
    }
}
