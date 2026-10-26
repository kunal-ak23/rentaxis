"use client";

import type { ReactNode } from "react";
import { useTranslations } from "next-intl";
import { FileSpreadsheet, SearchX } from "lucide-react";
import { AccessDeniedState } from "@/components/ui/PageStates";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";

const COUNT = new Intl.NumberFormat("en-US");

export type ReportState = "idle" | "loading" | "error" | "empty" | "ready";

/**
 * The frame every report page shares (PACT report spec §1.5): title,
 * description, period and scope note, the export button, the page's filter
 * bar, then one of — a prompt to pick a scope before anything loads (`idle`),
 * a skeleton, a load error with retry, the empty state, or the report itself —
 * with a banner when the server cut the result short. A role that may not run
 * the report sees only the access-denied card: no filters, no export.
 */
export function ReportShell({
    title,
    description,
    period,
    scopeNote,
    allowed,
    filterBar,
    exportMenu,
    state,
    idleMessage,
    error,
    onRetry,
    truncated,
    children,
}: {
    title: string;
    description?: string;
    period?: string;
    scopeNote?: string;
    allowed: boolean;
    filterBar: ReactNode;
    exportMenu?: ReactNode;
    state: ReportState;
    idleMessage: string;
    error?: string | null;
    onRetry?: () => void;
    truncated?: { shown: number; total: number } | null;
    children?: ReactNode;
}) {
    const t = useTranslations("Reports");
    const tState = useTranslations("PageState");

    if (!allowed) return <AccessDeniedState message={t("shellAccessDenied")} />;

    return (
        <div className="min-w-0">
            <div className="flex items-start justify-between gap-3 mb-6">
                <div className="min-w-0">
                    <h1 className="text-xl font-bold text-foreground tracking-tight mb-1 flex items-center gap-2">
                        <FileSpreadsheet size={20} className="text-primary shrink-0" aria-hidden />
                        <span className="min-w-0 break-words">{title}</span>
                    </h1>
                    {description && <p className="text-xs text-muted font-medium">{description}</p>}
                    {(period || scopeNote) && (
                        <p className="text-xs text-muted mt-1 flex flex-wrap gap-x-3 gap-y-0.5">
                            {period && <span data-testid="report-period" className="tabular-nums font-semibold">{period}</span>}
                            {scopeNote && <span data-testid="report-scope-note" className="text-info">{scopeNote}</span>}
                        </p>
                    )}
                </div>
                {exportMenu && <div className="shrink-0">{exportMenu}</div>}
            </div>

            {filterBar}

            {state === "error" && <LoadErrorBanner message={error || tState("loadFailedBody")} onRetry={onRetry} />}

            {truncated && state === "ready" && (
                <p role="status" data-testid="report-truncated" className="mb-4 rounded-xl border border-warning/30 bg-warning/10 px-4 py-2 text-xs font-medium text-warning">
                    {t("tableTruncated", { shown: COUNT.format(truncated.shown), total: COUNT.format(truncated.total) })}
                </p>
            )}

            {state === "idle" && (
                <div data-testid="report-idle" className="text-center py-20 bg-background border border-dashed border-border rounded-xl flex flex-col items-center gap-2 px-4">
                    <div className="w-16 h-16 bg-surface rounded-xl flex items-center justify-center text-muted shadow-sm mb-4">
                        <FileSpreadsheet size={28} aria-hidden />
                    </div>
                    <h3 className="text-sm font-bold text-foreground max-w-md">{idleMessage}</h3>
                </div>
            )}
            {state === "loading" && (
                <div data-testid="report-loading" aria-busy="true" className="space-y-3 animate-pulse">
                    {[1, 2, 3, 4].map(i => <div key={i} className="bg-input rounded-xl h-16" />)}
                </div>
            )}
            {state === "empty" && (
                <div data-testid="report-empty" className="text-center py-24 bg-background border border-dashed border-border rounded-xl flex flex-col items-center px-4">
                    <div className="w-16 h-16 bg-surface rounded-xl flex items-center justify-center text-muted shadow-sm mb-6">
                        <SearchX size={28} aria-hidden />
                    </div>
                    <h3 className="text-sm font-bold text-foreground">{t("tableEmpty")}</h3>
                </div>
            )}
            {state === "ready" && children}
        </div>
    );
}

export default ReportShell;
