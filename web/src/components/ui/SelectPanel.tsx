"use client";

import { forwardRef, useEffect, useRef, type ReactNode } from "react";
import { ChevronDown, Search } from "lucide-react";
import { cn } from "@/lib/utils";

/**
 * Trigger + dropdown panel shared by SearchableSelect (client-side filter) and
 * AsyncSearchSelect (server search). Pure presentation: the owning select keeps
 * the state and the keyboard handler ({@link handleListKey}).
 *
 * Matches the app dropdown-panel convention (see TopHeader.tsx's profile menu):
 * a fixed full-screen overlay closes the panel on outside click rather than a
 * document-level listener.
 */

export type SelectRow = { value: string; label: string; sublabel?: string };

type TriggerProps = {
    /** Lets a `<label htmlFor>` name the combobox. */
    id?: string;
    open: boolean;
    listboxId: string;
    selected: { label: string; sublabel?: string } | null;
    placeholder: string;
    onToggle: () => void;
    className?: string;
    testId?: string;
    disabled?: boolean;
};

export const SelectTrigger = forwardRef<HTMLButtonElement, TriggerProps>(function SelectTrigger(
    { id, open, listboxId, selected, placeholder, onToggle, className, testId, disabled },
    ref,
) {
    return (
        <button
            ref={ref}
            id={id}
            type="button"
            role="combobox"
            aria-expanded={open}
            aria-haspopup="listbox"
            aria-controls={listboxId}
            data-testid={testId}
            disabled={disabled}
            onClick={onToggle}
            className={cn(
                "w-full bg-input border border-border p-3 rounded-xl text-xs text-start flex items-center justify-between gap-2 cursor-pointer focus:ring-2 focus:ring-primary/20 focus:outline-none disabled:opacity-60 disabled:cursor-not-allowed",
                className,
            )}
        >
            <span className={cn("truncate", !selected && "text-muted")}>
                {selected ? (
                    <>
                        {selected.label}
                        {selected.sublabel && <span className="text-muted"> · {selected.sublabel}</span>}
                    </>
                ) : (
                    placeholder
                )}
            </span>
            <ChevronDown size={13} className="text-muted shrink-0" />
        </button>
    );
});

type PanelProps = {
    listboxId: string;
    query: string;
    onQueryChange: (q: string) => void;
    onKeyDown: (e: React.KeyboardEvent<HTMLInputElement>) => void;
    searchPlaceholder: string;
    /** Label of the "clear" row at index -1 (the select's placeholder). */
    clearLabel: string;
    rows: SelectRow[];
    selectedValue: string;
    highlightedIndex: number;
    onSelectIndex: (index: number) => void;
    onDismiss: () => void;
    /** A status line under the clear row ("No matches", "Searching…", …), or null. */
    message: ReactNode;
};

export function SelectPanel({
    listboxId,
    query,
    onQueryChange,
    onKeyDown,
    searchPlaceholder,
    clearLabel,
    rows,
    selectedValue,
    highlightedIndex,
    onSelectIndex,
    onDismiss,
    message,
}: PanelProps) {
    const optionRefs = useRef<Array<HTMLButtonElement | null>>([]);

    useEffect(() => {
        if (highlightedIndex >= 0) {
            optionRefs.current[highlightedIndex]?.scrollIntoView({ block: "nearest" });
        }
    }, [highlightedIndex]);

    return (
        <>
            <div className="fixed inset-0 z-40" onClick={onDismiss} />
            <div className="absolute left-0 right-0 top-full mt-1.5 bg-surface rounded-xl shadow-xl border border-border z-50 overflow-hidden">
                <div className="p-2 border-b border-border">
                    <div className="relative">
                        <Search size={12} className="absolute start-2.5 top-1/2 -translate-y-1/2 text-muted pointer-events-none" />
                        <input
                            autoFocus
                            type="text"
                            role="combobox"
                            aria-expanded="true"
                            aria-controls={listboxId}
                            aria-activedescendant={highlightedIndex >= 0 ? `${listboxId}-opt-${highlightedIndex}` : undefined}
                            value={query}
                            onChange={(e) => onQueryChange(e.target.value)}
                            onKeyDown={onKeyDown}
                            placeholder={searchPlaceholder}
                            className="w-full bg-input border border-border ps-7 pe-2 py-2 rounded-lg text-xs focus:ring-2 focus:ring-primary/20 focus:outline-none"
                        />
                    </div>
                </div>
                <div id={listboxId} role="listbox" className="max-h-56 overflow-y-auto">
                    <button
                        type="button"
                        role="option"
                        aria-selected={highlightedIndex === -1}
                        onClick={() => onSelectIndex(-1)}
                        className={cn(
                            "w-full text-start px-3 py-2 text-xs text-muted hover:bg-input/50 transition-colors cursor-pointer",
                            highlightedIndex === -1 && "bg-input",
                        )}
                    >
                        {clearLabel}
                    </button>
                    {message && <p className="px-3 py-3 text-[11px] text-muted text-center">{message}</p>}
                    {rows.map((o, i) => (
                        <button
                            key={o.value}
                            id={`${listboxId}-opt-${i}`}
                            ref={(el) => {
                                optionRefs.current[i] = el;
                            }}
                            type="button"
                            role="option"
                            aria-selected={o.value === selectedValue}
                            onClick={() => onSelectIndex(i)}
                            className={cn(
                                "w-full text-start px-3 py-2 text-xs hover:bg-input/50 transition-colors cursor-pointer",
                                o.value === selectedValue && "bg-primary/5 text-primary font-semibold",
                                i === highlightedIndex && "bg-input",
                            )}
                        >
                            {o.label}
                            {o.sublabel && <span className="block text-[10px] text-muted mt-0.5">{o.sublabel}</span>}
                        </button>
                    ))}
                </div>
            </div>
        </>
    );
}

/**
 * Keyboard for the search box: ArrowUp/ArrowDown move the highlight (starting
 * at the "clear" row, index -1), Enter selects the highlighted row, Escape
 * closes without changing the value.
 */
export function handleListKey(
    e: React.KeyboardEvent<HTMLInputElement>,
    rowCount: number,
    setHighlightedIndex: (fn: (i: number) => number) => void,
    onEnter: () => void,
    onEscape: () => void,
) {
    switch (e.key) {
        case "ArrowDown":
            e.preventDefault();
            setHighlightedIndex((i) => Math.min(i + 1, rowCount - 1));
            break;
        case "ArrowUp":
            e.preventDefault();
            setHighlightedIndex((i) => Math.max(i - 1, -1));
            break;
        case "Enter":
            e.preventDefault();
            onEnter();
            break;
        case "Escape":
            e.preventDefault();
            onEscape();
            break;
        default:
            break;
    }
}
