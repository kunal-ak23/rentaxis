"use client";

import { useEffect, useRef, useState } from "react";
import { ChevronDown } from "lucide-react";
import type { LeaseSectionId } from "@/lib/nav/routeMap";
import { cn } from "@/lib/utils";

/**
 * One collapsible section of a contract tab — Journal Vouchers, Revenue
 * recognition and Vat inside Cheques, Contract / Attachments / Addenda inside
 * Attachments, Notes / Maintenance inside Activities (spec §5). What were tabs
 * are sections now, not removed.
 *
 * A `lazy` section mounts its body only while open, so a collapsed Journals or
 * Recognition section makes no request (as an unvisited tab did before). A
 * section holding editable state (the cheque grid) passes `lazy={false}` so
 * collapsing it does not throw work away.
 *
 * `forceOpen` (a deep link, or "raise a penalty → show it") opens the section
 * and scrolls it into view; `focusSeq` repeats that for the same section.
 */
export default function LeaseSection({ id, title, defaultOpen, forceOpen = false, focusSeq = 0, lazy = true, children }: {
    id: LeaseSectionId;
    title: string;
    defaultOpen: boolean;
    forceOpen?: boolean;
    focusSeq?: number;
    lazy?: boolean;
    children: React.ReactNode;
}) {
    const [open, setOpen] = useState(defaultOpen || forceOpen);
    const [seen, setSeen] = useState(defaultOpen || forceOpen);
    const ref = useRef<HTMLDetailsElement>(null);

    useEffect(() => {
        if (!forceOpen || !ref.current) return;
        ref.current.open = true;
        ref.current.scrollIntoView?.({ block: "start", behavior: "smooth" });
    }, [forceOpen, focusSeq]);

    return (
        <details
            ref={ref}
            open={open}
            id={`lease-section-${id}`}
            data-testid={`lease-section-${id}`}
            onToggle={e => {
                const now = (e.currentTarget as HTMLDetailsElement).open;
                setOpen(now);
                if (now) setSeen(true);
            }}
            className="group bg-surface border border-border rounded-xl scroll-mt-4"
        >
            <summary
                data-testid={`lease-section-toggle-${id}`}
                className="flex items-center justify-between gap-3 cursor-pointer select-none list-none px-5 py-3 text-sm font-semibold text-foreground [&::-webkit-details-marker]:hidden"
            >
                <span>{title}</span>
                <ChevronDown size={15} className={cn("text-muted transition-transform shrink-0", open && "rotate-180")} aria-hidden />
            </summary>
            <div className="border-t border-border p-4 min-w-0">{(lazy ? open : seen || open) && children}</div>
        </details>
    );
}
