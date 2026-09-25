// src/components/nav/SectionPanel.tsx
"use client";
import { ChevronDown } from "lucide-react";
import { useLocale, useTranslations } from "next-intl";
import { useState } from "react";
import { Link } from "@/i18n/routing";
import { cn } from "@/lib/utils";
import type { RailSection } from "@/lib/nav/navModel";
import { useLabel } from "@/lib/nav/useLabel";
import { OrgName } from "./OrgName";
import { TenantSwitcher } from "@/components/ui/TenantSwitcher";
import { useSession } from "next-auth/react";
import { hasPermission, type UserRole } from "@/lib/rbac";
import type { NavCounts } from "./useNavCounts";

function fmtDate(iso: string, locale: string) {
    const [y, m, d] = iso.slice(0, 10).split("-").map(Number);
    return new Date(y, m - 1, d).toLocaleDateString(locale === "ar" ? "ar-AE" : "en-GB");
}

/**
 * `showOrg`: the panel carries the organisation (switcher for roles that can
 * switch, plain name otherwise) only when it is the inline panel; in a flyout
 * or the phone drawer the header has it (PR #363 R1 ruling: one org control).
 */
export function SectionPanel({ section, activeItem, counts, showOrg = true }: {
    section: RailSection; activeItem: string | null; counts: NavCounts; showOrg?: boolean;
}) {
    const { data: session } = useSession();
    const canSwitch = hasPermission(session?.user?.role as UserRole | undefined, "canSwitchTenants");
    const label = useLabel();
    const t = useTranslations("Navigation");
    const locale = useLocale();
    const [open, setOpen] = useState<Record<string, boolean>>(() =>
        Object.fromEntries(section.groups.map(g => [g.id, g.defaultOpen || g.items.some(i => i.id === activeItem)])));

    return (
        <div data-testid="nav-panel" aria-label={t("sectionPanel", { name: label(section.label) })}
            className="flex h-full w-[240px] flex-col border-e border-border bg-surface">
            {showOrg && (
                <div className="shrink-0 border-b border-border p-3" data-testid="panel-org">
                    {canSwitch ? <TenantSwitcher isCollapsed={false} /> : <OrgName />}
                </div>
            )}
            {/* min-h-0: the list scrolls inside its own area and never runs under the pinned status card. */}
            <div data-testid="nav-panel-list" className="min-h-0 flex-1 overflow-y-auto thinscroll px-3 py-3 space-y-3">
                <div className="px-2 text-[15px] font-semibold text-foreground">{label(section.label)}</div>
                {section.groups.map(g => {
                    const isOpen = open[g.id] ?? true;
                    return (
                        <div key={g.id}>
                            {g.label && (
                                <button type="button" data-testid={`panel-group-toggle-${g.id}`} aria-expanded={isOpen}
                                    aria-controls={`panel-group-items-${g.id}`}
                                    onClick={() => setOpen(o => ({ ...o, [g.id]: !isOpen }))}
                                    className="flex w-full items-center justify-between px-2 py-1 text-[10.5px] font-semibold uppercase tracking-[0.08em] text-[var(--ink-500)] cursor-pointer">
                                    {label(g.label)}
                                    <ChevronDown size={12} className={cn("transition-transform", isOpen && "rotate-180")} />
                                </button>
                            )}
                            <div id={`panel-group-items-${g.id}`} data-testid={`panel-group-items-${g.id}`} hidden={!isOpen} className="space-y-0.5">
                                {g.items.map(i => (
                                    <Link key={i.id} href={i.href} data-testid={i.testId} data-tour={i.testId}
                                        aria-current={i.id === activeItem ? "page" : undefined}
                                        className={cn("block rounded-[var(--radius-sm)] px-2.5 py-1.5 text-[13.5px]",
                                            i.id === activeItem ? "bg-[var(--sand-100)] font-semibold text-[var(--ink-900)]" : "text-[var(--ink-600)] hover:bg-[var(--sand-100)]")}>
                                        {label(i.label)}
                                    </Link>
                                ))}
                            </div>
                        </div>
                    );
                })}
                {section.savedViews.length > 0 && (
                    <div data-testid="panel-saved-views">
                        <div className="px-2 py-1 text-[10.5px] font-semibold uppercase tracking-[0.08em] text-[var(--ink-500)]">{t("pinned")}</div>
                        {section.savedViews.map(v => (
                            <Link key={v.id} href={v.href} data-testid={v.testId} className="block rounded-[var(--radius-sm)] px-2.5 py-1.5 text-[13px] text-[var(--ink-600)] hover:bg-[var(--sand-100)]">
                                {label(v.label)}
                            </Link>
                        ))}
                    </div>
                )}
            </div>
            {section.statusCard === "booksLocked" && (
                <div data-testid="nav-status-card" className="m-3 shrink-0 rounded-[var(--radius)] border border-border bg-[var(--sand-100)] p-3 text-[12px] text-[var(--ink-700)]">
                    {counts.booksLockedThrough ? t("booksLockedThrough", { date: fmtDate(counts.booksLockedThrough, locale) }) : t("booksNotLocked")}
                </div>
            )}
            {section.statusCard === "chequesToDeposit" && counts.chequesToDeposit !== null && (
                <div data-testid="nav-status-card" className="m-3 shrink-0 rounded-[var(--radius)] border border-border bg-[var(--sand-100)] p-3 text-[12px] text-[var(--ink-700)]">
                    {t("chequesToDeposit", { count: counts.chequesToDeposit })}
                </div>
            )}
        </div>
    );
}
