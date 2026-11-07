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
    /** The record a screen shows ("Contract C-12 · Unit 304"); null when it unmounts. */
    setRecordTitle: (title: string | null) => void;
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
    useEffect(() => {
        if (!set || !title) return;
        set(title);
        return () => set(null);
    }, [set, title]);
}
