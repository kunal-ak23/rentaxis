"use client";

import { useEffect, useRef, useState } from "react";
import { useTranslations } from "next-intl";
import { ChevronDown, FileText, Keyboard, LayoutGrid, Plus, RotateCcw, X, XSquare } from "lucide-react";
import { cn } from "@/lib/utils";
import { useLabel } from "@/lib/nav/useLabel";
import { arrowStep } from "@/lib/workspace/keys";
import type { Tab, Workspace } from "@/lib/workspace/store";

/**
 * The tab strip (spec §2): a WAI-ARIA tablist between the header and the page.
 * Industry-standard behaviour, as in a browser or an IDE: click to switch,
 * middle-click or × to close, "+" for a new tab, an overflow menu listing every
 * tab. Logical CSS only, so it mirrors in Arabic. Hidden below 768 px by the host.
 */
export default function WorkspaceStrip({
    ws, notice, onSwitch, onClose, onNew, onReopen, onCloseOthers,
}: {
    ws: Workspace;
    notice: string | null;
    onSwitch: (id: string) => void;
    onClose: (id: string) => void;
    onNew: () => void;
    onReopen: () => void;
    onCloseOthers: () => void;
}) {
    const t = useTranslations("Workspace");
    const label = useLabel();
    const [menuOpen, setMenuOpen] = useState(false);
    const [helpOpen, setHelpOpen] = useState(false);
    const listRef = useRef<HTMLDivElement>(null);
    const menuRef = useRef<HTMLDivElement>(null);

    const titleOf = (tab: Tab) =>
        typeof tab.title === "string" ? tab.title : tab.title ? label(tab.title) : t("untitled");

    // Keep the active tab in view when tabs overflow.
    useEffect(() => {
        listRef.current?.querySelector<HTMLElement>('[aria-selected="true"]')?.scrollIntoView?.({ block: "nearest", inline: "nearest" });
    }, [ws.activeId, ws.tabs.length]);

    useEffect(() => {
        if (!menuOpen) return;
        const onDown = (e: MouseEvent) => { if (!menuRef.current?.contains(e.target as Node)) setMenuOpen(false); };
        const onEsc = (e: KeyboardEvent) => { if (e.key === "Escape") setMenuOpen(false); };
        document.addEventListener("mousedown", onDown);
        document.addEventListener("keydown", onEsc);
        return () => { document.removeEventListener("mousedown", onDown); document.removeEventListener("keydown", onEsc); };
    }, [menuOpen]);

    const focusTab = (id: string) => listRef.current?.querySelector<HTMLElement>(`[data-tab-id="${id}"]`)?.focus();

    const onTabKey = (e: React.KeyboardEvent, tab: Tab) => {
        const rtl = document.documentElement.dir === "rtl";
        const at = ws.tabs.findIndex(x => x.id === tab.id);
        const step = arrowStep(e.code, rtl);
        let to: Tab | undefined;
        if (step) to = ws.tabs[(at + step + ws.tabs.length) % ws.tabs.length];
        else if (e.code === "Home") to = ws.tabs[0];
        else if (e.code === "End") to = ws.tabs[ws.tabs.length - 1];
        else if (e.code === "Delete") { e.preventDefault(); onClose(tab.id); return; }
        else if (e.code === "Enter" || e.code === "Space") { e.preventDefault(); onSwitch(tab.id); return; }
        if (!to) return;
        e.preventDefault();
        onSwitch(to.id);
        requestAnimationFrame(() => focusTab(to!.id));
    };

    return (
        <div className="relative shrink-0 border-b border-border bg-surface" data-testid="workspace-strip">
            <div className="flex items-end h-10 ps-2 pe-1 gap-1">
                <div
                    ref={listRef}
                    role="tablist"
                    aria-label={t("stripLabel")}
                    className="flex items-end gap-px min-w-0 flex-1 overflow-x-auto overflow-y-hidden no-scrollbar"
                >
                    {ws.tabs.map(tab => {
                        const isActive = tab.id === ws.activeId;
                        const title = titleOf(tab);
                        const Icon = tab.kind === "record" ? FileText : LayoutGrid;
                        return (
                            <div
                                key={tab.id}
                                role="tab"
                                data-testid="workspace-tab"
                                data-tab-id={tab.id}
                                aria-selected={isActive}
                                aria-controls="workspace-panel"
                                tabIndex={isActive ? 0 : -1}
                                title={title}
                                onClick={() => onSwitch(tab.id)}
                                onAuxClick={e => { if (e.button === 1) { e.preventDefault(); onClose(tab.id); } }}
                                onKeyDown={e => onTabKey(e, tab)}
                                className={cn(
                                    "group relative flex items-center gap-1.5 h-9 min-w-[112px] max-w-[220px] flex-[0_1_200px] ps-3 pe-1.5",
                                    "rounded-t-lg border border-b-0 text-xs cursor-pointer select-none outline-none",
                                    "focus-visible:ring-2 focus-visible:ring-primary/40",
                                    isActive
                                        ? "bg-background border-border text-foreground font-semibold -mb-px h-[37px] before:absolute before:inset-x-0 before:top-0 before:h-0.5 before:rounded-t-lg before:bg-primary"
                                        : "bg-transparent border-transparent text-muted hover:bg-input/60 hover:text-foreground after:absolute after:end-0 after:top-2.5 after:bottom-2.5 after:w-px after:bg-border hover:after:hidden",
                                )}
                            >
                                <Icon size={13} className={cn("shrink-0", isActive ? "text-primary" : "opacity-70")} aria-hidden />
                                <span className="truncate flex-1"><bdi>{title}</bdi></span>
                                <button
                                    type="button"
                                    tabIndex={-1}
                                    aria-label={t("close", { title })}
                                    data-testid="workspace-tab-close"
                                    onClick={e => { e.stopPropagation(); onClose(tab.id); }}
                                    className={cn(
                                        "shrink-0 rounded p-0.5 hover:bg-input",
                                        isActive ? "opacity-70 hover:opacity-100" : "opacity-0 group-hover:opacity-70 hover:!opacity-100 focus:opacity-100",
                                    )}
                                >
                                    <X size={12} aria-hidden />
                                </button>
                            </div>
                        );
                    })}
                </div>
                <button
                    type="button"
                    onClick={onNew}
                    aria-label={t("newTab")}
                    title={t("newTab")}
                    data-testid="workspace-new-tab"
                    className="mb-1.5 p-1.5 rounded-md text-muted hover:text-foreground hover:bg-input"
                >
                    <Plus size={15} aria-hidden />
                </button>
                <div ref={menuRef} className="relative mb-1.5">
                    <button
                        type="button"
                        aria-haspopup="menu"
                        aria-expanded={menuOpen}
                        data-testid="workspace-menu"
                        onClick={() => setMenuOpen(o => !o)}
                        className="flex items-center gap-1 px-2 py-1 rounded-md text-[11px] font-semibold text-muted hover:text-foreground hover:bg-input"
                    >
                        {t("allTabs", { count: ws.tabs.length })}
                        <ChevronDown size={12} aria-hidden />
                    </button>
                    {menuOpen && (
                        <div role="menu" className="absolute end-0 top-full mt-1 z-50 w-72 rounded-xl border border-border bg-surface shadow-lg py-1 text-xs">
                            <div className="max-h-80 overflow-y-auto thinscroll">
                                {ws.tabs.map((tab, i) => (
                                    <button
                                        key={tab.id}
                                        type="button"
                                        role="menuitem"
                                        onClick={() => { onSwitch(tab.id); setMenuOpen(false); }}
                                        className={cn(
                                            "w-full flex items-center gap-2 px-3 py-1.5 text-start hover:bg-input",
                                            tab.id === ws.activeId && "font-semibold text-primary",
                                        )}
                                    >
                                        <span className="w-4 text-muted tabular-nums">{i < 8 ? i + 1 : ""}</span>
                                        <span className="truncate flex-1"><bdi>{titleOf(tab)}</bdi></span>
                                    </button>
                                ))}
                            </div>
                            <div className="border-t border-border mt-1 pt-1">
                                <MenuAction icon={RotateCcw} disabled={ws.closed.length === 0} onClick={() => { onReopen(); setMenuOpen(false); }} text={t("reopenClosed")} hint="Alt+Shift+T" />
                                <MenuAction icon={XSquare} disabled={ws.tabs.length < 2} onClick={() => { onCloseOthers(); setMenuOpen(false); }} text={t("closeOthers")} />
                                <MenuAction icon={Keyboard} onClick={() => { setHelpOpen(true); setMenuOpen(false); }} text={t("shortcuts")} />
                            </div>
                        </div>
                    )}
                </div>
            </div>
            {notice && (
                <p role="status" data-testid="workspace-notice" className="absolute end-2 top-full mt-1 z-40 rounded-lg border border-warning/40 bg-surface px-3 py-1.5 text-xs text-warning shadow">
                    {notice}
                </p>
            )}
            {helpOpen && <ShortcutHelp onClose={() => setHelpOpen(false)} />}
        </div>
    );
}

