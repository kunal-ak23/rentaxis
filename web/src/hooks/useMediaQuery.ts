"use client";

import { useSyncExternalStore } from "react";

/** Whether a CSS media query matches now; false on the server and before hydration. */
export function useMediaQuery(query: string): boolean {
    return useSyncExternalStore(
        onChange => {
            if (typeof window === "undefined" || !window.matchMedia) return () => {};
            const mql = window.matchMedia(query);
            mql.addEventListener("change", onChange);
            return () => mql.removeEventListener("change", onChange);
        },
        () => (typeof window !== "undefined" && !!window.matchMedia && window.matchMedia(query).matches),
        () => false,
    );
}
