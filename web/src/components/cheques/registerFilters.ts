import type { ChequeMode, ChequeStatus } from "@/lib/api/leasing";

/**
 * The cheque register's filters and the URL query that carries them (#85).
 *
 * A link from the dashboard or a notification — `/finance/cheques?status=BOUNCED`
 * — has to open a filtered register, and a filtered register has to be a URL a
 * user can copy. Values the register cannot ask for (a DRAFT status, a typo) are
 * dropped rather than sent: the server would never answer them, and a register
 * showing "no rows" for a bad link reads as "no bounced cheques".
 */
export type RegisterFilters = {
    status: ChequeStatus | "";
    mode: ChequeMode | "";
    propertyId: string;
    from: string;
    to: string;
    search: string;
};

export const EMPTY_REGISTER_FILTERS: RegisterFilters = { status: "", mode: "", propertyId: "", from: "", to: "", search: "" };

/** Draft rows never enter the register, so DRAFT is not a filter. */
export const REGISTER_STATUSES: ChequeStatus[] = [
    "REGISTERED", "DEPOSITED", "CLEARED", "BOUNCED", "REPLACED", "CANCELLED", "RETURNED", "ONLINE_PENDING", "TRANSFERRED",
];
export const REGISTER_MODES: ChequeMode[] = ["PDC", "CASH", "TRANSFER", "ONLINE"];

const KEYS = ["status", "mode", "propertyId", "from", "to", "search"] as const;
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

type Query = { get(name: string): string | null };

export function filtersFromQuery(query: Query | null | undefined): RegisterFilters {
    const get = (k: string) => (query?.get(k) ?? "").trim();
    const status = get("status").toUpperCase();
    const mode = get("mode").toUpperCase();
    const from = get("from");
    const to = get("to");
    return {
        status: (REGISTER_STATUSES as string[]).includes(status) ? (status as ChequeStatus) : "",
        mode: (REGISTER_MODES as string[]).includes(mode) ? (mode as ChequeMode) : "",
        propertyId: get("propertyId"),
        from: ISO_DATE.test(from) ? from : "",
        to: ISO_DATE.test(to) ? to : "",
        search: get("search"),
    };
}

/**
 * The query string for these filters, keeping any parameter that is not a filter
 * (`leaseId` opens the cash-receipt dialog) exactly as it was.
 */
export function queryWithFilters(current: string, filters: RegisterFilters): string {
    const params = new URLSearchParams(current);
    for (const k of KEYS) {
        const v = filters[k];
        if (v) params.set(k, v);
        else params.delete(k);
    }
    const s = params.toString();
    return s ? `?${s}` : "";
}
