"use client";

import { createContext, useContext, useEffect } from "react";
import type { OpenHow } from "@/lib/workspace/store";

/**
 * What a screen can ask of the workspace (spec §3, §6). With the workspace off
 * (flag off, a phone, a renter, a superadmin page) `enabled` is false, `open`
 * navigates in place and `tabId` is null, so screens behave exactly as today.
 */
export interface WorkspaceApi {
    enabled: boolean;
    tabId: string | null;
    /** Open `href` (locale-less) as a tab by the opening rules, or navigate in place when off. */
    open: (href: string, how: OpenHow) => void;
    /** The mounted page's key; a title or an unsaved-work hold belongs to the page that set it. */
    pageKey: string;
    /** The record a page shows ("Contract C-12 · Unit 304"); null when it unmounts. */
    setRecordTitle: (pageKey: string, title: string | null) => void;
    /** The page holds unsaved work until the returned release is called (spec §5). */
    holdUnsaved: () => () => void;
}

export const WorkspaceContext = createContext<WorkspaceApi | null>(null);

export function useWorkspace(): WorkspaceApi | null {
    return useContext(WorkspaceContext);
}

export function useTabId(): string | null {
    return useContext(WorkspaceContext)?.tabId ?? null;
}

/**
 * A record screen names itself: the tab and the browser tab show this instead
 * of the route's nav label. Pass null until the record has loaded.
 */
export function useTabTitle(title: string | null | undefined) {
    const api = useContext(WorkspaceContext);
    const set = api?.setRecordTitle;
    const key = api?.pageKey;
    useEffect(() => {
        if (!set || !key || !title) return;
        set(key, title);
        return () => set(key, null);
    }, [set, key, title]);
}
