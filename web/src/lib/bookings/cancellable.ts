import { BUSINESS_TIME_ZONE } from "@/lib/businessDate";

type BookingLike = {
    status: string;
    resourceType: string;
    preferredDate: string | null;
    preferredStartTime?: string | null;
};

/** Dubai wall-clock "now" as `yyyy-MM-ddTHH:mm`, comparable with a booking's slot. */
export function dubaiNowStamp(now: Date = new Date()): string {
    const parts = Object.fromEntries(new Intl.DateTimeFormat("en-GB", {
        timeZone: BUSINESS_TIME_ZONE, year: "numeric", month: "2-digit", day: "2-digit",
        hour: "2-digit", minute: "2-digit", hourCycle: "h23",
    }).formatToParts(now).map(p => [p.type, p.value]));
    return `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}`;
}

/**
 * Whether the Tenant may cancel this booking (tutorial 24): any PENDING request,
 * and an APPROVED amenity booking whose slot has not started — the backend's rule
 * (BookingService.cancel / BookingFeeService.beforeSlot), which also reverses its
 * fee. Approved parking is released, not cancelled.
 */
export function tenantCanCancel(b: BookingLike, nowStamp: string = dubaiNowStamp()): boolean {
    if (b.status === "PENDING") return true;
    if (b.status !== "APPROVED" || b.resourceType !== "AMENITY") return false;
    if (!b.preferredDate) return true;
    const start = `${b.preferredDate}T${(b.preferredStartTime ?? "00:00").slice(0, 5)}`;
    return nowStamp < start;
}
