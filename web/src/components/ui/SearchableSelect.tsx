"use client";

import { useMemo, useState } from "react";
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

    const selected = options.find((o) => o.value === value);

    const filtered = useMemo(() => {
        const q = query.trim().toLowerCase();
        if (!q) return options;
        return options.filter((o) => o.searchText.includes(q));
    }, [options, query]);

    const close = () => {
        setOpen(false);
        setQuery("");
    };

    return (
        <div className="relative">
            <button
                type="button"
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
                    <div className="fixed inset-0 z-40" onClick={close} />
                    <div className="absolute left-0 right-0 top-full mt-1.5 bg-surface rounded-xl shadow-xl border border-border z-50 overflow-hidden">
                        <div className="p-2 border-b border-border">
                            <div className="relative">
                                <Search size={12} className="absolute left-2.5 top-1/2 -translate-y-1/2 text-muted pointer-events-none" />
                                <input
                                    autoFocus
                                    type="text"
                                    value={query}
                                    onChange={(e) => setQuery(e.target.value)}
                                    placeholder={searchPlaceholder}
                                    className="w-full bg-input border border-border pl-7 pr-2 py-2 rounded-lg text-xs focus:ring-2 focus:ring-primary/20 focus:outline-none"
                                />
                            </div>
                        </div>
                        <div className="max-h-56 overflow-y-auto">
                            <button
                                type="button"
                                onClick={() => {
                                    onChange("");
                                    close();
                                }}
                                className="w-full text-left px-3 py-2 text-xs text-muted hover:bg-input/50 transition-colors cursor-pointer"
                            >
                                {placeholder}
                            </button>
                            {filtered.length === 0 && (
                                <p className="px-3 py-3 text-[11px] text-muted text-center">No matches</p>
                            )}
                            {filtered.map((o) => (
                                <button
                                    key={o.value}
                                    type="button"
                                    onClick={() => {
                                        onChange(o.value);
                                        close();
                                    }}
                                    className={cn(
                                        "w-full text-left px-3 py-2 text-xs hover:bg-input/50 transition-colors cursor-pointer",
                                        o.value === value && "bg-primary/5 text-primary font-semibold",
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
