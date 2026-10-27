"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { AlertCircle, CheckCircle2 } from "lucide-react";
import { cn } from "@/lib/utils";

export interface ToastState {
    /** Changes with every toast, so showing the same message twice restarts it. */
    id: number;
    message: string;
    tone: "success" | "error";
}

/**
 * A short-lived confirmation ("Deleted", "Archived"). Render <Toast toast={toast} />
 * once in the page; call showToast from a handler. A new toast replaces the one
 * showing and restarts the clock; leaving the page cancels it.
 */
export function useToast(durationMs = 3500) {
    const [toast, setToast] = useState<ToastState | null>(null);
    const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
    const seq = useRef(0);

    useEffect(() => () => clearTimeout(timer.current), []);

    const showToast = useCallback((message: string, tone: ToastState["tone"] = "success") => {
        clearTimeout(timer.current);
        setToast({ id: ++seq.current, message, tone });
        timer.current = setTimeout(() => setToast(null), durationMs);
    }, [durationMs]);

    return { toast, showToast };
}

/** Pinned to the top inline-end corner (the left in Arabic), above page content. */
export function Toast({ toast }: { toast: ToastState | null }) {
    if (!toast) return null;
    return (
        <div role="status" aria-live="polite" data-testid="toast" data-tone={toast.tone}
            className={cn(
                "fixed end-4 top-4 z-[200] flex items-center gap-2.5 rounded-xl px-4 py-3 text-sm font-medium text-white shadow-lg",
                toast.tone === "success" ? "bg-success" : "bg-error",
            )}>
            {toast.tone === "success" ? <CheckCircle2 size={16} aria-hidden="true" /> : <AlertCircle size={16} aria-hidden="true" />}
            {toast.message}
        </div>
    );
}
