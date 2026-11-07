"use client";

import { Fragment, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useLocale, useTranslations } from "next-intl";
import type { RailSection } from "@/lib/nav/navModel";
import type { UserRole } from "@/lib/rbac";
import { workspaceKey } from "@/lib/workspace/keys";
import { loadSession, loadSnapshot, save } from "@/lib/workspace/persist";
import {
    activate, closeOthers, closeTab, EMPTY, MAX_TABS, neighbour, onFreshLoad, onUrlChange, openTab,
    reopenClosed, setTitle, validate, type OpenHow, type Workspace,
} from "@/lib/workspace/store";
import { screenLabel } from "@/lib/workspace/titles";
import { useLabel } from "@/lib/nav/useLabel";
import { WorkspaceContext, type WorkspaceApi } from "./WorkspaceContext";
import WorkspaceStrip from "./WorkspaceStrip";

const newId = () =>
    typeof crypto !== "undefined" && "randomUUID" in crypto ? crypto.randomUUID().slice(0, 8) : Math.random().toString(36).slice(2, 10);

const stripLocale = (pathname: string) => pathname.replace(/^\/(en|ar)(?=\/|$)/, "") || "/";

/**
 * Workspace tabs (spec 2026-10-30, option (b) with tab memory, Phase 1). One
 * page is mounted: the active tab's. The address bar always shows the active
 * tab's href, so deep links, the route guard and every test keep working.
 *
 * - The tab list lives in sessionStorage per user and organisation, seeded from
 *   a localStorage snapshot only when a browser tab lands on the bare home page.
 * - URL changes the host did not start follow spec §4's rules (activate an open
 *   tab, reopen a closed one, else the active tab takes the URL); a full page
 *   load follows the fresh-load rules.
 * - The page is keyed by the active tab, so a switch between two tabs on the
 *   same route remounts it and it reads its own query (spec §4, found by running it).
 * - Scroll is stored per tab and restored once the content is tall enough.
 *
 * With `enabled` false it renders `children` and only keeps the browser tab's
 * title right (Phase 0, for every user).
 */
