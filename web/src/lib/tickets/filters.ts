/**
 * The ticket statuses and priorities the backend knows (TicketStatus /
 * TicketPriority). Break-it R3 data3 F6: a `?status=` or `?priority=` from the
 * URL that is not one of these (a stale bookmark, a hand-edited link) is no
 * filter — `useUrlState` reads it as "ALL" and drops it — instead of a 400 the
 * Retry button could only repeat.
 */
export const TICKET_STATUSES = ["OPEN", "ASSIGNED", "IN_PROGRESS", "RESOLVED", "CLOSED", "REOPENED"] as const;
export const TICKET_PRIORITIES = ["LOW", "MEDIUM", "HIGH", "URGENT"] as const;

export function isTicketStatusParam(v: string): boolean {
    return v === "ALL" || (TICKET_STATUSES as readonly string[]).includes(v);
}

export function isTicketPriorityParam(v: string): boolean {
    return v === "ALL" || (TICKET_PRIORITIES as readonly string[]).includes(v);
}
