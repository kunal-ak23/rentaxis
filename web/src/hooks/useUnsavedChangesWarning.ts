"use client";

import { useContext, useEffect } from "react";
import { WorkspaceContext } from "@/components/workspace/WorkspaceContext";

/**
 * Ask the browser to confirm before leaving while work is unsaved.
 *
 * Extracted from `BulkChequeUploadFlow`, which had the only copy: a second
 * caller makes it a pattern, and a pattern belongs in one place. The handler is
 * only attached while `active` is true and is removed on save, on the work
 * finishing, and on unmount — a listener left behind would nag on every
 * subsequent navigation for no reason.
 *
 * Modern browsers ignore a custom message and show their own generic prompt;
 * `preventDefault()` plus a non-empty `returnValue` is what triggers it across
 * the current set.
 *
 * With workspace tabs on, the same work also makes switching away from or
 * closing the tab ask first: a tab switch unmounts the page, as a reload would.
 */
export function useUnsavedChangesWarning(active: boolean): void {
    const hold = useContext(WorkspaceContext)?.holdUnsaved;
    useEffect(() => {
        if (!active || !hold) return;
        return hold();
    }, [active, hold]);
    useEffect(() => {
        if (!active) return;
        const handler = (e: BeforeUnloadEvent) => {
            e.preventDefault();
            e.returnValue = "";
        };
        window.addEventListener("beforeunload", handler);
        return () => window.removeEventListener("beforeunload", handler);
    }, [active]);
}

export default useUnsavedChangesWarning;
