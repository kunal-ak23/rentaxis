// src/lib/workspace/store.ts
import { DASHBOARD_ROUTES, routeAllows } from "../nav/routeRegistry";
import { legacyRedirect } from "../nav/routeMap";
import type { Label } from "../nav/types";
import type { UserRole } from "../rbac";

/**
 * Workspace tabs (spec 2026-10-30, option (b)): a list of open screens, one of
 * them mounted. Pure functions over an immutable value, so the URL rules, the
 * opening rules and the load-time checks are tested without a browser.
 *
 * `href` is locale-less: path plus query, e.g. `/dashboard/leases/abc?tab=cheques`.
 */
export type TabKind = "screen" | "record";
export interface Tab {
    id: string;
    href: string;
    kind: TabKind;
    /** A nav label (re-translated on EN↔AR) or the last record title shown; null → derived from the route. */
    title: Label | string | null;
    openedAt: number;
    /** Last time the tab was active: a menu item focuses the most recently used tab on its path. */
    usedAt: number;
}
export interface Workspace {
    tabs: Tab[];
    activeId: string | null;
    /** Most recent first; for "Reopen closed tab" and Back after closing a tab. */
    closed: Tab[];
}
/** How a link asks to be opened (spec §3). */
export type OpenHow = "menu" | "query" | "record" | "new";

export const MAX_TABS = 15;
export const MAX_CLOSED = 10;
export const EMPTY: Workspace = { tabs: [], activeId: null, closed: [] };

