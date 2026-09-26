"use client";

import { useEffect, useRef } from "react";
import { X } from "lucide-react";

/**
 * A panel that slides in from the inline end (the right in English, the left
 * in Arabic). The contract page opens Assignment and Write off in it, and the
 * unit status board its unit details. Esc, the backdrop and the close button
 * all close it; focus moves into it on open and back where it was on close.
 */
export default function SideDrawer({ open, onClose, title, testId, closeLabel, children }: {
    open: boolean;
    onClose: () => void;
    title: string;
    testId: string;
    closeLabel: string;
    children: React.ReactNode;
}) {
    const closeRef = useRef<HTMLButtonElement>(null);

    useEffect(() => {
        if (!open) return;
        const back = document.activeElement as HTMLElement | null;
        closeRef.current?.focus();
        const onKey = (e: KeyboardEvent) => {
            if (e.key === "Escape") onClose();
        };
        window.addEventListener("keydown", onKey);
        return () => {
            window.removeEventListener("keydown", onKey);
            if (back && document.contains(back)) back.focus();
        };
    }, [open, onClose]);

    if (!open) return null;
    return (
        <div className="fixed inset-0 z-50">
            <button type="button" aria-label={closeLabel} tabIndex={-1} onClick={onClose} className="absolute inset-0 bg-black/30 cursor-default" />
            <aside
                role="dialog"
                aria-modal="true"
                aria-label={title}
                data-testid={testId}
                className="absolute inset-y-0 end-0 flex w-full max-w-xl flex-col bg-surface shadow-xl"
            >
                <header className="flex items-center justify-between gap-3 border-b border-border px-5 py-4">
                    <h2 className="text-sm font-bold text-foreground truncate">{title}</h2>
                    <button ref={closeRef} type="button" onClick={onClose} aria-label={closeLabel}
                        data-testid={`${testId}-close`} className="p-1 rounded-md hover:bg-input cursor-pointer">
                        <X size={16} />
                    </button>
                </header>
                <div className="flex-1 overflow-y-auto p-5">{children}</div>
            </aside>
        </div>
    );
}
