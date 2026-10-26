"use client";

import { useEffect, useRef, useState, type KeyboardEvent as ReactKeyboardEvent } from "react";
import { useTranslations } from "next-intl";
import { Download, Loader2, X } from "lucide-react";
import { saveResponse } from "@/lib/download";

export interface ExportOption {
    id: "xlsx" | "pdfEn" | "pdfAr" | "csv";
    url: string;
    /** Saved under this name only when the server's Content-Disposition names none. */
    fallbackName: string;
}

const LABEL_KEY = {
    xlsx: "exportXlsx",
    pdfEn: "exportPdfEn",
    pdfAr: "exportPdfAr",
    csv: "exportCsv",
} as const;

/** Row counts in the 422 message print with Latin digits in both languages, like the amounts. */
const COUNT = new Intl.NumberFormat("en-US");
function count(v: unknown): string {
    return typeof v === "number" ? COUNT.format(v) : String(v ?? "");
}

type Translate = ReturnType<typeof useTranslations<"Reports">>;

/** What to tell the user about a failed export, from the server's JSON where it sent some. */
async function failureMessage(res: Response, t: Translate): Promise<string> {
    let body: { code?: unknown; args?: unknown; message?: unknown } = {};
    try {
        const parsed: unknown = await res.json();
        if (parsed && typeof parsed === "object") body = parsed as typeof body;
    } catch {
        // Not JSON (a proxy error page): fall through to the generic message.
    }
    if (body.code === "report.tooManyRowsPdf" || body.code === "report.tooManyRows") {
        const args = (body.args && typeof body.args === "object" ? body.args : {}) as Record<string, unknown>;
        const key = body.code === "report.tooManyRowsPdf" ? "exportTooManyRowsPdf" : "exportTooManyRows";
        return t(key, { rows: count(args.rows), cap: count(args.cap) });
    }
    if (res.status === 429) return t("exportRateLimited");
    return typeof body.message === "string" && body.message ? body.message : t("exportFailed");
}

/**
 * The report's export button (PACT report spec §1.5): Excel in PACT's layout,
 * PDF in English or Arabic, CSV. The file is fetched and saved through
 * `saveResponse`, never opened in a tab, so a 422 ("too many rows") or 429
 * arrives here as JSON and is shown under the button instead of as a raw JSON
 * page. The button is disabled, with a spinner, while the server prepares the
 * file.
 *
 * Keyboard (WAI-ARIA menu button): opening the menu focuses its first item;
 * ArrowUp/ArrowDown (wrapping), Home and End move between items; Escape closes
 * it and Tab closes it and moves on. Escape and a choice give focus back to the
 * trigger, after a choice once the export ends, since the trigger is disabled
 * while it runs.
 */
