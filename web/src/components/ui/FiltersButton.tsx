"use client";

import { useEffect, useRef, useState } from "react";
import { SlidersHorizontal, X } from "lucide-react";

const PANEL_WIDTH = 256;
const GUTTER = 8;

/**
 * Where the open panel goes, in viewport pixels: under the button, lined up with
 * its inline end (right in English, left in Arabic), then pushed back inside the
 * viewport. At 390 px the button wraps to the start of its row, and a panel
 * anchored to its end ran off the screen (PR #368 R1 P2-1).
 */
export function fitFiltersPanel(r: { left: number; right: number; bottom: number }, rtl: boolean, vw: number): { left: number; top: number; width: number } {
    const width = Math.min(PANEL_WIDTH, vw - 2 * GUTTER);
    const preferred = rtl ? r.left : r.right - width;
    const left = Math.min(Math.max(preferred, GUTTER), vw - GUTTER - width);
    return { left, top: r.bottom + 4, width };
}

/**
 * Spec §7: rarely-used filters sit behind one "Filters" button; what is set
 * shows as removable chips next to it. The panel stays mounted (hidden) so its
 * controls keep their state and test ids.
 */
export function FiltersButton({ label, activeCount, children }: { label: string; activeCount: number; children: React.ReactNode }) {
    const [open, setOpen] = useState(false);
    const [place, setPlace] = useState<React.CSSProperties | null>(null);
    const root = useRef<HTMLDivElement>(null);
    const button = useRef<HTMLButtonElement>(null);
    const measure = (): React.CSSProperties | null => {
        const el = button.current;
        if (!el) return null;
        const r = el.getBoundingClientRect();
        if (r.width === 0 && r.height === 0) return null;
        const f = fitFiltersPanel(r, getComputedStyle(el).direction === "rtl", window.innerWidth);
        return { position: "fixed", left: f.left, top: f.top, width: f.width };
    };
    const measureRef = useRef(measure);
    useEffect(() => { measureRef.current = measure; });
    useEffect(() => {
        if (!open) return;
        // The button moves with the page; the panel follows it.
        const follow = () => setPlace(measureRef.current());
        window.addEventListener("scroll", follow, true);
        window.addEventListener("resize", follow);
        return () => {
            window.removeEventListener("scroll", follow, true);
            window.removeEventListener("resize", follow);
        };
    }, [open]);
    useEffect(() => {
        if (!open) return;
        const onDown = (e: MouseEvent) => {
            if (root.current && !root.current.contains(e.target as Node)) setOpen(false);
        };
        const onKey = (e: KeyboardEvent) => {
            if (e.key === "Escape") setOpen(false);
        };
        document.addEventListener("mousedown", onDown);
        window.addEventListener("keydown", onKey);
        return () => {
            document.removeEventListener("mousedown", onDown);
            window.removeEventListener("keydown", onKey);
        };
    }, [open]);
    return (
        <div ref={root} className="relative">
            <button ref={button} type="button" aria-expanded={open} data-testid="filters-button"
                onClick={() => { if (!open) setPlace(measure()); setOpen(o => !o); }}
                className="flex items-center gap-1.5 bg-surface border border-border rounded-lg px-3 py-2 text-xs cursor-pointer hover:bg-input">
                <SlidersHorizontal size={13} />
                {label}
                {activeCount > 0 && <span className="rounded-full bg-[var(--ink-900)] px-1.5 text-[10px] text-white tabular-nums">{activeCount}</span>}
            </button>
            <div hidden={!open} data-testid="filters-panel" style={place ?? undefined}
                className={`z-40 rounded-lg border border-border bg-surface p-3 shadow-lg space-y-3 ${place ? "" : "absolute end-0 top-full mt-1 w-64 max-w-[calc(100vw-2rem)]"}`}>
                {children}
            </div>
        </div>
    );
}

export function FilterChip({ label, onRemove, removeLabel, testId }: { label: string; onRemove: () => void; removeLabel: string; testId: string }) {
    return (
        <span data-testid={testId} className="inline-flex items-center gap-1 rounded-full border border-border bg-[var(--sand-100)] ps-2.5 pe-1 py-0.5 text-[11.5px]">
            {label}
            <button type="button" aria-label={removeLabel} onClick={onRemove} data-testid={`${testId}-remove`} className="rounded-full p-0.5 hover:bg-border cursor-pointer">
                <X size={11} />
            </button>
        </span>
    );
}
