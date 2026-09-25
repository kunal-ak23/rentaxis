"use client";
import { createContext, useContext, useEffect, useState, useSyncExternalStore } from "react";

/** ≥ 1280 px: the section panel can sit inline beside the rail (Tailwind `xl`). */
const WIDE_QUERY = "(min-width: 1280px)";
function subscribeWide(onChange: () => void) {
    if (typeof window === "undefined" || !window.matchMedia) return () => {};
    const mq = window.matchMedia(WIDE_QUERY);
    mq.addEventListener?.("change", onChange);
    return () => mq.removeEventListener?.("change", onChange);
}
const wideNow = () => (typeof window === "undefined" || !window.matchMedia ? true : window.matchMedia(WIDE_QUERY).matches);
export function useIsWide(): boolean {
    return useSyncExternalStore(subscribeWide, wideNow, () => true);
}

type NavShell = {
    drawerOpen: boolean;
    setDrawerOpen: (open: boolean) => void;
    /** The user's "Hide panel" preference (persisted as `sidebar_collapsed`). */
    panelHidden: boolean;
    setPanelHidden: (update: (hidden: boolean) => boolean) => void;
    /**
     * The section panel is showing inline beside the rail (≥ 1280 px, not
     * hidden). Decides where the ONE organisation control lives: at the top of
     * the panel when true, in the header otherwise (PR #363 R1 ruling).
     */
    inlinePanel: boolean;
};
const Ctx = createContext<NavShell>({
    drawerOpen: false, setDrawerOpen: () => {}, panelHidden: false, setPanelHidden: () => {}, inlinePanel: true,
});

export function NavShellProvider({ children }: { children: React.ReactNode }) {
    const [drawerOpen, setDrawerOpen] = useState(false);
    const [panelHidden, setPanelHiddenState] = useState(() => {
        try { return typeof window !== "undefined" && localStorage.getItem("sidebar_collapsed") === "true"; } catch { return false; }
    });
    useEffect(() => { try { localStorage.setItem("sidebar_collapsed", String(panelHidden)); } catch { /* private mode */ } }, [panelHidden]);
    const isWide = useIsWide();
    const value: NavShell = {
        drawerOpen, setDrawerOpen, panelHidden,
        setPanelHidden: update => setPanelHiddenState(update),
        inlinePanel: isWide && !panelHidden,
    };
    return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}
export const useNavShell = () => useContext(Ctx);
