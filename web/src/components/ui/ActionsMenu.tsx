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
 * The panel opens toward the inline end of its trigger (`end-0`, RTL-safe) and
 * flips to the other side when that would push it off the screen.
 */
export default function ActionsMenu({ label, items, testId, triggerTestId, variant = "button" }: {
    label: string;
    items: ActionsMenuItem[];
    testId: string;
    triggerTestId: string;
    variant?: "button" | "icon";
}) {
    const [open, setOpen] = useState(false);
    const [flip, setFlip] = useState(false);
    const root = useRef<HTMLDivElement>(null);
    const panel = useRef<HTMLDivElement>(null);
    const trigger = useRef<HTMLButtonElement>(null);

    useEffect(() => {
        if (!open) return;
        const onDown = (e: MouseEvent) => {
            if (root.current && !root.current.contains(e.target as Node)) setOpen(false);
        };
        document.addEventListener("mousedown", onDown);
        return () => document.removeEventListener("mousedown", onDown);
    }, [open]);

    /**
     * Keep the panel on screen: a trigger near the start edge (a wrapped header
     * row on a phone) would otherwise open a 200px panel past that edge. Decided
     * from the trigger's box when opening, so no effect has to re-render it.
     */
    const decideFlip = () => {
        const el = trigger.current;
        if (!el || typeof window === "undefined") return false;
        const r = el.getBoundingClientRect();
        if (r.width === 0) return false;
        const rtl = getComputedStyle(el).direction === "rtl";
        const need = 208;
        return rtl ? r.left + need > window.innerWidth - 4 : r.right - need < 4;
    };
    const openMenu = () => {
        setFlip(decideFlip());
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
                className={cn(
                    "absolute top-full z-50 mt-1 min-w-[200px] max-w-[calc(100vw-2rem)] overflow-hidden rounded-lg border border-border bg-surface py-1 shadow-lg",
                    flip ? "start-0" : "end-0",
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