export const pathOf = (href: string) => href.split(/[?#]/)[0];
const queryOf = (href: string) => new URLSearchParams(href.split("#")[0].split("?")[1] ?? "");

/** The registry pattern a concrete path belongs to (`/dashboard/leases/abc` → `/dashboard/leases/[id]`). */
export function routeOf(path: string): string | null {
    const segs = path.split("/");
    let best: string | null = null;
    for (const { path: pattern } of DASHBOARD_ROUTES) {
        const p = pattern.split("/");
        if (p.length !== segs.length) continue;
        if (!p.every((s, i) => s === segs[i] || /^\[.+\]$/.test(s))) continue;
        // A literal match beats a dynamic one (/leases/renewals over /leases/[id]).
        if (best === null || pattern.split("[").length < best.split("[").length) best = pattern;
    }
    return best;
}

/**
 * The query parameters that say *which* record a route shows (spec §3). Every
 * other parameter is view state. Routes with the id in the path need nothing.
 */
export const IDENTITY_PARAMS: Record<string, string[]> = {
    "/dashboard/finance/vouchers/payment": ["id"],
    "/dashboard/finance/vouchers/credit-note": ["id"],
    "/dashboard/finance/vouchers/purchase-invoice": ["id"],
    "/dashboard/finance/tenant-ledger": ["renterId"],
};

/** Path plus the route's identity parameters, in a stable order. */
export function identityOf(href: string): string {
    const path = pathOf(href);
    const keys = IDENTITY_PARAMS[routeOf(path) ?? path] ?? [];
    const q = queryOf(href);
    const parts = keys.filter(k => q.get(k)).map(k => `${k}=${q.get(k)}`);
    return parts.length ? `${path}?${parts.join("&")}` : path;
}

const touch = (tab: Tab, now: number): Tab => ({ ...tab, usedAt: now });

function activateIn(ws: Workspace, id: string, now: number): Workspace {
    return { ...ws, activeId: id, tabs: ws.tabs.map(t => (t.id === id ? touch(t, now) : t)) };
}

function insert(ws: Workspace, tab: Tab, afterActive: boolean): Workspace {
    const at = afterActive ? ws.tabs.findIndex(t => t.id === ws.activeId) : -1;
    const tabs = at < 0 ? [...ws.tabs, tab] : [...ws.tabs.slice(0, at + 1), tab, ...ws.tabs.slice(at + 1)];
    return { ...ws, tabs, activeId: tab.id };
}

export const kindOf = (href: string): TabKind => (routeOf(pathOf(href))?.includes("[") || identityOf(href) !== pathOf(href) ? "record" : "screen");

export type OpenResult = { ws: Workspace; outcome: "focused" | "opened" | "full"; tab: Tab | null };

/**
 * The opening rules (spec §3):
 * - menu: focus the most recently used tab on that path (its own query wins), else open at the end;
 * - query: focus only an exact href match, else open right after the current tab;
 * - record: focus the tab with the same identity, else open right after the current tab;
 * - new: always a new tab at the end (the strip's "+").
 * At the cap nothing opens and the caller asks the user to close a tab.
 */
export function openTab(ws: Workspace, href: string, how: OpenHow, newId: string, now: number): OpenResult {
    const path = pathOf(href);
    let hit: Tab | undefined;
    if (how === "menu") {
        hit = ws.tabs.filter(t => pathOf(t.href) === path).sort((a, b) => b.usedAt - a.usedAt)[0];
    } else if (how === "new") {
        hit = undefined;
    } else if (how === "query") {
        hit = ws.tabs.find(t => t.href === href);
    } else {
        const id = identityOf(href);
        hit = ws.tabs.find(t => identityOf(t.href) === id);
    }
    if (hit) return { ws: activateIn(ws, hit.id, now), outcome: "focused", tab: hit };
    if (ws.tabs.length >= MAX_TABS) return { ws, outcome: "full", tab: null };
    const tab: Tab = { id: newId, href, kind: kindOf(href), title: null, openedAt: now, usedAt: now };
    return { ws: insert(ws, tab, how === "query" || how === "record"), outcome: "opened", tab };
}

export function activate(ws: Workspace, id: string, now: number): Workspace {
    return ws.tabs.some(t => t.id === id) ? activateIn(ws, id, now) : ws;
}

/** In-tab navigation: the active tab follows the URL (its title is re-derived unless the route stays the same). */
export function navigateActive(ws: Workspace, href: string, newId: string, now: number): Workspace {
    const active = ws.tabs.find(t => t.id === ws.activeId);
    if (!active) {
        if (ws.tabs.length >= MAX_TABS) return ws;
        return insert(ws, { id: newId, href, kind: kindOf(href), title: null, openedAt: now, usedAt: now }, false);
    }
    const sameRecord = identityOf(active.href) === identityOf(href);
    return {
        ...ws,
        tabs: ws.tabs.map(t => (t.id === active.id
            ? { ...t, href, kind: kindOf(href), title: sameRecord ? t.title : null, usedAt: now }
            : t)),
    };
}

/**
 * A URL change the host did not start (in-page link, router.push, Back/Forward)
 * — spec §4 rules 1–3: an open tab with that href is activated; a recently
 * closed one is reopened; otherwise the active tab takes the URL.
 */
export function onUrlChange(ws: Workspace, href: string, newId: string, now: number): Workspace {
    const open = ws.tabs.find(t => t.href === href);
    if (open) return open.id === ws.activeId ? ws : activateIn(ws, open.id, now);
    const closed = ws.closed.find(t => t.href === href);
    if (closed && ws.tabs.length < MAX_TABS) {
        return insert({ ...ws, closed: ws.closed.filter(t => t.id !== closed.id) }, touch(closed, now), true);
    }
    return navigateActive(ws, href, newId, now);
}

export type FreshLoad = { ws: Workspace; replaceWith: string | null };

/**
 * A full document load (reload, sign-in redirect, organisation switch, pasted
 * link) — spec §4: landing on an open tab activates it; landing on the bare home
 * page while tabs exist goes back to the stored active tab; anything else opens
 * next to the stored active tab.
 */
export function onFreshLoad(ws: Workspace, href: string, newId: string, now: number): FreshLoad {
    const open = ws.tabs.find(t => t.href === href);
    if (open) return { ws: activateIn(ws, open.id, now), replaceWith: null };
    if (pathOf(href) === "/dashboard" && !queryOf(href).toString()) {
        const active = ws.tabs.find(t => t.id === ws.activeId) ?? ws.tabs[ws.tabs.length - 1];
        if (active) return { ws: activateIn(ws, active.id, now), replaceWith: active.href };
    }
    const r = openTab(ws, href, "record", newId, now);
    // At the cap a fresh load still has to show its page: it takes over the active tab.
    return { ws: r.outcome === "full" ? navigateActive(ws, href, newId, now) : r.ws, replaceWith: null };
}

/** Closing the active tab activates its neighbour towards the end, else towards the start. */
export function closeTab(ws: Workspace, id: string, now: number): Workspace {
    const at = ws.tabs.findIndex(t => t.id === id);
    if (at < 0) return ws;
    const tabs = ws.tabs.filter(t => t.id !== id);
    const closed = [ws.tabs[at], ...ws.closed.filter(t => t.href !== ws.tabs[at].href)].slice(0, MAX_CLOSED);
    if (ws.activeId !== id) return { ...ws, tabs, closed };
    const next = tabs[at] ?? tabs[at - 1] ?? null;
    return { tabs: next ? tabs.map(t => (t.id === next.id ? touch(t, now) : t)) : tabs, activeId: next?.id ?? null, closed };
}

export function reopenClosed(ws: Workspace, now: number): Workspace {
    const [last, ...rest] = ws.closed;
    if (!last || ws.tabs.length >= MAX_TABS) return ws;
    const existing = ws.tabs.find(t => t.href === last.href);
    if (existing) return activateIn({ ...ws, closed: rest }, existing.id, now);
    return insert({ ...ws, closed: rest }, touch(last, now), true);
}

/** Close every tab but `keepId` (and the ones the caller says hold unsaved work). */
export function closeOthers(ws: Workspace, keepId: string, keep: ReadonlySet<string> = new Set()): Workspace {
    const gone = ws.tabs.filter(t => t.id !== keepId && !keep.has(t.id));
    return {
        tabs: ws.tabs.filter(t => t.id === keepId || keep.has(t.id)),
        activeId: keepId,
        closed: [...gone.reverse(), ...ws.closed].slice(0, MAX_CLOSED),
    };
}

export function setTitle(ws: Workspace, id: string, title: Label | string | null): Workspace {
    const tab = ws.tabs.find(t => t.id === id);
    if (!tab || JSON.stringify(tab.title) === JSON.stringify(title)) return ws;
    return { ...ws, tabs: ws.tabs.map(t => (t.id === id ? { ...t, title } : t)) };
}

/** Next / previous tab, wrapping. `step` is +1 (towards the end) or -1. */
export function neighbour(ws: Workspace, step: 1 | -1): string | null {
    if (ws.tabs.length === 0) return null;
    const at = Math.max(0, ws.tabs.findIndex(t => t.id === ws.activeId));
    return ws.tabs[(at + step + ws.tabs.length) % ws.tabs.length].id;
}

/**
 * Load-time checks on a stored workspace (spec §7): moved URLs are rewritten,
 * unknown routes and routes the role cannot reach are dropped, superadmin pages
 * are never tabs, and the shape is repaired if storage held something odd.
 */
export function validate(raw: unknown, role: UserRole | undefined): Workspace {
    if (!raw || typeof raw !== "object") return EMPTY;
    const w = raw as Partial<Workspace>;
    const fix = (t: unknown): Tab | null => {
        if (!t || typeof t !== "object") return null;
        const tab = t as Partial<Tab>;
        if (typeof tab.id !== "string" || typeof tab.href !== "string" || !tab.href.startsWith("/dashboard")) return null;
        let href = tab.href;
        const moved = legacyRedirect(new URL(href, "http://x"));
        if (moved) href = moved.pathname + moved.search;
        const route = routeOf(pathOf(href));
        const entry = route ? DASHBOARD_ROUTES.find(e => e.path === route) : undefined;
        if (!entry || !role || !routeAllows(entry, role)) return null;
        const title = typeof tab.title === "string" || (tab.title && typeof tab.title === "object") ? tab.title : null;
        return {
            id: tab.id, href, kind: kindOf(href), title: moved ? null : title,
            openedAt: Number(tab.openedAt) || 0, usedAt: Number(tab.usedAt) || 0,
        };
    };
    const seen = new Set<string>();
    const tabs = (Array.isArray(w.tabs) ? w.tabs : []).map(fix)
        .filter((t): t is Tab => !!t && !seen.has(t.id) && !!seen.add(t.id))
        .slice(0, MAX_TABS);
    const closed = (Array.isArray(w.closed) ? w.closed : []).map(fix).filter((t): t is Tab => !!t).slice(0, MAX_CLOSED);
    const activeId = tabs.some(t => t.id === w.activeId) ? (w.activeId as string) : (tabs[tabs.length - 1]?.id ?? null);
    return { tabs, activeId, closed };
}