export default function WorkspaceHost({
    enabled, userId, orgId, role, rail, mainClassName, children,
}: {
    enabled: boolean;
    userId: string;
    orgId: string;
    role: UserRole | undefined;
    rail: RailSection[];
    /** The layout's classes for <main>, which the host renders so the strip sits above it. */
    mainClassName: string;
    children: React.ReactNode;
}) {
    const pathname = usePathname();
    const search = useSearchParams();
    const router = useRouter();
    const locale = useLocale();
    const t = useTranslations("Workspace");
    const label = useLabel();
    const query = search.toString();
    const href = `${stripLocale(pathname)}${query ? `?${query}` : ""}`;

    const [ws, setWs] = useState<Workspace>(EMPTY);
    const [loadedFor, setLoadedFor] = useState<string | null>(null);
    const [renderKey, setRenderKey] = useState<string>("page");
    const [notice, setNotice] = useState<string | null>(null);
    const [recordTitle, setRecordTitleState] = useState<string | null>(null);
    const scope = `${userId}|${orgId}`;
    const loaded = enabled && loadedFor === scope;
    // The href the host itself navigated to: its URL change is already in `ws`.
    const expected = useRef<string | null>(null);
    const wsRef = useRef(ws);
    wsRef.current = ws;
    const scrolls = useRef(new Map<string, number>());
    const scrollRef = useRef<HTMLElement | null>(null);

    const go = useCallback((target: string, replace = false) => {
        expected.current = target;
        const url = `/${locale}${target}`;
        if (replace) router.replace(url);
        else router.push(url);
    }, [locale, router]);

    // Load (or reload, on a user or organisation change) and apply the fresh-load rules.
    useEffect(() => {
        if (!enabled || !userId || !orgId || loadedFor === scope) return;
        const raw = loadSession(userId, orgId) ?? (stripLocale(pathname) === "/dashboard" ? loadSnapshot(userId, orgId) : null);
        const r = onFreshLoad(validate(raw, role), href, newId(), Date.now());
        setWs(r.ws);
        setRenderKey(r.ws.activeId ?? "page");
        setLoadedFor(scope);
        if (r.replaceWith && r.replaceWith !== href) go(r.replaceWith, true);
        else expected.current = null;
        // Runs once per scope; later URL changes go through the effect below.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [enabled, scope]);

    // Every URL change after the load.
    useEffect(() => {
        if (!loaded) return;
        if (expected.current === href) {
            expected.current = null;
            setRenderKey(wsRef.current.activeId ?? "page");
            return;
        }
        expected.current = null;
        const next = onUrlChange(wsRef.current, href, newId(), Date.now());
        if (next.activeId !== wsRef.current.activeId) storeScroll(wsRef.current.activeId);
        setWs(next);
        setRenderKey(next.activeId ?? "page");
         
    }, [href, loaded]);

    useEffect(() => {
        if (loaded) save(userId, orgId, ws);
    }, [ws, loaded, userId, orgId]);

    const storeScroll = (id: string | null) => {
        if (id && scrollRef.current) scrolls.current.set(id, scrollRef.current.scrollTop);
    };

    // Restore the tab's scroll once its page is tall enough; give up quietly after ~1.5 s.
    useEffect(() => {
        if (!loaded) return;
        const el = scrollRef.current;
        const target = scrolls.current.get(renderKey) ?? 0;
        if (!el) return;
        if (target === 0) {
            el.scrollTop = 0;
            return;
        }
        let frame = 0;
        const start = performance.now();
        const step = () => {
            if (el.scrollHeight - el.clientHeight >= target) {
                el.scrollTop = target;
                return;
            }
            if (performance.now() - start < 1500) frame = requestAnimationFrame(step);
        };
        frame = requestAnimationFrame(step);
        return () => cancelAnimationFrame(frame);
    }, [renderKey, loaded]);

    const active = ws.tabs.find(t => t.id === ws.activeId) ?? null;

    // A tab with no stored title takes its route's nav label; a record screen overrides it.
    useEffect(() => {
        if (!loaded || !active) return;
        if (recordTitle) {
            setWs(w => setTitle(w, active.id, recordTitle));
        } else if (active.title === null || typeof active.title === "object") {
            const l = screenLabel(active.href, rail);
            if (l) setWs(w => setTitle(w, active.id, l));
        }
    }, [loaded, active, recordTitle, rail]);

    // Phase 0, for everyone: "{title} — Miftah" in the browser tab.
    const fallback = screenLabel(href, rail);
    const shown = recordTitle ?? (fallback ? label(fallback) : null);
    useEffect(() => {
        document.title = shown ? `${shown} — ${t("appName")}` : t("appName");
    }, [shown, t]);

    const switchTo = useCallback((id: string) => {
        const w = wsRef.current;
        const tab = w.tabs.find(x => x.id === id);
        if (!tab || id === w.activeId) return;
        storeScroll(w.activeId);
        setWs(activate(w, id, Date.now()));
        go(tab.href);
         
    }, [go]);

    const close = useCallback((id: string) => {
        const w = wsRef.current;
        const next = closeTab(w, id, Date.now());
        scrolls.current.delete(id);
        setWs(next);
        if (w.activeId !== id) return;
        const now = next.tabs.find(x => x.id === next.activeId);
        go(now ? now.href : "/dashboard");
    }, [go]);

    const open = useCallback((target: string, how: OpenHow) => {
        const w = wsRef.current;
        const r = openTab(w, target, how, newId(), Date.now());
        if (r.outcome === "full") {
            setNotice(t("full", { max: MAX_TABS }));
            return;
        }
        storeScroll(w.activeId);
        setWs(r.ws);
        const dest = r.tab && r.outcome === "focused" ? r.tab.href : target;
        if (dest !== href) go(dest);
        else setRenderKey(r.ws.activeId ?? "page");
         
    }, [go, href, t]);

    const reopen = useCallback(() => {
        const w = wsRef.current;
        const next = reopenClosed(w, Date.now());
        if (next === w) return;
        storeScroll(w.activeId);
        setWs(next);
        const tab = next.tabs.find(x => x.id === next.activeId);
        if (tab) go(tab.href);
         
    }, [go]);

    useEffect(() => {
        if (!notice) return;
        const id = setTimeout(() => setNotice(null), 5000);
        return () => clearTimeout(id);
    }, [notice]);

    // Alt-based shortcuts (spec §10), matched on `code` so an Arabic layout works.
    useEffect(() => {
        if (!loaded) return;
        const onKey = (e: KeyboardEvent) => {
            const k = workspaceKey(e);
            if (!k) return;
            const w = wsRef.current;
            e.preventDefault();
            if (k.action === "goto") { const tab = w.tabs[k.index]; if (tab) switchTo(tab.id); }
            else if (k.action === "last") { const tab = w.tabs[w.tabs.length - 1]; if (tab) switchTo(tab.id); }
            else if (k.action === "next" || k.action === "prev") { const id = neighbour(w, k.action === "next" ? 1 : -1); if (id) switchTo(id); }
            else if (k.action === "close") { if (w.activeId) close(w.activeId); }
            else reopen();
        };
        window.addEventListener("keydown", onKey);
        return () => window.removeEventListener("keydown", onKey);
    }, [loaded, switchTo, close, reopen]);

    const api = useMemo<WorkspaceApi>(() => ({
        enabled: loaded,
        tabId: loaded ? ws.activeId : null,
        open: loaded ? open : (target: string) => router.push(`/${locale}${target}`),
        setRecordTitle: setRecordTitleState,
    }), [loaded, ws.activeId, open, router, locale]);

    return (
        <WorkspaceContext.Provider value={api}>
            {loaded && (
                <WorkspaceStrip
                    ws={ws}
                    notice={notice}
                    onSwitch={switchTo}
                    onClose={close}
                    onNew={() => open("/dashboard", "new")}
                    onReopen={reopen}
                    onCloseOthers={() => { if (ws.activeId) setWs(w => closeOthers(w, w.activeId!)); }}
                />
            )}
            <main ref={scrollRef} className={mainClassName} {...(loaded ? { role: "tabpanel", id: "workspace-panel" } : {})}>
                <Fragment key={loaded ? renderKey : "page"}>{children}</Fragment>
            </main>
        </WorkspaceContext.Provider>
    );
}
