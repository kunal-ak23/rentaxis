"use client";

import { AlertTriangle, BookOpen, Briefcase, FileText, Home, LayoutGrid, PanelLeftClose, PanelLeftOpen, Settings, Wallet, X } from "lucide-react";
import Image from "next/image";
import { usePathname } from "next/navigation";
import { useSession } from "next-auth/react";
import { useTranslations } from "next-intl";
import { useEffect, useState } from "react";
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

function Rail({ rail, active, onPick, badge }: { rail: RailSection[]; active: RailId | null; onPick: (id: RailId) => void; badge: number | null }) {
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
                        aria-current={isActive ? "true" : undefined} title={label(s.label)}
                        onClick={() => onPick(s.id)}
                        className={cn("relative flex w-14 flex-col items-center gap-0.5 rounded-[var(--radius-sm)] py-2 text-[10px] font-medium",
                            isActive ? "bg-[var(--sand-100)] text-[var(--ink-900)]" : "text-[var(--ink-600)] hover:bg-[var(--sand-100)]")}>
                        <Icon size={18} className={isActive ? "text-[var(--gold-500)]" : "text-[var(--ink-500)]"} />
                        <span className="max-w-full truncate">{label(s.label)}</span>
                        {s.badge === "collection" && badge !== null && badge > 0 && (
                            <span aria-label={t("badgeLabel", { count: badge })}
                                className="absolute top-1 end-1.5 min-w-4 rounded-full bg-error px-1 text-center text-[9px] font-bold leading-4 text-white">
                                {badge > 99 ? "99+" : badge}
                            </span>
                        )}
                    </Link>
                );
            })}
            <div className="mt-auto px-1 text-center font-mono text-[9px] text-[var(--ink-500)]">v{APP_VERSION}</div>
        </nav>
    );
}

export default function MvpSidebar() {
    const t = useTranslations("Navigation");
    const pathname = usePathname();
    const { data: session } = useSession();
    const role = session?.user?.role as UserRole | undefined;
    const { isEnabled, tenantSlug } = useTenantFeatures();
    const counts = useNavCounts(role);
    const { drawerOpen, setDrawerOpen } = useNavShell();
    const rail = buildNav({ role, isEnabled, tenantSlug, booksLive: counts.booksLive });
    const active = activeNav(pathname, rail);
    const [picked, setPicked] = useState<RailId | null>(null);
    const [flyout, setFlyout] = useState(false);
    const [panelHidden, setPanelHidden] = useState(() => {
        try { return typeof window !== "undefined" && localStorage.getItem("sidebar_collapsed") === "true"; } catch { return false; }
    });

    useEffect(() => { try { localStorage.setItem("sidebar_collapsed", String(panelHidden)); } catch { /* private mode */ } }, [panelHidden]);
    // A navigation resets the picked section and closes the flyout (state
    // adjusted during render, React's "reset state on prop change" pattern)…
    const [seenPath, setSeenPath] = useState(pathname);
    if (seenPath !== pathname) {
        setSeenPath(pathname);
        setPicked(null);
        setFlyout(false);
    }
    // …and closes the phone drawer, whose state lives in the shell context.
    useEffect(() => { setDrawerOpen(false); }, [pathname, setDrawerOpen]);
    useEffect(() => {
        const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") { setFlyout(false); setDrawerOpen(false); } };
        window.addEventListener("keydown", onKey);
        return () => window.removeEventListener("keydown", onKey);
    }, [setDrawerOpen]);

    const shownId = picked ?? active.section ?? rail[0]?.id ?? null;
    const shown = rail.find(s => s.id === shownId) ?? null;
    const pick = (id: RailId) => { setPicked(id); setFlyout(true); };
    const panel = shown && <SectionPanel key={shown.id} section={shown} activeItem={shown.id === active.section ? active.item : null} counts={counts} />;

    return (
        <>
            {/* ≥ 768 px: rail always; ≥ 1280 px: panel beside it unless hidden. */}
            <aside className="relative sticky top-0 z-40 hidden h-screen shrink-0 md:flex">
                <Rail rail={rail} active={active.section} onPick={pick} badge={counts.collectionBadge} />
                <div className={cn("hidden h-full", !panelHidden && "xl:flex")}>{panel}</div>
                {flyout && (
                    <div data-testid="nav-flyout" className="absolute top-0 start-16 z-50 h-full shadow-lg xl:hidden">{panel}</div>
                )}
                <button type="button" onClick={() => setPanelHidden(h => !h)} aria-label={panelHidden ? t("showPanel") : t("hidePanel")}
                    className="absolute top-12 -end-3 z-50 hidden rounded-full border border-border bg-surface p-1.5 shadow-sm hover:bg-[var(--sand-100)] xl:block cursor-pointer">
                    {panelHidden ? <PanelLeftOpen size={10} className="rtl:-scale-x-100" /> : <PanelLeftClose size={10} className="rtl:-scale-x-100" />}
                </button>
            </aside>

            {/* < 768 px: drawer. */}
            {drawerOpen && (
                <div className="fixed inset-0 z-50 md:hidden">
                    <button type="button" aria-label={t("closeMenu")} onClick={() => setDrawerOpen(false)} className="absolute inset-0 bg-black/30" />
                    <div data-testid="nav-drawer" role="dialog" aria-modal="true" aria-label={t("openMenu")} className="absolute inset-y-0 start-0 flex max-w-full bg-surface shadow-xl">
                        <Rail rail={rail} active={active.section} onPick={id => setPicked(id)} badge={counts.collectionBadge} />
                        {panel}
                        <button type="button" onClick={() => setDrawerOpen(false)} aria-label={t("closeMenu")} className="absolute top-2 end-2 p-1 cursor-pointer"><X size={16} /></button>
                    </div>
                </div>
            )}
        </>
    );
}
