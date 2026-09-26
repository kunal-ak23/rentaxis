"use client";

import { useEffect, useRef } from "react";
import { X } from "lucide-react";

const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]):not([type="hidden"]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/** The modal on top: the last open `aria-modal` dialog in the document (portaled dialogs come later). */
function topmostModal(): Element | null {
    const all = document.querySelectorAll('[aria-modal="true"]');
    return all.length ? all[all.length - 1] : null;
}

/**
 * A panel that slides in from the inline end (the right in English, the left
 * in Arabic). The contract page opens Assignment and Write off in it, and the
 * unit status board its unit details. Esc, the backdrop and the close button
 * all close it; focus moves into it on open, stays inside while it is the top
 * dialog (Tab and Shift+Tab wrap), and goes back where it was on close. A
 * dialog opened from inside it gets Esc first (PR #368 R1 P3-4).
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
    const panelRef = useRef<HTMLElement>(null);

    useEffect(() => {
        if (!open) return;
        const back = document.activeElement as HTMLElement | null;
        closeRef.current?.focus();
        const isTop = () => topmostModal() === panelRef.current;
        const onKey = (e: KeyboardEvent) => {
            if (!isTop() || e.defaultPrevented) return;
            if (e.key === "Escape") {
                onClose();
                return;
            }
            if (e.key !== "Tab" || !panelRef.current) return;
            const items = Array.from(panelRef.current.querySelectorAll<HTMLElement>(FOCUSABLE)).filter(el => !el.closest("[hidden]"));
            if (items.length === 0) return;
            const first = items[0], last = items[items.length - 1];
            const inside = panelRef.current.contains(document.activeElement);
            if (e.shiftKey && (document.activeElement === first || !inside)) {
                e.preventDefault();
                last.focus();
            } else if (!e.shiftKey && (document.activeElement === last || !inside)) {
                e.preventDefault();
                first.focus();
            }
        };
        // Focus that lands behind the drawer (a click on the page, a script) comes back to it.
        const onFocusIn = (e: FocusEvent) => {
            if (!isTop() || !panelRef.current || panelRef.current.contains(e.target as Node)) return;
            closeRef.current?.focus();
        };
        window.addEventListener("keydown", onKey);
        document.addEventListener("focusin", onFocusIn);
        return () => {
            window.removeEventListener("keydown", onKey);
            document.removeEventListener("focusin", onFocusIn);
            if (back && document.contains(back)) back.focus();
        };
    }, [open, onClose]);

    if (!open) return null;
    return (
        <div className="fixed inset-0 z-50">
            <button type="button" aria-label={closeLabel} tabIndex={-1} onClick={onClose} className="absolute inset-0 bg-black/30 cursor-default" />
            <aside
                ref={panelRef}
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
