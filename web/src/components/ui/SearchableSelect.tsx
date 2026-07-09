"use client";

import { useEffect, useId, useMemo, useRef, useState } from "react";
import { ChevronDown, Search } from "lucide-react";
import { cn } from "@/lib/utils";

export type SearchableSelectOption = {
    value: string;
    label: string;
    /** Secondary text shown under the label (e.g. email, property name) — also searchable via searchText. */
    sublabel?: string;
    /** Lowercased text this option matches against; include every field the user should be able to search by. */
    searchText: string;
};

type SearchableSelectProps = {
    options: SearchableSelectOption[];
    value: string;
    onChange: (value: string) => void;
    placeholder?: string;
    searchPlaceholder?: string;
    className?: string;
};

/**
 * Dropdown with a search box, for lists too long to scan as a plain <select>
 * (e.g. renters where names collide, units across many properties). Matches
 * the existing app dropdown-panel convention (see TopHeader.tsx's profile
 * menu): a fixed full-screen overlay closes the panel on outside click
 * rather than a document-level listener.
 *
 * Keyboard: ArrowUp/ArrowDown move the highlight (starting at the "clear"
 * row, index -1), Enter selects the highlighted row, Escape closes without
 * changing the value. Both close paths return focus to the trigger button
 * so keyboard flow continues at the same form field rather than dropping to
 * <body>.
 */
export function SearchableSelect({
    options,
    value,
    onChange,
    placeholder = "— Select —",
    searchPlaceholder = "Search...",
    className,
}: SearchableSelectProps) {
    const [open, setOpen] = useState(false);
    const [query, setQuery] = useState("");
    const [highlightedIndex, setHighlightedIndex] = useState(-1);
    const triggerRef = useRef<HTMLButtonElement>(null);
    const optionRefs = useRef<Array<HTMLButtonElement | null>>([]);
    const listboxId = useId();

    const selected = options.find((o) => o.value === value);

    const filtered = useMemo(() => {
        const q = query.trim().toLowerCase();
        if (!q) return options;
        return options.filter((o) => o.searchText.includes(q));
    }, [options, query]);

    useEffect(() => {
        if (highlightedIndex >= 0) {
            optionRefs.current[highlightedIndex]?.scrollIntoView({ block: "nearest" });
        }
    }, [highlightedIndex]);

    const close = (returnFocus: boolean) => {
        setOpen(false);
        setQuery("");
        // Reset the highlight so reopening starts fresh at the clear row rather
        // than restoring a stale index from the previous session.
        setHighlightedIndex(-1);
        if (returnFocus) triggerRef.current?.focus();
    };

    const selectIndex = (index: number) => {
        if (index === -1) {
            onChange("");
        } else {
            const option = filtered[index];
            if (option) onChange(option.value);
        }
        close(true);
    };

    const handleSearchKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
        switch (e.key) {
            case "ArrowDown":
                e.preventDefault();
                setHighlightedIndex((i) => Math.min(i + 1, filtered.length - 1));
                break;
            case "ArrowUp":
                e.preventDefault();
                setHighlightedIndex((i) => Math.max(i - 1, -1));
                break;
            case "Enter":
                e.preventDefault();
                selectIndex(highlightedIndex);
                break;
            case "Escape":
                e.preventDefault();
                close(true);
                break;
            default:
                break;
        }
    };

    return (
        <div className="relative">
            <button
                ref={triggerRef}
                type="button"
                role="combobox"
                aria-expanded={open}
                aria-haspopup="listbox"
                aria-controls={listboxId}
                onClick={() => setOpen((v) => !v)}
                className={cn(
                    "w-full bg-input border border-border p-3 rounded-xl text-xs text-left flex items-center justify-between gap-2 cursor-pointer focus:ring-2 focus:ring-primary/20 focus:outline-none",
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

            {open && (
                <>
                    <div className="fixed inset-0 z-40" onClick={() => close(false)} />
                    <div className="absolute left-0 right-0 top-full mt-1.5 bg-surface rounded-xl shadow-xl border border-border z-50 overflow-hidden">
                        <div className="p-2 border-b border-border">
                            <div className="relative">
                                <Search size={12} className="absolute left-2.5 top-1/2 -translate-y-1/2 text-muted pointer-events-none" />
                                <input
                                    autoFocus
                                    type="text"
                                    role="combobox"
                                    aria-expanded="true"
                                    aria-controls={listboxId}
                                    aria-activedescendant={highlightedIndex >= 0 ? `${listboxId}-opt-${highlightedIndex}` : undefined}
                                    value={query}
                                    onChange={(e) => {
                                        setQuery(e.target.value);
                                        // Reset the highlight on every keystroke: filtering
                                        // shrinks the list, so a previously-highlighted index
                                        // can fall outside it — leaving aria-activedescendant
                                        // pointing at a nonexistent row and Enter selecting
                                        // nothing. Resetting to -1 (the clear row) means the
                                        // next ArrowDown lands on the first match.
                                        setHighlightedIndex(-1);
                                    }}
                                    onKeyDown={handleSearchKeyDown}
                                    placeholder={searchPlaceholder}
                                    className="w-full bg-input border border-border pl-7 pr-2 py-2 rounded-lg text-xs focus:ring-2 focus:ring-primary/20 focus:outline-none"
                                />
                            </div>
                        </div>
                        <div id={listboxId} role="listbox" className="max-h-56 overflow-y-auto">
                            <button
                                type="button"
                                role="option"
                                aria-selected={highlightedIndex === -1}
                                onClick={() => selectIndex(-1)}
                                className={cn(
                                    "w-full text-left px-3 py-2 text-xs text-muted hover:bg-input/50 transition-colors cursor-pointer",
                                    highlightedIndex === -1 && "bg-input",
                                )}
                            >
                                {placeholder}
                            </button>
                            {filtered.length === 0 && (
                                <p className="px-3 py-3 text-[11px] text-muted text-center">No matches</p>
                            )}
                            {filtered.map((o, i) => (
                                <button
                                    key={o.value}
                                    id={`${listboxId}-opt-${i}`}
                                    ref={(el) => {
                                        optionRefs.current[i] = el;
                                    }}
                                    type="button"
                                    role="option"
                                    aria-selected={o.value === value}
                                    onClick={() => selectIndex(i)}
                                    className={cn(
                                        "w-full text-left px-3 py-2 text-xs hover:bg-input/50 transition-colors cursor-pointer",
                                        o.value === value && "bg-primary/5 text-primary font-semibold",
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
            )}
        </div>
    );
}
