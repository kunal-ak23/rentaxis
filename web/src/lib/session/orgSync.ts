import Cookies from "js-cookie";

/**
 * Cross-tab organisation sync (break round 1, F3).
 *
 * The active organisation is the `active_tenant_id` cookie, shared by every
 * tab. Each tab remembers the organisation its page was loaded for (the "page
 * org"); a tab that switches announces it on a BroadcastChannel, with a
 * localStorage `storage` event as the fallback for browsers without one, so
 * every other open tab can block itself until it is reloaded.
 */
export const ACTIVE_ORG_COOKIE = "active_tenant_id";
const CHANNEL = "rentaxis-org";
const STORAGE_KEY = "rentaxis-org-change";

type OrgChange = { orgId: string; from: string; at: number };

const tabId = `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
let pageOrg: string | null = null;

/** The cookie's organisation id; "" when none is selected. */
export function readActiveOrgCookie(): string {
    return Cookies.get(ACTIVE_ORG_COOKIE) ?? "";
}

/** The organisation this tab's page was loaded for ("" = none), read once from the cookie. */
export function getPageOrg(): string {
    if (pageOrg === null) pageOrg = readActiveOrgCookie();
    return pageOrg;
}

/** This tab itself selected `orgId` (no broadcast: e.g. persisting the resolved default). */
export function setPageOrg(orgId: string): void {
    pageOrg = orgId;
}

/** Test hook. */
export function resetPageOrg(): void {
    pageOrg = null;
}

/** This tab switched organisation: remember it and tell every other tab. */
export function announceOrgChange(orgId: string): void {
    setPageOrg(orgId);
    const msg: OrgChange = { orgId, from: tabId, at: Date.now() };
    try {
        if (typeof BroadcastChannel !== "undefined") {
            const bc = new BroadcastChannel(CHANNEL);
            bc.postMessage(msg);
            bc.close();
        }
    } catch {
        // fall through to the storage event
    }
    try {
        localStorage.setItem(STORAGE_KEY, JSON.stringify(msg));
    } catch {
        // storage blocked: the proxy's 409 still stops a stale write
    }
}

/** Calls `onChange(orgId)` when ANOTHER tab switches organisation. */
export function subscribeOrgChange(onChange: (orgId: string) => void): () => void {
    const handle = (data: unknown) => {
        const msg = data as Partial<OrgChange> | null;
        if (!msg || typeof msg.orgId !== "string" || msg.from === tabId) return;
        onChange(msg.orgId);
    };

    let bc: BroadcastChannel | null = null;
    try {
        if (typeof BroadcastChannel !== "undefined") {
            bc = new BroadcastChannel(CHANNEL);
            bc.onmessage = (e: MessageEvent) => handle(e.data);
        }
    } catch {
        bc = null;
    }

    const onStorage = (e: StorageEvent) => {
        if (e.key !== STORAGE_KEY || !e.newValue) return;
        try {
            handle(JSON.parse(e.newValue));
        } catch {
            // ignore malformed values
        }
    };
    window.addEventListener("storage", onStorage);

    return () => {
        window.removeEventListener("storage", onStorage);
        bc?.close();
    };
}
