// src/lib/workspace/persist.ts
import type { Workspace } from "./store";

/**
 * Where a workspace lives (spec §7). The live one is per browser tab in
 * sessionStorage: it survives reloads and the sign-in redirect but never the
 * browser session, which matters on shared office PCs. A copy of the tab list
 * (hrefs and titles only) goes to localStorage as a seed for a browser tab that
 * starts on the bare home page. Keys carry the user and the organisation, so no
 * one sees another user's or another organisation's tabs.
 */
export const sessionKey = (userId: string, orgId: string) => `ws:${userId}:${orgId}`;
export const snapshotKey = (userId: string, orgId: string) => `miftah.workspace.v1:${userId}:${orgId}`;

function read(store: Storage | undefined, key: string): unknown {
    try {
        const raw = store?.getItem(key);
        return raw ? JSON.parse(raw) : null;
    } catch {
        return null;
    }
}

function write(store: Storage | undefined, key: string, value: unknown) {
    try {
        store?.setItem(key, JSON.stringify(value));
    } catch {
        // Quota or a blocked storage: the workspace still works for this page.
    }
}

const session = () => (typeof window === "undefined" ? undefined : window.sessionStorage);
const local = () => (typeof window === "undefined" ? undefined : window.localStorage);

export const loadSession = (userId: string, orgId: string) => read(session(), sessionKey(userId, orgId));
export const loadSnapshot = (userId: string, orgId: string) => read(local(), snapshotKey(userId, orgId));

export function save(userId: string, orgId: string, ws: Workspace) {
    write(session(), sessionKey(userId, orgId), ws);
    write(local(), snapshotKey(userId, orgId), { tabs: ws.tabs, activeId: ws.activeId, closed: [] });
}

/** Sign-out: this user's snapshots and live workspaces, in every organisation. */
export function clearUser(userId: string) {
    for (const store of [session(), local()]) {
        if (!store) continue;
        try {
            const keys = Array.from({ length: store.length }, (_, i) => store.key(i)).filter((k): k is string => !!k);
            for (const k of keys) {
                if (k.startsWith(`ws:${userId}:`) || k.startsWith(`miftah.workspace.v1:${userId}:`)) store.removeItem(k);
            }
        } catch {
            // nothing to clear
        }
    }
}