export function ExportMenu({ options, disabled = false }: { options: ExportOption[]; disabled?: boolean }) {
    const t = useTranslations("Reports");
    const [open, setOpen] = useState(false);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const root = useRef<HTMLDivElement>(null);
    const trigger = useRef<HTMLButtonElement>(null);
    const menu = useRef<HTMLDivElement>(null);
    const refocus = useRef(false);

    // Close the menu on a click outside it, or on Escape with focus back on the trigger.
    useEffect(() => {
        if (!open) return;
        const onDown = (e: MouseEvent) => {
            if (root.current && !root.current.contains(e.target as Node)) setOpen(false);
        };
        const onKey = (e: KeyboardEvent) => {
            if (e.key !== "Escape") return;
            setOpen(false);
            trigger.current?.focus();
        };
        document.addEventListener("mousedown", onDown);
        document.addEventListener("keydown", onKey);
        return () => {
            document.removeEventListener("mousedown", onDown);
            document.removeEventListener("keydown", onKey);
        };
    }, [open]);

    // An opened menu takes focus on its first item.
    useEffect(() => {
        if (open) menu.current?.querySelector<HTMLElement>('[role="menuitem"]')?.focus();
    }, [open]);

    // Once a chosen export ends, focus returns to the trigger, unless the user has moved it elsewhere meanwhile.
    useEffect(() => {
        if (busy || !refocus.current) return;
        refocus.current = false;
        const active = document.activeElement;
        if (!active || active === document.body || root.current?.contains(active)) trigger.current?.focus();
    }, [busy]);

    function onMenuKeyDown(e: ReactKeyboardEvent<HTMLDivElement>) {
        if (e.key === "Tab") {
            setOpen(false);
            return;
        }
        const items = Array.from(menu.current?.querySelectorAll<HTMLElement>('[role="menuitem"]') ?? []);
        if (items.length === 0) return;
        const at = items.indexOf(document.activeElement as HTMLElement);
        let next: number;
        switch (e.key) {
            case "ArrowDown": next = at < 0 ? 0 : (at + 1) % items.length; break;
            case "ArrowUp": next = at <= 0 ? items.length - 1 : at - 1; break;
            case "Home": next = 0; break;
            case "End": next = items.length - 1; break;
            default: return;
        }
        e.preventDefault();
        items[next].focus();
    }

    async function run(option: ExportOption) {
        refocus.current = true;
        trigger.current?.focus();
        setOpen(false);
        setError(null);
        setBusy(true);
        try {
            const res = await fetch(option.url);
            if (res.ok) await saveResponse(res, option.fallbackName);
            else setError(await failureMessage(res, t));
        } catch {
            setError(t("exportFailed"));
        } finally {
            setBusy(false);
        }
    }

    return (
        <div ref={root} className="relative inline-block">
            <button
                ref={trigger}
                type="button"
                onClick={() => setOpen(o => !o)}
                disabled={disabled || busy}
                aria-busy={busy || undefined}
                aria-haspopup="menu"
                aria-expanded={open}
                data-testid="report-export"
                className="flex items-center gap-1.5 px-4 py-2 rounded-lg bg-surface text-foreground border border-border text-xs font-bold cursor-pointer disabled:opacity-40 disabled:cursor-not-allowed hover:bg-input transition-all focus:ring-2 focus:ring-primary/20 focus:outline-none whitespace-nowrap"
            >
                {busy ? <Loader2 size={13} className="animate-spin" aria-hidden /> : <Download size={13} aria-hidden />}
                {busy ? t("exportBusy") : t("exportMenu")}
            </button>
            {open && (
                <div
                    ref={menu}
                    role="menu"
                    aria-label={t("exportMenu")}
                    onKeyDown={onMenuKeyDown}
                    className="absolute end-0 top-full mt-1 z-30 w-56 max-w-[calc(100vw-2rem)] bg-surface border border-border rounded-xl shadow-lg py-1"
                >
                    {options.map(o => (
                        <button
                            key={o.id}
                            type="button"
                            role="menuitem"
                            tabIndex={-1}
                            onClick={() => run(o)}
                            className="block w-full text-start px-3 py-2 text-xs font-medium text-foreground hover:bg-input focus:bg-input focus:outline-none cursor-pointer"
                        >
                            {t(LABEL_KEY[o.id])}
                        </button>
                    ))}
                </div>
            )}
            {error && (
                <div
                    role="alert"
                    className="absolute end-0 top-full mt-1 z-20 w-72 max-w-[calc(100vw-2rem)] flex items-start gap-2 bg-surface border border-error/30 text-error rounded-xl shadow-lg px-3 py-2 text-xs font-medium"
                >
                    <span className="flex-1">{error}</span>
                    <button
                        type="button"
                        onClick={() => setError(null)}
                        aria-label={t("exportDismiss")}
                        className="shrink-0 cursor-pointer text-error/70 hover:text-error"
                    >
                        <X size={13} aria-hidden />
                    </button>
                </div>
            )}
        </div>
    );
}

export default ExportMenu;
