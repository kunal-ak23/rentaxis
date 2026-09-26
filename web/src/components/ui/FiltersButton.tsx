"use client";

import { useEffect, useRef, useState } from "react";
import { SlidersHorizontal, X } from "lucide-react";

/**
 * Spec §7: rarely-used filters sit behind one "Filters" button; what is set
 * shows as removable chips next to it. The panel stays mounted (hidden) so its
 * controls keep their state and test ids.
 */
export function FiltersButton({ label, activeCount, children }: { label: string; activeCount: number; children: React.ReactNode }) {
    const [open, setOpen] = useState(false);
    const root = useRef<HTMLDivElement>(null);
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
            <button type="button" aria-expanded={open} data-testid="filters-button" onClick={() => setOpen(o => !o)}
                className="flex items-center gap-1.5 bg-surface border border-border rounded-lg px-3 py-2 text-xs cursor-pointer hover:bg-input">
                <SlidersHorizontal size={13} />
                {label}
                {activeCount > 0 && <span className="rounded-full bg-[var(--ink-900)] px-1.5 text-[10px] text-white tabular-nums">{activeCount}</span>}
            </button>
            <div hidden={!open} data-testid="filters-panel"
                className="absolute end-0 top-full z-40 mt-1 w-64 max-w-[calc(100vw-2rem)] rounded-lg border border-border bg-surface p-3 shadow-lg space-y-3">
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
