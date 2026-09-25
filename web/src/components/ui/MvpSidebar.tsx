"use client";

import { AlertTriangle, BookOpen, Briefcase, FileText, Home, LayoutGrid, PanelLeftClose, PanelLeftOpen, Settings, Wallet, X } from "lucide-react";
import Image from "next/image";
import { usePathname, useSearchParams } from "next/navigation";
import { useSession } from "next-auth/react";
import { useTranslations } from "next-intl";
import { useEffect, useRef, useState } from "react";
import { Link } from "@/i18n/routing";
import { cn } from "@/lib/utils";
import type { UserRole } from "@/lib/rbac";
import { useTenantFeatures } from "@/hooks/useTenantFeatures";
import { activeNav, buildNav, type RailId, type RailSection } from "@/lib/nav/navModel";
import { useLabel } from "@/lib/nav/useLabel";
import { useNavShell } from "@/components/nav/NavShellContext";
import { useNavCounts } from "@/components/nav/useNavCounts";
import { SectionPanel } from "@/components/nav/SectionPanel";

const APP_VERSION = process.env.NEXT_PUBLIC_APP_VERSION || '0.6.0.dev';

const ICONS: Record<RailId, React.ElementType> = {
    home: Home, leasing: FileText, collection: Wallet, accounting: BookOpen,
    operations: Briefcase, settings: Settings, more: LayoutGrid,
};

/**
 * The nav item to highlight for a pathname: the longest href that equals the
 * path or is a whole-segment prefix of it. (Kept verbatim: M-9.)
 */
export function activeNavHref(pathname: string, hrefs: string[]): string | null {
    const path = pathname.replace(/^\/(en|ar)(?=\/|$)/, "") || "/";
    let best: string | null = null;
    for (const href of hrefs) {
        if ((path === href || path.startsWith(`${href}/`)) && (best === null || href.length > best.length)) {
            best = href;
        }
    }
    return best;
}

function Rail({ rail, active, onRailClick, badge }: {
    rail: RailSection[]; active: RailId | null; badge: number | null;
    onRailClick: (id: RailId, e: React.MouseEvent) => void;
}) {
    const label = useLabel();
    const t = useTranslations("Navigation");
    return (
        <nav data-testid="nav-rail" data-tour="sidebar-nav" className="flex h-full w-16 flex-col items-center gap-1 border-e border-border bg-surface py-3">
            <Link href="/" className="mb-3 rounded-lg focus:outline-none focus:ring-2 focus:ring-[var(--gold-500)]/30">
                <Image src="/logo.png" alt="RentAxis" width={32} height={32} className="h-8 w-8 object-contain" priority />
            </Link>
            {rail.map(s => {
                const Icon = ICONS[s.id] ?? AlertTriangle;
                const isActive = s.id === active;
                return (
                    <Link key={s.id} href={s.href} data-rail={s.id} data-testid={`rail-${s.id}`} data-tour={s.tourId}
                        aria-current={isActive ? "true" : undefined} title={label(s.label)} aria-label={label(s.label)}
                        onClick={e => onRailClick(s.id, e)}
                        className={cn("relative flex w-14 flex-col items-center gap-0.5 rounded-[var(--radius-sm)] py-2 text-[10px] font-medium",
                            isActive ? "bg-[var(--sand-100)] text-[var(--ink-900)]" : "text-[var(--ink-600)] hover:bg-[var(--sand-100)]")}>
                        <Icon size={18} className={isActive ? "text-[var(--gold-500)]" : "text-[var(--ink-500)]"} />
                        <span aria-hidden className="max-w-full truncate">{label(s.railLabel)}</span>
                        {s.badge === "collection" && badge !== null && badge > 0 && (
                            <span aria-label={t("badgeLabel", { count: badge })}
                                className="absolute top-1 end-1.5 min-w-4 rounded-full bg-error px-1 text-center text-[9px] font-bold leading-4 text-white">
                                {badge > 99 ? "99+" : badge}
                            </span>
                        )}
                    </Link>
                );
            })}
            <div className="mt-auto max-w-full break-all px-1 text-center font-mono text-[9px] leading-tight text-[var(--ink-500)]">v{APP_VERSION}</div>
        </nav>
    );
}

/**
 * The shell (spec §1a; flyout ruling PR #363 R1):
 * - ≥ 1280 px with the panel shown: a rail click navigates to the section and
 *   the inline panel follows the page.
 * - 768–1279 px (or the panel hidden): a rail click opens that section's panel
 *   as a flyout WITHOUT navigating. The flyout stays open across navigations
 *   (pick page after page) and closes on outside click, Esc, the same rail
 *   item again, or its close button.
 * - < 768 px: the header's menu button opens a drawer (rail + panel); a rail
 *   click there swaps the panel, a page click navigates and closes it.
 */
