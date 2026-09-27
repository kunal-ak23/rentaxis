"use client";

import { useId, useMemo, useRef, useState } from "react";
import { SelectPanel, SelectTrigger, handleListKey } from "@/components/ui/SelectPanel";

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
    const listboxId = useId();

    const selected = options.find((o) => o.value === value);

    const filtered = useMemo(() => {
        const q = query.trim().toLowerCase();
        if (!q) return options;
        return options.filter((o) => o.searchText.includes(q));
    }, [options, query]);

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

    return (
        <div className="relative">
            <SelectTrigger
                ref={triggerRef}
                open={open}
                listboxId={listboxId}
                selected={selected ?? null}
                placeholder={placeholder}
                onToggle={() => setOpen((v) => !v)}
                className={className}
            />
            {open && (
                <SelectPanel
                    listboxId={listboxId}
                    query={query}
                    onQueryChange={(q) => {
                        setQuery(q);
                        // Reset the highlight on every keystroke: filtering
                        // shrinks the list, so a previously-highlighted index
                        // can fall outside it — leaving aria-activedescendant
                        // pointing at a nonexistent row and Enter selecting
                        // nothing. Resetting to -1 (the clear row) means the
                        // next ArrowDown lands on the first match.
                        setHighlightedIndex(-1);
                    }}
                    onKeyDown={(e) =>
                        handleListKey(e, filtered.length, setHighlightedIndex, () => selectIndex(highlightedIndex), () => close(true))
                    }
                    searchPlaceholder={searchPlaceholder}
                    clearLabel={placeholder}
                    rows={filtered}
                    selectedValue={value}
                    highlightedIndex={highlightedIndex}
                    onSelectIndex={selectIndex}
                    onDismiss={() => close(false)}
                    message={filtered.length === 0 ? "No matches" : null}
                />
            )}
        </div>
    );
}
