"use client";

import { Link } from "@/i18n/routing";
import { cn } from "@/lib/utils";
import type { CollectionsTab, CollectionsTabId } from "@/lib/nav/collectionsModel";
import { useLabel } from "@/lib/nav/useLabel";

export type PillCounts = Partial<Record<CollectionsTabId, number>>;

const nf = new Intl.NumberFormat("en-US");

/**
 * The hub's status pills (spec §1a list-page pattern): one link per view with
 * its count when an existing endpoint gives one. The property filter rides
 * along on every pill so switching views keeps it.
 */
export default function CollectionPills({ tabs, active, counts, propertyId, label }: {
    tabs: CollectionsTab[]; active: CollectionsTabId; counts: PillCounts; propertyId: string; label: string;
}) {
    const tr = useLabel();
    return (
        <nav aria-label={label} className="flex flex-wrap gap-2" data-testid="collections-pills">
            {tabs.map(t => {
                const href = propertyId ? `${t.href}&propertyId=${encodeURIComponent(propertyId)}` : t.href;
                const n = counts[t.id];
                const on = t.id === active;
                return (
                    <Link key={t.id} href={href} data-testid={`collections-pill-${t.id}`} aria-current={on ? "page" : undefined}
                        className={cn("inline-flex items-center gap-1.5 rounded-full border px-3 py-1.5 text-[12.5px] font-medium transition-colors",
                            on ? "border-[var(--ink-900)] bg-[var(--ink-900)] text-white"
                                : "border-border bg-surface text-[var(--ink-600)] hover:bg-[var(--sand-100)]",
                            !on && t.id === "overdue" && n ? "text-error" : null)}>
                        {tr(t.label)}
                        {n !== undefined && (
                            <span data-testid={`collections-count-${t.id}`}
                                className={cn("rounded-full px-1.5 text-[11px] tabular-nums", on ? "bg-white/20" : "bg-[var(--sand-100)]")}>
                                {nf.format(n)}
                            </span>
                        )}
                    </Link>
                );
            })}
        </nav>
    );
}