function MenuAction({ icon: Icon, text, hint, disabled, onClick }: {
    icon: React.ElementType; text: string; hint?: string; disabled?: boolean; onClick: () => void;
}) {
    return (
        <button type="button" role="menuitem" disabled={disabled} onClick={onClick}
            className="w-full flex items-center gap-2 px-3 py-1.5 text-start hover:bg-input disabled:opacity-40 disabled:hover:bg-transparent">
            <Icon size={13} className="text-muted" aria-hidden />
            <span className="flex-1">{text}</span>
            {hint && <kbd className="text-[10px] text-muted" dir="ltr">{hint}</kbd>}
        </button>
    );
}

function ShortcutHelp({ onClose }: { onClose: () => void }) {
    const t = useTranslations("Workspace");
    const rows: [string, string][] = [
        ["Alt+1 … Alt+8", t("keyGoto")],
        ["Alt+9", t("keyLast")],
        ["Alt+PgDn / Alt+PgUp", t("keyNextPrev")],
        ["Alt+W", t("keyClose")],
        ["Alt+Shift+T", t("keyReopen")],
        ["Ctrl+K", t("keySearch")],
    ];
    useEffect(() => {
        const onEsc = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
        document.addEventListener("keydown", onEsc);
        return () => document.removeEventListener("keydown", onEsc);
    }, [onClose]);
    return (
        <div className="fixed inset-0 z-[60] flex items-center justify-center bg-black/30" onClick={onClose}>
            <div role="dialog" aria-modal="true" aria-label={t("shortcuts")} data-testid="workspace-shortcuts"
                className="w-96 rounded-2xl border border-border bg-surface p-5 shadow-xl" onClick={e => e.stopPropagation()}>
                <div className="flex items-center justify-between mb-3">
                    <h2 className="text-sm font-bold">{t("shortcuts")}</h2>
                    <button type="button" onClick={onClose} className="p-1 rounded hover:bg-input" aria-label={t("close", { title: t("shortcuts") })}>
                        <X size={14} aria-hidden />
                    </button>
                </div>
                <dl className="space-y-2 text-xs">
                    {rows.map(([keys, what]) => (
                        <div key={keys} className="flex justify-between gap-4">
                            <dt className="text-muted">{what}</dt>
                            <dd><kbd className="rounded border border-border bg-input px-1.5 py-0.5 font-mono text-[11px]" dir="ltr">{keys}</kbd></dd>
                        </div>
                    ))}
                </dl>
            </div>
        </div>
    );
}
