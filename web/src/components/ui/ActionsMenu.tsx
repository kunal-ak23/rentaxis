"use client";

import { useEffect, useRef, useState } from "react";
import { ChevronDown, MoreHorizontal } from "lucide-react";
import { Link } from "@/i18n/routing";
import { cn } from "@/lib/utils";

export interface ActionsMenuItem {
    id: string;
    label: string;
    testId: string;
    onSelect?: () => void;
    href?: string;
    destructive?: boolean;
    icon?: React.ElementType;
}

/**
 * One "More actions" / ⋯ menu. Items are always mounted and the panel is
 * `hidden` while closed, so hidden items stay out of the accessibility tree
 * but tests (and the action-coverage catalog) can still find them by test id.
 * See `measure` for where the open panel goes.
 */
export default function ActionsMenu({ label, items, testId, triggerTestId, variant = "button" }: {
    label: string;
    items: ActionsMenuItem[];
    testId: string;
    triggerTestId: string;
    variant?: "button" | "icon";
}) {
    const [open, setOpen] = useState(false);
    // Where the open panel sits, in viewport coordinates (null: not measured, e.g. in tests).
    const [place, setPlace] = useState<React.CSSProperties | null>(null);
    const root = useRef<HTMLDivElement>(null);
    const panel = useRef<HTMLDivElement>(null);
    const trigger = useRef<HTMLButtonElement>(null);

    useEffect(() => {
        if (!open) return;
        const onDown = (e: MouseEvent) => {
            if (root.current && !root.current.contains(e.target as Node)) setOpen(false);
        };
        // The panel is placed against the trigger when it opens; a scroll or resize
        // moves the trigger, so the menu closes rather than float off it.
        const onMove = (e: Event) => {
            if (panel.current && e.target instanceof Node && panel.current.contains(e.target)) return;
            setOpen(false);
        };
        // Esc closes it wherever focus is (the trigger keeps focus after a mouse click).
        const onKey = (e: KeyboardEvent) => {
            if (e.key !== "Escape") return;
            setOpen(false);
            trigger.current?.focus();
        };
        document.addEventListener("mousedown", onDown);
        document.addEventListener("keydown", onKey);
        window.addEventListener("scroll", onMove, true);
        window.addEventListener("resize", onMove);
        return () => {
            document.removeEventListener("mousedown", onDown);
            document.removeEventListener("keydown", onKey);
            window.removeEventListener("scroll", onMove, true);
            window.removeEventListener("resize", onMove);
        };
    }, [open]);

    /**
     * The panel is `position: fixed`, placed from the trigger's box when it opens,
     * so a row menu inside a scrolling or clipped table is never cut off. It
     * lines up with the trigger's inline end (right in English, left in Arabic),
     * flips to the start edge when that would leave the screen, and opens upward
     * near the bottom of the viewport.
     */
    const measure = (): React.CSSProperties | null => {
        const el = trigger.current;
        if (!el || typeof window === "undefined") return null;
        const r = el.getBoundingClientRect();
        if (r.width === 0 && r.height === 0) return null;
        const vw = window.innerWidth, vh = window.innerHeight;
        const rtl = getComputedStyle(el).direction === "rtl";
        const need = 208;
        const endFits = rtl ? r.left + need <= vw - 4 : r.right - need >= 4;
        const alignRight = rtl ? !endFits : endFits; // anchor the panel's right edge to the trigger's right edge
        const estimate = items.length * 34 + 10;
        const up = r.bottom + 4 + estimate > vh - 4 && r.top - 4 - estimate >= 4;
        return {
            position: "fixed",
            ...(alignRight ? { right: Math.max(4, vw - r.right) } : { left: Math.max(4, r.left) }),
            ...(up ? { bottom: vh - r.top + 4 } : { top: r.bottom + 4 }),
        };
    };
    const openMenu = () => {
        setPlace(measure());
        setOpen(true);
    };

    if (items.length === 0) return null;

    const focusables = () => Array.from(panel.current?.querySelectorAll<HTMLElement>('[role="menuitem"]') ?? []);
    const close = (refocus: boolean) => {
        setOpen(false);
        if (refocus) trigger.current?.focus();
    };
    const onKeyDown = (e: React.KeyboardEvent) => {
        if (e.key === "Escape") {
            e.stopPropagation();
            close(true);
            return;
        }
        if (e.key === "Tab") {
            setOpen(false);
            return;
        }
        if (e.key !== "ArrowDown" && e.key !== "ArrowUp" && e.key !== "Home" && e.key !== "End") return;
        e.preventDefault();
        const list = focusables();
        if (list.length === 0) return;
        const i = list.indexOf(document.activeElement as HTMLElement);
        const next = e.key === "Home" ? 0
            : e.key === "End" ? list.length - 1
            : e.key === "ArrowDown" ? (i + 1) % list.length
            : (i - 1 + list.length) % list.length;
        list[next]?.focus();
    };
    const onTriggerKeyDown = (e: React.KeyboardEvent) => {
        if (e.key !== "ArrowDown") return;
        e.preventDefault();
        openMenu();
        requestAnimationFrame(() => focusables()[0]?.focus());
    };
    const itemClass = (destructive?: boolean) => cn(
        "flex w-full items-center gap-2 px-3 py-2 text-start text-xs font-medium hover:bg-[var(--sand-100)] focus:bg-[var(--sand-100)] focus:outline-none cursor-pointer",
        destructive ? "text-error" : "text-foreground",
    );

    return (
        <div ref={root} className="relative inline-block">
            <button
                ref={trigger}
                type="button"
                data-testid={triggerTestId}
                aria-haspopup="menu"
                aria-expanded={open}
                aria-label={variant === "icon" ? label : undefined}
                title={variant === "icon" ? label : undefined}
                onClick={() => (open ? setOpen(false) : openMenu())}
                onKeyDown={onTriggerKeyDown}
                className={variant === "icon"
                    ? "p-1.5 rounded-md text-[var(--ink-600)] hover:bg-[var(--sand-100)] cursor-pointer"
                    : "flex items-center gap-1.5 bg-input text-foreground border border-border px-4 py-2 rounded-lg text-xs font-semibold hover:bg-border transition-all cursor-pointer"}
            >
                {variant === "icon" ? <MoreHorizontal size={16} /> : <>{label}<ChevronDown size={14} /></>}
            </button>
            <div
                ref={panel}
                role="menu"
                aria-label={label}
                data-testid={testId}
                hidden={!open}
                onKeyDown={onKeyDown}
                style={place ?? undefined}
                className={cn(
                    "z-50 min-w-[200px] max-w-[calc(100vw-2rem)] max-h-[70vh] overflow-y-auto rounded-lg border border-border bg-surface py-1 shadow-lg",
                    // Unmeasured (server render, tests): hang below the trigger at its inline end.
                    !place && "absolute top-full end-0 mt-1",
                )}
            >
                {items.map(item => {
                    const Icon = item.icon;
                    const body = <>{Icon && <Icon size={14} />}<span className="truncate">{item.label}</span></>;
                    return item.href ? (
                        <Link key={item.id} href={item.href} role="menuitem" data-testid={item.testId}
                            className={itemClass(item.destructive)} onClick={() => setOpen(false)}>
                            {body}
                        </Link>
                    ) : (
                        <button key={item.id} type="button" role="menuitem" data-testid={item.testId}
                            className={itemClass(item.destructive)}
                            onClick={() => {
                                setOpen(false);
                                item.onSelect?.();
                            }}>
                            {body}
                        </button>
                    );
                })}
            </div>
        </div>
    );
}