export default function MvpSidebar() {
    const t = useTranslations("Navigation");
    const pathname = usePathname();
    const { data: session } = useSession();
    const role = session?.user?.role as UserRole | undefined;
    const { isEnabled, tenantSlug } = useTenantFeatures();
    const counts = useNavCounts(role, pathname);
    const { drawerOpen, setDrawerOpen, panelHidden, setPanelHidden, inlinePanel } = useNavShell();
    const rail = buildNav({ role, isEnabled, tenantSlug, booksLive: counts.booksLive });
    const search = useSearchParams()?.toString() ?? "";
    const active = activeNav(pathname, rail, search);
    const [picked, setPicked] = useState<RailId | null>(null);
    const [flyoutOpen, setFlyoutOpen] = useState(false);
    const flyout = flyoutOpen && !inlinePanel;
    const asideRef = useRef<HTMLElement>(null);
    const drawerRef = useRef<HTMLDivElement>(null);
    const drawerCloseRef = useRef<HTMLButtonElement>(null);

    // A navigation with no flyout open returns the panel to the page's own
    // section (state adjusted during render, React's "reset state on prop
    // change" pattern). An open flyout keeps its section: that is the point.
    const [seenPath, setSeenPath] = useState(pathname);
    if (seenPath !== pathname) {
        setSeenPath(pathname);
        if (!flyout) setPicked(null);
    }
    // The phone drawer closes on navigation; its state lives in the shell context.
    useEffect(() => { setDrawerOpen(false); }, [pathname, setDrawerOpen]);
    useEffect(() => {
        const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") { setFlyoutOpen(false); setDrawerOpen(false); } };
        window.addEventListener("keydown", onKey);
        return () => window.removeEventListener("keydown", onKey);
    }, [setDrawerOpen]);
    // Outside click closes the flyout (the rail and the flyout are inside <aside>).
    useEffect(() => {
        if (!flyout) return;
        const onDown = (e: MouseEvent) => {
            if (asideRef.current && !asideRef.current.contains(e.target as Node)) setFlyoutOpen(false);
        };
        document.addEventListener("mousedown", onDown);
        return () => document.removeEventListener("mousedown", onDown);
    }, [flyout]);
    // Drawer focus: into the drawer on open, back to the menu button on close.
    const wasDrawerOpen = useRef(false);
    useEffect(() => {
        if (drawerOpen) drawerCloseRef.current?.focus();
        else if (wasDrawerOpen.current) document.querySelector<HTMLElement>('[data-testid="header-menu"]')?.focus();
        wasDrawerOpen.current = drawerOpen;
    }, [drawerOpen]);
    const trapFocus = (e: React.KeyboardEvent) => {
        if (e.key !== "Tab" || !drawerRef.current) return;
        const items = Array.from(drawerRef.current.querySelectorAll<HTMLElement>("a[href], button:not([disabled]):not([tabindex=\"-1\"])"));
        if (items.length === 0) return;
        const first = items[0], last = items[items.length - 1];
        if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
        else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
    };

    const shownId = picked ?? active.section ?? rail[0]?.id ?? null;
    const shown = rail.find(s => s.id === shownId) ?? null;
    const onAsideRailClick = (id: RailId, e: React.MouseEvent) => {
        if (inlinePanel) { setPicked(null); return; } // navigate; the inline panel follows the page
        e.preventDefault();
        if (flyout && shownId === id) { setFlyoutOpen(false); setPicked(null); return; }
        setPicked(id);
        setFlyoutOpen(true);
    };
    const onDrawerRailClick = (id: RailId, e: React.MouseEvent) => { e.preventDefault(); setPicked(id); };
    const panelFor = (showOrg: boolean) => shown &&
        <SectionPanel key={shown.id} section={shown} activeItem={shown.id === active.section ? active.item : null} counts={counts} showOrg={showOrg} />;

    return (
        <>
            {/* ≥ 768 px: rail always; ≥ 1280 px: panel beside it unless hidden. */}
            <aside ref={asideRef} className="relative sticky top-0 z-40 hidden h-screen shrink-0 md:flex">
                <Rail rail={rail} active={active.section} onRailClick={onAsideRailClick} badge={counts.collectionBadge} />
                {/* Rendered only when it is the visible copy, so tour targets and test ids are unique. */}
                {inlinePanel && <div className="hidden h-full xl:flex">{panelFor(true)}</div>}
                {flyout && (
                    <div data-testid="nav-flyout" className="absolute top-0 start-16 z-50 flex h-full shadow-lg">
                        {/* No org block: below the inline width the header carries the one org control. */}
                        {panelFor(false)}
                        <button type="button" onClick={() => { setFlyoutOpen(false); setPicked(null); }} aria-label={t("closeMenu")}
                            data-testid="nav-flyout-close"
                            className="absolute top-2 -end-10 rounded-full border border-border bg-surface p-1.5 shadow-md cursor-pointer"><X size={14} /></button>
                    </div>
                )}
                <button type="button" onClick={() => setPanelHidden(h => !h)} aria-label={panelHidden ? t("showPanel") : t("hidePanel")}
                    className="absolute top-12 -end-3 z-50 hidden rounded-full border border-border bg-surface p-1.5 shadow-sm hover:bg-[var(--sand-100)] xl:block cursor-pointer">
                    {panelHidden ? <PanelLeftOpen size={10} className="rtl:-scale-x-100" /> : <PanelLeftClose size={10} className="rtl:-scale-x-100" />}
                </button>
            </aside>

            {/* < 768 px: drawer. */}
            {drawerOpen && (
                <div className="fixed inset-0 z-50 md:hidden" ref={drawerRef} onKeyDown={trapFocus}>
                    <button type="button" tabIndex={-1} aria-hidden onClick={() => setDrawerOpen(false)} className="absolute inset-0 bg-black/30" />
                    <div data-testid="nav-drawer" role="dialog" aria-modal="true" aria-label={t("openMenu")} className="absolute inset-y-0 start-0 flex max-w-full bg-surface shadow-xl">
                        <Rail rail={rail} active={active.section} onRailClick={onDrawerRailClick} badge={counts.collectionBadge} />
                        {panelFor(false)}
                    </div>
                    {/* On the backdrop beside the drawer (rail 64 + panel 240). */}
                    <button ref={drawerCloseRef} type="button" onClick={() => setDrawerOpen(false)} aria-label={t("closeMenu")} data-testid="nav-drawer-close"
                        className="absolute top-3 start-[316px] rounded-full bg-surface p-2 shadow-md cursor-pointer"><X size={16} /></button>
                </div>
            )}
        </>
    );
}
